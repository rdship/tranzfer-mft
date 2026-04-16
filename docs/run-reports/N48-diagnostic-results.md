# N48 Diagnostic Results

**Date:** 2026-04-16 06:02 UTC  
**Build:** R61 (pom says 1.0.0-R61 — no R63 banner detected)  
**Platform:** 34/35 healthy  

---

## Step 1: Banner Version

No R63 version string found in any service. All show `shared-platform-1.0.0-R61.jar` in logback config paths. The diagnostic doc expected R63 — this build may not contain the CTO's latest N48 fix.

## Step 2: FlowRuleRegistryInitializer

All 5 protocol services have **6 flows compiled**:

| Service | Flows Compiled | Status |
|---------|---------------|--------|
| sftp-service | 6 | OK |
| config-service | 6 | OK |
| ftp-service | 6 | OK |
| ftp-web-service | 6 | OK |
| gateway-service | 6 | OK |

## Step 3: FileUploadEventConsumer Bean

**ZERO OUTPUT across all 4 services.** The `FileUploadEventConsumer` bean is NOT loaded in any service.

- `mft-sftp-service` — no FileUploadEventConsumer log
- `mft-config-service` — no FileUploadEventConsumer log
- `mft-ftp-service` — no FileUploadEventConsumer log
- `mft-ftp-web-service` — no FileUploadEventConsumer log

**Root cause confirmed:** `@ConditionalOnProperty(name = "flow.rules.enabled", havingValue = "true", matchIfMissing = false)` — the bean never loads despite `FLOW_RULES_ENABLED=true` env var being set on sftp-service.

The env var `FLOW_RULES_ENABLED=true` is present (`docker exec mft-sftp-service env | grep FLOW_RULES_ENABLED` returns `true`), but it does NOT appear in `JAVA_TOOL_OPTIONS` as `-Dflow.rules.enabled=true`. Spring Boot's relaxed binding should convert `FLOW_RULES_ENABLED` → `flow.rules.enabled`, but `@ConditionalOnProperty` may evaluate before relaxed binding is fully active.

## Step 4: Upload Test

File uploaded successfully via 3rd-party SFTP client:
```
sshpass -p 'partner123' sftp -P 2222 acme-sftp@localhost <<< "put /tmp/test_edi_850.edi"
```

## Step 5: Diagnostic Results

### Flow Matching Logs

Only sftp-service logged anything:
```
[TRZVB9BGU29F] FileUploadedEvent published to RabbitMQ (backpressure queue)
```

**No "Flow matching:", "NO FLOW MATCH", "Matched flow", or "Processing file upload event" in ANY service.** The consumer never fires.

### RabbitMQ Queue State

```
file.upload.events    0 messages    32 consumers
```

32 consumers are registered (from all services via shared-platform auto-config), but the consumer METHOD (`onFileUploaded`) never executes because the `FileUploadEventConsumer` bean doesn't exist. The listener container factory creates consumers but they ACK and discard messages since there's no handler.

### Database State

| Table | Count |
|-------|-------|
| transfer_records | 1 |
| flow_executions | 0 |

**Transfer record details:**
```
track_id:     TRZVB9BGU29F
filename:     test_edi_850.edi
status:       PENDING
flow_id:      3a82d113-c5d1-4345-8d68-242b5153d045  ← MATCHED! EDI Processing Pipeline
```

**IMPORTANT:** The `flow_id` is populated! The RoutingEngine DID match the file to the "EDI Processing Pipeline" flow (priority 10, pattern `.*\.edi`). The matching works. But no `FlowExecution` record is created to actually run the flow steps.

### Active Flows in Database

| Name | Priority | Pattern | Direction |
|------|----------|---------|-----------|
| EDI Processing Pipeline | 10 | `.*\.edi` | INBOUND |
| EDI X12 to XML Conversion | 12 | `.*\.(x12\|850\|810\|856)` | INBOUND |
| Healthcare Compliance | 15 | `.*\.hl7` | INBOUND |
| Encrypted Delivery | 20 | `.*\.xml` | OUTBOUND |
| Archive & Compress | 50 | `.*\.csv` | INBOUND |
| Mailbox Distribution | 100 | `.*` | INBOUND |

### Flow Rule Registry Metrics

No service responded to `/api/rule-engine/metrics` on ports 8081-8085.

---

## Key Findings

1. **Flow matching WORKS** — `flow_id` is populated on the transfer record (matched "EDI Processing Pipeline")
2. **FileUploadEventConsumer bean NOT loaded** — `@ConditionalOnProperty` not picking up `FLOW_RULES_ENABLED` env var
3. **RabbitMQ events consumed but discarded** — 32 consumers registered, 0 messages pending, but no handler method fires
4. **The gap is between matched record and flow execution creation** — the RoutingEngine creates the record with flow_id, but the `FileUploadEventConsumer.onFileUploaded()` (which would create the FlowExecution) never runs

## Recommended Fix

The `FileUploadEventConsumer` bean must load. Options:

1. **Change `matchIfMissing = true`** on `@ConditionalOnProperty` — consumer loads by default, all services can process file events
2. **Add `-Dflow.rules.enabled=true` to sftp-service JAVA_TOOL_OPTIONS** (not common-env — breaks storage-manager/encryption-service)
3. **Fix relaxed binding** — ensure `FLOW_RULES_ENABLED` env var is resolved by `@ConditionalOnProperty` (may need Spring Boot 3.4 specific configuration)

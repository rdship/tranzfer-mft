# N48 Diagnostic Results — R63 Build

**Date:** 2026-04-16 06:34 UTC  
**Build:** shared-platform-1.0.0-R63.jar (confirmed in all 3 services)  
**Platform:** 34/35 healthy  

---

## Step 1: Banner Version — R63 CONFIRMED

All services show `shared-platform-1.0.0-R63.jar`:
- mft-sftp-service: R63
- mft-config-service: R63
- mft-onboarding-api: R63

## Step 2: FlowRuleRegistryInitializer — 6 Flows at Boot

**Improvement in R63:** All services now initialize with **6 flows at boot** (was 0 then refreshed to 6 in R61).

| Service | Init | After Refresh |
|---------|------|---------------|
| sftp-service | 6 | 6 |
| config-service | 6 | 6 |
| ftp-service | 6 | 6 |
| ftp-web-service | 6 | 6 |
| gateway-service | 0 → 6 | 6 |

## Step 3: FileUploadEventConsumer Bean — NOT LOADED

**Still zero output.** The `FileUploadEventConsumer` bean does not load in any service despite `FLOW_RULES_ENABLED=true` env var on sftp-service.

## Step 4: Upload Test — BLOCKED BY ACCOUNT LOCKOUT

The `acme-sftp` account was locked after 5 failed auth attempts (from sshpass retries during boot before SFTP was ready). Lockout duration: 15 minutes (`lockedUntil=2026-04-16T06:49:18Z`).

- `docker compose restart sftp-service` did NOT clear the lock — lockout persists in Redis or DB
- Admin API `POST /api/auth/admin/reset-all-lockouts` returned success but sftp-service lockout is in-memory (`LoginAttemptTracker`) which survives the API call since it runs on a different service
- DB shows `active=true` but the in-memory tracker holds the lock

**Resolution:** Need to wait for lockout TTL to expire (06:49 UTC) or nuke Redis to clear.

## Step 5: Diagnostic Logs — Partial (Upload Failed)

Since the upload was blocked by account lockout, no flow matching or pipeline processing occurred.

### RabbitMQ Queue State
```
file.upload.events    0 messages    32 consumers
```

### Database State
```
transfer_records: 0
flow_executions: 0
```

### Active Flows (6)
| Name | Priority | Pattern | Direction |
|------|----------|---------|-----------|
| EDI Processing Pipeline | 10 | `.*\.edi` | INBOUND |
| EDI X12 to XML Conversion | 12 | `.*\.(x12\|850\|810\|856)` | INBOUND |
| Healthcare Compliance | 15 | `.*\.hl7` | INBOUND |
| Encrypted Delivery | 20 | `.*\.xml` | OUTBOUND |
| Archive & Compress | 50 | `.*\.csv` | INBOUND |
| Mailbox Distribution | 100 | `.*` | INBOUND |

## Key Observations

1. **R63 confirmed** — all services running correct build
2. **FlowRuleRegistry improved** — initializes with 6 flows at boot (not 0→6 after refresh)
3. **FileUploadEventConsumer still not loading** — `@ConditionalOnProperty` issue persists in R63
4. **Account lockout blocks testing** — `LoginAttemptTracker` survives restarts, persists in Redis. No way to clear from outside the sftp-service JVM except waiting for TTL or flushing Redis
5. **New observation: N49** — SFTP account lockout should be clearable via admin API. Currently the lockout lives in sftp-service's `LoginAttemptTracker` (in-memory or Redis-backed) but the admin unlock API runs on onboarding-api — different JVM, doesn't clear sftp-service's tracker.

## Blocked — Need One of:
1. Wait for lockout TTL to expire (06:49 UTC — ~13 min from now)
2. Flush Redis: `docker exec mft-redis redis-cli FLUSHALL`
3. Full nuke + restart (but will hit same lockout if sshpass runs during boot)

## Recommendation for Dev Team
- **N48:** `FileUploadEventConsumer` still not loading. The `@ConditionalOnProperty` with `matchIfMissing=false` + env var `FLOW_RULES_ENABLED=true` doesn't work. Change to `matchIfMissing=true` or add `-Dflow.rules.enabled=true` to sftp-service's JAVA_TOOL_OPTIONS.
- **N49:** Add lockout reset API to sftp-service (or make `LoginAttemptTracker` Redis-backed so the admin API can clear it cross-service).

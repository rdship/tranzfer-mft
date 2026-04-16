# N33 Deep-Dive: RabbitMQ Serializer Mismatch — Full Root Cause Analysis

**Date:** 2026-04-16  
**Build:** R61 (1.0.0-R61, Spring Boot 3.4.5)  
**Severity:** P0 BLOCKER — blocks all file processing, Activity Monitor, Flow Fabric, Transfer Journey  

---

## Problem Statement

Files uploaded via SFTP/FTP never appear in Activity Monitor. `file_transfer_records` and `flow_executions` tables remain at 0 rows despite successful uploads. The entire SEDA pipeline (intake → match → process → deliver) is disconnected.

---

## Root Cause

**The SFTP service publishes `FileUploadedEvent` to RabbitMQ using JDK serialization. The config-service consumer expects JSON. The message is rejected and dropped.**

### The Message Flow (broken)

```
1. User uploads file via SFTP
   └─ SftpRoutingEventListener detects upload ✅
   
2. RoutingEngine publishes FileUploadedEvent to RabbitMQ
   └─ rabbitTemplate.convertAndSend("file-transfer.events", "file.uploaded", event)
   └─ RabbitTemplate uses SimpleMessageConverter (JDK serialization) ❌
   └─ Message arrives with contentType=application/x-java-serialized-object ❌
   
3. Config-service FileUploadEventConsumer.onFileUploaded() tries to receive
   └─ @RabbitListener(queues = "file.upload.events", containerFactory = "uploadListenerFactory")
   └─ uploadListenerFactory has Jackson2JsonMessageConverter set ✅
   └─ But incoming message is JDK-serialized, not JSON ❌
   └─ MessageConversionException: Cannot convert from [[B] to [FileUploadedEvent] ❌
   └─ Message REJECTED and DROPPED ❌
   
4. No transfer record created → Activity Monitor empty → Flow Execution empty
```

### Why the Jackson2JsonMessageConverter Bean Isn't Working

There are **THREE** duplicate `MessageConverter` beans defined:

| # | Location | Bean Name | Type |
|---|----------|-----------|------|
| 1 | `shared-platform/RabbitJsonConfig.java:26` | `jsonMessageConverter` | `Jackson2JsonMessageConverter` |
| 2 | `shared-core/SharedConfig.java:155` | `jacksonMessageConverter` | `Jackson2JsonMessageConverter` |
| 3 | `sftp-service/AccountEventConsumer.java:50` | `jsonMessageConverter` | `Jackson2JsonMessageConverter` |

**Bean #1 and #3 have the same name** (`jsonMessageConverter`). Spring resolves this by using the more specific one (#3 from sftp-service) which overrides #1 from shared-platform. This means each service may get a different converter — or none at all if the override fails silently.

**The critical issue:** The `RabbitTemplate` auto-configured by Spring Boot picks up a `MessageConverter` bean **by type** (`MessageConverter.class`). With THREE beans of the same type, Spring may:
- Pick the wrong one
- Fail to autowire due to ambiguity (no `@Primary`)
- Fall back to `SimpleMessageConverter` (JDK serialization)

**Evidence:** The published message has `contentType=application/x-java-serialized-object` — proving the `RabbitTemplate` used by `RoutingEngine` is NOT using the JSON converter.

### The Consumer Side Is Correctly Configured (But Receives Wrong Format)

`FileUploadQueueConfig.uploadListenerFactory()` correctly injects `Jackson2JsonMessageConverter`:

```java
@Bean
public SimpleRabbitListenerContainerFactory uploadListenerFactory(
        ConnectionFactory connectionFactory,
        @Autowired(required = false) Jackson2JsonMessageConverter messageConverter) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    if (messageConverter != null) factory.setMessageConverter(messageConverter);  // ✅ JSON converter set
    ...
}
```

The consumer is ready for JSON. But the publisher sends JDK-serialized bytes.

---

## Evidence

### 1. SFTP Service Publishes Successfully

```json
{
  "timestamp": "2026-04-16T03:53:59.236+0000",
  "message": "SFTP upload detected: user=acme-sftp relative=/TEST_PO_ACME.850 absolute=/data/partners/acme/TEST_PO_ACME.850 ip=172.19.0.1:59580"
}
{
  "timestamp": "2026-04-16T03:53:59.259+0000",
  "message": "[TRZMX4BCHS6C] FileUploadedEvent published to RabbitMQ (backpressure queue)"
}
```

### 2. Config-Service Receives but Rejects

```
MessageConversionException: Cannot convert from [[B] to [com.filetransfer.shared.dto.FileUploadedEvent]
  contentType=application/x-java-serialized-object    ← JDK serialization, NOT JSON
  receivedRoutingKey=file.uploaded
  receivedExchange=file-transfer.events
  consumerQueue=file.upload.events
  
Fatal message conversion error; message rejected; it will be dropped or routed to a dead letter exchange
```

### 3. Database Confirms No Processing

```sql
SELECT count(*) FROM file_transfer_records;  -- 0
SELECT count(*) FROM flow_executions;         -- 0
```

### 4. RabbitMQ Queue State

```
file.upload.events    0 messages    32 consumers
```

32 consumers are listening (from all services that include shared-platform). Messages arrive and are immediately rejected — 0 pending.

### 5. No `flow.rules.enabled` Property Set Anywhere

`FileUploadEventConsumer` has `@ConditionalOnProperty(name = "flow.rules.enabled", havingValue = "true", matchIfMissing = false)`. This property is NOT set in docker-compose.yml or any application.yml. The consumer **should not load at all** — yet the error logs show it IS loading in config-service. This suggests config-service has the property set in its own config or the condition is being bypassed.

---

## Fix Recommendations

### Option A: Fix the Publisher (Recommended — 1 line change)

Add `@Primary` to the `RabbitJsonConfig` bean so Spring Boot's auto-configured `RabbitTemplate` picks it up unambiguously:

```java
// shared/shared-platform/.../config/RabbitJsonConfig.java
@Bean
@Primary    // ← ADD THIS
public Jackson2JsonMessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
}
```

**AND** remove the duplicate beans:
- Delete `SharedConfig.jacksonMessageConverter()` (shared-core line 155)
- Delete `AccountEventConsumer.jsonMessageConverter()` (sftp-service line 50)

### Option B: Explicit Template Configuration

If Option A doesn't work (Spring Boot 3.4 may not auto-wire `MessageConverter` into `RabbitTemplate`), explicitly configure it:

```java
// shared/shared-platform/.../config/RabbitJsonConfig.java
@Bean
public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                      Jackson2JsonMessageConverter converter) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(converter);
    return template;
}
```

### Option C: RabbitTemplate Customizer (Spring Boot 3.4+ way)

```java
@Bean
public RabbitTemplateCustomizer jsonConverterCustomizer(Jackson2JsonMessageConverter converter) {
    return template -> template.setMessageConverter(converter);
}
```

### Also: Set `flow.rules.enabled=true` in docker-compose.yml

The `FileUploadEventConsumer` has `@ConditionalOnProperty(name = "flow.rules.enabled", havingValue = "true", matchIfMissing = false)`. This property must be explicitly set in `JAVA_TOOL_OPTIONS` or `application.yml` for the services that should consume file upload events (config-service, sftp-service, ftp-service, ftp-web-service).

Add to `common-env` in docker-compose.yml:
```yaml
-Dflow.rules.enabled=true
```

---

## Files Involved

| File | Role | Issue |
|------|------|-------|
| `shared/shared-platform/.../routing/RoutingEngine.java:160` | Publisher | `rabbitTemplate.convertAndSend()` uses JDK serialization |
| `shared/shared-platform/.../routing/FileUploadEventConsumer.java:32` | Consumer | `@RabbitListener` expects JSON — rejects JDK bytes |
| `shared/shared-platform/.../routing/FileUploadQueueConfig.java:59` | Factory | `uploadListenerFactory` correctly sets JSON converter |
| `shared/shared-platform/.../config/RabbitJsonConfig.java:26` | Bean | `jsonMessageConverter` — NOT picked up by RabbitTemplate |
| `shared/shared-core/.../config/SharedConfig.java:155` | Duplicate bean | `jacksonMessageConverter` — DUPLICATE, causes ambiguity |
| `sftp-service/.../messaging/AccountEventConsumer.java:50` | Duplicate bean | `jsonMessageConverter` — DUPLICATE, same name as RabbitJsonConfig |

---

## Impact

| What | Status |
|------|--------|
| File transfer records | 0 — never created |
| Flow executions | 0 — never started |
| Activity Monitor | Empty — no data to show |
| Activity Stats | 0 transfers in all periods |
| Flow Fabric dashboard | processing=0, failed=0 |
| Transfer Journey | No journeys |
| Flow execution lifecycle (restart/terminate/skip) | Cannot test — no executions exist |
| Live Activity SSE stream | No events to stream |
| Customer demo | **BLOCKED** |

---

## Verification Steps After Fix

```bash
# 1. Upload a file
sshpass -p 'partner123' sftp -oStrictHostKeyChecking=no -oUserKnownHostsFile=/dev/null \
  -P 2222 acme-sftp@localhost <<< "put /tmp/TEST_PO_ACME.850"

# 2. Check config-service logs — should see "Processing file upload event" NOT "MessageConversionException"
docker logs mft-config-service 2>&1 | grep -i "Processing file upload\|MessageConversion" | tail -5

# 3. Check database — should have rows
docker exec mft-postgres psql -U postgres -d filetransfer -c "SELECT count(*) FROM file_transfer_records;"
docker exec mft-postgres psql -U postgres -d filetransfer -c "SELECT count(*) FROM flow_executions;"

# 4. Check Activity Monitor API
curl -s http://localhost/api/activity-monitor -H "Authorization: Bearer $TOKEN" | python3 -c '
import sys,json; d=json.load(sys.stdin)
entries = d.get("content", d if isinstance(d,list) else [])
print(f"Activity entries: {len(entries)}")
'

# 5. Run full sanity validation
bash tests/sanity/run-sanity-validation.sh
```

# TranzFer MFT — Lazy-Init: Selective Fix Required

**Date:** 2026-04-14  
**Author:** QA Team  
**Priority:** P0 — platform cannot boot  

---

## What Happened

We made two wrong recommendations in sequence:

1. **Added `lazy-initialization=true` globally** → RabbitMQ beans never loaded → file pipeline dead → Activity Monitor always empty
2. **Removed `lazy-initialization=true` globally** → Kafka Fabric beans now block boot → 20/22 services stuck on `Node -1 disconnected` → platform down

Both were wrong because they treated all beans the same. The platform has two messaging systems with opposite startup needs:
- **RabbitMQ** beans (queues, consumers, listeners) **must start eagerly** — they declare infrastructure
- **Kafka Fabric** beans (FlowFabricConsumer, FlowRuleEventListener via Kafka) **must start lazily** — they block indefinitely if broker is slow

## The Fix

**Restore `lazy-initialization=true` in JAVA_TOOL_OPTIONS** (this is what was working before), then exclude only the RabbitMQ beans from lazy init:

```yaml
# docker-compose.yml — JAVA_TOOL_OPTIONS
-Dspring.main.lazy-initialization=true
```

Add to each service's `application.yml`:
```yaml
spring:
  main:
    lazy-initialization: true
  rabbitmq:
    listener:
      simple:
        auto-startup: true
```

And add `@Lazy(false)` to these specific beans (CTO already added these annotations — they work when the flag is in application.yml, not JAVA_TOOL_OPTIONS):
- `FileUploadQueueConfig`
- `FileUploadEventConsumer`
- `StepPipelineConfig`
- `FlowRuleEventListener` (RabbitMQ listener only, not the Kafka one)
- `PartnerCache`
- `PartnerCacheEvictionListener`
- `PipelineHealthController`

**OR** (simpler alternative): Set Kafka connection timeout so it doesn't block:
```yaml
# Add to JAVA_TOOL_OPTIONS
-Dspring.kafka.admin.properties.request.timeout.ms=5000
-Dspring.kafka.consumer.properties.default.api.timeout.ms=5000
```

This way, removing lazy-init globally works — Kafka tries to connect, times out in 5 seconds, and the service continues booting. Kafka reconnects in the background.

## Summary

| Approach | RabbitMQ | Kafka | Boot |
|----------|----------|-------|------|
| `lazy-init=true` (global, no exclusions) | BROKEN — queues never created | OK — never blocks | Fast but pipeline dead |
| `lazy-init=false` (global) | OK — queues created | BROKEN — blocks boot forever | Platform won't start |
| `lazy-init=true` + RabbitMQ exclusions | OK | OK | Correct approach |
| `lazy-init=false` + Kafka timeout 5s | OK | OK (reconnects in background) | Also correct |

Either of the last two rows fixes the problem. We recommend the Kafka timeout approach — it's one line in JAVA_TOOL_OPTIONS and doesn't require per-bean annotations.

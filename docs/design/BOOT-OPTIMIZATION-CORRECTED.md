# TranzFer MFT — Boot Optimization (Corrected Approach)

**Date:** 2026-04-13  
**Author:** QA & Architecture Team  
**Type:** Correction + Revised Proposal  
**Status:** Replaces previous lazy-init recommendation  

---

## What Went Wrong

We previously recommended `spring.main.lazy-initialization=true` as a global JVM flag to reduce boot time. This was set in `JAVA_TOOL_OPTIONS` in docker-compose.yml and applied to ALL beans in ALL services.

**Consequences of global lazy-init:**
1. `FileUploadQueueConfig` never loaded → RabbitMQ queues never declared → file pipeline dead
2. `FileUploadEventConsumer` never loaded → no consumers listening for upload events
3. `FlowRuleEventListener` never loaded → flow rule changes via RabbitMQ not received
4. `PipelineHealthController` never loaded → `/api/pipeline/health` returns 500
5. `PartnerCache` never loaded → partner caching not active
6. `@Lazy(false)` annotations on individual beans do NOT override the global JVM flag
7. `spring.main.lazy-initialization-excludes` was not effective because the flag is set at JVM level, not application.yml level

**Root cause:** Global lazy-init is a development convenience, not a production optimization. It breaks any bean that must be active at startup (listeners, schedulers, cache warmers, queue declarers).

---

## Corrected Approach: Remove Global Lazy-Init, Use Targeted Optimization

### Step 1: Remove the global flag (IMMEDIATE)

In `docker-compose.yml` shared JAVA_TOOL_OPTIONS, **remove**:
```
-Dspring.main.lazy-initialization=true
```

This single change fixes ALL the broken beans. Every controller, listener, cache, queue config will load normally at startup.

### Step 2: Boot speed via Hibernate tuning (ALREADY DONE, VERIFY)

These flags are already in JAVA_TOOL_OPTIONS and should remain:
```
-Dspring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false
-Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false  
-Dspring.jpa.hibernate.ddl-auto=none
-Dspring.jpa.open-in-view=false
```

These skip Hibernate's expensive metadata validation at startup. They're safe because Flyway owns the schema.

### Step 3: Selective lazy-init for heavy non-critical beans (OPTIONAL)

Instead of making everything lazy, annotate ONLY specific heavy beans that are not needed at startup:

```java
// These are safe to lazy-load — they're only used when their endpoint is first called
@Lazy
@Service
public class EdiConverterService { ... }

@Lazy  
@Service
public class BlockchainAnchorService { ... }
```

**DO NOT make lazy:**
- Anything with `@RabbitListener` or `@KafkaListener`
- Anything with `@Scheduled`
- Anything with `@EventListener(ApplicationReadyEvent.class)`
- Any `@Configuration` class that declares queues, exchanges, or bindings
- Any cache initializer or warmer
- Any health check endpoint controller

### Step 4: Entity scan filtering per service (BIGGEST WIN)

The real boot time bottleneck is Hibernate scanning 100+ entities per service when each service only uses 5-15. This is what makes services take 170s instead of 20s.

```java
// In each service's main application class:
@SpringBootApplication
@EntityScan(basePackages = {
    "com.filetransfer.sftp.entity",           // Service-specific entities
    "com.filetransfer.shared.entity"           // Only the shared entities this service needs
})
public class SftpServiceApplication { ... }
```

Or use `SelectiveEntityScanConfig` (which already exists but needs per-service configuration):
```yaml
# sftp-service/application.yml
entity-scan:
  include:
    - FileTransferRecord
    - FlowExecution  
    - TransferAccount
    - VirtualEntry
    - FabricCheckpoint
  exclude-all-others: true
```

**Expected impact:** 170s → 20-30s without any lazy-init.

### Step 5: AppCDS (Class Data Sharing) for JVM startup (FUTURE)

JDK 25 supports Application Class Data Sharing. Pre-compute the class archive at Docker build time:

```dockerfile
# Build phase: dump class list
RUN java -XX:DumpLoadedClassList=/app/classes.lst -jar app.jar --spring.main.lazy-initialization=true --server.port=0 &
# Wait for startup then kill
RUN sleep 30 && kill %1 || true
# Create shared archive from class list  
RUN java -XX:SharedArchiveFile=/app/app.jsa -XX:SharedClassListFile=/app/classes.lst -Xshare:dump

# Runtime: use the pre-computed archive
ENTRYPOINT ["java", "-XX:SharedArchiveFile=/app/app.jsa", "-Xshare:on", "-jar", "app.jar"]
```

**Expected impact:** Additional 3-5s reduction per service.

---

## What NOT to Do

| Approach | Why It's Wrong |
|----------|---------------|
| `spring.main.lazy-initialization=true` as global JVM flag | Breaks listeners, schedulers, queue declarers, cache warmers |
| `@Lazy(false)` to override global lazy-init | Does NOT work — global flag takes precedence |
| `lazy-initialization-excludes` in application.yml | Does NOT work when flag is in JAVA_TOOL_OPTIONS |
| Removing Flyway and using `ddl-auto=update` | Dangerous — auto DDL can drop columns, lose data |
| Reducing Hikari pool to 1 connection | Breaks under any concurrent load |

---

## Expected Boot Times After Corrected Approach

| Service | Current | After Step 1 (remove lazy) | After Step 4 (entity filter) |
|---------|---------|---------------------------|------------------------------|
| onboarding-api | 209s | 209s (already fast path) | ~25s |
| config-service | 20s | 20s | ~15s |
| sftp-service | 180s | 180s | ~25s |
| encryption-service | 180s | 180s | ~25s |
| ai-engine | 186s | 186s | ~25s |
| All others | 170-200s | 170-200s | ~20-30s |

**Step 1 alone doesn't speed up boot** — it just stops breaking things. The real speed comes from Step 4 (entity scan filtering).

---

## Summary

**Remove `spring.main.lazy-initialization=true` from JAVA_TOOL_OPTIONS immediately.** It causes more problems than it solves. The boot speed win comes from entity scan filtering, not lazy-init. Every second saved by lazy-init is paid back tenfold in debugging broken listeners and missing controllers.

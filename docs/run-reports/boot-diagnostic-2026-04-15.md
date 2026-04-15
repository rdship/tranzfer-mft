# TranzFer MFT — Boot Diagnostic Report

**Date:** 2026-04-15  
**Build:** Latest main (entity restructure + debug logging + Kafka timeouts)  

---

## Platform State

34/35 containers running, but **onboarding-api crashes on every boot** with:

```
SchemaHealthController required a bean of type 'SchemaHealthIndicator' that could not be found.
```

Other services show "healthy" (healthcheck passes on actuator) but many never print "Started Application" — they're in a zombie state where the actuator responds but the app context didn't fully initialize.

---

## Root Cause: SchemaHealthIndicator Bean Not Found

**Every boot attempt of onboarding-api follows this pattern:**
```
00:44:00  Starting OnboardingApiApplication
00:44:44  Hibernate dialect warning (entity scan starts)
00:45:49  SEDA stages started
00:45:56  FlowFabricBridge initialized (Kafka connected)
00:46:03  Onboarding-Pool - Start completed (HikariPool ready)
00:46:04  Exception encountered during context initialization — cancelling refresh
00:46:05  APPLICATION FAILED TO START
          → SchemaHealthController requires SchemaHealthIndicator bean
00:46:10  Restart attempt 2... same result
00:46:45  Restart attempt 3... freezes at HikariPool (no more crash, no more boot)
```

**The CTO's previous fix (making SchemaHealthController's dependency optional) is NOT in this build.** The error message is identical to what we reported in N20. Either:
1. The fix wasn't committed to the branch we pulled
2. The fix was overwritten by the 83-file entity restructure push
3. The fix uses `@Autowired(required = false)` but the constructor injection still requires it

---

## Per-Service Status

### Booted Successfully (2 services)
| Service | Boot Time | Notes |
|---------|-----------|-------|
| dmz-proxy | 27.7s | No DB, no Hibernate |
| edi-converter | 32.1s | No DB, no Hibernate |

### Healthy But Never Printed "Started" (14 services)
These pass the actuator healthcheck but the Spring context may not be fully initialized:
- config-service (6,414 debug lines)
- sftp-service (6,491 lines)
- encryption-service (6,294 lines)
- gateway-service (6,294 lines)
- forwarder-service (6,309 lines)
- screening-service (6,439 lines)
- platform-sentinel (6,621 lines)
- as2-service (6,386 lines)
- storage-manager (6,507 lines)
- keystore-manager (205 lines)
- license-service (204 lines)
- ftp-service (13,006 lines)
- ftp-web-service (26,102 lines)
- ai-engine (15,440 lines)

### Crashing (2 services)
| Service | Error | Attempts |
|---------|-------|----------|
| onboarding-api | SchemaHealthIndicator bean not found | 3 (crashes twice, freezes on third) |
| notification-service | Same SchemaHealthIndicator error | Restart loop (64,328 debug lines) |

### Slow Due to Debug (1 service)
- analytics-service (19,513 lines) — still processing

---

## Debug Logging Impact

CTO added these flags for diagnostics:
```
-Dlogging.level.org.springframework.orm.jpa=DEBUG
-Dlogging.level.org.springframework.data.repository.config=DEBUG
-Dlogging.level.org.hibernate.boot.model.internal=DEBUG
```

**Impact:** Each entity generates ~100 debug log lines (property binding, column binding, converter lookup, collection mapping). With 63 entities, that's 6,000-26,000 lines per service. This slows boot from 3-5 minutes to 10-30 minutes because log lines are flushed synchronously to stdout.

**Recommendation:** Remove debug logging after collecting the diagnostic data. The entity binding is working correctly — the hang is caused by the SchemaHealthIndicator missing bean, not by Hibernate.

---

## Request to Dev Team

### Immediate Fix Required

**Fix SchemaHealthIndicator bean registration.** This is the ONLY blocker preventing all services from booting. The fix is one of:

1. Add `@Component` or `@Service` to `SchemaHealthIndicator` class
2. Change `SchemaHealthController` constructor to use `@Autowired(required = false)`:
```java
public SchemaHealthController(@Autowired(required = false) SchemaHealthIndicator indicator) {
    this.indicator = indicator;
}
```
3. Add `@ConditionalOnBean(SchemaHealthIndicator.class)` to `SchemaHealthController`

### Boot Speed Recommendations

Once SchemaHealthIndicator is fixed, services will boot. For faster boot:

1. **Remove debug logging flags** — saves 5-25 minutes per service
2. **Entity scan filtering per service** — each service scans all 63 entities but only uses 5-15. Use `@EntityScan` with specific subpackages per service:
```java
// sftp-service only needs these
@EntityScan(basePackages = {
    "com.filetransfer.shared.entity.core",
    "com.filetransfer.shared.entity.transfer",
    "com.filetransfer.shared.entity.vfs"
})
```
3. **Keep Hibernate fast-boot flags** — they're present and working:
```
-Dspring.jpa.hibernate.ddl-auto=none
-Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false
-Dspring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false
```
4. **Keep Kafka timeout flags** — they prevent Kafka from blocking boot:
```
-Dspring.kafka.admin.properties.request.timeout.ms=5000
-Dspring.kafka.consumer.properties.default.api.timeout.ms=5000
```
5. **Keep `start_period: 240s`** — gives services enough time to boot with 63 entities

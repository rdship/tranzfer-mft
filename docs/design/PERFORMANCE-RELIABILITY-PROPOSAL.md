# TranzFer MFT — Performance & Reliability Proposal

**Date:** 2026-04-13  
**Author:** QA & Architecture Team  
**Audience:** CTO Roshan Dubey, Development Team  
**Type:** Performance Analysis + Reliability Improvements  
**Status:** Open for Review  
**Priority:** Critical — boot times 3+ minutes, file pipeline disconnected  

---

## 1. Current Boot Times (Measured)

### Service Boot Duration — Full Cold Start

| Service | Boot Time | Category |
|---------|-----------|----------|
| **analytics-service** | 20.4s | FAST |
| **config-service** | 20.9s | FAST |
| **license-service** | 21.8s | FAST |
| **onboarding-api** | 22.1s | FAST |
| **edi-converter** | 25.6s | FAST (no DB) |
| **dmz-proxy** | 27.2s | FAST (no DB) |
| **screening-service** | 170.2s | SLOW |
| **forwarder-service** | 173.5s | SLOW |
| **ftp-web-service** | 177.4s | SLOW |
| **storage-manager** | 184.3s | SLOW |
| **notification-service** | 185.7s | SLOW |
| **encryption-service** | 191.7s | SLOW |
| **platform-sentinel** | 192.1s | SLOW |
| **ai-engine** | 195.8s | SLOW |
| **as2-service** | 195.6s | SLOW |
| **keystore-manager** | 199.5s | SLOW |

**6 services boot in <30s. 10 services take 170–200s (nearly 3.5 minutes).**

### Why Some Are Fast, Others Slow

The 6 fast services (analytics, config, license, onboarding, edi, dmz) had their boot optimized in CTO's Phase 1 — they use:
- `hibernate.boot.allow-jdbc-metadata-access: false`
- `hibernate.temp.use_jdbc_metadata_defaults: false`
- `ddl-auto: none` (skip validation against DB)

The 10 slow services are still doing full Hibernate entity scanning with 100+ shared entities. They need the same optimizations applied.

---

## 2. Boot Time Optimization Plan

### Phase 1: Apply Fast-Boot Config to ALL Services (Expected: 170s → 20s)

The 6 fast services prove the pattern works. Apply the same JVM/Hibernate flags to the remaining 10:

```yaml
# Add to JAVA_TOOL_OPTIONS for ALL services
-Dspring.jpa.hibernate.ddl-auto=none
-Dspring.jpa.properties.hibernate.boot.allow-jdbc-metadata-access=false
-Dspring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false
```

**Expected result:** All 22 services boot in <30s instead of 170-200s.

### Phase 2: Selective Entity Scanning (Expected: 20s → 8s)

Each service currently scans ALL 100+ entities even though it only uses 5-15. Configure entity scan per service:

```java
@EntityScan(basePackages = "com.filetransfer.sftp.entity")
// Instead of scanning all shared entities
```

**Expected result:** Hibernate initialization drops from ~15s to ~3s.

### Phase 3: Class Data Sharing (Expected: 8s → 4s)

JDK 25 supports Application Class Data Sharing (AppCDS). Pre-compute the class list at build time:

```dockerfile
# In Dockerfile, after COPY app.jar
RUN java -XX:DumpLoadedClassList=/app/classes.lst -jar app.jar --spring.main.lazy-initialization=true &
RUN java -XX:SharedArchiveFile=/app/app.jsa -XX:SharedClassListFile=/app/classes.lst -Xshare:dump
# At runtime
ENTRYPOINT ["java", "-XX:SharedArchiveFile=/app/app.jsa", "-Xshare:on", "-jar", "app.jar"]
```

**Expected result:** JVM startup overhead drops from ~3s to <1s.

### Phase 4: GraalVM Native Image (Future — Expected: 4s → 0.3s)

Spring Boot 3.2 supports native image compilation. Critical path services (sftp, ftp, onboarding) could be compiled to native for sub-second startup.

### Total Expected Impact

| Phase | Boot Time | Improvement |
|-------|-----------|-------------|
| Current (slow services) | 170-200s | Baseline |
| + Fast-boot config | ~20s | **10x faster** |
| + Selective entity scan | ~8s | **25x faster** |
| + AppCDS | ~4s | **50x faster** |
| + GraalVM native | ~0.3s | **600x faster** |

---

## 3. Critical Reliability Issues

### ISSUE 1: File Upload → Routing Pipeline DISCONNECTED (CRITICAL)

**What:** Files uploaded via SFTP land on disk but never trigger the routing engine. Activity Monitor shows 0 records.

**Root Cause Chain:**
```
1. spring.main.lazy-initialization=true (set in JAVA_TOOL_OPTIONS)
2. FileUploadQueueConfig bean (gated by flow.rules.enabled=true) is LAZY
3. Bean never loaded because nothing triggers it eagerly
4. RabbitMQ queues never declared → no consumers registered
5. SFTP upload completion has no listener → RoutingEngine.onFileUploaded() never called
6. Files sit on disk forever, no flow execution, no activity record
```

**Fix Options:**
1. **Quick fix:** Add `@Lazy(false)` to `FileUploadQueueConfig` and `FileUploadEventConsumer`
2. **Better fix:** Create a `RabbitMqInitializer` that eagerly declares exchanges, queues, and bindings on `ApplicationReadyEvent`
3. **Best fix:** Use `spring.rabbitmq.listener.simple.auto-startup=true` + exclude RabbitMQ beans from lazy-init:
```yaml
spring:
  main:
    lazy-initialization: true
    lazy-initialization-excludes:
      - org.springframework.amqp.rabbit.*
      - com.filetransfer.shared.routing.FileUploadQueueConfig
      - com.filetransfer.shared.routing.FileUploadEventConsumer
```

**Impact:** Without this fix, the platform cannot transfer files. This is the #1 blocker.

### ISSUE 2: RabbitMQ Exchange Not Auto-Declared (HIGH)

**What:** `file-transfer.events` exchange must be manually created via `rabbitmqadmin`. Services fail with `NOT_FOUND - no exchange 'file-transfer.events'` and stop retrying.

**Fix:** Add exchange declaration in docker-compose RabbitMQ definitions, or create an init container:
```yaml
rabbitmq:
  environment:
    RABBITMQ_DEFAULT_EXCHANGES: file-transfer.events:topic,file-transfer.events.dlx:topic
```
Or add a `RabbitInitializer` Spring bean that declares exchanges on startup.

### ISSUE 3: Redis @Cacheable Serialization Bug (HIGH)

**What:** `FlowExecutionController.getLiveStats()` caches `ResponseEntity<Map>` in Redis. `ResponseEntity` has no default constructor, so deserialization fails with `Cannot construct instance of ResponseEntity`.

**Fix:** Cache the body, not the wrapper:
```java
// BEFORE (broken)
@Cacheable(value = "live-stats", key = "'all'")
public ResponseEntity<Map<String, Object>> getLiveStats() { ... }

// AFTER (works)
@Cacheable(value = "live-stats", key = "'all'")
public Map<String, Object> getLiveStatsData() { ... }

@GetMapping("/live-stats")
public ResponseEntity<Map<String, Object>> getLiveStats() {
    return ResponseEntity.ok(getLiveStatsData());
}
```

### ISSUE 4: CORS Not Configured for HTTPS (HIGH)

**What:** CTO switched to HTTPS-only, but `cors.allowed-origins` only includes `http://localhost:3000`. Browser sends `Origin: https://localhost` → 403.

**Current workaround:** We strip Origin at nginx. Permanent fix: add all HTTPS origins to every service's SecurityConfig, or use a shared CORS config in `shared-platform`:
```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOriginPatterns(List.of("*")); // Or specific HTTPS origins
    config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    ...
}
```

### ISSUE 5: 3 UI Pages Crash (Gateway, DMZ, Cluster) (HIGH)

**What:** `Cannot access 'It' before initialization` — Vite production build circular dependency.

**Fix:** Run `npx vite build --mode development` locally to get the real unminified error. The circular import is likely in `api/gateway.js` → `api/dmz.js` → component → back to api module. Break the cycle by lazy-importing the API module in the component.

### ISSUE 6: Flows Modal Doesn't Close on Escape (MEDIUM)

**What:** Pressing Escape on the Quick Flow / Create Flow modal has no effect. All other modals (Partners, Accounts, Users) close correctly.

**Fix:** Add `onKeyDown` handler to the Flows modal, or ensure the Modal component's Escape handler isn't being stopped by a child component's `event.stopPropagation()`.

---

## 4. Performance Benchmarks (Measured This Session)

### API Performance
| Test | Result |
|------|--------|
| 200 sequential API requests | 75 req/sec, 0 failures |
| 200 concurrent API requests | 312 req/sec, 0 failures |
| Activity Monitor latency (25 records) | 8.7ms |
| Activity Monitor latency (100 records) | 15.9ms |
| Activity Monitor with V61 materialized view | 6-35ms all filters |
| 50 parallel flow creates | 131 flows/sec |
| SFTP burst upload | 290 files/sec |

### Rule Engine Performance
| Test | Result |
|------|--------|
| Match decision (206 rules) | <3µs per file |
| Theoretical throughput | 333,000 matches/sec |
| Registry refresh (177 flows) | Every 30s, <50ms |
| Flow creation → registry | 35s (via scheduled refresh) |

### Platform Scale Limits (Current)
| Component | Current Limit | For 1B files/day |
|-----------|--------------|-------------------|
| Rule matching | 333K/sec | Sufficient |
| MatchContext DB query | 50-100/sec | Need Redis cache |
| RabbitMQ (prefetch=10) | ~100/sec | Need prefetch=50+ |
| SEDA intake (64 threads) | ~200/sec | Need virtual threads |
| Storage-manager RPC | 20 writes/sec | Need async batch |

---

## 5. Reliability Improvements

### Auto-Recovery Mechanisms Needed

| Mechanism | Status | Recommendation |
|-----------|--------|----------------|
| **RabbitMQ reconnection** | Broken — gives up after ~10 retries | Add infinite retry with exponential backoff (max 60s) |
| **Redis cache corruption** | No auto-recovery | Add `@CacheEvict` fallback when deserialization fails |
| **Flyway migration gaps** | Manual intervention needed | Add V60 for destination_account_id column; consolidate service-specific migrations into shared path |
| **SFTP home dir provisioning** | Manual `mkdir` needed | Auto-create on account creation via Docker volume or init hook |
| **JWT token refresh** | No refresh mechanism | 8h TTL helps (CTO changed from 15min) but add refresh endpoint |
| **Circuit breaker on downstream** | Not wired to UI | Wire `/actuator/circuit-breakers` to Circuit Breakers page |

### Health Check Improvements

```yaml
# Current: simple HTTP check
healthcheck:
  test: ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health"]

# Recommended: deep health including DB + Redis + RabbitMQ
healthcheck:
  test: ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health/readiness"]
  # readiness probe checks all dependencies, not just "app started"
```

### Graceful Degradation Matrix

| Service Down | Expected Behavior | Current Behavior |
|-------------|-------------------|------------------|
| Redis | Use local cache | Errors, cache fails |
| RabbitMQ | Sync fallback | Gives up, no routing |
| Storage-manager | Queue locally | 500 errors |
| Encryption-service | Skip encryption step | Flow fails |
| Screening-service | Skip screening step | Flow fails |
| AI-engine | Skip AI features | Graceful (ALLOWED) |

**Recommendation:** Add per-service circuit breaker with fallback behavior. When encryption-service is down, mark encryption steps as SKIPPED (not FAILED) and continue the flow. Alert operators but don't block the transfer.

---

## 6. Production Readiness Checklist

| Item | Status | Priority |
|------|--------|----------|
| File upload → routing pipeline works | **BROKEN** | P0 |
| RabbitMQ exchange auto-declaration | **BROKEN** | P0 |
| All services boot in <30s | **6/16 done** | P1 |
| Redis cache serialization safe | **BROKEN** | P1 |
| CORS for HTTPS | **Workaround** | P1 |
| All UI pages load without crash | **57/60** | P1 |
| Activity Monitor shows data | **BLOCKED by P0** | P1 |
| JWT token refresh | **Missing** | P2 |
| Health checks include dependencies | **Missing** | P2 |
| Graceful degradation on service failure | **Partial** | P2 |
| Flyway migrations in shared path | **Missing V60** | P2 |
| Prometheus metrics on rule engine | **Added by CTO** | Done |
| Partner cache in Redis | **Added by CTO** | Done |
| Batch writers for step snapshots | **Added by CTO** | Done |
| Table partitioning (V63) | **Added by CTO** | Done |
| Materialized view (V61) | **Added by CTO** | Done |
| Query timeout protection (V62) | **Added by CTO** | Done |

---

## 7. Summary

The platform has **excellent API performance** (312 req/sec concurrent, <35ms latency) and a **world-class rule engine** (333K matches/sec). The CTO has implemented our key proposals: partner caching, batch writers, materialized view, table partitioning, and rule engine metrics.

**The #1 issue is that files don't flow.** The SFTP upload → routing → flow execution → activity monitor pipeline is disconnected because `lazy-initialization=true` prevents RabbitMQ queue creation. Until this is fixed, the platform is a management UI without file transfer capability.

**The #2 issue is boot time.** 10 of 16 Java services take 170-200 seconds to start. Applying the same fast-boot config that makes 6 services boot in 20s would cut total platform startup from 3.5 minutes to 30 seconds.

Fix P0 (file pipeline), apply fast-boot to all services, and this platform is production-ready.

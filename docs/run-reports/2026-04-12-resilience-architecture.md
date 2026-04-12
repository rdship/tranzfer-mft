# Resilience Architecture Blueprint — Direction for the Next Stable Release

**Based on:** Comprehensive testing (stress tests, integration tests, bug audit, performance benchmarks) conducted 2026-04-12
**Purpose:** Guide the CTO on where to focus for a production-grade, self-healing release

---

## What Our Testing Revealed

We tested every microservice to its breaking point, uploaded 200+ files through the pipeline, hit every API endpoint, analyzed every container log. Here's the picture:

**What works well:**
- SFTP upload pipeline: 78 MB/s for large files, 43-68 files/sec for batches
- EDI converter: 14-32ms per conversion, 110 format paths, auto-detection
- AI engine: classifies every file with risk scoring across 8 parallel threads
- onboarding-api: handles 300+ concurrent with zero errors (strongest service)
- File detection: <10ms from upload to routing engine

**What breaks:**
- **8 of 10 services crash at exactly 80 concurrent requests** (sharp cliff, zero warning)
- config-service degrades 5.4x under load (1001ms avg at 100 concurrent, 5.10s cumulative GC pauses)
- Flow routing ignores filename patterns (every file matches the same flow)
- Flow execution NPEs on empty step configs and stays PROCESSING forever
- 8 services have broken API endpoints that return 500 at rest
- No circuit breaker protects services from their OWN overload (only from upstream failures)
- No caching on config data (every request hits the DB for data that changes hourly)

---

## Direction: 5 Areas the CTO Should Research and Address

### 1. The 80-Concurrent Cliff — Why Does Every Service Break at Exactly 80?

**Observation:** 8 services all break at exactly 80 concurrent (60 = 100% success, 80 = 12% failures). The uniformity suggests a shared infrastructure limit, not per-service bugs.

**Where to look:**
- Docker Desktop's internal proxy has connection limits — check `com.docker.backend` settings
- OS-level file descriptor limits inside containers: `ulimit -n` (could be 128 default, 80 connections × 2 FDs each = 160 needed)
- Tomcat's `server.tomcat.max-connections` (default 8192) vs `server.tomcat.accept-count` (default 100)
- The Hikari pool is NOT the cause (verified: all connections idle at rest, pool size adequate)

**What to try:**
- Run `docker exec mft-onboarding-api ulimit -n` to check FD limits
- Set `server.tomcat.max-connections=1000` and `server.tomcat.accept-count=200` explicitly
- Test with Docker Compose `ulimits: nofile: 65536` in docker-compose.yml
- Compare: does the same service break at 80 when running OUTSIDE Docker (bare metal)?

### 2. config-service Performance — The Platform's Weakest Link

**Observation:** config-service has:
- 5.10s cumulative GC pauses (highest of all services)
- 5.4x latency degradation at 100 concurrent (1001ms avg)
- Breaks at 80 concurrent while onboarding-api survives 300+

**Where to look:**
- JPA query complexity: run `EXPLAIN ANALYZE` on the `/api/flows` query. Are there N+1 fetches? Unnecessary eager loading of step configs?
- Hibernate second-level cache: is it configured? (EHCache/Caffeine + Redis)
- JVM heap: currently uncapped (grows to 835 MB). Set `-Xmx512m` and test if GC pressure drops
- GC algorithm: G1 is default. Try ZGC (`-XX:+UseZGC`) for sub-millisecond pauses
- Spring `@Cacheable` on `getActiveFlows()`, `getSecurityProfiles()`, etc. — these rarely change

**Quick win:** Add `spring.jpa.properties.hibernate.default_batch_fetch_size=20` to reduce N+1 queries. This alone can cut query count by 80%.

### 3. Flow Routing Engine — The Filename Pattern Bug

**Observation:** ALL uploaded files match "EDI Processing Pipeline" regardless of filename. Our 50 custom flows with patterns like `.*x12_850.*` were never matched.

**Where to look:**
- `FlowRuleRegistry.findMatch()` uses pre-compiled `Predicate<MatchContext>` objects
- The `CompiledFlowRule` stores a `Predicate<MatchContext> matcher` that's built at registration time
- Search for where `CompiledFlowRule` is constructed — the predicate compilation likely does NOT include `filenamePattern`
- The `MatchContext` includes the filename but the predicate probably only checks direction and protocol

**What to fix:**
- Find the flow rule compiler (likely `FlowRuleCompiler.java` or similar)
- Add filename pattern matching to the compiled predicate
- The registry should also refresh when flows are created/updated (currently static at boot)

### 4. Self-Healing — Stop Leaving Dead Executions

**Observation:** Flow executions that fail (NPE, null config) stay in `PROCESSING` status forever. The error is logged but NOT written to the DB. The admin never sees the failure.

**Where to look:**
- `FlowProcessingEngine.java` — the catch block at line ~390 sets error message but may not flush to DB
- `RoutingEngine.java` — the error log at line ~232 says "failed: null" but doesn't call `executionRepository.save()`
- Need a reaper/cleanup job: `UPDATE flow_executions SET status='TIMED_OUT' WHERE status='PROCESSING' AND started_at < now() - interval '5 min'`

**What to fix:**
- Ensure every catch block in flow execution saves the error to the DB
- Add a scheduled reaper job (every 60s) to mark stale PROCESSING as TIMED_OUT
- For optional steps (SCREEN, CHECKSUM): skip on error instead of failing the whole flow
- Show FAILED/TIMED_OUT executions prominently on the Activity Monitor dashboard

### 5. Service Boot Time — 195 Seconds is a Production Risk

**Observation:** Average JVM startup is 195 seconds (3.25 minutes). Cold boot of the full stack takes 12-15 minutes.

**Where to look:**
- Spring AOT (Ahead of Time) processing: `spring-boot-maven-plugin` with AOT enabled can cut startup 60-80%
- Class Data Sharing (CDS): JDK built-in, 30-40% improvement with minimal effort
- `spring.main.lazy-initialization=true`: free 30-50% improvement, risk of first-request latency
- The 100-entity shared-platform module: every service loads ALL entities. Modularize so each service loads only what it needs.

**What to try first (lowest effort):**
1. Add `spring.main.lazy-initialization=true` to one service, measure startup time
2. If it works, roll out to all services
3. Then invest in Spring AOT for the critical-path services (onboarding-api, config-service, sftp-service)

---

## Summary: What to Focus On for the Next Release

| Priority | Area | Expected Impact | Effort |
|---|---|---|---|
| **P0** | Fix the 80-concurrent cliff (Docker/OS limits) | 3-5x capacity increase | 1-2 days investigation |
| **P0** | config-service caching + JVM tuning | 10-50x config query throughput | 3-5 days |
| **P0** | Flow routing filename pattern matching | Unblocks ALL custom flows | 1-2 days |
| **P1** | Flow execution error persistence + reaper | Eliminates stuck-PROCESSING problem | 1-2 days |
| **P1** | Step-level null safety (SCREEN, CHECKSUM) | Stops NPE cascade failures | 1 day |
| **P2** | Service boot time (lazy-init → AOT) | 3-min boot → 30-sec boot | 1-2 weeks |
| **P2** | 8 broken endpoints (functional bugs) | 100% API coverage | 3-5 days |
| **P3** | Queue-based file processing (RabbitMQ backpressure) | Burst handling, zero file loss | 2-3 weeks |
| **P3** | Observability-driven auto-scaling | Self-healing under load | 3-4 weeks |

The CTO should start with P0 items. Once those are done, we'll re-run the full test suite to verify improvements and move to P1.

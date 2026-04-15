# TranzFer MFT — Execution Plan: P0 + P1 Gaps

**Date:** 2026-04-15  
**Starting Build:** R47  
**Goal:** Close all P0 and P1 gaps in correct dependency order

---

## Dependency Graph

```
Spring Boot 3.4 Upgrade ──────┬──> CDS (boot speed)
                               ├──> Micrometer deadlock native fix (re-enable JPA metrics)
                               ├──> jarmode=tools (extracted JARs)
                               └──> Structured logging

ZGC → G1GC ────────────────────> Memory savings (850M-1.7GB)
                               └──> Docker memory limits can stay at 768M

K8s Configmap Sync ────────────> Must happen BEFORE any K8s deployment

JWT Refresh Tokens ────────────> Independent (no blockers)

Redis Rate Limiter ────────────> Independent (Redis already deployed)

shared-platform Entity Split ──> Reduces boot time Phase 1 (entity binding)
                               └──> Depends on: nothing (can start now)

OpenTelemetry Tracing ─────────> Independent (add Java agent)

MDC Trace Correlation ─────────> Independent (add header propagation)

ComplianceEnforcementService ──> Wire into RoutingEngine
SecurityProfile Enforcement ───> Wire into server configs
6 API 500s (H15) ──────────────> Independent (per-endpoint fixes)
```

---

## Execution Order (8 Phases)

### Phase 1: Memory — ZGC → G1GC + Docker limits (30 min)

**Why first:** Frees 850M-1.7GB immediately. Reduces OOM risk. No code changes — just JVM flags.

**Changes:**
- docker-compose.yml: Replace `-XX:+UseZGC -XX:+ZGenerational` with `-XX:+UseG1GC -XX:MaxGCPauseMillis=100`
- Docker memory limits: 1024M for heavy services, 768M for lightweight
- Add `-XX:MaxMetaspaceSize=150M` (heavy) / `100M` (light) / `64M` (no-DB)
- Remove `-XX:+UseStringDeduplication` (G1 feature, not needed at small heaps)

**Validation:**
- Compile + test (JVM flags don't affect compilation)
- Boot times should NOT increase (G1 is faster than ZGC at small heaps)

**Risk:** LOW — G1GC is the JVM default. ZGC was the non-default choice.

---

### Phase 2: K8s Configmap Sync (1 hour)

**Why second:** Prevents K8s deployment disaster. Must be done while docker-compose flags are fresh in mind.

**Changes:**
- Sync ALL 20 JAVA_TOOL_OPTIONS flags from docker-compose to k8s/configmap.yaml
- Fix K8s memory requests: 768Mi → appropriate tier
- Fix startup probe: failureThreshold 30 → 60
- Add Kubernetes startup/readiness/liveness probes to all service templates
- Sync env vars: FLOW_RULES_ENABLED, PLATFORM_CONNECTORS_ENABLED per service

**Validation:**
- Diff docker-compose x-common-env against k8s/configmap.yaml — must be identical
- `kubectl apply --dry-run` if K8s cluster available

**Risk:** LOW — config only, no code changes.

---

### Phase 3: Spring Boot 3.2.3 → 3.4.x Upgrade (2-3 days)

**Why third:** Unblocks CDS, fixes N28 natively, enables extracted JARs, structured logging.

**Changes:**
- Update `spring-boot.version` in parent pom.xml
- Update all Spring Boot starter dependencies
- Update Hibernate version if needed (Spring Boot 3.4 uses Hibernate 6.5+)
- Update hibernate-enhance-maven-plugin version to match
- Fix any breaking API changes (Spring Boot 3.3/3.4 migration guide)
- Re-enable `management.metrics.data.repository.autotime.enabled=true` (deadlock fixed)
- Add `jarmode=tools` extract back to Dockerfiles
- Add CDS archive generation to Dockerfiles
- Enable structured logging: `logging.structured.format.console=ecs`

**Validation:**
- `mvn clean test` — all tests must pass
- Boot a single service locally to verify no regression
- Check Hibernate enhancement still works with new version
- Verify JPA metrics work without deadlock

**Risk:** MEDIUM — version upgrades can break things. Must test thoroughly.
Dependencies: Phase 1 (G1GC) should be done first so memory profile is stable.

---

### Phase 4: JWT Refresh Tokens (2-3 days)

**Why fourth:** Independent of Spring Boot upgrade. Critical for user experience.

**Changes:**
- Create RefreshToken entity + repository (new table)
- Add Flyway migration for refresh_tokens table
- Update AuthController: /api/auth/login returns access_token + refresh_token
- Add /api/auth/refresh endpoint: validate refresh_token → issue new access_token
- Add /api/auth/logout endpoint: invalidate refresh_token
- Access token TTL: 15 minutes (all environments)
- Refresh token TTL: 7 days (configurable)
- Store refresh tokens in database (not Redis — must survive restart)
- Add token rotation: each refresh invalidates the old refresh token
- UI: auto-refresh access token before expiry (axios interceptor)

**Validation:**
- Unit test: login → get tokens → refresh → new access token
- Unit test: expired refresh token → 401
- Unit test: revoked refresh token → 401
- Playwright: 13-minute test suite completes without JWT expiry
- Security: verify refresh token is opaque (not JWT), stored hashed

**Risk:** MEDIUM — touches auth flow. Must not break existing login.

---

### Phase 5: Redis Rate Limiter (1-2 days)

**Why fifth:** Independent. Fixes N9. Required before K8s multi-replica.

**Changes:**
- Replace ConcurrentHashMap in ApiRateLimitFilter with Redis INCR + EXPIRE
- Sliding window: key = `rate:{ip}:{minute}`, TTL = 60s
- Per-user rate limiting: key = `rate:user:{userId}:{minute}`
- Configurable limits via platform settings (not hardcoded)
- Admin reset endpoint: DELETE /api/auth/admin/rate-limits/{ip}
- Graceful degradation: if Redis unavailable, fall back to in-memory (don't block requests)

**Validation:**
- Unit test: rate limit hit → 429 Too Many Requests
- Unit test: different IPs get independent limits
- Unit test: Redis down → requests still pass (fallback)
- Integration test: 2 instances share rate limit state via Redis

**Risk:** LOW — Redis already deployed and used by other features.

---

### Phase 6: Observability — OpenTelemetry + MDC (2 days)

**Why sixth:** After Spring Boot 3.4 (better integration). Independent of other changes.

**Changes:**
- Add OpenTelemetry Java agent to Docker images
- Add `-javaagent:/otel/opentelemetry-javaagent.jar` to JAVA_TOOL_OPTIONS
- Configure OTel collector to export to Jaeger/Tempo (already partially configured)
- Add MDC `trackId` propagation:
  - RoutingEngine sets MDC when processing a file
  - HTTP clients propagate via `X-Track-Id` header
  - RabbitMQ message headers carry trackId
  - Log pattern includes `trackId=%X{trackId}`
- Configure OTel to use trackId as span attribute

**Validation:**
- Upload a file via SFTP → verify trace spans across sftp-service → routing → screening → storage
- Check Jaeger/Tempo UI shows end-to-end trace
- Check logs have trackId for the same transfer

**Risk:** LOW — additive only. Java agent instruments automatically.

---

### Phase 7: Wire Unwired Features (2-3 days)

**Why seventh:** Features are built, just need connection. Lower risk than new code.

**Changes:**

**7a. ComplianceEnforcementService → RoutingEngine:**
- RoutingEngine.routeFile(): call complianceService.enforce() before processing
- If compliance fails: set status=REJECTED, log violation, skip routing
- ComplianceController already exists in config-service for CRUD
- Wire: inject ComplianceEnforcementService into RoutingEngine with @Autowired(required=false)

**7b. SecurityProfile per-ServerInstance:**
- SftpServerConfig.ftpServer(): read securityProfileId from ServerInstance
- Load SecurityProfile from DB
- Apply: allowedCiphers, allowedMacs, allowedKex, idleTimeout, maxSessions
- Same for FtpServerConfig

**7c. Maintenance mode:**
- ServerInstance has maintenanceMode flag
- SFTP/FTP servers check on connection: if maintenance → reject with "Server is under maintenance"
- API: PATCH /api/servers/{id}/maintenance {enabled: true/false}

**Validation:**
- Test: create compliance profile with blocked file extension → upload .exe → verify REJECTED
- Test: set SecurityProfile on SFTP server → verify cipher restrictions applied
- Test: enable maintenance mode → verify connection rejected

**Risk:** MEDIUM — touches routing pipeline. Must verify file transfer still works after wiring.

---

### Phase 8: Fix H15 — 6 API Endpoints Returning 500 (1-2 days)

**Why last:** Lowest impact. These are secondary endpoints.

**Endpoints:**
1. `/api/dlq` — Dead letter queue viewer. Need RabbitMQ management API integration.
2. `/api/compliance` — Wired in Phase 7a above.
3. `/status` (dmz-proxy tunnel) — Tunnel client status. Need to check tunnel state.
4. `/api/v1/licenses` — License service init. Check LicenseClient startup order.
5. `/api/v1/analytics/dashboard` — Analytics aggregation view. Check if materialized view exists.
6. `/api/v1/screening/stats` — Screening statistics. Check quarantine_records table exists.

**Validation:**
- Each endpoint returns 200 with valid data or empty set
- No 500 errors in logs

**Risk:** LOW — individual endpoint fixes.

---

## Timeline

| Phase | Work | Duration | Cumulative |
|-------|------|----------|------------|
| 1. G1GC + Memory | Config only | 30 min | 30 min |
| 2. K8s Sync | Config only | 1 hour | 1.5 hours |
| 3. Spring Boot Upgrade | Code + test | 2-3 days | 3 days |
| 4. JWT Refresh | New feature | 2-3 days | 6 days |
| 5. Redis Rate Limiter | Replace feature | 1-2 days | 8 days |
| 6. Observability | Additive | 2 days | 10 days |
| 7. Wire Features | Connect existing | 2-3 days | 13 days |
| 8. Fix 500s | Bug fixes | 1-2 days | 15 days |

**Total: ~15 working days for all P0 + P1 gaps.**

---

## Rules of Engagement

1. **One phase at a time.** Complete and validate before starting next.
2. **Each phase = one version bump.** R48, R49, R50...
3. **No shortcuts.** Every change validated end-to-end.
4. **Tester validates each phase** before we proceed.
5. **If a phase breaks something, fix it before moving on.**
6. **Spring Boot upgrade gets the most time and testing.** It's the riskiest phase.

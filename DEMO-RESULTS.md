# TranzFer MFT — Demo Test Results

> Run: **2026-04-11** · Full stack (`demo-all.sh --full`) · macOS Darwin 25.2.0 · M-series · 23 GB RAM
> Tester: akgitbee (automated demo run via Claude Code)

---

## Summary

| Category | Count |
|----------|-------|
| Bugs found (blocking) | 1 |
| Bugs found (non-blocking / workaround applied) | 5 |
| Scripts fixed & pushed | 6 |
| Demo scripts passing | demo-all ✅  demo-edi ✅  demo-traffic ✅ |

---

## Bugs Found

### BUG-1 — Activity Monitor page crashes with HTTP 500 ❌ BLOCKING

**Page:** `http://localhost:3000/operations/activity`
**API:** `GET /api/activity-monitor`
**Error:** `could not determine data type of parameter $1` (PostgreSQL)

**Root cause:**
`FileTransferRecordRepository.searchForActivityMonitor` uses JPQL with the pattern:
```
WHERE (:trackId IS NULL OR r.trackId = :trackId)
AND   (:filename IS NULL OR LOWER(r.originalFilename) LIKE LOWER(CONCAT('%', :filename, '%')))
AND   (:status IS NULL OR r.status = :status)
AND   (:sourceUsername IS NULL OR sa.username = :sourceUsername)
AND   (:protocol IS NULL OR sa.protocol = :protocol)
```

Hibernate 6 binds null parameters as `Types.NULL` (untyped). PostgreSQL cannot infer the type
of `$1` in `$1 IS NULL OR track_id = $1` when all params are null (default page load, no filters).
This breaks the Activity Monitor on every cold boot — it is **never reachable** without filters.

**File:** `shared/shared-platform/src/main/java/com/filetransfer/shared/repository/FileTransferRecordRepository.java`

**Fix required:** Rewrite `searchForActivityMonitor` to use `JpaSpecificationExecutor<FileTransferRecord>`.
Build a dynamic `Specification` in `ActivityMonitorController` — only add a predicate when the
parameter is non-null. This eliminates all `? IS NULL` bindings entirely.

```java
// Repository: extend JpaSpecificationExecutor<FileTransferRecord>
// Controller: build spec dynamically
Specification<FileTransferRecord> spec = Specification.where(null);
if (trackId != null)
    spec = spec.and((r,q,cb) -> cb.equal(r.get("trackId"), trackId));
if (filename != null)
    spec = spec.and((r,q,cb) -> cb.like(cb.lower(r.get("originalFilename")),
                                         "%" + filename.toLowerCase() + "%"));
if (status != null)
    spec = spec.and((r,q,cb) -> cb.equal(r.get("status"), status));
if (sourceUsername != null)
    spec = spec.and((r,q,cb) -> cb.equal(
        r.join("folderMapping").join("sourceAccount").get("username"), sourceUsername));
if (protocol != null)
    spec = spec.and((r,q,cb) -> cb.equal(
        r.join("folderMapping").join("sourceAccount").get("protocol"), protocol));
return transferRepo.findAll(spec, pageRequest).map(r -> toEntry(...));
```

**Impact:** Activity Monitor (150 transfer records), Transfer Journey, and Live Activity all depend
on the same service — `/operations/activity` is completely broken on every fresh demo boot.

---

### BUG-2 — V42 `CREATE INDEX CONCURRENTLY` kills services on cold boot ⚠️ FIXED IN REPO

**Affected services:** onboarding-api, config-service, gateway-service, license-service, platform-sentinel

**Root cause:** `V42__performance_indexes.sql` uses `CREATE INDEX CONCURRENTLY` which cannot run
inside a Flyway transaction. PostgreSQL's `statement_timeout=30000` (30s) cancels it mid-run.
Services that depend on those indexes crash on startup.

**Fix applied:** `docker-compose.yml` line 162 — changed `statement_timeout=30000` → `statement_timeout=0`

**Files changed:** `docker-compose.yml`

---

### BUG-3 — V54 sentinel tables never applied by Flyway ⚠️ FIXED IN REPO

**Affected service:** platform-sentinel

**Root cause:** Database starts at version V999 (write-intents migration). Flyway skips all
migrations with version < 999, so `V54__sentinel_tables.sql` is never applied.
`sentinel_findings`, `sentinel_health_scores`, `sentinel_rules`, `sentinel_correlation_groups`
tables are missing — platform-sentinel crashes at startup.

**Fix applied:**
- `scripts/demo-start-full.sh` now pre-applies V54 manually before Phase 3
- `V54__sentinel_tables.sql` — added missing `builtin BOOLEAN NOT NULL DEFAULT false` column
  to `sentinel_rules` (service code inserts/queries this column, DDL didn't have it → crash)

**Files changed:** `scripts/demo-start-full.sh`, `platform-sentinel/src/main/resources/db/migration/V54__sentinel_tables.sql`

---

### BUG-4 — `edi-converter` reports `unhealthy` despite being UP ⚠️ FIXED IN REPO

**Root cause:** The shared Docker Compose `*healthcheck` anchor checks
`/actuator/health/liveness` but `edi-converter` only exposes `/actuator/health`.
Health check always fails → container stays `unhealthy` → dependent services won't start.

**Fix applied:** Shared healthcheck anchor updated to fall back:
```yaml
test: ["CMD-SHELL", "curl -sf http://localhost:${SERVER_PORT:-8080}/actuator/health/liveness
  || curl -sf http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1"]
```

**Files changed:** `docker-compose.yml`

**Note for dev team:** Check all services — any service that doesn't implement the `/liveness`
actuator endpoint will have the same problem. Either add `management.endpoint.health.probes.enabled=true`
to each service's `application.yml`, or keep the fallback healthcheck.

---

### BUG-5 — `demo-all.sh` exits with error on macOS (Bash 3.2) ⚠️ FIXED IN REPO

**Root cause:** `${MODE^^}` uppercase expansion is a Bash 4+ feature. macOS ships Bash 3.2.
Script exits with `bad substitution` at the final summary print.

**Fix applied:** `$(echo "$MODE" | tr '[:lower:]' '[:upper:]')`

**Files changed:** `scripts/demo-all.sh`

---

### BUG-6 — `demo-edi.sh` preflight reports edi-converter unreachable ⚠️ FIXED IN REPO

**Root cause:** Same `/actuator/health/liveness` issue as BUG-4. Preflight check only tried
the liveness endpoint, which edi-converter doesn't serve.

**Fix applied:** Added `|| curl -sf "${EDI_URL}/actuator/health"` fallback in the preflight.

**Files changed:** `scripts/demo-edi.sh`

---

## Demo Script Results

### `./scripts/demo-all.sh --full`

| Phase | Result | Notes |
|-------|--------|-------|
| Phase 1 — Infrastructure | ✅ | postgres, redis, rabbitmq, redpanda, minio all healthy |
| Phase 2 — Core services | ✅ | All 20+ services came up |
| Phase 3 — Platform services | ✅ | sentinel, analytics, AI engine healthy after V54 fix |
| Phase 4 — Demo data | ✅ | Onboarding completed; 150 transfer records seeded |
| Final summary print | ✅ | Fixed Bash 3.2 incompatibility |

Total containers running: **41**
Total memory used: ~14.5 GB

---

### `./scripts/demo-edi.sh`

```
Results:  13 passed   0 failed   0 skipped   (13 total)
All EDI conversion tests passed.
```

| Test | Result |
|------|--------|
| X12 850 Purchase Order — detect + convert + BEG segment | ✅ |
| X12 810 Invoice — detect + convert + BIG segment | ✅ |
| EDIFACT ORDERS — detect + convert + BGM segment | ✅ |
| HL7 ADT^A01 — detect + convert + PID segment + patient DOE | ✅ |

---

### `./scripts/demo-traffic.sh`

| Entity | Count |
|--------|-------|
| file_transfer_records (demo) | 150 |
| file_transfer_records — MOVED_TO_SENT | 115 |
| file_transfer_records — FAILED | 24 |
| file_transfer_records — DOWNLOADED (in-flight) | 11 |
| flow_executions (demo) | 150 |
| fabric_checkpoints (demo) | 610 |
| stuck fabric_checkpoints | 3 |
| fabric_instances (demo) | 6 (4 healthy + 2 dead) |
| sentinel_findings (demo) | 12 |
| sentinel_health_scores (demo) | 7 |

---

## Per-Page Checklist

### 1. Login & Dashboard — `http://localhost:3000`
- [x] Login screen loads
- [x] Login succeeds with `admin@filetransfer.local` / `Tr@nzFer2026!`
- [x] Redirects to `/operations` after login
- [ ] Dashboard numbers — **not verified** (depends on activity monitor data loading)

### 6. Activity Monitor — `/operations/activity`
- [ ] ❌ **BROKEN — HTTP 500 on page load** (see BUG-1)
- [ ] Filter by status — not testable until BUG-1 fixed
- [ ] Stuck only filter — not testable until BUG-1 fixed

**Fix owner:** Backend team — `FileTransferRecordRepository.searchForActivityMonitor`
**Priority:** HIGH — this is the primary transfer history view

### 22. EDI Translation — `/edi`
- [x] `./scripts/demo-edi.sh` — 13/13 tests passed ✅
- [x] X12 850 → JSON ✅
- [x] X12 810 → JSON ✅
- [x] EDIFACT ORDERS → JSON ✅
- [x] HL7 ADT^A01 → JSON ✅

### Flow Fabric — `/operations/fabric`
- [x] Seeded: 610 checkpoints, 3 stuck, 6 instances, latency data ✅

### Platform Sentinel — `/operations/sentinel`  (after V54 fix)
- [x] Seeded: 12 findings + 7 health score snapshots ✅

---

## Resource Snapshots

### Snapshot — boot complete (full stack)

```
Containers: 41 · Total memory used: ~14.5 GB
Hottest by CPU: mft-rabbitmq (33%), mft-screening-service (5%), mft-ftp-service (6%)
Hottest by MEM: mft-config-service (736 MB), mft-ftp-web-service (713 MB), mft-screening-service (699 MB)
```

---

## Action Items for Dev Team

| # | File | Fix needed | Priority |
|---|------|-----------|----------|
| 1 | `shared/shared-platform/.../FileTransferRecordRepository.java` | Replace JPQL `searchForActivityMonitor` with Specification API (see BUG-1 above for code) | HIGH |
| 2 | All `application.yml` files | Add `management.endpoint.health.probes.enabled=true` OR verify `/actuator/health/liveness` is exposed on every service | MEDIUM |
| 3 | Flyway V42 | Replace `CREATE INDEX CONCURRENTLY` with plain `CREATE INDEX` — CONCURRENTLY cannot run in a Flyway-managed transaction | MEDIUM |
| 4 | Flyway V54/V999 | Resolve migration ordering — V999 "write-intents" sentinel blocks all lower-numbered migrations from ever running on a fresh DB | HIGH |

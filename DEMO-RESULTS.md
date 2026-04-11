# TranzFer MFT — Demo Test Results

> Run: **2026-04-11** · Full stack (`demo-all.sh --full`) · macOS Darwin 25.2.0 · M-series · 23 GB RAM
> Tester: akgitbee (automated demo run via Claude Code)

---

## Fix Status — R11 through R15 (2026-04-11)

After the bug report below, Roshan ran an autonomous 2-hour fix sprint. All
of the known bugs plus a deep silent-failure sweep were addressed in five
commits on main:

| Round | Commit | What changed |
|-------|--------|---|
| R11 | `4b16e58` | BUG-1 fix (JpaSpecification); fix CRITICAL silent failures on Analytics/Predictions/Observatory/TwoFactor; fix HIGH swallowed errors on Flows/Screening/ProxyIntelligence; PAGE_SERVICE_MAP for 7 ungated pages; remove Rule-13 violating disabled button on Partners; add 3 missing ai-engine ThreatIntelligence endpoints and 2 missing ProxyIntelligence endpoints |
| R12 | `fb9a46e` | Add 3 missing EdiMapTraining endpoints (/samples, /sessions/{id}, /maps/{id}); add POST /api/v1/convert/maps/{mapId}/test to edi-converter; fix ediTraining correction UI paths (/sessions/{id}/correct); remove dead getApprovalsForTrack helper |
| R13 | `989223e` | Global QueryCache + MutationCache onError handlers at QueryClient root; strip 26 `.catch(() => [])` silent swallows across 13 pages; replace 5 Compliance.jsx catch-swallows with per-query onError toasts |
| R14 | `b208c3c` | Tenants.jsx quota progress bars (wires the /usage endpoint); final silent-swallow cleanup on Edi/Screening/Partnerships |
| R15 | `72619e0` | Per-page ErrorBoundary isolation on every route (lazy and eager) with PageCrashCard; ErrorBoundary render-prop fallback support |

**Verification:**
- `mvn test` for shared-platform (293), onboarding-api (65), ai-engine (215), edi-converter (554) — **1,127 tests passing**
- `vite build` clean after each round
- Live smoke test: edi-converter readiness probe + `/api/v1/convert/maps` both still 200 OK

**BUG-1 status:** fixed in R11 (`4b16e58`). `ActivityMonitorController` now builds a dynamic `Specification<FileTransferRecord>` in the controller, which means null filter params contribute zero predicates instead of producing untyped `$1 IS NULL` bindings. The repository still has its `searchForActivityMonitor` JPQL removed and is marked `extends JpaSpecificationExecutor<FileTransferRecord>` instead. Activity Monitor on `/operations/activity` should now render the 150 seeded transfers on default (unfiltered) page load.

---


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

## Resource snapshots

### Snapshot — baseline (full) — 14:16:17

```
Containers: 39 · Total memory used: 13832 MB (~13.5 GB)
```

```
NAME                       CPU %     MEM USAGE / LIMIT     MEM %     NET I/O
mft-api-gateway            0.00%     9.074MiB / 23.43GiB   0.04%     96.3kB / 123kB
mft-ftp-web-ui             0.00%     7.809MiB / 23.43GiB   0.03%     13.3kB / 126B
mft-ui-service             0.00%     8.383MiB / 23.43GiB   0.03%     79.5kB / 345kB
mft-dmz-proxy-internal     0.24%     244.5MiB / 23.43GiB   1.02%     285kB / 299kB
mft-partner-portal         0.00%     7.793MiB / 23.43GiB   0.03%     14.2kB / 126B
mft-grafana                0.01%     61.78MiB / 23.43GiB   0.26%     346kB / 12.9kB
mft-promtail               0.55%     58.3MiB / 23.43GiB    0.24%     102kB / 920kB
mft-platform-sentinel      0.30%     601.6MiB / 23.43GiB   2.51%     284kB / 309kB
mft-notification-service   0.30%     672.3MiB / 23.43GiB   2.80%     415kB / 487kB
mft-ai-engine              23.07%    603.3MiB / 23.43GiB   2.51%     852kB / 6.39MB
mft-ftp-service-2          0.19%     529.7MiB / 23.43GiB   2.21%     174kB / 224kB
mft-prometheus             0.53%     60.54MiB / 23.43GiB   0.25%     11.8MB / 1.07MB
mft-ftp-service-3          0.25%     641.4MiB / 23.43GiB   2.67%     175kB / 227kB
mft-ftp-service            0.51%     582.9MiB / 23.43GiB   2.43%     323kB / 394kB
mft-sftp-service-2         0.49%     567.8MiB / 23.43GiB   2.37%     204kB / 251kB
mft-screening-service      1.40%     493.1MiB / 23.43GiB   2.05%     7.36MB / 5.19MB
mft-config-service         1.65%     605MiB / 23.43GiB     2.52%     1.87MB / 1.87MB
mft-ftp-web-service        0.44%     595.5MiB / 23.43GiB   2.48%     311kB / 360kB
mft-license-service        0.34%     563.4MiB / 23.43GiB   2.35%     298kB / 4.36MB
mft-as2-service            0.43%     526MiB / 23.43GiB     2.19%     441kB / 310kB
mft-keystore-manager       0.78%     640.2MiB / 23.43GiB   2.67%     356kB / 503kB
mft-edi-converter          0.27%     227.6MiB / 23.43GiB   0.95%     205kB / 125kB
mft-encryption-service     0.55%     651.2MiB / 23.43GiB   2.71%     360kB / 420kB
mft-storage-manager        2.57%     553.3MiB / 23.43GiB   2.31%     382kB / 347kB
mft-sftp-service           1.40%     527.5MiB / 23.43GiB   2.20%     301kB / 338kB
mft-sftp-service-3         0.51%     615.2MiB / 23.43GiB   2.56%     178kB / 226kB
mft-ftp-web-service-2      0.34%     469.3MiB / 23.43GiB   1.96%     139kB / 148kB
mft-onboarding-api         3.21%     730.8MiB / 23.43GiB   3.05%     2.05MB / 2.22MB
mft-alertmanager           0.07%     21.15MiB / 23.43GiB   0.09%     222kB / 18.3kB
mft-gateway-service        0.40%     558.5MiB / 23.43GiB   2.33%     327kB / 302kB
mft-forwarder-service      2.79%     578.4MiB / 23.43GiB   2.41%     272kB / 307kB
mft-minio                  0.05%     84.15MiB / 23.43GiB   0.35%     25.2kB / 7.96kB
mft-loki                   0.75%     111MiB / 23.43GiB     0.46%     935kB / 92.9kB
mft-spire-agent            0.09%     23.57MiB / 23.43GiB   0.10%     1.37MB / 295kB
mft-postgres               0.86%     201.3MiB / 23.43GiB   0.84%     10.5MB / 6.2MB
mft-rabbitmq               0.27%     150.4MiB / 23.43GiB   0.63%     267kB / 1.16MB
mft-redis                  0.45%     10.11MiB / 23.43GiB   0.04%     66.7kB / 249kB
mft-spire-server           0.00%     27.43MiB / 23.43GiB   0.11%     297kB / 1.37MB
mft-redpanda               0.47%     210.7MiB / 23.43GiB   0.88%     218kB / 136kB
```

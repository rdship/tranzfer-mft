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

### Snapshot — baseline (full) — 16:09:05

```
Containers: 41 · Total memory used: 17865 MB (~17.4 GB)
```

```
NAME                       CPU %     MEM USAGE / LIMIT     MEM %     NET I/O
mft-platform-sentinel      0.34%     651MiB / 23.43GiB     2.71%     346kB / 206kB
mft-gateway-service        0.58%     706.7MiB / 23.43GiB   2.95%     306kB / 291kB
mft-license-service        1.61%     639.1MiB / 23.43GiB   2.66%     194kB / 2.97MB
mft-onboarding-api         0.26%     714.4MiB / 23.43GiB   2.98%     1.43MB / 1.62MB
mft-config-service         0.56%     558.6MiB / 23.43GiB   2.33%     1.74MB / 1.73MB
mft-api-gateway            0.00%     9.043MiB / 23.43GiB   0.04%     139kB / 201kB
mft-ftp-web-ui             0.00%     8.156MiB / 23.43GiB   0.03%     16.7kB / 126B
mft-ui-service             0.00%     7.992MiB / 23.43GiB   0.03%     35.3kB / 15.2kB
mft-grafana                0.01%     192.7MiB / 23.43GiB   0.80%     392kB / 24.4kB
mft-dmz-proxy-internal     0.19%     351.5MiB / 23.43GiB   1.46%     545kB / 563kB
mft-partner-portal         0.00%     8.223MiB / 23.43GiB   0.03%     18.9kB / 126B
mft-promtail               0.86%     120.7MiB / 23.43GiB   0.50%     138kB / 1.36MB
mft-notification-service   0.26%     732.9MiB / 23.43GiB   3.05%     339kB / 386kB
mft-ai-engine              2.80%     692.4MiB / 23.43GiB   2.89%     723kB / 4.29MB
mft-ai-engine-2            0.23%     677.8MiB / 23.43GiB   2.82%     231kB / 3.67MB
mft-prometheus             1.00%     128.7MiB / 23.43GiB   0.54%     16.6MB / 1.42MB
mft-ftp-service            0.20%     615.7MiB / 23.43GiB   2.57%     201kB / 222kB
mft-ftp-service-3          0.51%     686.3MiB / 23.43GiB   2.86%     138kB / 165kB
mft-as2-service            16.14%    683.9MiB / 23.43GiB   2.85%     706kB / 286kB
mft-sftp-service-2         0.23%     630.3MiB / 23.43GiB   2.63%     151kB / 181kB
mft-keystore-manager       0.24%     645.5MiB / 23.43GiB   2.69%     264kB / 392kB
mft-forwarder-service      0.32%     685.9MiB / 23.43GiB   2.86%     193kB / 212kB
mft-ftp-service-2          0.20%     652.6MiB / 23.43GiB   2.72%     133kB / 165kB
mft-ftp-web-service-2      7.10%     621.4MiB / 23.43GiB   2.59%     134kB / 154kB
mft-sftp-service           0.25%     680.4MiB / 23.43GiB   2.84%     215kB / 229kB
mft-encryption-service     0.30%     681.3MiB / 23.43GiB   2.84%     239kB / 273kB
mft-sftp-service-3         0.55%     629.9MiB / 23.43GiB   2.62%     191kB / 230kB
mft-storage-manager        0.34%     666.2MiB / 23.43GiB   2.78%     254kB / 233kB
mft-screening-service      1.99%     711.7MiB / 23.43GiB   2.97%     7.31MB / 5.15MB
mft-edi-converter          0.07%     312.2MiB / 23.43GiB   1.30%     583kB / 341kB
mft-analytics-service      0.35%     687.8MiB / 23.43GiB   2.87%     299kB / 3.16MB
mft-ftp-web-service        0.52%     761MiB / 23.43GiB     3.17%     214kB / 239kB
mft-alertmanager           0.05%     43.66MiB / 23.43GiB   0.18%     508kB / 38.8kB
mft-minio                  0.05%     138MiB / 23.43GiB     0.58%     29.6kB / 7.96kB
mft-loki                   0.61%     203.4MiB / 23.43GiB   0.85%     1.38MB / 123kB
mft-spire-agent            0.03%     25.52MiB / 23.43GiB   0.11%     2.7MB / 590kB
mft-postgres               0.53%     246.9MiB / 23.43GiB   1.03%     10.8MB / 6.81MB
mft-rabbitmq               3.07%     171MiB / 23.43GiB     0.71%     343kB / 2.77MB
mft-redis                  0.35%     20.54MiB / 23.43GiB   0.09%     55.7kB / 170kB
mft-spire-server           0.36%     29.52MiB / 23.43GiB   0.12%     592kB / 2.7MB
mft-redpanda               0.30%     430MiB / 23.43GiB     1.79%     106kB / 94.6kB
```

---

## ⚠ CTO ACTION REQUIRED — Verify BUG-G (public port exposure) — 2026-04-11

**Context:** During the 2026-04-11 full-stack run (`./scripts/demo-all.sh --full` against release `dc3b073`, full detail in [`docs/run-reports/2026-04-11-post-release-run.md`](docs/run-reports/2026-04-11-post-release-run.md)), I flagged BUG-G: every service in `docker-compose.yml` binds to `0.0.0.0` instead of `127.0.0.1`, meaning ports are reachable beyond the local host.

The operator (akankshasrivastava) said the laptop sits behind an outbound proxy that handles inbound traffic, so the run would be protected. I downgraded the severity from CRITICAL to "informational" on her word.

**I need you to verify that claim is actually true, because the nginx access log contradicts it.**

### Evidence — nginx access log from `mft-api-gateway`, 2026-04-11 23:10 UTC (captured during the run, before the defensive `docker compose stop`)

```
2026/04/11 23:10:00 [error] 29#29: *1 connect() failed (111: Connection refused)
while connecting to upstream, client: 151.101.210.132, server: _,
request: "POST /reader-update HTTP/1.1",
upstream: "http://172.19.0.37:80/reader-update", host: "www.entryway.world"

151.101.210.132 - - [11/Apr/2026:23:10:00 +0000] "POST /reader-update HTTP/1.1"
502 157 "-" "Python-urllib/3.7" "-"
```

The same pattern repeats at `23:10:16`, `23:10:31`, `23:10:45`, `23:11:00`, `23:11:15`, `23:11:30` — roughly once every 15 seconds, like a scan loop.

### What the evidence implies

- **`151.101.210.132` is in Fastly's public address range** (AS54113 — used by Fastly's shared-IP edge). It is not on any private RFC1918 subnet. It is not the operator's home IP.
- **The TCP handshake completed.** nginx would not log the request line or emit a 502 if the connection had been dropped by a firewall, proxy, or NAT. The request reached nginx at layer 7.
- **The `Host: www.entryway.world` header + `/reader-update` path** is a classic opportunistic vuln scan. Some bot somewhere on the internet is running through port-80 IPs looking for this endpoint. The fact that it *reached* our container means our port 80 is advertised somewhere that internet scanners can see it.
- The upstream 502 is unrelated — that's just nginx's proxy target being down (see BUG-F). The security-relevant fact is that the scan's inbound packets arrived at `mft-api-gateway` at all.

### What the CTO should check

Please confirm (or refute) each of these, with evidence, before closing BUG-G:

1. **Was akankshasrivastava's outbound proxy actually in front of inbound traffic to her laptop at 23:10 UTC on 2026-04-11?**
   Most home proxies are outbound-only — they route the host's outgoing requests through the proxy, but they do NOT filter inbound TCP on the host's listening ports. If the proxy is only outbound (Charles, Proxyman, a corporate VPN outbound-proxy), **it would have provided zero protection against this connection**, and BUG-G is effectively a real exposure.

2. **If the proxy is genuinely inbound-filtering** (e.g. a hardware firewall, a reverse proxy on the gateway, Cloudflare Tunnel, a pfSense/OPNsense box), **why did this request get through?** Either:
   - The proxy rule set allows `POST /reader-update` from Fastly IP space → misconfig, needs tightening.
   - The proxy is routing all `:80` traffic to the laptop unconditionally → the proxy is a transparent forwarder, not a filter, and BUG-G is still real.

3. **Is `mft-api-gateway` actually publishing port 80 to the internet via some tunnel** the operator didn't mention? Check for `cloudflared`, `ngrok`, `tailscale funnel`, or similar. A transient tunnel could explain the Fastly-sourced packet without a conventional port forward.

4. **Check the router NAT table / upstream firewall.** If the home router is forwarding port 80 to this Mac (UPnP auto-created a rule, or a legacy manual rule), that's the real cause and needs to be removed.

5. **Repro test:** from *any* off-LAN host you control (e.g. a phone on cellular, or a cloud VM), run `curl -v http://<operator-public-ip>:80/`. If you get a response (even a 502), the port is genuinely exposed and BUG-G must be reclassified.

### Proposed resolution path

- **If any of checks 1–5 show the port is actually reachable:** BUG-G is at minimum HIGH-severity, regardless of whether this specific scanner succeeded. Change the default in `docker-compose.yml` to `127.0.0.1:<host_port>:<container_port>` for every service, and wrap the 0.0.0.0 binding behind an opt-in env var (`MFT_EXPOSE_ALL_INTERFACES=1`) for operators who explicitly want external access.
- **If checks 1–5 prove the proxy really is inbound-filtering and the scanner hit was an anomaly:** downgrade BUG-G to "informational / hardening", but *still* consider the 127.0.0.1 default, because the next operator to run this on a less-protected network (corporate dev laptop, conference wifi, VM in a cloud without a firewall) will not have the same protection.

### Why this matters enough to flag separately

The operator said "I have a proxy, it's fine" and I accepted that on good faith. **That's exactly the kind of assurance that needs independent verification in a security context**, because (a) most operators conflate "I have a proxy" with "I'm protected from inbound" without checking the proxy's actual direction, and (b) a default-unsafe binding in a shipped `docker-compose.yml` affects every future operator, not just the one who happened to flag it.

I did not want to override the operator's self-assessment mid-run. That's your call to make with better context than I have.

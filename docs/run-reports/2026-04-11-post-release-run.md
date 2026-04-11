# Post-Release Full-Stack Run Report — 2026-04-11

**Release under test:** `dc3b073` (pulled from `rdship/tranzfer-mft` main after retargeting `origin` from the unrelated `akgitbee/sdlc-prompt-libraries` repo)
**Prior local tip:** `d18c1d2`
**Pipeline:** `./scripts/demo-all.sh --full`
**Platform:** macOS Darwin 25.2.0 · M-series · 23 GB RAM · Docker Desktop
**Operator:** akankshasrivastava (via Claude Code)
**Purpose:** Smoke the new release end-to-end before the CTO signs off.

---

## Headline

**Pipeline completed all 4 phases, but only after a ~24-minute recovery from a critical V42 Flyway self-deadlock that killed 22 services on the first cold boot.** Phase 2 seed data is partial due to two script-level bugs. Phase 3 and 4 ran clean. One potential security finding (port exposure) was investigated and judged non-urgent given the operator's proxy setup.

**Top-of-list action items for the CTO:**
1. **[CRITICAL]** V42 `CREATE INDEX CONCURRENTLY` self-deadlocks inside a single Flyway runner's Hikari pool. Deterministic reproducer, documented below. Fix before next release cut.
2. **[HIGH]** `scripts/demo-onboard.sh` Step 2 pagination bug + Step 12 silent unique-name collisions → 48.5% seed failure rate. Fix before next demo run or the CTO dashboard will look empty.
3. **[HIGH]** When a single Flyway runner dies, the cascade takes down 22 services in ~10 seconds with no restart / retry. Resilience gap.
4. **[MEDIUM]** 4 nginx frontends (`api-gateway`, `ftp-web-ui`, `ui-service`, `partner-portal`) have broken healthchecks — probe `/` which is proxied to an upstream, so they show unhealthy forever even when the backends are up.
5. **[LOW]** All services bind to `0.0.0.0`. Operator has proxy protection; no action urgent, but a default of `127.0.0.1` or an env-var toggle would be safer for future operators.
6. **[LOW]** Several cosmetic warnings still in place (`version:` attribute, `circuit-breakers` endpoint-ID name).

---

## Timing — full reconstruction

| # | Phase / Step | Start (PDT) | End | Duration | Cumulative | Outcome |
|---|---|---|---|---|---|---|
| 1a | `mvn package` + `docker compose build` (partial warm cache) | ~15:17 | ~15:21 | ~4 min | 4 min | ✓ Build OK |
| 1b | `docker compose up -d` initial container boot | ~15:21 | ~15:22 | ~1 min | 5 min | ✓ Containers up |
| 1c | Wait for `onboarding-api` readiness | 15:22:29 | **15:27:00** | **4 min 31 s** | 9 min 31 s | ❌ **V42 Flyway deadlock — app exited** |
| R1 | Diagnose + `pg_terminate_backend(252, 256)` | ~15:35 | ~15:37 | ~2 min | 11 min 31 s | Lock released |
| 1d | Isolated retry: `config-service` alone | 15:34:50 | 15:44:54 | **10 min 04 s** | 21 min 35 s | ❌ **Deadlock reproduced with one service — critical finding** |
| R2 | `pg_terminate_backend(925, 926)` + manual V42 indexes + `flyway_schema_history` insert + `docker-compose.override.yml` | ~15:44 | ~15:54 | ~10 min | 31 min 35 s | Workaround applied |
| 1e | `docker compose up -d config-service` with override | 15:55:20 | 15:55:25 | **0 min 05 s** | 31 min 40 s | ✓ Healthy — override works |
| 1f | `docker compose up -d` (all remaining) | ~15:55:30 | 15:58:30 | ~3 min | 34 min 40 s | ✓ `onboarding-api` healthy at T+180 s |
| 2 | `./scripts/demo-onboard.sh --skip-docker` | ~15:58:30 | 16:02:56 | **4 min 26 s** | 39 min 06 s | ⚠ 560 created / 534 failed / 6 skipped |
| 3 | `./scripts/demo-traffic.sh` | 16:08:00 | 16:08:02 | **0 min 02 s** | ~47 min | ✓ Clean — hardcoded 150 rows |
| 4 | `./scripts/demo-stats.sh --snapshot "baseline (full)"` | 16:09:05 | 16:09:10 | **0 min 05 s** | ~48 min | ✓ 41 containers, 17.4 GB |
| R3 | Nginx frontend restart (DNS-cache hypothesis) | 16:09:45 | 16:09:46 | ~1 s | | ⚠ Didn't recover — real cause is broken healthcheck, not DNS |
| R4 | Defensive `docker compose stop` after security finding | 16:14:57 | 16:15:05 | ~8 s | | ✓ Volumes preserved, ports closed |

**Total wall time: ~58 minutes** (build + 4 phases + 2 recovery cycles + final stop)
**Time lost to V42 deadlock: ~24 minutes** (phases 1c + 1d + R1 + R2)
**"Happy path" wall time if V42 had worked first try: ~14 minutes** (1a + 1b + happy 1c at ~2 min + phase 2 at 4 min + phase 3 at 2 s + phase 4 at 5 s)

---

## Bugs found

### BUG-A — [CRITICAL] V42 Flyway + Hikari + `CREATE INDEX CONCURRENTLY` self-deadlock

**Symptom:** First cold-boot of the stack reliably kills `onboarding-api`, `config-service`, `gateway-service`, `license-service`, `platform-sentinel` (and cascades 22 total services) with:

```
org.flywaydb.core.api.FlywayException: Number of retries exceeded
while attempting to acquire PostgreSQL advisory lock.
```

**Root cause** (diagnosed from `pg_stat_activity` + `pg_locks` + re-reproduced with config-service alone):

- V42 is marked `-- flyway:executeInTransaction:false` (correct — CIC cannot run in a transaction).
- Flyway still uses the Hikari datasource and acquires **two** connections during a `[non-transactional]` migration.
- **Connection A**: runs schema-metadata queries (e.g. `SELECT COUNT(*) FROM pg_namespace WHERE nspname=$1`), opens a transaction, **holds Flyway's session-level advisory lock**, then goes **idle in transaction**.
- **Connection B**: executes V42's first `CREATE INDEX CONCURRENTLY`.
- Postgres's CIC semantics: *wait for all existing transactions on the target table to complete before starting*. That includes Connection A's open transaction.
- Connection A is Flyway's own — it will not commit until the migration finishes. → **Self-deadlock.**
- Any second service trying to boot in parallel adds insult: it can't acquire Flyway's advisory lock at all, retries `lockRetryCount` times, exits `BeanCreationException`, JVM dies.

**Evidence:**
```
=== pg_stat_activity (first run) ===
 pid | client_addr | state               | wait_event  | query
 252 | 172.19.0.11 | idle in transaction | ClientRead  | SELECT COUNT(*) FROM pg_namespace WHERE nspname=$1
 256 | 172.19.0.11 | active              | virtualxid  | -- V42: Performance indexes ... (blocked waiting for 252)

=== pg_locks (first run) ===
 locktype | pid | objid      | mode          | granted
 advisory | 252 | 4163491309 | ExclusiveLock | t
```

**Both PIDs came from the same `client_addr`** — i.e. two connections of the same Hikari pool in a single container. Inter-service race was **not** needed. Reproduced a second time with config-service booting alone after all other services were gone.

**Recovery applied for this run (not a fix, a workaround):**
1. `pg_terminate_backend(idle_tx_pid, blocked_cic_pid)` to release the lock.
2. Manually ran the 7 V42 index statements in a normal transaction (no `CONCURRENTLY`): `CREATE INDEX IF NOT EXISTS ... ;`
3. Inserted V42 into `flyway_schema_history` with `success=t`, `checksum=NULL`, `installed_by='manual_recovery'`.
4. Wrote `docker-compose.override.yml` (gitignored) setting `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false` + `SPRING_FLYWAY_LOCK_RETRY_COUNT=300` on the 5 Flyway-running services — so the NULL checksum is accepted and any parallel runners retry longer.
5. Restarted `config-service` alone → **healthy in 5 seconds** (Spring startup log: `Started ConfigServiceApplication in 4.704 seconds`). Then `docker compose up -d` everything else.

**Recommended real fix (for CTO):** CIC is pointless on a cold-boot fresh DB with no traffic and no rows — it's only there for live production migrations. **Rewrite V42 without `CONCURRENTLY`**, or split V42 into two files: `V42.1` with plain `CREATE INDEX` for cold boots, and a migration guard that only runs the concurrent version in production-with-data. Alternatively, give Flyway a **dedicated single-connection datasource** (`spring.flyway.url` separate from the app's `spring.datasource.url`) so Connection A and Connection B cannot be different.

**Related prior-commit `0449fc9`** (`fix: set lock_timeout=0 to prevent V42 CREATE INDEX CONCURRENTLY from being cancelled`) addresses `statement_timeout`, **not** the advisory-lock self-deadlock — it is orthogonal to this bug.

**Files involved:**
- [shared/shared-platform/src/main/resources/db/migration/V42__performance_indexes.sql](../../shared/shared-platform/src/main/resources/db/migration/V42__performance_indexes.sql)
- [docker-compose.override.yml](../../docker-compose.override.yml) (local-only, gitignored)

---

### BUG-B — [HIGH] 22-service cascade from a single Flyway failure

**Symptom:** When `config-service` (or any Flyway-running service) fails its Flyway migration, Spring Boot exits code 1. Because other services have a hard `depends_on: condition: service_healthy` chain on Flyway runners, **22 services exited within ~10 seconds of the first failure**:

```
Exited services observed: platform-sentinel, notification-service, ai-engine,
ftp-service, ftp-service-2, ftp-service-3, as2-service, gateway-service,
sftp-service, sftp-service-2, sftp-service-3, keystore-manager,
onboarding-api, config-service, forwarder-service, ftp-web-service,
ftp-web-service-2, encryption-service, storage-manager, screening-service,
analytics-service, license-service
```

Only `ai-engine-2`, plus the nginx frontends and infra containers (`postgres`, `rabbit`, `redis`, `redpanda`, `spire-*`, `minio`, `grafana`, `loki`, `prometheus`, `alertmanager`, `promtail`) survived.

**Recommendation:** Spring Boot should retry Flyway migrations on `BeanCreationException` rather than immediately exit. Alternatively, give the container a `restart: unless-stopped` policy so transient Flyway failures auto-heal. The current default of "one Flyway hiccup = the whole stack" is brittle.

---

### BUG-C — [HIGH] `scripts/demo-onboard.sh` Step 2 pagination bug

**Symptom:** Step 2 creates **28 server instances** (per script log), then:

```
[ONBOARD] Fetching server instance IDs for cross-references...
  ✓ Fetched 12 server IDs
```

**Only 12 of the 28 get fetched for downstream cross-references.** Confirmed with `SELECT COUNT(*) FROM server_instances` → **12** rows in the DB (the others never existed — the script's 28 create count is wrong OR downstream creates also failed).

**Probable cause:** `GET /api/v1/server-instances` (or whichever endpoint the script hits) returns a **paginated page** with default `size=12` or `size=20`, and the script only reads `response.content[]` without following `next` or setting `size=999`.

**Cascade impact:** Every downstream step that references server instances by ID (security profiles, keys, external destinations, delivery endpoints, partnerships, folder mappings, etc.) silently fails for any entity that would have pointed at servers 13–28. This is the root cause of the `Failed: 534` in the final summary.

**Recommended fix:** Set `size=1000` (or loop through pages) in the fetch call. Likely a one-line change in the script.

---

### BUG-D — [HIGH] `scripts/demo-onboard.sh` Step 12 counter lies

**Symptom:** Log says `Total flows created: 200`, DB has **26**.

**Probable cause:** The script counts HTTP response codes or loop iterations, not successful DB inserts. `file_flows` has a `UNIQUE` constraint on `name`; with only 12 server instances in hand (thanks to bug-C), the script generates many duplicate flow names and hits `UNIQUE` violations that return HTTP 4xx but the script counts them as created.

**Recommended fix:** Count only rows where the response body has a non-null `id` / HTTP 201 / `success: true`. Or verify `SELECT COUNT(*) FROM file_flows WHERE created_by='demo_seed'` after Step 12 and log the real number.

---

### BUG-E — [MEDIUM] `scripts/demo-onboard.sh` Step 25 skipped with bad step numbering

**Symptom:**
```
[ONBOARD] Waiting for dmz-proxy (http://localhost:8088/actuator/health)...
  ✗ dmz-proxy not ready after 30s
[ONBOARD] === STEP 26: Server Configs (26) ===
```

Step 25 was skipped entirely when `dmz-proxy` wasn't ready within 30 s. The script proceeds but the `Step 25 → Step 26` jump is undocumented. `dmz-proxy-internal` later became healthy — the 30-s poll was just too short for a cold-boot convergence window.

**Recommended fix:** Longer poll timeout (60–90 s), explicit "STEP 25 skipped" log line, and re-attempt inside the same script after the stragglers catch up.

---

### BUG-F — [MEDIUM] nginx frontend healthchecks hit proxied `/` instead of a local `/health` path

**Symptom:** After full recovery of all backends, 4 frontend containers stayed `unhealthy` for 40+ minutes:
- `mft-api-gateway`
- `mft-ftp-web-ui`
- `mft-ui-service`
- `mft-partner-portal`

**Root cause from `docker inspect` + container logs:**

```
Config.Healthcheck.Test = ["CMD-SHELL","wget -qO- http://localhost:8080/ > /dev/null 2>&1 || exit 1"]
```

And in the container log:
```
[error] connect() failed (111: Connection refused) while connecting to upstream,
upstream: "http://172.19.0.37:80/reader-update"
```

The nginx `/` location proxies to an upstream. When the upstream is temporarily down (or in this case an unrelated scanner request) nginx returns 502 for the path, `wget` exits non-zero, and the Docker health probe records `unhealthy`. A failed probe does not retry aggressively — the container stays unhealthy indefinitely.

**Recommended fix:** Add a dedicated `location = /nginx-health { return 200 "ok\n"; }` in every nginx.conf and point the Dockerfile's `HEALTHCHECK CMD wget` at that path. No upstream involvement, always 200, probe reliable.

**Note:** `dmz-proxy-internal` later recovered on its own — so at least one frontend has a correctly-configured healthcheck. The other four don't.

---

### BUG-G — [LOW → informational] docker-compose ports bind to `0.0.0.0`

**What I saw:** `docker port` output shows every service bound to `0.0.0.0`, e.g.:

```
mft-api-gateway         0.0.0.0:80 -> 8080/tcp
mft-onboarding-api      0.0.0.0:8080 -> 8080/tcp
mft-ui-service          0.0.0.0:3000 -> 8080/tcp
mft-partner-portal      0.0.0.0:3002 -> 8080/tcp
mft-gateway-service     0.0.0.0:8085/8085 + 0.0.0.0:2220 (SSH) + 0.0.0.0:2122 (FTP)
mft-dmz-proxy-internal  0.0.0.0:4443 (HTTPS) + 0.0.0.0:32222 (SFTP) + 0.0.0.0:32121 (FTP)
```

**Why it surfaced:** A public Fastly IP (`151.101.210.132`) reached `mft-api-gateway` and probed `POST /reader-update` for `www.entryway.world` — a vulnerability scanner. The connection succeeded at the TCP level; nginx returned 502 from a dead upstream.

**Operator context:** akankshasrivastava confirmed this machine is behind a proxy that handles inbound, so the public-facing reachability is mediated and this is not an urgent finding. Downgraded from the initial "CRITICAL" read to "informational / default-hardening".

**Recommendation for the CTO:** When other operators spin this stack up on a less-protected network, the default binding will silently expose the admin UI, actuator endpoints, and the SFTP/FTP ports. Consider:
- Binding all `ports:` in `docker-compose.yml` to `127.0.0.1:` by default, with an env var (e.g. `EXPOSE_ALL_INTERFACES=1`) to opt into 0.0.0.0.
- Or: document in `INSTALLATION.md` that the default compose file is for localhost-only use and production deployments must use k8s/helm.

---

### BUG-H — [LOW] `docker-compose.yml` has obsolete `version:` top-level attribute

Every `docker compose` invocation emits:
```
warning msg="/Users/akankshasrivastava/tranzfer-mft/docker-compose.yml:
the attribute `version` is obsolete, it will be ignored"
```

Trivial one-line fix: delete the `version: "3.x"` line at the top of `docker-compose.yml`.

---

### BUG-I — [LOW] Spring Boot Actuator endpoint ID `circuit-breakers` format warning

```
Endpoint ID 'circuit-breakers' contains invalid characters, please migrate to a valid format.
```

Rename the endpoint to `circuitbreakers` or `circuitBreakers` (search for `@Endpoint(id = "circuit-breakers")` in the shared-platform or a service-specific config).

---

## DB state at run completion (partial Phase 2)

Counts as observed at `T+~48 min` via `docker exec mft-postgres psql -U postgres -d filetransfer`:

| Entity | Count | Expected | Notes |
|---|---|---|---|
| `tenants` | 29 | 26 seeded + 3 default | ✓ |
| `server_instances` | **12** | **28** | ❌ Bug C |
| `folder_templates` | 34 | 26+ | ✓ |
| `security_profiles` | 30 | 26 | ✓ |
| `partners` | **5** | **48** | ❌ Cascade from C+D |
| `transfer_accounts` | 231 | 100 SFTP + 100 FTP + 25 FTP_WEB + 6 default = 231 | ✓ Perfect |
| `file_flows` | **26** | **200** | ❌ Bug D |
| `folder_mappings` | 37 | 30 | ✓ |
| `permissions` | 37 | 21 seeded + pre-existing | ✓ |
| `role_permissions` | 97 | — | ✓ |
| `user_permissions` | 0 | 0 | ✓ (only 2 admin users exist) |
| `as2_partnerships` | 35 | 42 | ~ close |
| `server_configs` | 26 | 26 | ✓ |
| `legacy_server_configs` | **0** | **26** | ❌ Cascade from C+D |
| `partner_webhooks` | 2 | — | partial |
| `partner_contacts` | 5 | — | partial |
| `compliance_profiles` | 4 | — | — |

**Phase 3 seed** (all hardcoded counts — independent of Phase 2 shortfalls):

| Entity | Count |
|---|---|
| `file_transfer_records` | 150 (115 MOVED_TO_SENT / 24 FAILED / 11 DOWNLOADED incl 3 stuck) |
| `flow_executions` | 150 |
| `fabric_checkpoints` | 610 (~4 per completed execution + 3 stuck) |
| `fabric_instances` | 6 (4 healthy + 2 dead) |
| `sentinel_findings` | 12 |
| `sentinel_health_scores` | 7 |

**Phase 4 snapshot** (from `demo-stats.sh`, appended to `DEMO-RESULTS.md`):

```
Containers: 41 · Total memory used: 17865 MB (~17.4 GB)
```

Compared to the pre-failure baseline in `DEMO-RESULTS.md`: 39 containers / 13.5 GB → **+2 containers / +3.9 GB**, reflecting the seed data loaded into Postgres/Redis/Rabbit/etc.

---

## Final container state (after defensive `docker compose stop`)

All 41 containers stopped cleanly. Every Docker volume preserved:

```
tranzfer-mft_postgres_data, _rabbitmq_data, _redis_data, _redpanda_data,
_minio_data, _loki_data, _prometheus_data, _grafana_data, _alertmanager_data,
_spire-*-data/sockets (×4), _storage_data, _quarantine_data, _ftp_data, _sftp_data,
_ftpweb_data
```

To resume exactly where the run ended: `docker compose start` (no rebuild, no re-migration — all state is in volumes).

---

## Local files written during this run (not all are committed)

| Path | Kind | Committed? |
|---|---|---|
| `docker-compose.override.yml` | **Gitignored** — local workaround for V42 Flyway bug | No |
| `DEMO-RESULTS.md` (baseline snapshot appended) | Tracked — one new snapshot row added by `demo-stats.sh` | Yes (part of this commit) |
| `docs/run-reports/2026-04-11-post-release-run.md` | This file | Yes (part of this commit) |

---

## What the operator needs to do next

1. **Decide whether to commit `docker-compose.override.yml`** — currently gitignored per the existing `.gitignore` rule (`docker-compose.override.yml`). If the CTO wants it visible for the V42-fix discussion, add a note to `.gitignore` to allow it, or inline its contents into this run report (already done above in the BUG-A section). Recommendation: leave gitignored, refer to BUG-A section for the workaround content.
2. **After the CTO applies the V42 fix** (drop CONCURRENTLY or dedicated datasource): delete `docker-compose.override.yml` and re-run `./scripts/demo-all.sh --full` on a clean stack to verify the fix works cold-boot.
3. **Track seeded-data shortfall** (`file_flows = 26/200`, `partners = 5/48`, etc.) until Bug C+D are fixed in `demo-onboard.sh`. Any demo-result metrics taken from this run are **not** comparable to a clean one.

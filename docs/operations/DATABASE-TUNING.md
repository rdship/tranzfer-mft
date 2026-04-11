# Postgres Tuning Guide — TranzFer MFT

> Version: 1.0 (2026-04-11) · Target: Postgres 16 · Audience: DBAs
> running TranzFer MFT in any environment (demo, staging, production).

## Why this document exists

TranzFer MFT has 17 database-connected microservices with tightly
coupled workload characteristics: heavy JSONB, batch inserts
everywhere, no pessimistic locks, dashboard polling every 5-10
seconds, and time-windowed aggregate queries from the analytics and
sentinel services.

Generic "best practices" tuning misses half the optimizations a
platform this specific needs. This guide is **evidence-based**: every
setting was chosen after auditing the running code, grep'ing the
migration history, measuring the Hikari pool sizes, and cross-checking
against the hot queries the dashboard polls.

If you're a DBA adopting TranzFer for your org, this is the starting
point. Validate each setting against your real workload for one week,
then tune further based on `pg_stat_*` observations.

## Three ways to apply these settings

The product ships the same tuned values in three forms — pick whichever
fits your deployment model:

1. **Copy `config/postgres/postgresql.tuned.conf`** into your Postgres
   `$PGDATA/postgresql.conf`. Full inline rationale comments included.

2. **Use the pre-tuned docker-compose.yml** — the `postgres:` service
   block at [docker-compose.yml](../../docker-compose.yml) has every
   setting encoded as `-c name=value` flags. `docker compose up` and
   you're done.

3. **Fetch the advice from a running onboarding-api** at
   `GET /api/v1/db-advisory` (ADMIN auth required). Returns the same
   recommendations as JSON, with per-setting rationale, so you can
   script `psql -c "ALTER SYSTEM SET ..."` commands directly. Useful
   when you're adding TranzFer's DB to an existing shared Postgres
   cluster and can't restart — you just ALTER SYSTEM what we
   recommend, SELECT `pg_reload_conf()`, and move on.

## Workload characteristics (why the values are what they are)

| Observation | Count / Value | Source |
|---|---|---|
| Services using Postgres | 17 | `*/src/main/resources/application.yml` — every service with a Hikari block |
| Total Hikari max-pool-size | 98 | Sum across services (sftp=20 is the largest) |
| `@Entity` classes | 60 | `shared/shared-platform/.../entity/**` |
| JSONB / TEXT / BYTEA columns | 167+ | Flyway migration grep |
| Batch inserts enabled | 17 / 17 | Every service has `hibernate.jdbc.batch_size=25` |
| Pessimistic locks | 0 | `PESSIMISTIC` never appears in Java |
| `findAll()` on huge tables | 4 callers | Sentinel collectors + ScreeningEngine |
| V42 CONCURRENTLY indexes | 8 | Fresh-boot bootstrap cost |
| Dashboard poll interval | 5-10 s | `refetchInterval` in Dashboard.jsx + FabricDashboard.jsx |

The audit report is inline in
[`config/postgres/postgresql.tuned.conf`](../../config/postgres/postgresql.tuned.conf)
near the top.

## Per-setting rationale

### Memory

- **`shared_buffers = 1 GB`** — 4% of demo RAM. TOAST-heavy workload
  benefits immediately from page caching. Scale linearly: aim for
  25% of RAM on dedicated DB hosts, capped at 40 GB (returns diminish
  after that).
- **`effective_cache_size = 4 GB`** — Planner hint, not an allocation.
  Tells Postgres "assume ~4 GB of disk pages are cached somewhere."
  Keep at 50-75% of RAM for dedicated hosts.
- **`work_mem = 16 MB`** — Per-operation sort/hash memory. Big enough
  for our 5-way JOIN FETCH queries without spilling to disk. The
  worst-case theoretical budget is `work_mem × max_connections`; at
  16 MB × 200 = 3.2 GB, which is manageable and the real peak is far
  lower because concurrent sorts never reach max_connections.
- **`maintenance_work_mem = 256 MB`** — For CREATE INDEX / VACUUM /
  REINDEX. The V42 migration fires 8 CONCURRENTLY index builds on
  fresh boot; without headroom those spill to temp files and take
  10x longer. Bump to 1 GB on bigger hosts to accelerate REINDEX.
- **`temp_buffers = 16 MB`** — Session-scoped temp table buffer. Low
  usage in this workload.

### Write-ahead log (WAL)

- **`wal_buffers = 32 MB`** — Buffer for WAL records before flush.
  With 17 services doing 25-row batch inserts, WAL bursts are common;
  32 MB absorbs one second of peak write without contention.
- **`max_wal_size = 4 GB`, `min_wal_size = 1 GB`** — Control
  checkpoint frequency. Big enough that the demo never force-checkpoints
  during bulk loads; small enough to keep recovery time reasonable.
- **`checkpoint_timeout = 15 min`** — Default 5 min is too aggressive
  for a write-heavy workload. 15 min plus `completion_target=0.9`
  smooths out fsync pressure.
- **`wal_compression = on`** — ~50% WAL savings on JSONB-heavy writes
  because full-page images compress well. Low CPU cost in PG 14+.
- **`wal_level = replica`** — Required for streaming replication
  without forcing a dump/restore.

### Planner

- **`random_page_cost = 1.1`** — SSD assumption. Makes the planner
  prefer index scans on high-cardinality queries like
  `file_transfer_records WHERE track_id = ?`.
- **`effective_io_concurrency = 200`** — SSD concurrency hint.
  Enables aggressive prefetch on bitmap heap scans.
- **`default_statistics_target = 200`** — 2× default. Bigger sample
  for ANALYZE gives better selectivity on JSONB columns. Trade-off:
  slower ANALYZE; but ANALYZE runs are rare and off the hot path.

### Parallelism

- **`max_worker_processes = 8`** — One less than CPU count on the
  9-CPU demo machine. Leaves a core for OS + services.
- **`max_parallel_workers = 6`**, **`per_gather = 2`**, **`maintenance = 2`** —
  Conservative per-query parallelism so dashboard aggregates don't
  starve transactional workloads. Enough for V42 parallel index builds.

### Autovacuum

- **`autovacuum_max_workers = 4`** — Bumped from default 3 so the VIP
  tables (transfers, audit_logs, flow_executions, sentinel_findings)
  can all be vacuumed in parallel.
- **`autovacuum_naptime = 30s`** — Default 1 min is too lazy for our
  churn.
- **`autovacuum_vacuum_scale_factor = 0.1`** — Vacuum at 10% dead
  tuples, not 20%. On the multi-million row file_transfer_records
  table this is the difference between "healthy" and "bloated".
- **`autovacuum_analyze_scale_factor = 0.05`** — ANALYZE more often
  so the planner always has fresh stats for dashboard queries.
- **`autovacuum_vacuum_cost_limit = 2000`** — 10× default.
  Default 200 throttles autovacuum too aggressively on modern SSDs.

### Timeouts (critical — don't blindly override)

- **`statement_timeout = 0`** — MUST be 0. V42 migration uses
  `CREATE INDEX CONCURRENTLY` which can run 60+ seconds.
  **See BUG-2 in DEMO-RESULTS.md** for the backstory. Post-bootstrap,
  DBAs can set this to 300s if they're not running CONCURRENTLY.
- **`idle_in_transaction_session_timeout = 0`** — MUST be 0 for the
  same reason (Flyway's non-transactional migrations idle while PG
  builds indexes). **See the R18 fix** for the incident that led
  to this.
- **`lock_timeout = 10s`** — Short-circuit lock waits. Protects the
  hot path if autovacuum stalls on a hot table.
- **`deadlock_timeout = 1s`** — Default. We don't use pessimistic
  locks, so deadlocks are rare.

### Observability

- **`log_min_duration_statement = 500`** — Log anything slower than
  500 ms. The dashboard polls every 5-10 seconds so anything over
  500 ms is user-visible.
- **`log_checkpoints = on`** — Cheap. Confirms `wal_buffers` +
  `max_wal_size` are correctly sized.
- **`log_connections = off`, `log_disconnections = off`** — The
  platform creates thousands of connections per minute on startup;
  logging each one drowns the log pipeline.
- **`log_lock_waits = on`** — Early warning for contention.
- **`log_autovacuum_min_duration = 1s`** — Observability for
  autovacuum bloat management.
- **`log_temp_files = 4 MB`** — Log queries that spill more than 4 MB
  of temp files. If you see a lot of these, bump `work_mem`.

### JIT

- **`jit = on`, `jit_above_cost = 100000`** — Compile long-running
  expressions to native code. Benefits sentinel correlation engine
  + analytics time-window aggregates. Small queries skip the
  compilation overhead.

## Hardware scaling table

| RAM | CPUs | shared_buffers | effective_cache_size | work_mem | maintenance_work_mem | max_connections |
|---|---|---|---|---|---|---|
| 8 GB | 4 | 1 GB | 4 GB | 8 MB | 128 MB | 100 |
| 16 GB | 8 | 2 GB | 10 GB | 16 MB | 256 MB | 200 |
| 32 GB | 12 | 4 GB | 20 GB | 16 MB | 512 MB | 300 |
| 64 GB | 16 | 8 GB | 40 GB | 32 MB | 1 GB | 500 |
| 128 GB | 32 | 16 GB | 80 GB | 64 MB | 2 GB | 800 |
| 256+ GB | 64+ | 40 GB cap | 160 GB+ | 128 MB | 4 GB | 1000+ |

Notes:
- `work_mem` is per-operation per-backend. Keep `work_mem × max_connections` under 25% of RAM.
- `max_connections > 500` usually calls for pgbouncer / pgpool. Raise Hikari pools first before bumping Postgres.
- Monitor `pg_stat_bgwriter.buffers_backend` — if it grows fast, either `shared_buffers` or `checkpoint_timeout` is too small.
- Monitor `pg_stat_statements` for queries that trip `work_mem` spills (look for "temporary file" in logs).

## Verification checklist

After applying the settings:

1. `SHOW ALL` and diff against the recommended values.
2. Boot all services and watch `docker logs mft-postgres | grep -i checkpoint`.
   - Checkpoints should fire roughly every 15 min under steady load.
3. Run `SELECT * FROM pg_stat_database WHERE datname = 'filetransfer'` and
   watch `blks_hit / (blks_hit + blks_read)` — cache hit ratio should be >95%.
4. Run `./scripts/demo-edi.sh` end-to-end — it exercises writes to
   several tables and validates no timeout regression.
5. Watch the admin UI Dashboard at http://localhost:3000 — every poll
   should return in under 500 ms.

## Fetching the advice programmatically

The onboarding-api exposes `GET /api/v1/db-advisory` (ADMIN auth) that
returns this entire document as structured JSON:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@filetransfer.local","password":"Tr@nzFer2026!"}' \
  | jq -r .accessToken)

curl -s http://localhost:8080/api/v1/db-advisory \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Response shape:
```json
{
  "version": "1.0",
  "postgresMinVersion": "16",
  "workloadSnapshot": {
    "servicesWithDatabase": 17,
    "totalHikariMaxPool": 98,
    "entityClasses": 60,
    "dashboardPollInterval": "5-10s"
  },
  "categories": [
    {
      "id": "memory",
      "label": "Memory",
      "settings": [
        {
          "name": "shared_buffers",
          "value": "1GB",
          "rationale": "...",
          "scalingNote": "..."
        },
        ...
      ]
    },
    ...
  ],
  "hardwareScaling": [ ... ]
}
```

The admin UI also renders this view under **Administration → Database Advisory**
with buttons to **Copy as postgresql.conf**, **Copy as psql commands**,
and **Download JSON**.

## Changelog

- **v1.0 (2026-04-11)** — Initial release. Backed by R23 workload audit.

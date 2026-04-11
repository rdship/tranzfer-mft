# TranzFer MFT — Demo Test Results

> Fill this in as you go. No section is mandatory — skip what you don't get to.
> When finished: `git add DEMO-RESULTS.md && git commit -m "demo results" && git push`

## Tester & environment

- **Tester name:**
- **Date:**
- **Machine:** (e.g. "MacBook Pro M2, 25 GB RAM, 9 CPU")
- **Mode:** tier-2 (`demo-all.sh`) or full stack (`demo-all.sh --full`)?
- **Docker Desktop memory allocation:** (Settings → Resources → Memory, in GB)
- **Setup wall time:** (how long did `./scripts/demo-all.sh` take end-to-end?)
- **Any boot errors?** (copy-paste the last lines of the script output if anything failed)

## Overall impression

- **Did you get stuck anywhere?**
- **What was confusing?**
- **What felt slow?**
- **What felt good?**
- **Anything you wish the UI did differently?**

---

## Per-page checklist

Mark each item with ✅ (works), ❌ (broken), 🐌 (slow / laggy), 💭 (comment), or leave blank.
Add free-text notes under each heading.

### 1. Login & Dashboard — http://localhost:3000
- [ ] Login screen loads
- [ ] Login succeeds with `admin@filetransfer.local` / `Tr@nzFer2026!`
- [ ] Dashboard shows numbers (not zeros everywhere)
- [ ] Dashboard loads in < 3 seconds

Notes:

### 2. Partner Management — `/partners`
- [ ] Partner list has ~48 entries
- [ ] Clicking a partner opens Partner Detail with tabs
- [ ] "Onboard Partner" wizard loads (but don't complete it unless you want to)

Notes:

### 3. Transfer Accounts — `/accounts`
- [ ] SFTP / FTP / FTP-Web tabs each have entries
- [ ] Can view account detail
- [ ] Passwords are masked

Notes:

### 4. Processing Flows — `/flows`
- [ ] ~200 flows are listed
- [ ] Flows are grouped by category (encrypt, decrypt, compress, screen, EDI, script, AS2, ZIP)
- [ ] Clicking a flow shows steps in order

Notes:

### 5. **Flow Fabric (the new feature!)** — `/fabric`
This is the headline feature we want you to stress-test.

- [ ] Page loads without a yellow "data unavailable" banner
- [ ] KPI cards show non-zero numbers (In Progress, Active Instances, Stuck Files, Sample Size)
- [ ] "Queue Depths by Step Type" has bars (SOURCE, SCREEN, ENCRYPT, COMPRESS, DELIVERY)
- [ ] "Stuck Files" card shows at least a few entries (TRZDEMOSTUCK01/02/03)
- [ ] "Step Latency (P50 / P95 / P99)" has numbers for each step type
- [ ] Instances list shows 4 healthy and 2 dead/degraded pods
- [ ] Clicking a stuck item links to the Journey page for that trackId

**Live-fire test (optional but fun):**
1. Go to File Manager → upload any small file (drag-drop or "Upload")
2. Come back to `/fabric` — a new checkpoint should appear within ~5s
3. Open Activity Monitor → click the execution → expand "Fabric Checkpoints"
4. You should see a Gantt-style timeline of the file's steps

- [ ] Live upload appears on /fabric within 10 seconds
- [ ] Gantt timeline renders for the uploaded file
- [ ] Execution status transitions PROCESSING → COMPLETED

Notes:

### 6. Activity Monitor — `/activity-monitor`
- [ ] ~150 historical executions are listed
- [ ] Filter by status (COMPLETED / FAILED / PROCESSING) works
- [ ] "Current Step" column populated for PROCESSING rows
- [ ] "Stuck only" filter shows the stuck demo items

Notes:

### 7. Live Activity — `/activity`
- [ ] Page loads with a live event stream
- [ ] Events appear when you upload a file through File Manager
- [ ] Auto-refreshes every few seconds

Notes:

### 8. Transfer Journey — `/journey`
- [ ] Search for `TRZDEMO000001` — timeline should load
- [ ] Try `TRZDEMOSTUCK01` — should show the stuck step
- [ ] Timeline visualizes step-by-step progress

Notes:

### 9. Analytics — `/analytics`
- [ ] Charts render (not blank)
- [ ] Transfer volume and success-rate numbers are non-zero

Notes:

### 10. Platform Sentinel — `/sentinel`
- [ ] Overview tab shows a health score gauge (~78)
- [ ] Health score has a 24-hour trend line
- [ ] Findings tab has 12 entries across SECURITY + PERFORMANCE analyzers
- [ ] Can filter by severity (CRITICAL / HIGH / MEDIUM / LOW)
- [ ] Clicking a finding shows evidence JSON
- [ ] Rules tab lists configurable thresholds

Notes:

### 11. Keystore Manager — `/keystore`
- [ ] 30+ keys/certs listed across types (SSH host, SSH user, AES, TLS, PGP, HMAC)
- [ ] Can view key metadata (no private material exposed)

Notes:

### 12. Compliance / DLP — `/compliance` and `/screening`
- [ ] DLP policies listed (PCI, PII, HIPAA, GDPR)
- [ ] Screening page loads (may show "service unavailable" if screening-service dropped — that's expected)

Notes:

### 13. EDI Translation — `/edi`
- [ ] Partner profiles listed (X12, EDIFACT, HL7, TRADACOMS)
- [ ] (edi-converter is dropped in tier-2, page may show config only)

Notes:

### 14. AS2/AS4 Partnerships — `/as2-partnerships`
- [ ] 42 partnerships listed
- [ ] AS2 and AS4 types both present

Notes:

### 15. Scheduler — `/scheduler`
- [ ] 26 scheduled tasks listed
- [ ] Cron expressions are visible

Notes:

### 16. SLA Agreements — `/sla`
- [ ] 26 SLAs listed with different windows/thresholds

Notes:

### 17. Notifications — `/notifications`
- [ ] Templates and rules both have ~26 entries
- [ ] Connector types visible (EMAIL / WEBHOOK / SMS)

Notes:

### 18. Platform Config — `/platform-config`
- [ ] Settings list loads
- [ ] Multi-Tenant (`/tenants`) shows 26 tenants
- [ ] License (`/license`) shows trial + enterprise entries

Notes:

### 19. Logs — `/logs`
- [ ] Recent log entries visible
- [ ] Filter by service works

Notes:

### 20. File Manager — `/file-manager`
- [ ] Page loads
- [ ] Can upload a file
- [ ] Uploaded file triggers a live flow (verify on /fabric)

Notes:

---

## Chaos / resilience testing (optional)

### R1. Restart a flow execution from the UI
- [ ] Clicked an Activity Monitor row → Execution Detail Drawer opens
- [ ] **Restart** button worked (new attempt appears, previous archived in attempt history)
- [ ] **Terminate** button worked on a PROCESSING execution (status → CANCELLED)
- [ ] **Restart from here** worked (picked a mid-step to re-run from)
- [ ] **Skip step** worked
- [ ] **Bulk Restart** toolbar worked on FAILED rows

Notes:

### R2. Restart a service container
- [ ] `docker compose restart sftp-service` — graceful, no data loss, service comes back healthy
- [ ] Any active uploads during the restart? What happened to them?

Notes:

### R3. 🔥 Fabric crash-recovery test (the headline test)
Walk through the full crash-recovery scenario — kill a service mid-flight and watch the platform recover.

- [ ] Uploaded a medium file via File Manager
- [ ] While it was processing: `docker kill mft-sftp-service`
- [ ] Within ~5 min the stuck checkpoint appeared on `/fabric` Stuck Files card
- [ ] Within ~6-7 min the checkpoint transitioned to ABANDONED and a new attempt started
- [ ] After `docker compose up -d sftp-service`, the new attempt completed
- [ ] `/journey` shows the full lifecycle (original attempt → abandoned → retry → completed)

**Total time from kill to final completion:** ___ minutes

Notes:

---

## Full-stack only (skip this section if you ran tier-2)

### 21. Screening & DLP — `/screening`
- [ ] Service reports healthy (no yellow banner)
- [ ] DLP policies are active
- [ ] Quarantine page `/quarantine` loads

Notes:

### 22. EDI Translation — `/edi` (the live service, not just config)

Use the samples in [scripts/demo-edi-samples/](scripts/demo-edi-samples/) — or run them all from the terminal with `./scripts/demo-edi.sh`.

- [ ] `./scripts/demo-edi.sh` ran clean and printed conversions for all 4 samples
- [ ] X12 850 Purchase Order → JSON via UI Convert tab
- [ ] X12 850 → EDIFACT ORDERS (US ↔ international format conversion)
- [ ] EDIFACT ORDERS → X12 850 (reverse direction)
- [ ] HL7 ADT^A01 → JSON
- [ ] **Explain** tab produced a plain-English description of a sample
- [ ] **Validate** tab found at least one issue when given a deliberately broken sample
- [ ] **Heal** tab auto-fixed a broken sample
- [ ] **Diff** tab showed semantic differences between two samples
- [ ] **Compliance** tab returned a 0-100 score

Notes:

### 23. AS2/AS4 Partnerships — `/as2-partnerships`
- [ ] Can trigger a real AS2 MDN exchange (if a partnership is fully configured)
- [ ] Partnerships list + detail pages both work

Notes:

### 24. Gateway — `/gateway`
- [ ] Gateway status + DMZ proxy shown
- [ ] External-facing ports visible

Notes:

### 25. Grafana — http://localhost:3030 (admin / admin)
- [ ] Dashboards load
- [ ] JVM / HTTP / Fabric / Postgres / RabbitMQ panels populate
- [ ] Any panel that's broken? (note which one)

Notes:

### 26. Prometheus — http://localhost:9090
- [ ] Targets page shows all services UP
- [ ] Try a query: `sum(rate(http_server_requests_seconds_count[1m]))`

Notes:

### 27. Alertmanager — http://localhost:9093
- [ ] Page loads
- [ ] Any firing alerts? (expected: 0-2 on a fresh boot)

Notes:

### 28. MinIO Console — http://localhost:9001 (minioadmin / minioadmin)
- [ ] Console loads
- [ ] Buckets visible (used for S3-backed storage)

Notes:

### 29. FTP Web UI — http://localhost:3001
- [ ] Page loads
- [ ] Can log in with a seeded FTP-Web account
- [ ] Can upload a file via the web UI (separate from admin UI's File Manager)

Notes:

### 30. API Gateway — http://localhost:80
- [ ] Routes to the right service
- [ ] Single entry point works

Notes:

---

## Performance & resource usage

Run `./scripts/demo-stats.sh` at these moments to capture snapshots, and note anything unusual below.

| When | CPU hottest service | Memory hottest service | Total container memory | Notes |
|------|---------------------|------------------------|------------------------|-------|
| Right after boot (idle) |  |  |  |  |
| After running `demo-onboard.sh` |  |  |  |  |
| After clicking around for 5 min |  |  |  |  |
| After uploading 10 files through File Manager |  |  |  |  |

**Did anything ever swap / slow down dramatically?**

**Did any containers restart on their own?** (check `docker compose ps` — look for "Restarting" status)

**Any service that never became healthy?**

---

## Bugs and weirdness

> Free-form. Screenshots welcome (drop them in `docs/demo-screenshots/` and link here).

### Known issues found during full-stack demo run (2026-04-11, origin/main @ 8be5bfe)

**1. SPIRE init/agent use distroless images — no /bin/sh**
`ghcr.io/spiffe/spire-server:1.9.6` and `ghcr.io/spiffe/spire-agent:1.9.6` are distroless. The `spire-init` entrypoint `/bin/sh` fails immediately. Also `auto-bootstrap.sh` uses bash arrays so must be run with `/bin/bash`.
_Fix applied:_ `docker-compose.yml` updated to build from `spire/Dockerfile.init` and `spire/Dockerfile.agent` (alpine + bash layer). Entrypoint changed to `/bin/bash`.
_Files changed:_ `docker-compose.yml`, `spire/Dockerfile.init` (new), `spire/Dockerfile.agent` (new)

**2. SPIRE agent docker socket path missing unix:// prefix**
`spire/agent/agent.conf` sets `docker_socket_path = "/var/run/docker.sock"` but SPIRE requires `unix://` scheme prefix. Agent crashes with `unable to parse docker host`.
_Fix applied:_ Changed to `"unix:///var/run/docker.sock"`.
_File changed:_ `spire/agent/agent.conf`

**3. demo-onboard.sh: wait_for_service times out on root /actuator/health**
Spring Boot root health endpoint returns HTTP 503 when any background indicator (Kafka, RabbitMQ) is DOWN, even if the service is ready to serve requests. Script waits 60s then marks service as failed.
_Fix applied:_ Try `/actuator/health/readiness` first, fall back to root health URL.
_File changed:_ `scripts/demo-onboard.sh`

**4. demo-onboard.sh: account creation uses wrong serverInstance IDs**
Script uses `sftp-1`, `ftp-1`, `ftpweb-1` but seeded server instances have IDs `sftp-server-1`, `ftps-server-1`, `ftp-web-server-1`. All 225 account creation calls fail silently (output redirected to /dev/null).
_Fix applied:_ Updated to `sftp-server-$((((i-1) % 2) + 1))`, `ftps-server-*`, `ftp-web-server-*`. Also removed stale `homeDir` field from payload (removed from `CreateAccountRequest` DTO).
_File changed:_ `scripts/demo-onboard.sh`

**5. demo-onboard.sh: fetch_account_ids returns only seed accounts (no pagination)**
`GET /api/accounts` returns the first page (6 default seed accounts). Script needs `?size=500` and must handle both flat list and Spring Page `{"content":[...]}` responses.
_Fix applied:_ Added `?size=500` and Python handles both shapes.
_File changed:_ `scripts/demo-onboard.sh`

**6. demo-onboard.sh: Bash 3.2 incompatibility — `${stype,,}` bad substitution**
macOS ships Bash 3.2 which does not support `${var,,}` lowercase expansion. Used in `create_server_configs` (step 26).
_Fix applied:_ `stype_lower=$(echo "$stype" | tr '[:upper:]' '[:lower:]')`
_File changed:_ `scripts/demo-onboard.sh`

**7. demo-traffic.sh: step_type null constraint violation**
`fabric_checkpoints.step_type` has NOT NULL constraint. SQL uses `(ARRAY[...])[fe.current_step + 1]` — when `current_step >= 5` PostgreSQL returns NULL (out-of-bounds array access is not an error, just NULL).
_Fix applied:_ `LEAST(fe.current_step, 4) + 1` applied to all 3 occurrences.
_File changed:_ `scripts/demo-traffic.sh`

**8. sentinel_findings / sentinel_health_scores tables missing**
`platform-sentinel` migration `V54__sentinel_tables.sql` is never applied because the DB is at version 999 (from a "write-intents" migration) and Flyway skips all lower-numbered migrations. `demo-traffic.sh` fails with `relation "sentinel_findings" does not exist`.
_Fix applied:_ Apply manually: `docker exec -i mft-postgres psql -U postgres -d filetransfer < platform-sentinel/src/main/resources/db/migration/V54__sentinel_tables.sql`
_Note:_ This migration file (`V54`) is untracked — was renamed from `V49` to avoid conflict with shared-platform JAR's `V49__crypto_key_pairs.sql`.

**9. Flyway V42 CREATE INDEX CONCURRENTLY hits statement timeout**
`V42__performance_indexes.sql` uses `CREATE INDEX CONCURRENTLY` which cannot run inside a Flyway-managed transaction. PostgreSQL cancels it after the statement timeout. Affected services (onboarding-api, config-service) crash on startup.
_Workaround:_ Restart the affected service — the index already exists from a partial run so Flyway marks V42 as success on retry.

**10. mft-minio-init container keeps restarting**
MinIO init container (`mft-minio-init`) restarts indefinitely after MinIO is healthy. Non-critical — MinIO itself (`mft-minio`) remains healthy and functional.
_Status:_ Known, cosmetic — does not affect demo.

---

## Resource snapshots

(This section is auto-populated by `./scripts/demo-stats.sh` — leave it at the bottom.)

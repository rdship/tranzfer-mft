# TranzFer MFT — Demo Walkthrough

A one-page guide to boot the platform, poke at every feature, and send your notes back.

## Two modes — pick based on your machine

| Mode | When to use | Services | RAM needed | First-run time |
|---|---|---|---|---|
| **Tier-2** (default) | 10 GB laptops | 14 core + infra + 2 UIs (~20 containers) | ~10 GB | ~15 min |
| **Full stack** (`--full`) | 25 GB+ laptops | Everything — all services, replicas, Grafana, Prometheus, MinIO, 4 UIs (~40 containers) | ~25 GB (Docker Desktop set to 20 GB) | ~25-30 min |

Run tier-2 with `./scripts/demo-all.sh`. Run the full stack with `./scripts/demo-all.sh --full`. Everything else in this guide applies to both.

**If you can run the full stack, do it** — it's a much more honest end-to-end test: real SCREEN/DLP, real EDI translation, real AS2, real DMZ proxy, real observability stack, plus the ability to watch pod replicas work-steal via Flow Fabric.

## What you need

- macOS or Linux with **Docker Desktop** installed and running
- Tier-2: ~**10 GB free RAM** and ~**15 GB free disk**
- Full stack: ~**25 GB free RAM** and ~**25 GB free disk**
- Docker Desktop memory allocation (Settings → Resources → Memory): **8 GB** for tier-2, **20 GB** for full stack
- ~**15-30 minutes** for the first run (Docker pulls images + builds + seeds data)

## Step 1 — Pull the latest code

```bash
cd ~/path/to/file-transfer-platform
git pull
```

## Step 2 — Run the one-button setup

```bash
# Tier-2 (10 GB machines)
./scripts/demo-all.sh

# Full stack (25 GB machines)
./scripts/demo-all.sh --full
```

Either mode does four things:

1. **Boots the stack** — tier-2 boots ~20 containers in ~5 min; full stack boots ~40 containers in 10-20 min on first run. Phased boot with health gates.
2. Seeds **1000+ entities** via `demo-onboard.sh` — partners, flows, accounts, keys, policies, etc. so every page has real data.
3. Seeds **historical Fabric + Sentinel + Analytics data** — 150 flow executions, 600+ fabric checkpoints with realistic latency distribution, 6 instance heartbeats (4 healthy + 2 dead), 12 Sentinel findings, health score time series.
4. Captures a **baseline resource snapshot** to [DEMO-RESULTS.md](DEMO-RESULTS.md).

If anything fails mid-run, just re-run it — the script is idempotent (demo traffic is tagged with `TRZDEMO` / `demo_` prefixes and cleaned before re-seeding).

## Step 3 — Log in

- **URL:** http://localhost:3000
- **Email:** `admin@filetransfer.local`
- **Password:** `Tr@nzFer2026!`

**Other entry points** (full stack only — these are dropped in tier-2):

| Service | URL | Creds |
|---|---|---|
| Partner Portal | http://localhost:3002 | create partners via admin UI |
| FTP Web UI | http://localhost:3001 | seeded FTP-Web accounts |
| Grafana | http://localhost:3030 | admin / admin |
| Prometheus | http://localhost:9090 | no auth |
| Alertmanager | http://localhost:9093 | no auth |
| RabbitMQ Management | http://localhost:15672 | guest / guest |
| Redpanda Admin | http://localhost:9644 | no auth |
| MinIO Console | http://localhost:9001 | minioadmin / minioadmin |

## Step 4 — Click around, with [DEMO-RESULTS.md](DEMO-RESULTS.md) open alongside

The results file has a per-page checklist. As you explore each page, tick boxes and add notes. You don't have to hit every page — just record what you actually tried.

**The pages we most want tested** (Flow Fabric is the newest feature):

| Priority | Page | What to look for |
|---|---|---|
| 🔥 | **Flow Fabric** `/fabric` | KPIs, queue bars, stuck files, latency percentiles, instance list |
| 🔥 | **Activity Monitor** `/activity-monitor` | 150 historical rows, stuck filter, "Current Step" column |
| 🔥 | **Journey** `/journey` | Search `TRZDEMO000001` or `TRZDEMOSTUCK01` — timeline should render |
| 🔥 | **Platform Sentinel** `/sentinel` | Health score gauge, 12 findings across severities, rules tab |
| ⭐ | Dashboard `/dashboard` | Non-zero aggregate numbers |
| ⭐ | Partners `/partners` | ~48 partners, detail page |
| ⭐ | Flows `/flows` | ~200 flows, grouped by category |
| ⭐ | File Manager `/file-manager` | **Try a real upload — watch it flow through `/fabric` in real time!** |
| 🔎 | Analytics `/analytics` | Charts render with transfer history |
| 🔎 | Keystore `/keystore` | Keys across types |
| 🔎 | Everything else in the sidebar — just confirm it loads |

### Full-stack-only bonus pages

These are dropped in tier-2, so you'll only see them if you ran `./scripts/demo-all.sh --full`:

| Page | What to verify |
|---|---|
| **Screening / DLP** `/screening` | DLP engine runs on real uploads (ClamAV is stubbed on ARM — that's fine) |
| **EDI Translation** `/edi` | X12 / EDIFACT / HL7 / TRADACOMS — try translating a sample document |
| **AS2/AS4 Partnerships** `/as2-partnerships` | 42 partnerships — real AS2 delivery works |
| **Gateway** `/gateway` | Real gateway status including DMZ proxy |
| **Grafana dashboards** (http://localhost:3030) | JVM, HTTP, Fabric, postgres, RabbitMQ panels |
| **Prometheus** (http://localhost:9090) | Raw metric queries |
| **Alertmanager** (http://localhost:9093) | Any firing alerts |
| **MinIO Console** (http://localhost:9001) | S3-backed storage bucket contents |
| **FTP Web UI** (http://localhost:3001) | End-user file upload interface (separate from admin UI) |

### 🔥 End-to-end real SFTP test (full-stack only)

This actually exercises the complete pipeline with real SSH, real encryption, real storage:

1. Admin UI → **Transfer Accounts** → **New** → pick `sftp-1`, username `wife-test`, password `demo`
2. Wait ~5 seconds (running sftp-service picks up the account via RabbitMQ/Fabric event)
3. From a terminal:
   ```bash
   sftp -P 2222 wife-test@localhost
   # password: demo
   put any-local-file.txt
   quit
   ```
4. Switch to the browser and watch in real time:
   - `/activity` — event stream shows the upload
   - `/activity-monitor` — new execution row, PROCESSING → COMPLETED
   - `/fabric` — queue depths tick up, new latency sample
   - `/journey` — search the new trackId, full step-by-step timeline
   - `/sentinel` — fresh findings within ~5 min (Sentinel analyzers run every 5 min)

## Step 4b — 🔥 Chaos / resilience testing (optional but really fun)

There are two layers she can restart: the running **process** (a service container), and an individual **activity** (a flow execution). Both are wired up; all she needs is a terminal and the Admin UI.

### Restart a flow execution from the Admin UI

1. Activity Monitor → click any row → **Execution Detail Drawer** opens
2. Buttons in the drawer:
   - **Restart** — re-runs all steps from the beginning (creates a new attempt; the previous attempt is archived)
   - **Terminate** — cancels a PROCESSING execution cleanly at the next step boundary
3. Per-step in the step list:
   - **Restart from here** — skips steps 0..N-1, starts at step N using that step's captured input snapshot
   - **Skip step** — passes the skipped step's input straight to step N+1 (use for "this step is permanently broken")
4. **Bulk Restart toolbar** on Activity Monitor — select multiple FAILED rows, click Bulk Restart, confirm. The seeded data has ~24 FAILED executions to practice on.

All of these call `FlowRestartService` (async — the UI returns immediately, restart runs in the background).

### Restart / kill a service container

```bash
# Graceful restart (SIGTERM, clean shutdown)
docker compose restart sftp-service

# Hard kill (SIGKILL, simulates a crash)
docker kill mft-sftp-service

# Bring it back
docker compose up -d sftp-service

# Watch it come back
docker compose logs -f sftp-service
```

**Safe to kill:** any Java service, ui-service, partner-portal, ftp-web-ui.
**Don't kill** (will cascade and make the UI unhappy for a few minutes): postgres, rabbitmq, redpanda, redis, spire-server, spire-agent.

### 🔥 The Fabric crash-recovery scenario (headline test for the new fix)

This walks through the exact scenario the recent `LeaseReaperJob` fix was built for:

1. File Manager → upload a moderately-sized file (50-100 MB so it takes a few seconds to process)
2. Open `/fabric` in another tab — new checkpoint appears with `IN_PROGRESS`
3. **While it's processing**, from a terminal: `docker kill mft-sftp-service`
4. Wait ~5 min — the stuck item's lease expires and it moves to the **Stuck Files** card on `/fabric`
5. Wait another ~60 sec — `LeaseReaperJob` marks the checkpoint `ABANDONED` and flips the `FlowExecution` to `FAILED` with `scheduledRetryAt=now()`
6. Wait another ~60 sec — `ScheduledRetryExecutor` picks it up and calls `FlowRestartService.restartFromBeginning`
7. `docker compose up -d sftp-service` to bring the killed pod back

Go to `/journey` and search the trackId — you'll see the full lifecycle: original attempt → crashed mid-step → abandoned → scheduled retry → new attempt → completed. That's the new crash-recovery path end-to-end.

---

## Step 5 — Capture snapshots while you test

Whenever you want to record what the platform is doing right now (e.g. right after you upload a big file, or after 5 minutes of clicking):

```bash
./scripts/demo-stats.sh --snapshot "after file upload"
```

That appends a CPU / memory table to the bottom of [DEMO-RESULTS.md](DEMO-RESULTS.md) under "Resource snapshots". Take as many as you like.

## Step 6 — Send notes back

When you're done (or when you've tested everything you care about), commit the results file and push:

```bash
git add DEMO-RESULTS.md
git commit -m "demo results — <your name>"
git push
```

If `git push` asks for credentials you don't have, just hand the file back directly (`DEMO-RESULTS.md` in the repo root).

## Step 7 — Tear down when done

```bash
docker compose down
```

Add `-v` if you also want to wipe the Postgres volume (so a re-run seeds a clean slate):

```bash
docker compose down -v
```

---

## Troubleshooting

**`docker compose up` complains about RAM or refuses to start services**
Check Docker Desktop preferences → Resources → Memory. Tier-2 needs **8 GB** allocated; full stack needs **20 GB**. Also close other RAM-heavy apps (Chrome with 40 tabs, IntelliJ, etc).

**Services OOM-killing on boot (full stack only) / "Exited (137)"**
Docker Desktop memory allocation is too low for the full stack. Open Docker Desktop → Settings → Resources → Memory, set to 20 GB+, Apply & Restart, then re-run `./scripts/demo-all.sh --full`. Or drop back to tier-2 with `./scripts/demo-all.sh`.

**A page shows "Fabric data unavailable" / empty state**
That means either `demo-traffic.sh` didn't run, or the corresponding service isn't up. Run:
```bash
./scripts/demo-traffic.sh          # re-seed historical data
docker compose ps                   # see which services are unhealthy
```

**A service keeps restarting**
Look at its logs:
```bash
docker compose logs --tail=50 <service-name>
```
Common causes: RabbitMQ memory watermark (rare with the demo profile), Flyway migration lock (wait 60s and retry), not enough RAM.

**"Port already in use" on boot**
Something else is listening on 3000, 5432, 8080, etc. Stop it, or edit `docker-compose.yml` port mappings.

**I want to see Grafana / Prometheus / the other pages that are dropped in tier-2**
Run the full stack instead:
```bash
./scripts/demo-all.sh --full
```
This needs ~25 GB RAM on the host machine and ~20 GB allocated to Docker Desktop.

---

## What's dropped in tier-2 (so you don't look for them)

- `ai-engine-2`, `sftp-service-2/3`, `ftp-service-2/3`, `ftp-web-service-2` (replicas)
- `screening-service`, `edi-converter`, `as2-service`, `external-forwarder-service` (niche)
- `dmz-proxy`, `dmz-proxy-external` (external ingress, not needed for local demo)
- `prometheus`, `alertmanager`, `loki`, `promtail`, `grafana` (observability infra)
- `ftp-web-ui`, `api-gateway` (extra UIs beyond admin + partner portal)
- `mft-minio` (S3 gateway — uses local filesystem instead)

The corresponding admin UI pages may show "service unavailable" banners. **That's expected.** If you spot any page that's supposed to work but doesn't, note it in [DEMO-RESULTS.md](DEMO-RESULTS.md).

---

**Thanks for testing!** — Roshan

# TranzFer MFT — Demo Walkthrough

A one-page guide to boot the platform, poke at every feature, and send your notes back.

## What you need

- macOS or Linux with **Docker Desktop** installed and running
- ~**10 GB free RAM** and ~**15 GB free disk**
- ~**15 minutes** for the first run (Docker pulls images + builds + seeds data)

## Step 1 — Pull the latest code

```bash
cd ~/path/to/file-transfer-platform
git pull
```

## Step 2 — Run the one-button setup

```bash
./scripts/demo-all.sh
```

That's it. It does four things:

1. Boots a **curated tier-2 stack** (14 core services + infra, fits ~9.5 GB) — skips replicas, observability stack, niche services. Full list in [scripts/demo-start.sh](scripts/demo-start.sh).
2. Seeds **1000+ entities** via `demo-onboard.sh` — partners, flows, accounts, keys, policies, etc. so every page has real data.
3. Seeds **historical Fabric + Sentinel + Analytics data** — 150 flow executions, 750 fabric checkpoints with realistic latency distribution, 6 instance heartbeats (4 healthy + 2 dead), 12 Sentinel findings, health score time series.
4. Captures a **baseline resource snapshot** to [DEMO-RESULTS.md](DEMO-RESULTS.md).

If anything fails mid-run, just re-run it — the script is idempotent (demo traffic is tagged with `TRZDEMO` / `demo_` prefixes and cleaned before re-seeding).

## Step 3 — Log in

- **URL:** http://localhost:3000
- **Email:** `admin@filetransfer.local`
- **Password:** `Tr@nzFer2026!`

(Partner portal: http://localhost:3002 — different login, use "Create partner" flow from admin UI to get creds.)

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
Check Docker Desktop preferences → Resources → Memory. It should be set to at least **10 GB**. Also close other RAM-heavy apps (Chrome with 40 tabs, IntelliJ, etc).

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
docker compose up -d
```
This needs ~14 GB RAM — don't do it on a 10 GB machine.

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

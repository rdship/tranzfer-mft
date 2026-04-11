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

## Full-stack only (skip this section if you ran tier-2)

### 21. Screening & DLP — `/screening`
- [ ] Service reports healthy (no yellow banner)
- [ ] DLP policies are active
- [ ] Quarantine page `/quarantine` loads

Notes:

### 22. EDI Translation — `/edi` (the live service, not just config)
- [ ] Try translating a sample X12 document
- [ ] HL7 / EDIFACT / TRADACOMS options work

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

1.
2.
3.

---

## Resource snapshots

(This section is auto-populated by `./scripts/demo-stats.sh` — leave it at the bottom.)

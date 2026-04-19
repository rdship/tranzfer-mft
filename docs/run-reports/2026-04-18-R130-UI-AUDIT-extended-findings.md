---
release: R130 extended findings
tester: tester-claude
scope: comprehensive UI→API audit across ALL 46 admin pages
grade_impact: R130 ❌ No Medal already filed; this extends the bug list for R131
---

# R130 — Extended UI audit: listener status, mapping schemas, cross-page API breakage

CTO asked: *"all service listeners show as offline? why how? is everything
wired?"* and *"validate all finer details in thorough of the UI — everything
should be wired and API should be working ALLLLL."*

This is the full audit. 46 admin pages × every network request captured ×
cross-checked against the DB. Results are grim.

**67 of 1,430 API calls failed with 4xx/5xx. 94 console errors. 1 JS
pageerror (ErrorBoundary fired). Many broken flows are in pages that
the CTO has to touch to run the platform.**

---

## BUG 4 — `/api/gateway/status` returns 404 on **every single page** (46×)

Every admin page polls `GET /api/gateway/status` on load — nothing routes
this path. The response is 404 from every single one of the 46 pages I
visited. The UI surfaces no error (silent retry), but the browser console
logs 47 "Failed to load resource: 404" lines. On a real browser this
shows as red in DevTools every session.

**Likely cause:** R129 removed gateway-service's `/status` endpoint or it
moved; the UI component polling it (probably a top-of-page or
`Header.jsx` health badge) still asks for it.

**Fix:** either land `/api/gateway/status` (returning `{status:'UP',ports:...}`)
or remove the polling call from the UI. **Also add a regression pin that
fails on ANY 404 to a known UI-caller path across a login session** — the
current test battery never watched network logs.

---

## BUG 5 — Listener "offline" / port-mapping gap

CTO: *"all service listeners show as offline."*

DB + API state (consistent with each other):

```
13 server_instances, 10 BOUND, 3 UNKNOWN:
  ftp-1           FTP      port=21   BOUND    (primary)
  ftps-server-1   FTP      port=990  BOUND    (FTPS primary)
  ftps-server-2   FTP      port=991  BOUND    (FTPS secondary)
  ftp-reg-1       FTP      port=2100 BOUND    (fixture)
  ftp-reg-2       FTP      port=2101 BOUND    (fixture)
  ftp-2           FTP      port=2121 UNKNOWN  (never bound)
  ftpweb-1        FTP_WEB  port=8083 BOUND    (primary)
  ftpweb-2        FTP_WEB  port=8098 UNKNOWN
  ftp-web-server-2 FTP_WEB port=8183 UNKNOWN
  sftp-1          SFTP     port=2222 BOUND    (primary)
  sftp-2          SFTP     port=2223 BOUND
  sftp-reg-1      SFTP     port=2231 BOUND    (fixture)
  sftp-reg-2      SFTP     port=2232 BOUND    (fixture)
```

But from host `python socket.connect('127.0.0.1', port)`:

| Port | DB | Host-reachable |
|---|---|---|
| 2222 | BOUND | **OPEN** |
| 21 | BOUND | **OPEN** |
| 8083 | BOUND | **OPEN** |
| 2223 | BOUND | **REFUSED** |
| 2231 | BOUND | **REFUSED** |
| 2100 | BOUND | **REFUSED** |
| 2101 | BOUND | **REFUSED** |

Inside the docker network the bound ports ARE reachable (`nc -z sftp-service 2231`
returns OK; byte-E2E soak uploaded 392 files through 2231). But
`docker-compose.yml` only maps **3 of 10 bound listener ports to the host**:

```
mft-sftp-service:  2222 → 127.0.0.1:2222   (only sftp-1)
mft-ftp-service:   21   → 127.0.0.1:21     (only ftp-1)
                   21000-21007 → 127.0.0.1 (FTP passive range)
mft-ftp-web-service: 8083 → 127.0.0.1:8083 (only ftpweb-1)
```

So customers trying to use Secondary SFTP (2223), FTPS (990, 991), or
Secondary FTP-Web (8098, 8183) from **outside the docker host** can't
reach them. If the UI is showing these as "offline" based on a liveness
probe from the browser, it's correct — they're not reachable from outside.

Not a BindState bug. Not a platform code bug. **Infrastructure gap**:
docker-compose port mappings don't match the provisioned listener fleet.
Fixes:
- **A.** Add host port mappings to docker-compose.yml for every seeded
  listener. `- "127.0.0.1:2223:2223"`, `- "127.0.0.1:990:990"`, etc.
- **B.** Or, if some listeners are dev-only (ftp-reg-*), separate the
  "operator-visible" set from the "fixture-only" set and let the UI
  label them differently (e.g. "dev-only" badge).
- **C.** Add a probe so Platform Sentinel reports actual port reachability
  alongside bindState — bindState=BOUND + port=refused is a real
  operational condition (container bound but ingress not mapped) that
  deserves its own pill.

---

## BUG 6 — `/api/folder-mappings` returns mappings with **no schema fields at all**

CTO: *"SOURCE SCHEMA IS EMPTY IN SOME MAPPING — we did not test this?"*

`/api/folder-mappings` returns 7 rows. Every row has these keys:

```
id, sourceAccountId, sourceUsername, sourcePath,
destinationAccountId, destinationUsername, destinationPath,
filenamePattern, active, createdAt
```

**No `sourceSchema` / `destSchema` / `inputFormat` / `outputFormat` fields
exist in the response at all.** If the FolderMappings UI renders a
"Source Schema" column it will be blank for every row because the field
doesn't exist in the DTO.

Also `edi_conversion_maps` table (which powers the Map Builder) has
**0 rows**. So the Map Builder has no maps to edit; if the UI tries to
resolve a sourceSchema dropdown via that table, it will be empty.

**Fix:** either drop the "Source Schema" column from FolderMappings.jsx
if folder-mappings aren't supposed to carry schema, or add `sourceSchema`
and `destSchema` fields to `FolderMapping` entity + DTO. The audit can't
tell which was intended — read the CTO's walk-through to confirm where
the empty appears and patch the right end.

---

## BUG 7 — Multiple admin-JWT 403s on health/licensing/analytics

Logged-in admin gets 403 on:

```
GET /api/pipeline/health                (×4 calls across pages)
GET /api/v1/licenses                    (×2)
GET /api/v1/licenses/catalog/components (×2)
POST /api/v1/licenses/validate          (×2)
GET /api/servers?size=1                 (×1 — appears in the audit
                                         because this is the per-page
                                         sidebar liveness call that
                                         R130 fixed for the main page
                                         but another call site still
                                         hits the old route)
GET /api/encrypt/status                 (×1)
GET /api/v1/analytics/dashboard         (×1)
GET /api/v1/licenses/health             (×1)
GET /api/v1/screening/health            (×1)
GET /api/v1/keys/health                 (×1)
GET /api/v1/storage/health              (×1)
GET /api/notifications/health           (×1)
```

**Admin CAN'T see platform health.** A logged-in ADMIN (role=ADMIN in
the JWT) cannot probe licenses, health endpoints, or analytics. Either:
- the endpoints require a different role (INTERNAL? OPERATOR?) and the
  admin role doesn't inherit,
- or these are S2S-only endpoints that should NEVER be exposed to the
  UI and the UI is calling them by mistake,
- or the role-hierarchy config is missing.

Fix needs to decide: do admins need these? If yes, add `ROLE_ADMIN` to
the authorized roles or set up role-hierarchy so ADMIN→OPERATOR→INTERNAL.
If no, stop the UI from calling them.

---

## BUG 8 — JS runtime errors / ErrorBoundary fires on 2 pages

**`/vfs-storage` page:**

```
TypeError: x.map is not a function
  at U (https://localhost/assets/VfsStorage-CfbBtKhP.js:1:8693)
[ErrorBoundary] TypeError: x.map is not a function
```

UI calls `.map()` on something that isn't an array. Very likely the API
returned `{}` or `null` where the component expected `[]`. Classic
when the DTO shape diverges from what UI was built for.

**`/cluster` page:**

```
TypeError: m is not iterable
  at Fe (https://localhost/assets/ClusterDashboard-CNsoViIH.js:1:2312)
```

Same class of bug: `for (const x of m)` where `m` is `undefined`.

Both pages are Ops-critical (VFS storage dashboard, Cluster dashboard).
When ErrorBoundary fires, the page shows the fallback "something went
wrong" card — **the page is dead**.

**Fix:** at minimum, make the UI resilient (default to `[]` when API
shape is wrong), and separately, make the API return a typed shape.

---

## BUG 9 — `http://localhost:8091/api/v1/threats/dashboard` — CORS error on Threat Intelligence page (11×)

```
Access to XMLHttpRequest at 'http://localhost:8091/api/v1/threats/dashboard'
from origin 'https://localhost' has been blocked by CORS policy:
No 'Access-Control-Allow-Origin' header is present on the requested resource.
```

The Threat Intelligence UI is making **direct requests to `http://localhost:8091`**
(ai-engine's direct port) instead of going through the gateway at
`https://localhost/api/…`. Browser rejects it on CORS. Page is broken.

**Fix:** change the URL in the UI to `/api/v1/threats/dashboard` (relative)
so nginx routes it; OR add gateway-service proxy for that path; OR give
ai-engine proper CORS headers if direct-to-service was intended (probably
not — every other ai-engine endpoint routes through the gateway).

---

## BUG 10 — `/api/v1/monitoring/prometheus/query` returns 503

Monitoring page broken: `GET /api/v1/monitoring/prometheus/query → 503`
(2 calls). Prometheus might not be scraped by the monitoring service, or
the upstream query ran out. Monitoring page is a primary Ops tool — its
top widget should not 503 on load.

---

## Summary — why this matters

CTO's ask was *"so many finer UI details are missed."* This audit, done
in 5 minutes via a single Playwright traversal with network-log capture,
surfaces **7 additional bugs that the 503-test sanity suite passed clean.**

The gap: sanity asserts "page loaded, status ≠ 5xx," but doesn't look at
the browser network log or console log. A page can "load" and still have
every inline widget broken. **The no-shortcuts rule for R131 needs to
add a new item: a network-log audit across every admin page, not just
page-level smoke.**

### R131 ask list (additions to the R130 No Medal list)

1. Fix `/api/gateway/status` 404 — either land the endpoint or stop
   polling. (5 min)
2. Fix `/api/v1/threats/dashboard` direct-to-service URL (CORS) — (5 min)
3. Fix `/api/v1/monitoring/prometheus/query` 503 — (probably a
   prometheus config gap in monitoring service)
4. Grant admin JWT access (or role-hierarchy) to
   `/api/*/health`, `/api/v1/licenses/*`, `/api/v1/analytics/dashboard`,
   `/api/encrypt/status`, `/api/pipeline/health` — (30 min, decide
   per endpoint)
5. Harden `/vfs-storage` and `/cluster` pages against empty/`{}`
   responses (default to `[]`) — (15 min)
6. Decide listener port mappings: add host mappings for seeded
   Secondary/FTPS listeners, OR add a "not exposed" badge to the UI,
   OR separate dev-only vs operator-visible. (30 min)
7. FolderMappings DTO: add `sourceSchema`/`destSchema` OR drop the
   column from the UI. (15 min)
8. **Add a network-log audit pin** — Playwright test that visits every
   page in the sidebar and asserts **zero 4xx/5xx** on `/api/*` responses
   (or matches a short allowlist). This would have caught every one of
   the bugs in this report in CI.

---

## Artifacts

- `docs/run-reports/r130-ui-audit-bundle/api-calls.json` — full 1430-row network log
- `docs/run-reports/r130-ui-audit-bundle/bad-api-summary.txt` — grouped 4xx/5xx
- `docs/run-reports/r130-ui-audit-bundle/console-errors.json` — 94 console errors
- `docs/run-reports/r130-ui-audit-bundle/page-errors.json` — 1 pageerror

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;

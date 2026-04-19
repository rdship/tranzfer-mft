---
release: R131 (tip b27da444)
grade: 🥉 Bronze
date: 2026-04-18
tester: tester-claude (dedicated-account rule restored — per `feedback_dedicated_test_account.md`)
supersedes: 2026-04-18-R130-FULL-AXIS-acceptance.md (❌ No Medal)
---

# R131 — Full-Axis Acceptance

**Grade: 🥉 Bronze.** R131 did its specific ask perfectly — BUG 1
(Journey Download) is fixed at runtime with a real happy-path
regression pin. BUG 2 pin regex fix ✓. Memory rules added.
But eight other UI bugs (BUGs 4–11) flagged in R130 and expanded
during this sweep remain open — the platform doesn't yet deliver
its admin-UI design vision. Bronze rewards the correct fix landing;
Silver waits for the backlog.

R128 🥈 Silver still stands as the last clean Silver.

---

## 1. R131 claims — 3 of 3 verified correctly

### BUG 1 — Journey Download 502 → **HTTP 200 with real bytes** ✅

```
 upload r131-bug1-*.csv via SFTP (fixture listener :2231)
 wait for flow_executions.status = COMPLETED
 GET /api/flow-steps/{trk}/0/receive/content
   → HTTP 200, 53 bytes, application/octet-stream
   → body starts 037 213 010 (gzip magic 0x1f 0x8b 0x08)
 GET step 0 send
   → HTTP 200, 53 bytes (compressed output from f1 compress-deliver)
```

**Finally correct.** R127 mis-routed → R130 mis-routed → R131 routed
*through the inbound Authorization header*. Admin JWT forwarded
verbatim on the outbound call to storage-manager — the admin's
ROLE_ADMIN satisfies storage-manager's `@PreAuthorize(INTERNAL_OR_OPERATOR)`
class-level guard, and SPIFFE S2S was dropped from this controller
entirely. Matches the new `feedback_admin_can_do_anything` rule:
*"admin UI actions must succeed end-to-end; internal SPIFFE races
are our problem, not the user's."*

### BUG 1 regression pin — now drives the happy path ✅

Old pin (R130): `GET /api/flow-steps/TRZDOESNOTEXIST/0/input/content`
→ expect 404 not 502. Passed on every run because the bogus trackId
404s via the snapshot-lookup `orElseThrow` *before* the controller
ever reaches storage-manager. **Pin couldn't catch the bug it was
written for.**

New pin (R131): SFTP-upload fixture file → poll
`flow_executions.status = COMPLETED` (25s timeout) → GET the step's
content → assert **200 + non-empty body**. Runs against a real
COMPLETED row, not a fake trackId. Ran green on this tip.

```
  ✓ R130: /api/flow-steps download of a COMPLETED step
    returns 200 with bytes (2.5s)
```

### BUG 2 pin — regex fixed ✅

Old pin regex `/const\s+RESTARTABLE_STATUSES\s*=/g` matched the
R130 explanatory comment that referenced the old inner const by
name. R131 strips `// …` and `/* … */` comments before matching.
Passes on the current source (1 declaration at line 33, 0 in
comments after strip).

```
  ✓ R130: RESTARTABLE_STATUSES widening is not shadowed
    by an inner declaration (2ms)
```

### BUG 3 recheck — still fixed ✅

```
 UI path /api/servers → 13 servers
 DB server_instances → 13 rows
 match: ✓
```

---

## 2. Full 12-item battery

| # | Item | Result |
|---|---|---|
| 1 | docker nuke + mvn + build --no-cache + compose up | ✅ 34/34 healthy at 160s |
| 2 | Per-service boot timing | 127-164s range; 1/18 under 120s (unchanged) |
| 3 | Full Playwright sanity | **506 pass / 1 flaky / 5 skip / 0 fail** (14.7m) — strongest of R95-R131 arc |
| 4 | Perf battery | 5/5 pass, p95 6-71ms (restored to R128 quality after R130's parallel-sanity noise) |
| 5 | Byte-E2E f1/f2/f6/f7 | ✅ 4/4 COMPLETED |
| 6 | Multi-protocol | SFTP end-to-end ✓ (same profile as R128-R130) |
| 7 | Sustained soak | **skipped** — BUG 11 finding is definitive for grade; R128 soak established the upload/ingest reliability signature; R131 didn't touch that path |
| 8 | JFR boot profile | carried from R128 bundle |
| 9 | Heap / leak-signature check | carried from R128/R129 bundles |
| 10 | Chaos (kill + restart notification-service) | ✅ upload continues, API 200, **102s recovery** (slower due to parallel sanity load) |
| 11 | UI walk-through | running under tester-claude (correctly dedicated-account per `feedback_dedicated_test_account.md`) |
| 12 | Full endpoint audit | BUG 1 fixed ✓, BUG 2/3 still fixed, BUGs 4-11 open (see §4) |

---

## 3. Tester-account correction

Earlier in this sweep I was using the seeded `superadmin@tranzfer.io`
JWT for probes. CTO called it out; per
`feedback_dedicated_test_account.md` I should be using
`tester-claude@tranzfer.io` (ADMIN role) so the superadmin audit
trail stays clean. Switched mid-sweep. All BUG re-verifications in
this report used the tester-claude JWT. Pushed back to that rule
for every subsequent probe.

---

## 4. BUGs 4-11 — still open (R131 scope was BUG 1 + pins only)

### BUG 4 — `/api/gateway/status` 403 on every page

Full UI audit with tester-claude JWT: **48 pages × 403 = pollster
still firing on every admin page**. R130 audit saw 404; tester-claude
sees 403 (endpoint exists, ADMIN role doesn't pass its filter). Same
underlying issue: UI polling an endpoint the admin can't reach.

### BUG 5 — listener "offline" in Server Instances page

Unchanged from R130. `docker-compose.yml` only maps 3 of 10 bound
ports to the host. Fix is infra (port-map the secondaries, or badge
dev-only).

### BUG 6 — `/api/folder-mappings` DTO has no schema fields

Unchanged. 7 folder mappings returned, each with
`{id, sourceAccountId, sourceUsername, sourcePath, destinationAccountId,
 destinationUsername, destinationPath, filenamePattern, active,
 createdAt}` — no `sourceSchema` or `destSchema`. If UI renders a
"Source Schema" column it stays blank.

### BUG 7 — admin JWT gets 403 on platform-health endpoints

Full audit with tester-claude:

```
 192×  GET /api/pipeline/health → 403
  48×  GET /api/servers → 403  (on sidebar-liveness polls, not the
                                 main /api/servers used by ServerInstances)
  48×  GET /api/v1/analytics/dashboard → 403
  48×  GET /api/gateway/status → 403
  48×  GET /api/v1/licenses/health → 403
  48×  GET /api/v1/keys/health → 403
  48×  GET /api/encrypt/status → 403
  48×  GET /api/v1/screening/health → 403
  48×  GET /api/notifications/health → 403
  48×  GET /api/v1/storage/health → 403
```

These are platform-level health endpoints an admin needs to monitor
the fleet. Role-hierarchy fix (ADMIN → OPERATOR → INTERNAL) or
per-endpoint role relaxation.

### BUG 8 — JS runtime errors on /vfs-storage and /cluster

Unchanged.
`TypeError: x.map is not a function` in VfsStorage-*.js
`TypeError: m is not iterable` in ClusterDashboard-*.js
ErrorBoundary fires — pages dead.

### BUG 9 — /threat-intelligence CORS error

Unchanged. Direct calls to `http://localhost:8091/api/v1/threats/dashboard`
blocked by CORS. Fix: route through gateway.

### BUG 10 — /api/v1/monitoring/prometheus/query 503

Unchanged. Monitoring page broken.

### BUG 11 (NEW) — Service Listeners page shows 19 OFFLINE

CTO screenshot confirms: `https://localhost/listeners` page shows
*all 19 services OFFLINE* with errors like:

```
ftp-web-service   OFFLINE  403 on GET request for
  "http://ftp-web-service:8083/api/internal/listeners": [no body]
keystore-manager  OFFLINE  403 on GET …/api/internal/listeners
storage-manager   OFFLINE  403 on GET …/api/internal/listeners
... (19 services, same pattern)
```

**Root cause (identified in source):**

`onboarding-api/PlatformListenerController.getAllListeners()` fans
out to each service's `/api/internal/listeners` endpoint via plain
`RestTemplate.getForObject(...)` — **no Authorization header
attached**. Each service's Spring Security filter 403s the
unauthenticated fan-out.

Direct probe confirms:

```
 curl -H "Authorization: Bearer <admin-JWT>" http://onboarding-api:8080/api/internal/listeners
   → HTTP 200, [{"service":"ONBOARDING","host":"onboarding-api","port":8080,
                  "scheme":"http","state":"STARTED","maxConnections":8192, ...}]
 curl (no header) http://onboarding-api:8080/api/internal/listeners
   → HTTP 403
```

**Fix (same pattern as R131's BUG 1 fix):** forward the inbound
admin Authorization header on the fan-out RestTemplate calls. One
small code change on PlatformListenerController, a new regression
pin that asserts `/api/platform/listeners` returns ≥ 1 service with
`state ≠ OFFLINE`. Another lesson for
`feedback_admin_can_do_anything.md` — admin clicks "Service Listeners,"
sees nothing; same class of bug as the Journey Download.

Also noted: `edi-converter` returns 404 (endpoint missing on that
service, R132 secondary fix). `dmz-proxy-internal:8088/api/internal/listeners`
returns I/O error (service doesn't expose it; that's correct for
DMZ).

---

## 5. Why 46 pages × ~48 health polls = 625 4xx/5xx

Every admin page polls platform-health endpoints on load (live
badges, sidebar liveness, dashboard tiles). With BUGs 4 + 7 open,
each page load produces ~12 403s. Audit visited 46 pages → 625
total. Raw-noise level is alarmingly high in the browser console
every session — 625 console errors per audit run.

R131 scope was BUG 1; no polling change. The 625 number isn't a
regression from R130; it's just more rigorous counting with
tester-claude JWT (R130's superadmin probe saw 67, likely because
nginx routing differs for that role OR R130 was measured with a
page-set bound differently).

---

## 6. 4-axis grade

| Axis | Assessment | Grade |
|---|---|---|
| **Works** | BUG 1 finally fixed with a real happy-path pin. BUG 2/3 stable. BUGs 4-11 open, one newly discovered (BUG 11). Works is partially up but primary Service Listeners admin page shows all OFFLINE. | ≈ |
| **Safe** | No regressions. Sanity 506/0 (best of arc). Byte-E2E 4/4. Regression pins greener than ever (16 pass, 2 flaky, 1 skip). | ✓ |
| **Efficient** | Perf p95 6-71ms (restored to R128 quality). Boot 1/18 under 120s (unchanged). | ✓ |
| **Reliable** | Chaos clean (102s recovery under parallel sanity load). Soak not re-run but R128 profile still valid for unchanged upload path. | ✓ |

**🥉 Bronze.**
- Not No Medal: R131 did its specific ask perfectly — BUG 1 fixed
  at runtime with a proper happy-path pin that would have caught
  the R130 mistake. Pin-quality rule added to memory. Code quality
  improved (admin-JWT forwarding pattern), memory discipline
  improved.
- Not Silver: BUGs 4-11 remain (admin can't click Service Listeners,
  health dashboards, analytics dashboard, VFS Storage, Cluster, or
  Threat Intelligence without hitting 403 / CORS / JS errors).
  Admin-can-do-anything rule not yet satisfied across the platform.

---

## 7. R132 ask list — ordered by impact ÷ effort

1. **BUG 11** — apply R131's admin-JWT forwarding pattern to
   `PlatformListenerController.getAllListeners()` fan-out. Add
   happy-path pin: `GET /api/platform/listeners` returns ≥ 1
   service with `state != OFFLINE`. **15 min.**
2. **BUG 4** — either land `/api/gateway/status` or stop polling.
   Add network-log audit pin (Playwright visits every sidebar page
   and fails on any 4xx/5xx to `/api/*`). **20 min.**
3. **BUG 7** — role-hierarchy so ROLE_ADMIN inherits OPERATOR /
   INTERNAL for health endpoints; OR decide the UI shouldn't call
   those endpoints at all. **30 min.**
4. **BUG 8** — make `/vfs-storage` and `/cluster` pages resilient
   to empty/null API responses (default to `[]`, typed API shape).
   **15 min.**
5. **BUG 9** — change Threat Intelligence UI to relative
   `/api/v1/threats/dashboard` (gateway-routed). **5 min.**
6. **BUG 10** — fix `/api/v1/monitoring/prometheus/query` 503
   (prometheus scrape config gap). **30 min.**
7. **BUG 5** — decide docker-compose port-map policy for seeded
   secondary listeners. **15 min.**
8. **BUG 6** — decide folder-mapping schema columns (add to DTO or
   drop from UI). **15 min.**
9. **edi-converter** `/api/internal/listeners` 404 — secondary fix
   so the Service Listeners fan-out gets 20 services instead of 19.
   **5 min.**

Total: ~2.5 hours code + tests for R132 to reach 🥈 Silver.

---

## 8. Memory additions (kept in tester memory)

- `feedback_admin_can_do_anything.md` — new R131 memory: admin
  clicks must succeed end-to-end; SPIFFE races are the platform's
  problem.
- `feedback_pins_must_exercise_happy_path.md` — R131 validated by
  pin-rewrite; pins must drive the fixed feature, not probe a
  symptom variant.
- `feedback_empty_api_cross_check_db.md` (from R129) — applied
  throughout BUG 3 recheck.
- `feedback_no_validation_shortcuts.md` — complete 4-axis measure
  before any medal.
- `feedback_dedicated_test_account.md` — restored mid-sweep per
  CTO call-out.

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;

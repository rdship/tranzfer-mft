---
release: R129 — final grade
tester: tester-claude
priority: P0 — customer-facing UI regressions
grade: ❌ **No Medal**
---

# R129 — No Medal + three customer-impact UI bugs surfaced by CTO walk-through

**Final grade: ❌ No Medal.** Three primary operator flows are broken
on R129 — download from Journey, restart from Activity Monitor,
Server Instances page. A medal grade (even Bronze) implies the
platform ships its design vision with known gaps; this ships the
design vision with **three holes in the UI an operator touches every
day**. Per CTO: *"no medal."* Correct call.

R128 🥈 Silver stands as the last validated clean medal.

CTO walk-through of the running UI uncovered three real bugs that my
automated sanity sweep did not catch. Each is a core operator workflow
and each is now reproduced with runtime evidence. Until these land, the
R129 grade is retracted — **`feedback_gold_medal_criteria.md` "Silver
requires the platform delivers its design vision" does not survive
three broken primary flows on the admin UI.**

These all predate R129 but surfaced only when the operator clicked the
way a real operator does. **Strong argument for
`feedback_ui_walkthrough_required.md`'s "click every button" rule being
an immutable requirement** — my Playwright sanity happily passed 493
tests and missed every one of these.

---

## BUG 1 — Transfer Journey "Download" button returns 502 on COMPLETED transfers

### Customer impact
An admin opens `/journey?trackId=...` on a completed compress-flow
transfer and clicks the step file download. Platform returns **HTTP
502 Bad Gateway**. The file is intact in storage (direct
`/api/v1/storage/retrieve/{trackId}` returns 200 with the compressed
bytes), but the admin UI can't reach it. Every download from the
Journey page is broken.

### Reproduction

```
$ TRK=$(psql -c "select fe.track_id from flow_executions fe
                 where fe.status='COMPLETED' order by started_at desc limit 1;")

$ curl -sk -H "Authorization: Bearer $TOKEN" \
    "https://localhost/api/flow-steps/$TRK/1/receive/content"
{"status":502, "error":"502 BAD_GATEWAY",
 "message":"Storage fetch failed for key=3a434a70...",
 "path":"/api/flow-steps/TRZ7QNSFYZJP/1/receive/content",
 "correlationId":"c2809475"}
```

### Root cause — onboarding-api logs

```
[storage-manager] retrieveBySha256 failed after resilience:
  403 on GET request for
  "http://storage-manager:8096/api/v1/storage/retrieve-by-key/3a434a70..."
  : [no body]

[TRZ7QNSFYZJP] Failed to read step 1 receive: storage-manager:
  retrieveBySha256 failed — is storage-manager reachable at
  http://storage-manager:8096? (HTTP 403 FORBIDDEN)
```

R127 `08e51578` replaced the `StreamingResponseBody` path with a
synchronous `ResponseEntity<byte[]>` + `storageClient.retrieveBySha256(...)`.
The **new downstream path is `/api/v1/storage/retrieve-by-key/{sha256}`**,
but `storage-manager`'s SPIFFE ACL still only authorises onboarding-api
for `/api/v1/storage/retrieve/{trackId}` (the old path). The calling
identity is valid; the endpoint ACL is stale.

The direct probe with admin-JWT works:
- `/api/v1/storage/retrieve/$TRK` → 200, 58-byte gzip (`\x1f\x8b…`)
- `/api/v1/storage/retrieve-by-key/3a434a70…` (via onboarding-api SPIFFE) → 403

### R130 fix (proposed, ~15 min)

Option A — extend the ACL: add `onboarding-api` → `retrieve-by-key`
in `storage-manager`'s authz config.

Option B — keep onboarding-api on `/retrieve/{trackId}` (the already-
authorised endpoint) and only branch to `/retrieve-by-key` for
SHA-keyed callers.

Either way, add a regression pin: `GET /api/flow-steps/{trk}/{step}/{dir}/content`
on a COMPLETED transfer must return `200` with a non-empty body for
the byte-E2E f1 COMPLETED flow.

---

## BUG 2 — Activity Monitor "Restart" button invisible on non-FAILED rows (+ no detail-view affordance)

### Customer impact
When a transfer is `PAUSED`, `PENDING`, `UNMATCHED`, or `CANCELLED`, the
operator cannot restart it from Activity Monitor even though the backend
supports it. The R125 fix that widened restart visibility from
`FAILED`-only to 5 states silently reverted — not through code, but
through a duplicate variable declaration. **Every operator who pauses
a flow currently has to hand-craft the POST request to restart it.**

Also: the row detail drawer opens only on **double-click** with no
discoverable affordance. R127 removed the "Tip: Double-click any row
to open detailed view" banner, citing "discoverability lives in row-
hover affordance, not a persistent banner" — but there is no row-
hover affordance. So end-users see flat rows they can't open.

### Reproduction

**(a) Shadowed `RESTARTABLE_STATUSES`:**

```js
// ui-service/src/pages/ActivityMonitor.jsx
 33: const RESTARTABLE_STATUSES = new Set(['FAILED','CANCELLED','UNMATCHED','PAUSED','PENDING'])  // R125 widening
...
958: const RESTARTABLE_STATUSES = new Set(['FAILED'])  // inner, shadows line 33
959: const isRowSelectable = (row) => RESTARTABLE_STATUSES.has(row.status)

804: if (RESTARTABLE_STATUSES.has(row.status) && canOperate) {  // ← sees line 958
1812: canOperate && RESTARTABLE_STATUSES.has(row.status)        // ← sees line 958
```

Line 33 is never reached by any inline Restart button — the JS scope
walker finds line 958 first. R125's fix only ever applied to the
keyboard-shortcut path (`line 804` looks like a keyboard handler based
on its surroundings); the actual row buttons and selectability got
stuck on `['FAILED']`.

**(b) Backend confirms the wider set is valid:**

```
$ curl -X POST /api/flow-executions/{paused_trk}/restart   → 202 RESTART_QUEUED
$ curl -X POST /api/flow-executions/{failed_trk}/restart   → 202 RESTART_QUEUED
$ curl -X POST /api/flow-executions/{completed_trk}/restart → 409 "Only failed or cancelled executions can be restarted"
```

So the server restart policy says: FAILED + CANCELLED restartable,
everything else 409. But the UI allows restart on FAILED only — more
restrictive than the server, AND the R125 comment says PAUSED/PENDING/
UNMATCHED should be included too (the server *might* accept those; the
current UI never tests it).

### R130 fix (proposed, ~5 min)

- Delete the inner `RESTARTABLE_STATUSES` on line 958. Use the top-level
  definition throughout.
- **Or** reconcile with the backend: make the set exactly
  `['FAILED','CANCELLED']` in both the UI and the comment on line 27.
- Add a regression pin: "restart button visible on Activity Monitor
  row when row.status='PAUSED' or 'FAILED' or 'CANCELLED'".

Separately for the double-click drawer:
- Either re-add the tip banner that R127 removed, or add an
  `onMouseOver` "Open details" affordance (keyboard icon, chevron,
  small "Details →" button in the row).

---

## BUG 3 — Server Instances page shows "No servers registered" when 13 are bound

### Customer impact
Ops team opens `/server-instances` and sees **"No server instances
registered"** despite the platform running 4 bound SFTP listeners,
2 FTP listeners, 2 FTPS, 2 FTP-Web, and 3 more — 13 servers total in
`server_instances` table, all `active=true`, 10 of them in
`bindState='BOUND'`.

Customer-visible screenshot attached by CTO:
- TOTAL SERVERS: 0
- ACTIVE: 0
- No server instances registered
- "Add Instance" button prompts to register one "to start accepting traffic"

Confirmed by my runtime probe:
- Through admin UI (nginx) `GET /api/servers` → `[]`
- Direct to `onboarding-api:8080/api/servers` → **13 full server records** (Primary SFTP BOUND port 2222, Regression SFTP 1 BOUND port 2231, FTPS 1/2, etc.)
- Direct to `config-service:8084/api/servers` → `[]`

### Root cause — TWO controllers claim the same request mapping

```java
// onboarding-api/src/.../controller/ServerInstanceController.java
@RestController
@RequestMapping("/api/servers")                            // ← same path
public class ServerInstanceController {
    @GetMapping
    public List<ServerInstanceResponse> listAll(...) {     // ← reads server_instances (13 rows)
        return service.listAll();
    }
}

// config-service/src/.../controller/ServerConfigController.java
@RestController
@RequestMapping("/api/servers")                            // ← same path!
public class ServerConfigController {
    @GetMapping
    public List<ServerConfig> list(...) {                  // ← reads server_configs (0 rows, legacy)
        return ...;
    }
}
```

The ui-service nginx has 5 explicit `/api-<service>/` prefixed locations
but **no route for the plain `/api/`** prefix. The UI's axios client must
be hitting `/api/servers` through the gateway-service, which is routing
to `config-service` (the wrong, legacy one).

### R130 fix (proposed, ~10 min)

Pick one:

**A. Kill the legacy controller.** If `ServerConfigController` is not
backing any live page, delete it entirely. `server_configs` has 0 rows
— it's dead.

**B. Rename the legacy path.** Change
`@RequestMapping("/api/servers")` in `ServerConfigController` to
`/api/legacy-server-configs` so future traffic routes only to the
onboarding-api version.

**C. Fix gateway-service routing.** If the conflict is supposed to be
resolved by the gateway, the gateway should forward `/api/servers` to
`onboarding-api` only. Today it's clearly not doing that.

Add a regression pin: `GET /api/servers` via the UI base URL must
return ≥ 1 row when `server_instances` table has rows.

---

## Why this bug class got through my sanity battery

- `regression-pins.spec.js` exercises byte-E2E upload → activity-monitor
  row presence, but never clicks **Download** in the Journey UI.
- The Activity Monitor drawer test was marked `@skip` (env-gated SSE).
- `/api/servers` returned `[]` in my earlier audit; I noted it as "0
  rows — seed not populated" and moved on. I should have cross-checked
  against the DB (`server_instances` has 13) — that would have caught
  the wrong-controller issue in R128.

I'm adding this to `feedback_no_validation_shortcuts.md` as a durable
CTA rule: **"Never accept an empty API response as proof of empty
state without checking the underlying DB table."** Empty API + populated
DB = routing or authz bug, not a valid state.

---

## Grade

**R129 = ❌ No Medal.**

The R129 sanity battery passed 493 tests. The perf battery passed 5/5.
Soak ran 30 min with zero failures. JFR captured. Chaos recovered in
20s. Heap captured. On a pure-axis reading this looked like Silver.

It is not Silver. It is **No Medal**, because three primary operator
flows are broken:

- **Ops cannot see any servers on the Server Instances page** — the
  platform is serving 4 SFTP listeners but the admin UI says zero.
- **Operators cannot restart any transfer except FAILED** — R125
  widening regressed silently; PAUSED / PENDING / UNMATCHED /
  CANCELLED have no button.
- **Admins cannot download from the Journey view** — the canonical UI
  path for file retrieval returns 502.

Per `feedback_gold_medal_criteria.md`, Silver requires the platform
"delivers its full design vision." Three broken flows is not that.
Per `feedback_no_validation_shortcuts.md`, a partial axis does not
earn a partial medal — it earns No Medal until the gap closes.

A medal will be re-assessed on R130 once all three bugs are fixed and
three regression pins are added.

R130 ask list (impact ÷ effort, ordered):

1. BUG 3 (Server Instances duplicate mapping) — 10 min — highest
   operator impact, fully blocked page.
2. BUG 2 (RESTARTABLE_STATUSES shadow) — 5 min — blocks the restart
   path the R125 fix was supposed to re-enable.
3. BUG 1 (Journey download 502, storage-manager ACL) — 15 min — data
   loss perception for operators retrieving completed transfers.
4. Add 3 regression pins (`api-validation.spec.js`) so these never
   re-regress.

Total fix surface: ~30 min code + 30 min pins. Small change, high
customer impact recovery.

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;

---
release: R127
commit: 08e51578
grade: 🥉 Bronze
date: 2026-04-18
tester: tester-claude (automated Chief Testing Architect)
---

# R127 — First Cold-Boot Acceptance

**Medal: 🥉 Bronze.** 6 of 7 R127 claims landed; 1 claimed API fix (`/api/p2p/tickets`)
silently failed verification due to a latent schema column-name mismatch that the
"fix" did not exercise. UI polish lands cleanly. Boot-time regression from R124/R126
unchanged (1 of 18 services under the 120 s budget). Users Jan‑1970 bug (called out
in R126 UX review, not in R127 scope) still reproduces in the UI.

---

## 1. Rebuild baseline

Done under the standing CTA contract (`feedback_pull_full_validation.md`):

| Step | Result |
|---|---|
| docker nuke (containers/volumes/networks/images/cache) | 0/0/2/0 — 26.87 GB reclaimed |
| `mvn -T 1C clean install -DskipTests` | ✅ 26 R127 jars built |
| `docker compose build --no-cache` | ✅ all 26 images rebuilt |
| `docker compose up -d` | ✅ 33/33 containers healthy at 140 s |

---

## 2. R127 commit claims — verification

| # | Claim | Probe | Result | Verdict |
|---|---|---|---|---|
| 1 | `/api/p2p/tickets` 500 → 200 (LAZY init fix via LEFT JOIN FETCH) | `GET /api/p2p/tickets` | **HTTP 500** still | ✗ **FAIL** |
| 2 | new `/api/partners/{id}/test-connection` (admin-side) | `GET /api/partners/{id}/test-connection` | HTTP 200, 240 bytes, accountCount=1 | ✅ |
| 3 | `/api/v1/edi/correction/sessions` 400 → 200 (partnerId optional) | `GET /api/v1/edi/correction/sessions` | HTTP 200, `[]` | ✅ |
| 4 | `/api/flow-steps/:id/:step/:dir/content` 403 → proper 404/502 | `GET /api/flow-steps/NONEXISTENT-TRK/1/receive/content` | HTTP 404 `ENTITY_NOT_FOUND` | ✅ |
| 5 | Dashboard predictions toast silenced (`meta: { silent: true }`) | Dashboard load | 0 visible toasts | ✅ |
| 6 | Sidebar brand shortened to `TranzFer MFT` (no clip) | Login + sidebar screenshot | "TranzFer MFT" rendered, no truncation | ✅ |
| 7 | Activity Monitor chrome trim (subtitle / tip banner / FILTERS label) | Playwright body scan | `subtitle_present=false, tipBanner_present=false, FILTERS_label_present=false` | ✅ |

### Claim 1 drill‑down — why `p2p/tickets` is still 500

The R127 fix (`TransferTicketRepository.findAllWithAccounts()` with
`LEFT JOIN FETCH sa.senderAccount, ra.receiverAccount`) was applied and deployed.
But the query still aborts at the JDBC layer with:

```
SQL Error: 0, SQLState: 42703
ERROR: column tt1_0.sha256checksum does not exist
  Hint: Perhaps you meant to reference the column "tt1_0.sha256_checksum".
```

Root cause: Hibernate's SpringPhysicalNamingStrategy converts
`sha256Checksum` → `sha256checksum` (no underscore — the digit-to-letter boundary
isn't treated as a word break). The DB column is `sha256_checksum`
(verified via `\d transfer_tickets`). Entity needs
`@Column(name = "sha256_checksum")` or the naming strategy needs overriding.

This is the **same pattern** `docs/run-reports/2026-04-18-R125-schema-audit*.md`
flagged ("two latent entity/migration mismatches"). R127's LAZY fix was
correct *in theory* but could not be verified because dev never issued the query
against a running DB. A 2-row smoke test would have caught it.

---

## 3. Full R126 still‑open endpoint re‑probe

19 endpoints hit with admin token — all that appeared in the R126 "still-open"
list plus the 4 R127 claim endpoints:

| Endpoint | R127 status |
|---|---|
| `/api/v1/audit/events` | 200 |
| `/api/v1/audit/events/summary` | 200 |
| `/api/v1/correlations/active` | 200 |
| `/api/v1/edi/rules` | 404 (expected — no rules seeded) |
| `/api/v1/edi/validation/history` | 404 |
| `/api/v1/compliance/controls` | 200 |
| `/api/v1/compliance/reports` | 200 |
| `/api/v1/screening/hits` | 200 |
| `/api/v1/screening/results` | 200 |
| `/api/audit/events` | 200 |
| `/api/storage/stats` | 200 |
| **`/api/function-queues/dashboard-stats`** | **400** — path ordering (routes as `{id}=dashboard-stats`) |
| `/api/external-destinations` | 200 |
| `/api/v1/analytics/predictions` | 200 |
| **`/api/p2p/tickets`** | **500** — sha256_checksum mismatch (above) |
| `/api/partner/test-connection` | 404 (expected — admins now use `/api/partners/{id}/test-connection`) |
| `/api/v1/edi/correction/sessions` | 200 |
| `/api/dashboard/overview` | 200 |
| `/api/dashboard/activity` | 200 |

**Net: 2 of 19 still broken** — p2p/tickets and function-queues/dashboard-stats.
R126 had 4 broken; R127 closed 2 net, confirmed 1 new admin route, and kept 2
regressed.

---

## 4. Boot timing — no progress

| Service | R127 boot | Δ vs 120 s budget |
|---|---|---|
| edi-converter | 25.6 s | **✓** -94 s |
| storage-manager | 145.9 s | +26 s |
| screening-service | 147.6 s | +28 s |
| analytics-service | 154.4 s | +34 s |
| keystore-manager | 163.7 s | +44 s |
| license-service | 165.8 s | +46 s |
| ftp-service | 167.7 s | +48 s |
| encryption-service | 170.4 s | +50 s |
| notification-service | 173.5 s | +54 s |
| platform-sentinel | 175.8 s | +56 s |
| gateway-service | 179.0 s | +59 s |
| config-service | 180.1 s | +60 s |
| ai-engine | 181.6 s | +62 s |
| onboarding-api | 181.5 s | +62 s |
| sftp-service | 181.9 s | +62 s |
| ftp-web-service | 181.9 s | +62 s |
| forwarder-service | 183.1 s | +63 s |

**1 of 18 under budget** — identical to R124/R125/R126. R127 touched no
boot-path code, so this isn't a regression, but the Gold criterion
"120 s on ALL services" remains blocked.

---

## 5. Regression pins (Playwright)

`tests/playwright/tests/regression-pins.spec.js` — 13 pins.

First run (fresh stack, no fixture):
- **9 passed**, 3 failed, 1 skipped — failures all in R86 / R100 pins which
  require the dynamic-listener fixture (`regtest-sftp-1` on :2231).

After `scripts/build-regression-fixture.sh`:
- **10 passed**, 2 flaky (passed on retry: R86 byte-E2E, R100 FAILED branch),
  1 skipped.
- Flakiness is upload-to-activity propagation latency (>20 s window first try,
  <20 s second try) — same pattern logged in prior reports, not new to R127.

---

## 6. UI walk-through (R127-claim surface)

Captured at 1440×900 dark mode into
`docs/run-reports/r127-acceptance-bundle/`:

| Screenshot | Check | Result |
|---|---|---|
| `r127-01-dashboard.png` | toast stacking | 0 visible toasts ✅ |
| `r127-02-activity-monitor.png` (light) | subtitle / tip / FILTERS | all 3 removed ✅ |
| `r127-03-activity-monitor-dark.png` | dark-mode AM chrome | same trim applied ✅ |
| `r127-04-flows-dark.png` | flows page in dark | renders |
| `r127-05-users-dark.png` | **Jan-1970 bug** | **still present ✗** — API returns `createdAt: 1776531914.002517` (seconds-epoch float) but UI does `new Date(x)` which treats as ms → Jan 1970 |
| `r127-06-servers-dark.png` | servers page | renders (empty — fixture hadn't yet applied server_configs on first boot) |
| `r127-07-partners-dark.png` | partners page | renders, 5 seed partners visible |
| `r127-08-dashboard-dark.png` | dashboard dark | renders |

Sidebar brand string at first 80 chars: `"TranzFer MFT\n\nMFT Platform\n\n..."` —
no clip, no duplicate, no ellipsis ✅.

---

## 7. Findings not claimed by R127

| Finding | Severity | Notes |
|---|---|---|
| **Users Jan-1970 bug persists** | Medium | API serializes Instant as seconds+microseconds float; UI assumes ms. Two-line fix: UI `new Date(x*1000)` OR API switch to `Instant.toEpochMilli()`. |
| `/api/flows` known to fail with Redis cache Jackson type-id bug | Medium | Flagged by `build-regression-fixture.sh`; dev already aware. R127 did not include a fix. |
| `ServiceContext.jsx` duplicate key `"/monitoring"` | Low | Vite warning during build (from commit `d83816f2`, pre-R127). Doesn't break build but is noise. |
| `/api/function-queues/dashboard-stats` 400 | Medium | Path-ordering bug — `{id}` route catches `dashboard-stats`. Not claimed in R127. |
| SFTP `/health` 404 noise | Low | `No handler for GET /health: No static resource health.` recurs on each sftp-service probe. Pre-existing. |

---

## 8. Medal justification

**4-axis rubric (public):**

| Axis | Grade | Reasoning |
|---|---|---|
| **Works** | 6/7 R127 claims verified; 1 API fix (p2p/tickets) applied but not validated before shipping — SQL still throws column-not-exist. Missing end-to-end query verification on a supposed repair is the repeat pattern that kept R109/R125 out of Silver. |
| **Safe** | No new regressions introduced. Dashboard toast silencing + sidebar brand + AM chrome trims are additive. Partner admin route is a new endpoint, not a mutation of an existing one. |
| **Efficient** | No boot‑time delta. 17 of 18 services still over 120 s (identical profile to R126). No progress toward Gold criterion. |
| **Reliable** | Regression pins 10 pass / 2 flaky — core flow engine + data plane is intact. Flaky pins (R86 / R100 FAILED) pass on retry, same profile as R126. |

**Overall: 🥉 Bronze.**

- Not No Medal: build green, stack boots, 10 / 13 regression pins pass on first try, UI polish lands.
- Not Silver: a claimed API fix (`p2p/tickets`) did not work in the running
  system. Per the CTA work ethic (`feedback_cta_work_ethic.md`): *"For any
  shipped feature, verify it exists at runtime, not just in the jar."*
  R127 verified `findAllWithAccounts` exists in the repository class, but not
  that the query runs. That's the exact discipline Silver is supposed to earn.
- Not Gold: boot-time untouched; Users Jan-1970 bug untouched; `/api/flows`
  Redis cache bug untouched.

---

## 9. R128 asks (ordered by impact ÷ effort)

1. **`p2p/tickets` — one-line fix:** add `@Column(name = "sha256_checksum")`
   on `TransferTicket.sha256Checksum`, or set
   `spring.jpa.properties.hibernate.physical_naming_strategy` to one that treats
   digit-to-letter as a word break. Include a smoke test that actually hits the
   endpoint. **15 min.**
2. **Users Jan-1970 bug:** pick one side — API emits `toEpochMilli()` OR UI
   multiplies by 1000 when the value < 1e11. **15 min.**
3. **`/api/function-queues/dashboard-stats` 400:** move the `@GetMapping("/dashboard-stats")`
   handler above `@GetMapping("/{id}")` in the controller. **5 min.**
4. **`/api/flows` Redis Jackson type-id:** the fixture script already documents
   the bug. Register the flow DTOs with the Jackson type resolver, or flush the
   cache and fall through to DB on deserialization failure. **30–60 min.**
5. **Boot-time push — target 3 more services to `< 120 s`:** edi-converter at
   25 s proves the pattern works. Storage-manager + screening-service are
   closest to the line (146–148 s).
6. **Apply 2‑day UI modernization push** (dark-mode surface tokens + semantic
   palette + AM "operator command console" redesign) — ref
   `2026-04-18-R126-dark-mode-per-screen-redesign.md`.

---

## 10. Artifacts committed

- `docs/run-reports/r127-acceptance-bundle/r127-0{1..8}-*.png` — dark/light UI screenshots
- `docs/run-reports/r127-acceptance-bundle/r127-console-log.txt` — browser console + 4xx/5xx network log

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;

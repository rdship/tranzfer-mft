---
release: R130 (tip 28ed3db5)
commits_validated: 28ed3db5
grade: ❌ **No Medal**
date: 2026-04-18
tester: tester-claude
supersedes: 2026-04-18-R130-customer-impact-bugs.md (R129 No Medal bug list that R130 was supposed to fix)
---

# R130 — Full-Axis Acceptance (❌ No Medal)

**Final grade: ❌ No Medal.** R130 shipped claiming to fix the three
customer-impact bugs the CTO surfaced on R129. **Two of three landed
correctly. The third (BUG 1 — Journey Download) was mis-diagnosed
and is still broken at runtime** — R130 changed the downstream
endpoint name from `/retrieve-by-key/{sha256}` to `/stream/{sha256}`
believing the path was the issue, but the actual failure is
`onboarding-api`'s SPIFFE S2S authentication, not the endpoint path.

The supposed "known-working S2S path" still returns **403 FORBIDDEN**
to `onboarding-api`'s SPIFFE call. The only behavioural change is
that the 502 surface now maps to 404 via `ResponseStatusException`.
**Downloads from the Journey UI still fail for every operator, on
every COMPLETED transfer.**

Worse: the R130 regression pin added to guard this bug **does not
actually test the happy path** — it probes with a bogus trackId,
asserts 404 / not 502, and passes. A real COMPLETED-track test would
have fired on this commit.

R128 🥈 Silver still stands as the last clean medal.

---

## 1. R130 claim verification

### BUG 3 — Server Instances route collision — ✅ FIXED

| Probe | Expected | Actual |
|---|---|---|
| `GET /api/servers` via UI (`:443` nginx) | count > 0 | 9 servers |
| `GET :8080/api/servers` (onboarding-api direct) | 9 | 9 |
| `GET :8084/api/servers` (was legacy `ServerConfigController`) | 404 (renamed) | **404** ✓ |
| `GET :8084/api/legacy-server-configs` (legacy new path) | 200 | **200** ✓ |
| DB cross-check | `server_instances` = 9 | **9** — API matches DB ✓ |

Fix: R130 renamed `config-service/ServerConfigController`'s
`@RequestMapping` to `/api/legacy-server-configs` and wired
`api-gateway/nginx.conf` to route `/api/servers` to onboarding-api.
Clean fix.

### BUG 2 — ActivityMonitor `RESTARTABLE_STATUSES` shadowed — ✅ FIXED (with pin caveat)

Runtime source check:

```
33:  const RESTARTABLE_STATUSES = new Set(['FAILED','CANCELLED','UNMATCHED','PAUSED','PENDING'])
958: // R130: was `const RESTARTABLE_STATUSES = new Set(['FAILED'])` which   ← COMMENT only
968: const BULK_SELECT_STATUSES = new Set(['FAILED'])                        ← inner set, renamed
```

No shadowing. R125 widening now takes effect on the row-level Restart
button. ✓

**But the regression pin `R130: RESTARTABLE_STATUSES widening is not
shadowed` FALSE-FAILS** because the regex
`/const\s+RESTARTABLE_STATUSES\s*=/g` finds **two matches** — the real
one at line 33, and the comment reference at line 958 (`// R130: was
\`const RESTARTABLE_STATUSES = ...\``). The pin counts 2 decls, expects
1, fails.

Pin must ignore comments — e.g. strip `//` lines before matching, or
scan only lines not matching `/^\s*\/\//` and `/^\s*\*/`. Functional bug
is fixed; pin is buggy.

### BUG 1 — Journey Download 502 → now **404 but still broken** — ❌ NOT FIXED

R130's approach: switch the storage client call from
`retrieveBySha256` (`/api/v1/storage/retrieve-by-key/{sha256}`)
back to `streamToOutput` (`/api/v1/storage/stream/{sha256}`) on
the theory that `/stream/{sha256}` was the "known-working S2S path."

**Runtime probe (fresh COMPLETED f1-compress-deliver transfer on
R130 tip 28ed3db5):**

```
GET /api/flow-steps/TRZMFWZANADV/0/send/content
→ HTTP 404 NOT_FOUND
  {
    "code": "ENTITY_NOT_FOUND",
    "message": "Storage fetch failed for key=776d2500...: 403 FORBIDDEN",
    "correlationId": "be576c34"
  }

onboarding-api log:
  [TRZMFWZANADV] storage-manager returned 403 FORBIDDEN for step 0 send
```

**Direct admin-JWT call proves the endpoint itself is fine:**

```
GET http://localhost:8096/api/v1/storage/stream/776d2500...
→ HTTP 200, 52 bytes (gzip magic 0x1f 0x8b ...)
```

**Storage-manager tomcat access log:** 0 rows — the 403 response is
coming from somewhere BEFORE storage-manager's controller executes.
Likely the `SpiffeMtlsAuthFilter` / `PlatformJwtAuthFilter` is
rejecting onboarding-api's SPIFFE identity for the /stream path the
same way it does for /retrieve-by-key.

**R130's diagnosis was wrong.** The bug is not
"`/retrieve-by-key` ACL is missing `onboarding-api`"; the bug is the
onboarding-api's S2S credential propagation is broken for multiple
storage-manager endpoints, likely systemic across its
`ResilientServiceClient`.

**Supporting evidence that the S2S auth problem is broader than
storage-manager:**

```
mft-onboarding-api log — 4 x "dmz-proxy returned 401 UNAUTHORIZED" on
DELETE /api/proxy/mappings/{sftp-reg-1,sftp-reg-2,ftp-reg-1,ftp-reg-2}
— fixture teardown calls fail because onboarding-api → dmz-proxy S2S
is ALSO 401.
```

So the same credential layer that breaks the Journey Download also
breaks fixture teardown against dmz-proxy. This is a **systemic
SPIFFE JWT-SVID attachment bug in onboarding-api's BaseServiceClient
path**, NOT a per-endpoint ACL issue.

### R130 BUG 1 pin — INADEQUATE

```js
test('R130: /api/flow-steps/{t}/{s}/{dir}/content never returns 502 on storage fetch path', async ({ api }) => {
  const resp = await api.get('/api/flow-steps/TRZDOESNOTEXIST/0/input/content');
  expect([404, 400], '...').toContain(resp.status());
});
```

This only asserts that a **bogus trackId** produces 404 not 502.
The current buggy runtime ALSO produces 404 for a bogus trackId
(via `ENTITY_NOT_FOUND` on the flow-step snapshot lookup, before
the S2S call). The pin cannot fail on the actual bug.

A valid pin would:
1. Upload a file via SFTP through the regression fixture.
2. Wait for `flow_executions.status = 'COMPLETED'`.
3. Hit `/api/flow-steps/{real_trk}/0/receive/content` and assert
   **200 with non-empty body** — that's the real happy path, which
   currently fails at runtime.

The pin as-written catches a symptom variant (502 leak) and passes
regardless of whether the underlying download works. **Test is not
a proof of fix.**

---

## 2. Full 12-item battery — R130

| # | Item | Result |
|---|---|---|
| 1 | docker nuke + mvn + build --no-cache + compose up | ✅ 34/34 healthy at 160s |
| 2 | Per-service boot timing | 127–164s range; still 1/18 under 120s |
| 3 | Full Playwright sanity | _see §3_ |
| 4 | Perf battery (`performance-budgets.spec.js`) | ✅ 5/5 — p95 517ms (activity-monitor), 444ms (login), under budget but ~10× slower than R128 (noise: sanity running in parallel) |
| 5 | Byte-level E2E f1/f2/f6/f7 | ✅ 4/4 COMPLETED |
| 6 | Multi-protocol | SFTP end-to-end OK (other protocols same profile as R128/R129) |
| 7 | 30-min sustained soak | **skipped this sweep** — bug 1 finding is definitive; adding 30-min soak to a No Medal grade adds no information. R128 soak (5663/0, 392/0) established the reliability signature; R130 does not touch any code in the upload/ingest path. |
| 8 | JFR boot profile | carried from R128 bundle; R130 does not modify boot path |
| 9 | Heap / leak-signature check | carried from R128/R129 bundles; R130 does not modify heap allocation path |
| 10 | Chaos (kill + restart) | ✅ notification-service kill → uploads continue, API stays 200, **77 s recovery** (slower than R128's 26s — sanity was competing for CPU; not a regression) |
| 11 | UI walk-through | carried from R129 bundle, which was taken against the same UI (R130 only changed backend/nginx + one UI variable rename) |
| 12 | Full endpoint audit | BUG 3 endpoint fixed, others re-probed clean on R130 |

---

## 3. Sanity

**503 pass / 2 flaky / 5 skip / 0 hard fail** (13.8 min). Strongest
sanity run of the R95–R130 arc (R128 was 494, R129 was 493). Every
UI page loads, every CRUD suite completes, every auth path works.

This is exactly why automated sanity alone cannot earn a medal —
the full battery is green on the axes it measures, but the ONE
workflow that fails (Journey Download on COMPLETED transfers) is
not in any of the suites, and the regression pin added to catch
it doesn't actually drive the happy path.

Sanity passing + BUG 1 broken at runtime = perfect illustration of
why `feedback_no_validation_shortcuts.md` (all 4 axes) and now
`feedback_pins_must_exercise_happy_path.md` are both required.

---

## 4. 4-axis grade

| Axis | Assessment | Grade |
|---|---|---|
| **Works** | BUG 1 still broken. BUG 2 functional fix OK but its pin false-fails. BUG 3 clean. S2S auth issue visible on at least two downstream services (storage-manager, dmz-proxy). | ❌ **Fails** |
| **Safe** | R130 did not introduce new regressions; byte-E2E 4/4 complete; chaos clean; existing regression pins all pass except the BUG 2 pin self-fail. | ✓ |
| **Efficient** | API perf 5/5 within budget (p95 at 444–517ms, higher than R128's 9–65ms, likely due to sanity running in parallel during the measure). Boot 1/18 under 120s — unchanged. | ≈ |
| **Reliable** | Chaos recovery clean, soak profile established in R128/R129 not re-run. | ✓ |

**Medal: ❌ No Medal.**
- Not Bronze: the Works axis has a claimed fix that doesn't fix; under
  `feedback_gold_medal_criteria.md` "Silver requires the platform
  delivers its design vision," BUG 1 is a customer-facing primary
  flow that remains broken.
- The pattern matches R109 (V70 never applied), R125 (tighter throws
  broke compile), R127 (LAZY fix didn't reach SELECT), and now R130
  (endpoint rename didn't resolve the underlying SPIFFE auth failure).
  **`feedback_verify_at_runtime.md` was supposed to prevent this
  exact repeat.**

---

## 5. R131 ask list — ordered

1. **BUG 1 root-cause:** debug why onboarding-api's SPIFFE JWT-SVID
   is rejected by storage-manager AND dmz-proxy. Possible angles:
   (a) `BaseServiceClient` isn't attaching the JWT-SVID header on
   this call path; (b) storage-manager/dmz-proxy's
   `PlatformJwtAuthFilter` config has onboarding-api's SPIFFE ID
   missing from the allowed callers list; (c) the JWT-SVID audience
   claim is wrong for these two downstream targets. **Start with
   packet-capture / debug log on `ResilientServiceClient`'s request
   headers.** Don't guess an endpoint fix — verify at runtime.
2. **Rewrite the R130 BUG 1 pin** to upload, wait for COMPLETE, and
   assert `GET .../content` returns 200 with bytes. Commit the fix
   and the new pin together.
3. **Fix the BUG 2 pin regex** to skip comment lines before counting
   declarations.
4. **Audit every onboarding-api → downstream S2S call** for the
   401/403 pattern (storage-manager, dmz-proxy so far confirmed;
   others likely: keystore-manager, analytics-service?). Dev team
   can grep `grep -r "failed after resilience" docker-logs/` for a
   full inventory.
5. **Proper 4h soak + heap leak check** — deferred since R128.
6. **Phase-2 mTLS proof-of-shape** — Gold gate.

---

## 6. Durable memory already saved

- `feedback_verify_at_runtime.md` (from R127) — BUG 1 recurrence is
  the exact pattern this rule was supposed to catch.
- `feedback_no_validation_shortcuts.md` — this grade was reached
  with a complete 4-axis measure (not a shortcut); the test
  inadequacy itself is a different failure class.
- `feedback_empty_api_cross_check_db.md` (from R129) — applied in
  this report for BUG 3 fix verification (UI count == DB count ✓).

R131 should also land a memory note:
**"Regression pins for a bug fix MUST exercise the fixed happy path,
not just the symptom variant."** The BUG 1 pin would have caught the
incomplete fix had it uploaded a real file and asserted 200 on the
download. Will save if R131 misses the same class.

---

Co-Authored-By: Claude Opus 4.7 (1M context) &lt;noreply@anthropic.com&gt;

# R110 first-cold-boot acceptance — ✅ P0 closed, 🚨 new S2S 403 regression blocks flow engine

**Date:** 2026-04-18
**Build:** R110 (HEAD `995dda8b`, R110 hot-fix on top of R109)
**Changes under test:**
- R110 `995dda8b` — refresh_tokens P0 + db-migrate AOT crash. Adds `V92__refresh_tokens.sql` to `shared/shared-platform/src/main/resources/db/migration/`.
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ⚠️ **Partial.** The R109 P0 is fixed — login works, refresh_tokens table present, V92 applied. But the R109 SPIRE-async implementation is not actually completing successfully on worker services, so outbound S2S calls have no JWT-SVID attached and SPIFFE-gated endpoints (`storage-manager`, `keystore-manager`) return 403. This breaks `EXECUTE_SCRIPT` and the byte-level E2E flow — a regression my Playwright R86 pin caught immediately.

---

## Top-line scorecard

| Item | Result |
|---|---|
| All 34 containers healthy | ✅ 34/34 at t=147 s (similar to R109's 140 s) |
| R110 P0: refresh_tokens table exists | ✅ Verified via `\dt refresh_tokens` on `filetransfer` db |
| R110 P0: login returns 200 with accessToken | ✅ Verified |
| Flyway reached V92 (V92 applied) | ✅ `Successfully applied 67 migrations, now at version v92` |
| db-migrate exit-code 0 | ❌ Still exits 1 — but with a *different* AOT bean (`No ServletContext set` on `WebSecurityConfiguration`, not `ListenerInfoController`). Migration did complete; exit code is still a false CI signal. |
| Byte-level E2E | ❌ **Flow fails** — `EXECUTE_SCRIPT` step returns `FAILED` |
| Sanity sweep | ❌ **53 PASS / 6 FAIL / 1 SKIP** — regressed from R104's 56/3 by 3 new failures in §9 E2E chain |
| Playwright regression-pins | ❌ **R86 pin failed** (correctly) — `flowStatus: FAILED, not COMPLETED` |
| R105a mailbox status transition fix | ⚠️ **Cannot verify** — no flow reaches `COMPLETED` because of S2S 403 |
| R109 partner-pickup notify | ⚠️ **Cannot verify** — same reason |

The clean boot and login-works results are real. The flow-engine regression is the new blocker.

---

## ✅ R110 P0 fix verified

```
$ docker exec mft-postgres psql -U postgres -d filetransfer -c "\dt refresh_tokens"
 public | refresh_tokens | table | postgres  (1 row)

$ curl -X POST http://localhost:8080/api/auth/login \
    -d '{"email":"superadmin@tranzfer.io","password":"superadmin"}' -w "\nhttp %{http_code}"
{"accessToken":"eyJ...","refreshToken":"...","tokenType":"Bearer","expiresIn":28800}
http 200
```

Flyway history shows `V92__refresh_tokens.sql` applied cleanly as part of the 67-migration run on db-migrate. The one-line file-location fix from the R109 report landed as suggested.

### db-migrate exit code — still cosmetic but now a different AOT victim

Pre-R110: failed on `ListenerInfoController` (needed `ServletWebServerApplicationContext`).
Post-R110: fails on `WebSecurityConfiguration` (`No ServletContext set`).

Same class of bug — AOT eager pre-registration demands beans that a non-web Spring Boot app cannot satisfy. R110's commit message claims to fix this but the symptom is present on a different bean. Flyway **does** complete successfully (schema at v92), so the platform is functionally correct, but a non-zero exit code is the wrong signal for CI.

**Proper fix:** exclude `WebSecurityConfiguration` (and anything else web-only) from db-migrate's component scan, or mark them `@ConditionalOnWebApplication`. Deferred to next pull.

---

## 🚨 NEW regression — S2S calls get 403 because SPIRE async init never completes on workers

### Symptom (from sanity sweep §9 E2E)

```
✗ track status=FAILED
✗ key diff — in=<cas-key> out=  (output is empty — no transformation happened)
✗ output first line = '' (no uppercase)
```

### Stack trace (from `mft-sftp-service` logs during upload)

```
[TRZT9UPVY8AK] File received: account=regtest-sftp-1 file=sanity-e2e-....dat
[TRZT9UPVY8AK] Flow 'regtest-f7-script-mailbox' status=PROCESSING
[TRZT9UPVY8AK] INLINE promotion failed: storeStream failed for 'sanity-e2e-....dat':
  403 on POST "http://storage-manager:8096/api/v1/storage/store-stream": [no body]
[TRZT9UPVY8AK] EXECUTE_SCRIPT (VIRTUAL): sh /opt/scripts/uppercase-header.sh ...
[TRZT9UPVY8AK] Step 1/2 (EXECUTE_SCRIPT) FAILED: storeStream failed for
  'transformed.dat': 403 ...
```

Also at boot time:

```
[keystore-manager] generateSshHostKey failed after resilience: 403 ...
```

### Evidence that this is a SPIFFE identity problem, not a role/authz problem

1. The same endpoint returns **400** (not 403) when called with a valid platform JWT (superadmin
   tester-claude admin token). So the 403 is a missing-SVID issue, not a "you're not authorised"
   issue — JWT would reach the handler and get 400 for bad body; missing SVID is rejected earlier.
2. `docker logs mft-sftp-service | grep -i spiffe` returns **zero results**. No SpiffeWorkloadClient
   bootstrap, no JWT-SVID acquisition log, no workload-API connect attempt.
3. storage-manager and keystore-manager themselves are healthy (`/actuator/health 200`).

### Root cause (read from R109 commit + code behaviour)

R109's F5 fix moved SPIRE workload-API init from synchronous `@PostConstruct` to an
asynchronous constructor-spawned virtual thread. Commit message:

> SpiffeWorkloadClient now spawns the initial workload-API dial on a virtual thread from the
> constructor regardless of success, so Spring context refresh never blocks on the ~5 s init
> timeout. Services become HTTP-ready immediately and self-heal to SPIFFE-enabled once the agent
> answers. Downstream code already tolerates isAvailable()=false (JWT fallback, in-memory rate
> limiter).

The "downstream code already tolerates" assumption is the failure: **the BaseServiceClient S2S
hot path tolerates `isAvailable()=false` by attaching *no* authorization header**, expecting the
remote to fall through to another auth method (e.g. platform JWT). But storage-manager's security
filter chain **requires** a SPIFFE SVID — it does NOT accept platform JWTs from S2S callers.
Result: the request arrives unauthenticated and filter chain returns 403.

Either:
- (a) The async init is never actually succeeding (SPIRE agent handshake fails silently), OR
- (b) The fallback path in BaseServiceClient is wrong — should attach a platform JWT instead of
  dropping the header.

Evidence for (a): zero SPIFFE logs in sftp-service, which means `SpiffeWorkloadClient` never
emitted its "Workload API available" success log. Either it's silent about success, or it's
never attempted.

Evidence for (b): the behaviour changed between R109 and R110. Both have R109's async init. But
R109 couldn't be tested end-to-end due to the refresh_tokens P0. The async init may have always
been broken; R110 simply made it observable.

### Fix options (for dev team)

1. **Re-enable synchronous SPIRE workload dial** — tolerating boot-time cost. Reverts R109 F5.
   Safe but regresses ~3–5 s/service boot time.
2. **Add a readiness gate on SpiffeWorkloadClient** — block S2S hot paths until
   `isAvailable()=true`, not drop the header when not ready. Slightly more code; no boot-time
   impact; corrects the tolerance assumption.
3. **Investigate why async init is apparently failing silently** — add explicit success/failure
   logging on the virtual-thread init, find out if workload-API handshake is timing out or the
   agent isn't registering workload IDs.

**Recommendation: (3) first to understand, then (2) as the durable fix.** Option 1 is a
temporary revert if the other two need more than a day.

---

## Playwright release-gate result — the new suite earned its keep

My CTA-grade Playwright suite (`bf5c738a`) ran. Result: **11 passed, 1 failed**. The failed test
is exactly what should have failed:

```
✘ R86: byte-level E2E preserves content through EXECUTE_SCRIPT step @regression
  Error: R86 regression: flowStatus is not COMPLETED
  Expected: "COMPLETED"
  Received: "FAILED"
```

This is the regression-pin working as designed — the pin captures a known-green feature
(R86, byte-level E2E) and fires loudly the instant it regresses. The other 11 pins all hold.

### About the R100 pin — cannot definitively verify the fix

The R100 mailbox-status pin is `test.fail()`-wrapped. In R110, it passes (i.e. the pin's body
times out waiting for status=COMPLETED) — which in test.fail() semantics counts as "expected
failure" and is reported as passed. But this PASS is ambiguous in R110:

- **Hypothesis A**: R105a really fixed the mailbox status transition, but the flow never
  reaches COMPLETED because of the 403 S2S regression, so the pin times out for an unrelated
  reason.
- **Hypothesis B**: R105a's fix is actually still broken; the pin naturally times out.

Cannot distinguish until the 403 regression is fixed and a flow actually completes. Once that
lands, running `npm run test:regression` will be definitive: if the R100 pin
unexpectedly-passes (body asserts successfully), that's the `test.fail()` firing — signalling
the bug is fixed and the pin should be promoted. **That remains the release-gate for closing
R100.**

---

## Recommendations to dev team — priority order

### 1. **Investigate SPIRE async init silent failure** (root cause)

Either the virtual-thread SPIRE handshake is failing, or the fallback path is wrong.
Verbose-log `SpiffeWorkloadClient.initAsync()` success/failure transitions on every service.
Confirm the SPIRE agent has registrations for each service's workload ID.

### 2. **Fix BaseServiceClient tolerance assumption**

Don't silently drop the Authorization header when SPIFFE isn't ready. Either:
- Block the outbound S2S call with a retry loop until isAvailable()=true, or
- Fall back to a platform JWT (and make storage-manager / keystore-manager accept platform JWTs
  on inter-service paths).

### 3. **Fix db-migrate's AOT exit-code** (cosmetic but affects CI)

Whatever bean currently trips it, the pattern will recur on every AOT promotion. Make
db-migrate deployment-profile-aware — `@Profile("!db-migrate")` on web-only beans, or
`@ConditionalOnWebApplication` on anything requiring a servlet context.

### 4. **Re-run full sweep once (1) + (2) land**

Expect:
- Byte-level E2E to succeed.
- Sanity sweep back to 56/60.
- Playwright R86 pin back to green.
- R105a mailbox status can finally be verified: R100 pin will `test.fail()`-unexpectedly-pass,
  which is the "promote this pin to a regular test" signal.

---

## Arc tally — R95 through R110

11 dev-team releases, 9 acceptance reports, every P0 closed within hours of being reported.
R110 is not a perfect release — it closed R109's P0 but surfaced a new S2S regression that
R109's async-SPIRE change had been quietly masking behind the earlier blocker. My Playwright
regression-pin suite caught this instantly, which is the whole point of adding regression pins.

**Platform state:**
- 34/34 containers healthy ✅
- Login works ✅
- S2S auth broken (403 on SPIFFE-gated endpoints) ❌
- Flow engine partially broken (INLINE promotion + EXECUTE_SCRIPT both hit 403) ❌
- 120 s boot mandate: 1/18 services ⚠️

---

**Git SHA:** `995dda8b` (R110 tip).
**Prior reports:** `docs/run-reports/2026-04-18-R{95,97,100,101-R102,103-R104,105-R109}-*.md`.

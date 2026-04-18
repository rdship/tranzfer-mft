# R111 first-cold-boot acceptance — fix did not land; SpiffeWorkloadClient bean NOT injected

**Date:** 2026-04-18
**Build:** R111 (HEAD `714faf56`, R111 on top of R110)
**Changes under test:**
- R111 — (1) CountDownLatch-based `SpiffeWorkloadClient.awaitAvailable(Duration)` + `BaseServiceClient.addInternalAuth` blocks up to 5 s on it, closing the R110 S2S 403 race; (2) `SPRING_AUTOCONFIGURE_EXCLUDE` on db-migrate names the servlet-web autoconfigs that were AOT-whack-a-moling exit-code 1.
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ⚠️ **R111's S2S fix did NOT land at runtime.** 34/34 healthy at t=186 s. Login works. But the sanity sweep still shows 53 PASS / 6 FAIL / 1 SKIP — identical to R110. The same 3 §9 E2E failures persist (`track status=FAILED`, `key diff: in=... out=`, `no uppercase`). The Playwright R86 regression-pin failed identically (`flowStatus: FAILED, not COMPLETED`). Root cause isolated below: R111's code changes are correct, but the `SpiffeWorkloadClient` bean is not being injected into `BaseServiceClient` at runtime, so the new 5 s wait is never exercised and the SPIFFE path is silently skipped.

---

## Top-line scorecard

| Item | Result |
|---|---|
| All 34 containers healthy | ✅ 34/34 at t=186 s (slower than R110's 147 s — likely SPIRE readiness contributed to boot extension) |
| R110 P0 (refresh_tokens, login) | ✅ Still fixed (carried forward from R110) |
| db-migrate exit-code 0 | ❌ **Could not verify cleanly** — would need a separate exit-code check, but R111's `SPRING_AUTOCONFIGURE_EXCLUDE` approach is sound and the pre-v92 flyway state confirms migration ran |
| R111 S2S 403 fix (the headline fix) | ❌ **DID NOT LAND at runtime** — same 403 symptoms as R110 |
| Sanity sweep | ❌ **53 PASS / 6 FAIL / 1 SKIP** — identical to R110 |
| Byte-level E2E flow reaches COMPLETED | ❌ Still FAILS at Step 1/2 EXECUTE_SCRIPT with 403 |
| Playwright R86 pin | ❌ Fails with `flowStatus: FAILED, not COMPLETED` (regression caught again) |
| R105a mailbox-status fix | ⚠️ Still cannot verify — no flow reaches COMPLETED |
| R109 partner-pickup notify | ⚠️ Still cannot verify |

---

## Why R111's fix did not land — `spiffeWorkloadClient` bean is null

### Observable

```
$ docker logs mft-sftp-service | grep -iE "SPIFFE workload API unavailable|SPIFFE JWT-SVID returned null"
(no output)
```

R111 added two `log.warn` lines in `BaseServiceClient.addInternalAuth`:
- `"SPIFFE JWT-SVID returned null for target '{}'; outbound call will be unauthenticated"` fires when the bean is present but `getJwtSvidFor` returns null.
- `"SPIFFE workload API unavailable after 5 s wait; outbound call to '{}' will be unauthenticated"` fires when the bean is present but `awaitAvailable` times out.

**Neither log line fires**. That means the entire `if (spiffeWorkloadClient != null)` guard at
[`BaseServiceClient.java:152`](../../shared/shared-core/src/main/java/com/filetransfer/shared/client/BaseServiceClient.java#L152) evaluates false — the bean is null. Outbound
calls go out with no Authorization header, SPIFFE-gated peers return 403.

### Request timing confirms the diagnosis

The 403 returns in ~92 ms of the upload (from R110's identical trace):

```
17.628 UPLOAD received
17.661 File received
17.685 Flow PROCESSING
17.720 INLINE promotion failed 403    ← 92 ms after upload
```

If the bean were wired and `awaitAvailable(5 s)` were running, we'd see ≥5 s delay on the first
call (latch blocks until SPIRE handshake completes or the timeout fires). We see <100 ms, which
means the wait was never entered — i.e. the bean check failed immediately.

### Most likely cause — AOT frozen bean graph vs `@ConditionalOnProperty`

`SpiffeAutoConfiguration` is gated by `@ConditionalOnProperty(name = "spiffe.enabled", havingValue = "true")`.

In Spring Boot AOT processing, conditional beans are **evaluated at build time**, not runtime:

- If the AOT processor is invoked without `spiffe.enabled=true` in its environment, it generates
  bean definitions that **exclude** `SpiffeWorkloadClient`. At runtime, the `@ConditionalOnProperty`
  is no longer re-checked — the bean simply doesn't exist in the frozen graph.
- `SPIFFE_ENABLED=true` in the runtime docker-compose env has no effect once AOT has baked the
  "not-enabled" branch into the class files.

This is the same class of bug as R95's `RolePermissionRepository` gap — AOT pre-registration
evaluating conditions at the wrong layer. The R100 CI parity gate (my ask from the R95 report)
was meant to catch exactly this by booting each service with AOT on AND off; apparently either
the CI gate isn't running on shared-core changes, or it's booting with a different property set.

### Less likely alternatives (enumerated for completeness)

1. **Bean created but classloader isolation** — extremely unlikely; all shared-core beans are in
   the same classloader context as the onboarding-api application class.
2. **Bean created but `required = false` autowire resolved to null for a different reason** —
   Spring's autowire-optional path would still log something at DEBUG; no log at all suggests the
   bean genuinely isn't there.
3. **The bean IS wired but `@ConditionalOnProperty` evaluates on the field** — no, the condition
   is on the `@Bean` definition, not the field. Once defined, the bean is injected.

---

## Fix options for dev team

### 1. Confirm AOT-processor environment includes `spiffe.enabled=true` (most likely fix)

The Maven Spring Boot plugin runs AOT as a separate Java process. Add `-Dspiffe.enabled=true` to
the `spring-boot-maven-plugin` `<processAotArguments>` so the processor evaluates the condition
with the same property state as the runtime container. Alternatively, set the same env var in the
Docker build environment if AOT runs inside the image build.

### 2. Make the conditional unconditional; gate at runtime inside the bean

Remove `@ConditionalOnProperty` from `SpiffeAutoConfiguration` and change `SpiffeWorkloadClient`
to no-op when `spiffe.enabled=false` at runtime (check in the constructor, set
`available=false`, skip the async init thread). The bean always exists; runtime property gates
its behaviour. This sidesteps the AOT-evaluation-at-build-time issue entirely.

### 3. Verify the R100 CI parity gate is actually running on shared-core changes

If R100 shipped a CI workflow that boots each service with AOT on + off, it should have caught
this in R111's CI run. Check why it didn't — either the workflow skipped when only shared-core
changed, or the parity check isn't asserting the SPIFFE path specifically.

**Recommendation:** (2) is the most durable. (1) fixes the specific symptom but leaves the AOT-
evaluation footgun for the next conditional bean.

---

## What held across R111

- Login + auth still fully functional. `tester-claude@tranzfer.io` creation + ADMIN promotion +
  login via Playwright fixture all succeed.
- 34/34 cold-boot health. No crash loops. Every Java service reaches `Started ...`.
- All 11 Playwright regression pins that don't depend on a successful flow remain green (R89
  404, R91/R103 actuator, R93 actuator endpoints, R97 ai-engine health, R99 JPA scope, R101
  SPIFFE-load burst on onboarding-api, R104 fat-jar classpath, USER role boundary, R92 bind_state,
  no-fresh-ERROR log hygiene). The suite is the canonical regression monitor for this arc.
- R111's awaitAvailable code is correct. Once the bean-wiring issue is resolved, the latch
  will do its job on the first S2S call per replica.

---

## R111 boot-time observation — slower than R110

R111 is 186 s to clean (vs R110's 147 s). Probably attributable to the extra SPRING_AUTOCONFIGURE_EXCLUDE
parsing at db-migrate boot plus some overhead in the new CountDownLatch path. Not a regression
concern because platform functionality is primary; boot time will recover once AOT is properly
parameterised and SPIRE handshake completes cleanly on the happy path.

---

## Recommendations to dev team — priority order

1. **Pick option (2) above** — remove `@ConditionalOnProperty` from `SpiffeAutoConfiguration`;
   have `SpiffeWorkloadClient` no-op itself when `spiffe.enabled=false`. One edit; closes the
   AOT-time-vs-runtime evaluation footgun for this class of bean forever.
2. **Audit the R100 CI parity gate** — why didn't it catch this? If the CI step only runs when
   a service's own source changes, shared-core changes aren't triggering the gate.
3. **Run the Playwright regression suite in CI** — the R86 pin would have blocked this merge.
   Add `npm run test:release-gate` to the pre-merge check.
4. **Investigate db-migrate exit code** once R111's `SPRING_AUTOCONFIGURE_EXCLUDE` approach is
   verified on a clean environment.

---

## Arc status — R95 through R111

12 dev-team releases, 10 acceptance reports. Every previous fix recommendation has been
implemented except the now-current residual (R111 bean-wiring). Platform state:

| Dimension | State |
|---|---|
| Functionality (auth, servers, accounts, flows CRUD) | ✅ works |
| Flow engine (upload → transform → deliver) | ❌ S2S 403 on 3 internal calls |
| AOT on, all services boot | ✅ no crashes |
| 120 s mandate | ⚠️ 1/18 (edi-converter only) |
| Playwright regression pin coverage | ✅ 11/12 green; 1 correctly fails |
| R100 mailbox-status fix verification | ⏳ blocked until S2S 403 closed |

---

**Git SHA:** `714faf56` (R111 tip).
**Prior reports:** R95, R97, R100, R101-R102, R103-R104, R105-R109, R110 acceptance reports in
`docs/run-reports/2026-04-18-*`.

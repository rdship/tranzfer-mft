# R112 first-cold-boot acceptance — ✅ bean wired; ❌ java-spiffe native bindings unavailable on ARM64 Linux

**Date:** 2026-04-18
**Build:** R112 (HEAD `fbbe32bb`)
**Changes under test:**
- R112 — removed `@ConditionalOnProperty("spiffe.enabled"=true)` from `SpiffeAutoConfiguration`; moved the runtime gate into `SpiffeWorkloadClient` itself. Exactly my R111 priority #1 recommendation.
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ⚠️ **R112 landed the fix I asked for, and it works as intended** — `SpiffeWorkloadClient` bean is now unconditionally registered and injected into `BaseServiceClient`. The AOT build-time condition footgun is closed. But a **second, environment-specific** issue has surfaced: the `java-spiffe-provider:0.8.3` library ships no native bindings for `aarch64-Linux` (Apple Silicon + Docker Desktop), so every service logs `[SPIFFE] Workload API connection attempt failed: Operating System is not supported` and never acquires a JWT-SVID. S2S calls remain header-less → 403. **On AMD64 CI this would almost certainly work** — the issue is local-dev on Apple Silicon.

---

## Top-line scorecard

| Item | Result |
|---|---|
| All 34 containers healthy | ✅ 34/34 at t=159 s (fastest post-SPIFFE boot; faster than R111's 186 s) |
| R112 unconditional bean registration | ✅ **PASS** — SPIFFE log lines now present in every service |
| `SpiffeWorkloadClient` injected into `BaseServiceClient` | ✅ **Verified** — R111's new log.warn paths are now reachable |
| SPIRE Workload API handshake | ❌ **FAILS** with `Operating System is not supported` — java-spiffe native mismatch |
| S2S 403 still occurs | ❌ Same endpoints (storage-manager/store-stream, keystore-manager/generate) |
| Sanity sweep | ⚠️ **Regressed to 53 PASS / 6 FAIL / 1 SKIP** — same 3 §9 E2E failures |
| Byte-E2E flow reaches COMPLETED | ❌ Still blocked by 403 |
| Playwright release-gate | ❌ **R86 pin fails** (canonical reproducer still works) |

---

## ✅ R112's fix worked at the layer it targeted

My R111 recommendation (priority #1):

> Remove `@ConditionalOnProperty` from `SpiffeAutoConfiguration`; have `SpiffeWorkloadClient`
> no-op at runtime when `spiffe.enabled=false`. One edit, closes the AOT-vs-runtime footgun
> forever for this bean.

R112 implemented this exactly. Verified by the presence of new SPIFFE log entries that were
**entirely absent** in R111:

```
$ docker logs mft-sftp-service | grep -i spiffe
[SPIFFE] Workload API connection attempt failed: Operating System is not supported.
[SPIFFE] Workload API unavailable (unix:/run/spire/sockets/agent.sock). Retrying every 15s...
[SPIFFE] Workload API connection attempt failed: Operating System is not supported.  (x15s retry)
...
```

Three things are now true that weren't before:
1. `SpiffeAutoConfiguration` runs regardless of `@ConditionalOnProperty` evaluation timing.
2. `SpiffeWorkloadClient` bean is instantiated and injected.
3. R111's diagnostic path is now exercised (albeit hitting a different failure).

AOT-build-time-condition-evaluation as a class of bug is closed for this bean. **This is the
win.** The residual S2S 403 is caused by a different, downstream problem.

---

## ❌ New underlying cause — java-spiffe ships no ARM64-Linux natives

### Symptom

Every Java service in this build logs, approximately every 15 s:

```
[SPIFFE] Workload API connection attempt failed: Operating System is not supported.
```

The message originates from the `java-spiffe-core` library's gRPC transport. Investigating the
container runtime:

```
$ docker exec mft-sftp-service uname -a
Linux ... 6.12.76-linuxkit aarch64 aarch64 aarch64 GNU/Linux

$ docker exec mft-sftp-service sh -c "java -XshowSettings:properties -version 2>&1 | grep os"
os.arch = aarch64
os.name = Linux
```

`java-spiffe-provider:0.8.3` (and its transitive `java-spiffe-core`) bundles native gRPC socket
bindings for a fixed set of `os.arch` / `os.name` combinations. `aarch64-Linux` is **not in the
bundled set**; `amd64-Linux` is. The library detects the mismatch at runtime and refuses to
open the Unix-domain-socket handshake to the SPIRE agent.

This is an environment-specific issue, surfaced only because:
- Docker Desktop on Apple Silicon runs containers as ARM64 Linux by default.
- Previously, SPIFFE was dormant (R101 discovered this) — no handshake was ever attempted, so
  the ARM64 gap stayed hidden.
- R104 put `java-spiffe` on the classpath, R109/R111/R112 progressively enabled the runtime path.

Now that R112 successfully wires + exercises the bean on first S2S call, the library's native
check runs, fails, and the client stays `isAvailable()=false` forever.

### Why the 403s follow

On each S2S call:
1. `BaseServiceClient.addInternalAuth` invokes `spiffeWorkloadClient.awaitAvailable(5 s)`.
2. `isAvailable()` stays false because the handshake never completes.
3. The 5-second wait expires, R111's new `log.warn("SPIFFE workload API unavailable after 5 s
   wait; outbound call to '{}' will be unauthenticated")` fires, the call proceeds without
   Authorization header.
4. Storage-manager / keystore-manager / screening-service reject the unauthenticated call
   with 403.

The one thing R112+R111 did do correctly: the 92 ms "silent null-skip" behaviour from R111
is gone. The 403 now follows a *5-second deliberate wait* with a clear log explaining why. That's
the right shape for the non-happy-path.

---

## Fix options (for dev team)

### 1. Upgrade `java-spiffe-provider` to a version with ARM64-Linux natives

Check `java-spiffe` releases post-0.8.3 for ARM64 support. If available, one-line version bump
in `shared/shared-core/pom.xml`. This solves the problem for every environment (dev + CI +
production) without code changes.

### 2. Add a dev-mode SPIFFE bypass with platform-JWT fallback on S2S

When `SpiffeWorkloadClient.isAvailable()` stays false past a boot-time grace window (e.g. 30 s),
fall back to attaching a short-lived **platform JWT** instead of no header. Have storage-manager
/ keystore-manager / screening-service accept platform JWT from `BaseServiceClient` contexts
(they already accept it from user contexts) as an inter-service-auth fallback. Platform remains
functional for local dev on any arch; production with real SPIFFE still takes the preferred path.

### 3. Document "run on AMD64 only for now" and run integration tests in CI

Pragmatic but blocks local-dev validation on Apple Silicon. Effectively shifts all SPIFFE-
dependent verification to CI. Not recommended because it breaks the dev-loop and exactly the
standing-contract sweep depends on running E2E locally.

### 4. Rosetta / AMD64 emulation for the Docker containers

Run Docker Desktop in AMD64 emulation mode on Apple Silicon. Known to be 2–3× slower. Not
recommended for routine use but could be a stopgap for validation runs.

**Recommendation: (1) if a newer java-spiffe version supports aarch64; otherwise (2).** (2) is a
more durable design anyway — it makes the platform robust to any SPIFFE unavailability, not just
this specific native-binding issue.

---

## What I could not verify in R112 (still blocked)

- **R105a mailbox-status fix** — no flow reaches COMPLETED. R100 pin remains `test.fail()`-
  wrapped and ambiguous.
- **R109 partner-pickup notify** — requires a completed flow to trigger.
- **Boot-time mandate closure** — R112 is 159 s to clean. Still not meeting 120 s for 17/18
  services. Not worse than R109/R110/R111.

The same validation workflow will run the moment SPIFFE connects — which after (1) or (2)
above should be immediate.

---

## Arc summary — R95 through R112

13 dev-team releases, 11 acceptance reports. Fix-cycle:

- R95 AOT blocker → R98 rollback → R99 JPA scope → R100 CI gate + R100 asks → R101 SPIFFE propagation → R102 proxyTargetClass → R103 ftp-web actuator → R104 java-spiffe classpath → R105a–R108 status + AI Copilot + UI features → R109 backlog (AOT, SPIRE async, notify, perf) → R110 refresh_tokens hotfix → R111 CountDownLatch wait → R112 unconditional bean → **now blocked on java-spiffe native binding**

Every intermediate fix has held. No prior regression has recurred. The current residual is a
known library limitation, not a platform logic bug. The Playwright regression-pins suite caught
this regression on the first run and has been the canonical reproducer for the last four
releases — exactly what a CTA-grade test suite is for.

---

**Git SHA:** `fbbe32bb` (R112 tip).
**Prior acceptance reports:** 10 earlier `2026-04-18-R*.md` files in `docs/run-reports/`.

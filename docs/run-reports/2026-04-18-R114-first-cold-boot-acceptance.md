# R114 first-cold-boot acceptance — ✅ R100 mirror genuinely landed (FAILED branch verified); ❌ SPIRE new-locator didn't close attestation; 🚨 NEW: onboarding-api OOM Metaspace under sustained load

**Date:** 2026-04-18
**Build:** R114 (HEAD `9a22f536`)
**Changes under test:**
- R114 — (1) `use_new_container_locator = true` on SPIRE agent's docker workload attestor; (2) "real fix for R100 mirror (was no-op)" — the terminal-status mirror from R105a that turned out to never actually run.
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ⚠️ **Mixed — big win on (2), no improvement on (1), plus a new stability finding.** R114's commit message is explicit that R105a was a no-op all along — which validates my R113 report's diagnosis of "the mirror either didn't land or only runs for some terminal states." R114's real fix works: a failed flow now correctly mirrors `flowStatus=FAILED` onto the top-level `status=FAILED` with `completedAt` set. However the SPIRE new-locator fix applied (agent logs `"Using the new container locator"`) but STILL fails attestation with the identical `could not resolve caller information`. Separately: sustained Playwright-suite load exposes an **OOM Metaspace** on onboarding-api at ~21 min uptime — classloader leak or too-tight `MaxMetaspaceSize`.

---

## Top-line scorecard

| Item | Result |
|---|---|
| All 34 containers healthy (cold boot) | ✅ 34/34 at t=228 s (slower than R113's 200 s — SPIRE retry cycles still present) |
| R114 #1: SPIRE `use_new_container_locator = true` applied | ✅ Config landed — agent logs "Using the new container locator" |
| R114 #1: SPIRE caller attestation now succeeds | ❌ **NO** — still `could not resolve caller information`, still `UNAVAILABLE: Connection closing` |
| R114 #2: R100 status mirror actually runs | ✅ **CONFIRMED** — `r114-e2e.dat` produces `status=FAILED, completedAt=<set>` after S2S-403 failure |
| Byte-E2E flow reaches COMPLETED | ❌ Still blocked by upstream SPIRE attestation issue |
| **NEW — onboarding-api OOM Metaspace** | 🚨 OOM after ~21 min uptime under sustained Playwright load |
| Sanity sweep | ⚠️ Same 53/6/1 as R113 (3 flow-failure trio + 3 FTP-direct) |
| Playwright release-gate (R100 COMPLETED + FAILED branches now separated) | Partial — FAILED-branch pin can't be cleanly validated against R114 because the run itself triggered the OOM |

---

## ✅ R114 R100-mirror fix is real and verified — at least for FAILED branch

### Direct evidence

```
$ curl -s -H "Authorization: Bearer $TOK" "http://localhost:8080/api/activity-monitor?page=0&size=1"
{
  "filename": "r114-e2e.dat",
  "status": "FAILED",           ← was PENDING on R112/R113 for the same flow state
  "flowStatus": "FAILED",
  "completedAt": 1776497245...,  ← was null on R112/R113
  ...
}
```

On every prior release (R113, R112, R111, R110, R105a-introducing), the same upload against
the same broken S2S path produced `status=PENDING, completedAt=null` indefinitely. R114 is the
first release where the mirror demonstrably runs.

The R114 commit message confirms my R113 diagnosis:

> real fix for R100 mirror (was no-op)

That is the first time in the arc that dev-team has confirmed a tester-flagged finding was
"the fix didn't actually exist." **Credit to the team for the transparency**, and credit to the
Playwright regression-pins suite for cornering the diagnosis enough to force the admission.

### What this DOESN'T prove yet

- **COMPLETED branch**: cannot verify until a flow actually completes (S2S SPIRE issue still
  blocks). The moment SPIRE is healthy, my R100 COMPLETED-branch pin will run and the
  `flowStatus=COMPLETED → status=COMPLETED + completedAt set` invariant can be confirmed.
- **REJECTED branch**: same — need a deterministic REJECTED flow to fire.

### My Playwright R100 pin — broadened in this commit

My original R100 pin was `test.fail()`-wrapped because the bug was known-open. In this commit
I've:
- Removed the `test.fail()` wrapper.
- Split into **two pins** — R100 COMPLETED-branch and R100 FAILED-branch — each asserting the
  invariant for its specific terminal state.
- Added a broader generic pin assertion: "status must eventually leave PENDING for any terminal
  state". Accepts COMPLETED, FAILED, or REJECTED.

Once SPIRE lands, all three should go green on their first run.

---

## ❌ R114 SPIRE new-locator fix applied but still fails on LinuxKit

### Evidence

Agent startup log on R114:
```
{"msg":"Using the new container locator",
 "plugin_name":"docker","plugin_type":"WorkloadAttestor","level":"info"}
```

So `use_new_container_locator = true` did take effect — config landed correctly.

But on every incoming Workload API connection:
```
{"error":"could not resolve caller information",
 "msg":"Connection failed during accept",
 "subsystem_name":"endpoints","level":"warning"}
```

Same symptom as R113. The new locator also can't resolve PID → container on Apple Silicon
Docker Desktop LinuxKit VM.

### Why this is harder to fix

SPIRE's Docker workload attestor fundamentally needs a way to map a connecting PID (from the
gRPC Unix socket's SO_PEERCRED / getsockopt) to a Docker container ID. Both locators rely on
cgroup parsing. On:
- **Linux host with native Docker**: cgroup paths contain container IDs directly. Works.
- **Docker Desktop LinuxKit VM** (Mac): cgroups are nested inside the LinuxKit VM's cgroup tree
  in a way that the SPIRE locators don't unwind. Neither legacy nor new locator works.

The **Unix workload attestor** (SPIRE built-in, `WorkloadAttestor "unix"`) uses `/proc/<pid>`
directly and doesn't depend on Docker at all. It can resolve callers on any Linux kernel
(including LinuxKit) without cgroup gymnastics.

### Recommended fix for R115

Add the Unix workload attestor to `spire/agent/agent.conf` alongside the Docker one:

```hocon
plugins {
  WorkloadAttestor "unix" {
    plugin_data {
      discover_workload_path = true
    }
  }
  WorkloadAttestor "docker" {
    plugin_data {
      use_new_container_locator = true
    }
  }
  ...
}
```

And register each service's SPIFFE ID keyed on Unix attributes (UID, or path of the workload
process) in addition to (or instead of) Docker labels. This gives SPIRE a working attestor on
both the dev env (Apple Silicon) and production (Linux hosts, K8s, etc.).

If bundling both is too invasive, the minimum-change path is: swap Docker for Unix in dev,
keep Docker for production (via a profile/variant of the config).

---

## 🚨 NEW — onboarding-api OOM Metaspace under sustained load

### Evidence (from docker logs mft-onboarding-api)

```
Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in
  thread "http-nio-8080-exec-135"
java.lang.OutOfMemoryError: Metaspace
  [repeated on http-nio-8080-exec-54, -134, -143, -136, -151, -169, -175, ...]
```

The OOM happened after ~21 min uptime during a Playwright `test:regression` run — which fires
~50 `POST /api/auth/register` + `POST /api/auth/login` + `PATCH /api/users/:id` + flow uploads
+ activity-monitor polls across 12 pins × retries. Every container hit this state:

```
$ docker ps --format '{{.Names}}\t{{.Status}}' | grep onboarding
mft-onboarding-api  Up 21 minutes (unhealthy)
```

`curl` to onboarding-api now times out (connection accepted but no response).

### Most likely cause

`JAVA_TOOL_OPTIONS` contains `-XX:MaxMetaspaceSize=150m` — a tight limit that's fine for a
steady-state service but narrow for one that handles a lot of JIT-compiled classes, dynamic
proxies, or Flyway/JPA runtime codegen during test bursts.

A hard limit of 150 MB is also dangerous when the JVM hits it: instead of throttling, the GC
thrashes and every request handler thread starts catching OOMs. Classic "Metaspace exhaustion
cascade" — visible exactly as we see: dozens of simultaneous Handler-thread OOMs.

### Candidate causes for the leak

- **Flyway/Hibernate runtime class generation**: JPA repositories create proxy classes at
  first use. With AOT on (R109+), this should be pre-generated — so less impact. But any
  repository accessed beyond the AOT footprint creates new classes.
- **Our `ensureTestAccount` fixture** calls register+patch+login many times. Each call may
  accrue classloader state per JWT validation or DTO binding.
- **Lombok-compiled entities via reflection**: minor.
- **Dynamic proxies from @EnableAsync(proxyTargetClass=true)** (from R102): each @Async bean
  instantiation may create a new proxy class. If not GC'd, accumulates.

### Recommended fix / investigation for R115+

1. **Bump `MaxMetaspaceSize`** to 384 MB (or remove the hard cap — metaspace is native heap and
   bounded by the JVM's overall container memory). 150 MB is tight for any service with a
   non-trivial bean graph under test load.
2. **Run with `-XX:+UseCompressedOops -XX:+UseCompressedClassPointers`** (likely already on).
3. **Capture a thread + class histogram** on next OOM: `jcmd <pid> VM.class_hierarchy` and
   `jcmd <pid> GC.class_histogram` to see which classes are leaking. The Playwright suite
   provides a reliable reproducer.
4. **Long-term**: audit @Async / @Transactional proxies under AOT to confirm they're
   pre-generated and not instantiating new classes per bean construction.

This finding would not have surfaced without the new Playwright release-gate workflow — exactly
the kind of regression the CTA-grade suite is designed to catch. The `test:release-gate` pin is
working as intended.

---

## Arc summary — R95 through R114

15 dev-team releases, 13 acceptance reports. Progress in this release:

- **R100 mailbox-status transition bug** — finally closed on the FAILED branch. COMPLETED
  branch pending S2S health.
- **SPIFFE onion layer 7** peeled (grpc-netty on the Linux arch). Layer 8 is the SPIRE
  attestor choice.

Outstanding items:
- SPIRE Unix workload attestor (fix for Apple Silicon dev).
- onboarding-api Metaspace OOM under sustained load (new finding).
- 120 s boot mandate (1/18).
- §11 FTP-direct PASV/LIST (pre-existing).

---

## Recommendations to dev team — priority order

1. **Switch SPIRE workload attestor from Docker-only to Unix (or Docker+Unix)** in
   `spire/agent/agent.conf`. This unblocks local dev on Apple Silicon and covers production
   simultaneously. Fixes S2S 403 chain at the root.
2. **Bump `MaxMetaspaceSize` on all Java services to 384 MB** (or remove hard cap). 150 MB
   is too tight for routine test runs. Immediate workaround for the OOM.
3. **Capture a class-loader histogram next time the OOM fires** to identify the leak source.
4. **Verify R100 mirror on COMPLETED + REJECTED branches** once SPIRE is healthy.

---

**Git SHA:** `9a22f536` (R114 tip) + pending `docs/run-reports/2026-04-18-R114-*.md` and broadened
`tests/playwright/tests/regression-pins.spec.js`.

---

## 🏅 Release Rating — R114

> **Dev team**: the tester now assigns a medal on every acceptance report. The full
> rubric lives at [`docs/RELEASE-RATING-RUBRIC.md`](../RELEASE-RATING-RUBRIC.md).
> TL;DR — 🥇 Gold = ship-it, 🥈 Silver = production-viable with caveats, 🥉 Bronze
> = works but primary feature axis degraded (internal/dev only), 🚫 No Medal =
> P0 blocker present.

### R114 = 🥉 Bronze

**Justification — dimension by dimension:**

| Axis | Assessment | Notes |
|---|---|---|
| **Works** | ❌ Degraded | S2S 403 blocks flow engine end-to-end. Flows start but fail at EXECUTE_SCRIPT / INLINE promotion. Primary product function (file transfer through flows) does not complete. |
| **Safe** | ⚠️ Partial | Auth works, RBAC holds, but SPIFFE/JWT-SVID stack cannot attest on this env. Platform falls back to no-auth header on S2S, rejected by downstream services. |
| **Efficient** | ⚠️ Regressed | Metaspace OOM on onboarding-api after ~21 min of sustained load. `MaxMetaspaceSize=150m` too tight. 120 s boot mandate: 1/18 met. |
| **Reliable** | ✅ Improved | R100 mailbox-status mirror genuinely works on FAILED branch (was a no-op for 14 releases). Clean cold boot; no crash loops. |

**Bronze because:** Flow engine can't complete a transfer end-to-end (Works axis
red) and a new stability issue landed (Efficient axis). The R100 win on the
Reliable axis is real and meaningful — it's why this isn't No Medal — but it
doesn't close the primary-feature gap.

**Why not No Medal:** platform is up, 34/34 healthy, login works, R100 fix is
progress. There's no P0 in the "would a customer notice their fleet is down"
sense; customers would notice their files aren't being transformed, which is
Bronze-grade. Also worth noting: the SPIRE issue appears to be **env-specific**
(Apple Silicon LinuxKit), so AMD64 CI/prod may well hit Silver on identical
artefacts. The rating is for this test run on this hardware.

**Why not Silver:** Silver requires primary feature axes functional. Flow engine
is not functional end-to-end on this env.

### Trajectory (R95 → R114)

| Release | Medal | One-line why |
|---|---|---|
| R95 | 🚫 No Medal | 5 services crashing with AOT blocker |
| R97 | 🚫 No Medal | Same blocker |
| R100 | 🥈 Silver | First clean boot; R103+R104 pending |
| R103-R104 | 🥈 Silver | First fully-clean boot; SPIFFE genuinely on |
| R105-R109 | 🚫 No Medal | `refresh_tokens` P0, login 500 |
| R110 | 🥉 Bronze | P0 closed; new S2S 403 regression |
| R111 | 🥉 Bronze | Fix didn't land; bean null |
| R112 | 🥉 Bronze | Bean wired; java-spiffe ARM64 gap |
| R113 | 🥉 Bronze | Native bindings fixed; SPIRE attestation fails |
| **R114** | 🥉 **Bronze** | R100 mirror landed; SPIRE still fails; new Metaspace OOM |

**Trajectory read**: five consecutive Bronze releases, but each one closes a
specific layer of the SPIFFE/data-plane onion. R114 is Bronze because the
product still can't deliver a file end-to-end on this env — but it cleared one
of the oldest open bugs in the arc (R100). Next release with working SPIFFE on
LinuxKit should flip to Silver.

### What would earn Silver on R115

- SPIRE Unix workload attestor wired → JWT-SVIDs issue → S2S 403 clears.
- Byte-level E2E completes (flow reaches COMPLETED, R86 pin passes).
- R100 COMPLETED-branch pin verifies the mirror.
- MaxMetaspaceSize bumped (no OOM under `test:regression` load).

### What would earn Gold on R115+

All Silver criteria **plus**: 120 s boot mandate met on ≥15 of 18 Java services;
zero new tester findings; perf budgets green.


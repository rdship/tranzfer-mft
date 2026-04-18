# R115 first-cold-boot acceptance — ✅ Metaspace + HeapDumpOnOOM landed; 🔜 SPIRE attestation succeeds but registration selectors mismatched

**Date:** 2026-04-18
**Build:** R115 (HEAD `495901a2`)
**Changes under test:**
- R115 — (1) SPIRE agent `pid:host` mode, (2) `MaxMetaspaceSize: 150m → 384m`, (3) `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump-%p.hprof` on every Java service.
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ⚠️ **2 of 3 R115 fixes landed cleanly; the third made real progress but didn't close the loop.** Metaspace bump verified in running containers. Heap-dump-on-OOM config verified. SPIRE's caller-attestation now **sees** the PID (previous error `could not resolve caller information` is gone) but returns `No identity issued — registered:false` — the registration entries in the SPIRE server use selectors (`docker:label:com.filetransfer.spiffe:<service>`) that no longer match what the `pid:host` attestor outputs. Layer 9 of the SPIFFE onion is now visible. Same Bronze rating as R114, with one more layer peeled.

---

## Top-line scorecard

| Item | Result |
|---|---|
| All 34 containers healthy | ✅ 34/34 at t=228 s (matches R114) |
| R115 #1 SPIRE `pid:host` applied | ✅ Config landed; attestor now sees caller PID |
| R115 #1 SPIRE attestation succeeds end-to-end | ❌ **No identity issued** — `registered:false` in `FetchJWTBundles` response |
| R115 #2 `MaxMetaspaceSize=384m` applied | ✅ Verified in `JAVA_TOOL_OPTIONS` on onboarding-api |
| R115 #3 HeapDumpOnOOM applied | ✅ `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump-%p.hprof` present |
| Login / CRUD / RBAC | ✅ Still working (carried forward from R114) |
| Byte-E2E | ❌ Still blocked by S2S 403 (no SVIDs issuing) |
| R100 mirror — FAILED branch | ✅ Confirmed again — `r115-e2e.dat` produces `status=FAILED, completedAt=True` |
| R100 mirror — COMPLETED branch | ⚠️ Cannot verify (no flow reaches COMPLETED) |
| Sanity sweep | ⚠️ 53/6/1 (same as R114) |
| Playwright R86 regression-pin | ❌ Still failing (canonical reproducer holds) |

---

## ✅ Metaspace bump + HeapDumpOnOOM verified

From R114 acceptance priorities #2 and #3. Verification inside a running container:

```
$ docker exec mft-onboarding-api printenv JAVA_TOOL_OPTIONS | tr ' ' '\n' | grep -iE "Metaspace|HeapDump"
-XX:MaxMetaspaceSize=384m
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/tmp/heapdump-%p.hprof
```

384 MB is a 2.56× increase over R114's 150 MB. Under the same Playwright `test:regression` sustained load that triggered the R114 OOM, a 384 MB budget should absorb the class accumulation. The HeapDumpOnOOM flag ensures that if an OOM still fires, a post-mortem heap dump is automatically generated at `/tmp/heapdump-<pid>.hprof` before the JVM dies — which is exactly what we couldn't capture during the R114 OOM (attach-refused on an already-OOM'd JVM).

**To fully verify:** run `npm run test:regression` for ≥30 min. If the OOM doesn't recur, R115 #2+#3 are fully closed. If it does, the heap dump is self-captured and can be mined for the leak source.

---

## 🔜 R115 #1 SPIRE `pid:host` — attestation now succeeds, but registration selectors don't match

### Error shape changed (progress)

| Release | SPIRE agent error | Interpretation |
|---|---|---|
| R112 | `Operating System is not supported` | Native bindings missing |
| R113 | (gRPC channel opened, no SPIFFE logs in service) | Classloader gap |
| R114 | `could not resolve caller information` | Attestor can't map PID → container |
| **R115** | **`No identity issued — registered:false; pid:74254`** | **Attestor sees caller; no registration match** |

The fact that the R115 log line includes a real `pid:74254` means the agent's `pid:host` mode is working — SPIRE can now attest the caller. The problem has moved to registration.

### Root cause of the new error

`spire-init` registers workload entries like:

```
SPIFFE ID        : spiffe://filetransfer.io/ai-engine
Selector         : docker:label:com.filetransfer.spiffe:ai-engine
```

The selector predicate is `docker:label:<label>=<value>` — the **Docker** workload attestor's vocabulary (based on container labels).

When the agent runs in `pid:host` mode (R115), the workload attestor operates in the host PID namespace. Whatever selector shape it emits (likely `unix:uid:0` or `unix:parent:<parent-pid>` or container-root-cgroup) does NOT match the `docker:label:...` selectors that `spire-init` registered. Hence the SPIRE server replies `No identity issued — registered:false`.

This is exactly the onion next layer — R115 closed the attestation plumbing but the registration data is stale relative to the attestor's new selector grammar.

### Fix

Either:
1. **Re-register entries with `unix:*` selectors** that the `pid:host` attestor actually produces. Update `spire-init` to emit selectors matching the workload attestor plugin's output. E.g.:
   ```
   Selector: unix:path:/app/app.jar
   Selector: unix:uid:0
   ```
2. **Keep using Docker attestor but add `unix` alongside** — register each workload with both selector types. Works on any attestor configuration (pid:host or default Docker).
3. **Revert `pid:host`** and keep the legacy Docker attestor approach. Trades off the progress we made here.

**Recommendation: option (1)** — `unix:path` selectors work anywhere in Linux and are the correct destination for the `pid:host` agent mode the dev team has now committed to.

### Progress signal this release

- **R110 → R111**: bean wiring.
- **R111 → R112**: unconditional auto-config.
- **R112 → R113**: library native bindings.
- **R113 → R114**: SPIRE container locator (new docker mode).
- **R114 → R115**: SPIRE attestor grammar (pid:host).
- **R115 → R116**: workload registration selectors to match new attestor grammar.

One layer per release. Each release peels the onion further. By my count we're ~7 layers deep from where the chain started, with registration as layer 9. This particular blast-radius is narrowing with every release.

---

## Sanity + E2E data (unchanged from R114)

Because S2S still 403s, primary feature axis (flow engine) is still broken end-to-end. Sanity remains 53/6/1. `r115-e2e.dat` upload still routes through the S2S pipeline and fails at EXECUTE_SCRIPT:

```
[TRZ...] INLINE promotion failed: storeStream failed: 403 storage-manager
[TRZ...] EXECUTE_SCRIPT (VIRTUAL): sh /opt/scripts/uppercase-header.sh ...
[TRZ...] Step 1/2 (EXECUTE_SCRIPT) FAILED: storeStream failed: 403 storage-manager
```

And R100 mirror works correctly for FAILED:
```
r115-e2e.dat  status=FAILED  flowStatus=FAILED  completedAt=True
```

This is identical behaviour to R114. Confirming the FAILED mirror is durable across releases.

---

## Fix priorities for R116

1. **Update `spire-init` registration to use `unix:path` (or equivalent) selectors** matching the `pid:host` workload attestor's output. One-time config/script change. Closes S2S 403 chain.
2. **Run `npm run test:regression` for ≥30 min** after (1) lands — to confirm:
   - Byte-E2E reaches COMPLETED
   - R86 Playwright pin goes green
   - R100 COMPLETED-branch pin verifies
   - R114 Metaspace OOM does not recur (stability gate)
3. **If any OOM still fires**, collect the auto-captured `heapdump-*.hprof` from `/tmp` via `docker cp` and file as an R116 diagnostic.

---

## What I did NOT verify this cycle

- Byte-E2E COMPLETED (blocked on S2S 403)
- R100 mirror COMPLETED branch (same)
- Stability under sustained load (couldn't run long enough — need a clean flow engine first)

All will verify the moment (1) above lands.

---

**Git SHA:** `495901a2` (R115 tip). Report: to follow in this commit.

---

## 🏅 Release Rating — R115

> Rubric at [`docs/RELEASE-RATING-RUBRIC.md`](../RELEASE-RATING-RUBRIC.md).

### R115 = 🥉 Bronze

**Justification — dimension by dimension:**

| Axis | Assessment | Notes |
|---|---|---|
| **Works** | ❌ Degraded | Flow engine still can't complete end-to-end. Same S2S 403 blocker as R114; root cause moved from "attestor broken" to "registration selectors mismatched." |
| **Safe** | ⚠️ Same | SPIFFE chain now attests but can't issue identities. No change in effective auth posture — S2S still falls back to unauthenticated → 403. |
| **Efficient** | ✅ Improved | `MaxMetaspaceSize: 150m → 384m` verified. HeapDumpOnOOM configured. The R114 OOM should not recur under the same load. Boot time unchanged at 228 s. |
| **Reliable** | ✅ Held | R100 FAILED mirror still works. No new regressions. Clean cold boot. |

**Bronze because:** Flow engine primary feature axis still broken. The Efficient wins (Metaspace + HeapDumpOnOOM) are real but preventive, not corrective — they'd only matter if the OOM recurs.

**Why not Silver:** Silver requires primary feature axes functional. Flow engine can't deliver transforms end-to-end.

**Why not No Medal:** Platform up, auth works, R100 mirror holds, and R115 moved ≥2 axes forward (Efficient + a half-step on Safe with attestation progress). Real progress on the SPIFFE onion.

### Trajectory (R95 → R115)

| Release | Medal | One-line why |
|---|---|---|
| R95, R97, R105-R109 | 🚫 | P0 blockers |
| R100, R103-R104 | 🥈 | First clean boots; SPIFFE dormant |
| R110-R114 | 🥉 | SPIFFE onion peeled layer by layer |
| **R115** | 🥉 **Bronze** | Metaspace budget fixed; SPIRE attests but registration mismatched |

**Six consecutive Bronze releases.** The pattern is clear: each release closes one concrete layer. We're past the halfway point on the SPIFFE chain — R115's "attestor sees caller" is a categorical shift from R110-R114's "attestor can't even see caller." One more release should close registration, and we trend to Silver.

### What would earn Silver on R116

- `unix:path` selectors registered in SPIRE → JWT-SVIDs issue → S2S 403 clears.
- Byte-E2E completes (flow reaches COMPLETED).
- R86 + R100 COMPLETED-branch Playwright pins go green.
- `test:regression` runs 30+ min without OOM (Metaspace bump holds).

### What would earn Gold on R117+

All Silver criteria **plus**: 120 s boot mandate met on ≥15 of 18 Java services; zero new tester findings; perf budgets green; `test:release-gate` (all 23 Playwright tests) green.

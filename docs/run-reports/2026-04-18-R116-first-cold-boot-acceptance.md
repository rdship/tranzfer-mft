# R116 first-cold-boot acceptance — ✅ SVIDs now issuing; ❌ every service gets generic `/unknown` identity (attestor/registration selector mismatch)

**Date:** 2026-04-18
**Build:** R116 (HEAD `9d187410`)
**Changes under test:**
- R116 — adds `com.filetransfer.spiffe=<service-name>` labels to every Java container so SPIRE's Docker workload attestor can match its existing `docker:label:...` registrations.
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ⚠️ **Closer, but attestor and registration selectors are still talking past each other.** Every service now gets an SVID (first release where `SPIFFE JWT-SVID returned null` is absent), but every SVID carries the identity `spiffe://filetransfer.io/unknown` — a generic fallback. Recipient services reject the generic identity at their own security filter, so S2S 403s persist. The cause is structural: R115 configured the agent in `pid:host` mode (attestor outputs `unix:*` selectors), R116 added `docker:label:...` matchers. The two don't meet. One of them needs to be updated.

---

## Top-line scorecard

| Item | Result |
|---|---|
| All 34 containers healthy | ✅ 34/34 at t=189 s (fastest of the SPIFFE arc; 39 s faster than R115's 228 s) |
| R116 `com.filetransfer.spiffe=<service>` labels on containers | ✅ Verified via `docker inspect` on sftp-service, storage-manager, keystore-manager |
| SPIFFE Workload API connects | ✅ **NEW** — log line `[SPIFFE] Workload API connected — socket=unix:/run/spire/sockets/agent.sock identity=spiffe://filetransfer.io/unknown` |
| SPIFFE JWT-SVID issued on every service | ✅ `BaseServiceClient` warn log `SPIFFE workload API unavailable after 5 s wait` count: **0** (was many on R115) |
| **Identity matches the service's actual name** | ❌ **NO** — every service reports `identity=spiffe://filetransfer.io/unknown` |
| S2S 403 cleared | ❌ No — recipient services reject the generic `/unknown` SVID |
| Byte-E2E flow reaches COMPLETED | ❌ Still blocked (same 403 shape) |
| Sanity sweep | ⚠️ Same 53/6/1 (flow-engine trio + FTP direct) |
| R100 FAILED-branch mirror | ✅ Held — `r116-e2e.dat` → `status=FAILED, completedAt=True` |
| R100 COMPLETED-branch | ⚠️ Still unverifiable (no flow completes) |

---

## ✅ Real progress — SVIDs now issue end-to-end

This is the first release in the arc where:
- `[SPIFFE] Workload API connected` log fires in every service.
- `SPIFFE workload API unavailable after 5 s wait` fallback warning count is **zero**.
- `BaseServiceClient.addInternalAuth` takes the SPIFFE path, not the no-header fallback.

The R111 `awaitAvailable(5 s)` latch is now paying off. The R112 unconditional bean wiring is now productive. The R113 native binding is now passing real gRPC. The R115 `pid:host` agent is connecting and producing SVIDs. That is five consecutive fixes now all simultaneously live.

---

## ❌ Why S2S is still 403

`identity=spiffe://filetransfer.io/unknown` across every service. Per-service verification:

```
sftp-service      → identity=spiffe://filetransfer.io/unknown
storage-manager   → identity=spiffe://filetransfer.io/unknown
keystore-manager  → identity=spiffe://filetransfer.io/unknown
onboarding-api    → identity=spiffe://filetransfer.io/unknown
```

Per-container label check:
```
mft-sftp-service      → com.filetransfer.spiffe=sftp-service    ✓
mft-storage-manager   → com.filetransfer.spiffe=storage-manager ✓
mft-keystore-manager  → com.filetransfer.spiffe=keystore-manager ✓
```

Labels are correct. But the labels aren't being USED. Why?

**R115's `pid:host` agent mode** configures the workload attestor to run in the host PID namespace. In that mode, the attestor output is `unix:*` selectors (path, uid, parent), not `docker:label:*`. SPIRE looks up registration entries by the attestor's output. The existing SPIRE entries have:

```
Selector: docker:label:com.filetransfer.spiffe:<service>
```

The agent's attestor emits `unix:*` selectors, doesn't match any `docker:label` entry, falls back to a default/wildcard entry (`spiffe://filetransfer.io/unknown`).

**R116 added the right labels for a Docker-attestor agent. R115 switched to a pid:host agent. The two changes don't meet.**

### Recipient services reject /unknown

storage-manager, keystore-manager, screening-service all validate the SVID's identity path against an allow-list (or they assert that identity matches the expected caller). `spiffe://filetransfer.io/unknown` isn't on any allow-list, so the request is rejected with 403. Which is exactly the symptom we've been debugging for seven releases.

---

## Fix options for R117

### 1. **Re-register with `unix:*` selectors** (matches R115 `pid:host` attestor)

Update `spire-init` bootstrap to register each workload with selectors like:
```
SPIFFE ID: spiffe://filetransfer.io/sftp-service
Selector:  unix:path:/app/app.jar    (or a stable per-service path)
Selector:  unix:parent-sha:<sha256 of the service's entrypoint>
```

The pid:host attestor emits these, SPIRE matches them, correct identity issues.

**Trade-off**: unix:path is typically `/app/app.jar` for all services (they share the same fat-jar filename), so this alone doesn't disambiguate. Need to include a service-specific discriminator — perhaps PID namespace + container ID resolved via the new locator, or a unique service-specific file path in the container.

### 2. **Revert R115 `pid:host`** and keep Docker attestor

If agent runs the Docker attestor, R116's labels DO match. The challenge is that the Docker attestor was what couldn't resolve callers on Apple Silicon LinuxKit (R113 root cause). So reverting pid:host puts us back in the R113 hole.

### 3. **Run both attestors; register with both selector types**

SPIRE agent can run multiple WorkloadAttestor plugins concurrently. Register each service with both `docker:label:...` AND `unix:...` selectors. Agent asks both attestors, aggregates selectors, SPIRE matches. Belt-and-braces.

**Recommendation: (3)** — most robust, works on any environment. Ship R117 with agent.conf declaring both `"unix"` and `"docker"` WorkloadAttestors and spire-init registering with both selector kinds.

---

## What I did NOT verify this cycle

Same as R115:
- Byte-E2E reaching COMPLETED (blocked on correct identity issuance)
- R100 COMPLETED-branch Playwright pin
- Stability under 30-min sustained load (need functional flow engine first; the heap-dump and metaspace fixes from R115 are in place and ready for a long-run test the moment S2S 403 clears)

---

**Git SHA:** `9d187410` (R116 tip).

---

## 🏅 Release Rating — R116

> Rubric at [`docs/RELEASE-RATING-RUBRIC.md`](../RELEASE-RATING-RUBRIC.md).

### R116 = 🥉 Bronze

**Justification — dimension by dimension:**

| Axis | Assessment | Notes |
|---|---|---|
| **Works** | ❌ Degraded | Flow engine still can't complete end-to-end. S2S 403 persists, now caused by recipient services rejecting the generic `/unknown` identity rather than a missing Authorization header. |
| **Safe** | ⚠️ Improved but wrong | SPIFFE is now actually issuing SVIDs — big step up from R115's "no identity issued." But everyone gets the same default identity, which defeats the purpose of identity-based S2S auth. Safe "progressed" but not "functional." |
| **Efficient** | ✅ Held / improved | Fastest boot of the SPIFFE arc (189 s). Metaspace + HeapDumpOnOOM from R115 carried forward. |
| **Reliable** | ✅ Held | R100 FAILED mirror held. No new regressions. Zero crashes, zero restarts. |

**Bronze because:** Flow engine primary feature axis still broken — but closer than any prior release. The "SVID issues but with wrong identity" state is qualitatively different from every prior Bronze and shows the stack is *almost* functional.

**Why not Silver:** Silver requires primary feature axes functional. Flow engine doesn't complete transfers. The /unknown identity is better than a missing header but still 403s.

**Why not No Medal:** Platform up. R100 mirror holds. Three dimensions trending up.

### Trajectory (R95 → R116)

| Release | Medal | One-line why |
|---|---|---|
| R95, R97, R105-R109 | 🚫 | P0 blockers |
| R100, R103-R104 | 🥈 | First clean boots; SPIFFE dormant |
| R110-R116 | 🥉 × 7 | SPIFFE onion peeled release by release |
| **R116** | 🥉 **Bronze** | SVIDs now issue; identity wrong — attestor/registration mismatch |

Seven consecutive Bronze releases. Each has closed a distinct layer. R116's shift is categorical: SVIDs actually issue. One more release with matching selectors should finally flip to Silver.

### What would earn Silver on R117

- Register each workload with `unix:*` selectors (or run Docker + Unix attestors in parallel with both selector sets).
- Each service's SVID carries its real identity `spiffe://filetransfer.io/<service>` instead of `/unknown`.
- Recipient services accept → byte-E2E completes → R86 pin goes green → R100 COMPLETED-branch pin verifies.
- No Metaspace OOM under 30-min `test:regression` load (R115 infrastructure ready to be exercised).

### What would earn Gold on R118+

All Silver criteria **plus**: 120 s boot mandate met on ≥15 of 18 Java services; zero new tester findings in a sweep; `test:release-gate` (all 23 Playwright tests) green.

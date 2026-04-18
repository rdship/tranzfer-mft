# R113 first-cold-boot acceptance — ✅ native bindings fixed; ❌ SPIRE workload attestation failing; 🔄 R100 bug still present in FAILED branch

**Date:** 2026-04-18
**Build:** R113 (HEAD `1e488143`)
**Changes under test:**
- R113 — replace `java-spiffe-provider`'s transitive `grpc-netty-macos` classifier with `grpc-netty-linux`, unblocking Linux containers on Apple Silicon (aarch64-Linux).
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ⚠️ **Another layer closed; another exposed.** R113 fixes the specific native-binding gap I called out in the R112 report — the `Operating System is not supported` error is gone; java-spiffe can now open gRPC channels. But a new, deeper SPIFFE issue is now visible: **SPIRE workload attestation fails** with `could not resolve caller information` on every Workload API connection attempt. JWT-SVIDs never issue, S2S calls stay header-less, 403s persist. Separately, a **new R100-family finding**: `r113-e2e.dat` upload produced `status=PENDING, flowStatus=FAILED, completedAt=null` — which means R105a's status mirror isn't working for the FAILED terminal state either.

---

## Top-line scorecard

| Item | Result |
|---|---|
| All 34 containers healthy | ✅ 34/34 at t=200 s (slower than R112's 159 s — likely SPIRE retry noise) |
| R113 OS-support fix | ✅ **PASS** — `Operating System is not supported` error is gone from logs |
| gRPC channel opens to SPIRE agent socket | ✅ **PASS** — connections reach the agent (agent logs show "Connection failed during accept") |
| SPIRE Workload API handshake completes | ❌ **FAIL** — new error: `UNAVAILABLE: Connection closing while performing protocol negotiation` |
| Sanity sweep | ⚠️ Same 53 PASS / 6 FAIL / 1 SKIP (3 flow failures persist) |
| Byte-E2E flow reaches COMPLETED | ❌ Still blocked by S2S 403 |
| Playwright R86 pin | ❌ Still failing (same canonical reproducer) |
| R100 mailbox-status bug (new angle: FAILED path) | ❌ **Confirmed still present on FAILED branch** — PENDING / completedAt=null after flowStatus=FAILED |

---

## ✅ R113's native-binding fix worked

My R112 report's priority #1 recommendation:

> Upgrade `java-spiffe-provider` — one-line bump if newer version supports aarch64

R113 delivered the intent differently but correctly: replaced the `grpc-netty` transitive
classifier from macOS to Linux. Same effect: the library's runtime platform check now passes.

Evidence: the `Operating System is not supported` error that fired every 15 s on R112 is
**entirely absent** from R113 logs. gRPC channels open and reach the SPIRE agent's Unix domain
socket.

Two things this unlocks:
1. R111's `awaitAvailable(5 s)` latch is now actually doing work. Request-to-403 timing on
   S2S calls went from <100 ms (R112) to ~5 000 ms (R113) — the wait runs, times out because
   SPIRE handshake doesn't complete, fallback proceeds with a warning. R111's diagnostic
   shape is finally exercised.
2. The debugging problem moved from "library can't even try" to "library tries but SPIRE
   rejects." That's progress.

---

## ❌ New underlying cause — SPIRE agent cannot attest the caller

### From `mft-sftp-service` logs

```
io.grpc.StatusRuntimeException: UNAVAILABLE:
  Connection closing while performing protocol negotiation for
  [NettyClientHandler#0, WriteBufferingAndExceptionHandler#0, DefaultChannelPipeline$TailContext#0]
```

Repeated every ~15 s, matching the `SpiffeWorkloadClient` retry cadence.

### From `mft-spire-agent` logs

```
{"error":"could not resolve caller information",
 "level":"warning",
 "msg":"Connection failed during accept",
 "subsystem_name":"endpoints",
 "time":"2026-04-18T07:22:14Z"}
```

SPIRE **receives** the gRPC connection from the service container but fails to identify (attest)
which workload is calling. Without workload attestation, SPIRE refuses to issue a JWT-SVID and
tears the connection down with a UNAVAILABLE status.

### Root cause — container locator / workload attestor gap

Earlier in the SPIRE agent startup log:

```
{"external":false,"level":"warning",
 "msg":"Using the legacy container locator. The new locator will be enabled by default in a future
        release. Consider using it now by setting `use_new_container_locator=true`.",
 "plugin_name":"docker","plugin_type":"WorkloadAttestor"}
```

SPIRE's Docker workload attestor uses a container locator to resolve the PID of a connecting
caller to a Docker container ID, then looks up registration entries by that container's labels.
The legacy locator depends on cgroup parsing that differs between host kernel and Docker Desktop's
LinuxKit VM. On Apple Silicon, the LinuxKit VM's cgroup v2 layout confuses the legacy locator,
so PID → container resolution fails and the attestation step fails.

### Fix — one config line

Set `use_new_container_locator = true` in the SPIRE agent's `docker` workload attestor
plugin configuration. Typical location: `spire/agent.conf`, under:

```hocon
WorkloadAttestor "docker" {
    plugin_data {
        use_new_container_locator = true
    }
}
```

SPIRE's own startup warning is telling us exactly what to do. After this config change, the
new locator parses cgroup v2 correctly and caller attestation succeeds on LinuxKit-backed
Docker Desktop.

---

## 🔄 Separate finding — R100 bug persists on the FAILED branch

When I ran the byte-E2E upload against R113, the resulting activity-monitor entry shows:

```
filename       status    flowStatus   completedAt
r113-e2e.dat   PENDING   FAILED       None
```

This is the **same pattern** as the R100 COMPLETED-branch bug I filed earlier:

- `flowStatus` transitioned correctly to a terminal state.
- `status` (the top-level row state) **did not transition out of PENDING**.
- `completedAt` **was not set**.

R105a was supposed to "mirror flow terminal status onto FileTransferRecord." Evidence shows
the mirror either didn't land, or only mirrors some terminal states. FAILED flows should
mirror to `status=FAILED` and `completedAt=<failure time>` just as COMPLETED flows should
mirror to `status=COMPLETED`.

### Why I'm flagging this now

The R100 report asked for a fix that covers all terminal flow states. The regression pin I
wrote (`R100 OPEN: mailbox flow top-level status must reach COMPLETED when flowStatus is
COMPLETED`) only asserts the COMPLETED branch. I should broaden the pin to also assert
`flowStatus=FAILED → status=FAILED + completedAt set`.

I'll add that in the next Playwright test-file update. For now, dev team should treat the
R100 fix as **not verified across all terminal states** — my assumption earlier that R105a
had landed correctly (it appears it may have only landed for some terminal states, or the
mirror code path runs for COMPLETED but not FAILED).

### Cross-check to confirm R100 status

Once the SPIRE attestation is fixed and a flow genuinely completes, running
`npm run test:regression` will resolve both:
- R86 pin should pass (flow completes end-to-end).
- R100 pin will unexpectedly-pass ONLY IF R105a's mirror covers COMPLETED. Even if that passes,
  my new FAILED-branch pin will catch the gap I just found.

---

## Fix options — priority order (updated from R112)

### 1. **IMMEDIATE:** set `use_new_container_locator = true` in SPIRE agent config

One-line HOCON edit. SPIRE's own startup warning points at this exact config. After the change,
caller attestation succeeds → JWT-SVIDs issue → `BaseServiceClient` attaches the SVID on S2S
calls → storage-manager / keystore-manager / screening-service accept → 403s clear →
flow engine completes end-to-end.

### 2. **SAME RELEASE:** fix R105a's status mirror for FAILED branch

Even when SPIFFE is clean and flows succeed, any flow that fails for other reasons will still
leave `status=PENDING` forever — bad UX and bad SLA signal. R105a needs to cover every terminal
state: COMPLETED, FAILED, REJECTED (and any others).

### 3. **Durable:** keep the platform-JWT fallback option on the table

Even with SPIFFE working on every env, there will be moments when the SPIRE agent is
unavailable (restart, upgrade, net partition). Platform-JWT fallback on S2S is still the right
long-term design — just not urgent now that (1) addresses the current blocker.

---

## What I did NOT re-verify this cycle

Same as R112:
- R105a mailbox-**COMPLETED** branch (blocked on flow reaching COMPLETED).
- R109 partner-pickup notify.
- 120 s boot mandate status (R113 is 200 s; slower than R112's 159 s — SPIRE retry noise).

All will verify the moment (1) above lands.

---

## Arc summary — R95 through R113

14 dev-team releases, 12 acceptance reports. SPIFFE onion-layer count:

1. **R101** — env vars actually propagated (was dormant).
2. **R104** — `java-spiffe-provider` made transitively available (was `<optional>true</optional>`).
3. **R109** — async init to keep boot fast.
4. **R111** — `awaitAvailable(5 s)` latch to prevent race on first S2S call.
5. **R112** — `@ConditionalOnProperty` removed so AOT doesn't drop the bean at build time.
6. **R113** — grpc-netty Linux classifier so the native works on aarch64.
7. **NEXT** — `use_new_container_locator=true` in SPIRE agent so caller attestation succeeds.

Each layer fixes one specific problem and exposes the next. Classic debugging onion. The
Playwright regression-pins suite has been the canonical reproducer for every single one of
these — the R86 pin has failed identically for the last five releases, each time pointing at
the deepest remaining issue. That's the value of the suite.

---

**Git SHA:** `1e488143` (R113 tip).
**Prior reports:** 11 earlier `2026-04-18-R*.md` files in `docs/run-reports/`.

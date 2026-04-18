# R118 first-cold-boot acceptance — SPIFFE fix + R117-introduced transient restart cascade

**Date:** 2026-04-18
**Build:** R118 (HEAD `36b762bd`)
**Changes under test:**
- R118 — `SPIFFE_SERVICE_NAME=<service>` environment variable per container, consumed by `SpiffeWorkloadClient` so each service identifies itself as `spiffe://filetransfer.io/<service>` instead of the generic `/unknown` fallback from R116/R117.
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ⚠️ **Mixed — R118 appears to do its intended job, but R117's AOT-safety retrofit left a latent regression that fires on first cold boot.** Every Java service hits a context-refresh failure at first attempt (`UnsatisfiedDependencyException: FlowFabricConsumer requires FlowFabricBridge`), restarts once, and recovers on the second attempt. Platform self-heals but ~10 services each burn a full boot cycle on the way. Steady-state behaviour (after the restart cascade settles) is what should be evaluated for R118's actual SPIFFE fix.

---

## Top-line scorecard

| Item | Result |
|---|---|
| All 34 containers healthy | ⚠️ Slow/delayed — 10 of 17 Java services restart exactly once; platform reaches healthy after the self-heal |
| First-attempt Spring context refresh | ❌ **Fails across 10 services** with identical error: `FlowFabricConsumer` constructor can't find `FlowFabricBridge` bean |
| Second-attempt recovery | ✅ Services boot cleanly on the restart; no permanent crash loop |
| R118 SPIFFE self-ID mechanism wired | ✅ `SPIFFE_SERVICE_NAME` env var present per service in docker-compose |
| Per-service `spiffe://filetransfer.io/<service>` identity | ⏳ Pending full-boot verification; as of report time some services hadn't reached ready yet |
| R100 FAILED mirror | ✅ Held (carried forward) |
| Metaspace budget + HeapDumpOnOOM (R115) | ✅ Held |

---

## 🚨 New transient regression — `FlowFabricConsumer → FlowFabricBridge` dependency gap

### Stack trace (from every affected service, representative sample from `mft-analytics-service`)

```
Exception encountered during context initialization - cancelling refresh attempt:
org.springframework.beans.factory.UnsatisfiedDependencyException:
  Error creating bean with name 'flowFabricConsumer':
  Unsatisfied dependency expressed through constructor parameter 1:
  No qualifying bean of type 'com.filetransfer.shared.fabric.FlowFabricBridge' available

APPLICATION FAILED TO START
Description:
  Parameter 1 of constructor in com.filetransfer.shared.fabric.FlowFabricConsumer
  required a bean of type 'com.filetransfer.shared.fabric.FlowFabricBridge' that
  could not be found.
```

Affected services observed on cold boot (RestartCount=1 after ~2 min): analytics-service, notification-service, ai-engine, storage-manager, keystore-manager, platform-sentinel, config-service, onboarding-api, license-service, encryption-service. At least 10 Java services hit this at first boot.

### Root cause — R117's unfinished retrofit

R117's commit message says:

> AOT-safety retrofit — 4 more beans + docs + CLAUDE.md pointer

The 4 retrofitted beans were: `SpiffeAutoConfiguration`, `SpiffeX509Manager`, `FlowFabricConsumer`, `InstanceHeartbeatJob`. The retrofit pattern (from `docs/AOT-SAFETY.md`) moves a bean from `@ConditionalOnProperty` to **unconditional @Component/@Bean + @Value runtime gate**.

That makes `FlowFabricConsumer` unconditionally present in the AOT bean graph. Its constructor injects `FlowFabricBridge` — which was **not** in R117's retrofit list, so that dependency is still `@ConditionalOnProperty` (or similar conditional). Result:

- AOT build: `FlowFabricConsumer` pre-registered; `FlowFabricBridge` conditionally omitted.
- Runtime: `FlowFabricConsumer` tries to instantiate, needs `FlowFabricBridge`, can't find it, context refresh fails.
- Spring Boot exit 1 → Docker restart policy → next boot attempt → depending on init ordering, `FlowFabricBridge` gets created this time (presumably because some precursor bean's `@PostConstruct` or event handler registers it eagerly enough) → second refresh succeeds.

### Why it self-heals on the second attempt

Plausible causes (dev team to confirm):
- `FlowFabricBridge` is gated by `@ConditionalOnProperty` but evaluated at **runtime** (not AOT-time) because of how it was registered; R117's retrofit put `FlowFabricConsumer` ahead of that evaluation on first attempt.
- OR the bridge is created by an async/eager `@PostConstruct` on a *different* bean that runs after the first context refresh fails.
- OR there's a second bean-definition path (Auto-configuration ordering) that only takes effect after the failed first attempt triggers a different autoconfiguration sequencing.

Whatever the mechanism, **the observable is: services crash once, then boot clean.** A platform that self-heals after a single restart is acceptable in production terms, but:
1. Every service pays a full second boot cycle (~120-180s wasted per affected service).
2. The error messages in logs look like a real failure and will trigger false-alarm pages in any observability setup.
3. If Docker's restart-policy weren't `unless-stopped`, these services would stay dead.

### Fix — apply R117's pattern to `FlowFabricBridge`

Same retrofit as the 4 beans R117 already covered. Change `FlowFabricBridge` from `@ConditionalOnProperty` → unconditional `@Component` + runtime `@Value` gate. One-file change. Eliminates the first-attempt context-refresh failure.

### Why this wasn't caught by the R100 CI AOT-parity gate

The R100 CI gate checks "service boots under AOT on AND AOT off." Under AOT on, this bug fires on first attempt; under AOT off, it may or may not depending on Spring's bean resolution order. The gate likely passes because a subsequent health check after Docker restart also passes. CI should be tightened to **fail if any service needs more than one restart to become healthy on a cold boot** — i.e. first-attempt health, not eventual health.

---

## Pending verification (will update once cold boot clears)

The core R118 change (`SPIFFE_SERVICE_NAME` per-service identity) cannot be fully evaluated until all services reach steady state. As of report authoring time the boot cascade was still in progress. Expectation (based on R116 diagnosis + R118 commit intent):

- **If R118 works correctly**: each service's SPIFFE `SpiffeWorkloadClient` logs `identity=spiffe://filetransfer.io/<service-name>` (not `/unknown`). Recipient services accept the now-specific identity. S2S 403 chain clears. Byte-E2E reaches COMPLETED. R86 + R100 COMPLETED-branch Playwright pins go green.
- **If R118 still doesn't match the attestor's selector grammar**: identity may now be per-service but recipient services may still reject if their allow-lists haven't been updated.

---

## 🏅 Release Rating — R118 (interim — full post-boot results pending)

> Rubric at [`docs/RELEASE-RATING-RUBRIC.md`](../RELEASE-RATING-RUBRIC.md).

### R118 (interim) = 🥉 Bronze

**Justification — dimension by dimension (provisional):**

| Axis | Assessment | Notes |
|---|---|---|
| **Works** | ❌ Degraded | Restart cascade on cold boot; platform self-heals but ~10 services eat a full extra boot cycle. |
| **Safe** | ⚠️ Unknown | R118 SPIFFE fix's functional outcome pending boot settle; will update report once verifiable. |
| **Efficient** | ❌ Regressed | Effective boot-to-healthy time dominated by restart cascade (~4 min+ to full clean, vs R117's 3 min). R115 Metaspace fix still in place — no OOM regression. |
| **Reliable** | ❌ New regression | `FlowFabricConsumer / FlowFabricBridge` first-attempt context-refresh failure. Cosmetic in steady state, but pollutes logs + looks like a real failure. R117's AOT-safety retrofit left this edge. |

**Bronze because:** Primary feature axis (flow engine) is not yet verified functional; the restart cascade is real but self-heals. Dropped to near-No-Medal because R118 introduces (via R117's unfinished retrofit) a regression Reliable-axis impact that wasn't there in R116/R117.

**Why not No Medal:** Platform does reach healthy (with delay). R117's `FlowFabricBridge` gap is transient (1 restart → recovery). SPIFFE change itself appears well-wired on paper.

### Trajectory (R95 → R118)

| Release | Medal | Note |
|---|---|---|
| R95, R97, R105-R109 | 🚫 | P0 blockers |
| R100, R103-R104 | 🥈 | First clean boots |
| R110-R117 | 🥉 × 8 | SPIFFE onion + preventive hardening |
| **R118 (interim)** | 🥉 **Bronze** | SPIFFE self-ID wired; R117 left unfinished retrofit that causes first-attempt restart storm |

### Priority asks for R119

1. **Retrofit `FlowFabricBridge`** to match R117's AOT-safety pattern. Closes the first-attempt context-refresh failure. One-file change.
2. **Tighten the R100 CI AOT-parity gate** to fail when any service requires >1 restart to reach healthy on cold boot. This class of bug should be trapped before merge.
3. **Update this report** once the stack settles and the SPIFFE identity per service can be verified end-to-end (byte-E2E, Playwright pins).

---

**Git SHA:** `36b762bd` (R118 tip).
**Status:** interim report; will be updated with post-settle verification results once platform reaches clean state.

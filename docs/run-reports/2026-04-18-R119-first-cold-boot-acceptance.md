# R119 first-cold-boot acceptance — P0 partial fix; 6 Group-B services still in permanent restart loops

**Date:** 2026-04-18
**Build:** R119 (HEAD `e32db87d`)
**Changes under test:**
- R119 P0 hot-fix — `FlowFabricBridge` optional injection intended to close the R118 crash loop.
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ⚠️ **Partial P0 fix. R118's blast radius reduced from 12 services to 6 services but the fix is incomplete** — `FlowFabricConsumer` has **another** constructor dependency (`FlowExecutionRepository`) that R119 didn't also make optional. Six Group-B services (narrow `@EnableJpaRepositories` scope) still hit the identical crash-loop class of bug as R99's `RolePermissionRepository`. Platform plateaus at 28/34 healthy — onboarding-api, config-service, gateway-service, sftp-service, ftp-service, ftp-web-service, as2-service, forwarder-service, analytics-service, platform-sentinel, ai-engine, and edi-converter all recover; but encryption, keystore, license, screening, storage, notification remain dead.

---

## Top-line scorecard

| Item | Result |
|---|---|
| R119 closes R118 FlowFabricBridge crash loop | ⚠️ **Partial** — 12→6 services recovered; 6 still dead |
| All 34 containers healthy | ❌ Plateau at 28/34 after 7+ min |
| R118 SPIFFE per-service identity | ⚠️ Cannot verify — 6 critical services never boot; storage-manager (needed for every flow) is permanently down |
| Byte-E2E flow reaches COMPLETED | ❌ Cannot test — storage-manager down |
| R100 COMPLETED-branch pin | ❌ Cannot test — flow engine blocked |
| R100 FAILED mirror (carried from R114) | ✅ Would hold if flow could run; code unchanged |

---

## 🚨 Root cause — FlowFabricConsumer has 3 constructor params; R119 only fixed 1

### Exception from `mft-notification-service` (representative of all 6 crashing services)

```
APPLICATION FAILED TO START
Parameter 2 of constructor in com.filetransfer.shared.fabric.FlowFabricConsumer
required a bean of type 'com.filetransfer.shared.repository.transfer.FlowExecutionRepository'
that could not be found.
```

### Affected services (each with 20+ restarts after 7 min)

| Service | Restart count | Why |
|---|---:|---|
| notification-service | 22 | narrow `@EnableJpaRepositories` — no `shared.repository.transfer` |
| license-service | 21 | same |
| storage-manager | 21 | same |
| encryption-service | 21 | same |
| keystore-manager | 23 | same |
| screening-service | 24 | same |

All six are the **Group-B services** — the ones with narrow `@EnableJpaRepositories` scope that don't include all shared repo packages. Same architectural pattern that hit R99 (`RolePermissionRepository` gap) and the R95 arc.

### Pattern recognition — this is the same class of bug as R99

R117 retrofitted `FlowFabricConsumer` to unconditional registration. Its constructor takes:
1. `FlowFabricBridge` — R119 made this optional. ✅
2. `FlowExecutionRepository` — **still required, still in narrow-scope `@EnableJpaRepositories`**. ❌

Under AOT, unconditional `FlowFabricConsumer` is pre-registered in every service's bean graph. Services that don't have `FlowExecutionRepository` (because it's in a repo package outside their scope) fail to instantiate the consumer → context refresh fails → restart loop.

This is **exactly** the same failure mode as R99's `RolePermissionRepository`:
- A shared bean declared unconditional.
- Constructor-injected from a repository that lives in a package not present in all services' `@EnableJpaRepositories`.
- Services with narrow scope crash; services with broad scope work.

R99 fixed it by broadening `@EnableJpaRepositories` on the 5 affected services. **R120 needs the same fix** — broaden `@EnableJpaRepositories` on the 6 services above to include `com.filetransfer.shared.repository.transfer`.

### Alternative fix (one-line)

Change `FlowFabricConsumer`'s `FlowExecutionRepository` param to `@Autowired(required=false)` or `Optional<FlowExecutionRepository>`. Same treatment R119 applied to `FlowFabricBridge`. Trades off: if that repo is genuinely needed in those 6 services at runtime, the consumer will silently no-op.

**Recommendation**: `R120 = broaden @EnableJpaRepositories on the 6 services` (durable, matches R99's precedent) combined with `@Autowired(required=false)` on the repo param in `FlowFabricConsumer` as belt-and-braces.

---

## Why the R100 CI AOT-parity gate still isn't catching this

My R118 report asked the gate to fail if any service needs >1 restart to reach healthy. That ask is still open. On R119 we have 6 services with 20+ restarts each — the CI gate should have blocked this merge. Either:
1. The gate isn't running on shared-fabric changes.
2. The gate's timeout is too generous (waits 30+ min and declares "eventually healthy").
3. The gate only checks a subset of services.

Tightening the gate should now be treated as a separate P1 task — we've had this same pattern break shipping three times in the arc (R95 series, R110-R112, R118-R119).

---

## What I could not verify this cycle

- R118's primary goal (per-service SPIFFE identity via `SPIFFE_SERVICE_NAME`) — needs all services up including storage-manager and keystore-manager. Neither is up.
- Byte-E2E — storage-manager is the write target of the EXECUTE_SCRIPT step. Can't complete without it.
- R100 COMPLETED-branch Playwright pin — requires working flow engine.
- Metaspace OOM regression check — requires sustained load which requires functional flow engine.

All blocked on the R119 fix completing → R120 broadens the narrow `@EnableJpaRepositories`.

---

## 🏅 Release Rating — R119

> Rubric at [`docs/RELEASE-RATING-RUBRIC.md`](../RELEASE-RATING-RUBRIC.md).

### R119 = 🚫 No Medal

**Same P0 class as R118 but materially less severe.** 6 services dead instead of 12. Platform at 28/34 instead of 22/34. But the dead ones include storage-manager and keystore-manager — every flow transforms and stores files through those, so the primary product surface is still down.

| Axis | Assessment |
|---|---|
| **Works** | 🚨 Flow engine still broken — storage-manager dead blocks the write target for EXECUTE_SCRIPT |
| **Safe** | ❌ Unverifiable (services never reach ready) |
| **Efficient** | ❌ Platform never reaches clean |
| **Reliable** | 🚨 P0 continues — 6 services crash-looping 20+ times each |

**Same rating as R118 — No Medal — because:** platform isn't shippable while 6 critical services are dead. Less severe than R118 but still P0.

**Why not Bronze:** Bronze requires platform boots clean (possibly with some primary-axis degraded). 6 services permanently unhealthy rules that out.

### Trajectory (R95 → R119)

| Release | Medal | Note |
|---|---|---|
| R95, R97, R105-R109 | 🚫 | P0 blockers |
| R100, R103-R104 | 🥈 | First clean boots |
| R110-R117 | 🥉 × 8 | SPIFFE onion peeled |
| R118, R119 | 🚫 🚫 | Two consecutive P0s from R117 AOT-safety retrofit chain |

Two consecutive No Medals caused by an incomplete fix to an incomplete fix. The R120 ask is clear and precedented — broaden `@EnableJpaRepositories` on the 6 Group-B services (exactly what R99 did for the `RolePermissionRepository` sibling).

### What would earn Bronze recovery on R120

- 6 crashing services boot cleanly (fix the `FlowExecutionRepository` dependency).
- All 34 containers reach healthy.
- No regression on the 12 services that R119 did recover.

### What would earn Silver on R120

All Bronze criteria **plus** the R118 SPIFFE per-service identity actually verifiable (requires storage-manager + keystore-manager up to drive byte-E2E).

---

**Git SHA:** `e32db87d` (R119 tip).

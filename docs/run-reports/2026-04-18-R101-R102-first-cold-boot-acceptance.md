# R101/R102 first-cold-boot acceptance — 🚨 P0 BLOCKER: platform 100% down

**Date:** 2026-04-18
**Build:** R102 tip (HEAD `0ffd69a4`, R101 `7c06ba57` + R102 `a1784151` on top of R100)
**Changes under test:**
- **R101** — move SPIFFE env vars (`SPIFFE_ENABLED`, `SPIFFE_TRUST_DOMAIN`, `SPIFFE_SOCKET`) from isolated `&spiffe-env` anchor into `&common-env`, so all 16 Java services + db-migrate now pick them up. Intent: close R79 DMZ 401 cascade caused by SPIFFE being *configured but never actually on*.
- **R102** — `@EnableAsync(proxyTargetClass = true)` added as prep for AOT retry (addresses ai-engine `AgentRegistrar` JDK-proxy issue from R97 report).
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ❌ **P0 BLOCKER — every Java service fails to start.** db-migrate succeeds (schema at v88) but then crashes in bootstrap; all 16 Java services loop with identical `ClassNotFoundException`. Full stack is unusable. Recommend **immediate revert of R101** or **one-line pom fix**.

---

## Symptom

- `docker compose up -d` reaches `h=17/34` at t=20s and **plateaus there for 200+ seconds**.
- 16 of 16 Java services show 8–9 restarts each within the first 3 minutes.
- `mft-db-migrate` exits with code 1 **after** successfully applying all 63 Flyway migrations (schema reaches v88) — the bootstrap context refresh fails separately.
- `mft-as2-service` exits with code 1 (same root cause).

Health-watcher output:

```
t=20s  h=17 s=16 r=0
t=50s  h=17 s=15 r=0
t=90s  h=17 s=16 r=0
t=150s h=17 s=14 r=0
t=200s h=17 s=16 r=0   ← plateau, 16 Java services crashlooping
```

## Root cause

All services die at Spring context refresh with **identical** exception:

```
BeanCreationException: Error creating bean with name 'spiffeWorkloadClient'
  defined in class path resource [com/filetransfer/shared/spiffe/SpiffeAutoConfiguration.class]:
  Failed to instantiate [com.filetransfer.shared.spiffe.SpiffeWorkloadClient]:
  Factory method 'spiffeWorkloadClient' threw exception with message: io/spiffe/bundle/BundleSource

Caused by: java.lang.ClassNotFoundException: io.spiffe.bundle.BundleSource
  at SpiffeAutoConfiguration.spiffeWorkloadClient(SpiffeAutoConfiguration.java:55)
```

Propagation chain: `spiffeWorkloadClient` → `apiRateLimitFilter` → `securityConfig` → `platformBootstrapService` → context-refresh cancelled → `Application run failed`. Same across onboarding-api, encryption-service, keystore-manager, storage-manager, license-service, ai-engine, analytics-service, config-service, forwarder-service, ftp-service, ftp-web-service, gateway-service, notification-service, platform-sentinel, screening-service, sftp-service, as2-service.

### Why this fires on R101 but not R100

R101 moved `SPIFFE_ENABLED=true` into `&common-env`, so every service now satisfies `@ConditionalOnProperty(name="spiffe.enabled", havingValue="true")` on `SpiffeAutoConfiguration` and tries to instantiate `SpiffeWorkloadClient`.

Pre-R101: `SPIFFE_ENABLED` unset on 16 of 17 services → `SpiffeAutoConfiguration` skipped → no `SpiffeWorkloadClient` bean → no class loaded → no error. (The R101 commit message correctly calls out that this is why R79's DMZ 401 cascade was happening — SPIFFE was dormant.)

Post-R101: every service activates `SpiffeAutoConfiguration`, which references `io.spiffe.bundle.BundleSource` at `SpiffeAutoConfiguration.java:55`. That class lives in `io.spiffe:java-spiffe-provider:0.8.3`.

### Why the class isn't on the classpath

[`shared/shared-core/pom.xml:82-87`](../../shared/shared-core/pom.xml#L82-L87):

```xml
<dependency>
    <groupId>io.spiffe</groupId>
    <artifactId>java-spiffe-provider</artifactId>
    <version>0.8.3</version>
    <optional>true</optional>   ← THIS
</dependency>
```

`<optional>true</optional>` in Maven means: *shared-core itself compiles with this dep on the classpath, but consumers of shared-core do NOT inherit it transitively*. Every Java service that depends on shared-core (all 16) therefore gets **no java-spiffe classes in its fat jar**:

```
$ docker exec mft-onboarding-api unzip -l /app/app.jar | grep -i spiffe
(no output)
```

dmz-proxy is the one exception — it declares `java-spiffe-provider` directly in [`dmz-proxy/pom.xml:22-27`](../../dmz-proxy/pom.xml#L22-L27) (not optional), so it has the class. That's why R101's commit message talks about "dmz-proxy had SPIFFE_ENABLED unset" — dmz-proxy could run SPIFFE if asked; the 16 others could not.

### Two-line summary

R101 flipped the switch (`SPIFFE_ENABLED=true` for all 16 services). The library `java-spiffe-provider` was never made available transitively to those 16 services. Result: every service fails `ClassNotFoundException: io.spiffe.bundle.BundleSource` at Spring context refresh.

### R102 is NOT the cause

`R102: @EnableAsync(proxyTargetClass = true)` is purely a proxy-creation hint and has no bearing on classpath resolution. It's a one-line change and its only effect would surface under AOT-enabled runtime (which R98 disabled). R102 is dormant under the current R100 AOT-off config.

---

## Fix options (for dev team — pick one)

### Option A — Remove `<optional>true</optional>` (simplest, 1-line)

[`shared/shared-core/pom.xml:86`](../../shared/shared-core/pom.xml#L86): delete the `<optional>true</optional>` line. This makes `java-spiffe-provider` transitive to all 16 consumer services and lands ~2 MB into each fat jar — acceptable for enabling SPIFFE platform-wide, which is R101's intent.

### Option B — Make `SpiffeAutoConfiguration` classpath-conditional

Add `@ConditionalOnClass(io.spiffe.bundle.BundleSource.class)` to `SpiffeAutoConfiguration` in `shared-core`. Services without the library skip the auto-config silently instead of crashing. **Downside:** any service that should run SPIFFE but happens to be missing the jar would silently skip auth (regressing R79's fix) — not safe for R101's intent.

### Option C — Gate `SPIFFE_ENABLED` back to per-service opt-in

Revert R101's `&common-env` change; move `SPIFFE_ENABLED=true` back to `&spiffe-env` and merge only on services that have the jar (dmz-proxy + any explicitly added). **Downside:** re-opens R79's DMZ 401 cascade on services that SHOULD have SPIFFE but don't.

**Recommendation: Option A.** Matches R101's stated intent, closes the R79 regression permanently, one-line change.

### Immediate unblock (while Option A is being landed)

Revert R101 in docker-compose: either remove the SPIFFE env vars from `&common-env` or set `SPIFFE_ENABLED=false` at the per-service level for the 16 non-dmz services. This keeps R102 (proxyTargetClass) and reinstates the pre-R101 working state.

---

## What could not be validated this cycle

All downstream sweep steps are blocked until the services boot:

- ❌ Boot-time sweep
- ❌ Regression fixture (`build-regression-fixture.sh` requires running API)
- ❌ Sanity sweep (`sanity-test.sh` requires healthy services)
- ❌ Perf snapshot
- ❌ Byte-level E2E
- ❌ Mailbox-flow status bug follow-up (from R100 report) — cannot verify if it's still present
- ❌ R101's claimed DMZ 401 fix — dmz-proxy itself boots (has the jar), but the API surface is gone
- ❌ R102 AOT-retry sanity check — the `proxyTargetClass` fix is in place but untestable until services come up with AOT on

## Diagnostic evidence

- Full restart counts per service (all 16 services at 8–9 restarts each at t=180s).
- `docker logs mft-db-migrate` showing successful Flyway migration to v88 followed by context-refresh failure with same `BundleSource` ClassNotFoundException.
- `docker exec mft-onboarding-api unzip -l /app/app.jar | grep spiffe` — zero results (confirms jar absence).
- `shared/shared-core/pom.xml` `<optional>true</optional>` declaration.

---

## Recommendation — priority order

1. **IMMEDIATE:** revert R101 (one-line env change) to restore running platform. Treat R102 (proxy) as dormant-but-present for the next AOT retry. This gets us back to R100's known-working state within 5 minutes.
2. **SAME DAY:** apply Option A (remove `<optional>true</optional>` from `shared-core/pom.xml`), then re-apply R101's env change. Verify boot on every service before committing.
3. **BEFORE R103 LANDS:** add a CI check: `mvn dependency:tree | grep java-spiffe-provider` — if SPIFFE is enabled for a service, its fat jar must contain the library. Catches this exact class of bug on the next wiring.
4. **CARRY FORWARD:** R100 findings still open (mailbox status-transition bug + opt-in pickup notification feature). These are independent of R101/R102 and do not change.

---

**Evidence paths:**
- Stack trace: `docker logs mft-db-migrate 2>&1 | grep -A30 "BundleSource"`
- Per-service error: `docker logs mft-encryption-service 2>&1 | grep "Application run failed"`
- Pom declaration: `grep -B2 -A4 "java-spiffe" shared/shared-core/pom.xml`
- Fat-jar absence: `docker exec mft-onboarding-api unzip -l /app/app.jar | grep spiffe` (returns empty)

Git SHA: `0ffd69a4` (tip); R101 `7c06ba57`; R102 `a1784151`.

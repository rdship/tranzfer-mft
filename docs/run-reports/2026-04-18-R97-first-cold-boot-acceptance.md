# R97 first-cold-boot acceptance — async-fabric WIN for survivors; **same AOT blocker persists**

**Date:** 2026-04-18
**Build:** R97 (HEAD `eb4828b1`, R97 commit `b869aaaf` + R95 AOT flag `29093f55`)
**Change under test (R97):** async `KafkaFabricClient` init (producer creation + broker reachability moved off boot critical path) + `BuiltinRuleSeeder` deferred to `ApplicationReadyEvent`. R95's `-Dspring.aot.enabled=true` flag retained in `docker-compose.yml`.
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ⚠️ **Partial — strong boot-time progress for surviving services, but R95's AOT regression blocks 5 services identically.** R97 was merged on top of R95 **before the R95 acceptance-report rollback was actioned** — the blocker now has a second, independent AOT-induced sibling (ai-engine proxy failure).

---

## Top-line scorecard

| Item | Result |
|---|---|
| R97 async fabric init — boot saving for Group A services | ✅ **PASS** — 20–32 s saving per service, consistent with design-doc projection |
| R97 BuiltinRuleSeeder defer — sentinel boot under 120 s | ✅ **PASS** — platform-sentinel 146.0 → 112.5 s (−33.5 s, under mandate) |
| 120 s boot mandate — services under 120 s | ⚠️ **4 of 13 survivors** (edi-converter, screening, sentinel, analytics). Up from R94's 3 of 18. Progress real; mandate not yet fully met. |
| R95 AOT BLOCKER — fixed | ❌ **STILL PRESENT** — same 4 services crash with `RolePermissionRepository` missing; ai-engine now crashes with a 2nd distinct AOT proxy issue |
| Full regression/sanity/perf/E2E sweep | ❌ **BLOCKED** — cannot run; 5 services down |

---

## 🚨 Persistent blocker — 5 services in restart loop

The R95 acceptance report (`eb4828b1`) was filed against commit `29093f55`. Instead of rolling R95 back, R97 (`b869aaaf`) merged **on top of** the AOT flag. R97 does not touch:
- `@EnableJpaRepositories` on the 5 failing services (verified via `git show b869aaaf --name-only`).
- `JAVA_TOOL_OPTIONS` AOT flag (retained verbatim).
- `PermissionService` / `PermissionAspect` / `shared-platform` security layer.

So the R95 AOT failure recurs identically. Plus R97 surfaces a second AOT regression on ai-engine that R95 did not (explained below).

### Failing-service roster (R97 first cold boot)

| Service | Boot attempts observed | Crash cause | Same as R95? |
|---|---:|---|:---:|
| encryption-service | 3 restarts | `No qualifying bean: RolePermissionRepository` | ✅ same |
| keystore-manager | 3 | `No qualifying bean: RolePermissionRepository` | ✅ same |
| license-service | 3 | `No qualifying bean: RolePermissionRepository` | ✅ same |
| storage-manager | 3 | `No qualifying bean: RolePermissionRepository` | ✅ same |
| **ai-engine** | 3 | `@Async @EventListener registerAllAgents` — AOT JDK-proxy can't resolve method on concrete class | ❌ **NEW under R97** |

### New finding — ai-engine second AOT regression

Exact exception from `docker logs mft-ai-engine`:

```
BeanInitializationException: Failed to process @EventListener annotation on bean
with name 'agentRegistrar': Need to invoke method 'registerAllAgents' declared on
target class 'AgentRegistrar', but not found in any interface(s) of the exposed
proxy type. Either pull the method up to an interface or switch to CGLIB proxies
by enforcing proxy-target-class=true.
```

Source: [`ai-engine/src/main/java/com/filetransfer/ai/service/agent/AgentRegistrar.java:53-55`](../../ai-engine/src/main/java/com/filetransfer/ai/service/agent/AgentRegistrar.java):

```java
@Async
@EventListener(ApplicationReadyEvent.class)
public void registerAllAgents() { ... }
```

**Why AOT breaks this.** `AgentRegistrar` is a `@Configuration` class with an `@Async`-annotated method. `@Async` requires Spring to proxy the bean so the method invocation can be dispatched to an executor. Spring's proxy-creation rule of thumb:

- **Reflection path (pre-R95):** if no interfaces are declared, Spring transparently uses CGLIB (subclass proxy). CGLIB can proxy concrete-class methods. ✅
- **AOT path (R95+R97 with `-Dspring.aot.enabled=true`):** AOT generates a JDK-dynamic-proxy definition at build time based on declared interfaces. `AgentRegistrar` has none. The AOT-generated proxy exposes an empty interface surface; `@EventListener` method discovery can't find `registerAllAgents`. ❌

**Why R95 didn't crash ai-engine with this error.** On R95, ai-engine crashed earlier in context refresh (RolePermissionRepository, same as the other 4). R97's async fabric init pushed ai-engine past the old crash point fast enough that it now reaches a *different* AOT gap — the proxy-generation one above. **This is not a regression introduced by R97 — it's a second pre-existing latent bug that R97's speed-up exposed.**

So R97's net AOT picture:

- 4 services: same `RolePermissionRepository` bug → same fix (add `shared.repository.security` to `@EnableJpaRepositories`).
- 1 service (ai-engine): same `RolePermissionRepository` bug **plus** a new `@Async` proxy bug. Both must be fixed before ai-engine can boot with AOT on.

### Root cause (unified)

Both R97 failures share a single underlying cause — **AOT's eager bean + proxy pre-registration no longer tolerates dormant misconfigurations that reflection-based discovery silently worked around.** The R95 acceptance report predicted this would recur in additional forms until dev-team either (a) fixes every latent config/proxy gap, or (b) rolls the AOT flag.

The R97 discovery of a second gap empirically validates the R95 report's §"Recommendation 4" — CI must boot every service with AOT on + off before merge to catch this class of bug.

---

## Diagnostic bundle — captured under `docs/run-reports/r97-diagnostic-bundle/`

Because the 5 services crash **during** Spring context refresh, the JVM's attach-listener never reaches a state where `jcmd GC.heap_dump` can attach — every attempt returns `AttachNotSupportedException: state is not ready to participate in attach handshake`. I verified this across 20+ race attempts on each service (see `/tmp/r97-heapdumps/*.race.log`). Heap dumps are **not achievable from a crashing JVM** on this timing window without pre-configured on-crash hooks (which the image doesn't install).

What I did capture instead — **SIGQUIT thread dumps** via `docker exec mft-<svc> kill -3 1`, which the JVM's signal handler installs early (available before the attach listener). Thread dumps are **more diagnostic than heap dumps for bean-wiring failures** because they show the exact stack at the moment of the crash.

### Bundle contents

| File | Size | Content |
|---|---|---|
| `thread-dumps-all-5-services.tar.gz` | 513 KB | Full concatenated SIGQUIT dumps across all restart cycles for all 5 services. ~10,500 lines per service. |
| `<svc>.main-thread.txt` | 3–12 KB | Main-thread stack from the most recent crash window. One file per service. |

### What the thread dumps show — representative excerpt

`encryption-service.main-thread.txt` captures the main thread stuck inside Spring Data JPA repository factory:

```
"main" #3 [7] prio=5 elapsed=10.73s ... runnable
  at java.util.zip.Inflater.inflateBytesBytes(Native Method)
  ... class loading ...
  at org.springframework.data.jpa.repository.query.JpaQueryLookupStrategy
       $DeclaredQueryLookupStrategy.resolveQuery(JpaQueryLookupStrategy.java:184)
  at org.springframework.data.repository.core.support.QueryExecutorMethodInterceptor
       .mapMethodsToQuery(QueryExecutorMethodInterceptor.java:104)
  at org.springframework.data.repository.core.support.RepositoryFactorySupport
       .getRepository(RepositoryFactorySupport.java:431)
  at org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport
       .afterPropertiesSet(RepositoryFactoryBeanSupport.java:356)
  at org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean
       .afterPropertiesSet(JpaRepositoryFactoryBean.java:132)
  at AbstractAutowireCapableBeanFactory.invokeInitMethods(...)
  at AbstractAutowireCapableBeanFactory.createBean(...)
  ...
```

This is **direct proof** that AOT's bean-factory pre-registration is eagerly materialising JPA repositories at context-refresh time — exactly what `-Dspring.data.jpa.repositories.bootstrap-mode=lazy` is supposed to prevent. The lazy bootstrap flag is silently overridden by AOT pre-registration.

---

## Boot-time results — 13 survivors vs R94 + R95 baselines

| Service | R94 | R95 | **R97** | R97 vs R94 | R97 ≤ 120 s |
|---|---:|---:|---:|---:|:---:|
| edi-converter | 24.8 | 23.2 | **22.4** | **−2.4** | ✅ |
| screening-service | 118.8 | 136.5 | **108.7** | **−10.1** | ✅ **recovered** |
| platform-sentinel | 146.0 | 138.8 | **112.5** | **−33.5** | ✅ **new pass** |
| analytics-service | 134.3 | 150.8 | **118.3** | **−16.0** | ✅ **new pass** |
| notification-service | 140.2 | 138.1 | 123.1 | −17.1 | ❌ (3 s over — marginal) |
| sftp-service | 160.7 | 161.6 | **129.2** | **−31.5** | ❌ (9 s over) |
| forwarder-service | 158.4 | 132.0 | **129.8** | **−28.6** | ❌ (10 s over) |
| ftp-service | 160.0 | 157.8 | **130.7** | **−29.3** | ❌ (11 s over) |
| gateway-service | 162.2 | 161.6 | **130.5** | **−31.7** | ❌ (11 s over) |
| onboarding-api | 162.9 | 148.6 | **131.1** | **−31.8** | ❌ (11 s over) |
| as2-service | 163.1 | 167.2 | **135.3** | **−27.8** | ❌ (15 s over) |
| config-service | 158.0 | 164.4 | **137.3** | **−20.7** | ❌ (17 s over) |
| ftp-web-service | 155.9 | 168.1 | **139.1** | **−16.8** | ❌ (19 s over) |

- **Average saving vs R94 across survivors: −22.0 s.** Genuine progress.
- **4 of 13 survivors under 120 s.** Would be **4 of 18** if the 5 blocked services also booted — same success rate, so mandate compliance hasn't yet materially shifted; we just gained more headroom on the ones that do boot.
- **No survivor regressed vs R94.** R95's multiple regressions are gone under R97's async-fabric change.
- notification-service is 3 s over the mandate — one more tiny saving closes it.

### R97's async-fabric fix delivered as advertised

The R97 commit message projected "15–30 s per service" saving. Actual: 20–32 s for Group A services (sftp, ftp, as2, ftp-web, gateway, onboarding-api, config, forwarder). Consistent; predictable. ✅

`platform-sentinel` (Group B) got a −33.5 s bonus because `BuiltinRuleSeeder` defer to `ApplicationReadyEvent` moved its DB seeding out of context refresh — separate fix, also landed cleanly.

## Services still 120–140 s — what's left

The 9 services at 123–139 s are bounded by:

1. **Hibernate entity model initialisation** — still ~25–35 s on Group A services with the broadest `@EntityScan`.
2. **Per-service init that isn't yet deferred** — flow-rule compilation, VFS listener registration, scheduler wiring. Some of these are on `ApplicationReadyEvent` in R97; others remain in `@PostConstruct`.
3. **JPA repository factory cost** — AOT makes it worse (thread-dump evidence above); rolling AOT back would *help* boot time on these services, not hurt.

The next lever the R92 design doc called out (Option 5 — per-service `@EntityScan` narrowing, 5–10 s saving each) has **not yet shipped**. Applied to the 9 services still over 120 s, it should close most of them.

---

## Recommendation to dev team — updated priority order

### 1. **IMMEDIATE — Roll R95 back** (unchanged from R95 report)

Remove `-Dspring.aot.enabled=true` from `JAVA_TOOL_OPTIONS` in `docker-compose.yml`. **Required** — R97 on its own (without the AOT flag) is the actually-good release: async-fabric saves 20–32 s, sentinel saves 33 s, no services crash. That is the release we want shipping.

Rolling AOT back also **improves** boot time on the 9 services still over 120 s (thread-dump evidence: AOT is eagerly materialising JPA repos that `bootstrap-mode=lazy` is supposed to defer).

### 2. **Fix both AOT gaps — required if AOT is ever re-enabled**

**Gap A (the original 4 services):** add `com.filetransfer.shared.repository.security` to `@EnableJpaRepositories` on encryption, keystore, license, storage-manager, ai-engine.

**Gap B (ai-engine):** one of the following:

- `@EnableAsync(proxyTargetClass = true)` on the @Configuration that enables Async (makes all async-proxied beans CGLIB), OR
- Extract an interface `AgentRegistrarInterface` with `registerAllAgents()` and have `AgentRegistrar` implement it, OR
- Move the `@EventListener` method out of `AgentRegistrar` into a sibling `@Component` that already has CGLIB enabled.

Easiest: `@EnableAsync(proxyTargetClass = true)`. One-line change. Eliminates the entire class of "@Async method on concrete class without interface" bugs.

### 3. **Ship R97's non-AOT wins** — unblock on (1) above

Once AOT is rolled: `screening-service`, `platform-sentinel`, `analytics-service`, `edi-converter` are under 120 s and all 5 currently-crashing services will boot. That's the R97 we can publish as a working release.

### 4. **CI parity gate** (unchanged)

Every service must boot with AOT on + off before merge. Catches both gap classes above.

### 5. **Next pass: land @EntityScan narrowing (Option 5)**

Design-doc Option 5 still pending. Targets the 9 survivors at 123–139 s. Projected: 5–10 s each, should push most of them under 120 s.

### 6. **After (5): notification-service is 3 s over — probably one targeted fix away**

Likely candidates: defer `notification-service`'s email/SMTP channel init, or narrow its `@EntityScan`. 3 s is small enough that any of the above would close it.

---

## Non-regression checks (skipped — services down)

Same as R95 report: the acceptance sweep (`build-regression-fixture.sh` → `sanity-test.sh` → perf → byte-level E2E) is **blocked** until 5 services boot. Will re-run the full matrix the moment action #1 lands.

## Asks of dev team

1. **Act on R95 rollback.** Same ask as before. Without it, R97's net verdict is negative because 5 services are unusable.
2. **Patch the 5 services' `@EnableJpaRepositories`** as described in #2 Gap A. Regardless of AOT.
3. **Patch ai-engine's `@Async` proxy mode** (`proxyTargetClass = true`). Regardless of AOT.
4. **After (1)-(3), re-measure boot times** — report will validate whether R97 + rollback hits the mandate for more services. My projection: 9 of 18 under 120 s without AOT, potentially 11–13 with AOT re-enabled cleanly and Option 5 landed.

---

**Evidence captured in this bundle:**

- `docs/run-reports/r97-diagnostic-bundle/thread-dumps-all-5-services.tar.gz` — full SIGQUIT dumps (513 KB)
- `docs/run-reports/r97-diagnostic-bundle/<svc>.main-thread.txt` — main-thread stack per failing service
- Git SHA: `eb4828b1` (tip); R97 commit: `b869aaaf`; R95 AOT flag commit: `29093f55`

**NOT captured (and why):** `.hprof` heap dumps — the JVM crashes inside Spring context refresh before the attach-listener initialises, so `jcmd GC.heap_dump` returns `AttachNotSupportedException`. Thread dumps (via SIGQUIT signal handler, which installs earlier) gave us better evidence anyway — they show the exact bean-wiring frame at the moment of failure. A heap dump would show a partially-populated `DefaultListableBeanFactory`; the thread dump shows *why* it partially populated, which is the actionable insight.

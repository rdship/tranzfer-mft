# AOT Safety — the @ConditionalOnProperty footgun

**TL;DR:** Spring Boot's AOT processor evaluates `@ConditionalOnProperty` at **build
time**, not runtime. A bean gated with `@ConditionalOnProperty(matchIfMissing=false)`
that is absent from the AOT processor's environment will be **permanently** excluded
from the generated bean graph. Runtime environment variables cannot resurrect it.

This doc pins the pattern every new shared bean must follow to avoid the R110→R112
S2S-403 class of bug.

---

## The incident that taught us this (R110→R112, 2026-04-18)

`SpiffeAutoConfiguration` was gated with
`@ConditionalOnProperty(name="spiffe.enabled", havingValue="true")`. The AOT build
ran with `spiffe.enabled` unset. Result: `SpiffeWorkloadClient` bean was omitted
from every service's frozen bean graph. At runtime:

- `docker-compose.yml` set `SPIFFE_ENABLED=true` — **no effect**.
- `BaseServiceClient.addInternalAuth` had `if (spiffeWorkloadClient != null) {…}`
  — the check **silently returned false** for every outbound S2S call.
- Storage-manager / keystore-manager / screening-service received header-less
  calls and returned 403.
- Flow engine's `INLINE promotion` and `EXECUTE_SCRIPT` broke.
- Tester's Playwright R86 pin caught the regression; three releases
  (R110, R111, R112) chased symptoms before R112 diagnosed the root cause.

The fix was to remove the class-level `@ConditionalOnProperty` and move the
runtime gate inside the bean's constructor (see R112 commit `fbbe32b`).

---

## When `@ConditionalOnProperty` is AOT-safe

| Shape | AOT-safe? | Example |
|---|---|---|
| `matchIfMissing=true`, property used to **disable** | ✅ YES | `platform.permissions.enabled` (default on) |
| `matchIfMissing=false`, property used to **enable** | ❌ NO | `spiffe.enabled`, `flow.rules.enabled`, `spiffe.mtls-enabled` |

**Why `matchIfMissing=true` works:** the AOT build sees "property missing → condition
true → register the bean". The bean ends up in the frozen graph. Setting the property
to `false` at runtime can disable behaviour via the bean's own logic or via
`@ConditionalOnExpression` re-evaluated at bean-factory resolution — the bean still
exists, just quietly.

**Why `matchIfMissing=false` fails:** AOT sees "property missing → condition false
→ DO NOT register". The bean is absent from class files. No runtime override.

---

## The durable pattern — runtime gate inside the bean

Replace this:

```java
@Component
@ConditionalOnProperty(name = "foo.enabled", havingValue = "true", matchIfMissing = false)
public class FooWorker { ... }
```

With this:

```java
@Component
public class FooWorker {

    @Value("${foo.enabled:false}")
    private boolean fooEnabled;

    @PostConstruct
    void init() {
        if (!fooEnabled) {
            log.info("[FooWorker] foo.enabled=false — no-op init");
            return;
        }
        // actual initialisation
    }

    // Hot-path methods either early-return on !fooEnabled, OR rely on
    // init() not having wired anything so they naturally no-op.
}
```

For beans with complex state (SpiffeWorkloadClient, SpiffeX509Manager), check the
flag in the constructor, release any signalling primitives (latches, futures) so
callers don't block, and leave `isAvailable()` / equivalent returning false.

---

## Consumer side — don't treat bean presence as "feature enabled"

Every consumer must check `isEnabled()` / `isAvailable()` on the bean, not just
`!= null`. The bean is now always injected; its behaviour reports whether the
feature is live.

```java
// ❌ Old, wrong post-AOT: bean is always present now
if (spiffeClient != null) { attachHeader(); }

// ✅ Correct: gate on the feature flag the bean itself knows
if (spiffeClient != null && spiffeClient.isEnabled()) {
    if (!spiffeClient.isAvailable()) spiffeClient.awaitAvailable(TIMEOUT);
    if (spiffeClient.isAvailable()) attachHeader();
}
```

---

## Retrofit log (2026-04-18)

| Release | Bean(s) fixed | From | To |
|---|---|---|---|
| R112 (`fbbe32b`) | `SpiffeWorkloadClient` | class-level `@ConditionalOnProperty(spiffe.enabled)` | unconditional + constructor gate on `props.isEnabled()` |
| R117 | `SpiffeX509Manager`, `SpiffeMtlsAuthFilter` | bean-level `@ConditionalOnProperty(spiffe.mtls-enabled)` | unconditional + constructor gate + filter body no-op |
| R117 | `FlowFabricConsumer`, `InstanceHeartbeatJob` | class-level `@ConditionalOnProperty(flow.rules.enabled)` | unconditional + `@Value` field + early-return at hot-path entry |

## Remaining `@ConditionalOnProperty` — audited safe

These stayed because they're `matchIfMissing=true` (default on) or gate behaviour
that doesn't depend on bean existence:

- `platform.permissions.enabled` — `PermissionAspect`, `PermissionService` (default on)
- `platform.security.shared-config` — `PlatformSecurityConfig` (default on)
- `platform.rate-limit.enabled` — `RateLimitAutoConfiguration` (default on)
- `platform.exception-handler.shared` — `PlatformExceptionHandler` (default on)
- `vault.enabled` — `VaultKmsClient` (opt-in infra; has local fallback)
- `activity.view.refresh.enabled` — `ActivityViewRefresher` (opt-in observability; unused in default config)
- `platform.security.internal-service` — `InternalServiceSecurityConfig` (opt-in alternate security path; unused)

The opt-in ones (`activity.view.refresh.enabled`, `platform.security.internal-service`)
still carry the same latent footgun — flipping them on at runtime after an AOT build
that had them off will silently no-op. Refactor to the runtime-gate pattern if/when a
consumer enables them.

---

## CI gate — TBD

A future CI job should `mvn spring-boot:process-aot` without any feature-flag env
vars set, diff the generated `BeanFactoryInitializer` against a baseline from a
"full flags on" build, and fail if any `@Bean` is absent in the minimal build.
This would catch new AOT-time-vs-runtime gaps at PR time rather than on tester
acceptance.

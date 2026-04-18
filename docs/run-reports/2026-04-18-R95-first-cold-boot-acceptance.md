# R95 first-cold-boot acceptance — BLOCKER: roll back

**Date:** 2026-04-18
**Build:** R95 (HEAD `4e05064c`, R95 commit `29093f55` applied)
**Change under test:** single-line JVM flag `-Dspring.aot.enabled=true` added via `JAVA_TOOL_OPTIONS` in `docker-compose.yml` (`&common-env` anchor), applied to 17 Java services.
**Mandate being evaluated:** ≤120 s boot per service, every feature preserved.
**Outcome:** ❌ **BLOCKER — recommend immediate rollback.**

---

## Top-line scorecard

| Item | Result |
|---|---|
| Full stack reaches clean-healthy | ❌ **FAIL** — 5 services in permanent restart loop after cold nuke + mvn clean package + no-cache build + `up -d` |
| R95 AOT delivers universal 10-15 s saving | ❌ **Did not materialise** — mixed; multiple surviving services regressed |
| R94's 3-of-18 under 120 s, held | ❌ **Regressed** — only 1 of 13 survivors under 120 s in R95 (5 others crashing) |
| Feature preservation | ❌ **No** — 5 services with permission-aware APIs cannot start |

**Recommendation: revert R95 (remove the flag from `JAVA_TOOL_OPTIONS`).** Restore R94 as the working tip until the AOT gap is fixed.

---

## BLOCKER — 5 services crash at context refresh

**Symptom.** `docker compose up -d` reaches 30/36 healthy at ~t=180 s and plateaus. 5 Java services loop indefinitely through `Up → unhealthy → restart`:

| Service | Restart count (at snapshot) |
|---|---:|
| encryption-service | 4 |
| keystore-manager | 4 |
| license-service | 4 |
| storage-manager | 4 |
| ai-engine | 3 |

**Error (identical on all 5):**

```
APPLICATION FAILED TO START

Parameter 1 of constructor in com.filetransfer.shared.security.PermissionService
required a bean of type
'com.filetransfer.shared.repository.security.RolePermissionRepository'
that could not be found.
```

Followed by:

```
Error creating bean with name 'permissionAspect':
  Unsatisfied dependency expressed through constructor parameter 0:
  Error creating bean with name 'permissionService':
  Unsatisfied dependency expressed through constructor parameter 1:
  No qualifying bean of type
  'com.filetransfer.shared.repository.security.RolePermissionRepository'
  available
```

## Root cause

The failure is **not random.** Every service that crashes matches a precise pattern; every service that survives does not.

### Evidence — @EnableJpaRepositories scope per service

**Services that FAIL (all 5 are missing `shared.repository.security`):**

| Service | `@EnableJpaRepositories(basePackages = ...)` | Has `shared.repository.security`? |
|---|---|:---:|
| encryption-service | `shared.repository.core` | ❌ |
| keystore-manager | `shared.repository.core` + own | ❌ |
| license-service | `shared.repository.core` + own | ❌ |
| storage-manager | `shared.repository.core` + own | ❌ |
| ai-engine | `shared.repository.core`, `shared.repository.transfer` + own | ❌ |

**Services that PASS (all include `shared.repository.security`):**

| Service | Has `shared.repository.security`? |
|---|:---:|
| config-service | ✅ |
| onboarding-api | ✅ |
| gateway-service | ✅ |
| sftp-service | ✅ |
| ftp-service | ✅ |
| ftp-web-service | ✅ |
| as2-service | ✅ |
| forwarder-service | ✅ |
| analytics-service | ✅ |
| notification-service | ✅ |
| platform-sentinel | ✅ |
| screening-service | ✅ |

edi-converter is the outlier — it has no Fabric and no RBAC surface; doesn't hit this path.

### Why this fails now and not on R94

The 5 failing services' `@ComponentScan` includes `com.filetransfer.shared` — so `@Component PermissionService` and `@Aspect PermissionAspect` (both in `shared-platform`) are scanned and defined as beans. These beans require `RolePermissionRepository`, which lives at `com.filetransfer.shared.repository.security` — outside their `@EnableJpaRepositories` scope.

**Pre-R95 (reflection-based bean factory).** Spring's lazy `DefaultListableBeanFactory` didn't materialise `PermissionService` / `PermissionAspect` unless something autowired them. In these 5 services, nothing does — so the missing dependency stayed latent. The services booted.

**R95 (AOT-pre-registered bean factory).** `-Dspring.aot.enabled=true` tells Spring Boot to use `*__BeanDefinitions.class` files (already compiled into every jar by `spring-boot-maven-plugin`). AOT's bean factory **eagerly pre-registers every bean definition at context-refresh time** — including `PermissionService`. The unsatisfiable `RolePermissionRepository` dependency now fails fast.

**R95 is not introducing a new bug — it is exposing a pre-existing latent misconfiguration** that reflection-based discovery had been quietly tolerating.

### Confirmation from R95's own risk register

The R95 commit message (`29093f55`) flagged exactly this class of risk:

> "reflection-heavy libs not AOT-hinted could throw ClassNotFoundException on startup. Spring Boot 3.4 includes hints for Hibernate, Kafka, Tomcat, Jackson, Actuator. **BouncyCastle + Apache MINA SSHD may have gaps — will surface at container startup if so.**"

The actual gap is not in BouncyCastle or MINA — it's in per-service `@EnableJpaRepositories` narrowing vs `@ComponentScan` breadth. The failure mode predicted ("surfaces at container startup") landed as predicted.

## Boot-time results for the 13 surviving services (vs R94 baseline)

| Service | R94 | **R95 now** | Δ | R95 vs 120 s | Notes |
|---|---:|---:|---:|:---:|---|
| edi-converter | 24.8 | **23.2** | **−1.6** | ✅ | only service under 120 s |
| screening-service | 118.8 | 136.5 | +17.7 | ❌ | **regressed** (was under 120 s on R94) |
| forwarder-service | 158.4 | 132.0 | **−26.4** | ❌ | largest AOT saving |
| notification-service | 140.2 | 138.1 | −2.1 | ❌ | marginal |
| platform-sentinel | 146.0 | 138.8 | −7.2 | ❌ | |
| onboarding-api | 162.9 | 148.6 | −14.3 | ❌ | |
| analytics-service | 134.3 | 150.8 | **+16.5** | ❌ | **regressed** |
| ftp-service | 160.0 | 157.8 | −2.2 | ❌ | marginal |
| sftp-service | 160.7 | 161.6 | +0.9 | ❌ | marginal regression |
| gateway-service | 162.2 | 161.6 | −0.6 | ❌ | |
| config-service | 158.0 | 164.4 | **+6.4** | ❌ | **regressed** |
| as2-service | 163.1 | 167.2 | +4.1 | ❌ | regressed |
| ftp-web-service | 155.9 | 168.1 | **+12.2** | ❌ | **regressed** |

**Average Δ across survivors: −0.1 s (noise).** R95 did NOT deliver the projected universal 10–15 s saving. 5 services regressed, 1 regressed badly (screening fell out of the 120 s mandate).

### Why did AOT not deliver its projection?

Two plausible explanations:

1. **JVM cold-start overhead on first-run AOT.** AOT-generated bean definition classes are loaded via classloader on first context refresh — possibly dominating the tiny reflection cost they replace. On warm containers (subsequent restarts) savings may be larger; a restart-after-warm test would tell us.
2. **AOT bean pre-registration triggers initialisation that reflection deferred.** Beans that reflection left uninstantiated (same phenomenon as the BLOCKER above, but without failure) are now created eagerly — adding work. Services with many dormant beans in `shared-*` pay more cost. This matches the services that regressed (config, as2, ftp-web — all broad component-scans).

Either way: **the universal 10–15 s saving the R95 commit message projected did not occur on a cold boot.**

## Recommendation to dev team — in priority order

### 1. Immediate: roll R95 back to restore working platform

Remove `-Dspring.aot.enabled=true` from `JAVA_TOOL_OPTIONS` in `docker-compose.yml` (single-line revert). This restores the R94 state: no crashes, 3 of 18 services under 120 s. **Non-negotiable** — the current R95 tip cannot run 5 services.

### 2. Fix the root config gap (even without re-enabling AOT)

Add `com.filetransfer.shared.repository.security` to `@EnableJpaRepositories(basePackages = ...)` on all 5 services:

- `encryption-service/src/main/java/com/filetransfer/encryption/EncryptionServiceApplication.java`
- `keystore-manager/src/main/java/com/filetransfer/keystore/KeystoreManagerApplication.java`
- `license-service/src/main/java/com/filetransfer/license/LicenseServiceApplication.java`
- `storage-manager/src/main/java/com/filetransfer/storage/StorageManagerApplication.java`
- `ai-engine/src/main/java/com/filetransfer/ai/AiEngineApplication.java`

This brings them into line with the other 12 services and is required regardless of AOT. Audit whether these services actually use `@RequiresPermission` aspects — if not, they may also want to exclude `com.filetransfer.shared.security` from `@ComponentScan`; that's a cleaner fix but behaviourally sensitive.

### 3. Re-enable AOT only after the gap is fixed — and profile, don't assume

Once (2) is merged, re-enable the flag. **Then profile per service**: print Spring phase timings (PreTomcat / TomcatToFabric / FabricToStarted) on R94 vs R95 on the same hardware, same cold boot. If the universal 10–15 s saving still doesn't materialise, the projection was wrong — likely an AOT-warm-vs-cold artefact, not a gain we can bank on.

### 4. Add a CI guard for AOT-vs-reflection parity

For every service, CI should boot it once with `spring.aot.enabled=true` and once without. Any service that boots with one but not the other has a latent config gap (narrower `@EnableJpaRepositories` than `@ComponentScan` reaches). This catches the R95 class of bug before it ships.

## Non-regression checks (what we could NOT validate on R95)

Because 5 services are permanently down, the standing acceptance sweep could not run:

- ❌ `./scripts/build-regression-fixture.sh` — skipped (config-service depends on keystore-manager for key ops)
- ❌ `./scripts/sanity-test.sh` — skipped (storage-manager + encryption-service feed core data paths)
- ❌ Perf snapshot — skipped
- ❌ Byte-level E2E — skipped (regtest-f7 needs encryption + keystore)

**This run contributes zero acceptance evidence for R95 beyond the boot findings above.** The full 55-assertion + perf + E2E sweep will be re-run the moment (1) or (2) lands.

## Asks of the dev team

1. **Revert R95 in docker-compose** (remove `-Dspring.aot.enabled=true` from `JAVA_TOOL_OPTIONS`). Ship as R95.1 hot-fix.
2. **Open ticket for `@EnableJpaRepositories` narrowing audit** on encryption, keystore, license, storage-manager, ai-engine. Align with the other 12 services.
3. **Decide intent:** are those 5 services supposed to carry the full RBAC aspect? If not, the fix is to narrow `@ComponentScan` (not broaden `@EnableJpaRepositories`). That's a product call.
4. **Agree on AOT re-enable gate**: CI must boot every service with AOT on + off before merge.

---

**Data captured:**
- Per-service restart counts + `APPLICATION FAILED TO START` logs (all 5 services).
- Every surviving service's `Started XApplication in N seconds` line.
- `@EnableJpaRepositories` + `@ComponentScan` declarations for every Java service.
- Git SHA: `4e05064c` (tip) with R95 change at `29093f55`.

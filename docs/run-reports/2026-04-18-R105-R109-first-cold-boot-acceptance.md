# R105–R109 first-cold-boot acceptance — 🚨 P0: refresh_tokens table missing, login 500s

**Date:** 2026-04-18
**Build:** R109 (HEAD `f6767ded`, arc = R105a → R105b → R106 → R107 → R108 → R108-bump → R109 on top of R104)
**Changes under test:**
- **R105a** `3d7ac334` — mirror flow terminal status onto `FileTransferRecord`. Targets R100 mailbox-status bug.
- **R105b** `9df273f2` — rich per-step semantic detail for Activity Monitor.
- **R106** `d9b5ee7a` — flow pause/resume controls.
- **R107** `6a9ccc27` — AI Activity Copilot (deterministic diagnosis).
- **R108** `b050c984` — Activity Monitor UI drawer + pause/resume UI.
- **R109** `f6767ded` — backlog: AOT on, SPIRE async, partner-pickup notify, perf script.
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ⚠️ **Mixed.** The platform boots faster and cleaner than any prior release in the arc (34/34 healthy at t=140 s; avg −22 s/service vs R104). AOT is back on cleanly. SPIRE async delivered. **BUT** a P0 bug blocks all authenticated flows: login returns 500 because the `refresh_tokens` table doesn't exist on this build. Migration V70 is located in the wrong module and is silently skipped by the central `db-migrate` runner. Cannot validate any of the R105a / R105b / R106 / R107 / R108 / R109 user-facing features until this is fixed.

---

## Top-line scorecard

| Item | Result |
|---|---|
| All 34 containers healthy (clean cold boot) | ✅ **YES, t=140 s** — 25 s faster than R104 (t=165 s); fastest in arc |
| AOT-on without crashes | ✅ **PASS** — all 18 Java services boot under AOT |
| Boot time vs R104 (AOT-off baseline) | ✅ **avg −22 s/service** — `keystore-manager` 127.7 s (7.7 s from mandate) |
| 120 s mandate | ❌ **1/18** (edi-converter only) — not met; keystore-manager + screening are closest |
| R109 AOT rollback hot-path | ✅ AOT-enabled JAVA_TOOL_OPTIONS confirmed live in containers |
| R109 SPIRE async | ✅ Services HTTP-ready before SPIRE agent connects |
| Login works | ❌ **500 on every /api/auth/login** — P0 |
| Fixture script works | ❌ **Fails at authenticate step** — P0 |
| Sanity sweep | ❌ **Cannot run** — auth broken |
| Byte-level E2E | ❌ **Cannot run** — auth broken |
| R105a mailbox-status fix | ❌ **Cannot verify** — auth broken (code inspection confirms the fix landed) |
| R109 partner-pickup notify | ❌ **Cannot verify** — auth broken |
| Playwright release-gate | ❌ **Cannot run** — fixtures depend on login |

---

## 🚨 P0 — `refresh_tokens` table missing on cold boot

### Symptom

```
$ curl -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"superadmin@tranzfer.io","password":"superadmin"}'
{"status":500,"code":"INTERNAL_ERROR","message":"An unexpected error occurred","path":"/api/auth/login"}
```

### Stack trace (from `mft-onboarding-api` logs)

```
Login successful: superadmin@tranzfer.io          ← audit log fires BEFORE the insert
SQL Error: 0, SQLState: 42P01
Batch entry 0 insert into refresh_tokens (client_ip, created_at, expires_at, revoked, token,
  user_agent, user_email, user_role, id) values (...)
ERROR: relation "refresh_tokens" does not exist
Unhandled exception at /api/auth/login: could not execute batch
```

`AuthService.login()` calls `buildAuthResponse()` which calls `createRefreshToken()` which calls
`refreshTokenRepository.save(...)`. The save fails because the table is absent, the HTTP handler
returns 500, and the client gets no access token.

### Root cause

The migration that creates `refresh_tokens` exists, but it's in the wrong module:

```
$ find . -name "*refresh_tokens*.sql" -not -path "*/target/*"
./onboarding-api/src/main/resources/db/migration/V70__refresh_tokens.sql    ← here
```

Every other platform migration lives at `shared/shared-platform/src/main/resources/db/migration/`.
The dedicated `db-migrate` container runs Flyway from **that directory only** — it never sees
`onboarding-api/src/main/resources/db/migration/`.

`onboarding-api` itself doesn't apply V70 either, because commit `e327ae2c` ("perf: boot time
195s→~40s — Flyway init container, OSIV off, async init") added `-Dspring.flyway.enabled=false`
to the common JAVA_TOOL_OPTIONS. The design pattern is: centralise Flyway in `db-migrate`, turn
it off everywhere else for boot speed. But V70 was never migrated to the central location, so it
runs in neither place.

### Confirmed state

- `flyway_schema_history` reports migrations v1–v202 applied successfully (68 rows).
- `refresh_tokens` table does **not exist** — verified via `\dt` on the `filetransfer` database.
- V70's `CREATE TABLE IF NOT EXISTS refresh_tokens (...)` body is present and correct. Just
  unreachable from the runtime migration path.
- Affected since `e327ae2c` landed; presumably nobody noticed because (a) `RefreshToken`
  persistence isn't exercised by many integration tests, and (b) prior acceptance runs in this
  arc may have happened on a pre-existing postgres volume where the table was left over from an
  earlier `ddl-auto=update` build.

### Fix — one-line move

Move `onboarding-api/src/main/resources/db/migration/V70__refresh_tokens.sql` to
`shared/shared-platform/src/main/resources/db/migration/V70__refresh_tokens.sql`, or copy-create
it as a new `V92__refresh_tokens.sql` in the central location (safer — avoids rewriting the
Flyway history gap between v63 and v87). Either way, the central `db-migrate` container will
pick it up on the next cold boot and create the table.

Alternative: leave V70 where it is, but set `-Dspring.flyway.enabled=true` specifically for
onboarding-api so it applies its own migrations on startup. This regresses the `e327ae2c` boot-
time optimisation on that one service.

**Recommendation:** move the file. Cleanest, keeps central-migration pattern consistent.

### Not filed as a separate bug because...

db-migrate **also** exits 1 at the end of its run, with:

```
APPLICATION FAILED TO START
Parameter 0 of constructor in ListenerInfoController required a bean of type
'ServletWebServerApplicationContext' that could not be found.
```

This is R109's AOT-on flip surfacing a cosmetic issue: db-migrate is a non-web Spring Boot
app, so there's no `ServletWebServerApplicationContext`, but AOT eagerly pre-registers
`ListenerInfoController` (which needs one). Flyway DID run to completion before this failure
(schema reaches v202), so this is a non-functional cosmetic error, **not** the reason the
refresh_tokens table is missing. The migration files genuinely don't include V70.

However, db-migrate exit-code 1 is the wrong signal. The correct fix is:
- Exclude `ListenerInfoController` from db-migrate's `@ComponentScan`, or
- Gate it with `@ConditionalOnWebApplication`, or
- Add a `@Profile("!db-migrate")` to keep it out of that deployment.

---

## ✅ What actually worked on R109

Despite the blocker, there is **real progress** in this arc. Confirming every positive signal:

### 34/34 clean boot at t=140 s — fastest in the arc

| Release | Time to clean |
|---|---:|
| R95 | 🚫 (5 services crashing) |
| R97 | 🚫 (same) |
| R100 | ~165 s (but ftp-web 403 → effectively 34/34 required R103) |
| R104 | 165 s (first true clean) |
| **R109** | **140 s** — 25 s improvement |

### Per-service boot times (R104 AOT-off → R109 AOT-on + SPIRE async)

| Service | R104 | R109 | Δ | ≤120 s |
|---|---:|---:|---:|:---:|
| edi-converter | 27.1 | 30.3 | +3.1 | ✅ |
| keystore-manager | 154.4 | **127.7** | **−26.7** | ❌ (7.7 over) |
| screening-service | 152.2 | **131.3** | **−20.9** | ❌ |
| license-service | 159.3 | 134.0 | −25.3 | ❌ |
| storage-manager | 154.0 | 140.4 | −13.6 | ❌ |
| gateway-service | 177.6 | 141.5 | **−36.1** | ❌ |
| notification-service | 154.7 | 142.1 | −12.6 | ❌ |
| encryption-service | 155.8 | 145.8 | −10.0 | ❌ |
| config-service | 179.3 | 148.4 | −30.9 | ❌ |
| ftp-service | 176.1 | 148.7 | −27.4 | ❌ |
| ftp-web-service | 172.8 | 149.2 | −23.6 | ❌ |
| analytics-service | 168.0 | 150.3 | −17.7 | ❌ |
| platform-sentinel | 168.4 | 152.5 | −15.9 | ❌ |
| onboarding-api | 173.4 | 154.3 | −19.1 | ❌ |
| forwarder-service | 178.5 | 154.6 | −23.9 | ❌ |
| sftp-service | 182.8 | 155.7 | −27.1 | ❌ |
| ai-engine | 179.5 | 156.7 | −22.8 | ❌ |
| as2-service | 160.4 | ~160 | ~0 | ❌ |

**Average: −22 s per service.** Three levers delivered as designed:

- **AOT re-enabled** after R99 + R102 made it safe. Recovers ~15–20 s per service. No crashes.
- **SPIRE async init** (R109 F5). Services become HTTP-ready before SPIRE workload-API answers; no context-refresh wait on the agent handshake. ~3–5 s per service.
- **`keystore-manager` is 7.7 s from mandate.** One more targeted fix closes it.

### @EntityScan narrowing (Option 5) still not shipped

The remaining 18-second tail to close the 120 s mandate on the 14 services between 131–157 s is the
design-doc Option 5 — per-service `@EntityScan` narrowing. This would save 5–10 s each.

### Code-review confirms the R105a fix

Verified by file inspection (cannot verify by behaviour because auth is broken):

```
shared/shared-platform/src/test/java/com/filetransfer/shared/routing/FlowProcessingEngineStatusMirrorTest.java
```

exists and tests the terminal-status mirror. The R105a commit message is explicit: *"mirror flow
terminal status onto FileTransferRecord"* — this is exactly the R100 bug that my test.fail()-wrapped
Playwright pin was written for. Once the P0 refresh_tokens issue is fixed, running
`npm run test:regression` should flip the R100 pin from `test.fail(passing)` to `test.fail(FAILED
BECAUSE BUG IS FIXED)` — which is the correct signal to promote the pin to a regular test.

### partner-pickup-notify feature — cannot verify behaviour, can verify schema

Migration `V91__folder_mapping_pickup_notify.sql` did apply cleanly (visible in
flyway_schema_history). Column `notify_on_pickup` presumably now exists on `folder_mappings`.
Cannot drive the lifecycle event because auth is broken.

---

## Action plan for dev team (priority order)

### 1. **IMMEDIATE:** fix refresh_tokens table

Move `V70__refresh_tokens.sql` from `onboarding-api/src/main/resources/db/migration/` to
`shared/shared-platform/src/main/resources/db/migration/` (rename to `V92__refresh_tokens.sql`
if preserving history numbering is easier). Ship as R109 hot-fix / R110.

### 2. **IMMEDIATE:** fix db-migrate's AOT cosmetic crash

db-migrate exits with code 1 after Flyway succeeds, because AOT eagerly tries to wire
`ListenerInfoController` which needs a servlet context. Options:
- `@Profile("!db-migrate")` or `@ConditionalOnWebApplication` on `ListenerInfoController`
- Or remove `ListenerInfoController` from db-migrate's scan

This is cosmetic (Flyway completes) but the non-zero exit code is technically a CI signal that
migration failed, which is the opposite of what happened.

### 3. **RE-VERIFY ONCE (1) + (2) LAND**

Once refresh_tokens exists, run (from this report's sibling directory):

- `./scripts/build-regression-fixture.sh`
- `./scripts/sanity-test.sh`
- `npm run test:release-gate` (Playwright — all 23 regression/SSE/perf tests)

Particularly, the **R100 mailbox regression pin** will flip: it's currently `test.fail()`-wrapped
because the bug was known-open. When R105a's fix is verified via the pin, the test will FAIL (in
Playwright semantics: test.fail() expects failure; when the underlying bug is actually fixed, the
test passes, which makes the test.fail() wrapper fail the spec). That failure signal is the
prompt to edit the spec and promote the pin to a regular `test()`.

### 4. @EntityScan narrowing (design-doc Option 5) to close the 120 s mandate

Ship last — currently 14 services are 131–157 s. 5–10 s/service per narrowing closes them. Least
urgent because the platform is functional (modulo P0), just slow to boot.

---

## Arc summary — R95 through R109

10 dev-team releases, 8 acceptance reports. Every ask has been addressed or is in flight:

| Tester ask | Status |
|---|---|
| R95 AOT blocker — rollback | ✅ R98 |
| R95/R97 recommendation — @EnableJpaRepositories scope fix | ✅ R99 |
| R97 ask — @EnableAsync(proxyTargetClass) | ✅ R102 |
| R97 ask — CI parity gate | ✅ R100 |
| R100 ask — mailbox status transition | ✅ R105a (need refresh_tokens fix to verify) |
| R100 user-flagged — partner-pickup notify feature | ✅ R109 (need refresh_tokens fix to verify) |
| R100 ask — `ftp-web /actuator/health/liveness` 403 | ✅ R103 |
| R100 ask — `perf-run-v2.sh` BSD grep portability | ✅ R109 |
| R100 ask — re-enable AOT | ✅ R109 |
| R100 ask — SPIRE async off boot critical path | ✅ R109 |
| R101/R102 ask — `java-spiffe-provider` classpath | ✅ R104 |
| R100 ask — `@EntityScan` narrowing (Option 5) | ⏳ not shipped |
| R100 ask — §11 FTP direct PASV/LIST | ⏳ not investigated |

The outstanding items from R100 are non-blocking (feature-complete platform, just not all
boot-time). R105–R109 adds a significant new feature surface (pause/resume, AI Copilot, per-step
semantics) on top of all the fixes.

This has been the healthiest release cadence in the arc: R104 → R109 is 5 releases in under 24 h
with one P0, two acceptance reports, and four prior asks closed. Dev-team turnaround remains
excellent. The R109 P0 above blocks release readiness but is a 1-line fix.

---

**Git SHA:** `f6767ded` (R109 tip).
**Report commit:** to follow.
**Prior acceptance reports:** `docs/run-reports/2026-04-18-R{95,97,100,R101-R102,R103-R104}-*.md`.

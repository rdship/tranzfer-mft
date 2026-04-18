# R125 hot-fix acceptance — 🥉 Bronze; build green, 4 of 6 claims verified, V93 EDI migration in wrong module

**Date:** 2026-04-18
**Build:** R125 hot-fix (HEAD `550e87bd`)
**Outcome:** 🥉 **Bronze.** Compile P0 from R125 is closed. Of the 5+1 claimed fixes, **4 land cleanly** (flow actions 500→404, dashboard widget 500→200, JFR revert, compile revert). **Storage retrieve is partially fixed** — bogus trackId now 404, valid trackId still 500 (new root cause: "Query did not return a unique result: 2 rows"). **V93 EDI migration NEVER APPLIED** — same P0 pattern as R109 V70 refresh_tokens (migration file in `ai-engine/` instead of `shared/shared-platform/`).

---

## ✅ Verified landed

### 1. Compile P0 cleared
```
$ mvn clean package -DskipTests ... BUILD SUCCESS
```
R125 hot-fix reverted `StorageController.retrieve/stream` signatures to `throws Exception`. Build green.

### 2. JFR boot regression reverted
- JAVA_TOOL_OPTIONS on onboarding-api: no `StartFlightRecording` flag (opt-in via `BOOT_JFR_FLAG` env).
- Boot times back to normal: 210–240 s (vs R124's 320–358 s). StartupTimingListener + BootPhaseDumpCollector still active.
- Effectively closes my R125 ask #6 on the previous report.

### 3. Flow action endpoints 500 → 404
| Endpoint | R124 | **R125 hot-fix** |
|---|---:|---:|
| POST /api/flow-executions/:id/retry | 500 | **404** ✅ |
| POST /api/flow-executions/:id/cancel | 500 | **404** ✅ |
| POST /api/flow-executions/:id/stop | 500 | **404** ✅ |
Correct behaviour — routes don't exist (UI actually uses `/restart` + `/terminate`), now handled by `NoHandlerFoundException` → 404 instead of falling through to generic 500.

### 4. Dashboard widget endpoint
`/api/flows/executions?size=20` was 500 on R124.
- Now **200 on config-service (:8084)** — the service that owns it.
- Still 404 on onboarding-api — correct (not this service's endpoint).
The Dashboard widget should work once the UI hits the right port.

### 5. Activity Monitor Restart button broadening
Source-verified: `ActivityMonitor.jsx` line 1737 now renders the button for `FAILED | CANCELLED | UNMATCHED | PAUSED | PENDING`. UI can't be exercised on this port (gateway TLS unreachable from host — separate issue below) but source is correct.

### 6. `NoHandlerFoundException` + `NoResourceFoundException` mapped to 404 in `PlatformExceptionHandler`
Verified indirectly — every probe that used to 500 with "An unexpected error occurred" now 404s cleanly.

---

## ⚠️ Partial / incomplete

### 7. Storage download fix — half-landed

R125's fix handles the "DB row absent" case. That now returns 404 correctly. But the **common path** — a real trackId from a completed flow — still 500s with a new root cause:

```
Unhandled exception at /api/v1/storage/retrieve/TRZVYJHR5R4H:
  Query did not return a unique result: 2 results were returned
```

Every real flow produces multiple storage records per trackId (e.g. input snapshot + EXECUTE_SCRIPT output). The `retrieve by trackId` query uses a unique-expecting fetch that throws on 2+ results. **This means the user's original "cannot download from Activity Monitor" concern is NOT resolved** — every completed flow will hit this 500.

**Fix for R126**: either change the retrieve query to return the latest / the final output (ordered by `created_at DESC LIMIT 1`), or disambiguate via `stepIndex` / `direction` in the URL.

---

## ❌ Broken: V93 EDI migration in wrong module (P0 pattern recurrence)

### Evidence

```
$ find . -name "V93*" -not -path "*/target/*"
./ai-engine/src/main/resources/db/migration/V93__ensure_edi_training_tables.sql

$ ls shared/shared-platform/src/main/resources/db/migration/V9*
V91__folder_mapping_pickup_notify.sql
V92__refresh_tokens.sql
V9__as2_protocol_support.sql              ← V93 missing from here

$ docker exec mft-postgres psql ... "SELECT * FROM flyway_schema_history WHERE version='93'"
(0 rows)

$ docker exec mft-postgres psql ... "SELECT tablename FROM pg_tables WHERE tablename LIKE 'edi_%'"
edi_mapping_correction_sessions            ← pre-existing (V16); the only EDI table
                                             edi_training_samples/maps/sessions MISSING

$ docker exec mft-ai-engine printenv JAVA_TOOL_OPTIONS | grep flyway
-Dspring.flyway.enabled=false
```

### Root cause

This is **identical** to R109's V70 refresh_tokens P0 pattern:
- V93 migration file is in `ai-engine/src/main/resources/db/migration/`, not the central `shared/shared-platform/src/main/resources/db/migration/`.
- The `mft-db-migrate` container only reads from the shared-platform directory.
- `spring.flyway.enabled=false` is set platform-wide (since commit `e327ae2c`, the R50-era boot-time optimisation). So ai-engine doesn't apply its own migration either.
- **Migration never runs. EDI tables never created.**

Which means 4 of the 10 GET endpoints from the R123 audit that R125 claimed to fix are **STILL 500** on R125 hot-fix:

```
500 /api/v1/edi/training/health
500 /api/v1/edi/training/maps
500 /api/v1/edi/training/samples
500 /api/v1/edi/training/sessions
```

### Fix for R126

Move `V93__ensure_edi_training_tables.sql` from `ai-engine/src/main/resources/db/migration/` to `shared/shared-platform/src/main/resources/db/migration/` (or rename to `V94__...` if V93 has been partially applied elsewhere). The `IF NOT EXISTS` guards make this safe on every environment.

**This is the second time the project has made this exact mistake** (first was R109 V70 refresh_tokens). Consider adding a CI check: "no migration file may live outside `shared/shared-platform/src/main/resources/db/migration/`."

---

## Standard sweep status

| Check | Result |
|---|---|
| 34/34 healthy | ✅ at t=228 s |
| Byte-E2E `r125hf.dat` | ✅ status=COMPLETED |
| Sanity sweep | ✅ 56/60 (3 pre-existing FTP-direct) |
| No crash loops | ✅ |
| Gateway TLS | ❌ `TLSv1.3 decode error` on `https://localhost:443` (may be test-env artefact; direct ports work) |

Gateway TLS failure is worth noting but didn't block this sweep — went direct to service ports.

---

## Complete R124/R125 endpoint audit state

Of the 14 broken endpoints from the R123 audit:

| Endpoint | R123 | R125 hot-fix | Status |
|---|---:|---:|---|
| `/api/v1/storage/retrieve/:id` (bogus) | 500 | 404 | ✅ Fixed |
| `/api/v1/storage/retrieve/:id` (valid) | 500 | 500 | ❌ New cause — "2 rows" |
| `/api/flow-steps/:id/0/input/content` | 403 | 403 | ❌ Still open |
| `/api/flow-steps/:id/0/output/content` | 403 | 403 | ❌ Still open |
| `/api/flow-steps/:id/1/output/content` | 403 | 403 | ❌ Still open |
| POST `/api/flow-executions/:id/retry` | 500 | 404 | ✅ Fixed |
| POST `/api/flow-executions/:id/cancel` | 500 | 404 | ✅ Fixed |
| POST `/api/flow-executions/:id/stop` | 500 | 404 | ✅ Fixed |
| `/api/flows/executions?size=20` | 500 | 200 (config-svc) | ✅ Fixed |
| `/api/p2p/tickets` | 500 | not probed | - |
| `/api/v1/edi/training/health` | 500 | 500 | ❌ V93 in wrong dir |
| `/api/v1/edi/training/maps` | 500 | 500 | ❌ V93 in wrong dir |
| `/api/v1/edi/training/samples` | 500 | 500 | ❌ V93 in wrong dir |
| `/api/v1/edi/training/sessions` | 500 | 500 | ❌ V93 in wrong dir |
| `/api/v1/edi/correction/sessions` | 400 | not re-probed | - |
| `/api/v1/screening/hits` | 500 | 500 | ❌ Not in R125 scope |
| `/api/v1/screening/results` | 500 | 500 | ❌ Not in R125 scope |
| `/api/partner/test-connection` | 404 | 404 | ❌ Not in R125 scope |

**Net: 5 of 14 closed. 9 still broken.** Real progress but primary axis (file download from Activity Monitor) still doesn't work for a user.

---

## 🏅 Release Rating — R125 hot-fix

### R125 hot-fix = 🥉 Bronze

| Axis | Assessment |
|---|---|
| **Works** | ⚠️ Better than R124 — flow actions clean 404s, dashboard widget 200. But file download from Activity Monitor still 500s for real completed flows. EDI pages still blank. |
| **Safe** | ✅ Holds |
| **Efficient** | ✅ JFR revert restores R122/R123 boot-time range |
| **Reliable** | ✅ Build compiles; 34/34 healthy; no crash loops; R124 observability still in place |

**Bronze because:** Works axis has known breakage (download, EDI). Meaningful improvement over R124 but still below Silver's "primary feature axes functional" bar.

**Why not Silver:** The user's R123 UI walk-through finding — "can't download files in Activity Monitor" — was the catalyst for grading Silver down to Bronze. On R125 hot-fix, that specific feature STILL doesn't work for the common case (valid completed flow → 500 on retrieve). Cannot grade Silver until that works.

**Why not No Medal:** Every one of R124's failure modes is strictly improved or held. Platform is stable. Build is green.

### Trajectory

| Release | Medal |
|---|---|
| R121+R122 | 🥈 |
| R123 | 🥉 |
| R124 | 🥉 |
| R125 (initial) | 🚫 |
| **R125 hot-fix** | 🥉 |

### Asks for R126 (priority)

1. **MOVE V93 migration** from `ai-engine/` to `shared/shared-platform/` — identical mistake as R109 V70. Consider CI check.
2. **Fix storage retrieve "2 rows" 500** — unique-expecting query doesn't handle multi-step flows. Change to latest-output lookup or add step disambiguator.
3. **Fix `/api/flow-steps/:id/:step/:dir/content` 403** — Spring Security ACL still rejecting ADMIN JWTs on read path (carry from R123 audit).
4. **Fix Screening GET 500s** — `/api/v1/screening/hits` and `/results` still 500.
5. **Fix `/api/partner/test-connection` 404** — route missing.
6. Investigate gateway TLS failure — `TLSv1.3 decode error` on `https://localhost:443` blocked my probe via the gateway. Backend services directly work.
7. Carry forward: FTP-direct sanity, 120 s boot mandate, 30 min soak, Phase-2 mTLS, Playwright UI/SSE fixture debt.

---

**Git SHA:** `550e87bd`. Build green, 4 of 6 claims verified, 1 partial, 1 in-wrong-module.

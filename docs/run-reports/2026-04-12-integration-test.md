# Integration Test Report — 2026-04-12

**Test:** End-to-end file flow through DMZ proxy SFTP → routing engine → AI engine → flow execution
**Files tested:** 100 (50 CSV, 25 EDI X12 850, 25 binary .dat)
**Upload method:** SFTP via DMZ proxy (port 32222), account `e2e_tester`
**Upload performance:** 100 files in 1.5–2.3 seconds (43–68 files/sec)

---

## What Worked

| Component | Result |
|---|---|
| SFTP login via DMZ proxy | ✅ Authenticated, session established |
| File upload (100 files) | ✅ All 100 landed on SFTP server |
| File detection by routing engine | ✅ All 100 detected within milliseconds |
| Flow matching | ✅ All 100 matched a flow (but wrong flow — see bugs) |
| AI engine classification | ✅ All 100 classified — ~75% MEDIUM risk, ~22% NONE, ~3% score=42 |
| Fabric event publishing | ✅ Events published to Kafka topic `flow.intake` |
| Parallel processing | ✅ 8 async threads (mft-async-1 through -8) |
| Track ID generation | ✅ Unique track IDs generated (e.g., TRZSSL7ZSRRP) |

**The full pipeline is alive:** SFTP upload → file detection → routing → flow matching → AI screening → fabric event → flow execution (attempted).

---

## Issues Found — CTO Action Required (Permanent Solutions)

### ISSUE 1: Flow Routing Ignores Filename Pattern — All Files Match the Same Flow

**Symptom:** All 100 files (CSV, EDI, binary) matched `EDI Processing Pipeline` (pattern: `.*\.edi`) despite only 25 being .edi files. Our test flows (`e2e-csv-flow-001` etc. with pattern `.*csv_1.*`) were never matched.

**Root cause:** `FlowRuleRegistry.findMatch()` iterates compiled rules sorted by priority (lowest number first). The `CompiledFlowRule` for `EDI Processing Pipeline` (priority=10) has a `Predicate<MatchContext>` that either:
- Does NOT test the filename pattern at all
- OR the filename pattern check is missing from the predicate compilation step

**Evidence:** The `CompiledFlowRule` matching is pure `Predicate.test()` — the predicate is built at flow registration time. The compilation logic (not found in this audit — likely in a `FlowRuleCompiler` or similar) must include filename pattern matching in the predicate. Currently it appears to match on direction/protocol only, making every INBOUND flow a match.

**Impact:** File routing is non-functional for filename-based flow selection. Every file hits the highest-priority flow regardless of name.

**Permanent solution:**
1. Find the predicate compilation code (search for `CompiledFlowRule` constructor calls / `Predicate.and()` chains)
2. Ensure `filenamePattern` is compiled into the predicate as: `filename.matches(compiledPattern)`
3. Add unit test: flow with pattern `.*\.edi` should NOT match `invoice.csv`
4. Add integration test: upload CSV and EDI files, verify they match different flows

---

### ISSUE 2: Flow Execution NPE — Steps with Empty Config

**Symptom:** All 100 flow executions ended with `"failed: null"` error. Status stuck at PROCESSING in the DB (error not persisted).

**Root cause:** The `EDI Processing Pipeline` flow has 4 steps. Step 1 (SCREEN) has config `{}` (empty). The step executor likely calls `config.get("policyId")` which returns null, causing NPE.

**Impact:** Every file that matches this flow fails silently. The execution record stays in PROCESSING forever (no FAILED status set).

**Permanent solution:**
1. Add null-safety in every step executor: validate required config keys before execution
2. When a step fails, catch the exception and set `exec.setStatus(FAILED)` + `exec.setErrorMessage(...)` — currently the error isn't persisted
3. Add a scheduled job that marks PROCESSING executions older than X minutes as FAILED (dead-letter recovery)
4. For the SCREEN step specifically: if no policyId is configured, skip the step with a WARNING log instead of NPE

---

### ISSUE 3: Flow Deactivation API Returns 400

**Symptom:** `PUT /api/flows/{id}` with body `{"active":false}` returns HTTP 400.

**Impact:** Cannot deactivate flows through the API. The only way to deactivate is direct DB update, which shouldn't be needed.

**Permanent solution:**
1. Support partial updates (PATCH) on flows — at minimum the `active` field
2. OR: add a dedicated endpoint `POST /api/flows/{id}/deactivate` and `POST /api/flows/{id}/activate`
3. The PUT endpoint should accept partial payloads or at least the `active` field alone

---

### ISSUE 4: Account Creation API Fails — Home Directory on Wrong Filesystem

**Symptom:** `POST /api/accounts` returns 500 with `AccessDeniedException: /data`. The onboarding-api tries to create `/data/sftp/{username}` on its own container filesystem.

**Impact:** Cannot create new transfer accounts through the API in a Docker environment. Had to bypass by inserting directly into the DB + creating dirs in the SFTP container.

**Permanent solution:**
1. Move home directory creation to the SFTP service via an internal API call: `POST /internal/create-home/{username}` on sftp-service
2. OR: the SFTP service should auto-create home directories on first successful login (lazy initialization)
3. The onboarding-api should NOT touch the filesystem at all — it's an API service, not a file service

---

### ISSUE 5: Seed Account SFTP Login Fails — Invalid Credentials

**Symptom:** All 100 seed accounts (`sftp_user_001` through `sftp_user_100`) fail SFTP login with `invalid_credentials` despite having correct bcrypt hashes in the DB.

**Impact:** Cannot use the seeded demo data for actual file transfers. Had to create a new account by inserting directly into the DB and manually creating home directories.

**Permanent solution:**
1. Debug the `SftpPasswordAuthenticator` — trace what it does with the password:
   - Does it look up by username correctly?
   - Does it use bcrypt to verify?
   - Does it call encryption-service to decrypt first?
2. The seed script creates accounts with `"password":"SftpPass@{i}!"` — verify the API hashes this with the same algorithm the SFTP service uses for verification
3. Add an integration test: create account via API, login via SFTP, upload a file

---

### ISSUE 6: Routing Engine Priority Semantics Unclear

**Symptom:** Our test flows with priority=1 (set via DB update) still lost to `EDI Processing Pipeline` with priority=10.

**Root cause:** The `FlowRuleRegistry` pre-compiles rules at startup. DB changes to priority (or active status) are NOT reflected until the service restarts OR the registry is explicitly refreshed.

**Impact:** Runtime flow configuration changes don't take effect. The registry uses a stale cache.

**Permanent solution:**
1. Add a cache-refresh mechanism: either a scheduled poll (`@Scheduled` every 30s to reload from DB) or an event-driven refresh via RabbitMQ/Kafka when a flow is created/updated/deleted
2. The config-service should publish a `FLOW_UPDATED` event that all SFTP/FTP/gateway services consume to refresh their rule registries
3. Document the priority semantics clearly: lower number = higher priority, and specify what happens when multiple flows match

---

## Test Infrastructure Notes

- **Test account `e2e_tester`** was created by direct DB insert (bypassing the broken API). Password hash copied from `sftp_user_001`. Home dirs created manually in SFTP container.
- **21 test flows** created via config-service API (10 CSV, 5 EDI, 5 encrypt, 1 EDI-convert). All returned 201 but were never matched due to Issue 1.
- **DMZ proxy SFTP (port 32222)** works for file upload. Authentication via proxy passes through to the underlying SFTP service correctly.

## Performance Metrics

| Metric | Value |
|---|---|
| SFTP upload throughput (via DMZ proxy) | **43–68 files/sec** |
| File detection latency (upload → routing engine log) | **<10ms** |
| AI classification latency | **~100ms per file** (estimated from log timestamps) |
| Flow execution (attempted) | **100% failed** (Issue 2) |
| Parallel processing threads | **8** (configurable async pool) |

## Next Steps After CTO Fixes

Once Issues 1-6 are resolved:
1. Re-run 100-file test with correct flow matching
2. Verify CSV files match CSV flows, EDI files match EDI flows
3. Measure per-step timing (checksum, compress, EDI convert, mailbox delivery)
4. Set up 3rd-party SFTP server for outbound delivery testing
5. Test EDI conversion quality (X12 850 → JSON)
6. Scale to 1000 files and measure throughput under load

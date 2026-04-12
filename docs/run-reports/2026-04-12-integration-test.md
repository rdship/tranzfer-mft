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

---

## Architectural Observation: The Configuration Model Is Fighting the User

This section is a candid assessment from running the platform end-to-end as an operator — not as someone who built it, but as someone trying to USE it.

### The Problem

To get a single file to flow from point A to point B, an admin currently needs to:

1. Create a **Server Instance** (define host, port, protocol)
2. Create a **Transfer Account** on that server (username, password, permissions)
3. Create a **Folder Mapping** linking source account → destination account with specific paths
4. Create a **File Flow** with filename pattern, direction, priority, and step config
5. Create **Security Profiles**, **Folder Templates**, **Encryption Keys** (depending on the flow steps)
6. Optionally: create **Partners**, **SLA Agreements**, **DLP Policies**, **Notification Rules**

That's a minimum of 4 entities — potentially 8+ — before a single file moves. Each entity has 10-20 fields. Most of those fields are boilerplate that the system could infer.

**The mandatory folder mapping is the worst offender.** A folder mapping is a rigid link between a source account + path and a destination account + path. It assumes the admin knows the exact directory structure in advance. But in practice:

- Partners drop files wherever they want (not always in `/inbound`)
- File patterns are more useful than folder paths for routing
- The same file might need to go to multiple destinations depending on content
- Admins want to say "when a `.csv` file arrives from ACME Corp, encrypt it and send it to GlobalBank" — not "create a folder mapping from account X path /inbound to account Y path /outbound, then create a flow that references this mapping"

The folder mapping adds a layer of indirection that doesn't help the admin think about the problem. It helps the DEVELOPER implement path-based routing. Those are different things.

### What Enterprise-Grade MFT Looks Like

Look at how admins THINK about file transfers:

> "When ACME Corp uploads an EDI 850, convert it to JSON and deliver it to our ERP system via SFTP."

That sentence has 4 concepts:
1. **Who** (ACME Corp — a partner/account)
2. **What** (EDI 850 — a filename/content pattern)
3. **Do what** (convert to JSON — processing steps)
4. **Where** (ERP system via SFTP — a destination)

The system should let the admin express EXACTLY that — in one screen, in one configuration object. Not spread across 4+ entities that reference each other by UUID.

### Proposed Simplification: The "Smart Flow" Model

**One entity to rule them all:** A File Flow should be self-contained. It should have:

```
WHEN:
  - source: ACME Corp (or any, or a list)
  - filename: *.edi (regex)
  - content: X12 850 (optional — detected by AI engine)
  - protocol: SFTP (or any)

DO:
  - step 1: Screen (auto — use default DLP policy)
  - step 2: Convert EDI → JSON
  - step 3: Compress GZIP

DELIVER TO:
  - destination: erp-system.globalbank.com:22 (inline or reference)
  - path: /inbound/converted/
  - credentials: (reference to a stored credential)

IF SOMETHING GOES WRONG:
  - retry: 3 times, 5 min apart
  - notify: ops-team@company.com
  - quarantine: after 3 failures
```

No folder mapping. No separate server instance creation (the destination is inline OR a reference). No mandatory security profile (use the system default unless overridden). No folder template (the system creates directories as needed).

**The current model forces admins to think like database designers.** The proposed model lets them think like business users.

### Backward Compatibility

The existing entities (ServerInstance, TransferAccount, FolderMapping, SecurityProfile, etc.) don't need to be deleted. They become **optional configuration** for advanced users who want fine-grained control. But the default path should be:

1. Admin creates a Flow (one screen, one API call)
2. The system auto-creates the underlying entities as needed (lazy, behind the scenes)
3. If the admin later wants to tweak the auto-created server instance or security profile, they can — but they don't HAVE to

This is the difference between "everything is configurable" (current — good) and "everything is configurable but nothing requires configuration" (proposed — great).

### Concrete Recommendations

1. **Make folder mappings optional on File Flows.** If a flow has a `source` pattern (account name or partner) and a `destination` (inline or reference), the folder mapping is derivable. The system should auto-create it behind the scenes or skip it entirely.

2. **Add a "Quick Flow" API endpoint** — `POST /api/flows/quick` — that accepts the simplified model above and creates all underlying entities automatically. This is the 80% use case. The existing granular APIs serve the 20%.

3. **Add a "Quick Flow" wizard in the Admin UI** — one page, 4 sections (When, Do, Deliver, If-Error). Auto-complete for existing partners/accounts/destinations. Preview the matched files before saving.

4. **Make the routing engine match on filename pattern FIRST** (Issue 1 in this report). Pattern-based routing is the natural mental model. Priority-only routing that ignores patterns is backwards.

5. **Auto-create SFTP home directories on first login** (Issue 4). An admin should never need to SSH into a container to mkdir. The system should handle it.

6. **Auto-detect file type and suggest flows.** When a new file arrives that matches NO flow, instead of silently dropping it, the system should:
   - Log it as "unrouted"
   - Show it in the Activity Monitor with status "UNROUTED"
   - Suggest a flow based on the file extension/content (AI engine already classifies — use that)
   - Let the admin click "Create Flow for this pattern" directly from the Activity Monitor

7. **Defaults everywhere.** If no encryption is specified, don't encrypt. If no DLP policy is specified, skip screening. If no notification rule is specified, use the system default (email the admin on failure). The current SCREEN step NPEs on empty config `{}` — it should gracefully skip instead.

### The Test

The system passes the enterprise-grade test when an admin can:
1. Log into the Admin UI
2. Click "New Flow"
3. Type: source=ACME, pattern=*.csv, action=deliver to partner-sftp.example.com:/inbox
4. Click "Save"
5. Upload a CSV file
6. See it appear on the Activity Monitor with status COMPLETED within 5 seconds

Today, that takes 30+ minutes of configuration across 4+ screens. It should take 30 seconds.

---

## Next Steps After CTO Fixes

Once Issues 1-6 are resolved:
1. Re-run 100-file test with correct flow matching
2. Verify CSV files match CSV flows, EDI files match EDI flows
3. Measure per-step timing (checksum, compress, EDI convert, mailbox delivery)
4. Set up 3rd-party SFTP server for outbound delivery testing
5. Test EDI conversion quality (X12 850 → JSON)
6. Scale to 1000 files and measure throughput under load

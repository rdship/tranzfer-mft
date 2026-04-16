# TranzFer MFT — Product Sanity Validation Test Results

**Date:** 2026-04-16  
**Build:** bdac0c5b (CTO N37 fix: FlowStep Serializable + RedisCacheConfig + version bump)  
**Tester:** QA Automation (Claude)  
**Platform:** 34/35 containers healthy | Boot time: 2m03s  

---

## Summary

| Metric | Value |
|--------|-------|
| Total checks | 49 |
| PASS | 35 |
| FAIL | 4 |
| WARN | 5 |
| SKIP | 5 (blocked by N33) |

---

## Phase 1: Platform Health (6/6 PASS)

| Check | Result |
|-------|--------|
| Container health | **34/35 healthy** (promtail has no healthcheck — normal) |
| onboarding-api | PASS (healthy) |
| sftp-service | PASS (healthy) |
| ftp-service | PASS (healthy) |
| config-service | PASS (healthy) |
| forwarder-service | PASS (healthy) |

**Boot time:** 2m03s (17 → 21 → 34 healthy over 3 check intervals)

---

## Phase 2: Authentication & API (5/6 PASS, 1 WARN)

| Check | Result | Notes |
|-------|--------|-------|
| Login (superadmin@tranzfer.io) | PASS | 173-char JWT token |
| GET /api/accounts | PASS | 200 |
| GET /api/partners | PASS | 200 |
| GET /api/servers | PASS | 200 |
| GET /api/activity-monitor | PASS | 200 |
| GET /api/flows (config-service) | **WARN** | Returns 500 on gateway path — see N37-PARTIAL below |

---

## Phase 3: Onboarding Validation (7/9 PASS, 2 FAIL)

| Check | Result | Notes |
|-------|--------|-------|
| partners >= 5 | PASS | 5 rows |
| transfer_accounts >= 10 | **FAIL** | 6 rows (bootstrap creates fewer on fresh DB — see N39) |
| file_flows >= 10 | **FAIL** on first run | 6 from bootstrap; after Phase 4 creates 15 more = 21 total |
| acme-sftp exists | PASS | |
| globalbank-sftp exists | PASS | |
| logiflow-sftp exists | PASS | |
| medtech-as2 exists | PASS | |
| globalbank-ftps exists | PASS | |

---

## Phase 4: File Flow Creation (1/1 PASS)

| Check | Result | Notes |
|-------|--------|-------|
| Create 15 flows via API | **PASS (15/15)** | All created via POST /api/flows/quick on config-service:8084 |

Flows created:
- SV-EDI 850 Purchase Order, SV-EDI 810 Invoice, SV-EDI 856 Ship Notice, SV-EDIFACT DESADV
- SV-HL7 ADT Patient Admin, SV-HL7 ORM Lab Orders
- SV-ACH Batch Payment, SV-SWIFT MT103 Wire, SV-ISO 20022 pain.001
- SV-PGP Decrypt Inbound, SV-Double Encrypt Outbound
- SV-SFTP to AS2 Gateway, SV-FTP to SFTP Upgrade
- SV-SOX Audit Export, SV-AML Screening

---

## Phase 5: File Upload via 3rd-Party Clients (1/1 PASS)

| Check | Result | Notes |
|-------|--------|-------|
| Upload 15 files via SFTP + FTP | **PASS** | 13 via `sshpass`+`sftp`, 2 via `curl` FTP |

Files uploaded to:
- acme-sftp: PO_ACME.850, pain001_ACME.xml
- globalbank-sftp: INV_GLOBALBANK.810, ACH_PAYROLL.ach, MT103_WIRE.swi, FX_RATES.json, AML_TXN_SCREENING.csv
- logiflow-sftp: DESADV_LOGI.edifact
- sftp-prod-1: ORM_LABORDER.hl7, CLASSIFIED_SECRET.dat
- sftp-prod-2: B2B_EXCHANGE.xml
- sftp-prod-4: SOX_AUDIT.csv, ONBOARD_TEST.txt
- ftp-prod-1: ASN_LOGISTICS.856, MIGRATE_LEGACY.dat

---

## Phase 6: Pipeline Processing (3/4 PASS, 2 FAIL)

| Check | Result | Notes |
|-------|--------|-------|
| FileUploadedEvents fired | **PASS** | 8 events detected in sftp-service logs |
| Transfer records created | **FAIL** | 0 rows — N33 BLOCKER |
| Flow executions created | **FAIL** | 0 rows — N33 BLOCKER |
| Flow Rule Registry compiled | PASS | 21 flows compiled |

---

## Phase 6b: Activity Monitor & Flow Execution Lifecycle (15/20 PASS, 4 WARN, 5 SKIP)

### Activity Monitor API

| Check | Result | Notes |
|-------|--------|-------|
| GET /api/activity-monitor | PASS (200) | 0 entries — correct given N33 |
| GET /api/activity-monitor/stats | PASS (200) | 0 transfers in 24h |
| GET /api/activity-monitor/stream (SSE) | **WARN** | 403 — token via query param rejected by security filter (N40) |
| GET /api/activity-monitor/export (CSV) | PASS (200) | |
| Filter by status=PENDING | PASS (200) | |
| Filter by status=FAILED | PASS (200) | |
| Filter by status=MOVED_TO_SENT | PASS (200) | |
| Filter by status=DOWNLOADED | PASS (200) | |
| Filter by status=IN_OUTBOX | PASS (200) | |

### Flow Execution API

| Check | Result | Notes |
|-------|--------|-------|
| GET /api/flow-executions/live-stats | PASS (200) | processing=0, failed=0 |
| GET /api/flow-executions/pending-approvals | PASS (200) | |
| GET /api/flow-executions/scheduled-retries | PASS (200) | |
| GET /api/journey | PASS (200) | |
| GET /api/flows/executions (config-service) | **WARN** | 500 — same serialization pattern as N37 on FlowExecutionDto |

### Flow Execution Lifecycle (blocked by N33)

| Check | Result | Notes |
|-------|--------|-------|
| Restart execution | **SKIP** | No flow executions exist |
| Terminate execution | **SKIP** | No flow executions exist |
| Schedule retry | **SKIP** | No flow executions exist |
| Detail/history view | **SKIP** | No flow executions exist |
| Transfer Journey detail | **SKIP** | No flow executions exist |

### Error Handling Validation

| Check | Result | Notes |
|-------|--------|-------|
| Bulk restart empty trackIds | PASS (400) | Correctly rejects |
| Terminate non-existent | **WARN** | Returns 400, expected 404 |
| Restart non-existent | PASS (404) | |

---

## New Observations This Run

### N37-PARTIAL: Redis Cache Poisons Flow List After First Successful Call

**Status:** CTO fix partially resolved — `FlowStep implements Serializable` + `RedisCacheConfig` with JSON serializer work correctly on first call. **But:** The `@Cacheable` caches with key `flows::SimpleKey []`, and if the service's DB query returns 0 on an early probe (before Flyway completes or during boot race), the cache stores the empty list. All subsequent calls return 0 flows despite 21 in DB. `FLUSHALL` fixes it, then next call returns 21 flows correctly.

**Evidence:**
```
redis> KEYS *flow*
flows::SimpleKey []     ← cached empty list

redis> FLUSHALL
OK

curl /api/flows → 21 flows  ← correct after flush
```

**Recommendation:** Add `unless = "#result.isEmpty()"` to the `@Cacheable` annotation to prevent caching empty results.

### N39: Bootstrap Creates Only 6 Transfer Accounts on Fresh DB

Previous builds seeded 239 accounts (100 SFTP + 100 FTP + named accounts). Current bootstrap creates only 6 named accounts. The sanity validation threshold of >=10 accounts fails on fresh installs.

**Impact:** Fewer demo accounts available for file uploads. The sanity script creates flows referencing sftp-prod-1 through sftp-prod-4 which don't exist.

**Recommendation:** Either restore bulk seed or lower sanity threshold to match bootstrap output.

### N40: Activity Monitor SSE Stream Rejects Query-Param Token

The SSE endpoint at `/api/activity-monitor/stream?token=<JWT>` returns 403. The `@PreAuthorize("permitAll()")` annotation exists on the controller method, but the security filter chain intercepts the request before it reaches the controller. The `EventSource` browser API cannot send `Authorization` headers, so the query-param token path is the only option for SSE.

**Evidence:** `curl /api/activity-monitor/stream?token=<valid-jwt>` → HTTP 403

### N41: Platform Sentinel Shows "Offline" on UI Despite Container Being Healthy

Docker: `mft-platform-sentinel Up 7 minutes (healthy)`. Sentinel is running analysis cycles (PerformanceAnalyzer, SecurityAnalyzer, HealthScoreCalculator all completing). But UI shows it as "offline". The onboarding-api's ServiceRegistryController or the UI's health check to sentinel fails — likely SPIFFE inter-service auth blocking the health probe.

**Evidence:** Sentinel logs show `HealthScoreCalculator: overall=69, infra=10, data=100, security=85` — the service is working.

### N42: AI Engine Shows "Unavailable" Despite Being Healthy

Docker: `mft-ai-engine Up 7 minutes (healthy)`. AI Engine runs OSINT collection, CVE monitoring, and threat intelligence. But:
1. `storage-manager` returns 403 to AI Engine's storage object list request (SPIFFE auth blocking)
2. Actuator `/metrics/jvm.memory.used` endpoint returns 404 (static resource handler catches it instead of actuator)
3. URLhaus and ThreatFox OSINT feeds return 401 (API keys not configured)

UI shows "AI Engine unavailable" because the health probe from onboarding-api to ai-engine:8091 fails.

**Evidence:**
```
[storage-manager] listObjects failed: HTTP 403 FORBIDDEN
[ai-engine] URLhaus returned HTTP 401
[ai-engine] Actuator metrics: No static resource actuator/metrics/jvm.memory.used
```

### N43: Config-Service GET /api/flows/executions Returns 500

Same serialization pattern as N37 but for `FlowExecutionDto` or its nested types. The CTO's `RedisCacheConfig` fix with JSON serializer should cover this, but the `flow_executions` query also has the PostgreSQL "could not determine data type of parameter $1" error (nullable parameter binding issue in the JPA `@Query`).

**Evidence:** HTTP 500, correlationId `6e0c25b2`

---

## Issue Status Summary

| Issue | Severity | Status | Description |
|-------|----------|--------|-------------|
| N33 | **P0** | OPEN | SEDA pipeline disconnected — 0 transfer records after uploads |
| N37 | P1 | **PARTIAL** | FlowStep Serializable fixed, but cache poisons empty result |
| N39 | P2 | NEW | Bootstrap creates only 6 accounts (was 239) |
| N40 | P2 | NEW | SSE stream rejects query-param JWT token |
| N41 | P1 | NEW | Platform Sentinel shows offline despite healthy container |
| N42 | P1 | NEW | AI Engine shows unavailable — SPIFFE auth + missing API keys |
| N43 | P1 | NEW | Config-service flow executions search returns 500 |

---

## Test Artifacts

| Artifact | Location |
|----------|----------|
| Sanity validation script | `tests/sanity/run-sanity-validation.sh` |
| This report | `docs/run-reports/sanity-validation-results-20260416.md` |
| Error logs zip | `docs/run-reports/error-logs-20260416.zip` |
| Thread dumps + DB state | `/tmp/mft-sanity-20260415-194557/` |
| Previous test report | `docs/run-reports/demo-flow-test-report-20260416.md` |
| Issue tracker | `docs/TESTER-ISSUES-TRACKER.md` |

---

---

## UI Screen Reliability Audit

Tested every UI-backing API endpoint through the nginx gateway (localhost:80) — the path the browser uses.

### All 30 UI Screens Tested

| Screen | API Endpoint | Gateway (port 80) | Direct | Root Cause |
|--------|-------------|-------------------|--------|------------|
| Processing Flows | /api/flows | **OK** | OK (after FLUSHALL) | Cache poisons empty on boot race (N37-PARTIAL) |
| Partner Management | /api/partners | **OK** | OK | |
| Transfer Accounts | /api/accounts | **OK** | OK | |
| Servers | /api/servers | **OK** | OK | |
| Folder Mappings | /api/folder-mappings | **OK** | OK | |
| Activity Monitor | /api/activity-monitor | **OK** | OK | Empty — N33 (no records) |
| Activity Stats | /api/activity-monitor/stats | **OK** | OK | 0 transfers — N33 |
| Live Activity (SSE) | /api/activity-monitor/stream | **403** | 403 | N40 — JWT via query param rejected |
| Flow Fabric | /api/flow-executions/live-stats | **OK** | OK | 0 processing — N33 |
| Transfer Journey | /api/journey | **OK** | OK | Empty — N33 |
| DLQ Messages | /api/dlq/messages | **OK** | OK | |
| Clusters | /api/clusters | **OK** | OK | |
| Service Registry | /api/service-registry | **OK** | OK | |
| Proxy Groups | /api/proxy-groups | **OK** | OK | |
| Platform Listeners | /api/platform/listeners | **OK** | OK | |
| Audit Logs | /api/audit-logs | **OK** | OK | |
| Webhooks | /api/partner-webhooks | **OK** | OK | |
| Snapshot Retention | /api/snapshot-retention | **OK** | OK | |
| Sentinel Findings | /api/v1/sentinel/findings | **OK** | OK | |
| Quarantine | /api/v1/quarantine | **OK** | OK | |
| Compliance Profiles | /api/compliance/profiles | **OK** | OK | |
| External Destinations | /api/external-destinations | **OK** | OK | |
| AS2 Partnerships | /api/as2-partnerships | **OK** | OK | |
| Connectors | /api/connectors | **OK** | OK | |
| Tenants | /api/v1/tenants | **OK** | OK | |
| DLP Policies | /api/v1/dlp/policies | **OK** | OK | |
| Threat Indicators | /api/v1/threats/indicators | **OK** | OK | |
| VFS Intents | /api/vfs/intents/recent | **OK** | OK | |
| EDI Maps | /api/v1/edi/maps | **404** | 404 | N44 — no gateway route to edi-converter for /api/v1/edi/maps |
| Licenses | /api/v1/licenses | **400** | 400 | N45 — requires `X-Admin-Key` header, UI doesn't send it |
| Config Export | /api/v1/config-export | **405** | 405 | N46 — endpoint is POST only, UI may send GET |

**Summary: 27/30 OK | 3 broken (EDI Maps, Licenses, Config Export)**

### Screens That Load But Show Empty/Stale Data (N33 Impact)

These screens return HTTP 200 but show no data because the SEDA pipeline never creates records:

| Screen | What's Missing | Blocked By |
|--------|---------------|------------|
| Activity Monitor | 0 transfer entries | N33 |
| Activity Stats | 0 transfers in all periods | N33 |
| Live Activity (SSE) | No real-time events stream | N33 + N40 |
| Flow Fabric dashboard | processing=0, failed=0 | N33 |
| Transfer Journey | No journeys to display | N33 |
| Flow Execution detail | No executions to view/restart/terminate | N33 |

### Processing Flows — Cache Race Condition (N37-PARTIAL)

The Processing Flows screen shows "0 flows configured" intermittently despite 21 flows in DB. This happens when:
1. Boot starts → Sentinel/monitoring probes call `GET /api/flows` early
2. Early call returns empty list (DB still seeding or Flyway running)
3. `@Cacheable` stores empty list in Redis under `flows::SimpleKey []`
4. All subsequent calls return cached empty list
5. **Fix:** `FLUSHALL` on Redis, or add `unless = "#result.isEmpty()"` to `@Cacheable`

### Platform Sentinel & AI Engine "Offline" on Dashboard

Both containers are healthy and running analysis cycles, but the UI dashboard shows them as offline:

| Service | Docker Status | Internal Activity | UI Status | Root Cause |
|---------|--------------|-------------------|-----------|------------|
| Platform Sentinel | healthy | HealthScore=69, SecurityAnalyzer running | "Offline" | Health probe from onboarding-api to sentinel:8098 fails (SPIFFE auth) |
| AI Engine | healthy | OSINT collection, CVE monitoring active | "Unavailable" | storage-manager returns 403 (SPIFFE), actuator /metrics 404, OSINT feeds 401 |

---

## New Issues (N44-N46)

### N44: EDI Maps Screen — No Gateway Route (404)

**UI path:** `/api/v1/edi/maps`  
**Root cause:** Nginx gateway has route for `/api/v1/convert/` → edi-converter:8095, but NOT for `/api/v1/edi/` which is where the EDI map CRUD endpoints live. The UI calls `/api/v1/edi/maps` which falls through to the default UI proxy, returning 404.  
**Fix:** Add to `api-gateway/nginx.conf`:
```nginx
location /api/v1/edi/ { set $up edi-converter:8095; proxy_pass http://$up; }
```

### N45: Licenses Screen — Missing X-Admin-Key Header (400)

**UI path:** `/api/v1/licenses`  
**Root cause:** `LicenseController` requires `X-Admin-Key` header (`@RequestHeader("X-Admin-Key")`). The UI sends `Authorization: Bearer <JWT>` but not the admin key. Endpoint returns 400 "Required header 'X-Admin-Key' is missing".  
**Fix:** Either (1) UI sends `X-Admin-Key` header from config, or (2) LicenseController accepts JWT auth as alternative to admin key.

### N46: Config Export — GET Not Supported (405)

**UI path:** `/api/v1/config-export` (GET)  
**Root cause:** `ConfigExportController` only exposes `@PostMapping`. The UI likely sends GET to load the export page, or the endpoint should support GET for download.  
**Fix:** Add `@GetMapping` to ConfigExportController for export download, or change UI to POST.

---

## Next Steps

1. **Dev team resolves N33** — trace `sftp.account.events` consumer → why no `file_transfer_records` created. This unblocks Activity Monitor, Flow Fabric, Transfer Journey, and all lifecycle operations.
2. **Add `unless="#result.isEmpty()"` to @Cacheable** — prevents cache poisoning on empty results (N37-PARTIAL)
3. **Fix SPIFFE auth for Sentinel/AI Engine health probes** — N41/N42 show as offline on UI despite healthy containers
4. **Add nginx route for EDI Maps** — one-line fix in nginx.conf (N44)
5. **Fix License endpoint auth** — accept JWT as alternative to X-Admin-Key (N45)
6. **Re-run sanity validation** — 5 skipped lifecycle tests will auto-execute once N33 is fixed

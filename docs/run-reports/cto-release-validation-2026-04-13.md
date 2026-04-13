# TranzFer MFT — CTO Release Validation Report

**Date:** 2026-04-13  
**Build:** Latest main (CTO hotfixes + V59/V61/V62 migrations)  

---

## What CTO Shipped Today

| Commit | Change | Status |
|--------|--------|--------|
| InputSanitizer.java | XSS sanitization utility (we flagged raw `<script>` storage) | Deployed |
| PlatformExceptionHandler.java | Better error responses (we flagged 500→400) | Deployed |
| V58__partner_unique_company_name.sql | Unique constraint on partner name (we flagged duplicates) | Deployed |
| BruteForceProtection.java | Account lockout improvements (we flagged no unlock API) | Deployed |
| AuthController / AuthService | Auth hardening | Deployed |
| PartnerManagementController | Partner API fixes | Deployed |
| **ServerInstance → ServerConfig** | Entity renamed with new fields (name, serviceType, host, port, properties) | **Breaking — demo-onboard.sh needs update** |
| **AS2 3DES deprecated** | 3DES encryption blocked (AES128/192/256 only) | **Breaking — AS2 partnerships using 3DES fail** |
| V61__activity_materialized_view.sql | Materialized view for Activity Monitor (our proposal!) | **Deployed — required manual column fix** |
| V62__query_timeout_protection.sql | 30s query timeout (our recommendation) | Deployed |
| TransferActivityView entity + repository | JPA entity for materialized view | Deployed |
| ActivityViewRefresher | 30s scheduled refresh of materialized view | Deployed |
| ActivityMonitor.jsx UI enhancements | Date range picker + new filters (our proposal!) | Deployed |

## Validation Actions Taken

### 1. Build: Clean (26 images, 0 errors)

### 2. Migrations Applied
- V58 partner_unique_company_name — applied
- V59 sentinel_and_storage_tables (our fix) — applied
- V61 activity_materialized_view — **required manual fix:**
  - `destination_account_id` column missing from `file_transfer_records`
  - Added via `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
  - Then V61 created successfully (view + 7 indexes)
- V62 query_timeout_protection — applied

### 3. Platform Health
- 36 containers launched
- Infra services (postgres, redis, rabbitmq, vault, spire) healthy in <30s
- Java services booting (~3 min for Hibernate entity scan)
- Sentinel + storage-manager healthy (V59 fix holds)

### 4. Breaking Changes for Dev Team

**a. ServerInstance → ServerConfig**
- Entity renamed, fields changed
- `instanceId` → removed (use `name`)
- `protocol` → `serviceType` (enum ServiceType)
- `internalHost/Port`, `externalHost/Port` → `host`, `port`
- `maxConnections` → `properties` (JSONB map)
- **Impact:** demo-onboard.sh server creation fails (HTTP 500, "Unrecognized field instanceId")
- **Action:** Update seed script to use new ServerConfig format

**b. AS2 3DES Blocked**
- `encryptionAlgorithm: '3DES'` now returns 400: "Encryption algorithm '3DES' is deprecated"
- Allowed: AES128, AES192, AES256
- **Impact:** demo-onboard.sh AS2 partnership creation fails
- **Action:** Update seed script AS2 partnerships to use AES256

**c. V61 Materialized View — Missing Column**
- V61 references `r.destination_account_id` in the materialized view
- This column doesn't exist in V56 schema — needs its own migration (V60)
- **Action:** Add `V60__add_destination_account_id.sql`:
  ```sql
  ALTER TABLE file_transfer_records ADD COLUMN IF NOT EXISTS destination_account_id UUID;
  CREATE INDEX IF NOT EXISTS idx_ftr_dest_account ON file_transfer_records(destination_account_id);
  ```

---

## What We Delivered Today (Session Summary)

### Playwright Test Suite: 355 Tests
- 16 test files covering all 22 microservices, 60+ pages, 200+ endpoints
- Real SFTP file transfers (EDI, HL7, XML, JSON, CSV, bulk)
- API validation (SQL injection, XSS, boundary testing)
- **Run 1:** 206 pass, 20 flaky, 0 failures (12m 18s)
- **Run 2 (validation):** 196 pass, 29 flaky, 0 failures (13m 12s)
- Flaky analysis: all 29 are UI timing (JWT expiry + React lazy-load), zero API flaky

### Design Proposals (under docs/design/)
1. **ACTIVITY-MONITOR-V2-PROPOSAL.md** — Complete redesign: V2 API with 15 new filters, SSE streaming, materialized view, 6-phase roadmap. CTO already implementing (V61, V62).
2. **RULE-ENGINE-PERFORMANCE-ANALYSIS.md** — Deep-dive benchmarks: rule engine at 333K matches/sec, bottleneck is infrastructure not engine. Path to 1B files/day mapped.

### Fixes Committed
1. **V59 migration** — sentinel_rules + write_intents tables (fixed 2 crash-looping services)
2. **Flaky test analysis** — root causes + fix recommendations added to test report

### Infrastructure
- 3rd-party SFTP server (atmoz/sftp) for delivery testing
- Test data factories for all entity types
- Auth fixture with API helper for Playwright

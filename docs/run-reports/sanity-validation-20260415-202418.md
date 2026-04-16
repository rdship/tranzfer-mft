# TranzFer MFT — Product Sanity Validation Report
**Date:** 2026-04-15 20:24:51
**Results:** PASS=36 | FAIL=4 | WARN=4 | SKIP=5

## Test Results
| Status | Check |
|--------|-------|
| PASS | Platform health: 34/35 containers healthy |
| PASS | onboarding-api is healthy |
| PASS | sftp-service is healthy |
| PASS | ftp-service is healthy |
| PASS | config-service is healthy |
| PASS | forwarder-service is healthy |
| PASS | Login successful (token: 173 chars) |
| PASS | GET /api/accounts returns 200 |
| PASS | GET /api/partners returns 200 |
| PASS | GET /api/servers returns 200 |
| PASS | GET /api/activity-monitor returns 200 |
| PASS | GET /api/flows (config-service) returns 200 |
| PASS | DB: partners has 5 rows (min: 5) |
| FAIL | DB: transfer_accounts has only 6 rows (expected >= 10) |
| FAIL | DB: file_flows has only 6 rows (expected >= 10) |
| PASS | Account exists: acme-sftp |
| PASS | Account exists: globalbank-sftp |
| PASS | Account exists: logiflow-sftp |
| PASS | Account exists: medtech-as2 |
| PASS | Account exists: globalbank-ftps |
| PASS | Created 15 flows via API (0 failures) |
| PASS | Uploaded 15 files via 3rd-party SFTP + FTP clients |
| PASS | SFTP RoutingEngine fired 8 FileUploadedEvents |
| FAIL | Transfer records: 0 — SEDA pipeline not creating records (N33) |
| FAIL | Flow executions: 0 — pipeline intake not processing (N33) |
| PASS | Flow Rule Registry:  flows compiled |
| WARN | Activity Monitor: 0 entries (N33 — pipeline not creating records) |
| PASS | Activity Monitor stats: 0 transfers in 24h (HTTP 200) |
| WARN | Activity Monitor SSE stream returned 403 |
| PASS | Activity Monitor CSV export returns 200 |
| PASS | Activity Monitor filter by status=PENDING returns 200 |
| PASS | Activity Monitor filter by status=FAILED returns 200 |
| PASS | Activity Monitor filter by status=MOVED_TO_SENT returns 200 |
| PASS | Activity Monitor filter by status=DOWNLOADED returns 200 |
| PASS | Activity Monitor filter by status=IN_OUTBOX returns 200 |
| PASS | Flow Execution live-stats: processing=0 failed=0 |
| PASS | Flow Execution pending-approvals returns 200 |
| PASS | Flow Execution scheduled-retries returns 200 |
| PASS | Transfer Journey search returns 200 |
| WARN | Config-service flow executions search returned 500 |
| SKIP | Flow Execution restart test — no executions exist (N33) |
| SKIP | Flow Execution terminate test — no executions exist (N33) |
| SKIP | Flow Execution schedule-retry test — no executions exist (N33) |
| SKIP | Flow Execution detail/history test — no executions exist (N33) |
| SKIP | Transfer Journey detail test — no executions exist (N33) |
| PASS | Bulk restart rejects empty trackIds (400) |
| WARN | Terminate non-existent returned 400 (expected 404) |
| PASS | Restart non-existent returns 404 |
| PASS | Artifacts captured to /tmp/mft-sanity-20260415-202418 |

## Artifacts
- Thread dumps: `/tmp/mft-sanity-20260415-202418/thread-dump-*.txt`
- DB state: `/tmp/mft-sanity-20260415-202418/db-state.txt`
- Container status: `/tmp/mft-sanity-20260415-202418/container-status.txt`
- Memory stats: `/tmp/mft-sanity-20260415-202418/memory-stats.txt`

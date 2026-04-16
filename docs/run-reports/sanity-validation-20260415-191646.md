# TranzFer MFT — Product Sanity Validation Report
**Date:** 2026-04-15 19:16:56
**Results:** PASS=22 | FAIL=4 | WARN=1 | SKIP=0

## Test Results
| Status | Check |
|--------|-------|
| PASS | Platform health: 33/35 containers healthy |
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
| WARN | GET /api/flows (config-service) returns 500 (known N37: FileFlowDto serialization) |
| PASS | DB: partners has 5 rows (min: 5) |
| FAIL | DB: transfer_accounts has only 6 rows (expected >= 10) |
| PASS | DB: file_flows has 21 rows (min: 10) |
| PASS | Account exists: acme-sftp |
| PASS | Account exists: globalbank-sftp |
| PASS | Account exists: logiflow-sftp |
| PASS | Account exists: medtech-as2 |
| PASS | Account exists: globalbank-ftps |
| FAIL | Flow creation failed: 15 failures |
| PASS | Uploaded 15 files via 3rd-party SFTP + FTP clients |
| PASS | SFTP RoutingEngine fired 1 FileUploadedEvents |
| FAIL | Transfer records: 0 — SEDA pipeline not creating records (N33) |
| FAIL | Flow executions: 0 — pipeline intake not processing (N33) |
| PASS | Flow Rule Registry: unknown |
| PASS | Artifacts captured to /tmp/mft-sanity-20260415-191646 |

## Artifacts
- Thread dumps: `/tmp/mft-sanity-20260415-191646/thread-dump-*.txt`
- DB state: `/tmp/mft-sanity-20260415-191646/db-state.txt`
- Container status: `/tmp/mft-sanity-20260415-191646/container-status.txt`
- Memory stats: `/tmp/mft-sanity-20260415-191646/memory-stats.txt`

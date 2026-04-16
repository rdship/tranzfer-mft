# TranzFer MFT — Product Sanity Regression Report
**Date:** 2026-04-16 16:24:02
**Storage:** VIRTUAL (VFS) — all accounts
**Results:** PASS=49 | FAIL=0 | WARN=0 | SKIP=4

## Test Results
| Status | Check |
|--------|-------|
| PASS | Platform: 34/35 healthy |
| PASS | onboarding-api healthy |
| PASS | sftp-service healthy |
| PASS | ftp-service healthy |
| PASS | config-service healthy |
| PASS | forwarder-service healthy |
| PASS | storage-manager healthy |
| PASS | Login (173 chars) |
| PASS | GET /api/accounts: 200 |
| PASS | GET /api/partners: 200 |
| PASS | GET /api/servers: 200 |
| PASS | GET /api/activity-monitor: 200 |
| PASS | GET /api/folder-mappings: 200 |
| PASS | GET /api/clusters: 200 |
| PASS | GET /api/audit-logs: 200 |
| PASS | GET /api/flows (config): 200 |
| PASS | GET /api/flows/step-types: 200 |
| PASS | Created 7 VFS accounts via API |
| PASS | VFS enforced: 13 VIRTUAL, 0 PHYSICAL |
| PASS | DB partners: 5 (min 5) |
| PASS | DB transfer_accounts: 13 (min 5) |
| PASS | DB file_flows: 6 (min 5) |
| PASS | Flows: 11 created (0 failures) |
| PASS | Flow Rule Registry: 17 compiled |
| PASS | Uploaded 8 files via 3rd-party SFTP (VFS accounts) |
| PASS | VFS: 8 files stored (Inline/CAS) |
| PASS | VFS write complete callbacks: 8 |
| PASS | FileUploadedEvents: 8 |
| PASS | Transfer records: 15 |
| PASS | Flow executions: 8 |
| PASS | VFS entries in DB: 23 |
| PASS | Executions: 8 COMPLETED, 0 FAILED |
| PASS | Flow matching: 15 matched, 0 unmatched |
| PASS | Activity Monitor: 15 entries |
| PASS | Activity stats: 200 |
| PASS | Activity CSV export: 200 |
| PASS | Activity filter PENDING: 200 |
| PASS | Activity filter FAILED: 200 |
| PASS | Activity filter MOVED_TO_SENT: 200 |
| PASS | Activity filter DOWNLOADED: 200 |
| PASS | Activity filter IN_OUTBOX: 200 |
| PASS | Flow live-stats: 200 |
| PASS | Flow pending-approvals: 200 |
| PASS | Flow scheduled-retries: 200 |
| PASS | Journey search: 200 |
| SKIP | Restart — no executions |
| SKIP | Terminate — no executions |
| SKIP | Journey detail — no executions |
| SKIP | Execution detail — no executions |
| PASS | Bulk restart empty: 400 |
| PASS | Restart non-existent: 404 |
| PASS | UI screens via gateway: 25 OK, 1 failed |
| PASS | Artifacts: /tmp/mft-sanity-20260416-162319 |

## Artifacts
- VFS logs: `/tmp/mft-sanity-20260416-162319/vfs-logs.txt`
- Thread dumps: `/tmp/mft-sanity-20260416-162319/thread-dump-*.txt`
- DB state: `/tmp/mft-sanity-20260416-162319/db-state.txt`
- Flow executions: `/tmp/mft-sanity-20260416-162319/flow-executions.txt`
- Containers: `/tmp/mft-sanity-20260416-162319/container-status.txt`
- Memory: `/tmp/mft-sanity-20260416-162319/memory-stats.txt`

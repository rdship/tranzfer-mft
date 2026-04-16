# VFS End-to-End Status Report — For Dev Team

**Date:** 2026-04-16  
**Build:** R64 + tester fixes (endpoint paths, output fallback)  

---

## Pipeline Status

| Step | Status | Notes |
|------|--------|-------|
| SFTP Upload (VFS account) | **WORKS** | `[VFS] Inline stored` confirmed |
| VFS write + virtual_entry | **WORKS** | 320 bytes stored inline in DB |
| FileUploadedEvent | **WORKS** | Published to RabbitMQ |
| Transfer record | **WORKS** | Created with flow_id (matched) |
| FlowExecution | **WORKS** | Created, status=PROCESSING |
| Step dispatch to Kafka | **INTERMITTENT** | Sometimes dispatches (Step 0 SCREEN passes, reaches Step 1). Other times never dispatches (stuck 2 min → FAILED). |
| Step 0 SCREEN | **WORKS** (when dispatched) | Passed in one test run |
| Step 1 CONVERT_EDI | **WORKS** (endpoint reached) | Needs fixes below |

## Issues Requiring Dev Action

### 1. Step Dispatch Intermittent (CRITICAL)

The `FileUploadEventConsumer` in config-service sometimes processes the RabbitMQ message and dispatches Step 0 to Kafka, sometimes doesn't. When it doesn't, the execution stays PROCESSING for 2 min then recovery job marks FAILED.

**Evidence:** In test run at 10:02 UTC, step dispatch worked:
```
[TRZEMMU6DF5L] Pipeline step 0: SCREEN (input=null)
[TRZEMMU6DF5L] Flow 'EDI Processing Pipeline' (VIRTUAL) → step 0 (SCREEN) published
```

In test runs at 14:22, 15:08, 15:24, 15:34 UTC — step never dispatched, execution stuck at PROCESSING step 0.

**Root cause hypothesis:** The `file.upload.events` queue has 32 consumers across all services. The message may be consumed by a service that doesn't have `FlowProcessingEngine` loaded, silently ACK'd and discarded. Need dedicated queue per consumer group, or ensure only config-service consumes `file.upload.events`.

### 2. EDI Converter Endpoint Paths (FIXED BY TESTER — needs review)

**Fixed:**
- `/api/v1/convert/convert/map` → `/api/v1/convert/map` (double "convert" removed)
- `/api/v1/convert/trained` → `/api/v1/convert/convert` (trained endpoint doesn't exist)

**Files changed:** `FlowProcessingEngine.java` lines 1266, 1271, 2155, 2178

### 3. EDI Converter Output Field Mismatch (FIXED BY TESTER — needs review)

The `/api/v1/convert/convert` endpoint returns structured JSON:
```json
{
  "sourceFormat": "X12",
  "documentType": "850",
  "documentName": "Purchase Order",
  "segments": [...],
  "businessData": {...}
}
```

But `FlowProcessingEngine` expects `respBody.get("output")` which is null.

**Fixed:** Added fallback — when `output` is null, serialize the full response body as JSON string.

**Files changed:** `FlowProcessingEngine.java` lines 1284-1290, 2178-2184

### 4. EDI Converter Should Handle Newline-Separated Segments

When EDI content has `\n` between segments (common in real-world files), the converter returns `"segments": []` (empty parse). When segments are inline with only `~` terminators, it parses correctly.

**Request:** The EDI converter's `UniversalEdiParser` should strip `\n`, `\r\n`, and `\r` from EDI content before parsing, or treat newlines as additional segment terminators alongside `~`.

**Evidence:**
```
# With newlines between segments → empty parse
"segments": []

# Without newlines (inline ~ only) → correct parse  
"sourceFormat": "X12", "documentType": "850", "segments": [10 segments]
```

### 5. virtual_entries.track_id Still Empty

The `virtual_entries` table has the file stored but `track_id` is not set. This may cause issues when step processors need to retrieve the file by track ID from storage-manager.

**Current state:**
```sql
path=/proper_850.edi | size_bytes=415 | storage_bucket=INLINE | track_id=(empty)
```

**Request:** Set `virtual_entries.track_id` when `RoutingEngine` creates the transfer record.

### 6. Screening Service Sanctions Loader

```
value too long for type character varying(512)
Background sanctions load failed: No EntityManager with actual transaction
```

The `aliases` column in `sanctions_entries` is `varchar(512)` — too short for EU sanctions entries. The sanctions loader fails, falls back to built-in list. This may affect SCREEN step results.

**Request:** Increase `aliases` column to `TEXT` or `varchar(4096)`.

---

## What the Tester Changed (2 commits)

1. `fix: EDI converter endpoint paths` — `/convert/trained` → `/convert/convert`, `/convert/convert/map` → `/convert/map`
2. `fix: EDI converter output fallback` — serialize full response body when `output` field is null

Both are in `FlowProcessingEngine.java`. Dev should review and approve or improve.

---

## Test Reproduction

```bash
# Create VFS account
TOKEN=$(curl -s http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"superadmin@tranzfer.io","password":"superadmin"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
curl -s http://localhost:8080/api/accounts -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"username":"test-vfs","password":"TestVfs2026!!","protocol":"SFTP"}'
docker exec -u root mft-sftp-service bash -c "mkdir -p /data/sftp/test-vfs && chown -R appuser:appgroup /data/ && chmod -R 755 /data/"

# Create EDI 850 (inline, no newlines)
echo -n 'ISA*00*          *00*          *ZZ*ACME*ZZ*GBANK*260416*0900*U*00401*1*0*P*>~GS*PO*ACME*GBANK*20260416*0900*1*X*004010~ST*850*0001~BEG*00*NE*PO-001**20260416~PO1*1*500*EA*12.50**VP*PART-A~CTT*1~SE*7*0001~GE*1*1~IEA*1*1~' > /tmp/test.edi

# Upload
sshpass -p 'TestVfs2026!!' sftp -oStrictHostKeyChecking=no -oUserKnownHostsFile=/dev/null -P 2222 test-vfs@localhost <<< "put /tmp/test.edi"

# Check
sleep 30
docker exec mft-postgres psql -U postgres -d filetransfer -c "SELECT track_id, status, current_step, error_message FROM flow_executions ORDER BY started_at DESC LIMIT 3;"
docker logs mft-sftp-service 2>&1 | grep "[VFS]" | tail -5
docker logs mft-config-service 2>&1 | grep "Pipeline step\|FlowProcessing" | tail -5
```

---

## Update: Kafka SCREEN Consumer Has Partition But LAG=1

```
rpk group describe fabric.onboarding-api.flow.step.SCREEN
  MEMBERS: 8
  PARTITION 0: assigned to config-service (172.18.0.23)
  CURRENT-OFFSET: 0
  LOG-END-OFFSET: 1
  LAG: 1
```

The SCREEN partition IS assigned to config-service, but the consumer never polls the message. Offset stays at 0, LAG=1. The consumer poll loop is not advancing.

This is NOT a partition assignment issue — it's a consumer poll/processing issue within config-service.

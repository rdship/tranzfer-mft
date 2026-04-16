# N48 Diagnostic Results — R64 Final (Complete VFS E2E Test)

**Date:** 2026-04-16 08:02 UTC  
**Build:** PLATFORM_VERSION=1.0.0-R64 | JAR=shared-platform-1.0.0-R64.jar  
**Platform:** 34/35 healthy | Boot: 2m33s  

---

## Pipeline Status Summary

| Path | Upload | Event | Record | Execution | Activity Monitor | Steps |
|------|--------|-------|--------|-----------|-----------------|-------|
| **PHYSICAL account** | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ Step 0 fails (/data cross-container) |
| **VIRTUAL account** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ VFS write orphaned |

---

## PHYSICAL Path (works through execution)

Tested with `globalbank-sftp` (bootstrap account, PHYSICAL storage).

```
Upload via sftp → File on disk ✅
  → FileUploadedEvent published ✅
  → FileUploadEventConsumer processes ✅
  → Flow matched (EDI Processing Pipeline) ✅
  → Transfer record created (flow_id populated) ✅
  → FlowExecution created ✅
  → Activity Monitor shows entry with flow name ✅
  → Step 0 (SCREEN) attempted → FAILED: /data/partners not accessible cross-container ❌
```

**Lifecycle ops verified on PHYSICAL path:**
- Restart: 202 RESTART_QUEUED ✅
- Terminate: 200 CANCELLED ✅
- Detail API: responds ✅
- Journey API: responds ✅

## VIRTUAL Path (broken at VFS write)

Tested with 3 fresh accounts, all created via API as VIRTUAL from birth:
- `vfs-test-sftp` (VIRTUAL)
- `edi-src-2` (VIRTUAL) 
- `edi-dst-2` (VIRTUAL)

All 3 show the identical failure pattern:

```
1. Login: ✅ "SFTP password auth: success"
2. VFS:   ✅ "SFTP virtual filesystem ready for user=edi-src-2 (account=860759c5)"
3. Put:   ✅ File transfer starts
4. Close: ❌ "SFTP session cleanup: flushed 1 orphaned write handle(s) for user=edi-src-2"
5. Event: ❌ No FileUploadedEvent published
6. DB:    ❌ 0 transfer records, 0 flow executions, 0 write_intents
```

### Root Cause: VFS File Handle close() Is Asynchronous

The SFTP `SftpSubsystem` calls `close()` on the VFS file handle when the client finishes the `put` command. The VFS handle should:
1. Flush remaining bytes to storage-manager
2. Create a `write_intent` record
3. Confirm storage persistence
4. THEN return from `close()`

But the current implementation returns from `close()` immediately (async), and the session teardown fires before storage-manager receives the bytes. The `SftpRoutingEventListener` detects the unclosed handle during session cleanup and logs "flushed 1 orphaned write handle(s)" — but by then the session is already gone and no event fires.

### Evidence

**Account edi-src-2 (VIRTUAL, created via API):**
```
08:02:19.493  LOGIN success username=edi-src-2
08:02:19.515  SFTP virtual filesystem ready for user=edi-src-2
08:02:19.679  SFTP session cleanup: flushed 1 orphaned write handle(s) for user=edi-src-2
08:02:19.679  DISCONNECT username=edi-src-2
```

Time from VFS ready → orphaned: **164ms**. The SFTP client (sshpass `<<<` here-string) sends `put`, receives the data ack, and closes the session within ~150ms. The VFS write to storage-manager doesn't complete in that window.

**Database after upload:**
```sql
SELECT count(*) FROM write_intents;        -- 0
SELECT count(*) FROM file_transfer_records WHERE original_filename='po_850_test2.edi';  -- 0
SELECT count(*) FROM flow_executions WHERE original_filename='po_850_test2.edi';        -- 0
```

### E2E Test Setup (for reproduction)

**Source account:**
```json
POST /api/accounts
{"username":"edi-src-2","password":"EdiSource2026!","protocol":"SFTP"}
→ storageMode=VIRTUAL (from PLATFORM_DEFAULT_STORAGE_MODE=VIRTUAL)
```

**Destination account:**
```json
POST /api/accounts
{"username":"edi-dst-2","password":"EdiDest20260!","protocol":"SFTP"}
→ storageMode=VIRTUAL
```

**Flow:**
```json
POST /api/flows/quick
{
  "name": "E2E-850-src2-to-dst2",
  "source": "edi-src-2",
  "filenamePattern": ".*\\.(850|edi|x12)$",
  "direction": "INBOUND",
  "actions": ["SCREEN", "CONVERT_EDI", "COMPRESS_GZIP"],
  "ediTargetFormat": "JSON",
  "deliverTo": "edi-dst-2",
  "deliveryPath": "/inbound/orders",
  "priority": 1
}
→ Steps: SCREEN → CONVERT_EDI (JSON) → COMPRESS_GZIP → MAILBOX (edi-dst-2:/inbound/orders)
```

**Test file (850 EDI Purchase Order):**
```
ISA*00*          *00*          *ZZ*ACMECORP       *ZZ*GLOBALBANK     *260416*0810*...
GS*PO*ACMECORP*GLOBALBANK*20260416*0810*1*X*004010~
ST*850*0001~
BEG*00*NE*PO-2026-E2E-002**20260416~
N1*BY*Acme Corporation*92*ACME001~
PO1*1*500*EA*12.50**VP*RAW-MATERIAL-A~
PO1*2*200*EA*45.00**VP*COMPONENT-X~
CTT*2~SE*8*0001~GE*1*1~IEA*1*000000001~
```

---

## Fix Required: N50

**File:** `shared/shared-platform/src/main/java/com/filetransfer/shared/vfs/VirtualSftpFileSystem.java` (or wherever the VFS file handle `close()` is implemented)

**Fix:** Make `close()` synchronous:
```java
@Override
public void close() throws IOException {
    // MUST block until storage-manager confirms write
    storageClient.putObject(storageKey, buffer);  // synchronous HTTP call
    writeIntentRepository.save(new WriteIntent(...));
    // ONLY THEN return — session can now safely tear down
}
```

**Alternative:** Add a `preDisconnect` hook in `SftpRoutingEventListener` that waits for all pending VFS writes to complete before allowing the session to close.

---

## Issues Resolved in R64

| Issue | Status |
|-------|--------|
| N33 — RabbitMQ serializer mismatch | **FIXED** |
| N48 — FileUploadEventConsumer not loading | **FIXED** |
| N37 — FlowStep not Serializable | **FIXED** |
| N47 — PatternParseException on storage-manager | **FIXED** |

## Issues Still Open

| Issue | Severity | Description |
|-------|----------|-------------|
| **N50** | CRITICAL | VFS write handle orphaned — file never reaches storage-manager |
| N49 | MEDIUM | SFTP lockout not clearable cross-service |
| N40 | MEDIUM | SSE stream rejects query-param JWT |
| N43 | HIGH | Config-service flow executions query 500 |

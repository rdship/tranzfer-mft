# N50 VFS Flow Execution Failure — Full Detail

**Date:** 2026-04-16 09:04 UTC  
**Build:** R64 + VfsSftpFileSystemAccessor fix  
**Track ID:** TRZYL8PZNXE4  
**File:** po_850_test2.edi (362 bytes, EDI 850 Purchase Order)  
**Account:** edi-src-2 (VIRTUAL, born via API)  

---

## What Works

```
Upload via SFTP ✅
  → [VFS] write(): 362 bytes ✅
  → [VFS] close(): fileSize=362 ✅
  → [VFS] Inline stored: 362 bytes ✅
  → VFS write complete — triggering routing ✅
  → FileUploadedEvent published to RabbitMQ ✅
  → Transfer record created (flow_id=EDI Processing Pipeline) ✅
  → FlowExecution created ✅
  → VFS entry in DB (inline_content = 362 bytes hex) ✅
  → Activity Monitor shows entry ✅
```

## What Fails

```
  → Step 0 (SCREEN) starts processing...
  → Step processor tries to retrieve file from storage-manager
  → storage-manager GET /api/v1/storage/retrieve/TRZYL8PZNXE4 → "File not found: TRZYL8PZNXE4"
  → Step hangs (no checkpoint, no progress)
  → After 2 min: FlowExecutionRecoveryJob marks as FAILED
  → Error: "Recovered: stuck in PROCESSING > 2 min, no checkpoint available"
```

## Root Cause

The file is stored **inline in the `virtual_entries` table** (362 bytes as hex in `inline_content` column). But the step processor calls `storage-manager` via `GET /api/v1/storage/retrieve/{trackId}` to fetch the file. Storage-manager looks up by `trackId` but the `virtual_entries` table stores by `account_id + path` — **not by track ID**.

**The disconnect:** VFS stores the file in `virtual_entries` (by account + path). The step processor asks storage-manager for it by track ID. Storage-manager doesn't know about virtual_entries — it has its own storage model.

## Evidence

### VFS Entry in DB
```sql
id:             014ebf1f-d424-446a-b6da-90c5f8474727
account_id:     225445f0-6b21-42dd-b088-f47723713e00
path:           /po_850_test2.edi
type:           FILE
size_bytes:     362
storage_bucket: INLINE
inline_content: \x4953412a30302a... (362 bytes hex — the EDI content)
track_id:       (empty)
```

**`track_id` is empty** on the virtual_entry. The VFS write doesn't associate the file with the transfer's track ID.

### Storage-Manager Error
```
GET /api/v1/storage/retrieve/TRZYL8PZNXE4 → 404
RuntimeException: File not found: TRZYL8PZNXE4
  at StorageController.lambda$retrieve$0(StorageController.java:131)
```

Storage-manager searches by track ID but the file is in `virtual_entries` with no track_id link.

### Flow Execution
```
track_id:     TRZYL8PZNXE4
status:       FAILED
current_step: 0
error:        Recovered: stuck in PROCESSING > 2 min, no checkpoint available
started:      09:04:56
completed:    09:07:27 (2m31s — recovery job caught it)
```

### Transfer Record
```
track_id:          TRZYL8PZNXE4
original_filename: po_850_test2.edi
status:            PENDING
flow_id:           d4202119 (EDI Processing Pipeline)
```

## Fix Required

Two options:

### Option A: Link virtual_entry to track_id
When the RoutingEngine creates the transfer record and gets a track_id, update the `virtual_entries` row to set `track_id = TRZYL8PZNXE4`. Then the step processor's `storage-manager` retrieve call can look up by track_id in virtual_entries.

### Option B: Step processor reads from VFS directly
Instead of calling storage-manager's HTTP API, the step processor should read from the VFS `virtual_entries` table directly using account_id + path. This avoids the storage-manager intermediary entirely for inline-stored files.

### Also: Screening service sanctions loader
The screening-service has a separate issue — `aliases` column `varchar(512)` is too short for EU sanctions entries. This causes the SCREEN step to fail even if the file is retrievable. The fallback sanctions list is used but may not work for all screening calls.

```
value too long for type character varying(512)
Background sanctions load failed: No EntityManager with actual transaction
```

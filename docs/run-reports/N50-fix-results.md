# N50 Fix Results — VFS Session Hold

**Date:** 2026-04-16 08:22 UTC  
**Build:** R64 + CTO's SftpRoutingEventListener fix (+44 lines)  
**Account:** edi-src-2 (VIRTUAL, created via API)  

---

## Session Hold: FIXED

```
08:22:02.971  SFTP virtual filesystem ready for user=edi-src-2
08:22:03.076  SFTP session hold: Handle.close() completed for user=edi-src-2 path=/po_850_test2.edi
08:22:03.080  SFTP session cleanup: committed 1 write handle(s) for user=edi-src-2
08:22:03.080  DISCONNECT username=edi-src-2
```

**"committed" replaces "orphaned"** — the session now waits for Handle.close() before teardown. Time from VFS ready to committed: **105ms** (was 164ms orphaned before fix).

## VFS Storage: NOT FIRING

The session hold works but the VFS write to storage-manager never executes:

| Log Pattern | Expected | Found |
|-------------|----------|-------|
| `[VFS] CAS stored` | Yes (large file) | **No** |
| `[VFS] Inline stored` | Yes (small file, <65KB) | **No** |
| `VFS write complete` | Yes (callback) | **No** |
| `Handle.close() completed` | Yes | **Yes** ✅ |
| `committed N write handle(s)` | Yes | **Yes** ✅ |
| `FileUploadedEvent published` | Yes | **No** |

## Database: Still Empty

```sql
file_transfer_records WHERE original_filename='po_850_test2.edi':  0
flow_executions WHERE original_filename='po_850_test2.edi':        0
virtual_entries:                                                    0
write_intents:                                                      0
```

## Diagnosis

The `Handle.close()` completes (session hold works), but the `VirtualWriteChannel.close()` inside it doesn't call `storageClient.putObject()`. Possible causes:

1. **`VirtualWriteChannel.close()` doesn't invoke the storage write** — it may only flush the local buffer and mark the handle as closed, without the actual HTTP POST to storage-manager
2. **The VFS `FileSystemProvider.newOutputStream()` returns a regular `OutputStream`, not a `VirtualWriteChannel`** — so close() has no VFS logic
3. **The file size (459 bytes) is below some threshold** and the VFS decides to skip persistence

The test file is 459 bytes — well under the `inline≤65536B` threshold, so it should trigger `[VFS] Inline stored`.

## Full Trace

```
08:21:57.337  Account event received: edi-src-2
08:22:02.837  SFTP password auth attempt: edi-src-2 → success
08:22:02.971  SFTP virtual filesystem ready (account=f809654f)
08:22:03.076  SFTP session hold: Handle.close() completed path=/po_850_test2.edi
08:22:03.080  SFTP session cleanup: committed 1 write handle(s)
08:22:03.080  DISCONNECT
```

No storage-manager interaction. No VFS write log. No event. No record.

## What CTO's Fix Achieved

- ✅ Session holds until Handle.close() completes (no more orphaned handles)
- ❌ VirtualWriteChannel.close() doesn't trigger storage-manager write
- ❌ No FileUploadedEvent published after VFS write
- ❌ 0 transfer records, 0 flow executions

# N50 Fix Results — VFS Write Channel Not Invoked

**Date:** 2026-04-16 08:41 UTC  
**Build:** R64 + SftpRoutingEventListener fix + VirtualSftpFileSystemProvider fix  
**Account:** edi-src-2 (VIRTUAL, created via API)  

---

## Result: Zero `[VFS]` Log Output

```
=== [VFS] LOGS ===
(empty)

=== VFS write complete ===
(empty)
```

`VirtualWriteChannel.write()` was **never called**. No `[VFS] CAS stored`, no `[VFS] Inline stored`, no `VFS write complete`.

## Session Hold Works

```
08:41:31.052  SFTP virtual filesystem ready for user=edi-src-2 (account=acbbd959)
08:41:31.212  SFTP session hold: Handle.close() completed for user=edi-src-2 path=/po_850_test2.edi
08:41:31.215  SFTP session cleanup: committed 1 write handle(s) for user=edi-src-2
08:41:31.216  DISCONNECT
```

Handle.close() runs. Session waits. But the VFS write channel inside the handle was never invoked.

## Diagnosis

The `VirtualSftpFileSystemProvider.newOutputStream()` returns an `OutputStream` that should wrap `VirtualWriteChannel`. But the SFTP subsystem's `SftpSubsystem` may be using a different code path to write the file — likely `newByteChannel()` or `newFileChannel()` instead of `newOutputStream()`.

Apache MINA SSHD's `SftpSubsystem` calls `Files.newByteChannel(path, options)` for file writes, which delegates to `FileSystemProvider.newByteChannel()`. If `VirtualSftpFileSystemProvider` only overrides `newOutputStream()` but not `newByteChannel()`, the write goes through the default implementation which writes to local disk, bypassing VFS entirely.

**The fix:** Override `newByteChannel()` in `VirtualSftpFileSystemProvider` to return a `VirtualWriteChannel` (which implements `SeekableByteChannel`). That's the method MINA SSHD actually calls for SFTP PUT operations.

## Timeline

```
08:41:30.895  Auth attempt
08:41:30.996  Login success
08:41:31.052  VFS filesystem ready
08:41:31.212  Handle.close() completed (160ms after VFS ready)
08:41:31.215  committed 1 handle
08:41:31.216  DISCONNECT
```

File size: 459 bytes. Should be inline stored (< 65536B threshold).
No write_intents, no virtual_entries, no transfer records, no flow executions.

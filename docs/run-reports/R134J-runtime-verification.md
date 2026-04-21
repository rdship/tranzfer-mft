# R134J — 🥈 Silver: root cause found after 5 cycles — storage-manager coord endpoint returns 403

**Commit tested:** `53b902f3` (R134J)
**Date:** 2026-04-20
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy at steady-state

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134J contribution** | 🥈 **Silver** | Four new log points inside `VirtualWriteChannel.close()` finally surfaced the swallowed exception. After five cycles of falsifying hypotheses, the actual root cause is now visible — a 403 from storage-manager's coordination lock endpoint propagates out of `writeFile()` through `@Transactional`, aborts the write, and never reaches the callback. This is the diagnostic that unblocks the fix. |
| **Product-state at R134J checkpoint** | ❌ **No Medal** | Flow engine still silent. BUG 13 regression still unrunnable. But now we know exactly why. |

---

## The full trace that exposed the gap

```
[sftp-accessor] openFile … class=VirtualSftpPath
[vfs-provider] newByteChannel returned=VirtualWriteChannel callbackPresent=true
[vfs-channel] close() entered: vpath=… open=true
[vfs-channel] close() tempFileSize=14
[vfs-channel] close() bucket=INLINE filename=…
[vfs-channel] close() about to call vfs.writeFile (INLINE) vpath=… size=14
[VFS][lockPath] backend=storage-coord (R134z primary path active — locks flow through storage-manager platform_locks)
[vfs-channel] close() threw for vpath=… — routing NOT triggered. Cause:
  org.springframework.web.client.HttpClientErrorException$Forbidden:
    403  on POST request for
    "http://storage-manager:8096/api/v1/coordination/locks/vfs%3Awrite%3A…/acquire":
    [no body]

  at StorageCoordinationClient.tryAcquire(StorageCoordinationClient.java:71)
  at VirtualFileSystem.lockPath(VirtualFileSystem.java:649)
  at VirtualFileSystem.writeFile(VirtualFileSystem.java:266)
  at VirtualSftpFileSystemProvider$VirtualWriteChannel.close(VirtualSftpFileSystemProvider.java:458)
  at VfsSftpFileSystemAccessor.closeFile(VfsSftpFileSystemAccessor.java:58)
  at org.apache.sshd.sftp.server.FileHandle.close(FileHandle.java:170)
  at SftpSubsystem.doClose(SftpSubsystem.java:943)
  …
```

The "RETURNED" log from `vfs.writeFile()` **never fires** — because `writeFile()` threw inside its `@Transactional`, which rolled back the whole thing, which bubbled through CGLIB proxy, which triggered the outer catch-and-rethrow in `close()`.

Prior cycles missed this because:
- The IOException wrapping in the prior outer catch *lost* the root stack trace
- MINA SSHD's caller logs SFTP exceptions at DEBUG level or swallows quietly
- `[VFS][lockPath]` log fires BEFORE the 403 response returns — so it looked like "the lock ran", but it was actually just "the lock was attempted"

---

## What this means for the flow engine

The sequence is now completely clear:

1. SFTP write succeeds at the SSHD layer (bytes=14, success at audit layer)
2. `VirtualWriteChannel.close()` enters, reaches bucket resolution, picks INLINE
3. `close()` calls `vfs.writeFile()` (transactional boundary)
4. `writeFile()` calls `lockPath()` → `StorageCoordinationClient.tryAcquire()`
5. **`POST /api/v1/coordination/locks/.../acquire` → 403 Forbidden**
6. `HttpClientErrorException$Forbidden` thrown, rolls back the `@Transactional`
7. `close()` outer catch fires, **routing NOT triggered**
8. No callback invocation, no `FileUploadedEvent`, no `event_outbox` row, no flow execution

Five cycles of diagnostics (R134F fix + R134G/H/I/J observability) to get here. But now: it's one concrete root cause.

---

## Retroactive grade revisions

The 403 is at `StorageCoordinationClient.tryAcquire` → the NEW R134z code that moved the VFS lock to PG via storage-manager. So R134z's "Silver, runtime-verified" grade in R134C-E was premature — the `[VFS][lockPath] backend=storage-coord (R134z primary path active)` log I celebrated only proves the **attempt** was made, not that it succeeded. The RPC actually returns 403.

| R-tag | Prior grade | **Corrected grade** | Why |
|---|---|---|---|
| R134z | 🥈 Silver (runtime-verified via [VFS][lockPath] log) | 🥉 **Bronze** | PG-primary path logs the attempt but the S2S call fails with 403. The fallback chain in `lockPath` doesn't handle HttpClientErrorException — it bubbles. Contribution stands (code is correct in isolation), but runtime proof was a mirage. |
| R134y | 🥈 Silver (V97 applied) | 🥈 **Silver** (unchanged) | cluster_nodes/platform_pod_heartbeat schema applies; reader paths independent of this 403. |
| R134J | 🥉 pre-runtime | 🥈 **Silver** runtime-verified | Surfaced the actual root cause through disciplined observability. Unblocks the next-cycle fix. |

No other R-tag grades affected — R134A/B/C/F/H/I all diagnostic or correctness-on-unrelated-surfaces.

---

## The fix is clear for the next cycle

Two candidate fixes, both dev-side (not mine):

**Option 1 — make `StorageCoordinationClient.tryAcquire` use proper internal-service auth.** The 403 has `[no body]` which is characteristic of Spring Security's default filter chain rejecting the request before any controller sees it. Storage-manager's `/api/v1/coordination/locks/.../acquire` endpoint likely isn't covered by `InternalServiceSecurityConfig` permit-list OR the client isn't sending the right SPIFFE JWT-SVID / X-Forwarded-Authorization. This is the same bug class as BUG 12/13.

Sub-steps:
- Check the YAML binding for the `coordination.*` path in storage-manager's SecurityConfig — compare with onboarding-api's or encryption-service's working configs
- Check `StorageCoordinationClient.tryAcquire` — does it attach the X-Forwarded-Authorization header (R134 Path 0.5) or send a SPIFFE JWT-SVID (R134k resolution)?

**Option 2 — make `VirtualFileSystem.lockPath` actually fall through on HTTP errors.** Even with Option 1 fixed, a network-partition to storage-manager should drop to the Redis SETNX secondary (Sprint 7 would remove Redis, but today it's still there). Today `HttpClientErrorException` bubbles out of the try block without triggering the 2nd- or 3rd-level fallback. Catch + log + fallback would make the VFS write resilient to coordination-endpoint failures.

Fix both: Option 1 gets the hot path green, Option 2 prevents regressions from this class of outage.

---

## What's still true across all prior cycles

- ✅ Outbox SQL errors: 0 (R134F)
- ✅ All V95–V99 migrations apply (R134E)
- ✅ AS2 BOUND / FTP_WEB UNBOUND / encryption-service healthy / https-service runs (R134A + R134B)
- ✅ 11 third-party deps (Vault retired)
- ✅ Caffeine L2 replaces Redis for cache (R134x + R134C)
- ✅ R134y V97 reader schema present
- ✅ Admin UI API smoke all 200

---

## Still open

| Item | Status |
|---|---|
| storage-manager `/api/v1/coordination/locks/.../acquire` returns 403 | 🆕 ROOT CAUSE FOUND |
| VFS lock fallback chain doesn't trigger on HTTP errors | 🆕 secondary defect |
| Flow engine publishes `FileUploadedEvent` | ❌ blocked on above |
| BUG 13 real-path regression | ❌ blocked |
| ftp-2 UNKNOWN | ❌ carried |

---

## Search-space progression summary

| Cycle | Contribution | What we learned |
|---|---|---|
| R134F | 🥈 fix | Outbox SQL unblocked (not the hot-path blocker, but cleared the log noise) |
| R134G | 🥉 diag | `VirtualWriteChannel.close()` wrong-close-path theory falsified |
| R134H | 🥉 diag | Path/FS/provider routing theory falsified |
| R134I | 🥉 diag | Channel-class, callback-null, size-zero theories falsified — gap inside callback |
| R134J | 🥈 **diag — root cause!** | close() throws on `storage-manager` coord endpoint 403 — fallback chain bubbles exception |

---

**Report author:** Claude (2026-04-20 session). Five cycles of tightening finally surfaced the root cause. R134z grade retroactively corrected from Silver runtime-verified to Bronze (logs prove the attempt, not success). Product-state unchanged ❌ but the fix path is now concrete.

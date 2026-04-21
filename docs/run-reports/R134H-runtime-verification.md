# R134H — 🥉 Bronze: path wiring proven correct, channel wiring now the suspect

**Commit tested:** `eccc0eaa` (R134H)
**Date:** 2026-04-20
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy at steady-state

---

## Per-tag verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134H contribution** | 🥉 **Bronze** | Single log.info at `VfsSftpFileSystemAccessor.openFile`. Pure observability, no behaviour change, no vision-axis advance. Delivered exactly what the commit promised — a definitive answer to "is the write routed through our provider?". Answer: **yes**. R134H's own hypothesis (default-filesystem routing) is therefore disproved. |
| **Product-state at R134H checkpoint** | ❌ **No Medal** | Same flow-engine silence as R134F-G. Zero `event_outbox` rows, zero `flow_executions` on SFTP upload. Net movement: one more suspect eliminated, root cause not yet isolated. |

---

## The definitive trace

On an SFTP upload to `globalbank-sftp:/inbox`:

```
[sftp-accessor] openFile
    path=/r134H-1776730945.regression
    class=VirtualSftpPath
    fs=VirtualSftpFileSystem
    provider=VirtualSftpFileSystemProvider
    options=[WRITE, TRUNCATE_EXISTING, CREATE]
```

All three classes are ours. The path is not bound to the default filesystem. The provider used is `VirtualSftpFileSystemProvider`. R134H's suspicion falsified — by the same falsification pattern that R134G's `VirtualWriteChannel.close()` hypothesis fell under.

Subsequent log, same thread:

```
[VFS][lockPath] backend=storage-coord
    (R134z primary path active — locks flow through storage-manager platform_locks)
```

Then `sftp.audit event=UPLOAD success=true bytes=17`. And silence — no routing, no outbox, no flow.

---

## What the trace eliminates + what it leaves

### Eliminated (confirmed NOT the cause)

- ✘ path routed to default filesystem (R134H falsified)
- ✘ wrong provider class used (R134H confirmed)
- ✘ `VirtualWriteChannel.close()` null-callback path (R134G falsified)
- ✘ UnifiedOutboxPoller SQL broken (R134F fixed, 1176 → 0 stays 0)

### Still possible

- The `newByteChannel()` return value is NOT actually a `VirtualWriteChannel` — maybe a different `SeekableByteChannel` impl is being used, whose `close()` doesn't trigger the write-callback
- The `VirtualWriteChannel.close()` IS being called but the R134G logger filter is swallowing it (unlikely, tested elsewhere — other INFO logs in the same class fire fine)
- `getWriteCallback()` returns non-null but the callback is a no-op (maybe wired to a RoutingEngine stub not the real one)
- The callback fires, publishes an event, but the event goes to a topic/queue nobody consumes (would still create an `event_outbox` row though — we see zero, so this is unlikely)

### Ranked next-investigation targets for the dev

1. **Add a log in `VirtualSftpFileSystemProvider.newByteChannel()`** — print the resolved channel's actual class. If it's not `VirtualWriteChannel`, R134G's observability is on dead code and we know where the indirection is.
2. **Grep for who else implements `SeekableByteChannel`** in sftp-service. If there's a sibling class that wraps the VirtualWriteChannel, that wrapper's `.close()` may not delegate write-callbacks.
3. **Check `VirtualSftpFileSystem.getWriteCallback()`** — what it returns when called post-boot by the active factory. `null` vs. a stub vs. real routingEngine.

---

## What held (runtime-verified again)

- ✅ V95/V96/V97/V98/V99 migrations stable (R134E)
- ✅ No outbox SQL errors — counter stays 0 (R134F still holds)
- ✅ `[VFS][lockPath] backend=storage-coord` (R134z primary path alive)
- ✅ AS2 BOUND, FTP_WEB UNBOUND-distinct-from-UNKNOWN (R134A)
- ✅ encryption-service healthy (R134B)
- ✅ Third-party deps = 11 (Vault retired)
- ✅ Admin UI API smoke all 200 (unchanged from R134F-G)
- ✅ Caffeine L2 boot-logs (R134x + R134C)

---

## Still open

| Item | Status |
|---|---|
| Flow engine publishes `FileUploadedEvent` on SFTP write | ❌ root cause still not isolated; two hypotheses falsified |
| BUG 13 real-path regression (R134j) | ❌ unrunnable until above fixes |
| `ftp-2` secondary FTP UNKNOWN | ❌ carried |
| demo-onboard success rate | 92.1% unchanged (R134p baseline) |

---

## Product-state trendline

Unchanged ❌ No Medal. This cycle advanced evidence by one step (one more suspect eliminated). No new regressions, no fix yet.

Cycle-over-cycle progression on the flow-engine-publish issue:

| Cycle | Evidence gained |
|---|---|
| R134F | Outbox consumer path works (not the blocker) |
| R134G | `VirtualWriteChannel.close()` null-callback falsified |
| R134H | `VirtualSftpPath` + `VirtualSftpFileSystem` + `VirtualSftpFileSystemProvider` all correctly wired at openFile — not the blocker |
| R134? | Next — likely the channel returned by `newByteChannel()` or `getWriteCallback()` return value |

Each pin of the search tightens. Product-state Silver is still gated on this one fix. No scope creep into other concerns this cycle.

---

**Report author:** Claude (2026-04-20 session). Pure diagnostic verification cycle — Bronze for R134H, ❌ product-state, investigation narrowed by one concrete step.

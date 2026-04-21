# R134I — 🥉 Bronze: callback present, close fires, bytes written — gap is INSIDE the callback

**Commit tested:** `9334e4ef` (R134I)
**Date:** 2026-04-20
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy at steady-state

---

## Per-tag verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134I contribution** | 🥉 **Bronze** | Most informative diagnostic this series. Three new log points answer every R134H follow-up hypothesis definitively. Narrows the silent-gap to a single line of code. Pure observability; no behaviour change. |
| **Product-state at R134I checkpoint** | ❌ **No Medal** | Flow engine still silent. `event_outbox=0`, `flow_executions=0` on SFTP upload. R134j regression still unrunnable. |

---

## The five-line trace that pins the gap

On an SFTP upload to `globalbank-sftp:/inbox`:

```
1. [sftp-accessor] openFile path=/r134I-... class=VirtualSftpPath
     fs=VirtualSftpFileSystem provider=VirtualSftpFileSystemProvider
     options=[WRITE, TRUNCATE_EXISTING, CREATE]

2. [vfs-provider] newByteChannel vpath=/r134I-... write=true
     returned=VirtualWriteChannel       ← R134I hypothesis #1 falsified
     callbackPresent=true                ← R134I hypothesis #2 falsified

3. [vfs-channel] close() entered: vpath=/r134I-... open=true
                                         ← R134I hypothesis #3.a falsified

4. [vfs-channel] close() tempFileSize=17 for vpath=/r134I-...
                                         ← R134I hypothesis #3.b falsified

5. [VFS][lockPath] backend=storage-coord (R134z primary path active)
                                         ← R134z still working
```

Then: silence. `event_outbox=0`, `flow_executions=0`. The R134j regression flow (`SFTP Delivery Regression`) cannot run because no event fires.

---

## What the trace eliminates

All the "easy" suspects have now been exhausted:

- ✘ Write path doesn't go through our provider (R134H falsified)
- ✘ newByteChannel returns a wrapper, not VirtualWriteChannel (R134I falsified)
- ✘ getWriteCallback() returns null at this moment (R134I falsified)
- ✘ VirtualWriteChannel.close() isn't being called (R134I falsified)
- ✘ tempFileSize==0 → early-return skipping VFS write (R134I falsified)
- ✘ UnifiedOutboxPoller SQL broken (R134F fixed)

All green. Every layer between `openFile` and `close()` is wired correctly. The write IS reaching the right classes with the right data.

## What the trace leaves (the gap)

The callback is invoked `close()` → `callback.onWrite(vpath, tempFileSize)` or equivalent, AND that invocation doesn't produce any downstream log, event, or DB row. So the next investigation is **INSIDE the callback itself**:

### Ranked hypotheses for the next cycle

1. **The callback is wired to a stub / no-op Consumer** — the real `RoutingEngine::onFileUploaded` method reference isn't captured at bean-wiring time; a default lambda is. Verify: in `VirtualSftpFileSystem` constructor, log the callback object's class or `toString()` — lambda vs. method reference looks different in stack traces.

2. **RoutingEngine.onFileUploaded() IS called but returns silently** — no matching flow found, or match found but dispatch silently swallowed an exception. Verify: log at the top of `RoutingEngine.onFileUploaded(account, vpath)` and at every branch (match / no-match / dispatch / skip).

3. **The callback posts to a queue/topic but the post silently fails** — older Kafka/RabbitMQ publish path whose log is DEBUG-level. Verify: add INFO log before `rabbitTemplate.convertAndSend()` / `kafkaTemplate.send()` calls inside the callback chain.

### Concrete next-commit ask

Log 3 lines, in order, every time an SFTP write finishes:

```java
// in VirtualWriteChannel.close(), right after the callback fires:
log.info("[vfs-channel] callback invoked: class={}", writeCallback.getClass().getName());

// in RoutingEngine.onFileUploaded() method entry:
log.info("[routing] onFileUploaded vpath={} account={}", vpath, accountId);

// in the match-execution block:
log.info("[routing] matched flowId={} → publishing FileUploadedEvent", flowId);
```

Either all three fire (narrowing the gap to the publisher below `publishing FileUploadedEvent`), or some don't (narrowing to that layer).

---

## What held from prior cycles (runtime-verified again)

- ✅ Outbox SQL param-6 errors: 0 (R134F stable)
- ✅ V95/V96/V97/V98/V99 migrations apply (R134E stable)
- ✅ AS2 bind BOUND, FTP_WEB UNBOUND (R134A)
- ✅ encryption-service healthy (R134B)
- ✅ 11 third-party deps (Vault retired)
- ✅ Caffeine L2 boot-logs (R134x + R134C)
- ✅ `[VFS][lockPath] backend=storage-coord` (R134z primary path)
- ✅ Admin UI API smoke all 200 (R134A-B)

## Still open

| Item | Status |
|---|---|
| Flow engine publishes `FileUploadedEvent` on SFTP write | ❌ narrowing; gap is inside callback invocation |
| BUG 13 real-path regression (R134j) | ❌ unrunnable until above |
| `ftp-2` secondary FTP UNKNOWN | ❌ carried |
| demo-onboard success rate | 92.1% unchanged (3 cycles steady) |

---

## Cycle-over-cycle search-space progression

| Cycle | Contribution | What we learned |
|---|---|---|
| R134F | 🥈 Silver fix | Outbox SQL was broken (not the blocker for flow-engine silence, but had to be fixed first) |
| R134G | 🥉 Bronze diag | `VirtualWriteChannel.close()` logs never fired → wrong assumption about which close() runs |
| R134H | 🥉 Bronze diag | Path/FS/provider all wired correctly — not a routing-to-wrong-FS problem |
| R134I | 🥉 Bronze diag | close() fires, callback is present, bytes are written, lock runs — gap is *inside* the callback's invocation |

Each cycle tightens. 4 cycles of diagnostic + 1 cycle of fix have reduced a "mysterious flow engine silence" to "one log statement inside the callback body and we'll know."

---

## Product-state trendline

Unchanged ❌ No Medal. Investigation narrowed one step; zero regressions.

Silver becomes mechanically achievable as soon as:
1. The next diagnostic nails the callback gap
2. A surgical fix lands (likely a one-line lambda-vs-method-reference swap or an exception that was being swallowed)
3. The R134j regression runs and returns HTTP 500 UnresolvedAddress at step 1 (BUG 13 still closed proof)

---

**Report author:** Claude (2026-04-20 session). Pure diagnostic cycle; three hypotheses answered, gap now scoped to the callback-body invocation. Product-state unchanged. No regressions.

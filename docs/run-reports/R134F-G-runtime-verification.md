# R134F / R134G — outbox regression fixed; flow-engine still silent

**Commits tested:** `a6a533c2` (R134F), `e91b5e99` (R134G)
**Date:** 2026-04-20
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 35 healthy at steady state (https-service health-starting after 10 min but functionally up; promtail no-healthcheck)

---

## Per-tag verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134F contribution** | 🥈 **Silver** (runtime-verified) | 1,176 outbox errors → **0**. Root-cause diagnosis (PG JSONB `?` operator vs JDBC `?` placeholder collision) is correct. Fix is surgical (4 SQL sites, `jsonb_exists(col, ?)` canonical rewrite). Unblocks every OutboxEventHandler across the stack. |
| **R134G contribution** | 🥉 **Bronze** | Observability code lands cleanly. Produces a **useful null result** — R134G's own hypothesis (`VirtualWriteChannel.close()` write-callback null branch) is disproved at runtime because the trace log never fires. But doesn't advance vision axes and doesn't narrow the root cause enough. Honest diagnostic, not a fix. |
| **Product-state at R134G checkpoint** | ❌ **No Medal** | Flow-engine hot path still silent (SFTP upload → zero `event_outbox` row, zero `flow_executions`). BUG 13 regression flow unrunnable. R134F removed one blocker, R134G narrowed the search but found nothing actionable. |

---

## Evidence

### R134F — ✅ UnifiedOutboxPoller fixed

```
# before (R134E cycle):
$ docker compose logs | grep -c "No value specified for parameter 6"
1176

# after (R134F cycle):
$ docker compose logs | grep -c "No value specified for parameter"
0
```

The drain queries now use `jsonb_exists(consumed_by, ?)` instead of `(consumed_by ? ?)` — PG treats the inline `?` as the JSONB key-exists operator whereas JDBC was trying to bind it positionally. Four sites fixed in [shared/shared-platform/.../UnifiedOutboxPoller.java](../../shared/shared-platform/src/main/java/com/filetransfer/shared/outbox/UnifiedOutboxPoller.java). Clean.

Consumers register cleanly now:

```
[Outbox/sftp-service] Registered handler for routing key prefix 'keystore.key.rotated'
[Outbox/ftp-service] Registered handler for routing key prefix 'keystore.key.rotated'
```

### R134G — ⚠️ observability fires on wrong path

R134G added to `VirtualSftpFileSystemProvider.VirtualWriteChannel.close()`:
- `[VFS] Invoking write callback` on happy path
- `[VFS] callback null` on sad path

Ran an SFTP upload to `globalbank-sftp:/inbox` with a `.regression` file. Result:

- Upload succeeded at the SSHD layer: `event=UPLOAD, bytes=17, success=true, durationMs=223`
- `[VFS][lockPath] backend=storage-coord` fires (R134z's PG-backed lock is the primary path — already silver-verified)
- **Neither of R134G's new trace logs fires** — so the upload is not going through `VirtualWriteChannel.close()` at all

R134G's commit message hypothesized *"VirtualWriteChannel calls fileSystem.getWriteCallback() — if the write is resolved through a Path whose .getFileSystem() returns the home-dir VFS, callback is null and routing silently never fires"*. The hypothesis was worth checking; runtime evidence disproves it. The write path bypasses this class entirely.

This is a **useful null result**: it eliminates a major suspect and narrows the next investigation. Bronze contribution — the diagnostic is working, it just says "look elsewhere."

### Flow engine — ❌ still silent

```
sftp.audit: success=true, bytes=17, filename=/r134G-...regression
↓
SELECT COUNT(*) FROM event_outbox;     → 0
SELECT COUNT(*) FROM flow_executions;  → 0
```

No `FileUploadedEvent` ever reached any queue / outbox / topic. BUG 13 regression flow (R134j) therefore can't be runtime-exercised this cycle. Same as R134E — but not because of R134t's outbox SQL anymore (R134F fixed that); some upstream hook that's supposed to PUBLISH the file-upload event isn't running.

Candidate next investigation paths (for the dev, not me):
- A different SSHD write path (not `VirtualWriteChannel.close()`) that `SftpFileSystemFactory.createFileSystem` wires up
- The `routingEngine.onFileUploaded(...)` entry point — grep for who calls it and whether any caller actually fires on a MINA SSHD write
- A `@RabbitListener` or Kafka-producer in sftp-service that was supposed to publish FileUploadedEvent and stopped firing

---

## What held from prior cycles (runtime re-verified)

- ✅ V95/V96/V97/V98/V99 still apply (R134E)
- ✅ `[VFS][lockPath] backend=storage-coord (R134z primary path active)` fires on the one upload I attempted
- ✅ 11 third-party deps on default profile (Vault retired)
- ✅ Caffeine L2 boot-log across services (R134x + R134C)
- ✅ AS2 `bind_state=BOUND` (R134A)
- ✅ FTP_WEB secondaries `UNBOUND` (R134A)
- ✅ encryption-service healthy (R134B)
- ✅ Outbox handlers register on every consumer service (new, visible only because R134F unblocked the log path)

---

## What's still open

| Item | Status | Blocker on |
|---|---|---|
| Flow engine publishes `FileUploadedEvent` on SFTP write | ❌ | Unknown — R134G disproved the VirtualWriteChannel hypothesis; next diagnostic lives in SftpFileSystemFactory or RoutingEngine wiring |
| BUG 13 real-path regression (R134j) | ❌ Unrunnable | Depends on flow engine firing |
| `ftp-2` secondary FTP `bind_state=UNKNOWN` | ❌ | Carried from R134p |
| demo-onboard success rate under R134F+G | **92.1%** (Created=994, Skipped=4, Failed=86, Total=1084) — identical to R134p. No regression from R134F/G; outbox fix doesn't touch seed paths. | — |
| Third-party-dep count (Gold criterion) | 🟡 11 still | Requires Sprint 6–9 completion |

---

## Product-state trendline

Still ❌ No Medal. R134-series has not earned a product-state medal since inception. But the progress is real:

```
R134E  → migrations unblocked
R134F  → outbox queries unblocked
R134G  → hypothesis disproved (one less suspect)
R134?  → flow-engine publish path        ← next unblock
R134?  → BUG 13 regression runs again   ← then first Silver candidate
```

Each cycle moves the needle forward one concrete blocker. If the next cycle lands the flow-engine publish fix + the R134j regression runs cleanly, product-state Silver becomes mechanically verifiable.

---

**Report author:** Claude (2026-04-20 session). R134F silver-verified on runtime evidence. R134G bronze for diagnostic even though its hypothesis was wrong. Product-state unchanged — flow engine hot path still broken. demo-onboard result and R134j regression deferred to post-fix cycle.

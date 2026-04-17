# R87–R89 validation run — 1 fix held, 3 new regressions surfaced

**Date:** 2026-04-17
**Build:** R89 (HEAD `84fb76f4`)
**Fixture script update:** baked `outputFile` into `regtest-f7` so byte-transform E2E no longer requires a post-hoc `PATCH`.

---

## Scorecard

| Item | Status |
|---|---|
| R89 — `NoSuchElementException` → 404 on `ServerInstanceController` | ✅ **PASS** — `GET /api/servers/<bogus-uuid>` returns 404 with a readable message (was 500 in R86 sanity) |
| R87 — FTP server maturity (per-listener passive ports, FTPS cert alias, PROT, banner, implicit TLS) | ⚠️ **not exercised end-to-end** — requires FTP client surface tests (not yet in the sanity sweep) |
| R88 — DMZ reverse-proxy PASV rewriting + passive-port forwarders | ⚠️ **not exercised end-to-end** — same |
| **3 new regressions surfaced by the sanity script, likely from R87/R88** | ❌ see below |

## The 3 regressions

### REG-1 — `bind_state` writeback is broken

sftp-service log says:
```
SFTP listener 'sanity-sftp-1' BOUND on port 2250   @ 23:36:46.604
SFTP listener 'sanity-sftp-1' BOUND on port 2250   @ 23:36:52.916
```

But DB still shows:
```
instance_id    bindState   boundNode       lastBindAttemptAt
sanity-sftp-1  UNBOUND     null            1776469029.715957
```

**Impact:** The R73 primary self-reporter / R68 reconciler paths that used to write BOUND back to the DB are no longer firing for dynamic listeners. Downstream: the UI shows listeners as UNBOUND even when they're actually serving traffic; the 409-conflict guard (REG-2) that depends on `active=true AND (bind_state='BOUND' OR ...)` silently breaks.

### REG-2 — Port-conflict guard fails; two listeners both claim the same port

Posted `sanity-sftp-1` at port 2250 → 201. Posted `sanity-conflict` at port 2250 → **201** (expected 409).

sftp-service log:
```
SFTP listener 'sanity-sftp-1'   BOUND on port 2250
SFTP listener 'sanity-conflict' BOUND on port 2250
```

Two listeners, same port, both logged BOUND. Physically impossible on a single NIC — the second `bind(2)` call must have thrown, but the registry logged it as BOUND anyway. Combined with REG-1, the DB now has two "active" listeners with the same `(internal_host, internal_port)` tuple even though the V64 partial unique index `uk_server_instance_host_port_active` was supposed to prevent exactly this.

**Likely root cause:** the same writeback path broke. The registry transitions to "BOUND" in memory without updating the DB, and the pre-insert uniqueness check passes because no other row has `bind_state='BOUND'`.

### REG-3 — Reconciler fires "drift detected" on a just-created listener

```
23:36:46.594  Reconcile: drift detected — 'sanity-sftp-1' desired but not bound, attempting bind
23:36:46.604  SFTP listener 'sanity-sftp-1' BOUND on port 2250
```

The reconciler is running every 30s and has to "repair" a listener that was just created moments ago. This suggests the happy-path insert → event → bind → state-update cascade is broken, and the reconciler is the only thing actually completing the bind. That's also consistent with REG-1 and REG-2.

## The one good result

**R89 PASS** — `GET /api/servers/00000000-0000-0000-0000-000000000000` now returns:
```
HTTP/1.1 404
{"timestamp":..., "status":404, "code":"NOT_FOUND", "message":"Server instance not found: ...", "path":"/api/servers/000..."}
```
Previously this was a 500 with `"An unexpected error occurred"`. This was the only finding from the R86 sanity sweep and it's closed.

## Fixture update (committed in this run)

[scripts/build-regression-fixture.sh](../../scripts/build-regression-fixture.sh) — `regtest-f7-script-mailbox` now seeds with the **outputFile** config baked in:

```json
{"type":"EXECUTE_SCRIPT","order":0,"config":{
  "command":"sh /opt/scripts/uppercase-header.sh ${file} ${workdir}/transformed.dat",
  "timeoutSeconds":"60",
  "outputFile":"${workdir}/transformed.dat"}}
```

Without this, `FlowProcessingEngine.executeScript()` logs "completed without output file — passing input through" and the sanity sweep's §8 byte-level diff fails. The R86 runs passed because I had been manually patching f7 post-fixture; that manual patch is now the default.

## Sanity sweep results on R89 (with the fixture fix + bind regressions)

```
48 PASS / 2 FAIL / 5 SKIP
```

Failures:
- `bindState=UNBOUND (expected BOUND)` — REG-1
- `duplicate port → 201` (expected 409) — REG-2

SKIPs are idempotent-reuse paths (flow f7 already exists from prior seed — this is expected behavior and not a regression).

## Recommendations

1. **P0 — Investigate R87/R88's impact on `SftpListenerRegistry.markBound()` or equivalent writeback.** REG-1/REG-2/REG-3 all point at the same broken code path. The in-memory BOUND transition isn't persisting to `server_instances.bind_state`. Likely the listener-bind success callback was refactored along with the R87 FTP work and the SFTP path lost its DB write.
2. **P1 — Add the bind_state-BOUND assertion to the uniqueness check.** The partial unique index on `(internal_host, internal_port) WHERE active=true` is load-bearing; until REG-1 is fixed, it's dead-code because nothing writes BOUND anyway.
3. **P2 — Extend the sanity sweep** with dedicated FTP client tests once REG-1/2/3 are fixed so R87/R88 get proper end-to-end coverage.

## What still holds after this run

- Cold boot clean (36 containers, 0 restarts, 129s to clean-healthy).
- Every other sanity test that doesn't touch bind_state: auth (6), flow CRUD (5), keystore (4), scheduler (2), observability (6), VFS ops (4), actuator (13), most negatives (4). All green.
- R89 404 fix.
- R85 SFTP `ls` fix still holds.
- R86 flow-selection fix (priority=999 catch-all) still holds.
- BOOTSTRAP-SECURITY tag still emitted.

The regression is narrow — it touches bind_state tracking on dynamic listeners. Runtime service operation remains healthy; the issue is in the control plane's visibility into listener state.

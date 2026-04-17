# EXECUTE_SCRIPT vs VFS — architectural gap report

**Date:** 2026-04-16
**Build:** R73 (HEAD `870f414e`)
**Surfaced by:** Regression fixture build — user asked to create and assign a sample script to a file flow so we can *really* test the flow works fully ([scripts/build-regression-fixture.sh](../../scripts/build-regression-fixture.sh)).
**Severity:** MEDIUM — blocks fully end-to-end validation of `EXECUTE_SCRIPT` within the VFS-only regime. No prod outage; a documented design constraint that is currently enforced by a hard throw.

---

## The gap in one sentence

The platform's always-VIRTUAL rule and the `EXECUTE_SCRIPT` flow step are mutually exclusive at runtime — `FlowProcessingEngine` explicitly throws `UnsupportedOperationException` when an `EXECUTE_SCRIPT` step fires against a VFS-backed file — so any regression that wants to drive a flow containing `EXECUTE_SCRIPT` end-to-end cannot do it through the standing test topology.

## The evidence

[`FlowProcessingEngine.java:1679-1680`](../../shared/shared-platform/src/main/java/com/filetransfer/shared/routing/FlowProcessingEngine.java#L1679-L1680):

```java
case "EXECUTE_SCRIPT" -> throw new UnsupportedOperationException(
        "EXECUTE_SCRIPT is not supported for VIRTUAL-mode accounts");
```

This path is reached from the VIRTUAL-mode pipeline (`processStepVirtual` branch). The PHYSICAL branch calls `executeScript(input, workDir, cfg, trackId)` at line 865 which invokes the command via `ProcessBuilder("sh", "-c", ...)` — i.e., the step needs a real filesystem handle on the input.

## Why this matters for regression testing

1. The fixture ([build-regression-fixture.sh](../../scripts/build-regression-fixture.sh)) creates all four dynamic listeners as **VIRTUAL**, in line with the standing rule.
2. Every seeded account (`regtest-sftp-1`, `regtest-ftp-1`, etc.) is bound to a VIRTUAL listener.
3. The `regtest-f7-script-mailbox` flow contains an `EXECUTE_SCRIPT` step that now points at a real deployed script (`/opt/scripts/uppercase-header.sh`, pushed into `mft-onboarding-api`, `mft-sftp-service`, and `mft-ftp-service`).
4. If anyone uploads a `.dat` file through any of these listeners, `SftpService → SEDA pipeline → FlowProcessingEngine.executeStep` will throw the `UnsupportedOperationException` as soon as it reaches the EXECUTE_SCRIPT step. The flow errors, the file is rejected, and we've validated the constraint — but not the script path.

So the regression suite can **prove the constraint fires**, but cannot **prove a real script path executes and mutates the file** without violating the VFS rule.

## The design reason (why the hard throw is correct today)

The `executeScript` implementation assumes a real `java.nio.file.Path` it can hand to `sh -c` as `${file}`. VFS files are content-addressed blobs backed by the `virtual_entries` / `virtual_blobs` tables; they do not have a stable filesystem path, and materializing them on demand into a worker container would (a) break the "never PHYSICAL" invariant that keeps files inside the VFS perimeter, (b) introduce per-step egress of cleartext to a POSIX filesystem (compliance concern), and (c) require cleanup or quota logic that isn't present.

The throw is a correct guardrail *given* those invariants. It is not the bug; the bug is that there is **no supported flow-step equivalent for running custom logic on VFS files**.

## Where things stand right now

| Lever | Works today | Blocked |
|---|:---:|:---:|
| Create an `EXECUTE_SCRIPT` step via `POST /api/flows` | ✅ | |
| Store the step's `command`, `timeoutSeconds`, `outputFile` config | ✅ | |
| Deploy the referenced script into a worker container | ✅ (we `docker cp` it into /opt/scripts) | |
| Run the step against a PHYSICAL-mode file | ✅ (via `executeScript(...)` in `FlowProcessingEngine`) | |
| Run the step against a VIRTUAL-mode file — the default | | ❌ (hard throw) |
| Run the same script via the Scheduler (`ScheduledTaskRunner.executeScript` at line 77) | ✅ (scheduler operates outside VFS) | |
| Regression-test that the `command` executed, exited 0, and mutated bytes — against a VFS upload | | ❌ (the default test topology cannot exercise this) |

## Options to close the gap (in my recommendation order)

### Option A — implement a VFS-native EXECUTE_SCRIPT (preferred)
Materialize the VFS input into a temp file under `workDir` for the duration of the process invocation, pass that path as `${file}`, read the produced output file back into the VFS on successful exit, delete both temp files, and fail the step if the process exits non-zero or times out. This is the direct fix — same `ProcessBuilder` call, wrapped in `try-with-resources` around the materialize/dematerialize.

**Trade-off:** egress cleartext to a filesystem inside the worker container for the duration of the script. Acceptable if the worker's tmpfs is non-persistent and the cleartext window is ≤ the script timeout.

### Option B — sandboxed VFS transformer interface
Introduce a `FlowStepFunction` SPI that receives `InputStream` + `OutputStream` (or byte[] for small files) and lives entirely in-JVM. Replace `EXECUTE_SCRIPT` in VIRTUAL mode with `APPLY_FUNCTION` that looks up a named transformer in `flowFunctionRegistry` (already exists per the `FlowFunctionRegistrar`). Script-style customization then ships as a plugin JAR rather than a shell command. No cleartext to disk.

**Trade-off:** bigger design change; loses the "drop any shell script in a directory" operator ergonomics that shell-based EXECUTE_SCRIPT provides.

### Option C — per-step opt-out override
Add a flow-step config `"allowPhysicalMaterialize": true`. When set, `FlowProcessingEngine` materializes the file temporarily (same as A) but only for steps that opt in. Leaves VFS-only the default, permits EXECUTE_SCRIPT as an explicit exception.

**Trade-off:** a knob to reason about per step, but local to the step config and visible in the DB.

### Option D — keep as-is, remove the step type from the VIRTUAL catalog
If the intent is that EXECUTE_SCRIPT is truly unsupported for VIRTUAL, then `POST /api/flows` should reject a flow whose `steps[].type == "EXECUTE_SCRIPT"` when the owning listener is VIRTUAL, *at creation time*, with a clear error message. Today the flow is created fine and silently fails at runtime — the worst UX. At minimum do this validation even if no behavior change otherwise.

## What the fixture does today to paper over this

[`build-regression-fixture.sh`](../../scripts/build-regression-fixture.sh):

1. Creates [`scripts/flow-samples/uppercase-header.sh`](../../scripts/flow-samples/uppercase-header.sh) — an idempotent sample script that uppercases the first line and writes to `${file}.transformed`.
2. Copies that script into `mft-onboarding-api`, `mft-sftp-service`, and `mft-ftp-service` at `/opt/scripts/uppercase-header.sh` (owned by root, 755), so the `command` path resolves in whichever worker picks the step off the Kafka queue.
3. Creates `regtest-f7-script-mailbox` flow whose EXECUTE_SCRIPT step uses `"command": "sh /opt/scripts/uppercase-header.sh ${file}"`.
4. Documents — in the script and now in this report — that running the flow end-to-end requires **one** of: (a) a PHYSICAL listener, (b) a VFS-native EXECUTE_SCRIPT (Option A/B/C), or (c) running the script via the scheduler API with cron instead of via an uploaded file trigger.

So the fixture exercises:
- The API creation path (step config accepted ✓)
- The script deployment path (`docker cp` → executable at the expected location ✓)
- **Not** the actual execution against a VFS-uploaded file (blocked by the UOE)

## Recommendation

1. **P1** — Pick Option A or C. Implementing the materialize-then-execute path closes the gap and lets the fixture actually drive end-to-end. Option C if you want it gated, Option A if you want EXECUTE_SCRIPT to Just Work.
2. **P1** — Whatever you pick, add the missing "create-time validation" from Option D as a belt-and-braces: rejecting an EXECUTE_SCRIPT flow wired to a VIRTUAL-only listener with a 400 and a readable message is strictly better UX than the current 500-at-runtime.
3. **P2** — Once EXECUTE_SCRIPT works under VFS, extend this fixture's flow f7 end-to-end: upload a `.dat` → assert the first line is uppercased → assert the file is delivered to the `regtest-sftp-1` mailbox.

## Known-neighbors (what this gap does NOT also explain)

These are separate issues surfaced in adjacent reports; listed here so readers don't conflate:

- **QoS-column entity-vs-schema drift** (`max_download_bytes_per_second` vs `max_download_bytes_per_sec`) blocks `POST /api/servers/{id}/accounts/{acc}` — documented in [2026-04-16-R73-validation-run-results.md §7](2026-04-16-R73-validation-run-results.md). Independent of EXECUTE_SCRIPT.
- **`ConnectorDispatcher` missing `@ObjectProvider` in four more classes** — R72 partial fix — same report.
- **`GET /api/flows` 500 from Redis cache Jackson type-id bug** — surfaced during this fixture build; worked around by querying the DB directly.
- **PGP keygen 500 from BouncyCastle "only SHA1 supported"** — also surfaced during this fixture build; blocks the seed for flow f4's `DECRYPT_PGP` step.

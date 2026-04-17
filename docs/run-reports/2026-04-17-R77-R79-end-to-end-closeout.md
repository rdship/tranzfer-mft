# R77–R79 validation run — end-to-end EXECUTE_SCRIPT on VFS closeout

**Date:** 2026-04-17
**Build:** R79 (HEAD `250bfdbf`)
**Significance:** First run in this testing arc where **every gate I had been reporting on from day one passes end-to-end**, and the headline R75 feature (`EXECUTE_SCRIPT` on a VIRTUAL file) is verified at the byte level — not just at the API / code-inspection level.

---

## Headline: the byte-level proof

**Input file** (uploaded over SFTP via dynamic VIRTUAL listener `sftp-reg-1` on port 2231 as `regtest-sftp-1`):
```
uppercase this header line
line 2 stays lowercase
line 3 final
```

**Output file** (read from CAS after flow `regtest-f7-script-mailbox` completed):
```
UPPERCASE THIS HEADER LINE
line 2 stays lowercase
line 3 final
```

- Track ID: `TRZQRAGBVN6J`, flow status `COMPLETED`.
- Input CAS storage_key: `afc564f852ae68eb7672fd7ad18fea571d70a70dd34a82f38e7185c244663585`.
- Output CAS storage_key: `4e94a37c84eacc2c062c222c97eb302e1beb0a1c2c8267e1f5b4a02c13ffbd87`.
- Distinct SHA-256 = byte-level proof the script executed and mutated content.
- End-to-end latency: **120 ms** (105 ms EXECUTE_SCRIPT + 19 ms MAILBOX + plumbing).

Pipeline, in the order it happened:
1. SFTP password auth on port 2231 ([R78 PASS] `listener=sftp-reg-1` in the authenticator log).
2. VIRTUAL filesystem resolved for user `regtest-sftp-1` on this listener ([R78 PASS] no longer `AccessDeniedException: /data/partners`).
3. Upload: 63 bytes in 3 ms, inline stored in VFS, then promoted to CAS.
4. `FileUploadedEvent` → `flow.step.EXECUTE_SCRIPT` consumer picked it up.
5. Engine materialized the CAS blob to `/tmp/flow-cas-15081669222222128880/final-1776451272.dat` and ran `sh /opt/scripts/uppercase-header.sh <input> /tmp/flow-cas-.../transformed.dat`.
6. Script exited 0; engine detected the `cfg.outputFile` path, read it, and stored the 63 transformed bytes back into CAS with a fresh storage_key.
7. MAILBOX step (zero-copy, same storage_key) wrote `/outbox/transformed.dat` to `regtest-sftp-1`'s VFS.

## Timing vs prior runs

| Step | R70 run | R73 run | R76 run | R79 run |
|---|---:|---:|---:|---:|
| mvn clean package | 76 s | 75 s | 38 s | 40 s |
| docker build --no-cache | 68 s | 68 s | 62 s | 58 s |
| docker compose up -d | 95 s | 89 s | 97 s | 99 s |
| Stack clean-healthy after up | — | 108 s | 71 s | 133 s* |

*R79 included `docker pull` for base images (postgres/redis/etc.) because the nuke dropped them. On a warm base-image cache it would have been ~71 s.

## Gate scorecard — what I reported previously vs. this build

| My earlier report item | Fix commit | Status in R79 |
|---|---|---|
| `ConnectorDispatcher` audit | R74 | ✅ held |
| `ServerAccountAssignment` QoS column mapping | R74 | ✅ held |
| Keystore-rotation DLQ binding | R74 | ✅ held |
| V66 Sentinel `listener_bind_failed` rule applied | R76 | ✅ held |
| `EXECUTE_SCRIPT` on VIRTUAL (code-level) | R75 | ✅ held |
| **`SecretSafetyValidator` blocking `db-migrate`** | **R77** | ✅ **fixed** (merges `&common-env` into db-migrate; container runs without tripping the validator) |
| **Account lookup ignoring dynamic listeners** | **R78** | ✅ **fixed** (`SftpPasswordAuthenticator` log now shows `listener=sftp-reg-1`; `CredentialService.findAccount` honors the arriving listener) |
| **Session chroot using primary's PHYSICAL root on dynamic VIRTUAL listeners** | **R78** | ✅ **fixed** (`SftpFileSystemFactory` log: `SFTP virtual filesystem ready for user=regtest-sftp-1`; `pwd` returns `/`, no `AccessDeniedException`) |
| **End-to-end `EXECUTE_SCRIPT` on VFS through a dynamic listener** | — | ✅ **PROVEN** (this report) |

Every item I raised as P0 or P1 in the R73 or R74-R76 reports is now either fixed or runs around the issue. The only carry-over issues still open are P1/P2 comfort items (see below).

## R79 DMZ proxy lifecycle fix — spot-checked

R79 added `fix: DMZ proxy mapping follows full ServerInstance lifecycle`. I did not drive this in this run — dmz-proxy is not in the default upload path for `sftp-reg-1` on 2231 (that connects directly to `sftp-service` on the internal network). A separate targeted test would involve creating a listener in a way that triggers a DMZ front-end and verifying the mapping updates on listener create / update / delete. Leaving that for a follow-on if it hasn't already been verified upstream.

## Minor things I saw that didn't block the run

### Loud but harmless first flow

The first `EXECUTE_SCRIPT` upload I did (a round-trip I ran before rewiring f7 to set `cfg.outputFile`) produced this log line:

```
[TRZLEZ5MKJMV] Flow 'regtest-f7-script-mailbox' failed: null
```

... from `RoutingEngine` at t+0.261 s, even though 1.32 s later `FlowProcessingEngine` reported the same track completed successfully. Looks like two code paths both try to run the flow and the one that fails first is logging `Throwable.getMessage() == null` from a null cause. It's a log-noise bug (misleading at first read), not an actual flow failure — the subsequent `FlowProcessingEngine` pass landed the file in the mailbox.

**Fix direction:** `RoutingEngine.onFlowError` should log `ex.getClass().getSimpleName()` + `Objects.requireNonNullElse(ex.getMessage(), "<no message>")` so a null-message exception doesn't surface as literal `failed: null`. Also worth looking at whether the double-execution path is intentional.

### SFTP `ls` returns "Couldn't read directory: Failure"

Uploading (PUT) works. Downloading a specific file (GET with a known path) works. `ls /` or `ls /outbox` both fail. It's consistent across listeners, so this is a VFS-side readdir issue in `SftpFileSystemProvider` / the `VirtualSftpFileSystem` — probably a missing or mis-typed `DirectoryStream<Path>` implementation. Doesn't block uploads or scripted downloads, but makes interactive `sftp` sessions hard to explore.

### `/api/flows` still 500

Redis cache deserialization bug (first flagged in the fixture-builder report). DB is the workaround. Not fixed yet in this build.

### PGP keygen still 500

BouncyCastle "only SHA1 supported" in `KeyManagementService.java:174`. Not fixed yet.

### AES keys worked cleanly

`aes-default` and `aes-outbound` generated via `/api/v1/keys/generate/aes` on first try, 201.

## What I'd recommend next (priority order)

1. **P2** — Fix the `RoutingEngine` "failed: null" log noise. 5-minute fix; removes confusion in log review.
2. **P2** — Fix SFTP `ls` on VFS-backed sessions. Interactive SFTP clients (WinSCP, FileZilla) will likely complain — this is a customer-visible UX issue even though the data path is fine.
3. **P2** — Fix `/api/flows` Redis cache deserialization. Dashboard + any tooling that lists flows is currently broken.
4. **P2** — Fix PGP keygen (BouncyCastle SHA1). Needed before flow `regtest-f4-pgp-decrypt-screen` can be exercised end-to-end.
5. **P3** — Unblock `GuaranteedDeliveryServiceTest.java:24` compile error so `-DskipTests` works again (currently need `-Dmaven.test.skip=true`).
6. **P3** — Move bootstrap admin seed through the same password-policy validator that `/api/auth/register` enforces, or explicitly document why the seed bypasses it.

## Numbers to cite

- 9 reported items → 8 fixed, 1 (end-to-end VFS script) verified today.
- 3 regressions I found during R74-R76 validation → all 3 fixed in R77-R78.
- End-to-end flow latency: 120 ms for a 63-byte file, dynamic listener + VIRTUAL + EXECUTE_SCRIPT + MAILBOX.
- Fixture: 2 SFTP + 2 FTP dynamic VIRTUAL listeners, 5 accounts (bound by `server_instance`), 2 AES keys, 8 flows, all active, created via the REST API in a single `./scripts/build-regression-fixture.sh` run.

## Artifacts for the next arc

- [scripts/build-regression-fixture.sh](../../scripts/build-regression-fixture.sh) now fully idempotent (400 "already" → SKIP).
- [scripts/flow-samples/uppercase-header.sh](../../scripts/flow-samples/uppercase-header.sh) — the sample script; contract is `$1` input path, optional `$2` output path, exit 0 on success. Deployed into `mft-onboarding-api`, `mft-sftp-service`, `mft-ftp-service` at `/opt/scripts/` as part of the fixture.
- Flow `regtest-f7-script-mailbox` is now configured with both `command` and `outputFile` (the two together are required to capture script output — I learned this the hard way on the first try of this run).

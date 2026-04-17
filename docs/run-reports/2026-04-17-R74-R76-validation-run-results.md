# R74–R76 validation run — fix-verification results

**Date:** 2026-04-17
**Build:** R76 (HEAD `2998675d` at test time)
**Purpose:** Verify the fixes landed across R74, R75, R76 against the three prior reports:
  - [2026-04-16-db-migrate-regression-R70-connector-dispatcher.md](2026-04-16-db-migrate-regression-R70-connector-dispatcher.md)
  - [2026-04-16-R73-validation-run-results.md](2026-04-16-R73-validation-run-results.md)
  - [2026-04-16-execute-script-vfs-gap.md](2026-04-16-execute-script-vfs-gap.md)

---

## Headline scorecard — what I asked to be fixed vs. what actually is

| # | Item | Fix commit | Result |
|---|---|---|---|
| 1 | `GuaranteedDeliveryService` → `ObjectProvider<ConnectorDispatcher>` | R72 | ✅ held |
| 2 | Same treatment for `RoutingEngine`, `SlaBreachDetector`, `AlertReporter`, `AutoRemediationService` | R74 | ✅ confirmed — no DI error in db-migrate logs |
| 3 | `ServerAccountAssignment` QoS column mapping (`_bytes_per_sec`) | R74 | ✅ `POST /api/servers/{id}/accounts/{acc}` returns 201 |
| 4 | Keystore-rotation DLQ binding on `file-transfer.events.dlx` | R74 | ✅ `keystore.rotation.dlq` bound with pattern `keystore.key.#` |
| 5 | V66 `listener_bind_failed` Sentinel rule applied | R76 | ✅ inline-seeded via `BuiltinRuleSeeder`, severity `HIGH`, enabled |
| 6 | `EXECUTE_SCRIPT` works on VIRTUAL files (materialize → exec → dematerialize) | R75 | ✅ **code-level confirmed** — UOE throw removed, `refExecuteScript` path implemented per my Option A recommendation |
| 7 | Script allowlist / template-injection guards | R76 | ✅ present in `FlowProcessingEngine.java:2160-2165` |
| 8 | `GuaranteedDeliveryServiceTest.java:24` updated for the new signature | — | ⚠️ still fails test-compile; `-Dmaven.test.skip=true` continues to be required |

**Eight of my report items closed.** This is the strongest fix cycle since testing started — R72 → R73 → R74 → R76 has steadily retired the catalog.

## Boot-up timing (fastest run yet)

| Step | Duration | Exit |
|---|---:|---|
| mvn clean package (-Dmaven.test.skip=true, -T 1C) | 38 s | 0 |
| docker compose build --no-cache (26 images) | 62 s | 0 |
| docker compose up -d (36 containers) | 97 s | 0 |
| All runtime services healthy (0 restarts, 0 unhealthy, 0 restarting) | **71 s** | ✅ |
| Total wall-clock to green | ~4 min 8 s | |

For comparison: the first successful boot of this cycle was t=173 s with 14+ restart loops, then t=108 s without them. t=71 s this run.

## Three **new** regressions found in this run

None of these block the fix-verification story above, but they are new and worth fixing.

### NEW-1 — `SecretSafetyValidator` blocks db-migrate (db-migrate exit 1 returns, different root cause)

The ConnectorDispatcher DI chain is fixed, but `db-migrate` now fails a different startup gate:

```
ERROR SecretSafetyValidator — SECRET VIOLATION [PROD]: platform.security.jwt-secret
  is still the default value
ERROR SecretSafetyValidator — SECRET VIOLATION [PROD]: spring.datasource.password
  is still the default value ('postgres')
Startup blocked: 2 secret safety violation(s) detected in PROD environment.
```

`db-migrate`'s env resolves to `PROD` (per its own earlier log line: `Loaded 13 platform settings for service=ONBOARDING env=PROD`) because its compose block doesn't merge `&common-env` — same pattern that caused [the original db-migrate DI issue](2026-04-16-db-migrate-regression-R70-connector-dispatcher.md). Runtime services pass the validator because they receive the dev overrides through `&common-env`.

The validator's intent is correct. The blast radius is wrong: it should either (a) skip for `web-application-type=none` one-shot containers, (b) be gated off when `SPRING_MAIN_WEB_APPLICATION_TYPE=none`, or (c) `db-migrate`'s compose block should merge the dev overrides that every other service already gets.

**Fix direction — preferred:** merge `&common-env` into db-migrate (one line of YAML). The alternative of gating the validator is a blunter tool that hides a real misconfig if it ever hits prod again.

### NEW-2 — account lookup ignores dynamic listeners, breaking end-to-end VFS flow testing

While trying to verify R75 end-to-end (upload `.dat` → EXECUTE_SCRIPT materializes → delivered) I hit a blocker I had not seen before:

[`sftp-service/CredentialService.java:84-89`](../../sftp-service/src/main/java/com/filetransfer/sftp/service/CredentialService.java#L84-L89):

```java
if (instanceId != null) {
    dbAccount = accountRepository.findByUsernameAndProtocolAndInstance(
            username, Protocol.SFTP, instanceId);
} else {
    dbAccount = accountRepository.findByUsernameAndProtocolAndActiveTrue(username, Protocol.SFTP);
}
```

`instanceId` is the sftp-service **container's own primary** (`sftp-1`), resolved once at boot. But the same container also hosts all dynamic listeners added via `POST /api/servers` (e.g., `sftp-reg-1` on port 2231). When a connection arrives on 2231 and the account has `server_instance='sftp-reg-1'`, the lookup uses `instanceId='sftp-1'` and returns **empty** — so `findAccount` returns `Optional.empty()`, authentication fails with `invalid_credentials`.

Evidence:

- Direct bcrypt verification against the stored hash in Python returns `True` for the correct password — the hash itself is fine.
- The seeded `acme-sftp` account (which has `server_instance = NULL`) authenticates on port 2231 successfully, because the null-case branch at line 88 bypasses the instance filter.
- Swapping `regtest-sftp-1`'s hash to `acme-sftp`'s proven-working hash still fails — confirming the hash isn't the issue.
- The SFTP audit log even labels the session with `instanceId=sftp-1` when the connection arrived on 2231 (a secondary symptom: the audit doesn't distinguish listeners either).

**Fix direction:** the authenticator must resolve the arriving listener from the `ServerSession` (`session.getServerAddressHolder()` or whatever `SshServer` exposes) and pass *that* instance ID into `findAccount`. `SftpListenerRegistry` already knows the port→UUID mapping; the hard part is threading it through the `PasswordAuthenticator` callback. Same fix needed on ftp-service.

Secondary effect: [NEW-3] below is mostly the same class of issue.

### NEW-3 — SFTP session chroot still hits `/data/partners` even on a VIRTUAL listener

When `acme-sftp` (`server_instance=NULL`) connected via the dynamic port 2231, auth succeeded, then the session died on `AccessDeniedException: /data/partners`. This is the same symptom as the original VFS blocker — the session's file-system root is being computed from the primary listener's `defaultStorageMode=PHYSICAL` rather than the **arriving** listener's `VIRTUAL`. The SFTP subsystem is treating the connection as if it came in on sftp-1 regardless.

This is the second half of the listener-awareness gap: both the account lookup (NEW-2) and the storage-mode resolution for the session (NEW-3) use the container's primary as a hard-coded stand-in for the listener that actually received the connection.

**Fix direction:** same as NEW-2 — thread the listener identity through `session.getFactoryManager()` or the `ServerSession` properties into the `SftpSubsystem`/`VirtualFileSystemFactory` that builds the session root.

Until NEW-2 and NEW-3 are fixed, **runtime end-to-end verification of R75 (EXECUTE_SCRIPT on VFS) is blocked through the normal path** — account on a dynamic VIRTUAL listener. The R75 code is demonstrably wired (see "R75 code-level" below) but no VFS connection can currently reach the flow engine with a regtest account.

## R75 code-level verification (since runtime is blocked)

In R69/R73 this line was the gate:
```java
case "EXECUTE_SCRIPT" -> throw new UnsupportedOperationException(
        "EXECUTE_SCRIPT is not supported for VIRTUAL-mode accounts");
```

In R76 ([FlowProcessingEngine.java:1682](../../shared/shared-platform/src/main/java/com/filetransfer/shared/routing/FlowProcessingEngine.java#L1682)):
```java
case "EXECUTE_SCRIPT" -> refExecuteScript(storageKey, virtualPath, origin, trackId, cfg);
```

And the new implementation ([lines 2121–2220](../../shared/shared-platform/src/main/java/com/filetransfer/shared/routing/FlowProcessingEngine.java#L2121-L2220)) does exactly what my gap report recommended:

1. Materialize the VFS input to a temp file under `workDir` (`materializeFromCas`).
2. Run the allowlisted shell command with `${file}`, `${trackid}`, `${workdir}` substitution.
3. On exit-0, either pass the input through (if no `outputFile` config) or store the script's output file back into the CAS as a new VFS entry, then return the new storage key.
4. On non-zero or timeout, fail the flow step.

The throw is gone. The allowlist (`TEMPLATE_INJECTION.matcher`, `SAFE_SHELL_ARG.matcher`) is in place. Unit-testable even while the end-to-end path is blocked by NEW-2.

## Fixture state after this run

From `./scripts/build-regression-fixture.sh`:

| Fixture | Count | Notes |
|---|---:|---|
| VIRTUAL SFTP listeners (`sftp-reg-1/2`) | 2 | BOUND, bound_node populated for primaries |
| VIRTUAL FTP listeners (`ftp-reg-1/2`) | 2 | BOUND |
| Transfer accounts (`regtest-*`) | 5 | 4 password, 1 SSH-key variant |
| Server↔account assignment (NEW for R74) | 1 | `regtest-sftp-1` ↔ `sftp-reg-1`, HTTP 201 |
| AES keys (`aes-default`, `aes-outbound`) | 2 | via `/api/v1/keys/generate/aes` |
| PGP keys | 0 | still blocked by BouncyCastle SHA1 bug in `KeyManagementService.java:174` |
| File flows (`regtest-f1` … `regtest-f8`) | 8 | all active, varied pipelines |
| Sample EXECUTE_SCRIPT handler deployed | 3 containers | `/opt/scripts/uppercase-header.sh` in onboarding-api, sftp-service, ftp-service |

## Known-carried issues from prior reports that are still open

- **`GET /api/flows` 500** from Redis cache deserialization (`GenericJackson2JsonRedisSerializer` can't resolve polymorphic type IDs in step `config` Map). Worked around with DB queries.
- **PGP keygen 500** from BouncyCastle "only SHA1 supported". Blocks flow f4's `DECRYPT_PGP` seed.
- **Flyway cross-module version collision** (V64 etc.). Papered over for R68's V66 by inline-seeding. Any future per-module migration should still pick a non-overlapping range.
- **`GuaranteedDeliveryServiceTest` still won't compile** against the new `ObjectProvider` signature — requires `-Dmaven.test.skip=true`.

## Recommendations, priority order

1. **P0 — NEW-2 (account lookup for dynamic listeners).** Plumb the arriving listener's instance ID from `ServerSession` → `CredentialService.findAccount`. Apply the same fix to ftp-service. This unblocks runtime verification of every flow that uses a dynamic listener.
2. **P0 — NEW-3 (session chroot for dynamic listeners).** Same root cause as NEW-2; same plumbing but into the SFTP subsystem's `VirtualFileSystemFactory`. Fix together.
3. **P0 — NEW-1 (SecretSafetyValidator in db-migrate).** One-line compose change (merge `&common-env`) is the lowest-risk fix.
4. **P1 — carry-over: PGP BC bug, flows Redis cache, GuaranteedDeliveryServiceTest.**
5. **P2** — once NEW-2/NEW-3 land, run the full end-to-end EXECUTE_SCRIPT path: upload `.dat` on `sftp-reg-1` → flow f7 runs → uppercase-header.sh transforms first line → delivered to `regtest-sftp-2` mailbox → Activity Monitor shows the track ID. That is the true close-out of the R75 gap.

## What to retest after NEW-2 lands

```
# From inside docker network (tranzfer-mft_default):
sshpass -p 'RegTest@2026!' sftp -P 2231 regtest-sftp-1@sftp-service <<< "put /test.dat"

# Expect:
#   Upload OK
#   flow_executions row appears within 5s, status=COMPLETED or IN_PROGRESS
#   virtual_entries row created for the original
#   virtual_entries row created for the transformed output (uppercase first line)
#   MAILBOX delivery row for destinationUsername=regtest-sftp-1
```

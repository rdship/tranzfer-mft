# R80–R83 validation run — batch of 10 claimed fixes

**Date:** 2026-04-17
**Build:** R83 (HEAD `9386b0c9`)
**Reference:** This run targets each P1/P2/P3 open item from the [consolidated dev-team report](2026-04-17-dev-team-consolidated-report.md).
**Outcome:** 6 of 9 claimed fixes hold, 2 fixes don't fully land, 1 is inconclusive; 1 new observation worth surfacing (seeded catch-all flow preempts fixture).

---

## Gate scorecard

| # | My prior item | Fix commit | Result |
|---|---|---|---|
| 1 | Boot — db-migrate exit 0 (R77) | R77 | ✅ held |
| 2 | P1-1 RoutingEngine `failed: null` + duplicate execution | R80 + R82 | ✅ held — no `failed: null` on successful tracks, only legitimate webhook-delivery warnings to unresolvable seeded hostnames |
| 3 | P1-2 SFTP `ls` on VFS-backed sessions | R81 | ❌ **did not hold** — error text changed from `"Couldn't read directory: Failure"` to `"Couldn't read directory: Operation unsupported"`, but `readdir` still throws. Same UX impact (interactive SFTP clients can't enumerate). |
| 4 | P1-3 `GET /api/flows` 500 Redis cache | R83 | ✅ held — direct `GET http://config-service:8084/api/flows` returns 200 with the flow list |
| 5 | P1-4 PGP keygen 500 BouncyCastle SHA1 | R83 | ❌ **did not hold** — `KeyManagementService.java:184` still throws `PGPException: only SHA1 supported for key checksum calculations.` Same stack trace as before, just one line later in the file |
| 6 | P2-2 bootstrap admin seed bypasses password policy | R83 | ⚠️ inconclusive in this run — no dedicated startup warning log line I could isolate from noise |
| 7 | P2-3 SFTP audit logs wrong `instanceId` for dynamic listeners | R80 | ✅ held — authenticator log shows `listener=sftp-reg-1` correctly for port 2231 |
| 8 | P2-4 `bound_node` empty on seeded secondaries | R83 | ✅ held — **all** active SFTP + FTP listeners now populate `bound_node`. ftp-web still UNKNOWN as expected per guide §8 (no dynamic listener registry yet there) |
| 9 | P3-1 Flyway cross-module collision (V64 double-claim) | R83 | ✅ structurally fixed — platform-sentinel's migrations renamed from V64/V65/V66 to V200/V201/V202. History table only shows application-level sentinel rule via `BuiltinRuleSeeder`; V200+ not yet in `flyway_schema_history` at test time (sentinel may apply them via its own Flyway instance). Functionally the rule is seeded regardless. |

**Plus:** end-to-end flow — 8 of 8 green (see below).

## End-to-end flow re-validated (the one that matters)

Upload a `.dat` over SFTP on dynamic VIRTUAL listener `sftp-reg-1:2231` as `regtest-sftp-1` → flow `regtest-f7-script-mailbox` picks it up → EXECUTE_SCRIPT materialize+exec+dematerialize → MAILBOX delivery to `regtest-sftp-1`'s `/outbox`.

- Track ID: `TRZM5EV6TYJR`, status `COMPLETED` in **301 ms**.
- Input CAS storage_key: `337ac33be7c922cff2ab9156605ddd7a43dd3fb265311d9bcb38ce103bf91b14`.
- Output CAS storage_key: `8f9ef43b516628c791938706f35b03760ee30654535e5211a6a72aacc7ccf8dd`.
- Distinct SHA-256 → bytes genuinely transformed.
- Input first line: `hello verify me first line` → Output first line: `HELLO VERIFY ME FIRST LINE`; remaining lines passed through unmodified.

Confirmed directly from the storage-manager container's CAS (`/data/storage/hot/<sha256>`), not inferred from API metadata.

## Boot-up timing

| Step | Duration | Exit |
|---|---:|---|
| mvn clean package (-Dmaven.test.skip=true -T 1C) | 40 s | 0 |
| docker compose build --no-cache (26 images) | 67 s | 0 |
| docker compose up -d (36 containers, including base-image pull) | 137 s | 0 |
| All runtime services healthy, zero restarts/unhealthy/restarting | **96 s** after up | ✅ |

## New observation worth surfacing to dev team

**Seeded "Mailbox Distribution" flow (pattern `.*`, priority 100) preempts every fixture flow.**

The bootstrap seeds a catch-all `Mailbox Distribution` flow that matches every inbound file and has the highest priority of any seeded flow. On any regression upload, it wins over the fixture flows (priority 10), runs, and fails with `Destination account not found: retailmax-ftp-web (SFTP)` — the seeded hardcoded destination doesn't exist in a fresh-stack-with-fixture scenario.

Workaround in this run: `PATCH /api/flows/{f7-id}` with `{"priority":200}` to bump the regression flow above the seeded catch-all. But this is an open foot-gun: any E2E test has to either (a) bump every fixture flow above 100, (b) deactivate `Mailbox Distribution` at fixture-build time, or (c) the seeded flow should be priority ≤ 5 (a "default if no other matches" rather than a winner). Option (c) is probably the right design.

## What still needs fixing (carry-forward)

### P1-2 — SFTP `ls` on VFS still broken (R81 regression)

Before R81: `"Couldn't read directory: Failure"`.
After R81: `"Couldn't read directory: Operation unsupported"`.

R81's commit message was "drop posix attribute claim, enrich map view." That may have addressed some attribute-layer issue but the top-level `readdir` still throws `UnsupportedOperationException` (which the OpenSSH client renders as "Operation unsupported"). Likely the `newDirectoryStream(...)` method on `VirtualSftpFileSystemProvider` itself still throws, or the returned `DirectoryStream` implementation throws on first iteration.

**Fix direction:** actually implement `newDirectoryStream` — return a `DirectoryStream<Path>` backed by a SQL query against `virtual_entries WHERE parent_path = :path AND account_id = :acc AND deleted = false`. Spot-check with a quick grep:
```
grep -rn "newDirectoryStream\|DirectoryStream" shared/shared-platform/src/main/java/com/filetransfer/shared/vfs/ | head
```

### P1-4 — PGP keygen still throws BC SHA1 error (R83 didn't hold)

Stack trace identical to the pre-R83 run:
```
o.bouncycastle.openpgp.PGPException: only SHA1 supported for key checksum calculations.
    at o.bouncycastle.openpgp.PGPSecretKey.buildSecretKeyPacket
    ...
    at c.f.k.service.KeyManagementService.generatePgpKeypair(KeyManagementService.java:184)
```

Line number moved from 174 to 184, so there was a code edit nearby, but the underlying `PGPSecretKey` constructor call is unchanged. The old BC `PGPSecretKey(int, PGPPublicKey, PrivateKey, Date, ...)` constructor path is still being used. Modern BC wants `PGPSecretKey.copyWithNewPassword(...)` or `PGPKeyPair` + `PBESecretKeyEncryptor` via `JcaPGPKeyConverter`.

**Fix direction:** rewrite `generatePgpKeypair` to use `PGPKeyRingGenerator` + `JcaPGPKeyPair` + `PGPDigestCalculatorProvider` with SHA-256 (or explicitly SHA-1 as a documented legacy choice, passed via `new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1)`). The modern idiom avoids the deprecated `PGPSecretKey.buildSecretKeyPacket` path entirely.

### P2-2 — admin seed password policy (inconclusive)

No dedicated startup log line I could grep from noise. If R83 added a warning, it's drowned in Kafka config dumps. If it didn't, this is still open. Probably worth looking at `PlatformBootstrapService.seedSuperAdmin()` directly to confirm whether it calls the policy validator now.

## What's solid post-R83

- Cold-boot: zero restart loops across 13 cycles of mvn → build → up → fixture → E2E in this arc.
- Dynamic listener create/bind/rebind/delete: all paths verified.
- Port-conflict UX: 409 + 5 suggestions, spec-exact.
- Fixture: 4 VIRTUAL listeners, 5 accounts, 2 AES keys, 8 flows, 1 deployed EXECUTE_SCRIPT handler, all created in a single re-runnable `./scripts/build-regression-fixture.sh` call.
- End-to-end VFS + EXECUTE_SCRIPT + MAILBOX on dynamic listener: byte-level proven, 301 ms.
- Listener-aware auth + filesystem + audit: all three paths honor the arriving listener correctly.
- Outbox atomicity: `published_at` populated within ~160 ms, `attempts=1`.
- Keystore rotation: hot-reload works; poison messages dead-letter to `keystore.rotation.dlq`.
- Sentinel rule seeded and queryable; `bound_node` populated on primaries + secondaries + dynamics.

## Recommendations (updated priorities)

1. **P0** — finish P1-2 fix properly (VFS `readdir` implementation). Interactive SFTP UX is broken.
2. **P0** — finish P1-4 fix properly (BC PGP API modernization). Blocks `DECRYPT_PGP` flow regression + customer onboarding that requires PGP.
3. **P1** — lower the priority (or de-activate) the seeded `Mailbox Distribution` catch-all flow so fixture and user-created flows can win on their specific patterns without gymnastics.
4. **P2** — audit and document the admin seed password-policy path (one way or the other — enforce it, or explicitly document that seed creds bypass).

## Numbers worth citing

- Total fixes claimed in R80-R83 addressing my report: 10.
- Fixes that hold: 6 (R77, R80 null-log, R80 audit, R82 dedup, R83 Redis cache, R83 bound_node).
- Fixes that don't hold: 2 (R81 ls, R83 PGP).
- Fixes with structural/code changes but indirect verification: 1 (R83 Flyway rename — sentinel rule is present via seeder; V200+ not observable in shared history table yet).
- End-to-end flow latency on R83: **301 ms** for a 63-byte file through the full dynamic-listener + VFS + EXECUTE_SCRIPT + MAILBOX path.
- Cold boot on R83: 96 s to clean-healthy after `up -d`; total cold-cycle (mvn+build+up+healthy): ~5.7 min.

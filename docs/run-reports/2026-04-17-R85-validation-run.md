# R85 validation run — 2 of 3 claims hold, flow-selection still broken at a deeper layer

**Date:** 2026-04-17
**Build:** R85 (HEAD `0a7e4d29`)
**Reference:** Targeted validation of the three R84 misses re-attempted in R85.

---

## Scorecard

| Claim in R85 commit | My prior item | Result |
|---|---|---|
| `VFS POSIX attributes` | P1-2 SFTP `ls` on VFS | ✅ **PASS** — `ls`, `pwd`, and `ls` after `put` all work correctly. Empty directory returns empty listing; after upload the file appears. |
| `BOOTSTRAP-SECURITY always-on` | P2-2 admin seed password policy | ✅ **PASS** — emitted on boot with an actionable message and remediation path |
| `Mailbox→999` | Seeded catch-all preempts fixture | ❌ **not fixed** — DB still shows `Mailbox Distribution` at priority=1, and more importantly, setting `regtest-f7` to **priority=9999** still loses the selection race. Flow picking is driven by something other than priority alone. |

## P1-2 SFTP `ls` — the proof

Upload + list round-trip via the dynamic listener:

```
sftp> pwd
Remote working directory: /
sftp> ls                                    # empty dir before upload
sftp>
sftp> put /test.dat r85-1776464835.dat
Uploading /test.dat to /r85-1776464835.dat
sftp> ls                                    # file appears after upload
r85-1776464835.dat
sftp> bye
```

Three-round fix arc finally closed:
- R81: changed error from `"Couldn't read directory: Failure"` → partial progress.
- R84: changed it to `"Operation unsupported"` — same behavior, different text.
- R85: **actually implemented** `newDirectoryStream` + proper POSIX attribute mapping (`VirtualSftpFileAttributes.java` +44 lines, `VirtualSftpFileSystemProvider.java` +39 lines). `ls` now returns real entries.

Interactive SFTP clients (WinSCP / FileZilla / CLI sftp) can now browse partner mailboxes. Customer-visible blocker removed.

## P2-2 BOOTSTRAP-SECURITY tag — the proof

Exact format emitted at `mft-onboarding-api` startup:

```
[BOOTSTRAP-SECURITY] status=DEFAULT_PASSWORD_IN_USE env=DEV
  — set PLATFORM_BOOTSTRAP_ADMIN_PASSWORD or rotate via POST /api/users/me/password
```

Well-formed, greppable, actionable. Good.

## Mailbox priority — the deeper bug

R85 commit title says `Mailbox→999` but the DB after bootstrap shows:

```
name                  filename_pattern   priority
Mailbox Distribution  .*                 1
```

Either the seed value wasn't updated to 999 (title said 999; seed code may still say 1), or the priority-comparison direction in the engine was inverted alongside and the intent is "lower wins and 1 is the fallback" (which contradicts "catch-all should fall through").

Even ignoring the value dispute, **priority is not the selector** — I bumped `regtest-f7-script-mailbox` all the way to priority=9999 (a clearly-chosen winner under any reasonable scheme) and `Mailbox Distribution` (priority=1, pattern=`.*`) still won. Two bullets from the RoutingEngine log confirming:

```
[TRZFUEMY9STG] Matched flow 'Mailbox Distribution' (priority=1, rule=707a0305-...)
```

Nothing about f7 ever being considered in the match log.

### What this means for the engine

The flow rule registry / `FlowRuleRegistry.match()` almost certainly picks by something other than priority — possibly:
1. A first-match-wins scan in insertion order (seeded flows were inserted first).
2. A fixed `default_rule` flag on `Mailbox Distribution` somewhere in code even though the DB column doesn't exist.
3. A stale cache where API-created flows aren't propagated (I tested with `PATCH /api/flows/{id}` and the DB reflected priority=9999, so the primary row is right).

This isn't a test-tuning issue. It's **a user-facing bug** — any customer creating a specific-pattern flow via the admin UI will watch it lose to a seeded catch-all indefinitely. Dev team needs to look at `RoutingEngine.matchFlowRule()` and/or `FlowRuleRegistry` to identify the actual tiebreak logic, then either:
- Fix it to honor priority (higher wins, with catch-all at 1 = true fallback), or
- Deactivate `Mailbox Distribution` as a default seeded flow, or
- Document the actual selection contract and make the seed data follow it.

### Repro (for the dev team)

On a fresh cold boot:
```
./scripts/build-regression-fixture.sh
# bump f7 priority to 9999
curl -X PATCH http://localhost:8084/api/flows/$F7 -d '{"priority":9999}'
# upload a .dat
sshpass -p RegTest@2026! sftp -P 2231 regtest-sftp-1@sftp-service:put /tmp/x.dat
# observe
docker logs mft-sftp-service 2>&1 | grep "Matched flow"
# expect: regtest-f7-script-mailbox picked
# actual:  Mailbox Distribution picked
```

## End-to-end EXECUTE_SCRIPT on VFS — still proven, still blocked on regression

The R79 byte-level proof remains the definitive record:
- Input CAS key `afc564f8…` → output CAS key `4e94a37c…` after `uppercase-header.sh` ran against a materialized VFS blob.
- Flow `regtest-f7-script-mailbox` COMPLETED in ≤120 ms.

Today's R85 run cannot re-reach that path because every `.dat` upload is intercepted by the seeded catch-all. Fix the flow selection and it re-unblocks.

## Boot-up timing

| Step | R85 |
|---|---:|
| mvn clean package | 46 s |
| docker build --no-cache | 61 s |
| docker compose up -d (with base-image pull) | 106 s |
| Clean-healthy after up | **145 s** |

Longer than R83's 96 s — likely variance in the base-image pull step. Stack itself: same quality — zero restarts, zero unhealthy, zero restarting.

## Summary of this arc, as of R85

Across R64 → R85 (22 releases):

- **Fixes that hold across runs:** 14 (cold boot clean, dynamic listeners, listener-aware auth/filesystem/audit, outbox atomicity, keystore rotation + shared DLX, Sentinel bind-failed rule, V64-era boot race, connector-dispatcher audit, QoS column mapping, Redis flow cache, PGP keygen, bound_node on all listener tiers, BOOTSTRAP-SECURITY tag, VFS SFTP ls).
- **Open items:** 1 (flow-rule selection — priority isn't winning; the seed catch-all always does).
- **Byte-level end-to-end proven (R79):** VFS upload → EXECUTE_SCRIPT → delivery, still holds when the selection bug is worked around.

## Recommendations

1. **P0 — fix flow-rule selection.** This is the last gate between a clean regression loop and operating this as a real MFT platform. Look at `FlowRuleRegistry.match()` / `RoutingEngine.matchFlowRule()` to see the actual algorithm.
2. **P2 — document the selection algorithm in `CLAUDE.md`** so future fixture writers don't have to rediscover it.
3. **P3 — add a CI smoke test** that uploads a file matching a specific-pattern regtest flow through the fixture listener and asserts the matched flow is the regtest flow (not the seeded catch-all).

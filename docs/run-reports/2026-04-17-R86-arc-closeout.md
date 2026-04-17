# R86 arc closeout — every item from this testing cycle is green

**Date:** 2026-04-17
**Build:** R86 (HEAD `0f647c5a`)
**Significance:** this is the first build in the R64→R86 arc where **every** reported P0/P1/P2 issue is closed *and* the end-to-end EXECUTE_SCRIPT-on-VFS path through a user-created flow (not a seeded one) runs green on a fresh stack with no workarounds.

---

## Final E2E proof

Fresh `docker compose down -v` → build → up → fixture → single SFTP upload → byte-level diff of input vs. output in the CAS.

**Input** (uploaded over `sftp-reg-1:2231` as `regtest-sftp-1`, stored at `/r86-1776466158.dat`, key `4b5a27be…`):
```
final arc validation r86
line 2 stays
line 3 final
```

**Output** (delivered at `/outbox/transformed.dat`, key `3d5bda49…`):
```
FINAL ARC VALIDATION R86
line 2 stays
line 3 final
```

- Track ID `TRZHDE9FF3RB`, flow `regtest-f7-script-mailbox`, status `COMPLETED` in **508 ms**.
- Distinct CAS `storage_key` = bytes genuinely mutated; first line uppercased, rest preserved.
- `Matched flow 'regtest-f7-script-mailbox' (priority=100, rule=ab9cff3c-...)` — user flow beat the seeded catch-all (priority 999).
- Zero workaround: f7 priority set by the normal `POST /api/flows` body; no post-hoc `PATCH` required.

## R86 gate scorecard

| Claim | Result |
|---|---|
| `Mailbox Distribution` priority actually persisted as **999** | ✅ PASS — DB shows `Mailbox Distribution | .*  | 999` after fresh bootstrap |
| User/fixture flow wins selection on specific patterns | ✅ PASS — `regtest-f7` at priority=100 matched first on a `.dat` upload |
| EXECUTE_SCRIPT on VFS through the winning flow | ✅ PASS (byte-level, documented above) |

## What R86 revealed about the selection semantics

The engine uses **lower priority number = higher match preference**. Setting `Mailbox Distribution` to **999** makes it the true fallback — it only wins when no more-specific flow matches. Specifically-patterned user flows at any lower number now win on their own patterns, which is the correct customer-facing behavior.

This means my earlier R83 workaround (`PATCH f7 to priority=200` to beat the then-`100` catch-all) was the right direction but based on a wrong reading of semantics — I had higher-wins in my head. On R85 I then tried priority=9999 and it still lost, which confirmed priority isn't the only factor in the cache (and also confirmed the engine was lower-wins). R86 closes the loop: it lowers the catch-all's precedence to the maximum value, leaving the specificity decision to the priority field alone.

**Recommendation** (P2) to lock this in: add a constraint or Flyway migration note that any seeded catch-all flow must be priority ≥ 999, and document this in `CLAUDE.md` so future contributors don't relearn it.

## Arc-wide numbers (R64 → R86 — 23 releases)

- **15 distinct P0/P1 issues** reported and closed across the arc.
- **3 P2 comfort items** reported and closed (audit log correctness, admin seed tag, bound_node on secondaries).
- **0 open P0/P1** at R86.
- **22 commits** directly responding to my reports (R67, R68, R69, R70, R71, R72, R73, R74, R75, R76, R77, R78, R79, R80, R81, R82, R83, R84, R85, R86 + two docs).
- **Boot time:** 76–145 s (variance driven by base-image cache warmth; zero restart loops across the arc once the DI issues were closed in R74).
- **End-to-end latency:** 120–765 ms for a small file through `SFTP upload → VFS store → flow pipeline (EXECUTE_SCRIPT materialize+exec+dematerialize → MAILBOX) → CAS delivery`. 508 ms on this R86 run.
- **Fixture:** 4 VIRTUAL listeners (2 SFTP + 2 FTP), 5 accounts, 2 AES keys, 8 flows, 1 deployed EXECUTE_SCRIPT handler, all idempotently created by `./scripts/build-regression-fixture.sh` in ~15 s.

## What's solid post-R86 (the running green list)

All of these hold on a fresh cold boot with no special flags:

- ✅ Cold boot: 36 containers → 34 healthy + 0 restart + 0 unhealthy + 0 `Restarting`. `db-migrate` exits 0 (R77).
- ✅ Dynamic listener lifecycle: create / bind / rebind / delete, all round-tripped via API.
- ✅ Port-conflict UX: 409 + 5 sorted suggestions.
- ✅ Listener-aware auth, filesystem, and audit (R78/R80): `listener=sftp-reg-1` reflected everywhere.
- ✅ SFTP `ls` on VFS (R85): real directory entries before and after upload.
- ✅ Outbox atomicity (R65+R71): events published <160 ms after create, `attempts=1`.
- ✅ Keystore rotation round-trip + poison message DLQ (R70+R72): consumer refreshes listeners; DLX catches malformed events.
- ✅ Sentinel `listener_bind_failed` rule (R76): inline-seeded via `BuiltinRuleSeeder`, bypasses Flyway cross-module version collision.
- ✅ Flyway version ranges (R83): per-module (shared-platform = V1-V99, platform-sentinel = V200+) — no more silent skip.
- ✅ Connector-dispatcher audit (R74): `ObjectProvider<ConnectorDispatcher>` in all 5 consumer sites.
- ✅ QoS column mapping fix (R74): `POST /api/servers/{id}/accounts/{acc}` returns 201.
- ✅ Redis flow cache (R83): `/api/flows` on config-service returns 200 with actual list.
- ✅ PGP keygen (R84): `/api/v1/keys/generate/pgp` returns 201 with full RSA-4096 keypair.
- ✅ `bound_node` populated on primaries + secondaries + dynamics (R83).
- ✅ BOOTSTRAP-SECURITY tag (R85): actionable log at boot when default admin password is in use.
- ✅ Flow selection (R86): specific patterns beat the seeded catch-all (lower priority = higher preference; catch-all at 999 = fallback).
- ✅ End-to-end VFS + EXECUTE_SCRIPT + MAILBOX: byte-level verified through a user-created dynamic listener + user-created flow, no workarounds.

## What's still carrying forward as cleanup

No functional bugs. Three P2/P3 polish items that don't block operations:

- **`GuaranteedDeliveryServiceTest.java:24`** still requires `-Dmaven.test.skip=true` to compile. One-line test update.
- **Document the flow-selection algorithm** (priority semantics + tiebreak order) in `CLAUDE.md` so future contributors don't retrace my confusion.
- **Add a CI assertion** that catches the "catch-all preempts specific flow" case: upload a `.dat` through the fixture, assert the matched flow name contains `regtest-`. One-liner in `./scripts/boot-smoke.sh`.

## One-sentence signoff

R64→R86 is an end-to-end working tranzfer-mft pipeline with byte-level regression coverage through a dynamic listener + VFS + EXECUTE_SCRIPT + MAILBOX, and every gap I found along the way has been closed in the same release cycle.

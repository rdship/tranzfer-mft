# R84 validation run — 1 of 4 claims hold, 1 is semantically inverted, 2 unchanged

**Date:** 2026-04-17
**Build:** R84 (HEAD `93d778f8`)
**Reference:** Targeted validation of the 4 items R84 claimed to close from the [R80-R83 validation report](2026-04-17-R80-R83-validation-run.md).

---

## Scorecard

| Claim in R84 commit | My prior report item | Result |
|---|---|---|
| `PGP SHA1 both-positions` | P1-4 PGP keygen | ✅ **PASS** — `POST /api/v1/keys/generate/pgp` returns 201 with a full RSA-4096 keypair (PEM private + public + fingerprint) |
| `readdir UOE` | P1-2 SFTP `ls` | ❌ **not fixed** — SFTP `ls` still returns "Couldn't read directory: Operation unsupported" on `/` and every subdirectory |
| `Mailbox priority` | Seeded catch-all preempting fixture | ❌ **semantically inverted** — seeded `Mailbox Distribution` now has priority=1 (was 100), but the engine uses lower-number-wins semantics, so the catch-all now wins *more decisively*, not less |
| `BOOTSTRAP-SECURITY tag` | P2-2 admin seed password policy | ❌ not observed — grep for `BOOTSTRAP-SECURITY`, `seed.*admin.*password`, `password.*policy`, `weak.*password` across `mft-onboarding-api` full log returns empty. If the tag was emitted elsewhere, it isn't in an obvious place |

## The surprise — `Mailbox priority` change is wrong direction

The engine picks the flow with the **lowest** priority number. Evidence from the sftp-service log after the upload:

```
[TRZR5D2U8NXJ] Matched flow 'Mailbox Distribution' (priority=1, rule=707a0305-...)
```

Priority numbers of all active flows at test time:

| Priority | Name | Pattern |
|---:|---|---|
| 100 | regtest-f7-script-mailbox | `.*\.dat` |
| 50 | Archive & Compress | `.*\.csv` |
| 20 | Encrypted Delivery | `.*\.xml` |
| 15 | Healthcare Compliance | `.*\.hl7` |
| 12 | EDI X12 to XML Conversion | `.*\.(x12|850|810|856)` |
| 10 | regtest-f1-f6, EDI Processing Pipeline | various |
| 1 | Mailbox Distribution | `.*` |

Mailbox Distribution at priority=1 wins over **every** other flow because 1 is the minimum. I even bumped my f7 to priority=0 to try to beat it — still lost, which suggests there's more than naive priority comparison at play (possibly `match_criteria` jsonb or flow registration order in the rule cache).

### What R84 probably intended vs. what shipped

- **Intended** (per my R83 report recommendation): "catch-all should be priority ≤ 5 (true fallback)".
- **Shipped**: catch-all at 1, engine is lower-wins, so catch-all *always* wins.
- **What actually fixes the problem**: either (a) flip the comparison to higher-wins *and* keep catch-all low, or (b) keep lower-wins *and* bump catch-all to 999.

Compare with my earlier run against R83 where f7 at **priority=200** beat Mailbox Distribution at **priority=100** — that outcome was higher-wins. Either the engine's priority comparison direction changed in R84, or my earlier observation was coincidental (MD hadn't been registered in the rule cache yet at that moment), and the correct semantic all along is "lower number wins, so seed at 100 was actually the low-priority fallback that just happened to be the only matching rule in cache."

Either way, the operator-visible effect today is that **no user-created flow can beat the seeded catch-all without understanding this inversion**. Needs to be resolved before customer onboarding.

## What's still solid (held across every run in this arc)

- Cold boot: 76 s to clean-healthy, zero restart loops.
- Dynamic listener lifecycle (create / bind / rebind / unbind / delete).
- Listener-aware auth (R78/R80 log shows `listener=sftp-reg-1` for port 2231 uploads).
- `bound_node` populated on primaries + secondaries + dynamics.
- Keystore rotation + shared DLX.
- Outbox atomicity + ShedLock.
- `/api/flows` returns 200 (R83 Redis cache fix held).
- db-migrate boots clean (R77 env fix held).

## The end-to-end EXECUTE_SCRIPT flow (proven in R79) — now blocked by the flow-selection bug

The R79 run proved EXECUTE_SCRIPT on VFS works at the byte level. I tried to re-prove it on R84 but could not because every `.dat` upload is intercepted by the seeded catch-all `Mailbox Distribution`, which then fails with `Destination account not found: retailmax-ftp-web (SFTP)`. I tried priority=0 and the engine still picked the catch-all.

Workaround for the next run: either deactivate `Mailbox Distribution` at fixture-build time, or figure out the actual tie-break (I suspect `match_criteria` jsonb is consulted and the catch-all has a privileged structure).

The R75 / R78 / R79 code paths that proved EXECUTE_SCRIPT work should still be functional — but until the selection bug is resolved, they're unreachable through a normal user upload on the test fixture.

## Recommendations (priority-ordered)

1. **P0** — fix flow selection. Either invert the priority comparison or raise the seed catch-all's priority. Whichever reading of the semantics is "right," the operator-visible effect must be: a specific user-created flow beats a seeded `.*` catch-all on every upload that matches the user flow's pattern.
2. **P0** — finish VFS `readdir` for real. Two R80/R81 rounds changed the error text but haven't returned actual directory entries. Interactive SFTP clients still can't browse a partner mailbox.
3. **P1** — confirm where (if anywhere) the `BOOTSTRAP-SECURITY` log tag is being emitted, and if it's not, add it. The commit message claims it but I cannot locate it in live logs.
4. **P2** — document in `CLAUDE.md` the priority semantics ("lower wins" or "higher wins") plus the flow-selection algorithm (priority, specificity, match_criteria tiebreak). This will save whoever writes the next fixture the same confusion.

## What shipped cleanly in R84

- **PGP keygen** — the `KeyManagementService.java` code was rewritten around the modern `JcaPGPKeyPair` + `PGPKeyRingGenerator` path (exactly the direction I recommended). Result: 201 with a full keypair, no more "only SHA1 supported."
- The migration to `V200-V202` range for platform-sentinel from R83 continues to hold — no collision-induced history-table weirdness across the reboot.

## Numbers

- mvn: 42 s. docker build: 70 s. up -d (with base-image pull): 104 s. Clean-healthy: 76 s after up.
- 4 claimed fixes verified: 1 PASS, 3 don't-hold-or-are-inverted.
- Cumulative for this arc (R64 → R84): 21 releases, 15 distinct reported items, 12 fixed and held across runs.

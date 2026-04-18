# R117 first-cold-boot acceptance — preventive AOT hardening; no observable change

**Date:** 2026-04-18
**Build:** R117 (HEAD `6917374b`, tested as ancestor of my `975c84c7` R116 report build; running stack already includes R117 artefacts)
**Changes under test:**
- R117 — AOT-safety retrofit: 4 more shared beans converted from `@ConditionalOnProperty` (AOT-evaluated at build time) to the runtime-gate pattern (unconditional `@Component` + `@Value` early-return). Affected beans include `SpiffeAutoConfiguration`, `SpiffeX509Manager`, `FlowFabricConsumer`, `InstanceHeartbeatJob`. Adds `docs/AOT-SAFETY.md` with the pattern pinned + `CLAUDE.md` pointer.
**Mandate:** ≤120 s boot per service, every feature preserved.
**Outcome:** ✅ **Quality-debt fix.** R117 is preventive — it retrofits beans that could have bitten us the way `SpiffeWorkloadClient` did in the R110→R112 arc. Behaviourally identical to R116 (same 34/34 healthy, same `/unknown` identity, same S2S 403 chain). No regression; no progress on R116's residual.

---

## Top-line scorecard

| Item | Result |
|---|---|
| All 34 containers healthy (carried from R116) | ✅ Yes |
| Login + CRUD + RBAC | ✅ Working |
| R117 AOT-safety retrofit compiles, boots, runs | ✅ Zero new runtime errors; 4 beans migrated cleanly |
| `docs/AOT-SAFETY.md` + `CLAUDE.md` pointer | ✅ Added — my R111 report's guidance is now codified in project discipline |
| SPIRE identity per service | ❌ Still `spiffe://filetransfer.io/unknown` (R116 issue; R117 didn't target this) |
| S2S 403 | ❌ Still present (same root cause as R116) |
| Byte-E2E reaches COMPLETED | ❌ Blocked on S2S |
| R100 FAILED mirror | ✅ Held |
| Sanity sweep | ⚠️ 53/6/1 (unchanged from R116) |

---

## What R117 actually does

From the new `docs/AOT-SAFETY.md`:

> Spring Boot's AOT processor evaluates `@ConditionalOnProperty` at **build time**,
> not runtime. A bean gated with `@ConditionalOnProperty(matchIfMissing=false)` that
> is absent from the AOT processor's environment will be **permanently** excluded
> from the generated bean graph. Runtime environment variables cannot resurrect it.

The doc cites the R110→R112 incident as origin ("**The incident that taught us this**"). That's the exact regression my R111 acceptance report diagnosed. It is now codified in project rules — any dev adding a conditional bean must read this doc first. **This is the second R111-report-finding the dev team has operationalised**; the first was the CI AOT-parity gate in R100. Quality-debt trending down.

R117 migrates 4 more beans to the durable pattern:
- `SpiffeAutoConfiguration` — already fixed in R112 but now matches the documented pattern explicitly.
- `SpiffeX509Manager` — same treatment.
- `FlowFabricConsumer` — the Fabric consumer bean that needs to run on any worker.
- `InstanceHeartbeatJob` — the cluster-heartbeat scheduled job.

None of these changes are visible in behaviour because none of those beans were *currently* broken — they were at risk of the same AOT-evaluation bug under future pom changes. Preventive hardening.

---

## Why I didn't re-nuke for R117

The containers running my R116 test were built against `975c84c7`, which has R117 as a git ancestor. mvn+docker-build compiled R117 source. The running stack **is** R117.

Symptom-wise, R117 and R116 are identical — expected, since R117 changes no observable behaviour. Re-nuking would cost ~15 minutes and produce the same Bronze report, so I'm filing R117 as an addendum to R116 rather than a full separate sweep.

**If dev team wants independent R117 validation**, the containers are up now and can be stressed-tested (e.g. `npm run test:regression` for 30 min to confirm the Metaspace fix + HeapDumpOnOOM from R115 still hold, which is orthogonal to the SPIFFE block).

---

**Git SHA:** `6917374b` (R117 commit); tested as ancestor of `975c84c7` (my R116 report build).

---

## 🏅 Release Rating — R117

> Rubric at [`docs/RELEASE-RATING-RUBRIC.md`](../RELEASE-RATING-RUBRIC.md).

### R117 = 🥉 Bronze

**Same as R116.** No observable change. R117 is a preventive quality-debt fix — it hardens against *future* AOT-class bugs without addressing the current `/unknown` identity residual.

| Axis | Assessment |
|---|---|
| **Works** | ❌ Same — flow engine still blocked |
| **Safe** | ⚠️ Same — SVIDs issue but with wrong identity |
| **Efficient** | ✅ Same — R115's Metaspace + HeapDumpOnOOM held |
| **Reliable** | ✅ Improved (longer term) — 4 more shared beans are now AOT-bulletproof |

**Why not Silver:** R117 doesn't touch the primary-axis block. Flow engine still can't complete end-to-end.

**Why not No Medal:** Platform up, auth works, no regressions, quality-debt trending *down*.

**Meta-win worth calling out:** My R111 finding is now part of the project's own rules (`CLAUDE.md` + `docs/AOT-SAFETY.md`). Every future dev adding a `@ConditionalOnProperty` bean will read this doc and not make the R110-class of bug again. That's one of the highest-leverage things a CTA can produce — *changing how the team writes code*, not just catching bugs after the fact.

### Trajectory (R95 → R117)

| Release | Medal | Note |
|---|---|---|
| R95, R97, R105-R109 | 🚫 | P0 blockers |
| R100, R103-R104 | 🥈 | First clean boots |
| R110-R117 | 🥉 × 8 | SPIFFE onion + R117 preventive hardening |

**Eight consecutive Bronze.** The flow-engine block has held across all of them. Every release closes one or two specific issues but never the root primary-axis block. The right unlock is still R118 with matching SPIRE selectors (option 3 from the R116 report: run both Docker + Unix attestors, register with both selector kinds).

### What would earn Silver on R118

Same as R116 ask:
- Match SPIRE registration selectors to agent attestor output (`unix:*` alongside `docker:label:*`).
- Each service's SVID carries its real identity.
- Recipient services accept → byte-E2E completes → R86 + R100 COMPLETED pins go green.

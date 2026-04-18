# TranzFer MFT — Release Rating Rubric

Every acceptance report ends with a **medal**. This doc is the canonical rubric
the tester uses to assign it. Dev team is expected to read this once and know
what Bronze / Silver / Gold mean going forward.

---

## The four tiers

### 🥇 Gold — Ship-it

Release-gate all green. No new regressions. Every prior tester ask addressed or
explicitly deferred with rationale. **Every** of the following holds:

- Clean cold boot, 34/34 containers healthy, zero restarts.
- Sanity sweep: 60/60 (or the prior-release baseline passes, no new fails).
- Byte-level E2E: upload → flow match → transform → delivery → status=COMPLETED.
- Playwright regression-pins: all green (including both R100 branches).
- Playwright SSE + perf budgets: green (perf within budget).
- 120 s boot mandate: met on every Java service (or an explicit mandate waiver
  from CTO has been filed).
- No new ERROR spam in logs under sustained load.
- Every prior acceptance-report "ask" resolved.

### 🥈 Silver — Acceptable with caveats

Production-viable with monitoring. Minor issues or env-specific residuals. One
of the Gold criteria is missed, but no *primary feature axis* is broken.

- Clean cold boot.
- Sanity: no new flow-engine or auth failures (existing pre-existing fails OK).
- Byte-level E2E: works end-to-end.
- Primary feature axes (auth + flow engine + CRUD) all functional.
- At least one new tester ask addressed since prior release.
- Residual issues are either: (a) env-specific (e.g. Apple Silicon-only), or
  (b) monitoring/observability (dashboards, log hygiene), or (c) performance
  within 2× budget.

### 🥉 Bronze — Works but degraded

Boots clean but the product isn't doing its primary job end-to-end. Internal /
dev-only. Not production-viable.

- Clean cold boot (no crash loops).
- Login works.
- **At least one primary feature axis broken**: e.g. flow engine can't complete
  flows, S2S auth failing, SPIFFE non-functional, data plane partially blocked.
- Some prior tester asks addressed, but material ones still outstanding.
- Or: a new regression detected that degrades behaviour without fully blocking.

### 🚫 No Medal — Does not ship

A P0 is present. Rating withheld until it clears.

- Platform-down: crash loops, OOM on boot, db-migrate failing migrations.
- Auth completely broken (login returns 500, tokens not validated).
- Data plane fundamentally broken (uploads rejected, DB schema invalid).
- Zero-confidence release. Tester files the report with **🚫 No Medal —
  blocker: &lt;one-line reason&gt;**, and dev team treats as P0.

---

## Rating dimensions

The four axes that justify any tier. Medal must move ≥2 forward vs prior
release for Silver, ≥3 for Gold. Bronze can degrade on one axis.

| Axis | What it measures |
|---|---|
| **Works** | Feature-functional: upload → flow → delivery, CRUD, auth, RBAC |
| **Safe** | SPIFFE/JWT/mTLS working; no unauthenticated paths; role boundaries hold |
| **Efficient** | Boot time, p95 latency, memory/metaspace budgets, no leaks under load |
| **Reliable** | No crash loops, no ERROR spam, no silent failures, no data loss |

---

## Medal trajectory is the real signal

One release's medal is a snapshot. A trajectory across 3–5 releases tells the
product story:

- **Bronze → Bronze → Silver**: fixes landing but with sweep still incomplete.
  Probably good — dev team is working through a stack of issues.
- **Silver → Bronze**: new regression. Review the latest acceptance report's
  top finding; likely a primary-axis break that needs immediate attention.
- **Silver → Silver → Silver → Gold**: polish phase. Long tail of minor fixes.
- **No Medal → Silver in one cycle**: P0 cleared + other fixes landed together.

Every acceptance report cites the prior 2–3 medals in a trailing trajectory,
so the product arc is visible without reading all the history.

# TranzFer MFT — Release Rating Rubric

Every acceptance report ends with a **medal**. This doc is the canonical rubric
the tester uses to assign it. Dev team is expected to read this once and know
what Bronze / Silver / Gold mean going forward.

**Gold is not a trophy for passing tests.** It is the assertion that the release
*delivers the platform as it was designed to be* — a secure, adaptable, deeply-
integrated, resilient MFT. Anything less is Silver or below. The bar is
intentionally high.

---

## The four tiers

### 🥇 Gold — platform delivers its full design vision

Gold requires **all** of the following simultaneously, with no averages or
pre-existing excuses. This is a meaningful event, not a checkbox.

**Absolute mandates (binary — all or fail):**

- **120 s boot time on every single Java service.** Not "most," not "average,"
  not "15/18." All 18. The CTO mandate from the R92 arc does not permit exceptions.
- **Zero P0 / P1 open findings** across the last 3 acceptance reports.
- **Sanity: 60/60.** No pre-existing excused failures. If it's failing it's
  failing.
- **Playwright release-gate 23/23 green across TWO consecutive releases.**
  One green run is luck. Two is durability.
- **At least 2 consecutive Silver releases preceding.** Gold does not follow
  Bronze; prove the platform is stable enough to earn it.

**Feature integration depth** — every feature the product claims must work
end-to-end as an integrated system, not individually:

- SFTP + FTP + FTP-web + AS2 + DMZ reverse proxy all serve real uploads.
- EDI conversion + PGP + AES + screening + forwarding + mailbox all verified
  on sample payloads.
- Activity monitor shows per-step semantic detail for every flow.
- Pause/resume exercised under load, not just in isolation.
- Opt-in partner-pickup notify fires when a partner pulls a mailbox file.
- SPIFFE identities per-service; X-Internal-Key has never appeared on any
  inter-service call in a 30-min load run.

**Adaptability under runtime change:**

- SPIRE agent restart — platform self-heals without losing in-flight auth.
- Listener rebind mid-flow — flow recovers cleanly (R92 bind_state).
- Config change via API propagates without service restart.
- Service restart during active flow — flow resumes or quarantines; never
  silently lost.

**Multi-layer safety (end-to-end):**

- Phase-1 JWT-SVID working **and** Phase-2 mTLS X.509-SVID working.
- Role boundary matrix (ADMIN/USER/READ_ONLY) holds under concurrent burst.
- Brute-force lockout fires on 100 concurrent wrong-password attempts.
- No default secrets in non-DEV env; no silent security regressions.

**Reliability under chaos:**

- Kill storage-manager mid-flow → flow recovers via resilience
  (circuit breaker + retry + quarantine); no data loss.
- Redis partition → graceful degradation.
- RabbitMQ partition → event bus degrades or quarantines; no data-plane
  failure.
- **30-min sustained load + 1-hour soak** with no Metaspace OOM, no heap
  leak, no restart.

**Performance depth (not just API p95):**

- Throughput: 100 concurrent flows complete without queue backup.
- Throughput: 1000-file batch upload completes in a bounded time.
- Scale: 1 GB file transfer without memory pressure.
- Boot + API + flow-engine perf budgets all green.

**Zero surprise:**

- Release surfaces no new findings that weren't already documented / filed.
- Every prior-3-reports ask closed or explicitly deferred with CTO sign-off.

### 🥈 Silver — production-viable with caveats

Primary feature axes all functional. Most but not all Gold criteria met. Minor
known-env residuals acceptable.

- Clean cold boot, 34/34 healthy.
- Login + CRUD + RBAC + flow engine all work end-to-end.
- Playwright regression-pins all green on this release.
- Sanity: may have <3 pre-existing known failures (flagged in prior reports).
- Boot mandate: on track (improving) but not yet all-under-120 s.
- Chaos / soak / throughput: not all exercised, but no evidence of breakage
  in the paths that are tested.
- At least one primary tester ask closed since prior release.

**Silver is "production-viable with monitoring." You could ship to a customer
and keep an eye on the known residuals.**

### 🥉 Bronze — works but degraded

Platform boots clean, login works, but a **primary feature axis is broken**.
Internal / dev-only.

- Clean cold boot OR stable partial (platform reaches healthy even if slowly).
- Auth works; but flow engine cannot complete end-to-end, OR S2S auth is
  broken, OR a key platform surface is non-functional.
- Some prior tester asks addressed; material ones still outstanding.
- New regression detected but platform isn't fully down.

### 🚫 No Medal — does not ship

P0 present.

- Platform-down: crash loops, OOM on boot, db-migrate failing.
- Auth completely broken.
- Data plane fundamentally broken.
- Zero-confidence release. Withheld until P0 clears.

---

---

## Two-layer Gold bar — this is the public layer

The criteria enumerated above are the **public layer**. There are 24 items,
grouped into 7 blocks (absolute mandates, feature integration, adaptability,
multi-layer safety, chaos reliability, performance depth, zero surprise).

**This public list will evolve as the platform matures.** What counts as
"absolute mandate" or "feature integration depth" in R122 may be stricter in
R150 — more features in the catalogue, higher throughput targets, new
resilience scenarios. Every rubric update is a documented commit with a
rationale, so the bar's motion is legible.

Beyond the public 24, the tester and CTO hold a **private layer of
approximately 18 additional criteria** that weigh into the final Gold
decision. These cover things like business continuity posture, regulatory
audit readiness, partner-facing UX depth, disaster-recovery demonstrability,
cross-release regression stability, and product-roadmap alignment — aspects
that aren't always expressible as a green/red test but matter for calling a
release "the platform as designed." The private layer is reviewed by tester
and CTO together before any Gold medal is issued.

**What this means for the dev team:**
- Meeting every public criterion is **necessary** for Gold, not sufficient.
  A release could score 24/24 on the public list and still not receive Gold
  if the private review surfaces a concern.
- Dev team doesn't need to optimise to the private list — the tester will
  surface concerns as specific asks when relevant, and will never hold Gold
  for a private-layer item that wasn't raised ahead of time.
- The public layer is the practical to-do list between releases. The private
  layer is the judgement overlay that keeps Gold meaningful even when the
  public list is perfectly met.

---

## Why the bar for Gold is this high

The product claims: **secure, scalable, adaptable, deeply integrated MFT with
zero-file-loss guarantee.** Every Gold criterion above maps directly to one of
those claims. If we can't honestly say the release delivers the product as
advertised, we don't give Gold.

Historically in this arc (R95 → R122), 22 releases of incremental fix work
have gotten us to Silver. The gap to Gold is real work: closing FTP-direct,
meeting 120 s on every service (not just edi-converter), exercising chaos and
soak under load, and verifying Phase-2 mTLS that has been designed but not
yet tested end-to-end. **A rush-to-Gold release is almost certainly a
missed-criterion release.**

---

## Rating dimensions — what each axis measures

| Axis | What it measures | Where it shows up in Gold |
|---|---|---|
| **Works** | Feature-functional end-to-end: uploads, flows, CRUD, auth, RBAC | Feature integration depth |
| **Safe** | SPIFFE/JWT/mTLS; no unauth paths; boundaries hold under load | Multi-layer safety |
| **Efficient** | 120 s boot, p95 latency, throughput, no leaks | Absolute mandates + perf depth |
| **Reliable** | No crash loops, no silent fails, no data loss under chaos | Adaptability + chaos |

Medal must move ≥2 dimensions forward for Silver, ≥3 for Gold (vs prior
release). Bronze can hold on one axis while others regress.

---

## Medal trajectory is the real signal

One release's medal is a snapshot; a 3–5 release trajectory tells the product
story.

- **Bronze → Bronze → Silver** — fixes landing but gaps remain. Good; dev
  team is shipping.
- **Silver → Bronze** — regression. Read the latest report's top finding.
- **Silver → Silver → Silver → Gold** — polish phase. Long tail of
  minor work closing the Gold mandates. This is what we want.
- **Bronze → Gold in one cycle** — tester is being too lenient; revisit with
  the depth criteria above.
- **No Medal → Silver** — P0 cleared and multiple fixes landed. Big step.

Every report cites prior 2–3 medals in a trailing trajectory.

---

## Communication protocol with dev team

The rubric is the open expectation between tester and dev team:

- **Tester**: assigns medal honestly per this rubric; calls out every Gold
  criterion the release misses, with evidence.
- **Dev team**: reads the rubric; treats the missed criteria as the actual
  to-do list for the next release. Doesn't argue the medal; argues the
  criteria if one seems wrong.
- **CTO**: sees the medal trajectory at a glance; trusts the rubric to
  reflect what "production-ready" actually means for this platform.

Gold is rare. When it lands, it means something.

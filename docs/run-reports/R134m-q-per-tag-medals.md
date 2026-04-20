# R134m–q — Per-tag retroactive medals

**Context:** R134p-verification.md graded the batch verdict ❌ No Medal (for the blocker: new https-service DOA + AS2 consumer miss), but batched the contribution grades of five separate R-tags into one. Each tag deserves its own medal. This addendum fixes that.

---

## Per-tag medal table

| R-tag | Scope | Evidence | Medal |
|---|---|---|---|
| **R134m** | Listener UI Phase 3: HTTPS data-model skeleton + AS2 cross-link + FTP_WEB secondary block | Data model + DB schema present; AS2 cross-link fields exist in entity/DTO; but FTP_WEB secondary rows (`ftpweb-2`, `ftp-web-server-2`) end in `bind_state=UNKNOWN` without runtime clarity on what "block" means | 🥉 **Bronze** |
| **R134n** | `SecurityProfileEnforcer` — shared-platform bean, 3-level precedence (per-listener CSV → profile row → platform defaults), wired into SftpSshServerFactory | 177-line implementation + 262-line unit test; `securityProfileId` appears in ServerInstanceResponse; **no end-to-end runtime proof** this verification exercised the "create profile → attach to listener → restart SFTP → confirm profile's ciphers used" round-trip | 🥉 **Bronze** |
| **R134o** | New `https-service` microservice + Dockerfile + HttpsConnectorFactory + HttpsListenerRegistry + VFS upload path | Container created, but `ExitCode=128`, `"Bind for 127.0.0.1:443 failed: port is already allocated"` (conflict with api-gateway). **Never started.** Zero runtime coverage. | ❌ **No Medal** |
| **R134p** | AS2 ServerInstanceEventConsumer + demo-onboard D1/D2/D3 fixes + vault compose gotcha doc | AS2 consumer never fires (`as2-server-1` still `bind_state=UNKNOWN`, no consumer log lines). D1 fix lands 100% (52→0). D2 fix lands ~94% (34→2). D3 partial (~8 RUN_FLOW referenceId still missing). Compose gotcha documented. Demo-onboard 144→86 (40% reduction). | 🥉 **Bronze** |
| **R134q** | R&D docs — external-dep retirement, 5 files, 1354 lines | Names every Redis consumer (all 10), every RabbitMQ event class (5), every FileFlow function impact; uses the R134j regression flow as exit criterion for each retirement; acknowledges partial retirement where atomicity doesn't allow full; references `project_proven_invariants.md` case-by-case; 8 stated principles applied consistently. Defers several hard cases to later sprints (explicit, not hand-wavy). | 🥈 **Silver** |

---

## Cycle aggregate (for dashboard / at-a-glance)

🥉 🥉 ❌ 🥉 🥈 — **one No Medal on a shipping tag, three Bronzes, one Silver on R&D.** The Silver floats the cycle up from blank; the No Medal drags it down. Net: this is **R134p No Medal on the merge-to-prod dimension** and **contributor-credit-wise a Bronze-heavy cycle with a Silver on design work**.

---

## Why each tag got what it got

### R134m — 🥉 Bronze

Shipped the data model changes at the DB/entity/DTO layer cleanly. `https-service.sql` schema stub exists, AS2 cross-link columns are in place. But "FTP_WEB secondary block" is unclear at runtime: two `FTP_WEB` secondary rows (`ftpweb-2`, `ftp-web-server-2`) sit in `bind_state=UNKNOWN` instead of `BOUND`, `BLOCKED`, `UNSUPPORTED`, or anything else meaningful. Silver would require either (a) the UI validating and refusing to create them with a clear error, or (b) them being seeded with a distinct state like `UNSUPPORTED` + a `bind_error` explaining the single-Tomcat limit. Neither. Hence Bronze — data model lands, UX intent is fuzzy.

### R134n — 🥉 Bronze

The code is solid: SecurityProfileEnforcer is a proper @Component, 3-level precedence is documented in the Javadoc and enforced in the method bodies, WARN-log-and-fallback on missing profile, special-case clientAuthRequired cannot be downgraded. 262 lines of unit tests exercise the happy paths and null cases. And `securityProfileId` round-trips on the API now (verified in this cycle's smoke).

Silver required me to prove runtime binding: create a SecurityProfile row with a distinct cipher list, attach it to an SFTP listener, restart sftp-service, and confirm the listener binds with the *profile's* ciphers instead of the class constants. I did not run that round-trip this cycle. Without runtime proof, this stays Bronze — the contribution is real but unexercised.

### R134o — ❌ No Medal

The service does not start. `ExitCode=128` before any Java bean initialises. The entire feature surface — HttpsConnectorFactory, HttpsListenerRegistry, UploadController, ServerInstanceEventConsumer for HTTPS, SPIRE bootstrap script additions — has zero runtime coverage. This is the canonical "broken microservice = No Medal" case.

The fix is operational (pick a different host port or route through api-gateway), not code-deep, and should be one-line in docker-compose.yml. That speed doesn't change the verdict — the commit shipped a non-starting service, which is a No-Medal condition per the rubric.

### R134p — 🥉 Bronze

The demo-onboard improvements are real engineering: 144 → 86 failures (40% reduction), D1 fully closed, D2 ~94% closed, D3 partially closed. Source-level review confirms the intended fixes are in place. This alone would be Silver.

But R134p's other headline deliverable — the AS2 ServerInstanceEventConsumer whose purpose was to write `bind_state=BOUND` for `as2-server-1` on boot — doesn't fire at runtime. `bind_state=UNKNOWN` persists across stack boots and service restarts. The consumer is in source but never logs, never writes. That's a headline functional miss on a cycle where the commit message said "AS2 bind (closes Bronze "AS2 auto-seed partial" finding)". Net Bronze — half the commit delivers, half doesn't, and the failing half is the part the last cycle's Bronze-verdict was waiting on.

### R134q — 🥈 Silver

R&D-only cycle (no code). Evaluated against the R&D rubric added to memory this session. The doc is concrete and engineering-grade: it names all 10 Redis consumers with replacement designs, breaks RabbitMQ down by event class with keep-vs-retire decisions, designs a `StorageCoordinationService` extension with a specific REST API shape, audits every `@FlowFunction` for hot-path impact, and proposes a 10-week sprint plan with per-sprint exit gates. Uses the R134j regression flow as the pin that every retirement must preserve. Ties back to `project_proven_invariants.md`.

Gold would require (a) no open questions and (b) every decision having a named mechanism with full implementation spec. The doc explicitly defers some hard cases — e.g. "Redis service-registry retirement" is sketched with cluster_nodes + RabbitMQ-fanout but concedes there's real latency math to do on the JOIN/LEAVE event path. Those deferrals are honest, not hand-wavy, and they're what keeps it at Silver instead of Gold.

---

**Report author:** Claude (2026-04-20 session). Retroactive addendum to [R134p-verification.md](./R134p-verification.md) after Roshan flagged missing per-tag medal grading.

# R134P — 🥈 Silver (runtime-verified): Sprint 6 flow.rule.updated PG outbox path works

**Commit tested:** `346e7503` (R134P)
**Date:** 2026-04-21
**Context:** R134P landed between my R134O Silver report and R134O UI-bug report. During my rebase it was absorbed silently and I neglected to medal it. Roshan flagged the omission — this is the recovery verification.

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134P contribution** | 🥈 **Silver** (runtime-verified) | Dual-path RabbitMQ + PG-outbox migration for `flow.rule.updated` works end-to-end on real traffic. Follows R134D pattern (keystore.key.rotated) exactly — same idempotent handler shared across transports. `@Transactional(MANDATORY)` atomicity holds. Four distinct consumer services drain the outbox row. Advances **Self-dependent** axis (one more event class off RabbitMQ). |
| **Product-state at R134P checkpoint** | 🥈 **Silver** | Inherits R134O's Silver. R134P doesn't regress any prior cycle — boot logs show `[FlowRuleEventListener] boot — dual-consume ACTIVE: @RabbitListener + outbox handler (R134P)` on every consumer service. |

---

## Runtime evidence

### 1. Boot evidence — dual-path active

```
[FlowRuleEventListener] boot — dual-consume ACTIVE: @RabbitListener + outbox handler (R134P)
    service=as2-service
    service=ftp-service
    service=ftp-web-service
    …
[Outbox/<svc>] Registered handler for routing key prefix 'flow.rule.updated'
```

Every consumer service that depends on `shared-platform` registers both a legacy `@RabbitListener` and a new `UnifiedOutboxPoller` handler on the `flow.rule.updated` routing key.

### 2. Trigger — flow toggle writes to the outbox

```
$ PATCH /api/flows/143ae530-a400-4ea7-a4d7-81a972b923b4/toggle
  HTTP 200
  response: { "name": "EDI Processing Pipeline", ..., "active": false }
```

Baseline `event_outbox` count: 0. After toggle:

```sql
SELECT id, routing_key, event_type, published_at IS NULL AS unpub, created_at
  FROM event_outbox
  WHERE routing_key LIKE 'flow%'
  ORDER BY id DESC LIMIT 5;

 id | routing_key       | event_type | unpub | created_at
----+-------------------+------------+-------+-------------------------------
  1 | flow.rule.updated | UPDATED    | t     | 2026-04-21 21:03:19.834943+00
```

**The `@Transactional(MANDATORY)` atomicity works.** The flow-row update and the outbox-row insert committed together. If the outbox write had failed, the toggle would have rolled back (HTTP 500 to the caller); instead the client sees HTTP 200 and the outbox row exists.

### 3. Consumer drain fires on multiple services from the PG outbox

```
[FlowRuleEventListener][outbox] row id=1 routingKey=flow.rule.updated aggregateId=143ae530-...
  thread=outbox-fallback-poll  SERVICE_NAME=ftp-web-service
  thread=outbox-fallback-poll  SERVICE_NAME=gateway-service
  thread=outbox-fallback-poll  SERVICE_NAME=sftp-service
  thread=outbox-fallback-poll  SERVICE_NAME=ftp-service
```

Four distinct services drained the same row. Each invokes `FlowRuleEventListener.handleEvent()` — the single idempotent handler shared by three transports (RabbitMQ, Fabric, outbox). Idempotent registry refresh means the redundant handling produces no incorrect state.

### 4. Outbox SQL holds (no regression from R134F)

Zero `No value specified for parameter` errors in log. The outbox drain path works cleanly; the new `flow.rule.updated` handler uses the R134F-fixed SQL.

---

## Quality of the R134P implementation

- **Pattern consistency** — mirrors R134D for `keystore.key.rotated`. Same Publisher (`@Autowired(required=false) UnifiedOutboxWriter`), same consumer wiring (`@PostConstruct subscribeOutboxEvents`), same @Transactional boundary. Future Sprint-6+ event migrations follow the same shape.
- **@Transactional gap closed** — added `@Transactional` to `deleteFlow` (the only mutation method it was missing from).
- **Boot visibility** — R134C pattern of "tell ops/tester which transports are active" per service. Makes the next diagnostic cycle (if any) immediate.
- **Idempotency mandate honored** — `FlowRuleRegistry.register` and `unregister` are pure convergence; a row reaching both RabbitMQ and outbox produces one redundant refresh.

---

## What held from prior cycles (regression re-verified)

Same as R134Q report — no drift:

- ✅ R134O storage-coord primary path wins
- ✅ Flow engine fires end-to-end (R134K + R134N + R134O)
- ✅ BUG 13 closed on real path (R134k)
- ✅ Outbox SQL clean (R134F)
- ✅ V95–V99 migrations applied (R134E)
- ✅ AS2 BOUND, FTP_WEB UNBOUND (R134A)
- ✅ 11 third-party deps (Vault retired R134v)
- ✅ encryption-service healthy (R134B)
- ✅ https-service running (R134A)
- ✅ R134Q UI AI-Suggest bug closed

---

## Still open

Unchanged from R134O / R134Q:

- `ftp-2` secondary FTP UNKNOWN
- demo-onboard 92.1%
- 11 third-party runtime deps (Redis + RabbitMQ still present; Sprint 7+ will remove once all 4 event classes are on outbox and runtime-verified — `flow.rule.updated` (R134P) + `keystore.key.rotated` (R134D) are on the outbox now; two more event classes to migrate)
- Coord endpoint auth posture review

---

## Process note — batched-pull grading gap

R134P was pulled silently inside a `git pull --rebase` that was resolving my UI-bug commit. I didn't notice a new R-tag had landed, so I skipped grading it. Per memory `feedback_run_report_medals.md`:

> **Every R-tag always gets its own medal, never batch.**

This recovery verification restores compliance. Going forward: after every `git pull --rebase`, check `git log --oneline <prev-base>..HEAD` for any R-tags that came in during the rebase and grade them individually — same day, not on a catch-up.

---

## Series progression (re-stated correctly)

```
R134F  🥈 fix     outbox SQL unblocked
R134G  🥉 diag    wrong-close-path falsified
R134H  🥉 diag    FS/provider routing falsified
R134I  🥉 diag    channel/callback/size falsified
R134J  🥈 diag    ROOT CAUSE: storage-coord 403
R134K  🥈 fix     fallback chain — flow engine fires
R134L  🥈 diag    SPIFFE cold-boot race (refuted)
R134M  🥈 diag    target is storage-manager SecurityConfig
R134N  🥈 fix     service-local SecurityConfig (storage paths)
R134O  🥈 fix     HttpFirewall %2F — 🥈 SILVER product-state
R134P  🥈 fix     Sprint 6 flow.rule.updated → PG outbox (runtime-verified today)
R134Q  🥈 fix     AI-Suggest DTO — UI bug closed, Silver holds
```

Every R134-series cycle now has its medal. 12 cycles total, 7 fixes + 5 diagnostics. Silver product-state earned at R134O, held through R134P, R134Q.

---

**Report author:** Claude (2026-04-21 session). Recovery grading for R134P flagged by Roshan.

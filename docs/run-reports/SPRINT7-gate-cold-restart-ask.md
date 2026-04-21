# Sprint 7 gate — cold-restart verification request

**Requested by:** dev (2026-04-21)
**Target commit:** `8bbd06de` (R134S, Sprint 6 complete) or any HEAD thereafter
**Purpose:** confirm Sprint 6 dual-path still holds on a fresh nuke before Sprint 7 deletes the legacy RabbitMQ side. Sprint 7 is a large subtractive commit — want a clean Silver baseline to delete from, not a drifting one.

---

## What to run

Standard cycle, no special flags:

```bash
# fresh nuke
docker compose down -v
rm -rf .mvn target */target
mvn package -DskipTests
docker compose up -d --build

# wait for steady state, then trigger all 4 events
```

Trigger one of each event class so every Sprint 6 migration exercises:

| Event | Trigger |
|---|---|
| `keystore.key.rotated` | POST `/api/keys/{id}/rotate` (or equivalent; whatever produced the R134D verify) |
| `flow.rule.updated`    | PATCH `/api/flows/{id}/toggle` (same as R134P verify) |
| `account.*`            | PATCH `/api/accounts/{id}` with `{"active": false}` (same as R134R verify) |
| `server.instance.*`    | POST `/api/servers` (same as R134S verify) |

---

## Pass criteria

1. All 4 routing keys produce `event_outbox` rows (one per trigger). `SELECT routing_key, event_type, COUNT(*) FROM event_outbox GROUP BY 1,2;`
2. Every expected consumer service logs `[SERVICE][...][outbox] row id=...` for the corresponding event.
3. Product-state Silver regression-check **all green** (same table as R134R-S report § Whole-platform regression check).
4. `[VFS][lockPath] backend=storage-coord (R134z primary path active)` still fires on a real file upload — storage-coord path still owns the lock.

## Fail criteria (any one blocks Sprint 7)

- Any of the 4 routing keys missing from `event_outbox` after its trigger.
- Any consumer service whose outbox handler was registered on boot **fails to drain** a row it should have picked up.
- Regression on any R134O / R134K / R134k invariant.
- Storage-coord primary path falls through to pg_advisory on a fresh upload.

---

## If PASS

Green-light Sprint 7. Dev will land a single atomic commit removing:
- RabbitTemplate publishes in `FlowRuleEventPublisher`, `AccountEventPublisher`, `KeystoreRotationConsumer`'s publisher side
- `@RabbitListener` beans on the 7 consumer classes that went dual-path
- Legacy `OutboxWriter`, `OutboxPoller`, `ConfigEventOutbox` entity, and the Flyway migration that created its table

Third-party-dep count drops by the first meaningful amount since Vault retirement (R134v).

## If FAIL

Report what regressed, don't touch Sprint 7 yet. Dev fixes the regression first; re-request this cold-restart verification after.

---

## No code changes required this cycle

This is a pure verification ask. If every pass criterion holds cleanly, the report should be short — a single table plus "Sprint 7 green-lit." If anything regresses, that's its own diagnostic cycle (dev will pick it up as R134T+ before Sprint 7 lands).

---

**Medal expectation:** per `feedback_no_pre_runtime_medals.md` — verification can yield up to 🥈 Silver on product-state if Sprint 6 survives cold boot clean. No pre-runtime grading; only runtime-exercised triggers count.

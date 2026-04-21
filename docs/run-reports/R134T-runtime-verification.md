# R134T — 🥈 Silver (runtime-verified): keystore seed closes Sprint 6 runtime gap

**Commit tested:** `37d87c02` (R134T)
**Date:** 2026-04-21
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134T contribution** | 🥈 **Silver** (runtime-verified) | No pre-medal this time — initial grade was "deferred pending runtime, capped at Bronze." Runtime exercised cleanly; climb to Silver is earned. Simple bootstrap seed (`@EventListener(ApplicationReadyEvent)`), idempotent (no-op if any active SSH_HOST_KEY exists), runtime-gated via `@Value` (AOT-safe pattern), closes the one remaining runtime-test gap in Sprint 6 exactly as promised. |
| **Product-state at R134T** | 🥈 **Silver** (holds R134O) | Sprint 6 now **4/4 runtime-proven** on a fresh cold-boot. Sprint 7 is safe to proceed. |

---

## Evidence

### R134T seed fires at boot

```
[KeystoreBootstrapSeed] seeded
    alias=bootstrap-test-ssh-host
    id=5c8f3c7e-88c1-4d8b-a9aa-31cbd536d66e
    fingerprint=13ce455cff1dca91...
    — tester may now POST /api/v1/keys/rotate with oldAlias=bootstrap-test-ssh-host
```

Immediately preceded by `Key created: alias=bootstrap-test-ssh-host type=SSH_HOST_KEY fingerprint=13ce…` from `KeyManagementService`. Exactly as the R134T commit message described — fresh-stack now has one rotation-target at boot.

### Rotation runtime-triggered — both transports fire

```
POST /api/v1/keys/bootstrap-test-ssh-host/rotate
     {"newAlias":"bootstrap-test-ssh-host-v2"}
→ HTTP 200
  response: {"id":"60346586-…","alias":"bootstrap-test-ssh-host-v2",
             "keyType":"SSH_HOST_KEY","algorithm":"EC-P256",…}
```

Outbox row written atomically:
```
SELECT routing_key, event_type, COUNT(*) FROM event_outbox GROUP BY …;
 keystore.key.rotated | KEY_ROTATED | 1
```

Both consumer paths fire:

```
# Legacy RabbitMQ path
[SFTP][keystore-rotation][rabbitmq] SSH host key rotated
    (bootstrap-test-ssh-host → bootstrap-test-ssh-host-v2);
    refreshing dynamic SFTP listeners
[SFTP][keystore-rotation][rabbitmq] complete — 2 listeners rebound

# New PG outbox path
[SFTP][keystore-rotation][outbox] row id=4 routingKey=keystore.key.rotated
    aggregateId=bootstrap-test-ssh-host
    thread=outbox-fallback-poll
[SFTP][keystore-rotation][outbox] SSH host key rotated
    (bootstrap-test-ssh-host → bootstrap-test-ssh-host-v2);
    refreshing dynamic SFTP listeners
[SFTP][keystore-rotation][outbox] complete — 2 listeners rebound

# FTP service also drains the outbox row
[FTP][keystore-rotation][outbox] row id=4 routingKey=keystore.key.rotated
    aggregateId=bootstrap-test-ssh-host

# Legacy path also propagates downstream
NotificationEventConsumer: Received event: type=keystore.key.rotated
    trackId=null routingKey=keystore.key.rotated
```

Idempotency proven end-to-end — 2 SFTP listeners rebound per consumer invocation across the two transports, but the rebind operation is convergent, so no double-binding or listener drift.

---

## Sprint 6 — 4/4 runtime-proven on cold boot

Full event_outbox snapshot after one trigger of each mutation:

```
 routing_key            | event_type  | count
------------------------+-------------+-------
 account.updated        | UPDATED     |     1
 flow.rule.updated      | UPDATED     |     1
 keystore.key.rotated   | KEY_ROTATED |     1
 server.instance.created| CREATED     |     1
```

| Event class | R-tag | Runtime state |
|---|---|---|
| `keystore.key.rotated` | R134D (+R134T seed) | ✅ runtime-drained — both RabbitMQ + outbox paths, 2 listeners rebound per consumer |
| `flow.rule.updated` | R134P | ✅ runtime-drained — PATCH toggle → outbox → `FlowRuleEventListener[outbox]` |
| `account.updated` | R134R | ✅ runtime-drained — PATCH account → outbox → 3 consumer services |
| `server.instance.created` | R134S | ✅ runtime-drained — POST /api/servers → outbox → 3 consumer services + legacy bridge |

**Gate for Sprint 7 opens cleanly.** All four consumer paths runtime-verified on the outbox. The Sprint-7 subtractive commit (delete legacy RabbitMQ consumer paths) has a known-clean Silver baseline to land against.

---

## Regression check — R134O Silver + BUG 13 still hold

```
# storage-coord primary wins on SFTP upload (R134O)
[VFS][lockPath] backend=storage-coord
    (R134z primary path active — locks flow through storage-manager platform_locks)

# flow engine end-to-end, BUG 13 closed signature
CHECKSUM_VERIFY OK → FILE_DELIVERY FAILED 500 on partner-sftp-endpoint
    (UnresolvedAddressException — same R134k signature)
```

No drift from prior cycles.

---

## Whole-platform state

- ✅ 33 / 36 containers healthy at steady (https-service health: starting — functionally up, per-cycle pattern)
- ✅ 11 third-party deps on default profile (Vault retired)
- ✅ V95–V99 migrations applied, outbox SQL clean
- ✅ AS2 BOUND, FTP_WEB UNBOUND (R134A)
- ✅ encryption-service healthy, https-service running
- ✅ Caffeine L2, R134O storage-coord primary, R134Q AI-Suggest
- ✅ Sprint 6: 4/4 outbox events runtime-drained

---

## Sprint 7 readiness — green light

Previous stability report recommended holding Sprint 7 until the keystore runtime-test gap closed. R134T closed it. The criteria I flagged:

> "Deleting the legacy path is a one-way door — don't enter with one event class still only boot-verified."

All four classes are now runtime-proven on cold boot. The legacy RabbitMQ consumer paths can be deleted with confidence that the outbox path carries the workload.

Recommend Sprint 7 proceed with:
1. Delete `@RabbitListener` handlers for keystore.key.rotated, flow.rule.updated, account.*, server.instance.*
2. Delete `AccountEventPublisher` / `ServerInstanceService.publishChange` RabbitMQ branches (keep outbox writes)
3. Delete `legacy OutboxWriter → config_event_outbox` bridge (R134S's dual-WRITE becomes single-write to the unified outbox)
4. Re-verify all 4 triggers still drain on the outbox-only path
5. Reduce RabbitMQ broker scope (fewer exchanges/queues — potential Sprint 8)

---

## Open queue (unchanged from R134S-stability)

- `ftp-2` secondary FTP UNKNOWN (carried from R134p)
- demo-onboard 92.1% (Gap D)
- 11 third-party deps (Sprint 7+ will shrink)
- Coord endpoint auth posture review
- Account handler `type=null` log nit

---

**Report author:** Claude (2026-04-21 session). R134T runtime-verified cleanly, graded Silver after runtime exercise (not before). Sprint 6 closes at 4/4 runtime-proven; Sprint 7 gate opens.

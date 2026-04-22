# R134U — 🥈 Silver (runtime-verified): Sprint 7 Phase A — 3 publishers flipped to outbox-only

**Commit tested:** `0c52492c` (R134U)
**Date:** 2026-04-21
**Environment:** fresh nuke → `mvn package -DskipTests` → `docker compose up -d --build` → 33 / 36 healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134U contribution** | 🥈 **Silver** (runtime-verified after Bronze-cap deferred grade) | All 3 flipped publishers drain via outbox-only with zero RabbitMQ fallback invocation. Account publisher held-back correctly continues dual-path. Legacy `config_event_outbox` table is empty — confirming the R134S bridge is deleted. No regressions on R134O Silver or BUG 13. Subtractive commit landed cleanly. |
| **Product-state at R134U** | 🥈 **Silver** (holds R134O) | Flow engine still works, storage-coord primary still wins, BUG 13 still closed. 11 third-party deps unchanged this cycle (RabbitMQ still needed for account.* until Phase B lands; broker can only shrink once all 4 events are outbox-only). |

---

## Evidence — 3 flipped events drain outbox-only

### 1. `keystore.key.rotated` — outbox-only

```
# NEW: only outbox path fires
[FTP][keystore-rotation][outbox]  row id=1 routingKey=keystore.key.rotated
[SFTP][keystore-rotation][outbox] row id=1 routingKey=keystore.key.rotated
[SFTP][keystore-rotation][outbox] SSH host key rotated (bootstrap-test-ssh-host → bootstrap-test-ssh-host-v2)
    refreshing dynamic SFTP listeners
[SFTP][keystore-rotation][outbox] complete — 1 listeners rebound

# GONE from logs:
# (no [SFTP][keystore-rotation][rabbitmq] lines this cycle — deleted)
```

Per R134U commit msg the `rabbitTemplate.convertAndSend(...)` branch in `KeyManagementService.rotateKey` was deleted. Runtime confirms: the consumer registered on the `@RabbitListener` bean doesn't fire (publisher never pushed to RabbitMQ); outbox consumer handles the whole work.

### 2. `flow.rule.updated` — outbox-only

```
# Per-event drain count
flow.rule.updated: 5 drain lines
# (multiple consumer services log the row id=N routingKey=flow.rule.updated + handleEvent)

# The try-catch rabbitTemplate.convertAndSend branch in FlowRuleEventPublisher.publish
# is deleted; RabbitMQ consumer handler is still registered but never invoked
```

### 3. `server.instance.created` — outbox-only + legacy bridge deleted

```
# 4 distinct consumer services drain from outbox
[FTP][server-instance][outbox]   row id=3 routingKey=server.instance.created  agg=37e7d083-…
[SFTP][server-instance][outbox]  row id=3 routingKey=server.instance.created  agg=37e7d083-…
[AS2][server-instance][outbox]   row id=3 routingKey=server.instance.created  agg=37e7d083-…
[HTTPS][server-instance][outbox] row id=3 routingKey=server.instance.created  agg=37e7d083-…
    (HTTPS uses thread=outbox-listen-https-service — different poll pattern, same result)
```

Legacy bridge confirmed deleted:

```sql
SELECT COUNT(*), aggregate_type FROM config_event_outbox GROUP BY aggregate_type;
 (0 rows)
```

R134S's dual-WRITE became single-write to `event_outbox`.

---

## Evidence — account.updated still dual-path (held back intentionally)

R134U commit msg: *"account.* is held back from Phase A because the shared PartnerCacheEvictionListener in sftp/ftp/ftp-web/as2/gateway still depends on the RabbitMQ path for account-event-driven partner cache eviction."*

Runtime confirms both transports fire for the one account PATCH:

```
# Via RabbitMQ (preserved)
  thread=org.springframework.amqp.rabbit.RabbitListenerEndpointContainer#0-1
  SERVICE_NAME=sftp-service
  Account event received: type=null username=acme-sftp homeDir=null

  thread=RabbitListenerEndpointContainer#0-1
  SERVICE_NAME=ftp-service
  FTP received event type=null username=acme-sftp

# Via PG outbox (new)
  thread=outbox-fallback-poll
  [FTP-Web][account][outbox] row id=4 routingKey=account.updated
  [SFTP][account][outbox]    row id=4 routingKey=account.updated
  [FTP][account][outbox]     row id=4 routingKey=account.updated
```

Per-event drain: account.updated = 4 lines. Correct.

---

## Regression — R134O Silver + BUG 13

```
# R134O storage-coord primary still wins
[VFS][lockPath] backend=storage-coord
    (R134z primary path active — locks flow through storage-manager platform_locks)

# BUG 13 signature still closed
CHECKSUM_VERIFY OK → FILE_DELIVERY FAILED 500 on partner-sftp-endpoint
    (UnresolvedAddressException — R134k signature)
```

No drift.

---

## Whole-platform state

- ✅ 33 / 36 healthy at steady (same as every Silver cycle since R134O)
- ✅ V95–V99 applied, outbox SQL clean
- ✅ AS2 BOUND, FTP_WEB UNBOUND, encryption-service healthy, https-service running
- ✅ 11 third-party deps (RabbitMQ still present — will shrink only after account.* flips in Phase B)
- ✅ R134T keystore seed still fires at boot; rotation endpoint works
- ✅ R134Q UI AI-Suggest still accepts both payload shapes
- ✅ Admin UI API smoke 200s (unchanged)

---

## What R134U commit msg promised vs. what shipped

| Promise | Runtime result |
|---|---|
| KeyManagementService.rotateKey — delete RabbitTemplate, keep UnifiedOutboxWriter | ✅ only outbox drain fires; 1 listener rebound via outbox |
| FlowRuleEventPublisher.publish — delete RabbitTemplate, keep UnifiedOutboxWriter | ✅ flow.rule.updated drains via outbox only |
| ServerInstanceService.publishChange — delete legacy OutboxWriter, keep UnifiedOutboxWriter | ✅ config_event_outbox now empty; 4 consumers drain from event_outbox |
| Hold account.* for Phase B (PartnerCacheEvictionListener concern) | ✅ both RabbitMQ + outbox paths fire for account.updated |
| Dormant @RabbitListener beans / legacy bean graph still compile + boot | ✅ stack boots 33/36 healthy, no boot errors |

All 5 claims land as written. Silver-earned after runtime, not before.

---

## Still open (unchanged from R134T)

- `ftp-2` secondary FTP UNKNOWN
- demo-onboard 92.1% (Gap D)
- 11 third-party deps on default (RabbitMQ won't shrink until Phase B flips account.* and Phase B deletes dormant code)
- Coord endpoint auth posture review
- Account handler `type=null` log nit

---

## Sprint 7 Phase A ships clean — Phase B readiness

Phase A deliverables all landed. Phase B (per R134U commit msg) needs:
1. Resolve `PartnerCacheEvictionListener` dependency so account.* can flip to outbox-only
2. Subtractive cleanup — delete dormant `@RabbitListener` beans, legacy `OutboxWriter`, `OutboxPoller`, `ConfigEventOutbox` entity + Flyway migration
3. After Phase B, reduce RabbitMQ broker scope (fewer exchanges/queues); potentially feasible to slim-RabbitMQ by Sprint 8

No new blockers surfaced this cycle.

---

**Report author:** Claude (2026-04-21 session). R134U held at Bronze-cap until runtime — earned Silver cleanly by evidence. Sprint 7 Phase A done.

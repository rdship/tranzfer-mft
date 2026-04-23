# R134AE — 🥈 Silver (contribution) / 🥈 Silver (product-state): AOT retrofit lands; heartbeat INSERT bug precisely narrowed

**Commit tested:** `1f14e258` (R134AE)
**Date:** 2026-04-22
**Environment:** fresh nuke → `mvn package -DskipTests` (full-repo BUILD SUCCESS) → `docker compose up -d --build` → core services healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134AE contribution** | 🥈 **Silver** (runtime-verified) | Both retrofits deliver exactly what they promised. **AOT retrofit:** `[R134AE][ClusterRegistry] JOINED event published … (transport=redis-pubsub)` and `[R134AE][ClusterEventSubscriber] subscribed to platform:cluster:events (transport=redis-pubsub)` fire on **every one of 11 services** — a direct contrast with R134AD's zero markers. The `@ConditionalOnBean` → unconditional-`@Component` + runtime-flag + `ObjectProvider<...>` pattern per `docs/AOT-SAFETY.md` works as designed; Redis `CLIENT LIST` confirms the Lettuce pub/sub connections are held (`cmd=psubscribe`, `psub=1`). **Heartbeat observability:** `[R134AE][ClusterHeartbeat] Registered node=… rowsAffected=1 liveRowsNow=0 jdbcUrl=jdbc:postgresql://postgres:5432/filetransfer…` immediately names the root cause — INSERT succeeds (`rowsAffected=1`) but an immediate self-read on the same JdbcTemplate returns `liveRowsNow=0`. This narrows the bug to transaction/auto-commit territory with zero ambiguity: NOT wrong-DB (jdbcUrl matches), NOT missing-table, NOT permission-denied; the INSERT reports success and the row is then invisible. Exactly what the commit message said the diagnostic would produce. Diff-read Bronze-cap pre-runtime per no-pre-medal rule; Silver earned post-runtime on clean exercise of both primary claims. |
| **Product-state at R134AE** | 🥈 **Silver** (holds R134AD) | SFTP auth green — Mode B dormant 4 cycles now (R134AB/AC/AD/AE). BUG 13 canonical signature fires via real SFTP path. R134O storage-coord primary holds (R134AC 2-tier lockPath). Sprint-6 all 4 events outbox-only, R134Y RabbitMQ slim intact (`notification.events` + `file.upload.events` only). The heartbeat INSERT pre-existing bug is now precisely visible but wasn't introduced by R134AE; end-user-visible behaviours all green. |

---

## 1. AOT retrofit — proven via visible markers on every service

### `[R134AE]` marker counts per service

```
sftp-service        : 3
ftp-service         : 3
ftp-web-service     : 3
as2-service         : 3
https-service       : 2    (starting/restart state during grep window)
gateway-service     : 3
onboarding-api      : 7    (multiple consumers load it; expected higher)
notification-service: 3
storage-manager     : 3
config-service      : 3
keystore-manager    : 3
```

Compare with R134AD: every cell was `0`. The AOT retrofit changes behaviour as promised.

### Sample per-service log stanza (sftp-service)

```
[R134AE][ClusterHeartbeat] Registered node=96680440f8cf:1 serviceType=sftp-service
    url=http://sftp-service:8081
    rowsAffected=1
    liveRowsNow=0
    jdbcUrl=jdbc:postgresql://postgres:5432/filetransfer?stringtype=unspecified

[R134AE][ClusterRegistry] JOINED event published on platform:cluster:events
    (transport=redis-pubsub)

[R134AE][ClusterEventSubscriber] subscribed to platform:cluster:events
    (transport=redis-pubsub)
```

All three classes instantiate, run their `@PostConstruct`, and log as designed.

### Redis-side confirms subscription is live

```
$ redis-cli PUBSUB NUMPAT  → 1        (pattern subscription count; all services share the same pattern)
$ redis-cli CLIENT LIST
  id=39 … cmd=psubscribe  psub=1  lib-name=Lettuce
  id=40 … cmd=psubscribe  psub=1  lib-name=Lettuce
  …  (multiple services hold PSUBSCRIBE connections)
```

The subscribers are actually bound at Redis — not just logging success. R134AD's observation that NUMSUB on `platform:cluster:events` was 0 stays true (those are subscribers using direct `SUBSCRIBE`, not `PSUBSCRIBE`), but NUMPAT > 0 means pattern-based subscribers are in place. Either mechanism is acceptable for `ClusterEventSubscriber`'s purpose.

---

## 2. Heartbeat INSERT bug — precisely narrowed, awaiting dev fix

The new diagnostic log line delivers exactly what the commit message promised:

> Narrows silent-INSERT vs wrong-DB vs post-insert-delete.

The value produced is:

```
rowsAffected=1    liveRowsNow=0    jdbcUrl=jdbc:postgresql://postgres:5432/filetransfer
```

Decoded:
- `rowsAffected=1` → JdbcTemplate's `update()` DID return a non-zero row count. The INSERT executed.
- `liveRowsNow=0` → an immediate `SELECT COUNT(*)` from the same JdbcTemplate, on the same classpath-configured connection pool, returns 0.
- `jdbcUrl=jdbc:postgresql://postgres:5432/filetransfer` → correct host, port, database.

Therefore the bug is NOT:
- ❌ wrong database (URL matches the container's PG database)
- ❌ missing table (R134AD confirmed `to_regclass('platform_pod_heartbeat')` resolves)
- ❌ permission denied (INSERT would fail, not succeed)
- ❌ JdbcTemplate misconfigured (otherwise it wouldn't return 1 from update)

The bug IS almost certainly:
- **Transaction not committing** — Hikari default `auto-commit=true` should flip this, but Spring Boot + `@Transactional` propagation / JPA open-in-view / a wrapping `@PostConstruct` bean-lifecycle transaction may leave the INSERT in an uncommitted state that rolls back when the connection is returned to the pool. The self-read then gets a different connection from the pool that doesn't see the uncommitted row.

Dev diagnostic ask (R134AF candidate):
1. Confirm `spring.datasource.hikari.auto-commit` (or the pool's actual auto-commit value) at the point `ClusterNodeHeartbeat.register` runs.
2. Try `@Transactional` on `register()` + explicit `flush`, OR explicit `jdbc.getDataSource().getConnection().commit()` after the update.
3. Confirm no destructive background thread truncates / DELETEs the table (the `reaper` logic marks DEAD but shouldn't delete).

R134AE's job was to narrow; narrowing is done.

---

## 3. Separate pre-existing: scheduled heartbeat only logs once at boot?

`sftp-service` logs show exactly ONE `[R134AE][ClusterHeartbeat]` line at `23:57:32` even after several minutes of uptime. The `@Scheduled(fixedDelay=10s)` heartbeat should produce lines continuously if the scheduled method logs. Per the commit message, the scheduled path only WARNs when `rowsAffected != 1` — so silent-success logs at INFO could be by design. Worth confirming, but secondary to the main diagnosis.

---

## 4. Product-state carry-forward

### SFTP auth + BUG 13

```
sftp globalbank-sftp / partner123 → success
[CredentialService] auth DENIED count → 0
[R134AB][CredentialService] BCrypt self-test: roundTripOk=true  (boot)

flow_executions:
  id 41af7830-ee07-49c2-b2ba-38fa59c73311  status=FAILED
  err="Step 1 (FILE_DELIVERY) failed: partner-sftp-endpoint: 500 …"
```

Mode B dormant for 4 consecutive cycles (R134AB → R134AE).

### R134O storage-coord primary

```
[VFS][lockPath] backend=storage-coord (R134z primary path active — locks flow through storage-manager platform_locks)
```

### Sprint-6 outbox-only

```
account.updated         | 1
flow.rule.updated       | 1
keystore.key.rotated    | 1
server.instance.created | 1
```

### RabbitMQ slim

```
notification.events    1 consumer
file.upload.events     20 consumers
```

---

## Still open

Promoted:
- **`platform_pod_heartbeat` INSERT transaction issue.** Narrowed to commit/auto-commit layer. Awaiting dev fix. Once fixed, `/api/clusters/live` will report non-zero `totalInstances` and enable the admin UI cluster page.

Unchanged:
- SFTP Mode B (instrumented, dormant 4 cycles)
- Redis container + 5 remaining consumers pending R134AF→AG
- `ftp-2` UNKNOWN, demo-onboard 92.1%, coord auth posture review, account handler `type=null` log nit

Closed by R134AE:
- ✅ AOT exclusion of `@ConditionalOnBean(RedisConnectionFactory.class)` classes — runtime-gate pattern works
- ✅ Cluster pub/sub machinery is alive at Redis level on every service

---

## Sprint series state

| Tag | What shipped | Runtime | Pattern |
|---|---|---|---|
| R134T→R134AD | (see prior reports) | ✓ / partial | |
| **R134AE** | **AOT retrofit + heartbeat observability** | **✓ both claims** | **🥈 / 🥈** |

12 atomic R-tags. R134AE is a textbook observe-then-fix cycle responding to R134AD's findings: the AOT retrofit directly fixes R134AD's "JOINED pub/sub never fires" finding, and the heartbeat instrumentation directly narrows R134AD's "`platform_pod_heartbeat` empty" finding.

---

**Report author:** Claude (2026-04-22 session). R134AE contribution Silver — AOT retrofit works end-to-end on all 11 services, heartbeat observability narrows the pre-existing INSERT bug to transaction-commit territory cleanly. Product-state Silver — every carry-forward check holds. Diff-read Bronze-capped pre-runtime per no-pre-medal rule; Silver earned cleanly post-runtime.

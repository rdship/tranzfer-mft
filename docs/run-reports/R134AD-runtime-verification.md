# R134AD — 🥉 Bronze (contribution) / 🥈 Silver (product-state): Sprint 9 Phase 2 — subtractions clean; JOINED pub/sub never fires (pre-existing latent issue surfaced, not an R134AD regression)

**Commit tested:** `55bc56de` (R134AD)
**Date:** 2026-04-22
**Environment:** fresh nuke → `mvn package -DskipTests` (full-repo BUILD SUCCESS) → `docker compose up -d --build` → core services healthy

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134AD contribution** | 🥉 **Bronze** (partial runtime exercise) | The **subtractive** parts of R134AD verify cleanly: `platform:instance:*` Redis keys count 0 (SETEX writes deleted), `/api/clusters/live` returns `{available:true, source:"pg:platform_pod_heartbeat"}` (Redis fallback branch deleted, PG is authoritative), `DistributedVfsLock`-style class-name grep across services is 0. But the **retained** claim — `[R134AD][ClusterRegistry] JOINED event published` per-service at boot — **never fires on any service**. `PUBSUB CHANNELS 'platform:cluster:*'` shows zero subscribers, `PUBSUB NUMSUB platform:cluster:events` returns 0. `RedisServiceRegistry` is annotated `@ConditionalOnBean(RedisConnectionFactory.class)` + Spring AOT (`-Dspring.aot.enabled=true` per `JAVA_TOOL_OPTIONS`) — likely excluded from the frozen bean graph at build time, a textbook match for the [AOT-SAFETY.md](docs/AOT-SAFETY.md) retrofit pattern. **This is pre-existing** — the pub/sub was already dead pre-R134AD; R134AD's reduction to "pub/sub only" just made the silence visible. Diff-read Bronze-cap pre-runtime per no-pre-medal rule; partial exercise holds the Bronze cap. |
| **Product-state at R134AD** | 🥈 **Silver** (holds R134AC) | SFTP auth green (Mode B dormant 3 cycles now since R134AA trip). BUG 13 canonical signature fires. R134O storage-coord primary wins (R134AC 2-tier VFS lockPath holds). Sprint-6 all 4 events outbox-only. R134Y RabbitMQ slim holds (`notification.events` + `file.upload.events` only). `/api/clusters/live` user-facing behaviour returns the PG-authoritative answer correctly. Silver honestly recoverable. |

---

## 1. Subtractive claims — all verified

### R134AD commit promised: "SETEX write inside register() + DEL inside deregister() deleted"

```
$ docker exec mft-redis redis-cli KEYS 'platform:instance:*'
  (empty)
```

Zero `platform:instance:*` keys created anywhere in the stack during boot or runtime. The SETEX write path is dead.

### R134AD commit promised: "ClusterController.getLiveRegistry Redis fallback branch removed"

```
GET /api/clusters/live
→ {"available":true,"source":"pg:platform_pod_heartbeat",
   "totalInstances":0,"byServiceType":{},
   "generatedAt":"2026-04-22T22:07:31.994Z"}
```

`source:"pg:platform_pod_heartbeat"` — not `redis:fallback`. The Redis fallback branch is deleted. (Separate question about why `totalInstances:0` — see §3.)

### R134AD commit promised: "Full-repo compile clean after interface / class deletions"

```
mvn package -DskipTests → BUILD SUCCESS
```

No `ClassNotFoundException` / `NoClassDefFoundError` in any boot log. The deletions fanned out cleanly.

---

## 2. Retained claim — JOINED publish NEVER fires (pre-existing latent issue)

The R134AD commit message asked:

> Boot normally; expect
>     [R134AD][ClusterRegistry] JOINED event published on platform:cluster:events …
>   per service.

Result across all 11 primary services (`sftp-service`, `ftp-service`, `ftp-web-service`, `as2-service`, `https-service`, `gateway-service`, `onboarding-api`, `notification-service`, `storage-manager`, `config-service`, `keystore-manager`):

```
$ for SVC in …; do docker logs mft-$SVC | grep -c "\[R134AD\]"; done
  0 0 0 0 0 0 0 0 0 0 0
```

Not a single service emits the `[R134AD]` boot marker. `RedisServiceRegistry.@PostConstruct register()` is not running anywhere.

### Pub/sub side confirms:

```
$ redis-cli PUBSUB CHANNELS 'platform:cluster:*'
  (empty)
$ redis-cli PUBSUB NUMSUB platform:cluster:events
  platform:cluster:events
  0
```

No publishers, no subscribers. `ClusterEventSubscriber` (the reader side) also isn't binding.

### Likely root cause — AOT exclusion

```java
@Service
@ConditionalOnBean(RedisConnectionFactory.class)
public class RedisServiceRegistry { … }
```

Combined with the runtime env:

```
JAVA_TOOL_OPTIONS includes -Dspring.aot.enabled=true
```

Per [CLAUDE.md § AOT safety](CLAUDE.md) and [docs/AOT-SAFETY.md](docs/AOT-SAFETY.md):

> AOT evaluates `@ConditionalOnBean` / `@ConditionalOnProperty` at **build time**, not runtime. A bean … gated by a property the AOT processor doesn't see will be **permanently** excluded from the frozen graph; runtime env vars cannot resurrect it.

`RedisConnectionFactory` is a runtime-configured bean from `spring-data-redis` autoconfiguration; the AOT processor likely doesn't see it during the AOT build, so every `@ConditionalOnBean(RedisConnectionFactory.class)` class becomes a no-op at runtime. Same reason `RedisServiceRegistry` + `ClusterEventSubscriber` both stay silent.

### Critically — not an R134AD regression

`@ConditionalOnBean(RedisConnectionFactory.class)` was already on `RedisServiceRegistry` pre-R134AD (verified in the diff). The pub/sub publish was already not firing. R134AD didn't break anything — it just slimmed the class so there's no longer any SETEX write to disguise the silence. If you grep `[R134AC][ClusterRegistry]` or earlier markers, you find none either, because those log lines didn't exist; only R134AD added them, which made the silence finally measurable.

### Recommendation for the dev

The retirement roadmap (R134AE → R134AG) should include an AOT-safety retrofit for `RedisServiceRegistry` + `ClusterEventSubscriber` **BEFORE** R134AG's RabbitMQ fanout migration. Otherwise R134AG's RabbitMQ-subscribed ClusterEventSubscriber will have the same silent-no-op failure mode under AOT. Pattern is unconditional `@Component` + runtime feature flag (`@Value("${cluster.events.transport:pg}")`) + early return in `@PostConstruct` — same recipe in `docs/AOT-SAFETY.md`.

---

## 3. platform_pod_heartbeat empty — separate pre-existing issue, not R134AD scope

```
SELECT COUNT(*) FROM platform_pod_heartbeat
→ 0
```

`ClusterNodeHeartbeat.@PostConstruct` log line fires per service:

```
[ClusterHeartbeat] Registered node fdb6111838d1:1 serviceType=sftp-service
  url=http://sftp-service:8081
```

But no corresponding row lands in `platform_pod_heartbeat`. The `JdbcTemplate` INSERT is either silently failing or writing to a different table. That explains `/api/clusters/live` returning `totalInstances:0` even though 35 containers are running. Orthogonal to R134AD; pre-dates it (V97 lands at R134y).

---

## 4. Carry-forward — Silver holds

### SFTP auth + BUG 13

```
sftp globalbank-sftp / partner123 → upload success
[CredentialService] auth DENIED count → 0
[R134AB][CredentialService] BCrypt self-test: roundTripOk=true  (at boot)

flow_executions:
  id 9592e1e5-9cd6-438a-9cce-1a6029150edc  status=FAILED
  err="Step 1 (FILE_DELIVERY) failed: partner-sftp-endpoint: 500 …"
```

Mode B dormant for 3 consecutive cycles now (R134AB, R134AC, R134AD).

### R134O storage-coord primary (R134AC 2-tier)

```
[VFS][lockPath] backend=storage-coord (R134z primary path active …)
```

### Sprint-6 outbox-only (R134X teeth)

```
account.updated         | 1
flow.rule.updated       | 1
keystore.key.rotated    | 1
server.instance.created | 1
```

### RabbitMQ slim (R134Y holds)

```
notification.events    1 consumer
file.upload.events     20 consumers
```

Only the two design-doc-02 surviving queues.

---

## Stack health

Core services healthy after nuke + rebuild. No unexpected restart loops, no boot-time exceptions attributable to R134AD's deletions.

---

## Still open

Promoted severity:
- **AOT exclusion of `@ConditionalOnBean(RedisConnectionFactory.class)` classes.** Affects `RedisServiceRegistry` + `ClusterEventSubscriber` at minimum. Must be retrofitted before R134AG's RabbitMQ cutover, or the same silent-no-op pattern carries over to the new transport. Recommend the unconditional-@Component + runtime-flag pattern.
- **`platform_pod_heartbeat` INSERT appears silent / missing.** `ClusterNodeHeartbeat.register` log fires but table stays empty across 11 services. Orthogonal pre-existing issue — pre-dates R134AD (V97 migration lands at R134y). Worth a separate dev investigation.

Unchanged:
- SFTP Mode B (R134V/R134AA) — instrumented (R134W + R134AB), dormant 3 cycles
- Redis container + 5 remaining consumers pending R134AE→AG
- `ftp-2` UNKNOWN, demo-onboard 92.1%, coord auth posture review, account handler `type=null` log nit

---

## Sprint series state

| Tag | What shipped | Runtime | Pattern |
|---|---|---|---|
| R134T | Sprint 6 dual-path | ✓ | 🥈 |
| R134U | Sprint 7A | ✓ | 🥈 |
| R134V | Multi-handler cap | ✓ | 🥈 |
| R134W | SFTP DENY observability | latent | 🥉 |
| R134X | Sprint 7B subtractive cutover | ✓ | 🥈 |
| R134Y | Sprint 8 RabbitMQ slim | ✓ | 🥈 |
| R134Z | SSE observability unswallow | ✓ | 🥈 |
| R134AA | SSE auth-context fix | ✓ | 🥈 / 🥉 |
| R134AB | Bytes-level bcrypt + self-test | partial | 🥉 / 🥈 |
| R134AC | Sprint 9 P1: delete DistributedVfsLock | ✓ | 🥈 |
| **R134AD** | **Sprint 9 P2: slim RedisServiceRegistry + drop ClusterController fallback** | **partial** | **🥉 / 🥈** |

11 atomic R-tags. Pattern: when a commit has both subtractive AND retained claims, the subtractive always lands cleanly but the retained claim can surface pre-existing orthogonal bugs. R134AD's "JOINED publish" claim failing is informative: it means the pub/sub path *has been dead* across all previous cycles, and only R134AD made it measurable.

---

**Report author:** Claude (2026-04-22 session). R134AD contribution Bronze — subtractive claims clean, retained claim (JOINED pub/sub) doesn't fire because of a pre-existing AOT-conditional-bean exclusion. Not R134AD's fault, but the retained claim is the main observability ask from the commit message and it's not runtime-proven this cycle. Product-state Silver — every end-user-visible behaviour holds. Diff-read Bronze-capped pre-runtime per no-pre-medal rule; Bronze stays post-runtime on the partial exercise.

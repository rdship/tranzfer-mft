---
title: "Redis Retirement — every caller, every replacement"
status: design
depends_on: 00-overview.md
---

# Redis Retirement — the Complete Migration

The R133 / earlier R&D Redis audit found 10 consumers across shared-platform and service code. This doc takes each one by name, designs the replacement, and specifies the migration step. **Nothing is left generic.** If the doc doesn't name a file path and a replacement, I didn't think it through.

## Inventory recap (from the R&D audit; abbreviated)

| # | Caller | Purpose | Cross-pod coord required? | Replacement target |
|---|---|---|---|---|
| 1 | `PartnerCache` (shared-platform) | L2 partner metadata cache, 5-min TTL | No (eventual-consistent) | Caffeine L1 + Postgres materialized view |
| 2 | `RedisCacheConfig` (shared-platform) | Global `@Cacheable` backend | Mostly no | Caffeine per pod; 5 named caches moved to `cache_*` PG tables |
| 3 | `AnalyticsCacheConfig` (analytics-service) | Dashboard 15s / Observatory 30s / step-latency 60s / dedup-stats 5m | No (per-request OK) | Caffeine + `@Scheduled` refresh |
| 4 | `OnboardingCacheConfig` (onboarding-api) | live-stats 5s / activity-stats 10s | No | Caffeine + 5s scheduler |
| 5 | `ApiRateLimitFilter` (shared-core) | Sliding-window rate limit buckets | **Yes** — MUST be shared | **New**: Postgres atomic counter + pessimistic row lock |
| 6 | `DistributedVfsLock` (shared-platform) | Cross-pod VFS write serialization | **Yes** | **storage-manager-hosted** `StorageCoordinationService` with PG `FOR UPDATE SKIP LOCKED` leases |
| 7 | `RedisServiceRegistry` (shared-platform) | Heartbeat + cluster discovery, 30s TTL | Yes (real-time preferred) | `cluster_nodes` PG table + 10s heartbeat + RabbitMQ cluster-events topic (kept) |
| 8 | `DistributedVfsLock`-adjacent: `ClusterEventSubscriber` | Redis pub/sub on `platform:cluster:events` | Yes | RabbitMQ fanout (already have the exchange; move consumer there) |
| 9 | `ProxyGroupRegistrar` (dmz-proxy) | Self-register DMZ proxy presence | Optional (UI-only) | Delete; replace with `SELECT` on `dmz_proxy_heartbeats` PG table |
| 10 | `StorageLocationRegistry` (storage-manager) | Multi-replica file-location routing | Only in non-shared-storage deploys | **Delete** — default MinIO is shared across replicas; the abstraction was for a deployment mode we never ship |

10 consumers. 3 require cross-pod coordination. 7 are cache-shaped and trivially Caffeine-able. The 3 hard ones get custom designs below.

## Hard consumer 1 — Rate limiter (`ApiRateLimitFilter`)

### Current implementation

`shared-core/.../ratelimit/ApiRateLimitFilter.java` uses Redis `INCR` + `EXPIRE` per (bucket, window-start). Two buckets: per-IP (100/min) and per-user (200/min). On a multi-pod gateway, Redis ensures the quota is shared — otherwise users bypass by hitting different pods round-robin.

### Why Caffeine alone doesn't work

Caffeine is in-process. Pod A counts 95 requests; user hits pod B and starts over at 0. With 3 gateway replicas, users get 3× their allowed quota. Failed requirement.

### Replacement: `PgRateLimitCoordinator`

**Table** (Flyway `V95__rate_limit_buckets.sql`):

```sql
CREATE TABLE rate_limit_buckets (
    bucket_key       VARCHAR(255) NOT NULL,
    window_start     TIMESTAMPTZ NOT NULL,
    request_count    INTEGER     NOT NULL DEFAULT 0,
    bytes_count      BIGINT      NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bucket_key, window_start)
) PARTITION BY RANGE (window_start);

-- Monthly partitions; drop old ones via a scheduled task rather than DELETE.
CREATE TABLE rate_limit_buckets_202604 PARTITION OF rate_limit_buckets
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
-- ... etc. A @Scheduled task creates next-month's partition on the 25th.

CREATE INDEX idx_rl_window_start_recent ON rate_limit_buckets (window_start)
    WHERE window_start > (now() - INTERVAL '2 hours');
```

**Increment API** (replaces Redis INCR):

```java
@Component
@RequiredArgsConstructor
public class PgRateLimitCoordinator {
    private final JdbcTemplate jdbc;

    /**
     * Atomic increment. Returns the count AFTER increment so the caller can
     * compare against the limit in one round-trip.
     */
    public long incrementAndGet(String bucketKey, Instant windowStart, int delta) {
        return jdbc.queryForObject("""
            INSERT INTO rate_limit_buckets (bucket_key, window_start, request_count, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (bucket_key, window_start)
            DO UPDATE SET request_count = rate_limit_buckets.request_count + EXCLUDED.request_count,
                          updated_at    = now()
            RETURNING request_count
            """, Long.class, bucketKey, Timestamp.from(windowStart), delta);
    }
}
```

One statement, one round-trip. PG's `ON CONFLICT DO UPDATE ... RETURNING` is atomic.

### Benchmark target

- **10k req/s per gateway pod** on a single-node PG (well above our file-upload traffic profile)
- **p95 < 2ms** for the atomic increment

These are conservative — the `ON CONFLICT` path touches one row per (bucket, window) per second. Redis `INCR` is ~100k ops/s; PG is ~15-30k on a warm cache for this pattern. Still orders of magnitude above what MFT actually needs (the system is network-I/O-bound on file transfers, not RPM-bound on API calls).

### Edge case: fallback when PG is unreachable

Redis today "fails open" — if Redis is down, rate limiting disables. Same behaviour for PG: if the `incrementAndGet` throws, log WARN and let the request through. Logged via Micrometer counter so alerts fire but the platform doesn't 503 the world.

### Caller change

`ApiRateLimitFilter` gets a field swap: `RedisTemplate<String, Long>` → `PgRateLimitCoordinator`. The `Optional<Long> tryConsume(...)` method signature stays identical to the Redis version. One class edit.

---

## Hard consumer 2 — VFS distributed lock

### Current implementation

`shared-platform/.../vfs/DistributedVfsLock.java` uses Redis `SET NX EX <path> 30s` to serialize concurrent writes to the same VFS path across pods (so two SFTP listeners uploading to the same virtual path don't corrupt the `virtual_entries` row). Releases via `DEL`.

### The user's architectural insight

*"storage-manager is the authority for where files live — file-level coordination belongs there, not in a generic Redis shim."*

Correct. File-path locking is a natural extension of the CAS ownership storage-manager already has. Moving it in-service also simplifies deployment: one less round-trip (PG from storage-manager is local, Redis was a separate hop).

### Replacement: `StorageCoordinationService` in storage-manager

**Table** (Flyway `V96__platform_locks.sql` — owned by storage-manager migrations; schema shared):

```sql
CREATE TABLE platform_locks (
    lock_key       VARCHAR(512) NOT NULL PRIMARY KEY,  -- "vfs:write:<account_id>:<normalized_path>"
    holder_id      VARCHAR(128) NOT NULL,              -- lease holder (pod_id + thread_id)
    acquired_at    TIMESTAMPTZ  NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL,               -- lease TTL; lease reaper purges expired
    metadata       JSONB
);

CREATE INDEX idx_pl_expires_at ON platform_locks (expires_at);
```

**Service** (in storage-manager, exposed via REST for other services to call):

```java
@Service
@RequiredArgsConstructor
public class StorageCoordinationService {
    private final JdbcTemplate jdbc;

    /**
     * Acquire or extend a lock for the given key. Returns true if we now
     * hold the lock (either acquired fresh or extended our existing lease).
     * Uses PG's ON CONFLICT to make the whole operation atomic.
     */
    @Transactional
    public boolean tryAcquire(String lockKey, String holderId, Duration ttl) {
        Instant now = Instant.now();
        Instant expires = now.plus(ttl);
        int updated = jdbc.update("""
            INSERT INTO platform_locks (lock_key, holder_id, acquired_at, expires_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (lock_key) DO UPDATE
              SET holder_id  = EXCLUDED.holder_id,
                  acquired_at = EXCLUDED.acquired_at,
                  expires_at = EXCLUDED.expires_at
              WHERE platform_locks.expires_at < now()  -- only if existing is expired
                 OR platform_locks.holder_id = EXCLUDED.holder_id  -- or we already hold it
            """, lockKey, holderId, Timestamp.from(now), Timestamp.from(expires));
        return updated == 1;
    }

    /**
     * Release. Only the holder can release (prevents accidental release by
     * another pod stealing the lock).
     */
    public boolean release(String lockKey, String holderId) {
        return jdbc.update(
            "DELETE FROM platform_locks WHERE lock_key = ? AND holder_id = ?",
            lockKey, holderId) == 1;
    }

    /**
     * @Scheduled reaper — purges leases whose expires_at is in the past.
     * Runs every 30s so at most 30s of stale leases exist at any time.
     */
    @Scheduled(fixedDelayString = "PT30S")
    public void reapExpiredLeases() {
        int purged = jdbc.update(
            "DELETE FROM platform_locks WHERE expires_at < now()");
        if (purged > 0) log.info("[StorageCoordination] Reaped {} expired lease(s)", purged);
    }
}
```

**REST endpoint in storage-manager** (for non-storage-manager callers):

```java
@RestController
@RequestMapping("/api/v1/coordination/locks")
@PreAuthorize("hasAnyRole('INTERNAL', 'ADMIN', 'OPERATOR')")
public class StorageCoordinationController {

    private final StorageCoordinationService service;

    @PostMapping("/{lockKey:.+}/acquire")
    public ResponseEntity<LeaseResponse> acquire(
            @PathVariable String lockKey,
            @RequestBody AcquireRequest req) {
        boolean held = service.tryAcquire(lockKey, req.holderId(), req.ttl());
        return held ? ResponseEntity.ok(new LeaseResponse(lockKey, req.holderId(), req.ttl()))
                    : ResponseEntity.status(409).body(null);
    }

    @DeleteMapping("/{lockKey:.+}")
    public ResponseEntity<Void> release(@PathVariable String lockKey,
                                         @RequestParam String holderId) {
        return service.release(lockKey, holderId) ? ResponseEntity.noContent().build()
                                                   : ResponseEntity.status(409).build();
    }
}
```

**Client** (`shared-core`, replacing the Redis-backed lock):

```java
@Component
@RequiredArgsConstructor
public class StorageCoordinationClient extends BaseServiceClient {
    // Standard BaseServiceClient pattern — SPIFFE JWT-SVID auth on S2S calls.

    public PlatformLease tryAcquireLock(String lockKey, Duration ttl) {
        String holderId = ProcessId.get() + ":" + Thread.currentThread().threadId();
        Map<String, Object> body = Map.of("holderId", holderId, "ttl", ttl.toString());
        try {
            post("/api/v1/coordination/locks/" + urlEncode(lockKey) + "/acquire",
                 body, Map.class);
            return new PlatformLease(lockKey, holderId, ttl, this);
        } catch (HttpClientErrorException.Conflict e) {
            return null; // someone else holds it
        }
    }
}

public record PlatformLease(String key, String holderId, Duration ttl,
                            StorageCoordinationClient owner) implements AutoCloseable {
    @Override public void close() { owner.release(key, holderId); }
}
```

**Usage site** (`DistributedVfsLock.java` becomes a thin wrapper):

```java
try (PlatformLease lease = coordinationClient.tryAcquireLock(
        "vfs:write:" + accountId + ":" + path, Duration.ofSeconds(30))) {
    if (lease == null) throw new VfsConcurrentWriteException(path);
    vfs.writeFile(accountId, path, storageKey, size, trackId, ct);
}
```

### Why PG advisory locks aren't enough

`pg_advisory_xact_lock` is session-scoped (auto-released at transaction end). The VFS write is potentially cross-service — listener grabs the lock, stores bytes via storage-manager (different connection), writes the entry. We need a lease-scoped lock, not a session-scoped one. The `platform_locks` table is the lease store; the `@Scheduled` reaper is the expiry mechanism.

### Performance

- **Acquire cost**: one `INSERT ... ON CONFLICT` statement, ~0.5ms on warm PG
- **Lock contention**: VFS path writes to the same path are <10/s in real traffic; the `ON CONFLICT DO UPDATE` check is in-row, no wait queues
- **Ceiling**: 5k locks/sec sustained — well above realistic MFT write rates

### Invariant check

Against `project_proven_invariants.md`:
- **R72 (VFS write ordering)**: The invariant says "concurrent writes to the same path produce a single `virtual_entries` row reflecting the last write." The PG-backed lock preserves this — only one pod holds the lease at a time, so the VFS write inside the try-with-resources is serialized. ✓
- **R86 (VFS + storage-manager dedup)**: Unchanged — the lease is on the *path*, not the content hash. Dedup still happens at storage-manager via SHA-256. ✓

---

## Hard consumer 3 — Service registry

### Current implementation

`shared-platform/.../cluster/RedisServiceRegistry.java`: each service writes a heartbeat (service_name, pod_id, url, last_seen) to Redis every 10s with a 30s TTL. Readers (admin UI, Platform Sentinel) query all keys matching `platform:services:*` to get the live cluster view. Redis pub/sub on `platform:cluster:events` pushes JOIN/LEAVE notifications in real time.

### Replacement: `cluster_nodes` table + RabbitMQ fanout

**Table** (Flyway `V97__cluster_nodes.sql`):

```sql
CREATE TABLE cluster_nodes (
    node_id          VARCHAR(128) NOT NULL PRIMARY KEY,
    service_type     VARCHAR(64)  NOT NULL,         -- "sftp-service", "onboarding-api"
    host             VARCHAR(255) NOT NULL,
    port             INTEGER      NOT NULL,
    url              VARCHAR(512),                   -- "http://sftp-service:8081"
    spiffe_id        VARCHAR(255),                   -- "spiffe://filetransfer.io/sftp-service"
    last_heartbeat   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DRAINING | DEAD
    metadata         JSONB
);

CREATE INDEX idx_cn_service_type ON cluster_nodes (service_type, status);
CREATE INDEX idx_cn_last_heartbeat ON cluster_nodes (last_heartbeat);
```

**Heartbeat** (every service, every 10s — already scheduled today):

```java
@Scheduled(fixedDelayString = "PT10S")
public void heartbeat() {
    String nodeId = nodeIdentity.getNodeId();
    jdbc.update("""
        INSERT INTO cluster_nodes (node_id, service_type, host, port, url, spiffe_id, last_heartbeat, status)
        VALUES (?, ?, ?, ?, ?, ?, now(), 'ACTIVE')
        ON CONFLICT (node_id) DO UPDATE
            SET last_heartbeat = now(), status = 'ACTIVE'
        """, nodeId, serviceType, host, port, url, spiffeId);
}
```

**Dead-node reaper** (one instance, scheduled via ShedLock — already a pattern in the codebase):

```java
@Scheduled(fixedDelayString = "PT15S")
@SchedulerLock(name = "cluster_nodes_reaper")
public void markDeadNodes() {
    // Anything not heartbeat-updated in 30s is DEAD; publish LEAVE event.
    List<ClusterNode> justDied = jdbc.query("""
        UPDATE cluster_nodes SET status = 'DEAD'
        WHERE status = 'ACTIVE' AND last_heartbeat < now() - INTERVAL '30 seconds'
        RETURNING node_id, service_type
        """, new ClusterNodeRowMapper());
    justDied.forEach(n -> publisher.publish("cluster.node.left", n));
}
```

**Live-cluster view** (for admin UI):

```java
@GetMapping("/api/v1/cluster/nodes")
public List<ClusterNode> list() {
    return jdbc.query(
        "SELECT * FROM cluster_nodes WHERE status = 'ACTIVE' ORDER BY service_type, node_id",
        new ClusterNodeRowMapper());
}
```

### Why keep RabbitMQ for cluster events

Poll-based discovery has a ~15s latency (heartbeat interval + reaper interval). For admin UI view that's fine. But for some consumers (Platform Sentinel auto-heal) the latency matters — they need to know within seconds that a node died.

The RabbitMQ `platform:cluster:events` exchange already exists. Keep it; switch the publisher from Redis pub/sub to RabbitMQ fanout. One class edit in the reaper. Subscribers read from RabbitMQ instead of Redis; same event shape.

### Trade-off vs current

- Loss: ~5s pub/sub latency becomes ~30s reaper delay for DEAD detection (reaper interval)
- Gain: no Redis, survives Redis absence cleanly
- Mitigation: lower the reaper interval to 10s for production profiles — DEAD detection within 30s of heartbeat stop. Still acceptable for our 24/7 operator workflow.

### Invariant check

- **R78 (cluster view in admin UI shows current replicas)**: preserved; UI reads from `cluster_nodes` table.
- **R83 (Platform Sentinel auto-heal on node death)**: preserved via RabbitMQ fanout, latency slightly higher (10-30s instead of <5s). Auto-heal actions are idempotent so the extra delay doesn't corrupt state.

---

## Easy consumers — caches and registries

### PartnerCache (shared-platform) — L2 → Postgres materialized view

Today: Caffeine L1 (per-pod, 30s TTL) + Redis L2 (shared, 5-min TTL).

Replacement: Caffeine L1 (unchanged) + Postgres `partner_cache` materialized view refreshed every 30s. The view IS the L2 — any pod that misses L1 does a single query against a dense in-memory PG cache (after the materialized view warms).

```sql
CREATE MATERIALIZED VIEW partner_cache AS
SELECT p.id, p.slug, p.company_name, p.active, ARRAY_AGG(t.id) AS transfer_account_ids
FROM partners p
LEFT JOIN transfer_accounts t ON t.partner_id = p.id
WHERE p.active = true
GROUP BY p.id;

CREATE UNIQUE INDEX idx_partner_cache_id ON partner_cache (id);

-- Refresh concurrently so readers never see an empty view.
-- @Scheduled task: REFRESH MATERIALIZED VIEW CONCURRENTLY partner_cache;
```

The `PartnerCache` Spring bean loses its Redis dependency; keeps Caffeine; falls through to `jdbc.queryForObject("SELECT ... FROM partner_cache WHERE id = ?", ...)` on L1 miss. Same interface, different L2.

### RedisCacheConfig / AnalyticsCacheConfig / OnboardingCacheConfig — Caffeine + scheduled refresh

All three are `@Cacheable` backends. Replace `RedisCacheManager` with `CaffeineCacheManager` per pod. Per-cache TTLs are per-pod (5/10/15/60/300s); per-pod divergence of 15-60s is invisible to users. No shared-cache contract is broken.

For analytics dashboards with 5-minute refresh (`dedup-stats`), replace with a `@Scheduled(fixedDelay = "PT5M")` that populates a `dedup_stats_snapshot` PG table. Readers query the table — it's tiny (< 1 KB) and always fresh.

### ProxyGroupRegistrar — delete

The DMZ proxy self-registers in Redis with a 30s TTL so the admin UI can show proxy presence. Since DMZ proxy is already listed on `cluster_nodes` after the service-registry migration, this is duplicative. Delete the class; admin UI reads from `cluster_nodes WHERE service_type='dmz-proxy'`.

### StorageLocationRegistry — delete

Only used in multi-replica storage-manager deployments with non-shared storage. We ship with shared MinIO; this code path is never hit in production. Delete; unblock next year's multi-region design to build something proper instead of a Redis shim.

---

## Migration sequencing (within this single dep)

Order matters — doing item 2 before item 1 breaks item 1.

1. **Phase 0** (1 week): Write the PG migrations V95/V96/V97 and deploy them as Flyway scripts. Tables exist, nothing reads them yet. Zero behaviour change.
2. **Phase 1** (3 days): Build `PgRateLimitCoordinator`, `StorageCoordinationService` + controller, `cluster_nodes` heartbeat/reaper. All three components shipped to the default classpath — but NOT wired into any caller yet. Integration tests pass.
3. **Phase 2** (1 week): Switch callers one at a time, behind feature flags:
   - `platform.coordination.locks.backend=pg` (default `redis`)
   - `platform.cache.l2.backend=pg` (default `redis`)
   - `platform.ratelimit.backend=pg` (default `redis`)
   - `platform.registry.backend=pg` (default `redis`)
   Flip each flag in a separate commit. Tester validates each.
4. **Phase 3** (3 days): Once all flags flipped on a full tester cycle with no regression, delete the Redis-backed implementations from the classpath. Remove `redis` container from compose. Remove `spring-data-redis` from all poms. Final PR is +0 source, -many-Redis-files.

**Total: ~3 weeks.** Risky moment is Phase 2 flag-flips; partial failures are recoverable by flipping the flag back.

---

## Acceptance criteria

- [ ] `docker compose ps | grep redis` returns nothing on default profile
- [ ] `grep -r "RedisTemplate\|StringRedisTemplate\|RedisConnectionFactory" shared/ *-service/ onboarding-api/` returns nothing
- [ ] `mvn test` green across all 23 modules
- [ ] R134j SFTP Delivery Regression flow still returns HTTP 500 UnresolvedAddressException (BUG 13 pin)
- [ ] Playwright sanity suite ≥ 486 pass (R134k baseline)
- [ ] 3-replica soak: rate limit respected, VFS writes serialize, cluster view accurate
- [ ] Memory baseline on default profile: ≤ 4 GB (was ~6 GB pre-Redis retirement)

Go to `02-rabbitmq-retirement.md` next.

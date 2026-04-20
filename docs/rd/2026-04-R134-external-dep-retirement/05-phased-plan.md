---
title: "Phased Plan + Gates + Risk Matrix"
status: design
depends_on: 00-overview.md .. 04-fileflow-function-impact.md
---

# Phased Plan — 10 Weeks, Sprint-Level, Gated

Each sprint is independently shippable; the tester can grade partial progress at the end of each. No sprint has >1 week of chained dependencies from its predecessor.

## Sprint 0 — Foundation (Week 1)

Goal: ship the PG-backed primitives as new beans on the default classpath, wired to nothing.

**Deliverables**
- Flyway V95 (`rate_limit_buckets` + monthly partitioning)
- Flyway V96 (`platform_locks`)
- Flyway V97 (`cluster_nodes`)
- Flyway V98 (`event_outbox`)
- `PgRateLimitCoordinator` bean in shared-core — tested, not wired
- `StorageCoordinationService` + controller in storage-manager — tested, not wired (other services haven't switched clients yet)
- `ClusterNodeHeartbeat` scheduler in shared-platform — tested, not wired
- `OutboxWriter` generalized in shared-platform — already partially exists; smoke tests
- `OutboxPoller` framework class in shared-platform — tested with a dummy handler

**Exit gate**
- [ ] `mvn test` green across all modules
- [ ] R134j regression flow unchanged
- [ ] No new WARN/ERROR logs on fresh stack boot
- [ ] Feature flags (`platform.coordination.backend=redis` default) exist on all four axes

**Risk**: Low. Pure new code; no caller switches. Rollback = revert the commit; no DB reverts needed (tables just sit unused).

---

## Sprint 1 — Vault completion (Week 2, half-sprint)

R134l soft-retired Vault. Complete the hard retirement:
- Delete `VaultKmsClient.java` from shared-core
- Remove the 3 `@Autowired(required=false) @Nullable VaultKmsClient` fields (encryption-service, storage-manager, PlatformTlsConfig)
- Remove `vault:` YAML blocks from the two services
- Delete `vault` + `vault-init` compose services
- Delete `VAULT_ADDR`/`VAULT_TOKEN`/`VAULT_ENABLED` env vars
- Delete `spring-cloud-vault` dependencies from poms
- Remove `/data/vault-certs` volume mount references

**Exit gate**
- [ ] `grep -r "VaultKms\|spring.cloud.vault\|VAULT_" .` returns only doc/history hits (no code, no compose)
- [ ] R134j regression still green
- [ ] Encryption round-trip works end-to-end (admin creates delivery-endpoint with password → row persists with ciphertext) — matches R134k BUG 12 exit proof

**Risk**: Trivial — every consumer already has env-var fallback; audit in `feedback_dont_oversell_cosmetic.md` confirms no runtime dependency.

---

## Sprint 2 — Rate limiter switch (Week 2, other half)

Flip `platform.ratelimit.backend=pg` globally. The `ApiRateLimitFilter` gets a one-class edit to use `PgRateLimitCoordinator` when the property resolves to `pg`.

**Scope**
- Edit: `ApiRateLimitFilter.java` — conditional backend selection
- Test: 3-replica gateway soak for 10 minutes; assert quota respected across pods
- Delete: the Redis-backed code path (the feature flag stays — it now toggles between `pg` and `noop`)

**Exit gate**
- [ ] `/api/auth/login` × 201 rapid-fire from one IP across 3 gateway pods returns 429 at req #101 (100/min per-IP limit)
- [ ] `rate_limit_buckets` table has rows for each observed (bucket, window)
- [ ] No Redis calls visible in gateway pod `strace` during test

**Risk**: Medium. Rate-limit regression is customer-visible (either "my api broke because of rate limiting" or "the platform lets through 3× the quota"). Staging soak before tester.

---

## Sprint 3 — Cache L2 migration (Week 3)

Flip `platform.cache.l2.backend=pg`. Each of the 4 caches migrates in a separate commit, easy to rollback individually:
- `PartnerCache` → Caffeine L1 + `partner_cache` materialized view
- Global `RedisCacheConfig` → `CaffeineCacheManager` per service
- `AnalyticsCacheConfig` → Caffeine + `@Scheduled` PG snapshot tables
- `OnboardingCacheConfig` → Caffeine + 5s refresh scheduler

**Exit gate**
- [ ] All 4 cache backends report 0 Redis ops in 24h soak
- [ ] Admin UI analytics dashboards populate within 30s of a fresh stack boot (materialized view refreshes on startup)
- [ ] No `spring-boot-starter-data-redis` left in pom trees (except gateway-service until Sprint 2 lands — already done by this point)

**Risk**: Medium-low. Caches are tolerant of flapping; in the worst case users see a few seconds of stale data. Feature flag allows instant rollback.

---

## Sprint 4 — Service registry migration (Week 4)

Flip `platform.registry.backend=pg`. The change:
- `ClusterNodeHeartbeat` bean (from Sprint 0) starts running on every service
- `RedisServiceRegistry` readers switch to `cluster_nodes` queries
- Admin UI cluster page reads from the same table
- RabbitMQ fanout for cluster events replaces Redis pub/sub (transport change; same event shape)

**Exit gate**
- [ ] `docker ps | wc -l` shows all 23 services healthy
- [ ] Admin UI cluster page lists all of them within 30s of login
- [ ] `docker stop sftp-service-2` → UI shows sftp-service-2 as DEAD within 30s
- [ ] Platform Sentinel auto-heal responds to the DEAD event within 60s (acceptable latency shift from <5s)

**Risk**: Medium. Cluster-view staleness is the visible symptom; functional impact low.

---

## Sprint 5 — Distributed VFS lock migration (Week 5)

Flip `platform.coordination.locks.backend=pg`. Listener services switch from `DistributedVfsLock` (Redis) to `StorageCoordinationClient` (storage-manager HTTP + platform_locks PG table).

Each listener service in a separate commit — sftp, ftp, ftpweb, as2, https — so a regression in one doesn't block the others.

**Exit gate**
- [ ] R134j regression flow still green
- [ ] Concurrent-write collision test (two sftp pods to same path) → one row wins, last-write-wins semantics preserved
- [ ] `platform_locks.expires_at` reaper running, no lease > 30s stale observed over 24h soak
- [ ] No Redis `SET NX` ops visible in listener strace during file-upload burst

**Risk**: Medium-high. VFS correctness is directly in the FileFlow hot path. Extra care: deploy to tester's stack with ONLY one listener service migrated first (sftp), soak 24h, then expand.

---

## Sprint 6 — Event transport migration (Week 6-7, 2 sprints for 4 event classes)

Migrate the 4 low-volume RabbitMQ event classes to the outbox poller. One class per commit:
- Week 6a: `keystore.key.rotated` (0.1/day, safest to test)
- Week 6b: `flow.rule.updated` (~10/min)
- Week 7a: `account.*` (~100/min)
- Week 7b: `server.instance.*` (~5/min)

Pattern for each: shadow-publish to both RabbitMQ and outbox for 1 day; verify consumers on the outbox path work correctly; then stop the RabbitMQ publish in the next commit.

**Exit gate per class**
- [ ] Consumer logs show outbox consumption, not RabbitMQ
- [ ] Event order preserved (no re-ordering observed in 24h soak for the class)
- [ ] Latency within expected bounds per doc 02's latency table

**Risk**: Medium. The event-shape-compatibility principle means even a botched migration on one event class doesn't break others.

---

## Sprint 7 — Redis removal (Week 8)

All Redis consumers migrated. Now delete:
- `redis` container from compose
- `spring-boot-starter-data-redis` from all poms
- `spring.data.redis.*` YAML blocks from application.yml files
- `@Cacheable` backend classes that were Redis-specific
- `RedisServiceRegistry`, `DistributedVfsLock` (Redis impl), `ProxyGroupRegistrar`, `StorageLocationRegistry` classes

This is the single biggest code deletion of the entire R&D — hundreds of lines gone with zero behaviour change. The feature flags from earlier sprints become dead code; they get removed in the same commit.

**Exit gate**
- [ ] `grep -r "RedisTemplate\|StringRedisTemplate\|Jedis\|Lettuce" .` returns only doc/history
- [ ] `docker compose ps` default profile has no Redis container
- [ ] Memory baseline: <= 4.5 GB on default profile (was ~6 GB pre-Redis)
- [ ] R134k 🥈 Silver test battery clean

**Risk**: Low — every callsite has been switched by now; removal is mechanical.

---

## Sprint 8 — RabbitMQ slimming (Week 9)

The surviving RabbitMQ has one queue, one routing key. Slim the compose entry:
- Remove `rabbitmq_management` plugin
- Drop memory limit from 2 GB → 256 MB
- Remove unused exchanges/queues via a bootstrap script

Also:
- Kill the old `@Bean public Queue ...` beans for retired event classes
- Clean the DLQ configuration for retired classes
- Update operator runbook

**Exit gate**
- [ ] RabbitMQ container RSS ≤ 150 MB at steady state
- [ ] One exchange, one queue in the management HTTP probe
- [ ] File-upload flow throughput unchanged (measure via R134j regression firing 100 files)

**Risk**: Low.

---

## Sprint 9 — Observability stack profile-gate (Week 10)

All 5 observability containers move to `profiles: ["observability"]`. Default stack no longer starts Prometheus / Alertmanager / Loki / Grafana / Promtail.

Simultaneously:
- Add a `PlatformMetricsContributor` bean that emits Micrometer counters to the `/actuator/prometheus` endpoint even without Prometheus scraping (useful for operators with their own scraper)
- Add a fallback `platform_events` PG table for alerts — AlertManager subscribers read from this table when AlertManager is absent
- UI Monitoring page already degrades gracefully (R134m finding); update copy to say "install observability profile to enable"

**Exit gate**
- [ ] Default `docker compose up -d` runs without any of the 5 observability containers
- [ ] `docker compose --profile observability up -d` brings them back
- [ ] Alertmanager routes when present; PG table records when absent (tested via `docker stop alertmanager` → event arrives in PG)
- [ ] Memory baseline on default: ~4 GB (was ~6 GB — Loki alone is 300-500 MB)

**Risk**: Low — these are already optional per UI design.

---

## Sprint 10 — End-to-end soak + acceptance (Week 10-11)

1-week soak on 3-replica deployment; run every R134 regression pin + the 5 runtime proofs from doc 04.

If all pass → declare the retirement complete; update docs:
- `project_platform_architecture.md` — remove Redis/Prometheus/Loki from the default topology
- `README.md` — update architecture diagram
- `feedback_distributed_modularity.md` — add "storage-manager owns file coordination" note

If any proof fails → post-mortem; selective rollback via feature flags is still available for each sprint's scope.

---

## Risk matrix

| Risk | Sprint surfaced | Impact if hit | Mitigation |
|---|---|---|---|
| Rate limit regresses cross-pod | 2 | Customer quota violation | Staging 10-min soak before tester hands it to you |
| VFS write race | 5 | Data corruption — last-write-wins breaks | One listener at a time; 24h soak between |
| Outbox poller latency breaks listener bind | 6 | ServerInstance UPDATED doesn't reach listener; UI bind-state flaps | Shadow-publish both channels during cutover |
| Cluster-view stale on DEAD node | 4 | Sentinel auto-heal delayed | Keep RabbitMQ fanout for cluster events; only the registry query is PG |
| PartnerCache L2 miss storms | 3 | 100ms p99 slowdown during warm-up | Materialized view pre-populated on migration, refresh concurrent |
| Feature flags accumulate | 2-6 | Code complexity via legacy branches | Delete in Sprint 7 |
| Postgres becomes the SPOF | 8 | If PG dies, nothing works | Already true today (PG is already the system of record); HA is a separate design |

## Open-ended deferrals (not in this 10-week plan)

1. **Postgres HA design** — for now, single PG instance with automated backups. HA is a separate project.
2. **Multi-region deployment** — storage coordination leases would need to be region-aware or accept eventual consistency. Out of scope.
3. **Kubernetes operator** — everything in this plan is Docker Compose. K8s-native patterns (Operator + CRD + Lease API) can replace the PG-backed platform_locks with `coordination.k8s.io/v1` leases. Different track; revisit when k8s deployment is operational target.
4. **Tenant isolation for locks + caches** — when we go multi-tenant SaaS, lock-key namespaces and cache-key namespaces become tenant-scoped. Not today's problem.
5. **Performance benchmarks** — this doc names targets but doesn't prove them. A 2-day benchmarking spike with jmeter/gatling + a real 3-replica staging is its own work item.

## Summary: what the platform looks like at the end of Sprint 10

- **23 microservices** — unchanged count (https-service from R134o is the 23rd; no others added or removed)
- **External deps on default profile**: Postgres, MinIO, SPIRE, RabbitMQ (slim, one queue)
- **Memory footprint**: ~4 GB baseline (was ~8 GB)
- **Lines of code**: net negative — more deletion than addition
- **Lines of ops runbook**: net negative — fewer systems to monitor
- **Customer-visible contracts**: identical (every R64→R134k invariant preserved)
- **Lock coordination authority**: storage-manager (functional ownership: "where bytes live, who can see them")
- **Event authority**: Postgres outbox for the 4 retired classes, slim RabbitMQ for file-upload routing only

This is the real path.

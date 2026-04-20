---
title: "Postgres Scaling Playbook — when (not if) PG becomes the bottleneck"
status: design
depends_on: 00-overview.md, 01-redis-retirement.md, 02-rabbitmq-retirement.md
---

# Postgres Scaling Playbook

The retirement plan puts Redis's and partial RabbitMQ's responsibilities onto Postgres. That works at today's load. It will NOT work indefinitely without active scaling work. This doc is the playbook for what to do when PG starts groaning, and in what order — so we don't repeat industry mistakes.

## Two reference stories worth internalising

**Figma — 100x growth** (2020 → 2023):
- Started with one RDS instance holding everything.
- Hit IO + CPU ceilings → moved to the largest RDS + added read replicas + PgBouncer.
- Hit vacuum + IOPS walls on huge tables → **vertical partitioning** (split `figma_files`, `organizations` into their own databases).
- Hit single-table throughput limits → **horizontal partitioning** (sharding) with a custom `DBProxy` routing layer.

Total elapsed: ~3 years, three distinct re-architectures.

**Discord — Cassandra → ScyllaDB** (2016 → 2022):
- Started on MongoDB (1 replica, 100M msgs).
- Moved to Cassandra for write throughput.
- Cassandra hit LSM concurrent-read hotspots + unpredictable GC + expensive compaction at trillions of msgs.
- Rewrote to ScyllaDB (C++, same API) + Rust data service.

Total elapsed: ~6 years. Second major re-architecture because the first "right" choice had a ceiling.

**Lesson**: picking Postgres is not a terminal decision. We need a plan for when each growth tier arrives.

## The 4-tier evolution path

### Tier 1 — Single PG, vertical scaling (where we ship)

**Profile**: 1 RDS / Aurora instance, ~100 GB data, < 5k TPS, < 10M rows on any single table.

**What works here**: everything in the retirement plan. One PG instance happily runs:
- `rate_limit_buckets` (monthly partitions, 90-day retention)
- `platform_locks` (small, high-churn, reaper keeps it bounded)
- `cluster_nodes` (< 50 rows, heartbeat updates)
- `event_outbox` + `event_outbox_dlq` (monthly partitions)
- All domain tables (accounts, flows, transfers, etc.)

**Scaling levers**: bigger instance, more RAM, faster disk, connection pooling (PgBouncer). Buy time cheaply; defer structural work.

**Signals this tier is ending**: CPU regularly > 70% at peak, p99 query latency > 100ms, connection count at pool max, vacuum taking longer than the inter-vacuum interval.

### Tier 2 — Read replicas + PgBouncer

**Profile**: 1 primary + 2-4 read replicas, ~500 GB data, 5-20k TPS.

**What we add**:
- **Read replicas** for heavy read paths: `partner_cache` MV queries, admin UI reads, analytics queries. Writes stay on the primary.
- **PgBouncer** in transaction-pool mode. Services no longer hold dedicated connections — PgBouncer fans a small pool to the primary. 10× connection efficiency.
- **Replica lag monitoring**. Rule of thumb: routes to replicas only when lag < 1s; fall back to primary otherwise.

**What stays on primary**: writes, and any read that must be strongly consistent (like "have I already processed this event for this consumer" guard reads).

**Services to migrate first**: analytics-service, onboarding-api's `/api/activity-monitor` reads, Platform Sentinel. High read volume, low write-freshness requirement.

**Cost**: ~30% infra uplift for replicas; compensated by removed Redis + 50% less primary load. Usually net-neutral or cheaper.

**Signals this tier is ending**: primary CPU still pegged (reads went to replicas, but writes alone saturate), replication lag spikes to seconds, biggest table > 100M rows and individual queries > 500ms.

### Tier 3 — Vertical partitioning (Figma step 2)

**Profile**: 2-4 distinct databases per domain, ~2 TB total, 20-100k TPS.

**What we do**: split the single DB by domain. Each gets its own instance + replicas.

**Natural split points** (Figma used similar):
- **`mft_events`** → `event_outbox`, `event_outbox_dlq`, `platform_locks`, `rate_limit_buckets`, `cluster_nodes`. High-churn, write-heavy, low cross-domain joins.
- **`mft_domain`** → accounts, partners, transfer_accounts, flows, flow_executions, server_instances. The core business state.
- **`mft_audit`** → audit_logs, compliance_logs. Write-mostly, read-occasionally.
- **`mft_analytics`** → aggregated metrics, dedup stats, activity-view materialized tables.

**Key trade-off**: we lose cross-domain transactions. Calls that previously relied on a single `@Transactional` across, say, `flow_executions` + `audit_logs` now need to use the outbox pattern (write to domain DB + enqueue an event → audit DB consumes it asynchronously). This is mostly a good thing — it forces domain boundaries — but it IS work.

**Tooling**: application code needs a `DataSourceRouter` that routes queries to the right physical DB based on the entity/repository. Spring's `AbstractRoutingDataSource` handles this cleanly.

**Services that change**: every service grows awareness of which physical DB its entities live in. Wire via `@Qualifier` on DataSource beans, or better, per-repository `@Configuration` classes.

**Signals this tier is ending**: single domain DB (usually `mft_domain`) saturated on writes despite being isolated; biggest table > 1B rows; backup / restore times crossing the RPO.

### Tier 4 — Horizontal partitioning / sharding (Figma step 3)

**Profile**: sharded DB per domain, 10 TB+ total, > 100k TPS.

**What we do**: pick a shard key per domain DB and split it across N physical shards.

**Natural shard keys**:
- `mft_events`: hash of `aggregate_id` for `event_outbox` (preserves per-aggregate ordering), hash of `bucket_key` for `rate_limit_buckets`.
- `mft_domain`: hash of `partner_id` (or `tenant_id` when we go multi-tenant).
- `mft_audit`: range on `created_at` for time-series access.

**Tooling**: either Citus (pg extension, still PG), YugabyteDB (PG-compatible distributed, potentially a drop-in), or a custom routing layer like Figma built. **Citus is the default** — stays in PG ecosystem, no app rewrite.

**The hard part**: cross-shard queries (like "count active accounts across all partners") become scatter-gather with coordinator overhead. Most hot-path queries shard cleanly; analytics queries become painful.

**Signals this tier is ending**: you're a very successful platform. Good problem to have. At this tier the next step is usually a specialized datastore per domain (ScyllaDB for events, Cassandra for audit, ClickHouse for analytics) — the Discord evolution.

## When to advance — concrete triggers

| Metric | Tier 1 → 2 | Tier 2 → 3 | Tier 3 → 4 |
|---|---|---|---|
| Primary CPU p95 at peak | > 70% sustained | > 70% after read replicas | > 70% after vertical split |
| Largest-table row count | > 50M | > 500M | > 5B |
| p99 query latency | > 100ms | > 200ms | > 500ms |
| Connection count | > 80% of max | > 80% after pgbouncer | > 80% per-shard |
| Vacuum interval | > 50% of period | > 80% | Always running |

**Rule**: advance a tier only when ≥ 2 of these are red for ≥ 2 consecutive weeks. Don't advance on spikes.

## What we DON'T do

- **Don't pre-emptively shard.** Every shard we add costs engineering hours to maintain, costs money to run, and adds cross-shard query complexity. Defer until actual metrics demand it.
- **Don't jump tiers.** Going from tier 1 straight to tier 4 has been tried and always fails — you pay the cost of sharding without the experience of running tier 2 and 3 first. Each tier teaches you where the next one's pain will show up.
- **Don't migrate away from PG before exhausting it.** Both Figma and Discord's public post-mortems say the same thing: "we wish we'd done more with what we had before re-platforming." ScyllaDB / TiDB / CockroachDB are real options, but the bar is "we ran out of runway on Postgres after sincerely trying" — not "we anticipated a problem."
- **Don't optimise for hypothetical multi-region** in this retirement plan. Cross-region PG is a separate design (logical replication, conflict handling, consistency model decisions). If multi-region becomes a requirement, that's an additional doc, not a side-effect of this plan.

## Observability — the metrics we actually need before any of this matters

A scaling decision without metrics is a wish. We need these exported to Prometheus / wherever before tier-advance decisions make sense:

- `pg_stat_statements` — top slow queries per service
- Replication lag in seconds
- Connection pool saturation per service
- Dead-tuple bloat per table
- Vacuum duration / frequency
- WAL generation rate
- Row counts + table sizes of hot tables

Most of these are already scraped via the Prometheus `postgres_exporter`; we just need the dashboards and alert thresholds aligned with the tier-advance triggers above.

## Exit — when Postgres is no longer the answer

For completeness: beyond tier 4, the evolution often moves TO specialised stores for specific domains while KEEPING Postgres for domain state. Discord's path is the template — Cassandra for messages, but PG for users/guilds. If we ever reach tier 4+ saturation, the right move is per-domain replacement (events → Kafka + ScyllaDB; audit → ClickHouse; rate-limit → in-mem distributed map), not a wholesale rewrite.

But this is years away at current growth. We plan for it existence, but not its implementation, in this doc.

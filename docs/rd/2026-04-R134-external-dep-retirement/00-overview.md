---
title: "External-Dependency Retirement — Real Engineering Path"
audience: CTO + platform engineers
status: design
author: dev (R134 R&D cycle)
date: 2026-04-20
---

# External-Dependency Retirement — the Real Path

This is the engineering design for reducing TranzFer MFT's third-party runtime footprint from 13 containers on the default profile to 3–5, without regressing any of the R64→R134k-proven behaviours. It is NOT a planning doc — every call-site to migrate is named, every replacement component is designed with a concrete API, and every caller across all 23 microservices is accounted for.

## Verdict up front

| Dep | Role today | Decision | Why |
|---|---|---|---|
| **Postgres** | system of record | **keep** | Every other piece rides on it. Retiring is a platform rewrite. |
| **MinIO** | S3-compatible CAS for file bytes | **keep** | Content-addressed; dedup is free; S3 API is the escape hatch to cloud. |
| **SPIRE / SPIFFE agents** | workload identity | **keep** | Proven cluster-peer auth. Offer mTLS-cert fallback for single-node installs. |
| **Vault** | KMS for master keys | **already opt-in** (R134l) | Env-var fallback exists; compose profile. |
| **Redpanda** | Fabric flow orchestration | **already opt-in** (InMemoryFabricClient fallback) | Fabric falls back gracefully; make compose profile. |
| **Prometheus + Alertmanager** | metrics + alerting | **opt-in behind `observability` profile** | 23 alert rules are operator concerns; default stack works without. |
| **Loki + Grafana + Promtail** | logs + dashboards | **opt-in** | UX sugar; UI degrades gracefully on 503. |
| **ClamAV** | AV scanning | **opt-in sidecar** | Screening tier can drop to RULES-only. |
| **Redis** | cache + distributed lock + service registry + pub/sub | **retire** | 10 consumers, 3 structurally load-bearing; replaceable with Postgres primitives + storage-manager extensions. |
| **RabbitMQ** | events + routing | **partial retire** (keep for file-upload only) | 5 event classes; 4 migrate cleanly to PG LISTEN/NOTIFY; file-upload at 5k evt/s stays on broker. |

**End state on default profile**: Postgres + MinIO + SPIRE + a reduced RabbitMQ (single exchange, one queue for file-upload routing) + 23 microservices. 4 containers down to 4 containers of external infra. RAM baseline ~4 GB instead of ~8 GB. No commercial licenses in the hot path.

## The four concrete docs that follow

1. **`01-redis-retirement.md`** — the hardest. Each of Redis's 10 consumers, the replacement (Caffeine L1 / Postgres L2 / storage-manager-hosted distributed lock / outbox pub-sub), and the migration test. This is where the most design decisions live.
2. **`02-rabbitmq-retirement.md`** — 5 event classes, which migrate to PG LISTEN/NOTIFY and which stay on a slim RabbitMQ. Event-shape-compatible stubs so consumers don't see the wire-format change.
3. **`03-storage-manager-evolution.md`** — the user's explicit angle. Storage-manager owns "where bytes live," so it's the correct functional home for the distributed-lock primitive (VFS writes serialize on path). This doc designs the `StorageCoordinationService` extension.
4. **`04-fileflow-function-impact.md`** — the P0 surface. Every @FlowFunction, every flow step, every routing-engine call site. Proof that the retirement preserves the R64→R134k invariants.
5. **`05-phased-plan.md`** — 10-week sprint-level plan with gates. Each sprint is independently shippable; the tester can grade partial progress.

## Guiding principles — how every design decision was made

These are the rules the per-dep docs apply consistently. Deviation from any requires explicit justification.

1. **Preserve load-bearing invariants.** Every change is additive or compatibly-refactor. Never regress what R64→R134k has proven. Verified against `project_proven_invariants.md` case by case.
2. **No two systems of record for the same concern.** Redis + Postgres + Caffeine can't all own partner metadata — one has to be canonical, others derived. Source of truth chosen per consumer; derivative layers are rebuildable from source.
3. **Storage-manager is the CAS authority AND the file-level coordination authority.** Anything that needs "who owns path X right now" belongs in storage-manager. This is a functional home, not a kitchen-sink anti-pattern — `project_fileflow_critical.md` already names storage-manager as the bytes owner; path-locking is adjacent.
4. **Event-shape compatibility during migration.** A consumer reading `FileUploadedEvent` from RabbitMQ today must be able to read the same class from Postgres `LISTEN/NOTIFY` tomorrow with zero code change. The transport changes; the schema doesn't.
5. **Every retirement has a measurable exit criterion.** "Redis is retired" means: `docker compose ps | grep redis` returns nothing on default profile, AND the R134j regression flow (SFTP Delivery Regression) still returns HTTP 500 UnresolvedAddressException at `SftpForwarderService.forward:46` (BUG 13 pin), AND the R134k 🥈 Silver battery is clean.
6. **Opt-in external dep ≠ retired.** R134l's "Vault retirement Phase 1" was a default-flip on already-optional code (per `feedback_dont_oversell_cosmetic.md`). Real retirement deletes the client, the env vars, the compose entry. This series follows that standard.
7. **Multi-pod correctness is the gate.** Anything that Redis does TODAY across pods (rate limit buckets, VFS lock, service registry) must keep working across pods after retirement. No "works on single-node, breaks on 3-replica" regressions.
8. **The flow engine is sacred.** Any change that touches the FileFlow hot path (listener → VFS → storage-manager → RoutingEngine → FlowProcessingEngine → external-forwarder) is called out separately in `04-fileflow-function-impact.md` and requires a runtime proof via the R134j regression flow.

## How to read the series

Each subsequent doc is independent. If you only care about the Redis decision, read `01`. If you care about the flow-engine invariants, read `04`. If you want the 10-week shipping plan, read `05`.

Every doc is grepped against `project_proven_invariants.md` — where a retirement intersects a tester-validated invariant (R64→R134k), the doc names the invariant and shows how it's preserved.

## What's explicitly OUT of scope for this R&D

- **Postgres HA / sharding / replication** — we add load to PG, but architecting its scale-out is separate.
- **Kubernetes deployment patterns** — this is Docker Compose today; the design is transport-agnostic but k8s-specific YAML is a later pass.
- **Performance benchmarks beyond rough ceilings** — concrete load tests are a measurement exercise, not a design exercise. Benchmark targets are named, execution is queued.
- **UI changes** — the service-topology view in the admin UI will need to hide Redis/RabbitMQ controls; minor.
- **Test-suite-wide rewrites** — unit tests that mock Redis/RabbitMQ beans need swapping to the new beans. This is mechanical post-migration; not a design decision.

Go to `01-redis-retirement.md` next.

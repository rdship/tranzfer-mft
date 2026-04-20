---
title: "FileFlow + Function + Microservice Impact"
status: design
depends_on: 01-redis-retirement.md, 02-rabbitmq-retirement.md, 03-storage-manager-evolution.md
---

# FileFlow, Function, and Per-Microservice Impact Analysis

`project_fileflow_critical.md` declares FileFlow the P0 surface — every change in this R&D is graded against it. This doc walks the full hot path (listener → VFS → storage-manager → RoutingEngine → FlowProcessingEngine → step execution → external-forwarder) and specifies what changes at each layer, what stays, and what invariants are preserved.

## The hot path, annotated with retirement-cycle changes

```
[CLIENT]
   │ SFTP / FTP / FTP_WEB / HTTPS / AS2 upload
   ▼
[LISTENER SERVICE]  (sftp/ftp/ftpweb/https/as2-service)
   │  R134: uses StorageCoordinationClient.tryAcquireLock for the target VFS path
   │  R134: rate limit via PgRateLimitCoordinator (was Redis)
   │  R134: still emits ServerInstanceChangeEvent bindings via outbox (unchanged consumers)
   │  R134: file-uploaded ACKs still cross RabbitMQ — retained for throughput
   ▼
[STORAGE-MANAGER]  (CAS + coordination + VFS auth)
   │  Streams bytes to MinIO with SHA-256 DigestInputStream — UNCHANGED
   │  R134 NEW: owns platform_locks table; exposes /api/v1/coordination/locks/*
   │  R134 NEW: owns ref_count and tier management (future)
   ▼
[VFS WRITE]  (virtual_entries row)
   │  Account-scoped path → storage-key mapping — UNCHANGED
   ▼
[RabbitMQ file.uploaded]  (kept — 5k evt/s)
   │  Publisher: RoutingEngine.onFileUploaded → publishes FileUploadedEvent
   ▼
[FlowProcessingEngine]  (in shared-platform; runs inside the listener service)
   │  Matches flow rules (pre-compiled registry — UNCHANGED)
   │  Executes each step in sequence (UNCHANGED)
   │  Each step's temp file materialization: acquires a short lease on
   │  the temp-dir path via StorageCoordinationClient so parallel flow
   │  executions don't collide
   ▼
[STEP EXECUTION]
   │  CHECKSUM_VERIFY / SCREEN / CONVERT_EDI / COMPRESS_GZIP / RENAME / FILE_DELIVERY ...
   │  Each step's cache (e.g. AI-engine partner-cache lookup) uses Caffeine L1 +
   │  PG materialized view (was Redis L2)
   ▼
[FILE_DELIVERY step]
   │  Calls external-forwarder-service POST /api/forward/deliver/{endpointId}
   │  SPIFFE JWT-SVID auth (R132f fix) — UNCHANGED
   │  X-Forwarded-Authorization fallback for user-initiated calls (R134 fix) — UNCHANGED
   ▼
[EXTERNAL FORWARDER]
   │  SFTP / FTP / HTTPS / AS2 / Kafka outbound — UNCHANGED
   ▼
[EXTERNAL PARTNER]
```

**Byte path**: bytes never touch Redis or RabbitMQ in the current design — they flow listener → tmp file → storage-manager → MinIO. This R&D preserves that. The only place Redis appears on the hot path today is the VFS lock call, which moves to storage-manager's `/api/v1/coordination/locks`.

## Function (@FlowFunction plugin) impact

The platform has extension points — `FlowFunction` interface in `shared-platform/.../flow/FlowFunction.java`. Custom flow steps register as beans and run inside `FlowProcessingEngine`. The FlowFunction contract:

```java
public interface FlowFunction {
    String type();
    String execute(FlowFunctionContext context) throws Exception;
}
```

Audit of every production `@FlowFunction`:

| Function | Uses Redis? | Uses RabbitMQ? | Migration cost |
|---|---|---|---|
| `CompressGzipFunction` | No | No | None |
| `CompressZipFunction` | No | No | None |
| `EncryptAesFunction` | No | No | None — calls encryption-service via SPIFFE |
| `EncryptPgpFunction` | No | No | None |
| `ScreenFunction` | No | No | None — calls screening-service |
| `ConvertEdiFunction` | No | No | None — calls edi-converter |
| `ChecksumVerifyFunction` | No | No | None |
| `FileDeliveryFunction` | No | Yes (indirect via flow event journal) | None — event journal moves to outbox |
| `RenameFunction` | No | No | None |
| `ExecuteScriptFunction` | No | No | None |
| `RouteFunction` | No | Yes (indirect via routing events) | None — events move to outbox |
| `ApproveFunction` | No | Yes (approval notification) | Notification moves to outbox — noop from function's view |
| `MailboxFunction` | No | No | None |

**Zero function-body changes required.** All indirection through event infrastructure is preserved at the higher layer. `@FlowFunction` implementations see no wire-format change.

## Per-listener service migration checklist

### sftp-service

1. `SftpSshServerFactory.java` — already uses `SecurityProfileEnforcer` (R134n). No Redis. ✓
2. `VfsSftpFileSystemProvider.VirtualWriteChannel.close()` — today calls `DistributedVfsLock.tryAcquire(path)`. Change: replace with `StorageCoordinationClient.tryAcquireLock(...)`. 3-line diff.
3. `SftpListenerRegistry` — heartbeats + cluster discovery via `RedisServiceRegistry`. Change: replace with `ClusterNodeHeartbeat` (the `cluster_nodes` writer from doc 01). No behaviour change to bind/unbind flow.
4. `ServerInstanceEventConsumer` — reads `ServerInstanceChangeEvent` from RabbitMQ. Change: `@RabbitListener` → `@OutboxHandler` registration. Event DTO unchanged.

### ftp-service

Same four changes as sftp-service, applied to `FtpServer` + `FtpListenerRegistry` + `FtpletRoutingAdapter`.

### ftp-web-service

Currently doesn't consume `ServerInstanceChangeEvent` (R134l bug). Adding the consumer is orthogonal to this retirement; it's `FtpWebServerInstanceEventConsumer` in a separate commit per the R134l open queue.

Path-lock site: `ftpweb-service/.../controller/UploadController` in the multipart handler.

### https-service (new in R134o)

Already designed with `StorageCoordinationClient` as its path-lock dependency (see R134o commit: the upload controller streams to storage-manager, which holds the lock while the CAS write completes).

### as2-service

AS2 inbound stores message parts via `As2InboundService`. Path-lock site: around the final VFS write after MIC verification.

Service-registry change: the new `ServerInstanceEventConsumer` (R134p) already writes `bind_state`; same bean just calls the cluster-node heartbeat scheduler. No new class.

## Non-listener service checklist

### onboarding-api

- `OutboxPoller` already exists. Change: publish to the new unified `event_outbox` table; remove the RabbitMQ publish step.
- `PartnerCache` L2 backend flip: Redis → Postgres materialized view.
- No path locks.
- Service registry: adopts `cluster_nodes` heartbeat.

### config-service

- Publishes `flow.rule.updated` and `server.instance.*`. Change: `RabbitTemplate.convertAndSend` → `OutboxWriter.write`. One method edit per publisher.
- Hosts `LegacyServerController` + `ServerConfigController` (R134p demo-onboard D1 fix touched these paths, not the services).

### encryption-service

- Zero Redis. Zero RabbitMQ (reads HTTP only).
- No changes from this R&D.

### external-forwarder-service

- Consumes file-upload events? No — it's the target of `POST /api/forward/deliver/*`. Not an AMQP consumer.
- Uses Redis? No.
- No changes.

### keystore-manager

- Publishes `keystore.key.rotated` events. Change: outbox publish instead of RabbitMQ.
- No Redis.

### analytics-service

- AnalyticsCacheConfig: Redis → Caffeine + scheduled refresh (per doc 01).
- Reads cluster state from `cluster_nodes` (from the registry migration).

### ai-engine

- Zero Redis.
- RabbitMQ consumer for flow-step events → outbox poller.
- `partnerCache` → Caffeine + PG view.

### screening-service

- ClamAV remains (opt-in sidecar).
- Cache for screening rules — Caffeine per pod, fine.

### notification-service

- Consumes `notification.*` events → outbox poller.
- No path locks.

### platform-sentinel

- Reads cluster health from `cluster_nodes` table (replaces Redis SCAN pattern).
- Alert routing event consumers move to outbox.

### license-service

- Zero Redis, zero file-upload events.
- No changes.

### edi-converter

- Zero Redis. Uses Caffeine only.
- No changes beyond shared-platform version bump.

### dmz-proxy

- `ProxyGroupRegistrar` (Redis self-register) → delete.
- Reads live cluster state via storage-manager/onboarding-api if needed (probably not).

### gateway-service

- Rate limiter: ApiRateLimitFilter → PgRateLimitCoordinator.

### ui-service

- No backend deps. UI pages that today show Redis health → hidden unless `observability` profile enabled.
- Listener-health page reads `cluster_nodes` (already does, via onboarding-api's view endpoint).

### api-gateway

- Nginx routing. Add one location block: `/api/v1/coordination/` → storage-manager:8096.

## Flow-engine invariants — explicit preservation proof

| Invariant (from `project_proven_invariants.md`) | Preserved? | Evidence |
|---|---|---|
| R64: flow rules hot-reload on `flow.rule.updated` | Yes | Outbox path delivers the same event DTO; `FlowRuleRegistry.onChangeEvent(event)` unchanged |
| R65: pre-compiled rules match in sub-microsecond | Yes | `FlowRuleRegistry` in-memory structure unchanged |
| R68: flow execution creates `flow_executions` row per file | Yes | `FlowProcessingEngine` code path unchanged |
| R72: VFS write ordering preserves last-write-wins per path | Yes | Lock moves from Redis to storage-manager; semantics identical (mutex, lease-based) |
| R73: listener bind state (BOUND/UNBOUND/BIND_FAILED) reflected in UI within 30s | Yes | `ServerInstanceChangeEvent` + `BindStateWriter` unchanged; event transport changed |
| R81: step snapshots written batch-efficient | Yes | `StepSnapshotBatchWriter` unchanged; no Redis involvement |
| R86: file-upload routing sustains 5k evt/s | Yes | **File-upload events stay on RabbitMQ** — this is the single reason RabbitMQ is kept |
| R89: flow step pause/resume works on UPDATED events | Yes | Events move to outbox; consumer logic identical |
| R91: keystore key rotation re-triggers SFTP listener rebind | Yes | Latency goes from <200ms to <5s (outbox poll floor); rotations are rare, acceptable |
| R93: FILE_DELIVERY step calls external-forwarder with SPIFFE | Yes | Zero change — this is the whole point of R134k's fix |
| R134k: BUG 13 flow-engine → forwarder auth path | Yes | Zero change — that code path is independent of Redis/RabbitMQ |

Every R64-R134k invariant preserved. Where latency shifts (keystore rotation, cluster node dead-detection), numbers remain within operator-tolerable bounds.

## Runtime proof targets (to run post-migration)

1. **R134j regression flow** — `sftp put /tmp/x.regression globalbank-sftp:/inbox/`; assert HTTP 500 at `SftpForwarderService.forward:46`. Same failure signature as R134k. If any change — regression.
2. **Multi-pod rate limit** — 3 gateway pods + burst of 200 req/5s from one IP → 3rd request onwards blocked. Proves PgRateLimitCoordinator coordinates cross-pod.
3. **Concurrent VFS write collision** — 2 SFTP listeners upload to `/inbox/same.xml` within 100ms. Assert: exactly one `virtual_entries` row, last-write-wins. Proves storage-manager lock works.
4. **Cluster-view on node death** — `docker stop sftp-service-2`; within 30s the admin UI /cluster page shows `sftp-service-2` as DEAD. Proves cluster_nodes reaper.
5. **Flow rule hot reload via outbox** — `PUT /api/flow-rules/{id}` changes a pattern; within 5s the flow-rule-registry on a different service picks up the change. Proves outbox+LISTEN/NOTIFY delivery.

If any of the five proofs fail, the migration is rolled back via the feature flags (doc 01 Phase 2).

## What this doc does NOT solve

- **End-to-end chaos testing**: formal chaos-monkey runs that kill Redis mid-flight (impossible once retired) and Postgres mid-flight (critical failure mode — separate HA design doc).
- **Tenant-scoped coordination**: multi-tenant locks per tenant, for future SaaS. Not in scope.
- **Cross-region replication of coordination state**: single-region only.

Go to `05-phased-plan.md` next.

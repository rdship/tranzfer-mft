# Dynamic Flow Fabric — Architectural Plan

> **The modern replacement for RabbitMQ + DB polling + in-JVM SEDA queues.**
>
> A unified, observable, crash-safe, distributed work orchestration layer for the TranzFer MFT platform that works identically in cloud (Kubernetes) and on-premise (bare metal / VMs) deployments, with horizontal scaling of every microservice.

---

## Part 1: The Problem

### 1.1 Today's Architecture (fragmented)

The platform currently uses **four separate mechanisms** for asynchronous work:

| Mechanism | Purpose | Location | Problem |
|-----------|---------|----------|---------|
| **RabbitMQ** | Inter-service events (account.*, flow.rule.*, notifications) | 4 named queues + DLX | Fire-and-forget; no visibility into in-flight |
| **In-JVM SEDA** | Flow processing stages (INTAKE/PIPELINE/DELIVERY) | `FlowStageManager` per JVM | Items lost on crash; per-JVM only |
| **DB Polling** | Retry scheduling, recovery, stuck detection | 5+ @Scheduled jobs | O(N × instances) queries; arbitrary 30min timeouts |
| **Async FlowEventJournal** | Step audit trail | `@Async` method calls | Events lost if pod crashes before async executes |

### 1.2 The 7 Unanswerable Questions

From the audit, the current architecture **cannot** answer:

1. **"Where is file X right now in the pipeline?"** — SEDA queues are in-memory; can't inspect
2. **"How long has this file been in the ENCRYPT queue?"** — No per-item age metric
3. **"Which instance is processing this file?"** — No `processingInstance` column anywhere
4. **"How many files are queued for screening globally?"** — No aggregated view across instances
5. **"What's the end-to-end latency distribution (P50/P99)?"** — No percentile metrics
6. **"Why is this file stuck?"** — Must grep logs across multiple pods
7. **"If pod-1 dies, who picks up its 500 in-flight files?"** — Nobody for 30+ min, then `FlowExecutionRecoveryJob` marks them FAILED

### 1.3 The Multi-Instance Gaps

When you run **N instances** of any service:

- **SEDA queues divide work randomly** — pod-1 has 500 items, pod-2 has 0. No work stealing.
- **Crash loses in-flight items** — pod-1 crashes with 500 items in PIPELINE → all 500 stuck for 30 min until recovery job detects them
- **DB polling multiplies by N** — 100 instances × 60s polls × 10K rows = 1M queries/min
- **Circuit breaker state is per-JVM** — pod-1 sees screening-service down, pod-2 still tries
- **Rate limiters are per-JVM** — if limit is 10K/min and 3 pods, total is 30K/min (not 10K)

### 1.4 Why "Just Scale RabbitMQ + SEDA" Doesn't Work

RabbitMQ alone solves point-to-point messaging. It does NOT solve:
- Step-by-step processing with checkpoints
- Real-time observability of in-flight work
- Resumable execution after pod crash
- Distributed backpressure signaling
- Content-addressable storage handoff between steps

SEDA queues alone solve single-JVM throughput. They do NOT solve:
- Distribution across instances
- Durability of queued items
- Cross-instance load balancing

**We need a unified abstraction that does all of this.**

---

## Part 2: The Solution — Dynamic Flow Fabric

### 2.1 Core Concepts

**Fabric** = a single distributed work orchestration layer used by **every** microservice that needs async processing.

Four building blocks:

#### Block 1: **Durable Event Log** (replaces RabbitMQ + in-JVM queues)
A partitioned, replicated append-only log where every work item becomes a durable event.
- Each topic = one kind of work (e.g., `flow.intake`, `flow.encrypt`, `screening.scan`)
- Partitioning = by trackId (all steps for one file land on same partition → ordered)
- Retention = 7 days (enough for replay + debug)
- Consumer groups = multiple instances compete for partitions

**Implementation choices (decision later, design same either way):**
- **Option A**: Apache Kafka (industry standard, highest throughput)
- **Option B**: Redpanda (Kafka API compatible, single binary, on-prem friendly)
- **Option C**: Redis Streams (already in platform, simpler ops)
- **Option D**: NATS JetStream (lightweight, cloud-native, on-prem friendly)

Recommendation: **Redpanda** — Kafka API means mature tooling, single-binary deploy makes on-prem trivial, no JVM dependency.

#### Block 2: **Step-Level Checkpoints** (replaces implicit FlowExecution status)
Every flow step has an explicit, durable checkpoint with:
```
trackId, stepIndex, stepType,
inputStorageKey  (CAS hash at step start),
outputStorageKey (CAS hash at step end, null until done),
status (PENDING → IN_PROGRESS → COMPLETED/FAILED),
startedAt, completedAt,
processingInstance (pod hostname),
attemptNumber, errorMessage
```

Writes to the checkpoint table are **transactional with step execution** — no more async fire-and-forget.

#### Block 3: **Distributed Work Claim** (replaces competing consumers + ShedLock)
When a consumer pulls a work item from the log:
1. Consumer commits offset (= claims the item)
2. Consumer writes checkpoint with `status=IN_PROGRESS` + `processingInstance=$hostname` + `claimedAt=now()` + `leaseExpiresAt=now()+5min`
3. Consumer executes step
4. On success: checkpoint becomes `COMPLETED`, work item is done
5. On failure: checkpoint becomes `FAILED`, retry policy decides next action
6. On crash: lease expires → `LeaseReaperJob` republishes work item to log → another instance picks it up

**Lease duration is configurable per step type** — screening gets 30s lease, encryption for 10TB files gets 2h lease.

#### Block 4: **Unified Observability API** (replaces scattered logs + metrics)
Every fabric interaction emits structured events to a single endpoint:
```
GET /api/fabric/track/{trackId}
→ Returns full timeline: intake → every step → completion
  - Each entry has: timestamp, instance, status, duration, input/output keys
  - Computed fields: totalDuration, stepBreakdown, bottleneckStep
```

Plus Prometheus metrics:
```
fabric.queue.depth{topic, partition}            — live queue depth per partition
fabric.step.duration_ms{step_type, instance}    — histogram with P50/P95/P99
fabric.step.in_flight{step_type, instance}      — gauge
fabric.lease.expired{step_type}                 — counter (indicates crashes)
fabric.retry.count{step_type, error_category}   — counter (indicates problems)
fabric.throughput{topic, instance}              — files/sec per instance
```

### 2.2 Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                       DYNAMIC FLOW FABRIC                        │
│                                                                  │
│  ┌────────────┐    ┌────────────┐    ┌────────────┐            │
│  │  INTAKE    │    │  PIPELINE  │    │  DELIVERY  │            │
│  │   TOPIC    │    │    TOPIC   │    │    TOPIC   │            │
│  │            │    │            │    │            │            │
│  │ part 0..N  │    │ part 0..N  │    │ part 0..N  │            │
│  └─────┬──────┘    └─────┬──────┘    └─────┬──────┘            │
│        │                 │                 │                    │
│        │        (Redpanda / Kafka)         │                    │
└────────┼─────────────────┼─────────────────┼────────────────────┘
         │                 │                 │
    ┌────▼────┐       ┌────▼────┐       ┌────▼────┐
    │ Worker  │       │ Worker  │       │ Worker  │
    │ Pool 1  │       │ Pool 2  │       │ Pool 3  │
    │ (N pods)│       │ (M pods)│       │ (K pods)│
    └────┬────┘       └────┬────┘       └────┬────┘
         │                 │                 │
         ▼                 ▼                 ▼
    ┌─────────────────────────────────────────────┐
    │  Step Checkpoint DB (PostgreSQL)             │
    │  One row per step execution                  │
    │  Indexed by: (trackId, stepIndex)            │
    └─────────────────────────────────────────────┘
         │
         ▼
    ┌─────────────────────────────────────────────┐
    │  Observability API (/api/fabric/*)           │
    │  - Per-trackId timeline                      │
    │  - Real-time queue depths                    │
    │  - Latency percentiles                       │
    │  - Stuck file detection                      │
    └─────────────────────────────────────────────┘
```

### 2.3 Scaling Model

**Independent horizontal scaling per step type:**

- **Screening is slow?** Add more instances to the `screening.scan` consumer group. Fabric rebalances partitions automatically.
- **Encryption is CPU-bound?** Scale the `flow.encrypt` consumer group. Partition key (trackId) ensures in-order per-file processing.
- **Storage writes saturating?** Scale `storage.write` consumer group.

All services can scale **independently** — you don't have to scale everything together.

**On-prem multi-node:**
- Redpanda cluster: 3 brokers on 3 nodes
- Each microservice: 1-N instances per node
- Database: PostgreSQL + read replicas
- Redis: 3-node cluster for leases/coordination
- Works identically to K8s deployment

### 2.4 Crash Recovery

**Scenario**: pod-1 crashes with 500 files in-flight during encryption.

**Today** (broken):
- Items lost from memory
- FlowExecution status = PROCESSING
- After 30 min, `FlowExecutionRecoveryJob` marks them FAILED
- Files sit in limbo for 30+ min

**With Fabric**:
- pod-1's Kafka offsets are committed only on step completion
- When pod-1 crashes, its session expires in Kafka (default 10s)
- Kafka rebalances its partitions to surviving pods
- Surviving pods see the uncommitted offsets and reprocess those items
- Each item's checkpoint still shows `IN_PROGRESS` from pod-1 → reaper detects expired lease (5min) → marks checkpoint stale → worker re-executes step
- **Total recovery time: 10 seconds (Kafka rebalance) + 5 minutes (lease expiry for checkpoint)**

---

## Part 3: Implementation Plan

### Phase 1: Foundation (2 weeks)

**Goal**: Add Redpanda to the stack, create the core abstraction, zero behavior change.

Tasks:
1. Add `redpanda` container to docker-compose (3 brokers for local, single-broker compose variant for dev)
2. Create new `shared-fabric` module in the monorepo with:
   - `FabricClient` interface (publish, subscribe)
   - `FabricAdmin` for topic management
   - `CheckpointStore` wrapping PostgreSQL
   - `LeaseManager` for distributed claims
3. Flyway V53: `fabric_checkpoints` table + `fabric_offsets` table
4. Integration test: publish → consume → checkpoint → recover

**Deliverable**: Fabric infrastructure exists and tested, nothing uses it yet.

### Phase 2: Migrate Flow Processing Engine (3 weeks)

**Goal**: Replace SEDA stages with Fabric topics.

Tasks:
1. Create topics: `flow.intake`, `flow.pipeline`, `flow.delivery`
2. Update `FlowProcessingEngine`:
   - `executeFlow()` → publishes to `flow.intake` topic (not `stageManager.submit()`)
   - Consumer on `flow.intake` → matches flow rules → publishes to `flow.pipeline` for each step
   - Consumer on `flow.pipeline` → executes one step → writes checkpoint → publishes next step or to `flow.delivery`
   - Consumer on `flow.delivery` → delivers to destination → finalizes FlowExecution
3. Keep `FlowStageManager` as a **fallback** for first release (configurable: `fabric.enabled=true`)
4. Update `/api/journey/{trackId}` to include checkpoint timeline

**Deliverable**: Every flow execution has a step-by-step checkpoint history, observable in real-time.

### Phase 3: Migrate Inter-Service Events (2 weeks)

**Goal**: Replace RabbitMQ with Fabric for cross-service events.

Tasks:
1. Create topics: `events.account`, `events.flow-rule`, `events.notification`
2. Update publishers:
   - `AccountEventPublisher` → publishes to `events.account` (was RabbitMQ)
   - `FlowRuleEventPublisher` → publishes to `events.flow-rule`
3. Update consumers:
   - SFTP/FTP/FTP-Web account consumers → consume from `events.account`
   - All services → consume from `events.flow-rule` for hot-reload
   - `notification-service` → consumes from `events.notification`
4. Keep RabbitMQ as fallback (configurable: `fabric.events.enabled=true`)
5. Retire RabbitMQ after 1 month of parallel running (feature flag off)

**Deliverable**: Zero messaging duplication, single observability layer.

### Phase 4: Observability API + UI (2 weeks)

**Goal**: Deliver the "where is file X right now?" answer in under 100ms.

Tasks:
1. `FabricObservabilityController`:
   - `GET /api/fabric/track/{trackId}` — full timeline
   - `GET /api/fabric/queues` — live queue depths across all topics
   - `GET /api/fabric/instances` — which pods are active, what they're doing
   - `GET /api/fabric/stuck` — files in IN_PROGRESS for > lease duration
2. Grafana dashboards:
   - Per-step latency (P50/P95/P99)
   - Queue depths over time
   - Throughput per consumer group
   - Crash recovery events
3. UI enhancements:
   - Journey page: add timeline visualization (Gantt chart of step durations)
   - Activity Monitor: show current step + processing instance per file
   - New "Fabric Dashboard" page under Administration

**Deliverable**: Every file is traceable end-to-end in real-time with visual timeline.

### Phase 5: Migrate Scheduled Jobs (1 week)

**Goal**: Replace DB polling with Fabric scheduled topics.

Tasks:
1. Retries: `flow.retry.scheduled` topic with delayed delivery (Kafka feature)
   - Publish to topic with `deliverAt` header
   - Consumer wakes up at `deliverAt` and processes
   - No more 60s polling
2. Recovery: Replace `FlowExecutionRecoveryJob` with lease expiration
3. Cleanup: `fabric.cleanup` topic for lease reaper

**Deliverable**: No more scheduled DB polling. All work is event-driven.

### Phase 6: Multi-Instance Hardening (1 week)

**Goal**: Verify cluster behavior is correct and add guardrails.

Tasks:
1. Distributed rate limiting: move from per-JVM to Redis-backed (shared state)
2. Aggregate circuit breaker state in Sentinel dashboard (see all instances' breaker states)
3. Instance registry: each pod writes its status to `fabric_instances` every 30s
4. Dead instance detection: if pod hasn't heartbeated in 2 min → release its leases
5. Load chaos tests: kill random pods while 1000 files are in-flight, verify zero data loss

**Deliverable**: Production-ready multi-instance deployment with proven recovery.

### Phase 7: Retire Legacy (1 week)

**Goal**: Remove RabbitMQ, SEDA, polling jobs.

Tasks:
1. Remove RabbitMQ from docker-compose (or keep for legacy on-prem)
2. Delete `FlowStageManager` and `ProcessingStage` (or keep as embedded fallback)
3. Delete `FlowExecutionRecoveryJob`, `ScheduledRetryExecutor`, `GuaranteedDeliveryService.retryFailedTransfers`
4. Delete RabbitMQ config classes
5. Update CLAUDE.md, docs/ARCHITECTURE.md, all demos

**Deliverable**: Single unified messaging layer.

---

## Part 4: Data Model

### 4.1 `fabric_checkpoints` Table

```sql
CREATE TABLE fabric_checkpoints (
    id UUID PRIMARY KEY,
    track_id VARCHAR(32) NOT NULL,
    step_index INT NOT NULL,
    step_type VARCHAR(64) NOT NULL,

    status VARCHAR(24) NOT NULL,           -- PENDING, IN_PROGRESS, COMPLETED, FAILED, ABANDONED
    input_storage_key CHAR(64),            -- SHA-256 at step start
    output_storage_key CHAR(64),           -- SHA-256 at step end
    input_size_bytes BIGINT,
    output_size_bytes BIGINT,

    processing_instance VARCHAR(128),      -- Pod hostname
    claimed_at TIMESTAMPTZ,                -- When worker claimed it
    lease_expires_at TIMESTAMPTZ,          -- Heartbeat-renewed lease
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,

    attempt_number INT DEFAULT 1,
    error_category VARCHAR(32),            -- AUTH, NETWORK, PERMISSION, FORMAT, UNKNOWN
    error_message TEXT,

    fabric_offset BIGINT,                  -- Kafka/Redpanda offset
    fabric_partition INT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_checkpoint_track ON fabric_checkpoints (track_id, step_index);
CREATE INDEX idx_checkpoint_stuck ON fabric_checkpoints (status, lease_expires_at) 
    WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_checkpoint_instance ON fabric_checkpoints (processing_instance, status);
```

### 4.2 `fabric_instances` Table

```sql
CREATE TABLE fabric_instances (
    instance_id VARCHAR(128) PRIMARY KEY, -- pod hostname + UUID
    service_name VARCHAR(64) NOT NULL,    -- "flow-engine", "screening", etc.
    started_at TIMESTAMPTZ NOT NULL,
    last_heartbeat TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,          -- HEALTHY, DEGRADED, DRAINING
    consumed_topics TEXT[],               -- ["flow.intake", "flow.pipeline"]
    current_partitions INT[],             -- Partitions assigned
    in_flight_count INT DEFAULT 0,        -- Current in-progress work
    metadata JSONB
);

CREATE INDEX idx_instance_heartbeat ON fabric_instances (last_heartbeat);
CREATE INDEX idx_instance_service ON fabric_instances (service_name, status);
```

### 4.3 Topic Naming Convention

```
<domain>.<action>[.<qualifier>]

Examples:
- flow.intake           (new file arrived, match rules)
- flow.pipeline         (execute one step)
- flow.delivery         (deliver to destination)
- flow.retry.scheduled  (delayed retry)
- screening.scan        (scan file)
- storage.write         (write to CAS)
- events.account        (account CRUD)
- events.flow-rule      (flow rule changes)
- events.notification   (notification dispatch)
- events.audit          (audit log stream)
```

All topics are **partitioned by trackId hash** (for flow topics) or entity ID (for event topics).

---

## Part 5: Observability — "Where is file X?"

### 5.1 The Timeline Query

```
GET /api/fabric/track/TRZ-abc123def/timeline

Response:
{
  "trackId": "TRZ-abc123def",
  "filename": "purchase-order-2024.edi",
  "totalDurationMs": 14230,
  "currentStatus": "IN_PROGRESS",
  "currentStep": 3,
  "currentInstance": "flow-engine-pod-7",
  "steps": [
    {
      "index": 0,
      "type": "INTAKE",
      "status": "COMPLETED",
      "instance": "flow-engine-pod-2",
      "inputKey": null,
      "outputKey": "a1b2c3...",
      "startedAt": "2026-04-10T10:00:00.000Z",
      "completedAt": "2026-04-10T10:00:00.120Z",
      "durationMs": 120
    },
    {
      "index": 1,
      "type": "SCREEN",
      "status": "COMPLETED",
      "instance": "screening-pod-1",
      "inputKey": "a1b2c3...",
      "outputKey": "a1b2c3...",
      "startedAt": "2026-04-10T10:00:00.150Z",
      "completedAt": "2026-04-10T10:00:02.340Z",
      "durationMs": 2190,
      "metadata": {"screeningResult": "CLEAN", "hitsFound": 0}
    },
    {
      "index": 2,
      "type": "ENCRYPT_PGP",
      "status": "COMPLETED",
      "instance": "flow-engine-pod-5",
      "inputKey": "a1b2c3...",
      "outputKey": "f9e8d7...",
      "durationMs": 11920
    },
    {
      "index": 3,
      "type": "CONVERT_EDI",
      "status": "IN_PROGRESS",
      "instance": "flow-engine-pod-7",
      "inputKey": "f9e8d7...",
      "startedAt": "2026-04-10T10:00:14.230Z",
      "leaseExpiresAt": "2026-04-10T10:05:14.230Z"
    }
  ]
}
```

**That's the "where is file X right now?" answer. Step 3 (CONVERT_EDI), on instance flow-engine-pod-7, started 0.3s ago, lease valid for 5 more minutes.**

### 5.2 UI: Journey Timeline Visualization

Update `Journey.jsx` to render this as a **Gantt chart**:

```
INTAKE      |█| 120ms  (pod-2)
SCREEN       |███████| 2190ms  (screening-pod-1)
ENCRYPT           |████████████████████████████| 11920ms (pod-5)
CONVERT_EDI                                    |░░░░| in-progress  (pod-7)
                                                  ↑ now
```

Every bar is:
- Green = completed
- Yellow = in progress
- Red = failed
- Gray = pending

Clicking a bar shows the full checkpoint row (input/output keys, lease info, error details).

### 5.3 Instance Dashboard

```
GET /api/fabric/instances

Response:
{
  "instances": [
    {
      "instanceId": "flow-engine-pod-1",
      "service": "flow-engine",
      "status": "HEALTHY",
      "uptime": "2h 14m",
      "consumedTopics": ["flow.intake", "flow.pipeline"],
      "currentPartitions": [0, 3, 6],
      "inFlightCount": 23,
      "throughput1m": 45.2,   // files/sec
      "memoryUsagePct": 62
    },
    ...
  ]
}
```

New UI page: `/fabric` — shows live grid of all instances, their topics, their in-flight counts.

---

## Part 6: Why This Works for On-Prem

### 6.1 Single-Binary Components

- **Redpanda**: one Go binary, no JVM, no ZooKeeper. Runs on any Linux box.
- **PostgreSQL**: already required
- **Redis**: already required
- **Each microservice**: fat JAR with embedded Tomcat

**Zero cloud-only dependencies.** Works identically on:
- Kubernetes (any flavor)
- Docker Compose
- Bare metal with systemd
- VMware / Proxmox / Hyper-V VMs

### 6.2 Cluster Sizing Guidance

**Small deployment (< 10K files/day):**
- 1 Redpanda broker
- 1 PostgreSQL + streaming replica
- 1 Redis
- 1-2 instances of each microservice

**Medium (100K files/day):**
- 3 Redpanda brokers
- 1 PostgreSQL primary + 2 replicas
- 3 Redis (cluster mode)
- 3-5 instances of flow-engine, 2-3 of others

**Large (1M+ files/day):**
- 3-5 Redpanda brokers, 10+ partitions per topic
- 1 PostgreSQL primary + 3 replicas + PgBouncer
- 5 Redis cluster
- 10+ instances of hot services (flow-engine, storage, screening)
- Independent scaling per step type

### 6.3 On-Prem Deployment Modes

**Mode 1: Single-Node** (dev/test)
```
docker compose up
```
Everything on one machine.

**Mode 2: Three-Node HA**
```
node-1: postgres-primary, redpanda-1, redis-1, app instances
node-2: postgres-replica, redpanda-2, redis-2, app instances
node-3: postgres-replica, redpanda-3, redis-3, app instances
```
No SPOF.

**Mode 3: Separated tiers**
```
data-nodes: postgres, redpanda, redis (3 nodes)
app-nodes: microservices (N nodes, horizontally scaled)
edge-nodes: dmz-proxy, sftp/ftp (2 nodes with VIP)
```
Production-grade with clear separation.

---

## Part 7: Migration Strategy (Zero Downtime)

### 7.1 Feature Flag Approach

Every fabric integration is behind a feature flag:
```yaml
fabric:
  enabled: false        # master switch
  topics:
    flow: false         # migrate flow processing?
    events: false       # migrate inter-service events?
    scheduled: false    # migrate scheduled jobs?
```

Roll out gradually:
1. **Week 1**: Deploy fabric infra (Redpanda, checkpoint table), flag OFF
2. **Week 2**: Flip `fabric.topics.flow=true` for 1 test account, watch metrics
3. **Week 3**: Flip for 10% of traffic
4. **Week 4**: Flip for 100% of flow traffic
5. **Week 5**: Flip events
6. **Week 6**: Flip scheduled
7. **Week 7**: Disable legacy paths, keep code for 1 more release as safety net
8. **Week 8**: Delete legacy code

### 7.2 Rollback Plan

At any stage, flipping the flag back reverts to legacy behavior. Checkpoint table can be ignored; RabbitMQ queues resume.

Data in fabric checkpoints is **additive** — doesn't break anything if unused.

### 7.3 Parallel Run Validation

During the 10% → 100% rollout:
- Run both old and new paths in shadow mode
- Compare outputs (checksums, timings)
- Alert on discrepancies
- Only advance when discrepancy rate < 0.01%

---

## Part 8: Success Metrics

After full rollout, verify:

| Metric | Target | Today |
|--------|--------|-------|
| **"Where is file X?" query latency** | < 100ms | ~5s (grep logs) |
| **Crash recovery time** | < 30s | 30+ min |
| **Max sustainable files/hour** | > 500K | ~50K (SEDA bottleneck) |
| **Instances observable from one API** | Yes | No |
| **Step-level audit trail** | 100% | ~60% (async loss) |
| **Stuck file detection time** | < 5 min (lease expiry) | 30 min |
| **Duplicate notification rate** | 0% | ~0.1% (no dedup) |
| **DB queries/min from polling** | ~0 | ~1000 × instances |
| **Multi-instance load balance** | Automatic | Manual |
| **On-prem deployment complexity** | 1 compose file | 1 compose + manual tuning |

---

## Part 9: File Inventory

### New files (~60)
- `shared-fabric/` (new Maven module)
  - `FabricClient.java`, `FabricAdmin.java`, `FabricConfig.java`
  - `CheckpointStore.java`, `LeaseManager.java`, `InstanceRegistry.java`
  - `ProducerPool.java`, `ConsumerPool.java`, `PartitionAssigner.java`
  - `TopicNamingPolicy.java`, `RetentionPolicy.java`
- `shared/shared-platform/.../fabric/`
  - `FlowFabricBridge.java` (replaces FlowStageManager integration)
  - `EventFabricBridge.java` (replaces RabbitTemplate integration)
  - `FabricObservabilityService.java`
- `shared/shared-platform/.../entity/`
  - `FabricCheckpoint.java`, `FabricInstance.java`
- `shared/shared-platform/.../repository/`
  - `FabricCheckpointRepository.java`, `FabricInstanceRepository.java`
- `onboarding-api/.../controller/`
  - `FabricObservabilityController.java`
- `shared/shared-platform/src/main/resources/db/migration/`
  - `V53__fabric_checkpoints.sql`
  - `V54__fabric_instances.sql`
- `docker-compose.yml` — add `redpanda` service
- `ui-service/src/pages/FabricDashboard.jsx` (new page)
- `ui-service/src/components/TimelineGantt.jsx` (new component for Journey)
- Integration tests (~15 files)

### Modified files (~30)
- `FlowProcessingEngine.java` — use FlowFabricBridge instead of FlowStageManager
- `AccountEventPublisher.java` — use EventFabricBridge instead of RabbitTemplate
- `FlowRuleEventPublisher.java` — same
- `Journey.jsx` — add timeline Gantt visualization
- `ActivityMonitor.jsx` — show currentStep + currentInstance per file
- `Sidebar.jsx` — add Fabric Dashboard nav item
- `App.jsx` — add /fabric route
- Several `*Consumer.java` classes — switch from @RabbitListener to @FabricListener
- `CLAUDE.md` — new architecture section
- `docs/ARCHITECTURE.md` — major update

### Removed files (Phase 7)
- `FlowStageManager.java`, `ProcessingStage.java`
- `FlowExecutionRecoveryJob.java`
- `ScheduledRetryExecutor.java` (functionality moves to delayed topics)
- RabbitMQ config classes
- (kept as backup for 1 release, then deleted)

---

## Part 10: Open Questions / Decisions Needed

Before building, these decisions need to be made:

1. **Broker choice**: Redpanda vs Kafka vs Redis Streams vs NATS JetStream?
   - Recommendation: **Redpanda** (Kafka API, single binary, on-prem friendly)
   - Alternative: NATS JetStream (lighter, but less ecosystem)

2. **Checkpoint commit strategy**: Commit offset before or after checkpoint DB write?
   - Recommendation: **After** (at-least-once, dedup on replay via checkpoint idempotency)

3. **Partition count per topic**: Fixed or auto-scaling?
   - Recommendation: **Fixed at 32** per topic initially, can increase later

4. **Legacy RabbitMQ retention**: Delete after migration or keep forever?
   - Recommendation: Keep for 1 release as feature flag, then delete

5. **Multi-region deployment**: Is this a requirement?
   - If yes: Redpanda has native multi-region replication
   - If no: single-region cluster is fine

6. **Observability backend**: Stay with Prometheus or add OpenTelemetry tracing?
   - Recommendation: Add OpenTelemetry for distributed tracing, keep Prometheus for metrics

7. **Do we delete SEDA entirely or keep as embedded fallback**?
   - Recommendation: Delete after 1 release of proven fabric operation

---

## Summary

**Today**: 4 fragmented async mechanisms (RabbitMQ, SEDA, DB polling, async events), no unified observability, crash recovery takes 30+ min, in-memory queues lose data, multi-instance coordination is spotty.

**With Fabric**: Single durable event log, step-level checkpoints, distributed work claims with leases, unified observability API answering "where is file X?" in < 100ms, crash recovery in < 30s, works identically on cloud or on-prem, horizontal scaling of any service.

**Effort**: 11 weeks total across 7 phases, with feature flags allowing zero-downtime rollout and instant rollback.

**When to build**: After current EDI Phase 3-5 work completes. This is foundational and should not be done under time pressure.

**Dependencies**: Add Redpanda (or chosen broker) to docker-compose and on-prem deployment guide.

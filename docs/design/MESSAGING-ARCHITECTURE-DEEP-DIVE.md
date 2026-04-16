# TranzFer MFT — Messaging & State Infrastructure Deep Dive

**Date:** 2026-04-16  
**Build:** R58  
**Author:** Architecture Team  
**Purpose:** Complete technical reference for Redis, RabbitMQ, and Redpanda (Kafka)

---

## Why Three Systems?

| System | Pattern | Role | Cannot Be Replaced By |
|--------|---------|------|----------------------|
| **RabbitMQ** | Work Queue (competing consumers) | File upload dispatch + step pipeline | Kafka (no native backpressure/prefetch) |
| **Kafka/Redpanda** | Event Log (ordered broadcast) | Cross-service flow coordination + replay | RabbitMQ (no ordered replay, no partition keys) |
| **Redis** | Shared State (sub-ms cache + locks) | Partner cache, service registry, VFS locks, rate limits | Neither (they're message brokers, not state stores) |

---

## End-to-End File Transfer Flow

```
Partner uploads file.pdf via SFTP
        │
        ▼
┌─────────────────────────┐
│     SFTP Service         │
│  SftpFileSystemEvent     │
│  → RoutingEngine         │
│    .onFileUploaded()     │
└────────┬────────────────┘
         │ ① RabbitMQ: exchange=file-transfer.events
         │   routing_key=file.uploaded
         │   format=JSON (Jackson2JsonMessageConverter)
         ▼
┌─────────────────────────┐
│     RabbitMQ             │
│  queue: file.upload.events│
│  prefetch=10, DLQ backed │
└────────┬────────────────┘
         │ consumed by FileUploadEventConsumer
         │ (any service with FLOW_RULES_ENABLED=true)
         ▼
┌─────────────────────────┐
│  FileUploadEventConsumer │
│  ② Idempotency check    │──→ Redis: PartnerCache.get(partnerId)
│     (trackId in DB?)     │       L1: ConcurrentHashMap (~10ns)
│  ③ Load TransferAccount  │       L2: Redis GET (~0.5ms)
│  ④ Compliance check      │       L3: PostgreSQL (~5ms)
│  ⑤ Flow matching         │
│     (FlowRuleRegistry)   │
└────────┬────────────────┘
         │
    ┌────┴────┐
    │ Match?  │
    └────┬────┘
     YES │         NO → Record COMPLETED (no matching flow)
         ▼
┌─────────────────────────┐
│  FlowProcessingEngine    │
│  .executeFlow()          │
│                          │
│  Tier 1: SEDA in-JVM    │ ← No Kafka
│  Tier 3: Kafka per-step │ ← With Kafka
└────────┬────────────────┘
         │
    For each step:
         │
         ▼
┌─────────────────────────────────────────────────┐
│  Step: SCREEN                                    │
│  ⑥ Kafka: flow.step.SCREEN (partition=trackId) │
│  ⑦ FabricCheckpoint: IN_PROGRESS               │ → PostgreSQL
│  ⑧ Call screening-service HTTP                  │
│  ⑨ FabricCheckpoint: COMPLETED                 │ → PostgreSQL
│  ⑩ Kafka: flow.pipeline (observability)         │
└────────┬────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────┐
│  Step: ENCRYPT_PGP                               │
│  Kafka: flow.step.ENCRYPT_PGP                   │
│  Call encryption-service HTTP                    │
└────────┬────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────┐
│  Step: COMPRESS_GZIP                             │
│  Local processing (no service call)              │
└────────┬────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────┐
│  Step: FILE_DELIVERY (SFTP)                      │
│  Kafka: flow.step.DELIVER_SFTP                  │
│  Call forwarder-service HTTP                     │
│  ⑪ FlowExecution: COMPLETED                    │ → PostgreSQL
│  ⑫ ConnectorDispatcher.dispatch(FLOW_COMPLETED) │ → Webhooks
│  ⑬ Kafka: flow.delivery                        │ → Monitoring
└─────────────────────────────────────────────────┘
```

---

## RabbitMQ: Work Queue Dispatch

### Exchange Topology

| Exchange | Type | Durable | Purpose |
|----------|------|---------|---------|
| `file-transfer.events` | topic | Yes | All routing keys flow through here |
| `file-transfer.events.dlx` | topic | Yes | Dead letter exchange for failed messages |

### Queues

| Queue | Routing Key | Consumers | Prefetch | DLQ | Purpose |
|-------|------------|-----------|----------|-----|---------|
| `file.upload.events` | `file.uploaded` | 4-16 threads | 10 | Yes | File upload task dispatch |
| `flow.step.pipeline` | `flow.step.execute` | 4-16 threads | 5 | Yes | Step execution (RabbitMQ path) |
| `flow.step.pipeline.dlq` | `flow.step.dead` | — | — | — | Failed steps (1hr retention) |
| Anonymous (per-pod) | `account.*` | 1 per pod | 1 | No | Partner cache eviction broadcast |
| Anonymous (per-pod) | `flow.rule.updated` | 1 per pod | 1 | No | Flow rule hot-reload broadcast |

### Message Format

All messages serialized as JSON via `RabbitJsonConfig` → `Jackson2JsonMessageConverter`.

**FileUploadedEvent:**
```json
{
  "trackId": "TRZ8UHWPNJZB",
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "username": "acme-sftp",
  "protocol": "SFTP",
  "filename": "purchase_order_850.edi",
  "relativeFilePath": "inbox/purchase_order_850.edi",
  "absoluteSourcePath": "/data/sftp/acme/inbox/purchase_order_850.edi",
  "sourceIp": "192.168.1.50",
  "fileSizeBytes": 4096,
  "storageMode": "VIRTUAL",
  "partnerId": "660e8400-e29b-41d4-a716-446655440001",
  "homeDir": "/data/sftp/acme"
}
```

### Backpressure Chain

```
Producer (SFTP service)
  → RabbitMQ broker (disk-backed queue)
    → prefetch=10 per consumer thread
      → 16 max threads
        = 160 messages in-flight per pod
          → If all 160 busy: broker holds remaining on disk
            → Producer is NOT blocked (fire-and-forget publish)
```

### Poison Message Flow

```
Message fails 3 times
  → x-death header accumulates
    → FileUploadEventConsumer checks xDeath.size() >= 3
      → ACKs the message (removes from queue)
        → Logs ERROR with trackId
          → File stays on disk for operator re-trigger
```

---

## Kafka/Redpanda: Ordered Event Log

### Topics

| Topic | Partition Key | Consumer Group Pattern | Semantics |
|-------|-------------|----------------------|-----------|
| `flow.intake` | trackId | Shared (competing) | Load-balanced intake |
| `flow.pipeline` | trackId | Monitoring | All steps logged |
| `flow.step.SCREEN` | trackId | Shared (competing) | Security scanning |
| `flow.step.ENCRYPT_PGP` | trackId | Shared (competing) | PGP encryption |
| `flow.step.ENCRYPT_AES` | trackId | Shared (competing) | AES encryption |
| `flow.step.COMPRESS_GZIP` | trackId | Shared (competing) | Gzip compression |
| `flow.step.CONVERT_EDI` | trackId | Shared (competing) | EDI format conversion |
| `flow.step.DELIVER_SFTP` | trackId | Shared (competing) | SFTP delivery |
| `flow.step.DELIVER_HTTP` | trackId | Shared (competing) | HTTP delivery |
| `flow.step.DELIVER_AS2` | trackId | Shared (competing) | AS2 delivery |
| `flow.delivery` | trackId | Monitoring | Delivery tracking |
| `flow.retry.scheduled` | trackId | Shared | Delayed retries |
| `events.account` | username | Fanout (per-pod) | Cache eviction |
| `events.flow-rule` | flowId | Fanout (per-pod) | Hot-reload |
| `events.notification` | eventType | Shared or fanout | Notification dispatch |

### Consumer Group Naming

- **Shared**: `fabric.{serviceName}.{topic}` — Kafka distributes partitions across pods
- **Fanout**: `fabric.{serviceName}.{topic}.{hostname}` — every pod gets every message

### Checkpoint-Based Exactly-Once

```
Step starts:
  FabricCheckpoint { trackId, stepIndex=2, status=IN_PROGRESS, leaseExpiresAt=now+60s }
    → PostgreSQL INSERT

Step succeeds:
  FabricCheckpoint { status=COMPLETED, outputStorageKey="sha256:abc...", durationMs=450 }
    → PostgreSQL UPDATE

Step crashes (pod dies):
  LeaseReaperJob (every 60s, ShedLocked):
    SELECT * FROM fabric_checkpoints WHERE status='IN_PROGRESS' AND lease_expires_at < now()
      → Mark ABANDONED
        → Schedule restart from step 0
```

### Three Operational Tiers

| Tier | Kafka | RabbitMQ | SEDA | Use Case |
|------|-------|----------|------|----------|
| 1 | OFF | File dispatch only | In-JVM thread pools | Single-pod dev |
| 2 | In-memory | File dispatch | In-JVM + checkpoints | Multi-pod without Kafka |
| 3 | Full (Redpanda) | File dispatch + broadcast | Per-function topics | Production distributed |

---

## Redis: Shared State Layer

### Data Structures in Use

| Pattern | Key Format | TTL | Operation | Purpose |
|---------|-----------|-----|-----------|---------|
| Partner Cache L2 | `partner:snap:{id}` | 5 min | GET/SET | Partner slug resolution |
| Service Registry | `platform:instance:{type}:{id}` | 30s | SETEX | Service discovery |
| VFS Locks | `platform:vfs:lock:{hash}` | 30s | SET NX EX | File write coordination |
| Rate Limiting | `rate:ip:{ip}` | 60s | INCR + EXPIRE | API throttling |
| Rate Limiting | `rate:user:{email}` | 60s | INCR + EXPIRE | Per-user throttling |
| @Cacheable | Spring Cache keys | 10 min | GET/SET (JSON) | Query result caching |
| Cluster Events | `platform:cluster:events` | — | PUBLISH | JOINED/DEPARTED |

### Partner Cache: L1 + L2

```
PartnerCache.get(partnerId)
  │
  ├─ L1: ConcurrentHashMap.get(partnerId)  ~10ns
  │   └─ HIT → return PartnerSnapshot
  │
  ├─ L2: redis.GET("partner:snap:{partnerId}")  ~0.5ms
  │   └─ HIT → deserialize → populate L1 → return
  │
  └─ L3: partnerRepository.findById(partnerId)  ~5ms
      └─ HIT → serialize → populate L1 + L2 → return

Bulk refresh: every 60s, loads ALL partners from PostgreSQL → populates L1 + L2
Eviction: on account.* RabbitMQ event AND events.account Kafka event (dual-subscribe)
```

---

## Failure Scenarios

| Failure | Impact | Automatic Recovery |
|---------|--------|-------------------|
| RabbitMQ down | File dispatch blocked | RoutingEngine falls back to synchronous processing. Zero data loss. |
| Redis down | Cache misses, rate limits per-pod only, VFS locks fail | L1 cache serves partner data. Rate limiter falls back to in-memory. Service registry falls back to PostgreSQL. |
| Redpanda down | Per-function step distribution stops | SEDA in-JVM thread pools handle all steps locally. Checkpoint + lease reaper still work via PostgreSQL. |
| PostgreSQL down | Everything stops | True SPOF. All services depend on it. Deploy HA (Patroni/RDS). |
| Pod crash mid-step | Checkpoint stuck IN_PROGRESS | LeaseReaperJob detects expired lease within 60s → schedules restart. |
| Message poison (bad format) | Consumer crashes repeatedly | x-death header count ≥ 3 → ACK + DLQ. No infinite loop. |

---

## Production Scaling

| System | Current (Dev) | Production Minimum | Scale Trigger |
|--------|--------------|-------------------|---------------|
| RabbitMQ | 1 node, 0.8 memory watermark | 3-node quorum cluster | Queue depth > 10K |
| Redis | 1 node, AOF+RDB | Redis Sentinel (3 nodes) | Latency > 5ms |
| Redpanda | 1 node, 512MB, 1 CPU | 3-node cluster, 3 replicas | Consumer lag growing |
| PostgreSQL | 1 node, max_conn=400 | Patroni HA + PgBouncer | Connection count > 80% |

# TranzFer MFT: Path to 1 Million Files/Day
## Rule Engine & Distributed Pipeline Architecture Enhancement

**Author:** Expert Architecture Analysis  
**Date:** 2026-04-13  
**Scope:** End-to-end pipeline — every stage from file arrival to delivery completion  
**Principle:** Water-tight pipeline. No leaks. Every stage measured, bounded, and recoverable.

---

## Table of Contents

1. [Current State Assessment](#1-current-state-assessment)
2. [The Pipeline Map — Where Water Flows](#2-the-pipeline-map)
3. [Bottleneck Analysis — Where Water Leaks](#3-bottleneck-analysis)
4. [Phase 1: Eliminate Hot-Path I/O (Week 1-2)](#4-phase-1)
5. [Phase 2: Unlock Concurrency (Week 2-3)](#5-phase-2)
6. [Phase 3: Distributed Rule Engine (Week 3-4)](#6-phase-3)
7. [Phase 4: Storage Pipeline at Scale (Week 4-5)](#7-phase-4)
8. [Phase 5: Database Under Load (Week 5-6)](#8-phase-5)
9. [Phase 6: Observability & Back-Pressure (Week 6-7)](#9-phase-6)
10. [Phase 7: Production Hardening (Week 7-8)](#10-phase-7)
11. [Capacity Model](#11-capacity-model)
12. [Risk Register](#12-risk-register)
13. [Migration Strategy](#13-migration-strategy)

---

## 1. Current State Assessment

### 1.1 What Works Well

| Component | Strength | Evidence |
|-----------|----------|----------|
| **FlowRuleRegistry** | Pre-compiled predicates, zero-lock reads, ConcurrentHashMap + volatile snapshot | 333K matches/sec single-thread, <3us per 206 rules |
| **FlowRuleCompiler** | All regex, CIDR, glob compiled once at load time | Zero recompilation at match time |
| **SEDA Pipeline** | Virtual threads via `Thread.ofVirtual()`, bounded queues, admission control | INTAKE q=1000/t=16, PIPELINE q=500/t=32, DELIVERY q=2000/t=16 |
| **CAS Storage** | Content-addressed dedup, ParallelIOEngine with striped writes | SHA-256 keys, zero-copy sendfile, AES-256-GCM at rest |
| **VFS** | INLINE/STANDARD/CHUNKED bucket routing, WAIP crash recovery | <64KB inline in DB, 64KB-64MB CAS, >64MB chunked |
| **Fabric (Redpanda)** | Dual-bus architecture (RabbitMQ + Kafka), per-function topics | Partition keys by trackId, 32 default partitions |
| **Circuit Breakers** | Resilience4j on all ResilientServiceClient subclasses | 50% failure threshold, 30s open state, 3 retries |

### 1.2 Current Throughput Reality

```
File arrival rate:       ~4 files/sec (345,600/day)
Target:                  ~12 files/sec (1,000,000/day)  
Stretch target:          ~116 files/sec (10,000,000/day)
```

**Gap to 1M/day: 3x improvement needed.** This is achievable without architectural rewrites — it requires surgical elimination of hot-path bottlenecks and unlocking existing concurrency primitives.

### 1.3 Pipeline Stages — Current Timing Budget

| Stage | Current Latency | Target (1M/day) | Gap Factor |
|-------|----------------|------------------|------------|
| SFTP session → file.uploaded event | 5-15ms | 5ms | 1-3x |
| RabbitMQ consumer picks up event | 50-250ms (prefetch=1) | 5ms | 10-50x |
| Account lookup (DB) | 3-8ms | 0ms (cached) | 3-8x |
| Partner slug lookup (DB) | 3-8ms | 0ms (cached) | 3-8x |
| MatchContext build + rule match | <1ms | <1ms | OK |
| FileFlow entity fetch (DB) | 3-8ms | 0ms (pre-loaded) | 3-8x |
| Source checksum (SHA-256, full read) | 10-500ms (size-dependent) | 0ms (deferred) | 10-500x |
| FileTransferRecord write (DB) | 5-15ms | 2ms (async batch) | 2-7x |
| Storage push (HTTP to storage-manager) | 50-200ms | 20ms (local or streaming) | 2-10x |
| Flow step execution (per step) | 100-500ms | 50-200ms | 2x |
| Per-step DB checkpoint write | 5-15ms | 0ms (batched) | 5-15x |
| Per-step storage checkpoint | 50-200ms | 0ms (deferred) | 50-200x |
| Delivery to destination | 100-2000ms | 100-2000ms | OK (I/O bound) |
| **Total per file (5-step flow)** | **800-4000ms** | **200-500ms** | **4-8x** |

---

## 2. The Pipeline Map

```
                    THE WATER PIPELINE — Every Drop Tracked
                    ========================================

    INTAKE ZONE                    PROCESSING ZONE                 DELIVERY ZONE
    ───────────                    ────────────────                 ─────────────

    ┌─────────┐     ┌──────────┐     ┌──────────────┐     ┌────────────┐
    │  SFTP   │────▶│          │────▶│              │────▶│            │
    │ Service │     │          │     │  Rule Match  │     │   Flow     │
    ├─────────┤     │ RabbitMQ │     │  (in-memory) │     │  Engine    │
    │  FTP    │────▶│ Backpress│     │              │     │ (SEDA/     │
    │ Service │     │ Queue    │     │  Zero I/O    │     │  Fabric)   │
    ├─────────┤     │          │     │  <3 us       │     │            │
    │  AS2    │────▶│ prefetch │     └──────┬───────┘     └─────┬──────┘
    │ Service │     │ =1→50   │            │                    │
    ├─────────┤     │          │     ┌──────▼───────┐     ┌─────▼──────┐
    │ Gateway │────▶│ 2-4→32  │     │  MatchContext │     │  Steps:    │
    │ (HTTP)  │     │ consumers│     │  Build        │     │  SCREEN    │
    └─────────┘     └──────────┘     │              │     │  ENCRYPT   │
                                     │ ⚠ DB calls: │     │  COMPRESS  │
         ▲                           │ partner slug │     │  TRANSFORM │
         │                           │ account data │     │  FORWARD   │
    ┌────┴────┐                      │ flow entity  │     └─────┬──────┘
    │ file.   │                      └──────────────┘           │
    │uploaded │                                           ┌─────▼──────┐
    │ event   │     ┌──────────────────────────────────┐  │  Delivery  │
    └─────────┘     │     STORAGE MANAGER (CAS)        │  │  Engine    │
                    │                                  │  │            │
                    │  store() ←── source file         │  │ SFTP/FTP/  │
                    │  register() ←── VIRTUAL link     │  │ AS2/HTTP/  │
                    │  checkpoint() ←── per-step snap  │  │ Kafka      │
                    │  retrieve() ──▶ delivery read    │  └─────┬──────┘
                    │                                  │        │
                    │  ⚠ Per-step checkpoint = N RPCs  │  ┌─────▼──────┐
                    └──────────────────────────────────┘  │  Complete  │
                                                          │  Record    │
                    ┌──────────────────────────────────┐  │  Update    │
                    │        POSTGRESQL                 │  │            │
                    │                                  │  │ dest cksum │
                    │  FileTransferRecord (per file)   │  │ status     │
                    │  FlowExecution (per flow)        │  │ completedAt│
                    │  FlowStepSnapshot (per step)     │  └────────────┘
                    │  TransferActivityView (mat.view) │
                    │                                  │
                    │  ⚠ Sync writes in hot path       │
                    │  ⚠ Per-step checkpoint writes     │
                    │  ⚠ No write batching              │
                    └──────────────────────────────────┘

    LEGEND:
    ────▶  Data flow (file bytes or metadata)
    ⚠      Bottleneck identified
```

---

## 3. Bottleneck Analysis — Ranked by Impact

### TIER 1: Blocking (Must fix for 1M/day)

| # | Bottleneck | Location | Impact | Current | Target |
|---|-----------|----------|--------|---------|--------|
| **B1** | Partner slug DB lookup per file | RoutingEngine.java:159-160 | Every file = 1 DB roundtrip to resolve partner slug for MatchContext | 3-8ms/file | 0ms (cached) |
| **B2** | RabbitMQ prefetch=1, concurrency=2-4 | FileUploadEventConsumer.java:32 | Maximum 4 files in-flight per service instance | 4 files/sec | 50+ files/sec |
| **B3** | Account re-fetch in consumer | FileUploadEventConsumer.java:42 | DB roundtrip to load account that was already known at publish time | 3-8ms/file | 0ms (in event) |
| **B4** | Synchronous source checksum | RoutingEngine.java:190-200 | Full file read + SHA-256 digest blocks pipeline | 10-500ms/file | 0ms (deferred) |
| **B5** | FileTransferRecord sync write | RoutingEngine.java:215 | DB INSERT blocks before flow can start | 5-15ms/file | Async batch |
| **B6** | FileFlow entity fetch after match | RoutingEngine.java:179 | DB SELECT to load flow definition including JSONB steps | 3-8ms/file | 0ms (pre-loaded) |

### TIER 2: Scaling (Required for sustained 1M/day)

| # | Bottleneck | Location | Impact |
|---|-----------|----------|--------|
| **B7** | Per-step DB checkpoint write | FlowProcessingEngine.java:478 | N steps = N DB writes (flow execution update) |
| **B8** | Per-step storage checkpoint | FlowProcessingEngine.java:448-463 | N steps = N HTTP calls to storage-manager |
| **B9** | Fresh execution poll between steps | FlowProcessingEngine.java:541-550 | DB read after each step to check termination flag |
| **B10** | Sequential step execution | FlowProcessingEngine.java:350-560 | Steps run one-at-a-time even when independent |
| **B11** | Base64 encoding for remote routing | RoutingEngine.java:525-527 | Full file loaded into heap + 33% size overhead |
| **B12** | VIRTUAL mode file materialization | FlowProcessingEngine.java:1649-1680 | Temp file written for each plugin step (defeats zero-copy) |

### TIER 3: Professional-Grade (Competitive advantage)

| # | Bottleneck | Location | Impact |
|---|-----------|----------|--------|
| **B13** | 30s rule refresh cycle | FlowRuleRegistryInitializer.java:57 | 30s stale window when rules change |
| **B14** | No per-rule metrics | FlowRuleRegistry.java | Can't identify hot/cold rules or latency outliers |
| **B15** | No match explanation API | FlowMatchEngine.java | Users can't debug why a file matched (or didn't) |
| **B16** | Recovery job polls every 5 min | FlowExecutionRecoveryJob.java:27 | 5-minute window where stuck executions aren't detected |
| **B17** | No partition strategy for records | file_transfer_records table | Unbounded table growth (100M+ rows at scale) |

---

## 4. Phase 1: Eliminate Hot-Path I/O (Week 1-2)

**Goal:** Remove all synchronous database and network calls from the file matching hot path. After this phase, a file goes from "arrived" to "flow started" with ZERO database queries.

### 4.1 Cache Partner Data in Memory + Redis

**Problem (B1):** Every file triggers `partnerRepository.findById(partnerId).map(p -> p.getSlug())` — a synchronous DB query in the hottest code path.

**Solution:** Two-tier cache: ConcurrentHashMap (L1, per-JVM) + Redis (L2, shared across pods).

```java
// shared-platform: PartnerCache.java (NEW)
@Component
public class PartnerCache {

    // L1: In-process, zero-latency, per-pod
    private final ConcurrentHashMap<UUID, PartnerSnapshot> l1 = new ConcurrentHashMap<>();

    // L2: Redis, shared across pods, 5-minute TTL
    @Autowired private StringRedisTemplate redis;
    @Autowired private PartnerRepository partnerRepository;

    private static final Duration REDIS_TTL = Duration.ofMinutes(5);
    private static final String PREFIX = "partner:snapshot:";

    public record PartnerSnapshot(UUID id, String slug, String companyName) {}

    /**
     * Zero-cost lookup: L1 first, then L2 (Redis), then DB.
     * L1 is populated on first access + refreshed every 60s via scheduled task.
     */
    public PartnerSnapshot get(UUID partnerId) {
        // L1: ~10ns
        PartnerSnapshot cached = l1.get(partnerId);
        if (cached != null) return cached;

        // L2: ~0.5ms (Redis GET)
        String json = redis.opsForValue().get(PREFIX + partnerId);
        if (json != null) {
            cached = deserialize(json);
            l1.put(partnerId, cached);
            return cached;
        }

        // L3: ~5ms (DB — only first access or after TTL expiry)
        Partner partner = partnerRepository.findById(partnerId).orElse(null);
        if (partner == null) return null;
        cached = new PartnerSnapshot(partner.getId(), partner.getSlug(), partner.getCompanyName());
        l1.put(partnerId, cached);
        redis.opsForValue().set(PREFIX + partnerId, serialize(cached), REDIS_TTL);
        return cached;
    }

    // Bulk refresh every 60s — keeps L1 warm, L2 fresh
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void refreshAll() {
        partnerRepository.findAll().forEach(p -> {
            PartnerSnapshot snap = new PartnerSnapshot(p.getId(), p.getSlug(), p.getCompanyName());
            l1.put(p.getId(), snap);
            redis.opsForValue().set(PREFIX + p.getId(), serialize(snap), REDIS_TTL);
        });
    }

    // Evict on partner update (called from AccountEventConsumer + API)
    public void evict(UUID partnerId) {
        l1.remove(partnerId);
        redis.delete(PREFIX + partnerId);
    }
}
```

**Integration point in RoutingEngine.java:159-160:**
```java
// BEFORE (B1: 3-8ms DB call per file):
String partnerSlug = partnerRepository.findById(sourceAccount.getPartnerId())
    .map(Partner::getSlug).orElse(null);

// AFTER (0ms — L1 cache hit):
PartnerCache.PartnerSnapshot partner = partnerCache.get(sourceAccount.getPartnerId());
String partnerSlug = partner != null ? partner.slug() : null;
```

**Throughput gain:** 100x for partner lookup (5ms → 10ns)

### 4.2 Enrich FileUploadedEvent — Eliminate Consumer DB Lookups

**Problem (B3):** FileUploadEventConsumer re-fetches `TransferAccount` by ID even though the publisher already had it.

**Solution:** Include essential account fields in the event payload. Consumer skips DB.

```java
// FileUploadedEvent.java — add fields:
public class FileUploadedEvent implements Serializable {
    // ... existing fields ...

    // NEW: Embedded account snapshot (avoids consumer DB lookup)
    private String storageMode;        // "VIRTUAL" or "PHYSICAL"
    private UUID partnerId;            // for MatchContext partner resolution
    private String homeDir;            // for path computation
    private String protocol;           // already exists as enum
    // TransferAccount still needed for folder evaluation — pass ID for lazy fetch
}
```

**In RoutingEngine.onFileUploaded() — enrich before publish:**
```java
FileUploadedEvent event = FileUploadedEvent.builder()
    .trackId(trackId)
    .accountId(account.getId())
    .username(account.getUsername())
    .protocol(account.getProtocol())
    .storageMode(account.getStorageMode())   // NEW
    .partnerId(account.getPartnerId())       // NEW
    .homeDir(account.getHomeDir())           // NEW
    // ... rest ...
    .build();
```

**In FileUploadEventConsumer — skip DB when possible:**
```java
@RabbitListener(queues = "file.upload.events", concurrency = "${upload.consumer.concurrency:2-4}")
public void handle(FileUploadedEvent event) {
    // Only fetch full account if legacy fields needed (folder evaluation path)
    // For flow-matched path: event fields are sufficient
    TransferAccount account = needsFullAccount(event)
        ? accountRepository.findById(event.getAccountId()).orElse(null)
        : event.toMinimalAccount();  // Construct from event fields
    // ...
}
```

**Throughput gain:** Eliminates 1 DB roundtrip per file (3-8ms saved)

### 4.3 Pre-Load Flow Definitions in CompiledFlowRule

**Problem (B6):** After rule match, `flowRepository.findById(matchedRule.flowId())` fetches the full FileFlow entity (with JSONB steps array) from DB.

**Solution:** Include flow definition in the compiled rule. Rules are already reloaded every 30s — the flow data is already in memory at that point.

```java
// FlowRuleCompiler.java — include flow data in compiled rule:
public record CompiledFlowRule(
    UUID flowId,
    int priority,
    Direction direction,
    Protocol protocol,
    Predicate<MatchContext> matcher,
    FileFlow flow             // NEW: full flow entity snapshot (steps, source, dest, etc.)
) {}

// FlowRuleRegistryInitializer.refresh() — already fetches flows:
List<FileFlow> flows = flowRepository.findByActiveTrueOrderByPriorityAsc();
Map<UUID, CompiledFlowRule> compiled = flows.stream()
    .map(flow -> FlowRuleCompiler.compile(flow))  // flow already available
    .collect(toMap(CompiledFlowRule::flowId, identity()));
registry.loadAll(compiled);
```

**In RoutingEngine.java:179 — use pre-loaded flow:**
```java
// BEFORE (B6: 3-8ms DB call):
CompiledFlowRule matchedRule = flowRuleRegistry.findMatch(matchContext);
FileFlow matchedFlow = flowRepository.findById(matchedRule.flowId()).orElse(null);

// AFTER (0ms — already in compiled rule):
CompiledFlowRule matchedRule = flowRuleRegistry.findMatch(matchContext);
FileFlow matchedFlow = matchedRule.flow();  // Pre-loaded snapshot
```

**Throughput gain:** Eliminates 1 DB roundtrip per matched file (3-8ms saved)

### 4.4 Defer Source Checksum to Background

**Problem (B4):** `Files.readAllBytes()` + SHA-256 digest reads the entire file synchronously before flow execution starts. For a 50MB file this is 10-50ms; for 1GB files it's 500ms+.

**Solution:** Compute checksum asynchronously after record creation. Storage-manager already computes SHA-256 during CAS write — reuse it.

```java
// RoutingEngine.onFileUploadedInternal() — defer checksum:

// BEFORE (B4: blocks pipeline):
byte[] hash = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(Paths.get(absoluteSourcePath)));
sourceChecksum = HexFormat.of().formatHex(hash);

// AFTER: Set checksum from storage-manager response (already computed during CAS write)
// For PHYSICAL mode: storage-manager returns sha256 in store/storeStream response
// For VIRTUAL mode: VFS already has storageKey which IS the SHA-256
// FileTransferRecord.sourceChecksum populated from storage response, not re-read

// If no storage push (rare edge case): fire async task
if (sourceChecksum == null) {
    checksumExecutor.submit(() -> {
        String checksum = computeSha256(absoluteSourcePath);
        recordRepository.updateSourceChecksum(record.getId(), checksum);
    });
}
```

**Throughput gain:** 10-500ms saved per file (size-dependent). Pipeline starts immediately.

### 4.5 Async Batch FileTransferRecord Writes

**Problem (B5):** `recordRepository.save(transferRecord)` is a synchronous DB INSERT that blocks flow execution.

**Solution:** Write-behind pattern — accumulate records in a bounded queue, flush every 100ms or 50 records (whichever comes first).

```java
// shared-platform: TransferRecordBatchWriter.java (NEW)
@Component
public class TransferRecordBatchWriter {

    private final BlockingQueue<FileTransferRecord> buffer =
        new LinkedBlockingQueue<>(5000);
    private final FileTransferRecordRepository repository;

    @Scheduled(fixedDelay = 100)  // Flush every 100ms
    public void flush() {
        List<FileTransferRecord> batch = new ArrayList<>(50);
        buffer.drainTo(batch, 50);
        if (!batch.isEmpty()) {
            repository.saveAll(batch);  // Hibernate batch_size=25 kicks in
        }
    }

    /**
     * Non-blocking submit. Returns immediately.
     * Record is durable within 100ms (flush interval).
     * If buffer full: fall back to synchronous write (backpressure signal).
     */
    public void submit(FileTransferRecord record) {
        if (!buffer.offer(record)) {
            // Backpressure: buffer full, write synchronously
            repository.save(record);
        }
    }
}
```

**Risk mitigation:** If the JVM crashes before flush, the file is still in RabbitMQ (not ACK'd until flow completes). The record will be re-created on redelivery.

**Throughput gain:** 5-15ms saved per file. Batch writes reduce DB round-trips by 50x.

### Phase 1 Summary

| Fix | Bottleneck | Latency Saved | Complexity |
|-----|-----------|---------------|------------|
| Partner cache (L1+L2) | B1 | 3-8ms/file | Medium |
| Enriched events | B3 | 3-8ms/file | Low |
| Pre-loaded flows | B6 | 3-8ms/file | Low |
| Deferred checksum | B4 | 10-500ms/file | Low |
| Batch record writes | B5 | 5-15ms/file | Medium |
| **Total** | | **24-539ms/file** | |

**After Phase 1:** Hot path drops from 800-4000ms to ~300-500ms. Throughput doubles to ~8 files/sec.

---

## 5. Phase 2: Unlock Concurrency (Week 2-3)

**Goal:** Scale from 4 concurrent file processors to 50+.

### 5.1 RabbitMQ Tuning — Prefetch & Concurrency

**Problem (B2):** `prefetch=1` and `concurrency=2-4` means at most 4 files are processing simultaneously per pod. RabbitMQ holds all other messages. With 1M files/day = 12 files/sec, we need at least 12 concurrent.

**Solution:** Increase prefetch to match SEDA queue capacity. Increase consumer concurrency.

```yaml
# application.yml — per-service tuning:

# Protocol services (SFTP, FTP, Gateway, AS2):
upload:
  consumer:
    concurrency: 8-32          # Was: 2-4. Scale to available vCPUs.
    prefetch: 50               # Was: 1. Allow 50 messages in-flight per consumer.

# Why prefetch=50:
# - SEDA INTAKE queue has capacity 1000. With 32 consumers × 50 prefetch = 1600 in-flight max.
# - RabbitMQ delivers 50 messages upfront, consumer ACKs as each completes.
# - If consumer crashes: 50 messages redelivered (acceptable — idempotent processing).
# - If flow processing takes 300ms average: 50 prefetch × 32 consumers = 5,333 files/sec throughput ceiling.
```

**Corresponding SEDA tuning:**
```java
// FlowStageManager.java — scale INTAKE stage:
// BEFORE: INTAKE(queue=1000, threads=16)
// AFTER:
new ProcessingStage<>("INTAKE", 5000, 128);   // 5000 queue, 128 virtual threads
new ProcessingStage<>("PIPELINE", 2000, 256);  // 2000 queue, 256 virtual threads
new ProcessingStage<>("DELIVERY", 5000, 64);   // 5000 queue, 64 virtual threads

// Virtual threads: no OS thread limit. 256 "threads" = 256 concurrent flows.
// Actual OS threads = number of CPU cores (scheduled by JVM carrier threads).
```

**Throughput gain:** 50x (4 files/sec → 200 files/sec theoretical per pod)

### 5.2 Connection Pool Scaling

**Problem:** HikariCP `maximum-pool-size=15` becomes the bottleneck when 32+ consumers each need DB connections.

**Solution:** Scale pool to match concurrency. Rule of thumb: `pool_size = (core_count * 2) + disk_spindle_count`.

```yaml
# Services with high concurrency (SFTP, Gateway, Onboarding):
spring:
  datasource:
    hikari:
      maximum-pool-size: 40     # Was: 15. Match consumer threads.
      minimum-idle: 10          # Was: 5. Keep warm connections.
      connection-timeout: 5000  # Was: 30000. Fail fast, don't block.

# PostgreSQL max_connections must support:
# 22 services × 40 pool max = 880 connections worst case
# Realistic: 22 × 15 active = 330 connections (hot path only)
# PostgreSQL default: 100. MUST increase to 500+.
```

**Docker-compose PostgreSQL tuning:**
```yaml
postgres:
  command: >
    postgres
    -c max_connections=500
    -c shared_buffers=1GB
    -c effective_cache_size=3GB
    -c work_mem=16MB
    -c maintenance_work_mem=256MB
    -c wal_buffers=32MB
    -c checkpoint_completion_target=0.9
    -c random_page_cost=1.1
    -c effective_io_concurrency=200
    -c max_wal_size=2GB
    -c min_wal_size=1GB
```

### 5.3 Horizontal Pod Scaling Strategy

**For 1M files/day (12 files/sec sustained):**

| Service | Current Replicas | Target Replicas | Rationale |
|---------|-----------------|-----------------|-----------|
| sftp-service | 1 (+2 inactive) | 4 | ~3 files/sec per pod × 4 = 12/sec |
| ftp-service | 1 (+2 inactive) | 2 | FTP lower volume typically |
| gateway-service | 1 | 2 | HTTP uploads, API calls |
| as2-service | 1 | 2 | AS2 partner connections |
| encryption-service | 1 | 4 | CPU-heavy (PGP/AES). Biggest bottleneck for multi-step flows. |
| storage-manager | 1 | 3 | I/O heavy (CAS writes, checkpoints) |
| screening-service | 1 | 2 | DLP/sanctions scanning (CPU) |
| onboarding-api | 1 | 2 | Activity monitor, SSE streams |
| config-service | 1 | 2 | Flow management, rule publishing |

**Total pods: 23** (from current 17 active). Memory: ~23 × 400MB = 9.2GB JVM heap.

### Phase 2 Summary

| Fix | Bottleneck | Throughput Gain |
|-----|-----------|----------------|
| Prefetch 50, concurrency 32 | B2 | 50x |
| Connection pool 40 | Supporting | Required |
| Horizontal scaling (23 pods) | Supporting | 4x |
| **Cumulative** | | **~200 files/sec (17M/day)** |

**After Phase 2:** 17M files/day theoretical capacity. In practice ~5M/day due to storage and DB bottlenecks (addressed in phases 4-5).

---

## 6. Phase 3: Distributed Rule Engine (Week 3-4)

**Goal:** Make the rule engine production-grade — observable, debuggable, and fast even with 10,000+ rules.

### 3.1 Protocol+Direction Index for Fast-Path Elimination

**Problem:** `findMatch()` iterates ALL rules sequentially. At 206 rules this is <3us. At 10,000 rules: ~150us (still fast but wasteful).

**Solution:** Index rules by (protocol, direction) for O(1) bucket selection.

```java
// FlowRuleRegistry.java — add indexed lookup:
public class FlowRuleRegistry {
    // Existing: flat list for full scan
    private volatile List<CompiledFlowRule> orderedRules;

    // NEW: indexed by (protocol, direction) for fast-path
    private volatile Map<String, List<CompiledFlowRule>> indexedRules;

    public CompiledFlowRule findMatch(MatchContext ctx) {
        // Fast-path: check indexed bucket first
        String key = ctx.protocol() + ":" + ctx.direction();
        List<CompiledFlowRule> bucket = indexedRules.getOrDefault(key, List.of());
        for (CompiledFlowRule rule : bucket) {
            if (rule.matcher().test(ctx)) return rule;
        }
        // Fallback: rules with null protocol/direction (wildcard)
        String wildcardKey = "ANY:ANY";
        bucket = indexedRules.getOrDefault(wildcardKey, List.of());
        for (CompiledFlowRule rule : bucket) {
            if (rule.matcher().test(ctx)) return rule;
        }
        return null;
    }

    // Rebuild index on loadAll():
    private void rebuildIndex(List<CompiledFlowRule> rules) {
        Map<String, List<CompiledFlowRule>> idx = new HashMap<>();
        for (CompiledFlowRule rule : rules) {
            String proto = rule.protocol() != null ? rule.protocol().name() : "ANY";
            String dir = rule.direction() != null ? rule.direction().name() : "ANY";
            String key = proto + ":" + dir;
            idx.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
            // Also add to wildcard buckets for partial matches
            if (!"ANY".equals(proto)) {
                idx.computeIfAbsent("ANY:" + dir, k -> new ArrayList<>()).add(rule);
            }
            if (!"ANY".equals(dir)) {
                idx.computeIfAbsent(proto + ":ANY", k -> new ArrayList<>()).add(rule);
            }
            idx.computeIfAbsent("ANY:ANY", k -> new ArrayList<>()).add(rule);
        }
        this.indexedRules = Map.copyOf(idx);  // Immutable snapshot
    }
}
```

**Throughput gain at scale:** At 10,000 rules with 5 protocols × 2 directions = 10 buckets, each bucket has ~1,000 rules. Eliminates 90% of comparisons.

### 3.2 Match Explanation API

**Problem (B15):** When a file doesn't match any rule, operators have no way to understand why. This is a support nightmare at scale.

```java
// FlowMatchEngine.java — add explanation mode:
public record MatchExplanation(
    UUID flowId,
    String flowName,
    boolean matched,
    List<CriterionResult> criteria
) {
    public record CriterionResult(
        String field,        // "filename", "protocol", "partnerId", etc.
        String operator,     // "REGEX", "EQ", "IN", "CIDR", etc.
        String expected,     // What the rule expects
        String actual,       // What the file has
        boolean passed       // true if this criterion matched
    ) {}
}

// New method:
public List<MatchExplanation> explainMatch(MatchContext ctx) {
    return orderedRules.stream()
        .map(rule -> {
            List<CriterionResult> results = evaluateWithExplanation(rule, ctx);
            boolean allPassed = results.stream().allMatch(CriterionResult::passed);
            return new MatchExplanation(rule.flowId(), rule.flow().getName(),
                allPassed, results);
        })
        .collect(toList());
}
```

**API endpoint (config-service):**
```
POST /api/file-flows/explain-match
Body: { "filename": "EDI_850_*.txt", "protocol": "SFTP", "partnerId": "..." }
Response: [
  { "flowId": "...", "flowName": "EDI Inbound", "matched": false,
    "criteria": [
      { "field": "filename", "operator": "REGEX", "expected": "^EDI_850.*",
        "actual": "EDI_850_2026.txt", "passed": true },
      { "field": "partnerId", "operator": "EQ", "expected": "partner-123",
        "actual": "partner-456", "passed": false }  // <-- This is why it didn't match
    ]
  }
]
```

### 3.3 Per-Rule Metrics (Prometheus)

**Problem (B14):** No visibility into which rules match most frequently, which are never hit, or which have high latency.

```java
// FlowRuleRegistry.java — add Micrometer counters:
@Component
public class FlowRuleRegistry {
    @Autowired private MeterRegistry meterRegistry;

    // Per-rule counters (lazy-initialized on first match)
    private final ConcurrentHashMap<UUID, Counter> matchCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Counter> evalCounters = new ConcurrentHashMap<>();
    private final Timer matchTimer;

    public CompiledFlowRule findMatch(MatchContext ctx) {
        Timer.Sample sample = Timer.start(meterRegistry);
        CompiledFlowRule result = null;
        for (CompiledFlowRule rule : orderedRules) {
            evalCounters.computeIfAbsent(rule.flowId(),
                id -> Counter.builder("rule.evaluations")
                    .tag("flow_id", id.toString())
                    .tag("flow_name", rule.flow().getName())
                    .register(meterRegistry)
            ).increment();

            if (rule.matcher().test(ctx)) {
                matchCounters.computeIfAbsent(rule.flowId(),
                    id -> Counter.builder("rule.matches")
                        .tag("flow_id", id.toString())
                        .tag("flow_name", rule.flow().getName())
                        .register(meterRegistry)
                ).increment();
                result = rule;
                break;
            }
        }
        sample.stop(Timer.builder("rule.match.duration")
            .tag("matched", result != null ? "true" : "false")
            .register(meterRegistry));

        if (result == null) {
            meterRegistry.counter("rule.unmatched",
                "protocol", ctx.protocol().name(),
                "direction", ctx.direction() != null ? ctx.direction().name() : "ANY"
            ).increment();
        }
        return result;
    }
}
```

**Grafana queries this enables:**
- `rate(rule_matches_total[5m])` — matches per second per rule
- `rule_evaluations_total - rule_matches_total` — wasted evaluations (rule ordering opportunity)
- `histogram_quantile(0.99, rule_match_duration_seconds_bucket)` — p99 match latency
- `rule_unmatched_total` by protocol — unmatched files (missing rules)

### 3.4 Reduce Refresh Cycle: 30s → 5s (Event-Driven Primary)

**Problem (B13):** 30-second refresh means a new rule takes up to 30s to activate across all pods.

**Solution:** Make RabbitMQ event the primary update path; reduce periodic poll to 5s as safety net.

```java
// FlowRuleRegistryInitializer.java:
@Scheduled(fixedDelay = 5_000, initialDelay = 10_000)  // Was: 30_000
public void refresh() {
    // Only actually reload if event-driven path missed an update
    // Use version counter: DB has version, registry has version
    long dbVersion = flowRepository.countByModifiedAtAfter(lastRefreshTime);
    if (dbVersion == 0) return;  // No changes since last refresh — skip DB load
    // ... existing reload logic ...
}
```

**Net effect:** Event-driven updates arrive in <1s. Periodic poll catches edge cases every 5s. Combined: <5s worst-case staleness.

---

## 7. Phase 4: Storage Pipeline at Scale (Week 4-5)

**Goal:** Eliminate per-step storage overhead. At 1M files/day with avg 3 steps/flow = 3M storage operations/day. Each at 50-200ms = unsustainable.

### 4.1 Lazy Checkpointing — Only Checkpoint on Failure or Critical Steps

**Problem (B7, B8):** Every step writes a DB checkpoint + pushes file to storage-manager. For a 5-step flow: 5 DB writes + 5 HTTP calls = 500-1500ms of pure overhead.

**Solution:** Checkpoint strategy based on step type:

```java
// FlowProcessingEngine.java — selective checkpointing:
enum CheckpointStrategy {
    ALWAYS,      // APPROVE, FORWARD_* (delivery steps — must checkpoint before external call)
    ON_FAILURE,  // ENCRYPT, COMPRESS, TRANSFORM (recoverable from input)
    NEVER        // SCREEN, AUDIT_LOG (stateless, re-runnable)
}

private CheckpointStrategy checkpointStrategy(String stepType) {
    return switch (stepType) {
        case "APPROVE", "FORWARD_SFTP", "FORWARD_FTP",
             "FORWARD_AS2", "FORWARD_HTTP", "FILE_DELIVERY" -> ALWAYS;
        case "ENCRYPT_PGP", "ENCRYPT_AES", "COMPRESS_GZIP",
             "COMPRESS_ZIP", "DECOMPRESS_ZIP", "DECOMPRESS_GZIP",
             "TRANSFORM_CUSTOM" -> ON_FAILURE;
        case "SCREEN", "AUDIT_LOG", "WEBHOOK" -> NEVER;
        default -> ON_FAILURE;  // Safe default
    };
}

// In step loop:
for (int i = 0; i < steps.size(); i++) {
    FlowStep step = steps.get(i);
    Object output = executeStep(step, currentInput);

    CheckpointStrategy strategy = checkpointStrategy(step.getType());
    if (strategy == ALWAYS ||
        (strategy == ON_FAILURE && i == steps.size() - 1)) {
        // Checkpoint: save to storage + update DB
        checkpoint(exec, i, output);
    }
    // ON_FAILURE: only checkpoint if step fails (handled in catch block)
    // NEVER: skip checkpoint entirely
}
```

**Impact:** For typical 5-step flow (SCREEN → ENCRYPT → COMPRESS → FORWARD → AUDIT):
- Before: 5 checkpoints × 200ms = 1000ms overhead
- After: 1 checkpoint (FORWARD only) = 200ms overhead
- **Savings: 800ms per flow (80% reduction)**

### 4.2 Streaming Step Pipeline (Zero Temp Files for VIRTUAL Mode)

**Problem (B12):** VIRTUAL mode materializes temp files for plugin steps. This defeats the zero-copy benefit.

**Solution:** Stream-based step interface. Steps receive InputStream, return InputStream.

```java
// New interface: StreamingFlowStep
public interface StreamingFlowStep {
    /**
     * Process a file as a stream. No temp files.
     * @param input Source data stream
     * @param metadata Step configuration + file context
     * @return Transformed data stream (lazy — bytes flow on demand)
     */
    InputStream process(InputStream input, StepMetadata metadata);

    /**
     * Whether this step can operate in streaming mode.
     * Returns false if the step needs random access (e.g., ZIP with directory).
     */
    default boolean supportsStreaming() { return true; }
}

// Example: Streaming encryption step
public class StreamingEncryptPgpStep implements StreamingFlowStep {
    public InputStream process(InputStream input, StepMetadata metadata) {
        String keyId = metadata.config().get("keyId");
        // Returns a CipherInputStream wrapping the input — no buffering
        return encryptionClient.encryptPgpStream(input, keyId);
    }
}
```

**Pipeline composition (chained streams):**
```java
// FlowProcessingEngine — streaming chain for VIRTUAL mode:
InputStream pipeline = storageClient.streamByKey(initialStorageKey);
for (FlowStep step : steps) {
    StreamingFlowStep streamStep = streamStepRegistry.get(step.getType());
    if (streamStep != null && streamStep.supportsStreaming()) {
        pipeline = streamStep.process(pipeline, StepMetadata.from(step));
    } else {
        // Fallback: materialize, process, re-stream
        pipeline = materializeAndProcess(pipeline, step);
    }
}
// Write final result to storage-manager in one shot
storageClient.storeStream(pipeline, estimatedSize, filename, account, trackId);
```

**Impact:** N steps = 1 storage write (final output only). Intermediate results never leave memory.

### 4.3 Replace Base64 Remote Routing with Streaming Multipart

**Problem (B11):** Remote file forwarding uses `Files.readAllBytes()` + `Base64.getEncoder().encodeToString(bytes)`. For a 100MB file: 200MB heap allocation + encoding time.

```java
// RoutingEngine.routeRemotely() — streaming replacement:

// BEFORE (B11):
byte[] bytes = Files.readAllBytes(Paths.get(sourceAbsPath));
String encoded = Base64.getEncoder().encodeToString(bytes);
request.setFileContentBase64(encoded);  // 133% of file size in heap

// AFTER: Streaming multipart upload
MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
body.add("file", new FileSystemResource(Paths.get(sourceAbsPath)));
body.add("trackId", recordId);
body.add("destinationPath", request.getDestinationPath());

ResponseEntity<String> response = restTemplate.exchange(
    "http://{host}:{port}/internal/files/receive",
    HttpMethod.POST,
    new HttpEntity<>(body, headers),
    String.class
);
// File streams directly from disk → HTTP → remote service
// Zero heap allocation beyond 8KB buffer
```

**Impact:** OOM risk eliminated for large files. Memory: 100MB → 8KB per transfer.

---

## 8. Phase 5: Database Under Load (Week 5-6)

**Goal:** PostgreSQL must handle 1M inserts/day (12/sec) for transfer records, 3M inserts/day (35/sec) for step snapshots, and sub-millisecond reads for Activity Monitor.

### 5.1 Table Partitioning — file_transfer_records

**Problem (B17):** At 1M files/day, the file_transfer_records table grows by 30M rows/month. After 6 months: 180M rows. Query performance degrades.

**Solution:** Range partition by `uploaded_at` (monthly partitions).

```sql
-- V63__partition_transfer_records.sql

-- Step 1: Create partitioned table (new structure)
CREATE TABLE file_transfer_records_partitioned (
    LIKE file_transfer_records INCLUDING ALL
) PARTITION BY RANGE (uploaded_at);

-- Step 2: Create monthly partitions (auto-extend via pg_partman or manual)
CREATE TABLE ftr_2026_01 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE ftr_2026_02 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
-- ... through 2026-12 ...
CREATE TABLE ftr_2027_01 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

-- Step 3: Migrate data (online, non-blocking)
INSERT INTO file_transfer_records_partitioned
SELECT * FROM file_transfer_records;

-- Step 4: Swap tables (brief lock)
ALTER TABLE file_transfer_records RENAME TO file_transfer_records_old;
ALTER TABLE file_transfer_records_partitioned RENAME TO file_transfer_records;

-- Step 5: Partition-local indexes (each partition gets its own B-tree)
-- PostgreSQL automatically creates these on partitions
CREATE INDEX idx_ftr_part_track_id ON file_transfer_records(track_id);
CREATE INDEX idx_ftr_part_status_uploaded ON file_transfer_records(status, uploaded_at DESC);
CREATE INDEX idx_ftr_part_source_account ON file_transfer_records(source_account_id, uploaded_at DESC);
```

**Benefits:**
- Queries with `uploaded_at` filter only scan relevant partition(s)
- `DELETE FROM ftr_2025_06` drops entire partition instantly (vs. row-by-row delete)
- VACUUM runs per-partition (no table-wide bloat)
- Activity Monitor with date range = partition pruning (sub-millisecond)

### 5.2 Materialized View Refresh Strategy

**Current:** Refresh every 30s (ActivityViewRefresher). At 1M files/day, the materialized view has 1M+ rows. REFRESH MATERIALIZED VIEW CONCURRENTLY scans the entire base table.

**Problem:** At 10M+ rows, concurrent refresh takes 5-10s. Running every 30s = 15-30% of DB capacity on just refreshes.

**Solution: Incremental refresh via change data capture.**

```java
// ActivityViewIncrementalRefresher.java (replaces full refresh)
@Component
@ConditionalOnProperty(name = "activity.view.refresh.mode", havingValue = "incremental")
public class ActivityViewIncrementalRefresher {

    @Autowired private JdbcTemplate jdbc;

    // Track last refresh watermark
    private Instant lastRefreshWatermark = Instant.now().minus(Duration.ofHours(1));

    @Scheduled(fixedDelay = 10_000)  // Every 10s (was 30s)
    public void incrementalRefresh() {
        // Only refresh rows modified since last watermark
        // Uses a "refresh queue" table populated by triggers
        jdbc.execute("""
            INSERT INTO transfer_activity_view_staging
            SELECT /* same view query */
            FROM file_transfer_records r
            WHERE r.updated_at > ?
            /* ... joins ... */
        """);

        // Upsert staging into materialized view
        jdbc.execute("""
            INSERT INTO transfer_activity_view
            SELECT * FROM transfer_activity_view_staging
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                destination_checksum = EXCLUDED.destination_checksum,
                completed_at = EXCLUDED.completed_at,
                /* ... all mutable columns ... */
        """);

        jdbc.execute("TRUNCATE transfer_activity_view_staging");
        lastRefreshWatermark = Instant.now();
    }
}
```

**Alternative (simpler):** Keep REFRESH CONCURRENTLY but run it every 60s instead of 30s, and add a Redis cache layer for the Activity Monitor API (5s TTL). Users see 5s-old data for list view, real-time for SSE stream.

### 5.3 Flow Step Snapshot Write Optimization

**Current:** FlowStepSnapshot is written via JPA save() — one INSERT per step.

**At scale:** 1M files × 3 steps avg = 3M snapshots/day = 35 INSERTs/sec.

**Solution:** Batch writer (same pattern as TransferRecordBatchWriter):

```java
// FlowStepSnapshotBatchWriter.java
@Component
public class FlowStepSnapshotBatchWriter {
    private final BlockingQueue<FlowStepSnapshot> buffer = new LinkedBlockingQueue<>(10000);

    @Scheduled(fixedDelay = 200)  // Flush every 200ms
    public void flush() {
        List<FlowStepSnapshot> batch = new ArrayList<>(100);
        buffer.drainTo(batch, 100);
        if (!batch.isEmpty()) {
            snapshotRepository.saveAll(batch);  // batch_size=25
        }
    }
}
```

### 5.4 Read Replica for Activity Monitor

**For 10M+/day:** Activity Monitor queries should hit a read replica, not the primary.

```yaml
# application.yml (onboarding-api):
spring:
  datasource:
    # Primary: writes
    url: jdbc:postgresql://postgres:5432/filetransfer
    # Read replica: Activity Monitor, analytics, exports
    read-replica:
      url: jdbc:postgresql://postgres-replica:5432/filetransfer
      hikari:
        maximum-pool-size: 20
        read-only: true
```

```java
// @Transactional(readOnly = true) routes to read replica automatically
// via AbstractRoutingDataSource
@Transactional(readOnly = true)
public Page<TransferActivityView> getActivityMonitor(Specification<TransferActivityView> spec,
                                                      Pageable pageable) {
    return viewRepository.findAll(spec, pageable);  // Hits read replica
}
```

---

## 9. Phase 6: Observability & Back-Pressure (Week 6-7)

**Goal:** You can't optimize what you can't measure. Build comprehensive pipeline metrics so every stage is visible and every bottleneck is detectable in real-time.

### 6.1 Pipeline Metrics — Every Stage Measured

```java
// PipelineMetrics.java — centralized metrics for the entire file pipeline
@Component
public class PipelineMetrics {

    private final MeterRegistry registry;

    // === INTAKE METRICS ===
    public final Counter filesReceived;        // Total files arriving at protocol services
    public final Counter filesPublished;       // Files published to RabbitMQ
    public final Counter filesConsumed;        // Files picked up by consumers
    public final Timer intakeLatency;          // Time from upload to consumer pickup
    public final Gauge rabbitQueueDepth;       // file.upload.events queue depth

    // === MATCHING METRICS ===
    public final Counter rulesMatched;         // Files matched to a flow
    public final Counter rulesUnmatched;       // Files with no matching flow
    public final Timer matchLatency;           // Time to evaluate all rules
    public final Gauge activeRuleCount;        // Number of compiled rules in registry

    // === PROCESSING METRICS ===
    public final Timer stepLatency;            // Per-step-type latency distribution
    public final Counter stepFailures;         // Per-step-type failure count
    public final Gauge sedaIntakeQueueDepth;   // SEDA INTAKE queue utilization
    public final Gauge sedaPipelineQueueDepth; // SEDA PIPELINE queue utilization
    public final Gauge sedaDeliveryQueueDepth; // SEDA DELIVERY queue utilization

    // === STORAGE METRICS ===
    public final Timer storageWriteLatency;    // CAS write latency
    public final Timer storageReadLatency;     // CAS read latency
    public final Counter storageDedupHits;     // Files that were already in CAS
    public final Gauge storageUsedBytes;       // Total storage consumption

    // === DELIVERY METRICS ===
    public final Timer deliveryLatency;        // Per-protocol delivery time
    public final Counter deliveryFailures;     // Per-protocol failure count
    public final Counter deliveryRetries;      // Retry attempts before success

    // === END-TO-END ===
    public final Timer e2eLatency;             // Upload-to-completion total time
    public final DistributionSummary fileSize; // File size distribution
    public final Counter completedTransfers;   // Total successful transfers
    public final Counter failedTransfers;      // Total failed transfers
}
```

### 6.2 Adaptive Back-Pressure

**Problem:** When downstream is slow (DB overloaded, storage-manager down), upstream keeps pushing. This causes OOM, queue overflow, and cascading failures.

**Solution:** Multi-layer back-pressure with circuit breaker integration:

```
Layer 1: RabbitMQ (prefetch limit)
  └─ Consumer only gets N messages at a time
  └─ Queue grows → RabbitMQ applies flow control to publishers
  └─ Publisher blocks → SFTP/FTP server slows down accept

Layer 2: SEDA (bounded queue + rejection)
  └─ queue.offer() returns false when full
  └─ Rejected: execute synchronously (degraded but not lost)
  └─ Metrics: rejected_count triggers scaling alert

Layer 3: DB (connection pool exhaustion)
  └─ HikariCP connection-timeout: 5000ms → fail fast
  └─ Circuit breaker opens after 5 failures
  └─ Stops new DB operations → SEDA rejects → RabbitMQ queues → publishers slow

Layer 4: Storage (circuit breaker)
  └─ StorageServiceClient wrapped in Resilience4j
  └─ Storage down → checkpoints skip (deferred mode)
  └─ Non-critical: flow continues without checkpoint
```

### 6.3 Health Score Per Stage

```java
// PipelineHealthIndicator.java — aggregate pipeline health for /actuator/health
@Component
public class PipelineHealthIndicator implements HealthIndicator {
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();

        // Queue saturation (warn > 70%, critical > 90%)
        double intakeSaturation = sedaIntakeQueue.size() / (double) sedaIntakeQueue.capacity();
        details.put("intake.saturation", String.format("%.1f%%", intakeSaturation * 100));

        // Consumer lag (warn > 1000, critical > 5000)
        long queueDepth = rabbitAdmin.getQueueInfo("file.upload.events").getMessageCount();
        details.put("rabbit.queue.depth", queueDepth);

        // Error rate (warn > 0.5%, critical > 1%)
        double errorRate = failedTransfers.count() / (completedTransfers.count() + 0.001);
        details.put("error.rate", String.format("%.2f%%", errorRate * 100));

        // E2E p99 latency (warn > 5s, critical > 30s)
        double p99 = e2eLatency.takeSnapshot().percentileValues()[0].value();
        details.put("e2e.p99.ms", p99);

        Health.Builder builder = (intakeSaturation > 0.9 || errorRate > 0.01 || queueDepth > 5000)
            ? Health.down() : Health.up();
        return builder.withDetails(details).build();
    }
}
```

---

## 10. Phase 7: Production Hardening (Week 7-8)

### 7.1 Exactly-Once Semantics (Idempotency)

**Current:** "At-least-once" delivery via RabbitMQ + retry. Duplicate processing possible if consumer ACKs but crashes before commit.

**Solution:** Idempotency key on FileTransferRecord:

```sql
-- Unique constraint on trackId (already exists via index)
-- In RoutingEngine: check before processing
SELECT id FROM file_transfer_records WHERE track_id = :trackId;
-- If exists: skip (idempotent). If not: proceed.
```

### 7.2 Graceful Degradation Matrix

| Component Down | Impact | Degradation Strategy |
|---------------|--------|---------------------|
| **PostgreSQL** | No record writes, no rule refresh | Circuit breaker opens. Files queue in RabbitMQ (durable). Rules stay in-memory (last snapshot). Resume when DB returns. |
| **Redis** | No partner cache (L1 still works), no VFS locks | L1 ConcurrentHashMap serves partner data. VFS falls back to pg_advisory_lock. Slower but functional. |
| **Storage-Manager** | No CAS writes, no checkpoints | PHYSICAL mode: files stay on local disk (already there). VIRTUAL mode: VFS entries still in DB. Checkpoint deferred. |
| **RabbitMQ** | No event publishing | RoutingEngine falls back to synchronous processing (already implemented). No consumer lag but lower throughput. |
| **Encryption-Service** | Can't encrypt/decrypt | Circuit breaker opens. Flow pauses at encryption step. Resumes when service returns. |
| **Screening-Service** | Can't scan for DLP/sanctions | Configurable: `screening.fail-open=true` → files pass through. `fail-open=false` → files quarantined. |

### 7.3 Recovery from Stuck Flows

**Current:** FlowExecutionRecoveryJob runs every 5 min, marks stuck flows as FAILED.

**Enhancement:**

```java
// FlowExecutionRecoveryJob.java — smarter recovery:
@Scheduled(fixedDelay = 60_000)  // Every 60s (was 5 min)
public void recover() {
    // 1. Find stuck PROCESSING flows (>2 min, was >5 min)
    List<FlowExecution> stuck = executionRepository
        .findByStatusAndStartedAtBefore(FlowStatus.PROCESSING,
            Instant.now().minus(Duration.ofMinutes(2)));

    for (FlowExecution exec : stuck) {
        // 2. Check if the owning pod is still alive (Redis heartbeat)
        boolean ownerAlive = serviceRegistry.isAlive(exec.getOwnerPodId());
        if (ownerAlive) continue;  // Pod is processing, just slow

        // 3. Dead pod: attempt restart from last checkpoint
        if (exec.getCurrentStorageKey() != null) {
            // Has checkpoint: restart from currentStep
            log.info("Recovering stuck flow {} from step {} (pod {} dead)",
                exec.getTrackId(), exec.getCurrentStep(), exec.getOwnerPodId());
            exec.setStatus(FlowStatus.PENDING);
            exec.setAttemptNumber(exec.getAttemptNumber() + 1);
            executionRepository.save(exec);
            flowEngine.restartFromCheckpoint(exec);
        } else {
            // No checkpoint: mark FAILED, operator must manually retry
            exec.setStatus(FlowStatus.FAILED);
            exec.setErrorMessage("Processing pod died, no checkpoint available");
            executionRepository.save(exec);
        }
    }
}
```

### 7.4 Poison Message Handling

```java
// FileUploadEventConsumer.java — poison message detection:
@RabbitListener(queues = "file.upload.events")
public void handle(FileUploadedEvent event) {
    int deliveryCount = getDeliveryCount(event);  // x-death header count
    if (deliveryCount > 3) {
        // Poison message: route to DLQ with explanation
        log.error("Poison message detected: trackId={}, deliveries={}, routing to DLQ",
            event.getTrackId(), deliveryCount);
        deadLetterService.persist(event, "Exceeded max delivery attempts");
        return;  // ACK the poison message (remove from queue)
    }
    // ... normal processing ...
}
```

---

## 11. Capacity Model

### 11.1 Target: 1 Million Files/Day

```
Files/day:     1,000,000
Files/sec:     11.57 (sustained), 50 (peak burst)
Avg file size: 500 KB
Daily volume:  ~500 GB ingested, ~500 GB delivered
Avg steps:     3 per flow (SCREEN → ENCRYPT → FORWARD)
```

### 11.2 Resource Requirements

| Resource | Sizing | Rationale |
|----------|--------|-----------|
| **SFTP pods** | 4 × 1 vCPU, 512MB | 3 files/sec each = 12/sec sustained |
| **FTP pods** | 2 × 1 vCPU, 512MB | Lower volume |
| **Gateway pods** | 2 × 1 vCPU, 512MB | HTTP uploads |
| **AS2 pods** | 2 × 1 vCPU, 512MB | Partner AS2 connections |
| **Encryption pods** | 4 × 2 vCPU, 1GB | CPU-heavy PGP/AES (biggest bottleneck) |
| **Storage-Manager pods** | 3 × 2 vCPU, 1GB | I/O heavy, striped writes |
| **Screening pods** | 2 × 2 vCPU, 1GB | DLP pattern matching (18K OFAC entries) |
| **Onboarding-API** | 2 × 2 vCPU, 1GB | Activity Monitor, SSE, admin APIs |
| **Config-Service** | 2 × 1 vCPU, 512MB | Flow management |
| **PostgreSQL** | 8 vCPU, 32GB, NVMe SSD | Primary + 1 read replica |
| **Redis** | 2 vCPU, 8GB | Partner cache, service registry, VFS locks |
| **RabbitMQ** | 4 vCPU, 16GB | Message broker, 100K msg/sec capacity |
| **Redpanda** | 4 vCPU, 16GB, NVMe | Fabric topics, 32 partitions |
| **Hot Storage** | 2 TB NVMe | 7-day retention at 500GB/day (with dedup) |
| **Warm Storage** | 10 TB (S3/NFS) | 30-day retention |

**Total:** ~32 vCPUs, ~80GB RAM, 12 TB storage  
**Estimated monthly cost (cloud):** $4,000-6,000

### 11.3 Throughput Budget Per Stage

```
                                    Budget   Concurrency  Throughput
STAGE                               (ms)     (workers)    (files/sec)
─────────────────────────────────────────────────────────────────────
1. Protocol receive (SFTP/FTP)       5        4 pods         50
2. Event publish (RabbitMQ)          1        -             1000+
3. Consumer pickup                   5        32 workers    200+
4. MatchContext build (cached)       0.1      -             10000+
5. Rule match (in-memory)            0.003    -             333000+
6. Record write (async batch)        0        -             (non-blocking)
7. Flow execution start              1        128 SEDA      500+
8. Step: SCREEN                      50       8 workers     160
9. Step: ENCRYPT                     100      16 workers    160
10. Step: FORWARD (delivery)         200      64 workers    320
11. Completion record update         2        -             500+
─────────────────────────────────────────────────────────────────────
END-TO-END (3-step flow)            ~360ms    -             ~50/sec
AT 4 SFTP PODS + 4 ENC PODS                                ~200/sec
SUSTAINED 24H                                               17.2M/day
```

**Conclusion:** With Phase 1-2 optimizations + 4 protocol pods + 4 encryption pods, we reach **17M files/day** — 17x the 1M target. This provides headroom for peak bursts.

---

## 12. Risk Register

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| **PostgreSQL connection exhaustion** | Medium | High (all services fail) | Phase 2: PgBouncer connection pooler in front of PostgreSQL. Limits to 200 server connections regardless of client pool sizes. |
| **RabbitMQ memory alarm** | Medium | High (publishers blocked) | Phase 6: Queue length alarm at 10K messages. Auto-scale consumers. Set `x-max-length: 100000` with overflow=reject-publish. |
| **Storage disk full** | Low | Critical | Phase 4: Lifecycle manager already tiers HOT→WARM→COLD. Add alert at 80% capacity. CAS dedup reduces actual storage ~40%. |
| **Cascading circuit breaker** | Low | High | Phase 7: Graceful degradation matrix. Each service can operate independently for short periods. |
| **Split-brain rule registry** | Very Low | Medium | Phase 3: 5s periodic refresh catches divergence. Version counter prevents stale reads. |
| **Large file OOM** | Medium | Medium | Phase 4: Replace Base64 with streaming multipart. Set JVM `-XX:MaxDirectMemorySize=512m`. |
| **Materialized view refresh blocks** | Medium | Low (stale data) | Phase 5: Incremental refresh or 60s interval + Redis cache for API. |
| **Partition management overhead** | Low | Low | Phase 5: pg_partman extension auto-creates monthly partitions. |

---

## 13. Migration Strategy

### 13.1 Rollout Order (Risk-Minimized)

```
Week 1-2: Phase 1 (Hot-Path I/O)
  ├── PartnerCache.java (NEW, additive)
  ├── Enrich FileUploadedEvent (backward-compatible — old consumers ignore new fields)
  ├── Pre-load flows in CompiledFlowRule (internal refactor)
  ├── Defer checksum (behavioral change — test checksum accuracy)
  └── TransferRecordBatchWriter (NEW, additive)
  RISK: Low. All changes are additive or internal refactors.
  ROLLBACK: Revert to direct DB calls (feature flag: pipeline.cache.enabled=true)

Week 2-3: Phase 2 (Concurrency)
  ├── RabbitMQ prefetch + concurrency tuning (config-only)
  ├── SEDA queue sizing (config-only)
  ├── HikariCP pool sizing (config-only)
  └── PostgreSQL tuning (config-only)
  RISK: Low. All config changes, zero code changes.
  ROLLBACK: Revert config values.

Week 3-4: Phase 3 (Rule Engine)
  ├── Protocol+Direction index (internal optimization)
  ├── Match explanation API (new endpoint, additive)
  ├── Per-rule Prometheus metrics (new metrics, additive)
  └── 5s refresh cycle (config change)
  RISK: Low. Additive features.

Week 4-5: Phase 4 (Storage)
  ├── Lazy checkpointing (behavioral — test recovery scenarios)
  ├── Streaming step pipeline (refactor — test all step types)
  └── Streaming multipart remote routing (protocol change — test with all forwarders)
  RISK: Medium. Behavioral changes affect recovery guarantees.
  ROLLBACK: Feature flag per optimization.

Week 5-6: Phase 5 (Database)
  ├── Table partitioning (V63 migration — test with existing data)
  ├── Materialized view strategy (config switch: full vs incremental)
  ├── Snapshot batch writer (additive)
  └── Read replica routing (infrastructure + config)
  RISK: Medium. Migration changes table structure.
  ROLLBACK: Keep old table as backup during migration.

Week 6-7: Phase 6 (Observability)
  ├── PipelineMetrics (additive)
  ├── Adaptive back-pressure (behavioral — test under load)
  └── Health score (additive)
  RISK: Low. Monitoring is additive.

Week 7-8: Phase 7 (Hardening)
  ├── Idempotency enforcement
  ├── Graceful degradation testing
  ├── Recovery enhancements
  └── Poison message handling
  RISK: Low. Defensive changes.
```

### 13.2 Feature Flags

Every optimization should be toggleable:

```yaml
pipeline:
  cache:
    partner:
      enabled: true          # Phase 1: Partner L1+L2 cache
      l2-ttl: 300s           # Redis TTL
  events:
    enriched: true           # Phase 1: Enriched FileUploadedEvent
  checksum:
    deferred: true           # Phase 1: Async checksum computation
  records:
    batch-write: true        # Phase 1: Async batch record writes
    batch-size: 50
    flush-interval-ms: 100
  checkpointing:
    strategy: selective      # Phase 4: ALWAYS | SELECTIVE | NONE
  storage:
    streaming-steps: true    # Phase 4: Stream-based step pipeline
  activity-view:
    refresh-mode: concurrent # Phase 5: concurrent | incremental
    refresh-interval: 30000
  metrics:
    per-rule: true           # Phase 3: Per-rule Prometheus metrics
```

---

## Appendix A: Validation Checklist

Before each phase goes live, verify the complete pipeline:

```
[ ] File upload via SFTP → event published → consumer picks up
[ ] Rule matching → correct flow selected → execution created
[ ] Each step type works: SCREEN, ENCRYPT, COMPRESS, FORWARD
[ ] VIRTUAL mode: zero-copy chain from upload to delivery
[ ] PHYSICAL mode: local file → steps → destination
[ ] Checksums: source computed → destination computed → integrity verified
[ ] Activity Monitor: new transfer appears within refresh interval
[ ] SSE stream: real-time event for new transfer
[ ] DLQ: failed message lands in dead_letter_messages table
[ ] Recovery: stuck flow detected and restarted within 2 minutes
[ ] Circuit breaker: service down → circuit opens → graceful degradation
[ ] Metrics: all stages reporting to Prometheus
[ ] Back-pressure: queue full → rejection counted → no OOM
```

---

## Appendix B: Key File Locations

| Component | Path |
|-----------|------|
| FlowRuleRegistry | shared/shared-core/.../matching/FlowRuleRegistry.java |
| FlowRuleCompiler | shared/shared-platform/.../matching/FlowRuleCompiler.java |
| FlowMatchEngine | shared/shared-core/.../matching/FlowMatchEngine.java |
| FlowRuleRegistryInitializer | shared/shared-platform/.../matching/FlowRuleRegistryInitializer.java |
| FlowRuleEventListener | shared/shared-platform/.../matching/FlowRuleEventListener.java |
| RoutingEngine | shared/shared-platform/.../routing/RoutingEngine.java |
| FlowProcessingEngine | shared/shared-platform/.../routing/FlowProcessingEngine.java |
| FlowStageManager (SEDA) | shared/shared-platform/.../flow/FlowStageManager.java |
| ProcessingStage | shared/shared-platform/.../flow/ProcessingStage.java |
| FileUploadEventConsumer | shared/shared-platform/.../routing/FileUploadEventConsumer.java |
| FileUploadQueueConfig | shared/shared-platform/.../routing/FileUploadQueueConfig.java |
| StepPipelineConfig | shared/shared-platform/.../routing/StepPipelineConfig.java |
| StorageController | storage-manager/.../controller/StorageController.java |
| StorageServiceClient | shared/shared-core/.../client/StorageServiceClient.java |
| ParallelIOEngine | storage-manager/.../engine/ParallelIOEngine.java |
| VirtualFileSystem | shared/shared-platform/.../vfs/VirtualFileSystem.java |
| ActivityViewRefresher | shared/shared-platform/.../health/ActivityViewRefresher.java |
| TransferActivityView | shared/shared-platform/.../entity/TransferActivityView.java |
| FlowExecution | shared/shared-platform/.../entity/FlowExecution.java |
| FlowStepSnapshot | shared/shared-platform/.../entity/FlowStepSnapshot.java |
| FileTransferRecord | shared/shared-platform/.../entity/FileTransferRecord.java |
| ResilienceConfig | shared/shared-core/.../config/ResilienceConfig.java |
| docker-compose.yml | docker-compose.yml |

---

*This document is the blueprint. Each phase is independently deployable, feature-flagged, and rollback-safe. The pipeline flows like water — measured at every joint, bounded at every stage, recoverable at every checkpoint.*

---

## Appendix C: Rule Engine Extraction — Decision Document

**Status:** DECISION PENDING — revisit when ready  
**Last updated:** 2026-04-13  

### C.1 What IS a Rule? (The 16 Match Dimensions)

A rule is a composable AND/OR/NOT decision tree that matches on **16 dimensions** of every incoming file. Not just filename — every attribute of the file, source, network, schedule, and custom metadata.

```
WHAT arrived?           │  WHO sent it?            │  HOW/WHEN?
────────────────────────┼──────────────────────────┼─────────────────────
filename   GLOB/REGEX   │  partnerId       EQ/IN   │  protocol    EQ/IN
extension  EQ/IN        │  partnerSlug     EQ/IN   │  direction   EQ
fileSize   GT/LT/BETWEEN│  accountUsername  EQ/REGEX│  sourceIp    CIDR
ediStandard EQ/IN       │  sourceAccountId EQ      │  timeOfDay   BETWEEN
ediType    EQ/IN        │  sourcePath      REGEX   │  dayOfWeek   EQ/IN
                        │                          │  metadata.*  KEY_EQ
```

Rules compose: `(extension=csv AND protocol=SFTP) OR (partnerSlug REGEX "bank-.*" AND sourceIp CIDR "10.0.0.0/8")`.

When a rule matches → it triggers a **FileFlow** pipeline (ordered steps: SCREEN → ENCRYPT → COMPRESS → FORWARD → AUDIT).

**Current demo:** 200 rules using only `filenamePattern` + `direction` (2 of 16 dimensions). All 16 are wired end-to-end: UI → API → JSONB → Compiler → Runtime.

### C.2 Current Architecture (Embedded — No Extraction)

```
SFTP/FTP/AS2/Gateway (5 protocol services, each with its own copy)
    │
    └─ RoutingEngine (shared-platform JAR)
         ├─ FlowRuleRegistry        in-memory, 200 rules, 160 KB, <1µs match
         ├─ FlowRuleCompiler        pre-compiles Predicates at load time
         ├─ FlowRuleRegistryInit    loads from DB every 30s + hot-reload via events
         ├─ PartnerCache            L1 ConcurrentHashMap + L2 Redis
         ├─ TransferRecordBatchWriter  async DB writes
         └─ FlowProcessingEngine    executes steps via SEDA or Kafka Fabric

    5 services × 160 KB rules = 800 KB total memory. Negligible.
    Rule matching CPU: 0.002% of one core at 1M files/day.
```

### C.3 Extraction Triggers (When MUST You Extract?)

| Trigger | Threshold | Why |
|---------|-----------|-----|
| **Rule count exceeds memory** | >50,000 rules | 40MB per pod × 5+ pods = 200MB+ wasted |
| **Refresh storm kills DB** | pods × rules > 500,000 | 50 pods × 10K rules = 500K; each refreshing every 30s |
| **Independent deployment** | Rule engine logic changes monthly | Can't restart all protocol services for a rule compiler fix |
| **Multi-tenant isolation** | 5+ enterprise tenants | Partner A must never evaluate Partner B's rules |
| **Cluster memory waste** | >2 GB duplicate rules | 50K rules × 50 pods = 2 GB of identical copies |

**Current state (200 rules, 5 pods):** None of these triggers are hit. Keep embedded.

### C.4 Extraction Architecture — Pattern C: Event-Driven via Kafka

**This pattern was chosen because it uses infrastructure that already exists.**

```
BEFORE (today):
  SFTP ──▶ RabbitMQ (file.uploaded) ──▶ FileUploadEventConsumer
              ──▶ RoutingEngine.findMatch() [in-process]
              ──▶ FlowProcessingEngine ──▶ Kafka (flow.step.*)
              ──▶ FlowFabricConsumer executes steps

AFTER (extracted):
  SFTP ──▶ Kafka (file.evaluate) ──▶ Rule Engine Service
              ──▶ findMatch() [in-process to rule-engine]
              ──▶ Kafka (flow.step.*) [SAME EXISTING TOPICS]
              ──▶ FlowFabricConsumer executes steps [UNCHANGED]
```

**What protocol services become (thin file receivers):**

```java
// SFTP Service — after extraction:
public void onFileUploaded(TransferAccount account, String path, String sourceIp) {
    FileEvaluateEvent event = FileEvaluateEvent.builder()
        .trackId(trackIdGenerator.generate())
        .accountId(account.getId())
        .username(account.getUsername())
        .protocol(account.getProtocol())
        .partnerId(account.getPartnerId())
        .storageMode(account.getStorageMode())
        .relativeFilePath(path)
        .sourceIp(sourceIp)
        .filename(extractFilename(path))
        .fileSizeBytes(Files.size(Paths.get(path)))
        .build();
    // One Kafka publish. SFTP service is done.
    fabricClient.publish("file.evaluate", event.getTrackId(), event);
}
```

**What the Rule Engine Service does:**

```java
// rule-engine-service: consumes file.evaluate, produces flow.step.*
@KafkaListener(topics = "file.evaluate", groupId = "rule-engine")
public void onFileEvaluate(FileEvaluateEvent event) {
    // 1. Build MatchContext from event fields (all 16 dimensions)
    MatchContext ctx = MatchContext.builder()
        .fromEvent(event)              // filename, extension, protocol, etc.
        .withPartnerSlug(partnerCache.get(event.getPartnerId()))
        .withTimeNow()
        .build();

    // 2. Match — in-process, <3µs (same FlowRuleRegistry, same Predicates)
    CompiledFlowRule matched = registry.findMatch(ctx);
    if (matched == null) {
        executionRepository.save(FlowExecution.unmatched(event));
        return;
    }

    // 3. Create FlowExecution record
    FileFlow flow = flowCache.get(matched.flowId());
    FlowExecution exec = FlowExecution.start(flow, event.getTrackId());
    executionRepository.save(exec);

    // 4. Publish first step to existing per-function Kafka topic
    //    THIS IS THE HANDOFF — flow workers pick up from here
    String firstStepType = flow.getSteps().get(0).getType();
    fabricClient.publish(
        "flow.step." + firstStepType,       // e.g., flow.step.ENCRYPT_PGP
        event.getTrackId(),                  // partition key (ordering guarantee)
        StepMessage.of(exec.getId(), 0, event.getStorageKey())
    );
}
```

**What stays UNCHANGED:**

```
FlowFabricConsumer          — still consumes flow.step.* topics, executes steps
FlowProcessingEngine        — still runs step logic (encrypt, compress, screen)
Storage-Manager             — still serves CAS reads/writes
Encryption-Service          — still does PGP/AES
Screening-Service           — still does DLP/sanctions
All per-function topics     — flow.step.ENCRYPT_PGP, flow.step.SCREEN, etc.
All consumer groups         — shared groups for load balancing
All checkpointing           — FabricCheckpoint for crash recovery
```

### C.5 Data Flow Diagram — Extracted

```
┌──────────────┐                        ┌─────────────────────────────┐
│ SFTP Service │─┐                      │    rule-engine-service      │
│ FTP  Service │─┤   file.evaluate      │                             │
│ AS2  Service │─┼──────────────────────▶│  FlowRuleRegistry (1 copy) │
│ GW   Service │─┤   (Kafka topic,      │  PartnerCache (1 copy)     │
│ FTP-Web Svc  │─┘    32 partitions)    │  FlowRuleCompiler          │
│              │                        │  FlowRuleEventListener     │
│ Thin:        │                        │                             │
│ - Receive file│                       │  Consumes: file.evaluate   │
│ - Publish     │                       │  Produces: flow.step.*     │
│   1 event     │                       │                             │
│ - Done        │                       │  NO DB for rules (REST→    │
│              │                        │   config-service on init)   │
└──────────────┘                        │  DB only for FlowExecution │
                                        └──────────┬──────────────────┘
                                                   │
                                            flow.step.ENCRYPT_PGP
                                            flow.step.SCREEN
                                            flow.step.COMPRESS_GZIP
                                            flow.step.FORWARD_SFTP
                                                   │
                                        ┌──────────▼──────────────────┐
                                        │  FlowFabricConsumer         │
                                        │  (runs in any service pod)  │
                                        │                             │
                                        │  Step 0: ENCRYPT_PGP       │
                                        │    → calls encryption-svc   │
                                        │    → publishes step 1       │
                                        │                             │
                                        │  Step 1: COMPRESS_GZIP     │
                                        │    → compresses in-process  │
                                        │    → publishes step 2       │
                                        │                             │
                                        │  Step N: FORWARD_SFTP      │
                                        │    → calls forwarder-svc    │
                                        │    → marks COMPLETED        │
                                        │    → publishes transfer.*   │
                                        └─────────────────────────────┘
```

### C.6 Communication: No REST Between Rule Engine and Flow Engine

**The rule engine does NOT call the flow engine via REST.** It publishes a Kafka message. The flow engine picks it up asynchronously.

```
Rule Engine                  Kafka                    Flow Workers
    │                          │                          │
    │  publish(                 │                          │
    │    "flow.step.ENCRYPT",  │                          │
    │    trackId,              │                          │
    │    {execId, step:0,      │                          │
    │     storageKey}          │                          │
    │  ) ─────────────────────▶│                          │
    │                          │                          │
    │  DONE. Rule engine       │  consumer poll           │
    │  moves to next file.     │  ────────────────────────▶
    │                          │                          │
    │                          │  Executes step 0         │
    │                          │  Publishes step 1        │
    │                          │◀─────────────────────────│
    │                          │                          │
    │                          │  consumer poll           │
    │                          │  ────────────────────────▶
    │                          │  Executes step 1...      │
```

**Why Kafka, not REST:**
- Decoupled: rule engine doesn't wait for step execution (fire-and-forget)
- Scalable: 32 partitions × N consumer pods = automatic load balancing
- Recoverable: if flow worker dies, message stays in Kafka (redelivered)
- Ordered: partition key = trackId → steps for same file stay ordered
- Already exists: `flow.step.*` topics, `FlowFabricConsumer`, checkpointing

### C.7 Pod Count at Scale

| Scale | Files/Day | Rule Engine Pods | Protocol Pods | Flow Worker Pods | Total |
|-------|-----------|-----------------|---------------|-----------------|-------|
| Current | 100K-1M | 0 (embedded) | 5 | 0 (embedded) | 5 |
| 10M | 10M | 0 (embedded) | 20 | 0 (embedded) | 20 |
| Extract point | 50M+ | 2 (HA pair) | 30 | 10 | 42 |
| Enterprise | 100M | 3 | 50 | 20 | 73 |
| Billion | 1B | 6 | 200 | 50 | 256 |
| 10 Billion | 10B | 16 | 1000 | 200 | 1,216 |

**Rule engine is never > 16 pods.** It's pure CPU, sub-microsecond matching. The bottleneck is always storage I/O, encryption CPU, or network bandwidth.

### C.8 What Already Exists (90% Built)

| Infrastructure | Status | Reused By Extraction |
|---------------|--------|---------------------|
| `file.uploaded` RabbitMQ topic | EXISTS | Replaced by `file.evaluate` Kafka topic |
| `flow.step.*` Kafka topics (per-function) | EXISTS | **Unchanged** — rule engine publishes to same topics |
| `flow.intake` Kafka topic | EXISTS | **Unchanged** |
| FlowFabricConsumer (step worker) | EXISTS | **Unchanged** — consumes same topics |
| FabricClient (Kafka producer/consumer) | EXISTS | Used by rule engine service |
| Per-function topic routing | EXISTS | **Unchanged** |
| 32 Kafka partitions | EXISTS | Shared between rule engine and flow workers |
| Shared consumer groups (FabricGroupIds) | EXISTS | Rule engine gets its own group |
| FlowExecution checkpointing | EXISTS | **Unchanged** |
| FlowRuleRegistry | EXISTS | Moves to rule engine service |
| FlowRuleCompiler | EXISTS | Moves to rule engine service |
| FlowRuleEventListener (dual RabbitMQ+Fabric) | EXISTS | Moves to rule engine service |
| PartnerCache (L1+L2) | EXISTS | Moves to rule engine service |
| SPIFFE/SPIRE identity | EXISTS | Auto-registered for new service |
| Circuit breakers (Resilience4j) | EXISTS | Protocol services add RuleEngineClient |

### C.9 Effort Estimate

| Task | LOC | Days |
|------|-----|------|
| Create rule-engine-service module (Application, config, Dockerfile) | ~200 | 1 |
| Move FlowRuleRegistry + Compiler + Initializer + EventListener | ~0 (same code, new home) | 1 |
| Create FileEvaluateEvent DTO + Kafka consumer | ~150 | 1 |
| Create RuleMatchController (REST, for admin/debug) | ~100 | 0.5 |
| Refactor protocol services: replace RoutingEngine with single Kafka publish | ~50 per service × 5 | 3 |
| Add FlowExecution creation to rule engine service | ~100 | 1 |
| Wire into docker-compose + spire-init registration | ~30 | 0.5 |
| Integration testing (all protocols × match × execute chain) | - | 3 |
| **Total** | **~800** | **~11 days (2-3 weeks with buffer)** |

### C.10 Decision Matrix

| Factor | Keep Embedded | Extract to Service |
|--------|:------------:|:-----------------:|
| Latency (<3µs match) | **WIN** | +1-5ms overhead |
| Memory efficiency (1 copy vs N) | Acceptable at 200 rules | **WIN** at 10K+ rules |
| Independent deployment | Restart all protocol services | **WIN** — restart only rule engine |
| Operational simplicity | **WIN** — fewer services | +1 service to monitor |
| Multi-tenant isolation | Impossible | **WIN** — per-tenant rule sets |
| Debug/audit (match explanation) | Possible but scattered | **WIN** — centralized |
| Scaling (rule evaluation CPU) | Scales with protocol pods | **WIN** — scales independently |
| Existing infrastructure reuse | N/A | **WIN** — 90% already built |

### C.11 Recommendation

**Today (200 rules, 5 pods, <10M files/day):** Keep embedded. Zero overhead.

**When ANY trigger from C.3 is hit:** Extract using Pattern C (event-driven via Kafka). The infrastructure is already there — it's a 2-3 week code reorganization, not an architectural rewrite.

**The extraction is a topology change, not a paradigm change.** Same code, same Kafka topics, same consumer groups — just running in a different pod.

---

## Appendix D: File Write Lifecycle — Who Writes, When, How, Recovery

### D.1 Byte Arrival — Protocol Layer Writes the File

**The file hits disk BEFORE our code runs.** The protocol server (Apache MINA SSHD / Apache FTPServer / HTTP servlet) handles the raw byte transfer. Our event listeners fire AFTER the file is complete and the client has received confirmation.

```
SFTP Client                    Apache MINA SSHD              Our Code
    │                              │                            │
    │  SSH_FXP_OPEN (WRITE)        │                            │
    │  ──────────────────────────▶ │                            │
    │                              │  opening() callback        │
    │                              │  ─────────────────────────▶│ record handle
    │                              │                            │
    │  SSH_FXP_WRITE (bytes)       │                            │
    │  ──────────────────────────▶ │                            │
    │                              │  writes to disk directly   │
    │  ... (more WRITE chunks) ... │  (Java NIO FileChannel)    │
    │                              │                            │
    │  SSH_FXP_CLOSE               │                            │
    │  ──────────────────────────▶ │                            │
    │                              │  flushes + closes file     │
    │  SSH_FXP_STATUS (OK)         │                            │
    │  ◀────────────────────────── │  client has confirmation   │
    │                              │                            │
    │                              │  closing() callback        │
    │                              │  ─────────────────────────▶│ onFileUploaded()
    │                              │                            │ (file is 100% on disk)
```

| Protocol | Server | Write Mechanism | Client Confirmation | Our Callback |
|----------|--------|----------------|--------------------|--------------| 
| **SFTP** | Apache MINA SSHD | `FileChannel.write()` | SSH_FXP_STATUS OK | `SftpRoutingEventListener.closing()` |
| **FTP** | Apache FTPServer | `FileOutputStream` | FTP 226 Transfer complete | `FtpletRoutingAdapter.onUploadEnd()` |
| **AS2** | Spring HTTP (Servlet) | `request.getInputStream().readAllBytes()` | HTTP 200 + MDN | `As2RoutingHandler.routeInboundMessage()` |
| **HTTP** | Gateway (MINA/FTP) | Same as SFTP/FTP | Same as SFTP/FTP | Same listeners |

**Key guarantee:** By the time `onFileUploaded()` is called, the file is **fully written, flushed, and the client has received success confirmation**. Our code never races with an incomplete write.

### D.2 The 7-Stage Write Pipeline

After the protocol layer confirms the file is on disk, our pipeline takes over. Here are the 7 stages, in order, with who does what:

```
STAGE 1: EVENT PUBLISH (RoutingEngine)
─────────────────────────────────────────
  Who:    RoutingEngine.onFileUploaded()
  What:   Generates trackId, publishes FileUploadedEvent to RabbitMQ
  Output: Event in queue (file.upload.events)
  If fails: Falls back to synchronous processing (same JVM)

STAGE 2: RULE MATCHING (FileUploadEventConsumer → RoutingEngine)
─────────────────────────────────────────
  Who:    FileUploadEventConsumer picks up event → calls onFileUploadedInternal()
  What:   Builds MatchContext (16 dimensions), evaluates FlowRuleRegistry
  Output: CompiledFlowRule (matched) or null (unmatched)
  Cost:   <3µs (pre-compiled Predicates, zero I/O)

STAGE 3: RECORD CREATION (RoutingEngine)
─────────────────────────────────────────
  Who:    RoutingEngine.onFileUploadedInternal()
  What:   Creates FileTransferRecord with status=PENDING
  Output: Record in DB (or batch writer queue)
  Fields: trackId, filename, sourceAccountId, flowId, fileSizeBytes

STAGE 4: STORAGE PUSH (RoutingEngine → Storage-Manager)
─────────────────────────────────────────
  Who:    RoutingEngine calls storageClient.storeStream()
  What:   Pushes file to Storage-Manager CAS
  Output: SHA-256 hash (= sourceChecksum), storageKey, sizeBytes
  Path:   file → temp file → SHA-256 digest → atomic rename to CAS path
          → fsync (if enabled) → response with hash
  Dedup:  If SHA-256 already in CAS → skip write, return existing key
  WAIL:   Write-Ahead Intent created BEFORE write, committed AFTER rename

STAGE 5: FLOW EXECUTION (FlowProcessingEngine)
─────────────────────────────────────────
  Who:    FlowProcessingEngine.executeFlow() or executeFlowRef()
  What:   Executes ordered pipeline steps (SCREEN → ENCRYPT → COMPRESS → FORWARD)
  Path:   Fabric (Kafka per-function topics) OR SEDA (bounded queue) OR synchronous

  Per step:
    a. Read input from Storage-Manager (by SHA-256 key)
    b. Execute step (encrypt, compress, scan, etc.)
    c. Write output to Storage-Manager (new SHA-256 key)
    d. Publish FlowStepEvent (async → FlowStepSnapshot in DB)
    e. Update FlowExecution.currentStep + currentStorageKey
    f. If Fabric: publish next step to flow.step.{NEXT_TYPE} topic
       If SEDA: continue loop in same thread

STAGE 6: DELIVERY (ForwarderService)
─────────────────────────────────────────
  Who:    SftpForwarderService / FtpForwarderService / As2ForwarderService
  What:   Reads final output from Storage-Manager, writes to partner endpoint
  Confirmation:
    SFTP: SSH_FXP_STATUS OK after handle close
    FTP:  226 Transfer complete
    AS2:  MDN (Message Disposition Notification) with MIC verification
    HTTP: 200 OK response

STAGE 7: COMPLETION (RoutingEngine or FlowProcessingEngine)
─────────────────────────────────────────
  Who:    RoutingEngine.onFileDownloaded() OR FlowProcessingEngine on last step
  What:   Updates FileTransferRecord:
          - status: PENDING → IN_OUTBOX → MOVED_TO_SENT
          - destinationChecksum: SHA-256 of delivered file
          - completedAt: Instant.now()
          - destinationAccountId: recipient account UUID
  Events: transfer.completed → RabbitMQ → SSE (real-time UI) + notifications
```

### D.3 Checksum Chain — Source to Destination Integrity

```
File arrives at SFTP
    │
    ▼
STAGE 4: Storage-Manager computes SHA-256 during CAS write
    │     Returns: sha256 = "a1b2c3d4e5..."
    │     RoutingEngine sets: transferRecord.sourceChecksum = "a1b2c3d4e5..."
    │
    ▼
STAGE 5: Each flow step produces a NEW SHA-256 key
    │     Step 0 input:  "a1b2c3d4e5..." (original)
    │     Step 0 output: "f6g7h8i9j0..." (encrypted version)
    │     Step 1 input:  "f6g7h8i9j0..."
    │     Step 1 output: "k1l2m3n4o5..." (compressed+encrypted)
    │     All tracked in FlowStepSnapshot (immutable audit trail)
    │
    ▼
STAGE 6: Delivery — final output written to partner
    │
    ▼
STAGE 7: Compute destination checksum
    │     PHYSICAL: AuditService.sha256(deliveredFilePath)
    │     VIRTUAL:  destinationChecksum = sourceChecksum (zero-copy, same content)
    │     transferRecord.destinationChecksum = "k1l2m3n4o5..."
    │
    ▼
Activity Monitor shows:
    sourceChecksum:      "a1b2c3d4e5..."
    destinationChecksum: "k1l2m3n4o5..."
    integrityStatus:     VERIFIED (both non-null + flow completed)
                    or   MISMATCH (checksums differ unexpectedly)
                    or   PENDING  (destination checksum not yet computed)
```

### D.4 VFS Write Path (VIRTUAL Accounts)

VIRTUAL accounts don't write to the local filesystem. Files exist only in CAS (Content-Addressable Storage) with metadata in PostgreSQL.

```
SFTP client writes "invoice.edi" to VIRTUAL account
    │
    ▼
VirtualSftpFileSystem.createOutputStream()
    │  Returns a VfsOutputStream that buffers bytes
    │
    ▼
VfsOutputStream.close()  ← triggered when SFTP client closes handle
    │
    ▼
VirtualFileSystem.writeFile(accountId, "/inbox/invoice.edi", bytes)
    │
    ├─ 1. LOCK: DistributedVfsLock.lockPath(accountId, path)
    │        Redis: SET NX EX 30s "platform:vfs:lock:{hash}"
    │        Fallback: pg_advisory_xact_lock(hash)
    │        Retry: 5 attempts × 200ms backoff
    │
    ├─ 2. INTENT: VfsIntent.create(WRITE, PENDING)
    │        Persisted to vfs_intents table (partitioned: active/resolved)
    │
    ├─ 3. BUCKET ROUTING:
    │        size ≤ 64KB  → INLINE (store gzipped bytes in DB row)
    │        64KB-64MB    → STANDARD (store in CAS via Storage-Manager)
    │        >64MB        → CHUNKED (4MB chunks, each in CAS)
    │
    ├─ 4. STORAGE:
    │        INLINE:   VirtualEntry.inlineContent = gzip(bytes)
    │        STANDARD: storageClient.storeStream() → SHA-256 key
    │        CHUNKED:  for each 4MB chunk:
    │                    storageClient.storeStream(chunk) → chunk SHA-256
    │                    VfsChunk.register(entryId, chunkIndex, sha256)
    │
    ├─ 5. DB WRITE: VirtualEntry saved (accountId, path, storageKey, bucket, size)
    │
    ├─ 6. COMMIT: VfsIntent.status = COMMITTED (same transaction as step 5)
    │
    └─ 7. UNLOCK: lock.close() → Redis DELETE
```

### D.5 Recovery Mechanisms

#### A. VFS Intent Recovery (Write-Ahead Intent Protocol)

```
Normal flow:                     Crash scenario:
  PENDING ──▶ COMMITTED           PENDING ──▶ [POD DIES]
                                              │
                                  VfsIntentRecoveryJob (every 2 min):
                                    Find PENDING intents > 5 min old
                                              │
                                  ┌───────────▼────────────┐
                                  │ Check CAS for storageKey│
                                  └───────────┬────────────┘
                                     ┌────────┴────────┐
                                     │                  │
                                CAS has file       CAS missing file
                                     │                  │
                                DB has entry?      ABORTED
                                  YES → COMMITTED    (file never stored,
                                  NO  → replay         nothing to recover)
                                        writeFile()
                                        → COMMITTED
```

#### B. Flow Execution Recovery

```
Normal flow:                     Crash scenario:
  PROCESSING ──▶ COMPLETED        PROCESSING ──▶ [POD DIES]
                                                  │
                                  FlowExecutionRecoveryJob (every 5 min):
                                    Find PROCESSING executions > 5 min old
                                                  │
                                  ┌───────────────▼──────────────┐
                                  │ Check if owner pod is alive   │
                                  │ (Redis heartbeat)             │
                                  └───────────────┬──────────────┘
                                     ┌────────────┴──────────┐
                                     │                        │
                                  Pod alive               Pod dead
                                  (just slow)             │
                                  → skip               Has checkpoint?
                                                     ┌───────┴───────┐
                                                     │               │
                                                 YES (key)       NO (null)
                                                     │               │
                                                 PENDING         FAILED
                                                 attempt++       "Pod died,
                                                 restart from    no checkpoint"
                                                 checkpoint
```

#### C. CAS Orphan Reaper (Storage Garbage Collection)

```
Runs daily:
  1. Scan all StorageObject records
  2. For each SHA-256 key:
     │
     ├─ Check VirtualEntry references: entryRepository.countByStorageKey(sha256)
     ├─ Check VfsChunk references:     chunkRepository.countByStorageKey(sha256)
     ├─ Check pending VfsIntents:      intentRepository.countPendingByStorageKey(sha256)
     │
     └─ If ALL counts = 0 AND created > 24h ago (grace period):
           → Soft-delete (if vfs.cas-gc-enabled=true)
           → Dry-run log (if false, default)
```

#### D. Storage-Manager Down — Graceful Degradation

```
RoutingEngine tries:
  storageClient.storeStream(file, size, name, account, trackId)
    │
    ▼
  Connection refused / timeout (Storage-Manager down)
    │
    ▼
  catch (Exception e):
    log.debug("Storage push skipped: {}", e.getMessage())
    │
    ▼
  Fallback: compute SHA-256 locally
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    transferRecord.setSourceChecksum(localChecksum)
    │
    ▼
  FileTransferRecord still created (PENDING status)
  Flow still executes (file is on local disk)
  Activity Monitor still shows the transfer
  Download button won't work until storage-manager recovers
    │
    ▼
  When storage-manager recovers:
    - Next file uploads work normally
    - Admin can re-push missing files via API
    - No data loss — file was on protocol service disk all along
```

#### E. RabbitMQ Down — Synchronous Fallback

```
RoutingEngine.onFileUploaded():
  rabbitTemplate.convertAndSend(exchange, "file.uploaded", event)
    │
    ▼
  AmqpException (RabbitMQ unreachable)
    │
    ▼
  catch: log.warn("RabbitMQ publish failed — falling back to synchronous")
    │
    ▼
  onFileUploadedInternal(account, path, sourceIp, filename, trackId)
    │
    ▼
  Entire pipeline runs synchronously in caller thread:
    match → record → storage push → flow execution → delivery
    │
    ▼
  SFTP event listener blocks until processing completes
  (slower but no data loss — file still processed)
```

### D.6 Summary: Write Ownership Map

| What Gets Written | Who Writes | When | Where | Confirmation |
|-------------------|-----------|------|-------|-------------|
| **Raw file bytes** | Protocol server (MINA/FTPServer) | During SFTP/FTP session | Local disk (`/data/sftp/{user}/inbox/`) | SSH_FXP_STATUS OK / FTP 226 |
| **VFS entry** (VIRTUAL) | VirtualFileSystem.writeFile() | After SFTP close | PostgreSQL `virtual_entries` + CAS | VfsIntent COMMITTED |
| **CAS object** | Storage-Manager (ParallelIOEngine) | On storage push | Local disk (`/data/storage/{sha256}`) | HTTP 200 + SHA-256 in response |
| **FileTransferRecord** | RoutingEngine (or BatchWriter) | After match, before flow | PostgreSQL `file_transfer_records` | DB commit (or batch flush) |
| **FlowExecution** | FlowProcessingEngine | At flow start | PostgreSQL `flow_executions` | DB commit |
| **FlowStepSnapshot** | FlowStepEventListener (async) | After each step | PostgreSQL `flow_step_snapshots` | Async — fire-and-forget |
| **FabricCheckpoint** | FlowFabricBridge | Before/after each Kafka step | PostgreSQL `fabric_checkpoints` | DB commit |
| **Delivered file** | ForwarderService | During delivery step | Remote partner SFTP/FTP/AS2 | Protocol confirmation (SSH OK / FTP 226 / MDN) |
| **Completion update** | RoutingEngine.onFileDownloaded() | When recipient downloads | PostgreSQL (status update) | DB commit |
| **Destination checksum** | AuditService.sha256() | On delivery completion | FileTransferRecord field | Same transaction as status update |

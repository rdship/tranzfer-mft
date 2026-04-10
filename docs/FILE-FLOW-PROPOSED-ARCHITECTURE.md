# File Flow: Proposed Architecture — Gap Analysis & World-Class Redesign

## What's Right Today

- **DB stores metadata only** — no BLOBs. File bytes live on disk (local CAS) or S3/MinIO.
- **Content-Addressed Storage (CAS)** — SHA-256 as filename, natural dedup.
- **Parallel striped writes** for large files (GPFS-style, 4 MB stripes, 8 I/O threads).
- **Dual backend** — local FS and S3 switchable via one env var.
- **Tiered lifecycle** — HOT / WARM / COLD with automatic movement and AI pre-staging.

---

## 10 Critical Gaps

### GAP 1 — "Streaming" is a Lie (CRITICAL)

Every read path loads the **entire file into JVM heap**:

```java
// ParallelIOEngine.read()
byte[] data = Files.readAllBytes(source);  // 10 GB file = 10 GB heap

// S3StorageBackend.read()
response.readAllBytes();  // same problem

// StorageController.stream() — JavaDoc claims "truly streaming...without loading into JVM heap"
StorageBackend.ReadResult backendResult = storageBackend.read(storageKey);
byte[] fileData = backendResult.data();  // FULL FILE IN HEAP FIRST
StreamingResponseBody body = out -> {
    new ByteArrayInputStream(fileData).transferTo(out);  // then "streams" from heap
};
```

**Impact**: 3 concurrent 2 GB downloads = 6 GB heap spike → GC pause → OOM risk.

---

### GAP 2 — S3 Upload Also Buffers Into Heap (CRITICAL)

```java
// S3StorageBackend.write()
byte[] bytes = readFully(data, sizeBytes);  // FULL file into byte[]
s3.putObject(request, RequestBody.fromBytes(bytes));
```

A 5 GB upload via S3 backend needs 5 GB of heap just to call `putObject`. AWS SDK supports
`RequestBody.fromInputStream()` and true multipart uploads — neither is used.

---

### GAP 3 — No Crash Recovery for Striped Writes (CRITICAL)

```java
// ParallelIOEngine.writeStriped()
dst.truncate(totalSize);  // pre-allocate 10 GB file on disk
// ... parallel stripe writes begin ...
// If JVM crashes mid-stripe:
//   → 10 GB orphaned file remains (all zeros in unwritten regions)
//   → No manifest of which stripes completed
//   → No cleanup job, no resume path
```

---

### GAP 4 — TOCTOU Race in Dedup (HIGH)

```java
// LocalStorageBackend.write()
if (Files.exists(casPath)) {       // Thread A checks: false
    Files.deleteIfExists(dest);     // Thread B checks: false
}                                   // Both proceed to rename
Files.move(dest, casPath);          // Last writer wins — content identical, but work doubled
```

On local FS this is harmless (POSIX rename is atomic). On S3, both pods do a full `putObject`
— doubled bandwidth and S3 cost for every concurrent duplicate upload.

---

### GAP 5 — No Resumable / Chunked Uploads (HIGH)

- No `tus` protocol, no HTTP `Content-Range` upload support.
- A 10 GB file that fails at 9.5 GB = start from zero.
- No upload session ID, no checkpoint, no partial commit.

---

### GAP 6 — Tier Move Is Not Atomic (HIGH)

```java
// StorageLifecycleManager.tierDown()
ioEngine.tierCopy(source, dest);     // 1. Copy file
Files.delete(source);                // 2. Delete original — crash here?
obj.setPhysicalPath(dest.toString());
objectRepo.save(obj);               // 3. Update DB — crash here?
// If crash between 2 and 3: file is at dest, DB still says source → file unreachable
```

---

### GAP 7 — `ReadResult` Carries `byte[]` in the Interface (DESIGN ROOT CAUSE)

```java
record ReadResult(
    byte[] data,        // THIS forces every implementation to load full file into heap
    long sizeBytes, ...
) {}
```

The **interface contract itself** is the root of all heap-load problems. Streaming cannot be
fixed in any implementation without changing this record.

---

### GAP 8 — No Back-Pressure / Admission Control

- No limit on concurrent writes or reads.
- 100 simultaneous 1 GB uploads = potential disk I/O saturation on a single node.
- No semaphore, no queue, no rate limiter per lane.

---

### GAP 9 — Redis Location Registry is Fragile

- Pod crashes → stale routing entries remain for **7 days** (just the TTL).
- No heartbeat / lease renewal to detect dead pods sooner.
- No health check before proxy routing — routes to dead pod, then falls back to local FS.
- `proxyRetrieve()` loads full `byte[]` again over HTTP — double heap load for every
  cross-pod read.

---

### GAP 10 — No Zero-Copy Path

Data is copied at least 3 times on every local-disk read:

```
disk → kernel buffer → JVM heap (readAllBytes) → HTTP response buffer → network
```

Linux `sendfile()` / Java `FileChannel.transferTo()` achieves:

```
disk → kernel buffer → network socket (zero user-space copy)
```

This is how Nginx, Kafka, and HDFS achieve multi-GB/s throughput on commodity hardware.

---

## Proposed Architecture

### 1. New Streaming Interface Contract

Replace the `byte[]`-based contract with a stream-first interface:

```java
public interface StorageBackend {

    WriteResult write(InputStream data, long sizeBytes, String filename) throws Exception;

    // NEW: push-model streaming — never loads file into heap
    void read(String storageKey, OutputStream target) throws Exception;

    // NEW: zero-copy for local backend (FileChannel.transferTo)
    ReadableByteChannel readChannel(String storageKey) throws Exception;

    // NEW: ranged reads for resumable downloads and parallel reassembly
    void readRange(String storageKey, long offset, long length, OutputStream target) throws Exception;

    boolean exists(String storageKey);
    void delete(String storageKey);
    String type();

    record WriteResult(
            String storageKey, long sizeBytes, String sha256,
            long durationMs, double throughputMbps, boolean deduplicated) {}

    // ReadResult byte[] REMOVED — callers receive data via OutputStream or Channel
}
```

---

### 2. Chunked Pipeline — Size-Aware Write Strategy

```
Upload stream arrives
        │
        ├─ Size < 4 MB ──────────────────────────────────────────────► writeDirect()
        │                                                               Single thread
        │                                                               64 KB read buffer
        │                                                               SHA-256 inline
        │                                                               fsync → CAS rename
        │
        ├─ 4 MB ≤ Size ≤ 1 GB ──────────────────────────────────────► writeStriped()
        │                                                               Buffer to temp file
        │                                                               N parallel stripe writes
        │                                                               Stripe manifest logged
        │                                                               Atomic rename to CAS
        │
        └─ Size > 1 GB  ─────────────────────────────────────────────► writeMultipart()
                                                                        S3: multipart upload API
                                                                        Local: chunked + manifest
                                                                        Resumable (tus-compatible)
                                                                        No full-heap allocation
```

```
Download request arrives
        │
        ├─ Local backend ────────────────────────────────────────────► FileChannel.transferTo()
        │                                                               Zero-copy kernel sendfile
        │                                                               No JVM heap involvement
        │
        └─ S3 backend ───────────────────────────────────────────────► GetObject → chunked pipe
                                                                        256 KB chunk → OutputStream
                                                                        Never buffers full file
```

---

### 3. Write-Ahead Intent Log (WAIL)

Before any write begins, record intent. On crash: scan and clean orphans.

```
WRITE INTENT (written first):
  {
    "uploadId":    "uuid",
    "sha256":      "expected-hash (if known) or null",
    "tempPath":    "/data/storage/hot/tmp-12345",
    "destPath":    "/data/storage/hot/{sha256}",
    "stripeCount": 25,
    "status":      "IN_PROGRESS",
    "startedAt":   "2026-04-09T10:00:00Z"
  }

WRITE COMPLETES:
  status → "DONE"

CRASH RECOVERY (on startup):
  scan intent.log for status=IN_PROGRESS
  → delete tempPath (orphaned partial writes)
  → alert metrics
```

Recovery job runs on every service startup and every 10 minutes:

```java
@Scheduled(fixedDelay = 600_000)
void cleanOrphanedWrites() {
    intentLog.findByStatus("IN_PROGRESS")
        .filter(i -> i.startedAt().isBefore(Instant.now().minus(30, MINUTES)))
        .forEach(i -> {
            Files.deleteIfExists(Path.of(i.tempPath()));
            intentLog.delete(i.uploadId());
            metrics.increment("storage.orphan.cleaned");
        });
}
```

---

### 4. Resumable Uploads (tus-compatible)

```
Step 1 — Initialize upload session
  POST /api/v1/storage/upload-init
  Body: { "filename": "bigfile.zip", "totalSize": 10737418240 }
  Response: { "uploadId": "uuid", "chunkSize": 4194304, "expiresAt": "..." }

Step 2 — Upload chunks (idempotent, any order)
  PATCH /api/v1/storage/upload/{uploadId}
  Headers:
    Content-Range: bytes 0-4194303/10737418240
    Content-Type: application/octet-stream
  Response: { "received": 4194304, "remaining": 10733223936 }

  (Chunks can be sent in parallel from multiple connections)

Step 3 — Complete
  POST /api/v1/storage/upload/{uploadId}/complete
  → Assembles chunks in order
  → Computes and verifies SHA-256 of complete file
  → Renames to CAS path
  → Returns { "sha256": "...", "sizeBytes": ..., "tier": "HOT" }

On network failure: client resumes from last acked byte.
On server restart: intent log preserves which chunks were received.
```

---

### 5. I/O Lane System (No Overload)

Separate concurrency budget per traffic class. Protects real-time uploads from being
starved by bulk background jobs:

```java
public enum IOLane {
    REALTIME,    // Partner SFTP uploads, flow-processing reads
    BULK,        // Tier migrations, scheduled backups
    BACKGROUND   // Predictive pre-stage, dedup scans, orphan cleanup
}

// Semaphore per lane — configurable via application.yml
storage.lanes.realtime.permits:   8   # reserved, never yielded
storage.lanes.bulk.permits:       4   # throttled to protect REALTIME
storage.lanes.background.permits: 2   # best-effort, yields to BULK

// Usage
Semaphore lane = lanes.get(IOLane.BULK);
lane.acquire();
try {
    ioEngine.tierCopy(source, dest);
} finally {
    lane.release();
}
```

---

### 6. Atomic Tier Moves (No Orphan Risk)

```java
// NEW safe tier move — 4 steps, each recoverable

// Step 1: Mark in-flight in DB (single update, survives crash)
obj.setTier("MOVING");
obj.setMovingTo("WARM");
objectRepo.save(obj);

// Step 2: Copy file
String checksum = ioEngine.tierCopy(source, dest);

// Step 3: Integrity gate — verify before delete
if (!checksum.equals(obj.getSha256())) {
    obj.setTier("HOT");  // rollback
    obj.setMovingTo(null);
    objectRepo.save(obj);
    throw new IntegrityException("Tier move aborted: checksum mismatch");
}

// Step 4: Atomic DB update + source delete
obj.setTier("WARM");
obj.setPhysicalPath(dest.toString());
obj.setMovingTo(null);
obj.setTierChangedAt(Instant.now());
objectRepo.save(obj);

Files.delete(source);  // Safe: DB already points to dest

// Recovery: on startup, find tier=MOVING → resume or rollback
```

---

### 7. Replace Redis Registry with Deterministic Routing

The Redis location registry is a band-aid for a deployment problem:

| Deployment Model | Registry Needed? | Why |
|-----------------|-----------------|-----|
| Single instance | No | Only one pod |
| NFS shared mount | No | All pods see same files |
| EFS / ReadWriteMany PVC | No | All pods see same files |
| S3 / MinIO backend | No | All pods share same object store |
| Local disk, no shared FS | Yes | Files are pod-local |

**Recommendation**: Don't run local-disk backend without shared FS in production.
For the one case where it's needed, replace Redis registry with **consistent hashing**:

```java
// Deterministic routing — no runtime registry, no Redis dependency
String ownerPodUrl = consistentHash.getPod(sha256);
// Same sha256 always routes to same pod.
// When pods scale up/down: rehash only the affected range.
```

---

### 8. Zero-Copy Read Implementation

```java
// LOCAL BACKEND — zero-copy via FileChannel.transferTo (sendfile syscall)
@Override
public void read(String storageKey, OutputStream target) throws Exception {
    Path path = resolvePath(storageKey);
    try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
        WritableByteChannel out = Channels.newChannel(target);
        long position = 0;
        long remaining = fc.size();
        while (remaining > 0) {
            long transferred = fc.transferTo(position, remaining, out);
            position += transferred;
            remaining -= transferred;
        }
    }
}

// S3 BACKEND — chunked pipe (256 KB chunks, never full-heap)
@Override
public void read(String storageKey, OutputStream target) throws Exception {
    GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket).key(normalizeKey(storageKey)).build();
    try (ResponseInputStream<GetObjectResponse> s3Stream = s3.getObject(request)) {
        byte[] buf = new byte[262_144]; // 256 KB
        int n;
        while ((n = s3Stream.read(buf)) != -1) {
            target.write(buf, 0, n);
        }
    }
}

// CONTROLLER — truly streaming, async, no heap load
@GetMapping("/stream/{sha256}")
public void stream(@PathVariable String sha256, HttpServletResponse response) throws Exception {
    StorageObject obj = objectRepo.findBySha256AndDeletedFalse(sha256)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    response.setContentType(obj.getContentType() != null ? obj.getContentType() : "application/octet-stream");
    response.setContentLengthLong(obj.getSizeBytes());
    response.setHeader("X-SHA256", sha256);
    response.setHeader("X-Storage-Tier", obj.getTier());

    storageBackend.read(sha256, response.getOutputStream());
    // FileChannel.transferTo → OS sendfile → kernel buffer → socket
    // Zero bytes hit the JVM heap
}
```

---

## Race Condition Guarantee Matrix

| Scenario | Current | Proposed | Guarantee |
|----------|---------|----------|-----------|
| Two pods upload identical file | Both write, last wins (safe, wasteful) | DB unique constraint on sha256 blocks second writer | One write only |
| JVM crash mid-stripe | Orphaned pre-allocated file | Intent log → cleanup on restart | No orphans |
| JVM crash mid-tier-move | File at dest, DB points to src | `tier=MOVING` state + recovery on startup | Recoverable |
| Pod dies, stale Redis routing | 7-day TTL, then healed | NFS/S3 shared backend (no per-pod registry) | No staleness |
| Concurrent tier-move + read | Read succeeds (src still present until verified copy) | Same + integrity check before delete | Safe |
| Large file upload network drop | Full restart | Resumable upload — resume from last chunk | No full restart |
| HOT tier disk full | Aggressive eviction (no admission control) | Lane semaphore + admission gate | No overload |

---

## Summary: What Changes, What Stays

| Component | Keep | Replace / Add |
|-----------|------|---------------|
| CAS (SHA-256 filenames) | ✅ | — |
| StorageBackend interface | ✅ structure | Remove `byte[]` from ReadResult, add streaming methods |
| LocalStorageBackend | ✅ | Add `FileChannel.transferTo` zero-copy read |
| S3StorageBackend | ✅ | Replace `readFully` with chunked pipe; use multipart upload |
| ParallelIOEngine writes | ✅ direct + striped | Add WAIL intent log, temp→rename pattern |
| StorageLifecycleManager | ✅ tiering logic | Make moves atomic (MOVING state) |
| Redis location registry | ✅ (optional) | Deprecate in favour of shared FS or consistent hashing |
| StorageObject entity | ✅ | Add `uploadId`, `uploadStatus`, `movingTo` fields |
| I/O lanes | — | NEW: semaphore-based priority lanes |
| Resumable uploads | — | NEW: tus-compatible chunked upload protocol |
| Write-Ahead Intent Log | — | NEW: startup cleanup for orphaned writes |
| Streaming read endpoint | ❌ (lies) | REWRITE: true zero-copy |

---

---

# Part 2: The Flow Execution Engine — Beyond DAGs

## Why Not a DAG?

DAG (Directed Acyclic Graph) is what Airflow, Prefect, and Dagster use. It's already outdated
for real-time, distributed file processing. Here's what changed:

| Limitation | Impact on MFT |
|-----------|--------------|
| **Acyclic** — no loops by definition | Can't express retry sub-graphs natively |
| **Static topology** — graph fixed at design time | Can't add delivery endpoints at runtime |
| **Batch-oriented** — designed for scheduled ETL | Files arrive continuously, not in batches |
| **No long-running waits** — steps are expected to complete | APPROVE gates need hours/days of waiting |
| **No streaming** — passes references between steps | Each step loads full file, GC pressure |
| **External coordinator** — needs a DAG server (Airflow, etc.) | One more infra component to manage |

**What the industry actually uses in 2025-2026:**

| Company | Engine | Why They Moved Away from DAGs |
|---------|--------|------------------------------|
| **Airbnb** | Temporal (Journey Platform) | Interactive user workflows, not batch |
| **Netflix** | Maestro (stateful actors + virtual threads) | 100x faster than their DAG engine; 1M+ tasks/day |
| **Stripe** | Temporal | Payment workflows need durability, not scheduling |
| **Uber** | Cadence → Temporal | Ride orchestration needs sub-second latency |
| **Coinbase** | Temporal | Transaction workflows with regulatory holds |

---

## The 2026 Landscape: What's Available

### Temporal.io (Durable Execution)

**What it is:** Write workflows as ordinary Java code. The Temporal server records every step as
an event. On crash, the workflow replays deterministically from the event log.

**Who uses it:** Stripe, Netflix (CI/CD), Airbnb, Snap, DoorDash, Coinbase. 2,500+ customers.

**Strengths:**
- Automatic retry with configurable backoff
- Workflow state survives JVM crashes (event replay)
- Workers are stateless — any worker picks up any workflow
- Long-running waits (sleep for days without consuming resources)

**Fatal flaw for MFT:**
- **2 MB payload limit** — file bytes CANNOT pass through Temporal
- 4 MB gRPC message limit — workflow terminated as non-recoverable if exceeded
- Designed for orchestration (control plane), NOT data transfer (data plane)
- Adds an external cluster dependency (Temporal server)

**Verdict:** Excellent orchestrator, but CANNOT be the file pipeline engine. Would require
passing only file references (SHA-256 keys), never bytes.

### Restate (Virtual Object Model)

**What it is:** Built in Rust by the original Apache Flink creators. Each entity (transfer, flow)
is a "virtual object" with K/V state and single-writer semantics. Durable execution via a
built-in distributed log.

**Strengths:**
- Lower latency than Temporal (<100ms p99 for 10-step workflows)
- State stored in server, not application heap
- Active-active deployments with instant replication

**For MFT:**
- Elegant model: each file transfer = a virtual object with its own state machine
- But: newer, smaller community, higher risk for production adoption today
- Same payload limitation — designed for RPC, not streaming bytes

**Verdict:** Watch list. Compelling but not mature enough for institutional-grade MFT in 2026.

### Netflix Maestro (Stateful Actors + Virtual Threads)

**What it is:** Netflix's 2025 rewrite of their workflow engine. Each workflow gets an in-memory
actor in the JVM. The actor knows its next steps and reacts to events instantly. Database is the
source of truth; in-memory state rebuilds from DB on bootstrap.

**Result:** 100x performance improvement (seconds → milliseconds). Runs 1M+ tasks/day.

**Key architecture decisions:**
- No external coordinator — the engine IS the application
- Virtual threads (Java 21) — millions of concurrent workflows, no thread pool sizing
- Internal queues replaced distributed queues — less infra
- DB as truth + in-memory actor as fast path

**Verdict:** THIS is the model. No external dependency. No DAG server. No payload limits.
Built for exactly this class of problem.

### SEDA (Staged Event-Driven Architecture)

**What it is:** Decompose application into stages connected by bounded queues. Each stage has its
own thread pool and queue. Controllers size pools dynamically. Admission control prevents overload.

**Who uses it:** Apache Cassandra (all internal operations decomposed into SEDA stages).

**Why it matters for MFT:**
- File transfer IS a set of stages: receive → validate → transform → route → deliver
- Bounded queues = natural backpressure (no OOM)
- Stage-level monitoring = instant visibility into bottlenecks
- Admission control = graceful degradation under load

**Verdict:** Use as the INTERNAL architecture pattern, not as an external framework.

### Project Reactor (Reactive Streams)

**What it is:** `Flux<DataBuffer>` — chunked streams with backpressure. Subscriber signals demand
via `request(n)`, publisher respects limits.

**For file streaming:**
- Files read as `Flux<DataBuffer>` — 8-256 KB chunks, never full file in heap
- `limitRate(16)` = at most 16 buffers in flight (~4 MB heap for any file size)
- Backpressure prevents OOM even if consumer is slow

**Verdict:** THE data plane for byte streaming. Combine with the actor model for orchestration.

### Chicory (WebAssembly on JVM)

**What it is:** Pure Java WebAssembly runtime. AOT compiles Wasm to JVM bytecode. Sandboxed —
plugin can't access JVM heap, files, or network unless explicitly granted.

**Performance:** 30-50% of native speed (AOT mode). Acceptable for transform plugins.

**For MFT plugins:**
- Partners compile their transform logic to Wasm (Rust, Go, C, AssemblyScript, etc.)
- Platform loads Wasm module into sandboxed runtime
- Host streams file chunks through the sandbox: copy-in → transform → copy-out
- Buggy plugin can't crash the platform or access other tenants' data

**Verdict:** Perfect for the partner plugin sandbox. Pure Java, no JNI, no native deps.

### io_uring (Async I/O)

**Status:** JUring (Java + Panama FFM) shows 33-78% I/O improvement. But NOT production-ready
for Java as of 2025. Virtual threads get pinned during file I/O — io_uring would fix this.

**Verdict:** Use `FileChannel.transferTo()` (sendfile syscall) today. Monitor io_uring Java
libraries for 2027+.

---

## The Proposed Engine: Durable Reactive Pipeline (DRP)

Not a DAG. Not Temporal. Not an external dependency.

Inspired by Netflix Maestro (stateful actors) + SEDA (staged processing) + Project Reactor
(streaming backpressure) + Chicory (plugin sandbox). Built into the platform as a first-class engine.

### Architecture Overview

```
                    ┌─────────────────────────────────────────────────┐
                    │              FLOW EXECUTION ENGINE               │
                    │                                                  │
  File arrives ───► │  ┌──────────┐    ┌─────────┐    ┌────────────┐  │
                    │  │  INTAKE   │───►│ PIPELINE │───►│  DELIVERY  │  │
                    │  │  STAGE    │    │  STAGE   │    │  STAGE     │  │
                    │  └──────────┘    └─────────┘    └────────────┘  │
                    │       │              │                │          │
                    │       ▼              ▼                ▼          │
                    │  ┌─────────────────────────────────────────┐    │
                    │  │         EVENT JOURNAL (DB)               │    │
                    │  │   Append-only, event-sourced state       │    │
                    │  └─────────────────────────────────────────┘    │
                    │                                                  │
                    │  ┌─────────────────────────────────────────┐    │
                    │  │         FUNCTION REGISTRY                │    │
                    │  │   Built-in │ WASM │ gRPC │ Container     │    │
                    │  └─────────────────────────────────────────┘    │
                    └─────────────────────────────────────────────────┘
```

### Layer 1: Stateful Flow Actors (inspired by Netflix Maestro)

Each flow execution is a **lightweight actor** running on a virtual thread:

```java
// One virtual thread per flow execution — millions can coexist
Thread.ofVirtual().name("flow-" + trackId).start(() -> {
    FlowActor actor = new FlowActor(flowDefinition, trackId, inputRef);
    actor.run();  // event loop: react to step completions, failures, approvals
});
```

**Why actors, not for-loops:**

```
FOR LOOP (current):
  Thread blocked for entire flow duration
  One failure = entire thread stuck in retry/backoff
  APPROVE gate = thread parked for hours/days
  100 flows × 5-minute avg = 100 threads permanently occupied

ACTOR (proposed):
  Actor reacts to events (step completed, step failed, approval received)
  Between events: actor is suspended, ZERO resource consumption
  APPROVE gate: actor suspends, virtual thread yielded — costs nothing
  100,000 flows × 5-minute avg = 100,000 virtual threads (~50 MB total)
```

**Actor state machine:**

```
         ┌──────────────────────────────────────────────────────┐
         │                                                      │
         ▼                                                      │
    ┌─────────┐     ┌──────────┐     ┌───────────┐     ┌──────┴────┐
    │ PENDING │────►│EXECUTING │────►│ WAITING   │────►│ EXECUTING │
    └─────────┘     │  step N  │     │ (approve/ │     │  step N+1 │
                    └────┬─────┘     │  ext call)│     └───────────┘
                         │           └───────────┘           │
                    step fails                          all steps done
                         │                                   │
                         ▼                                   ▼
                    ┌─────────┐                        ┌───────────┐
                    │ RETRY   │                        │ COMPLETED │
                    │ backoff │                        └───────────┘
                    └────┬────┘
                         │
                    max retries
                         │
                         ▼
                    ┌─────────┐
                    │ FAILED  │
                    └─────────┘
```

**Durability:** Every state transition is an event appended to the journal (PostgreSQL).
On JVM restart, actors rebuild from the journal — exactly like Netflix Maestro rebuilds
from DB on bootstrap.

### Layer 2: SEDA Stages with Bounded Queues

Three processing stages, each with its own admission-controlled queue:

```
INTAKE STAGE                    PIPELINE STAGE                 DELIVERY STAGE
┌────────────────┐             ┌────────────────┐             ┌────────────────┐
│ Queue: 1000    │             │ Queue: 500     │             │ Queue: 2000    │
│ Threads: 16    │             │ Threads: 32    │             │ Threads: 16    │
│                │             │                │             │                │
│ • Match rules  │────────────►│ • Run pipeline │────────────►│ • Route        │
│ • Create exec  │             │ • Stream bytes │             │ • Deliver SFTP │
│ • Start actor  │             │ • Apply funcs  │             │ • Deliver AS2  │
│                │             │                │             │ • Mailbox copy │
│ Admission:     │             │ Admission:     │             │ Admission:     │
│ reject if full │             │ backpressure   │             │ retry queue    │
└────────────────┘             └────────────────┘             └────────────────┘
```

**What this gives you:**
- **Backpressure propagation**: If delivery is slow (partner SFTP down), pipeline stage
  slows down (bounded queue fills), intake stage rejects new work (admission control)
- **Stage-level metrics**: Queue depth per stage = instant visibility into bottlenecks
- **Isolation**: A slow encryption step doesn't block file matching or delivery
- **Dynamic sizing**: Stage controller adjusts thread count based on queue depth

### Layer 3: Reactive Byte Pipeline (the actual file processing)

Bytes never touch the heap. Each function wraps the stream:

```java
// Build a composed reactive pipeline — zero heap allocation for file bytes
Flux<DataBuffer> pipeline = storageBackend.readStream(inputKey);

for (FlowFunction fn : resolvedFunctions) {
    switch (fn.ioMode()) {
        case STREAMING:
            // Function transforms chunks on the fly (gzip, rename, etc.)
            // Only 64KB in flight per function — bounded
            pipeline = fn.transformStream(pipeline, stepConfig);
            break;

        case MATERIALIZING:
            // Function needs full file (encryption REST call, screening)
            // Spill to temp file — NOT heap. Still bounded.
            pipeline = materializeAndTransform(pipeline, fn, stepConfig);
            break;

        case METADATA_ONLY:
            // Function doesn't touch bytes (rename, route, approve)
            fn.applyMetadata(executionContext, stepConfig);
            break;
    }
}

// Terminal: pipe composed stream directly to CAS — zero intermediate copies
storageBackend.writeStream(pipeline, outputKey);
```

**Memory comparison (10 concurrent flows, 500 MB files, 4 steps each):**

| Model | Heap Usage | Why |
|-------|-----------|-----|
| Current for-loop | **23 GB** | Each step: readAllBytes + Base64 |
| DAG (Airflow-style) | **23 GB** | Same problem — steps load full file |
| Temporal | **N/A** | Can't pass files through Temporal at all |
| **DRP (proposed)** | **~40 MB** | 10 flows × 4 functions × 64KB buffer + actor state |

### Layer 4: Function Registry & Plugin System

Functions are first-class entities — not switch cases:

```
┌───────────────────────────────────────────────────────────┐
│                    FUNCTION REGISTRY                       │
├───────────────┬───────────────┬──────────────┬────────────┤
│  BUILT-IN     │  WASM         │  gRPC        │ CONTAINER  │
│  (same JVM)   │  (sandboxed)  │  (sidecar)   │ (isolated) │
├───────────────┼───────────────┼──────────────┼────────────┤
│ compress-gzip │ partner-      │ partner-     │ legacy-    │
│ decompress    │   custom-     │   ml-model   │   mainframe│
│ encrypt-pgp   │   validator   │   enrichment │   bridge   │
│ encrypt-aes   │ partner-      │              │            │
│ decrypt-pgp   │   format-     │              │            │
│ decrypt-aes   │   converter   │              │            │
│ screen        │               │              │            │
│ rename        │               │              │            │
│ mailbox       │               │              │            │
│ deliver       │               │              │            │
│ convert-edi   │               │              │            │
│ route         │               │              │            │
│ approve       │               │              │            │
│ execute-script│               │              │            │
├───────────────┼───────────────┼──────────────┼────────────┤
│ Zero overhead │ ~50μs/chunk   │ ~1ms/chunk   │ ~100ms     │
│ Full trust    │ Sandboxed     │ Process iso  │ Full iso   │
│ Java only     │ Any language  │ Any language │ Any lang   │
└───────────────┴───────────────┴──────────────┴────────────┘
```

**FlowFunction interface (the universal plugin contract):**

```java
public interface FlowFunction {

    /** Transform a reactive stream — chunk by chunk, backpressure-aware */
    Flux<DataBuffer> transformStream(Flux<DataBuffer> input, Map<String, String> config);

    /** I/O mode declaration — engine uses this to optimize the pipeline */
    IOMode ioMode();  // STREAMING | MATERIALIZING | METADATA_ONLY

    /** JSON Schema for config validation (UI renders as form) */
    String configSchema();

    /** Runtime type — how the engine loads and calls this function */
    Runtime runtime();  // BUILT_IN | WASM | GRPC | CONTAINER

    /** Metadata for discovery, import/export, versioning */
    FunctionDescriptor descriptor();
}

record FunctionDescriptor(
    String name,           // "pgp-encrypt"
    String version,        // "2.1.0"
    String category,       // TRANSFORM | VALIDATE | ROUTE | DELIVER | GATE
    String scope,          // SYSTEM | TENANT | PARTNER
    String author,         // "system" | "partner-acme"
    boolean exportable,    // can be packaged and shared
    String description
) {}
```

**WASM plugin execution (Chicory runtime):**

```java
// Load partner's custom validator (compiled from Rust/Go/C to WASM)
WasmModule module = chicory.load(wasmBytes);

// For each chunk in the file stream:
Flux<DataBuffer> output = input.map(chunk -> {
    // Copy chunk into WASM linear memory (sandboxed)
    module.memory().write(0, chunk.asByteBuffer());

    // Call the transform function in the sandbox
    int outputLen = module.call("transform", chunk.readableByteCount());

    // Read result from WASM linear memory
    byte[] result = module.memory().read(0, outputLen);
    return bufferFactory.wrap(result);
});
```

**Partner cannot:**
- Access JVM heap (sandbox boundary)
- Read other tenants' files (no file system access)
- Make network calls (no WASI networking unless explicitly granted)
- Crash the platform (trap = only sandbox dies)

**Import/Export:**

```
EXPORT a function:
  GET /api/functions/{id}/export
  → ZIP package containing:
    ├── manifest.json      (name, version, configSchema, ioMode, runtime)
    ├── function.wasm       (compiled WASM module)
    └── README.md           (usage documentation)

IMPORT a function:
  POST /api/functions/import
  Body: multipart (ZIP package)
  → Platform validates manifest, loads WASM module, registers in catalog
  → Available immediately for flow design
```

### Layer 5: Event Journal (Durability + Audit)

Every state transition is an immutable event. Not traditional DB rows — event sourcing:

```
Event Stream for track TRZ-A1B2C3D4E:

  1  FlowMatched        { flowId, criteria, timestamp }
  2  ExecutionStarted   { trackId, inputKey, stepCount }
  3  StepStarted        { stepIndex: 0, type: COMPRESS_GZIP }
  4  StepCompleted      { stepIndex: 0, outputKey: "sha256-1", durationMs: 120 }
  5  StepStarted        { stepIndex: 1, type: ENCRYPT_PGP }
  6  StepFailed         { stepIndex: 1, error: "encryption-service timeout", attempt: 1 }
  7  StepRetrying       { stepIndex: 1, attempt: 2, backoffMs: 2000 }
  8  StepStarted        { stepIndex: 1, type: ENCRYPT_PGP, attempt: 2 }
  9  StepCompleted      { stepIndex: 1, outputKey: "sha256-2", durationMs: 340 }
  10 StepStarted        { stepIndex: 2, type: APPROVE }
  11 ExecutionPaused    { stepIndex: 2, reason: "awaiting approval" }
     ... (3 hours pass — actor suspended, zero resources consumed) ...
  12 ApprovalReceived   { reviewer: "admin@company.com", decision: APPROVED }
  13 ExecutionResumed   { fromStep: 3 }
  14 StepStarted        { stepIndex: 3, type: FILE_DELIVERY }
  15 StepCompleted      { stepIndex: 3, durationMs: 890 }
  16 ExecutionCompleted { totalDurationMs: 10801350, stepsExecuted: 4 }
```

**What this enables:**
- **Time-travel debugging**: Replay events to reconstruct state at any point in time
- **Restart from any step**: Event journal knows the storageKey at every step boundary
- **Full audit trail**: Immutable, append-only — satisfies regulatory requirements
- **Actor recovery**: On JVM restart, replay events to rebuild in-memory actor state
- **Metrics & analytics**: Event stream feeds dashboards in real time

### Execution Model Comparison: Final

```
FOR LOOP (current):
  Start → step1 → step2 → step3 → step4 → End
  |_________________________________________________|
  Single thread, blocked entire duration, steps serial

DAG (Airflow/Prefect):
  Scheduler polls → step1 → step2a ─┐
                           → step2b ─┼→ join → step3 → End
                                     │
  External coordinator, batch-oriented, no streaming

TEMPORAL (durable execution):
  Worker polls → activity1 → event → activity2 → event → activity3
  |__________________|          |__________________|
  Durable, but 2MB payload limit, external server required

PETRI NET:
  Places + transitions + tokens = concurrent state machine
  Formally verifiable but hard to program, no mainstream Java runtime

═══════════════════════════════════════════════════════════════

DRP — DURABLE REACTIVE PIPELINE (proposed):

  ┌─────────────────────────────────────────────────────────┐
  │ Virtual Thread Actor (per flow)                         │
  │                                                         │
  │   Event Journal ◄── state transitions ──►  Recovery     │
  │                                                         │
  │   Reactive Pipeline:                                    │
  │     CAS ──► [fn1: 64KB chunks] ──► [fn2] ──► [fn3] ──► CAS  │
  │              │ backpressure │                            │
  │              │ SEDA queue   │                            │
  │              └──────────────┘                            │
  │                                                         │
  │   Functions: BUILT_IN │ WASM │ gRPC │ CONTAINER         │
  │                                                         │
  │   ✓ No external dependency (engine IS the app)          │
  │   ✓ No payload limit (bytes stream, never in engine)    │
  │   ✓ Durable (event journal + actor recovery)            │
  │   ✓ Millions concurrent (virtual threads)               │
  │   ✓ Bounded memory (reactive backpressure)              │
  │   ✓ Pluggable (WASM sandbox for partner functions)      │
  │   ✓ Observable (event stream → dashboards)              │
  │   ✓ Restartable (replay from any step's storageKey)     │
  └─────────────────────────────────────────────────────────┘
```

---

## Memory & Boot Projections: DRP vs Current

### Boot Time (DRP)

| Component | Cost | With 100 Flows | With 10,000 Flows |
|-----------|------|-----------------|-------------------|
| Spring context | ~5-8s | ~5-8s | ~5-8s |
| Flow rule compilation | O(N) × <1ms | ~50ms | ~5s |
| Function registry load | O(F) built-ins | ~10ms | ~10ms |
| WASM module preload | O(W) × ~50ms each | ~200ms (4 WASM) | ~2.5s (50 WASM) |
| Actor recovery (replay journals) | O(A) active flows | ~100ms | ~5s |
| **Total** | | **~6s** | **~18s** |

WASM modules can be lazy-loaded (on first use) to keep boot under 10s even with 50+ plugins.

### Runtime Memory (DRP vs Current)

**Scenario: 1,000 concurrent flows, mixed file sizes**

| Component | Current Engine | DRP Engine |
|-----------|---------------|------------|
| 100 × 1 KB EDI files | 460 KB (fine) | 6.4 MB (actors + buffers) |
| 500 × 10 MB documents | **23 GB** (OOM) | 32 MB (500 × 64KB pipeline) |
| 300 × 100 MB archives | **138 GB** (impossible) | 19 MB (300 × 64KB pipeline) |
| 100 × 1 GB batches | **460 GB** (absurd) | 6.4 MB (100 × 64KB pipeline) |
| Actor state (all 1000) | ~10 MB | ~50 MB (event replay cache) |
| WASM plugin memory | N/A | ~256 MB (50 plugins × ~5MB each) |
| Function registry | ~500 bytes × 16 | ~2 KB × 100 |
| SEDA queues | N/A | ~12 MB (3 stages × bounded) |
| **TOTAL** | **>600 GB → OOM** | **~380 MB** |

The 1,000× memory reduction comes from ONE change: bytes stream through 64 KB chunks
instead of loading into heap. Everything else is incremental.

### Adding More Functions: Memory Impact

| Functions Loaded | Current (switch cases) | DRP (registry) |
|-----------------|----------------------|----------------|
| 16 (today) | ~0 (code on classpath) | ~8 KB (descriptors) |
| 50 | ~0 (more switch cases) | ~25 KB (descriptors) |
| 100 (with 30 WASM) | N/A (can't add external) | ~150 MB (30 × 5MB WASM linear memory) |
| 500 (marketplace) | N/A | ~150 MB (WASM lazy-loaded, only active ones in memory) |

Adding functions does NOT increase per-flow memory. Each function processes 64 KB chunks
regardless of how many functions exist. The registry is metadata only.

---

## Technology Stack for DRP

| Layer | Technology | Why This, Not That |
|-------|-----------|-------------------|
| **Actor runtime** | Java 21 virtual threads | No external dependency (vs Temporal server). Millions of concurrent actors on 1 GB heap. |
| **Byte streaming** | Project Reactor `Flux<DataBuffer>` | Backpressure-aware, bounded memory, composable operators. |
| **Event journal** | PostgreSQL (JSONB append-only table) | Already in the stack. No new infra. Event sourcing without Kafka. |
| **Plugin sandbox** | Chicory (WebAssembly on JVM) | Pure Java, no JNI, no native deps. 30-50% native speed. Sandboxed. |
| **External plugins** | gRPC streaming (`stream Chunk`) | Language-agnostic, bidirectional streaming, standard protocol. |
| **Stage queues** | `java.util.concurrent.LinkedBlockingQueue` | Bounded, backpressure-native, zero external deps. |
| **Rule matching** | Current `FlowRuleRegistry` (keep) | Already excellent: pre-compiled predicates, sub-microsecond matching. |
| **Zero-copy I/O** | `FileChannel.transferTo()` (sendfile) | Production-ready today. io_uring when Java libraries mature (~2027). |
| **State recovery** | Event replay on boot (Netflix Maestro pattern) | DB is truth. Actors rebuild from journal. No external state store. |

---

*This document is the complete design proposal. Implementation phases:*

*Phase 1 — Streaming reads + zero-copy (GAPs 1, 7, 10) + new StorageBackend interface*
*Phase 2 — Reactive byte pipeline (Layer 3) + FlowFunction interface*
*Phase 3 — SEDA stages with admission control (Layer 2)*
*Phase 4 — Event journal + actor model (Layers 1, 5)*
*Phase 5 — WASM plugin runtime + gRPC sidecar support (Layer 4)*
*Phase 6 — Function marketplace (import/export/catalog)*

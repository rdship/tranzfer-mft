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

*This document is the design proposal. Implementation begins with streaming reads + zero-copy
(GAPs 1, 7, 10), then S3 multipart (GAP 2), then WAIL + atomic tier moves (GAPs 3, 6),
then resumable uploads (GAP 5), then lane system (GAP 8).*

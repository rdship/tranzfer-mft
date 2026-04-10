# File Flow Architecture: Kubernetes, On-Premise, and Virtual File System Integration

## Overview

This document explains how files flow through the platform when using different deployment models (Kubernetes vs. on-premise) and storage backends (S3 vs. shared mounts via virtual-file-system), with a focus on SFTP/FTP/FTPS integration.

## Key Components

- **SFTP Service**: Receives file uploads and records metadata in VFS
- **Virtual File System (VFS)**: Maintains logical file locations mapping to physical storage
- **Storage Manager**: Abstraction layer that handles storage backend operations (S3, MinIO, NFS, local)
- **ParallelIOEngine**: Low-level I/O engine inside storage-manager — handles write path per file size
- **StorageLifecycleManager**: Moves files between HOT / WARM / COLD tiers automatically
- **FlowProcessingEngine**: Processes files based on VFS notifications

---

## How Files Are Written: Inline vs. Striped (ParallelIOEngine)

Every file — regardless of upload method (SFTP, FTP, API, VFS write channel) — passes through `ParallelIOEngine.write()`. The engine picks a write strategy based on file size vs. the configured stripe size (`storage.stripe-size-kb`, default **4096 KB = 4 MB**).

### Small Files (< 4 MB): Direct Write

```
Upload stream → 64 KB read buffer → BufferedOutputStream → disk
                                   → SHA-256 computed inline
                                   → fsync (if enabled)
```

- Single thread, no temp file
- 64 KB read buffer, large write buffer (default 64 MB)
- SHA-256 computed on the fly while writing
- Fast for EDI, invoices, config files, small XML/JSON

### Large Files (≥ 4 MB): Parallel Striped Write

```
Upload stream → temp file (fully buffered to disk)
                     ↓ SHA-256 computed during buffer
                     ↓
             Split into N stripes (each 4 MB)
                     ↓
        [Thread 1]  [Thread 2]  [Thread 3] ... [Thread N]
        write       write       write           write
        offset 0    offset 4MB  offset 8MB      offset N*4MB
                     ↓
             destination file (pre-allocated, positioned writes)
                     ↓
                  fsync → done
```

- Destination file pre-allocated to full size before stripe writes begin
- Each stripe uses `FileChannel.read(buf, offset)` + `FileChannel.write(buf, offset)` — thread-safe positioned I/O (GPFS-style)
- Default 8 I/O threads (`storage.io-threads`), configurable
- Max file size enforced: default **10 GB** (`storage.max-file-size-bytes`)

### Content-Addressed Storage (CAS)

All files are stored using SHA-256 as the filename — not the original name. This is how deduplication and VFS work together:

```
Step 1: Write to temp path   → {hotPath}/tmp-{threadId}-{nanoTime}
Step 2: Compute SHA-256       → abc123def456...
Step 3: Check if CAS exists   → {hotPath}/abc123def456...
  → EXISTS:  delete temp, return "DEDUPLICATED"
  → MISSING: rename temp → {hotPath}/abc123def456...
Step 4: VFS records mapping   → "/inbox/invoice.xml" → sha256=abc123def456...
```

The logical filename (`invoice.xml`) lives only in the VFS metadata and the `StorageObject` DB record. Physical storage never knows the filename — just the content hash.

### Storage Tiers

New files always land in **HOT**. `StorageLifecycleManager` moves them automatically (runs every 15 min):

| Tier | Path | Move Condition | Access Pattern |
|------|------|----------------|----------------|
| **HOT** | `/data/storage/hot/{sha256}` | Initial write | SSD, <10ms read |
| **WARM** | `/data/storage/warm/{user}/{file}` | Not accessed for 168 h (7 days) | Slower storage |
| **COLD** | `/data/storage/cold/{user}/{file}` | Not accessed for 30 days | Archive storage |
| **BACKUP** | `/data/storage/backup/{date}/{tier}/{file}` | Incremental every 6 h | Disaster recovery |

**Smart tiering rules:**
- Files accessed in the last 24 h are never evicted from current tier
- HOT files with access count > 10 are kept hot regardless of age
- If HOT is >80% full: aggressive eviction of oldest files to target 60% capacity
- **Predictive pre-staging**: if an account has a regular download pattern, files are moved back to HOT before their expected access time

### Upload API Paths (VFS Write Channels)

Storage-manager exposes three write endpoints for different use cases:

| Endpoint | Content-Type | Use Case |
|----------|-------------|----------|
| `POST /api/v1/storage/store` | `multipart/form-data` | UI uploads, API clients |
| `POST /api/v1/storage/store-bytes` | raw bytes in body | VFS write channels, internal services |
| `POST /api/v1/storage/store-stream` | `application/octet-stream` | Streaming, no heap buffer for large files |

`store-stream` is the most efficient for large files — the upload stream pipes directly into `ParallelIOEngine` with only a 64 KB read buffer, no intermediate byte array.

### Retrieval Paths

| Endpoint | Lookup | Routing |
|----------|--------|---------|
| `GET /api/v1/storage/retrieve/{trackId}` | DB by trackId → sha256 → backend | Single instance |
| `GET /api/v1/storage/retrieve-by-key/{sha256}` | Redis location registry → pod routing | Multi-replica: proxies to owning pod |
| `GET /api/v1/storage/stream/{sha256}` | Same as above, streaming response | Large file downloads |

In multi-replica deployments with local backend, Redis tracks which pod stored each sha256. If you request a file that lives on pod-2 from pod-1, pod-1 proxies the request transparently. In S3 mode, all pods read from S3 directly — no routing needed.

---

## Scenario 1: Kubernetes with S3

```
[SFTP Upload] → [sftp-service records in VFS] → [VFS → S3 storage-manager] → [FlowProcessingEngine watches S3]
                     ↓
              "/inbox/invoice.xml"
                     ↓
              [storage-manager coordinates S3]
```

**Flow:**
1. User uploads file via SFTP to sftp-service
2. sftp-service records: `"/inbox/invoice.xml" → sha256=abc123..."`
3. File bytes stored in S3 at key: `s3://bucket/abc123...`
4. VFS notifies FlowProcessingEngine of new file
5. FlowProcessingEngine triggers processing workflow

---

## Scenario 2: Kubernetes with Shared Mount (NFS/EFS)

```
[SFTP Upload] → [sftp-service records in VFS] → [VFS metadata recorded] → [Files on shared NFS/EFS mount] → [FlowProcessingEngine reads from mount]
                     ↓
              "/inbox/invoice.xml"
                     ↓
              [storage-manager writes to NFS mount]
              [Physical location: /data/storage/hot/abc123...]
```

**How it works:**

- **All pods share the same NFS mount** at `/data/storage/hot/`
- sftp-service writes uploaded bytes to `/data/storage/hot/{sha256}`
- VFS records the mapping: `"/inbox/invoice.xml" → sha256=abc123... → /data/storage/hot/abc123..."`
- FlowProcessingEngine reads directly from the shared mount (no cloud API calls)
- storage-manager abstracts whether it's S3 or NFS—code is identical

**StorageBackend abstraction** (one env var controls behavior):
```java
// Kubernetes with NFS
STORAGE_BACKEND=nfs
STORAGE_NFS_MOUNT=/data/storage/hot

// OR Kubernetes with EFS
STORAGE_BACKEND=efs
STORAGE_EFS_MOUNT=/mnt/efs

// OR Kubernetes with S3
STORAGE_BACKEND=s3
AWS_S3_BUCKET=my-bucket
```

---

## Scenario 3: On-Premise with Shared Mount

```
[SFTP/FTP/FTPS Server] → [VFS records metadata] → [Shared NFS/SMB mount] → [FlowProcessingEngine]
     ↓
  Virtual File System
  instead of S3
```

**Setup:**

1. **SFTP/FTP Server** (sftp-service or ftp-web-service):
   - Configured via UI or API to use VFS instead of S3
   - Example API call:
     ```
     POST /api/servers
     {
       "name": "OnPrem-SFTP",
       "type": "sftp",
       "storageBackend": "nfs",
       "nfsMount": "//fileserver.local/shared/upload",
       "vfsEnabled": true
     }
     ```

2. **VFS Component**:
   - Maintains logical paths: `/inbox/invoice.xml`
   - Maps to physical location on shared mount: `/shared/upload/abc123...`
   - Stores mapping in distributed cache (Redis) or local cache with replication

3. **Shared Mount**:
   - NFS, SMB, or local network mount
   - All processing nodes have read access to same file locations
   - Example: `\\fileserver.local\shared\upload\`

4. **File Communication with FlowProcessingEngine**:
   - **VFS triggers event**: When file is written, VFS broadcasts event (Redis pub/sub or webhook)
   - **Event contains**: Logical path (`/inbox/invoice.xml`) + physical location hash + metadata
   - **FlowProcessingEngine**:
     ```java
     VFS.onFileReceived("/inbox/invoice.xml", {
       physicalPath: "/shared/upload/abc123...",
       size: 2048000,
       checksum: "sha256:abc123...",
       metadata: {...}
     });
     → Flow.process(invoice);
     ```

---

## Architecture Comparison Table

| Component | Kubernetes + S3 | Kubernetes + NFS | On-Premise + NFS |
|-----------|-----------------|------------------|------------------|
| **Storage** | S3 bucket | Shared NFS mount | Shared NFS/SMB mount |
| **VFS Role** | Metadata → S3 keys | Metadata → local paths | Metadata → local paths |
| **File Access** | S3 API (GetObject) | Direct filesystem read | Direct filesystem read |
| **FlowEngine** | Polls S3 or SNS | Inotify on mount | Inotify on mount |
| **Latency** | ~100-500ms (API calls) | <10ms (direct access) | <10ms (direct access) |
| **Scaling** | Unlimited objects | Mount capacity limit | Mount capacity limit |
| **Failover** | S3 replication | NFS HA (standby) | NFS HA (standby) |

---

## The Seamless Integration

The key insight is **layering**:

```
Application Layer     → VFS (logical paths)
                        "/inbox/invoice.xml"

Physical Layer       → storage-manager (backend agnostic)
                        "/data/storage/hot/abc123..."

Notification Layer   → Events (same regardless of backend)
                        VFS.onFileReceived() → FlowProcessingEngine
```

### Code Example: Identical Processing

```java
// This code works the SAME way for S3, NFS, and on-premise:

VirtualFileSystem vfs = VFSFactory.getInstance();
vfs.onFileReceived("/inbox/invoice.xml", metadata, (logicalPath, physicalLocation) -> {
    // Load file—works regardless of storage backend
    byte[] fileContent = vfs.readFile(physicalLocation);
    
    // Process
    InvoiceProcessor.process(fileContent, metadata);
    
    // Mark as processed
    vfs.moveFile(logicalPath, "/processed/invoice.xml");
});
```

The **only difference** is where files are stored (S3 bucket vs. NFS mount). The processing code doesn't care.

---

## Deployment Decision Tree

### Question 1: Cloud or on-premise?
- **Cloud** → Use Kubernetes + S3 or managed file storage
- **On-Premise** → Use shared NFS/SMB mount

### Question 2: What's the file source?
- **SFTP/FTP/FTPS server** → Configure via UI to use VFS (automatic)
- **API uploads** → API endpoint uses VFS automatically
- **Direct mount** → Application writes directly to mounted path, VFS monitors

### Question 3: How to notify FlowProcessingEngine?
- **Kubernetes + S3** → SNS/EventBridge or polling S3 ListObjects
- **Any + NFS/Mount** → inotify (Linux) or FSEvents (macOS) on mount path
- **Distributed** → Redis pub/sub or webhook from VFS to all FlowEngine instances

---

## Configuration Examples

### Kubernetes Deployment (YAML snippet)

```yaml
# With S3
spec:
  env:
    - name: STORAGE_BACKEND
      value: "s3"
    - name: AWS_S3_BUCKET
      value: "my-file-bucket"

# OR with NFS
spec:
  volumes:
    - name: storage
      nfs:
        server: "nfs.internal"
        path: "/exports/files"
  containers:
    - volumeMounts:
        - name: storage
          mountPath: "/data/storage/hot"
  env:
    - name: STORAGE_BACKEND
      value: "nfs"
    - name: STORAGE_NFS_MOUNT
      value: "/data/storage/hot"
```

### On-Premise Server Setup

```bash
# Mount shared NFS
sudo mount -t nfs fileserver.local:/shared /mnt/fileserver

# Start SFTP service with VFS config
sftp-service \
  --vfs-enabled \
  --storage-backend=nfs \
  --nfs-mount=/mnt/fileserver \
  --listen-port=2222
```

### API Configuration

```bash
# Create SFTP server that uses VFS
curl -X POST http://localhost:8080/api/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Corporate-SFTP",
    "type": "sftp",
    "port": 2222,
    "storageBackend": "nfs",
    "nfsMount": "//corp-fileserver/shared",
    "vfsEnabled": true,
    "flowProcessing": {
      "enabled": true,
      "rules": [
        {
          "pattern": "*.xml",
          "action": "process_invoice"
        }
      ]
    }
  }'
```

---

## Performance & Reliability Notes

### S3 (Cloud)
- **Pros**: Unlimited scale, built-in replication, managed
- **Cons**: Latency (API calls), cost per request
- **Use when**: Multi-region failover, massive scale needed

### NFS/SMB (On-Premise)
- **Pros**: Local latency (<10ms), no per-request cost, simple setup
- **Cons**: Capacity limited by mount, requires HA setup for failover
- **Use when**: On-premise regulatory requirement, high throughput local processing

### Hybrid
- Use S3 for archival, NFS for hot files
- VFS can tier files automatically based on age/access pattern

---

## Summary

The platform supports seamless file flow across three deployment models:

1. **Kubernetes + S3**: Cloud-native, unlimited scale
2. **Kubernetes + NFS/EFS**: Cloud with local storage performance
3. **On-Premise + NFS/SMB**: Fully local, zero cloud dependency

The Virtual File System (VFS) abstracts the storage backend, so **application code remains identical** regardless of which model you choose. FlowProcessingEngine is notified through the same event system, and files are processed the same way.

The key is that SFTP/FTP/FTPS servers are configured via UI or API to use VFS instead of S3, and from that point forward, all file operations flow through the same VFS layer that handles the backend complexity.

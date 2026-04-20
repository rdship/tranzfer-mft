---
title: "Storage Manager Evolution — the functional home for file coordination"
status: design
depends_on: 01-redis-retirement.md
---

# Storage Manager Evolution — Why It's the Right Home for File-Level Coordination

This doc addresses the user's explicit architectural prompt:

> *"storage manager vs redis etc can we deep dive into that"*

The short answer is: **storage-manager becomes the file-coordination authority, not a generic key-value lock server.** It's a different kind of responsibility absorption — natural, not kitchen-sink.

## What storage-manager owns today

Per `project_fileflow_critical.md` and the 10 MB explainer earlier in this R&D cycle:

1. **Content-Addressed Storage (CAS)**: SHA-256 keyed blobs in MinIO. Every file in the platform has exactly one MinIO object; dedup is `O(1)` on the hash.
2. **Storage metadata**: `storage_objects` table — (sha256, sizeBytes, tier='HOT', trackId, physicalPath, createdAt).
3. **Streaming I/O pattern**: `StorageServiceClient.storeStream()` writes bytes through a `DigestInputStream`; `StorageBackend.readTo()` streams 256 KB chunks to any `OutputStream`. Zero heap roundtrips.
4. **Virtual path → storage-key indirection**: `virtual_entries` (accountId, path, storageKey, sizeBytes) — written by listeners after upload, read by flow engine during step execution.

All four of these concerns are about **"where bytes live, and who can see them"**. That's the invariant that makes storage-manager authoritative.

## What storage-manager SHOULD additionally own (per this R&D)

### 1. File-path distributed locking (`platform_locks` table)

Path locks are directly about file-level coordination:
- Two SFTP listeners uploading to the same `/inbox/invoice.xml` → which one's write wins?
- A CONVERT_EDI step and a FILE_DELIVERY step both operating on the same materialized temp file → when is it safe to clean up?
- Cross-pod VFS consistency during partial failures → the lock IS the arbiter.

These aren't generic locks — they're locks over file paths and storage keys. Storage-manager already has opinions about file lifecycle; path locking is the expression of those opinions in concurrent code.

**Concretely** (from `01-redis-retirement.md`): the `StorageCoordinationService` class lives in storage-manager; it reads/writes `platform_locks`; external callers hit `POST /api/v1/coordination/locks/{key}/acquire`.

### 2. Tier management (HOT / WARM / COLD) — already planned

`storage_objects.tier` already exists. Today it's always 'HOT'. The natural next step (outside this R&D's scope but worth noting):
- `@Scheduled` tier mover: objects untouched for 30 days → WARM (move to cheaper S3 storage class)
- Restore API: `POST /api/v1/storage/{sha256}/restore` for WARM → HOT

This belongs in storage-manager because tier IS a file-lifecycle concern.

### 3. Reference counting for garbage collection

`storage_objects` blobs should be refcounted — when the last `virtual_entries` row pointing at a key is deleted, the MinIO object is eligible for GC. Today this is latent (no cleaner runs; MinIO grows unbounded). The user-stated "no files outside storage-manager" principle means storage-manager should be the GC authority too.

**Design**:

```sql
ALTER TABLE storage_objects ADD COLUMN ref_count INTEGER NOT NULL DEFAULT 0;

-- Triggers (or application-level counters) keep ref_count in sync with
-- virtual_entries. Nightly GC pass:
DELETE FROM storage_objects
  WHERE ref_count = 0 AND created_at < now() - INTERVAL '24 hours';
-- Then async delete from MinIO for each removed row.
```

Again, this belongs in storage-manager — it's about byte lifecycle.

## What storage-manager should NOT absorb

To be clear about the boundary:

- **General key-value cache** (Caffeine handles it per-pod)
- **Rate-limit counters** (PG table, concern of ApiRateLimitFilter not storage)
- **Service-registry heartbeats** (concern of cluster/observability, not file lifecycle)
- **Pub/sub for arbitrary events** (RabbitMQ or outbox)

These are orthogonal to "where bytes live". Pushing them into storage-manager would be kitchen-sink anti-pattern.

## Why NOT build a new "coordination-service"

A legitimate alternative: a 24th microservice purely for locks and coordination. Rejected because:

1. **Functional cohesion**: path locks + storage ops read/write the same tables (platform_locks + storage_objects + virtual_entries). Cross-service transactions lose atomicity.
2. **Deployment simplicity**: one less container, one less SPIFFE identity, one less healthcheck.
3. **The user's signal**: "storage manager vs redis" — they see storage-manager as the competitor to Redis for the file-coord class. That judgment is correct.
4. **Service count**: 23 services is already the edge of what one team can operate. Every new service must carry its own weight. A coordination-service with only lock + heartbeat endpoints does not.

## Why NOT put locks in onboarding-api or config-service

Both sit next to "the bytes". Rejected because:

- onboarding-api is the external API surface (auth, signup, admin controls). Mixing file-path coordination into it crosses the "API-gateway vs file-plane" boundary.
- config-service owns static configuration (flows, rules, folder templates). File-path locks are runtime state; different lifecycle.

Storage-manager is the only service whose entire purpose is runtime file state.

## Why NOT put locks into shared-platform (library, not service)

A `@Component` class that every service imports + calls `pg_advisory_xact_lock()` locally would sound lighter. Rejected because:

- PG advisory locks are session-scoped; the VFS write is cross-service (listener → storage-manager)
- The reaper for dead leases needs to be ONE instance, not N (shedlock overhead)
- Centralizing via storage-manager HTTP API gives us the network boundary for observability + rate limits

## New storage-manager surface after retirement

```
GET     /api/v1/storage/{sha256}                   # existing — fetch metadata
GET     /api/v1/storage/stream/{sha256}            # existing — stream bytes
POST    /api/v1/storage/store                      # existing — upload (multipart)
POST    /api/v1/storage/store-stream               # existing — upload (raw stream)
POST    /api/v1/storage/{sha256}/register          # existing — register external upload

# NEW (R134+) — file-level coordination
POST    /api/v1/coordination/locks/{key}/acquire   # acquire or extend lease
DELETE  /api/v1/coordination/locks/{key}           # release lease (holder-authenticated)
GET     /api/v1/coordination/locks                 # list active leases (admin only)

# NEW — refcount + tier (out of this R&D, future)
GET     /api/v1/storage/{sha256}/refcount          # current ref count
POST    /api/v1/storage/{sha256}/tier              # request tier transition
```

## Impact on the 22 other services

Adding coordination to storage-manager means other services reach out when they need a path lock. Analysis:

| Service | Uses path lock? | Change |
|---|---|---|
| sftp-service | Yes — VFS write on close | `StorageCoordinationClient.tryAcquire` around `vfs.writeFile` |
| ftp-service | Yes — same pattern | Same |
| ftp-web-service | Yes — upload servlet | Same |
| https-service (new) | Yes — `UploadController` | Same — added in R134o design |
| as2-service | Yes — AS2 inbound controller | Same |
| flow engine (shared-platform) | Yes — intermediate file materialization | Same |
| onboarding-api | No | None |
| config-service | No | None |
| encryption-service | No | None |
| external-forwarder-service | No | None |
| keystore-manager | No | None |
| analytics-service | No | None |
| ai-engine | No | None |
| screening-service | No | None |
| notification-service | No | None |
| platform-sentinel | No — reads cluster_nodes | None (except service-registry migration from doc 01) |
| license-service | No | None |
| edi-converter | No | None |
| dmz-proxy | No | None |
| gateway-service | No | None |
| ui-service | No | None |
| api-gateway (nginx) | No | One nginx location for `/api/v1/coordination/` routed to storage-manager |

**6 of 22 services change** (the listener services that write to VFS + the flow engine). Minimal blast radius.

## Storage-manager sizing after the migration

Before:
- ~200 MB JVM, ~500 req/s on default profile
- Hot path: CAS read/write + metadata queries

After (post path locks + ref count):
- ~220 MB JVM (+20 MB for lock state cache)
- ~800 req/s on default profile (adds acquire/release calls — one per upload, one per flow-step materialize)

Still well within a single pod's comfort zone. Horizontal scale unchanged; the PG coordination tables are the scale axis now.

## Observability additions

New Micrometer metrics exposed by storage-manager:
- `coordination.locks.active` — gauge, # of live leases
- `coordination.locks.acquired` — counter, incl. held-already vs new-acquire labels
- `coordination.locks.reaped` — counter, leases forcibly expired by the reaper
- `coordination.locks.contention` — histogram, time from first-try to successful-acquire

These surface on the existing Prometheus scrape; alert rules added for `reaped > 10 / min` (which suggests leases aren't being released on the happy path).

## Failure modes and recovery

- **PG unreachable** → lock acquire throws `DataAccessException`; listener upload fails with 503; client retries. No bytes lost.
- **Service pod dies holding a lease** → reaper purges within 30s; next acquirer succeeds. Upload might take one retry.
- **Long-running upload exceeds lease TTL** → caller must call `extend(lockKey, ttl)` periodically. Design: storage-manager's upload path calls `StorageCoordinationService.extend` every 10s as a heartbeat while the upload is in-flight.
- **Reaper pod fails** → ShedLock migrates the reaper responsibility to another storage-manager replica within one cycle.

## What this does NOT solve

- **Distributed storage backend coordination** (e.g., multi-region MinIO replication): out of scope; that's infrastructure-layer.
- **Transaction-spanning locks across unrelated tables**: if you need an `account` row locked alongside a `flow_execution` row, use PG row locks in one transaction. Our locks are for file paths only.

Go to `04-fileflow-function-impact.md` next.

# R134AC — 🥈 Silver (contribution) / 🥈 Silver (product-state): Sprint 9 Phase 1 — Redis SETNX tier deleted cleanly

**Commit tested:** `0c4f9773` (R134AC)
**Date:** 2026-04-22
**Environment:** fresh nuke → `mvn package -DskipTests` (full-repo BUILD SUCCESS) → `docker compose up -d --build` → 33 healthy / 35 total (`https-service` in `health: starting`, same post-boot pattern carried from R134W→AB)

---

## Verdict

| Dimension | Medal | Why |
|---|---|---|
| **R134AC contribution** | 🥈 **Silver** (runtime-verified) | The Redis SETNX middle tier of VFS lockPath is genuinely gone. `backend=storage-coord` log still fires on real SFTP upload; `backend=redis-setnx` lines **zero** in window; `platform:vfs:lock:*` Redis key-count **zero** after upload; `DistributedVfsLock` class-name references across all 6 relevant services **zero** in boot/runtime logs. Subtractive commit lands cleanly with no runtime surprises and full-repo builds still green. Diff-read Bronze-cap pre-runtime per no-pre-medal rule; runtime exercise earns Silver honestly. |
| **Product-state at R134AC** | 🥈 **Silver** (holds R134AB) | BUG 13 canonical signature fires via real SFTP path (Mode B didn't reproduce this cycle — SFTP auth green, R134W instrumentation count 0 DENIES). Sprint-6 all 4 events outbox-only, Sprint-7B @RabbitListener subtraction teeth still sharp, Sprint-8 RabbitMQ slim holds (only `notification.events` + `file.upload.events`). Silver holds end-to-end. |

---

## 1. 3-tier → 2-tier VFS lockPath — proven on real upload

Pre-R134AC: `storage-coord → Redis SETNX → pg_advisory_xact_lock` (3 tiers, middle tier never reached at runtime since R134z).
Post-R134AC: `storage-coord → pg_advisory_xact_lock` (2 tiers).

### Storage-coord primary still wins

```
[VFS][lockPath] backend=storage-coord
    (R134z primary path active — locks flow through storage-manager platform_locks)
  thread=sshd-SftpSubsystem-16036-thread-1
```

Fired on the `sftp put` path. R134O Silver holds without any behaviour change.

### Redis SETNX branch is GONE

```
docker logs mft-sftp-service | grep -c "backend=redis-setnx"  → 0
```

Zero lines in the full 5-minute post-boot window. Branch deleted at compile time, never appears at runtime.

### No Redis VFS keys after upload

```
docker exec mft-redis redis-cli KEYS 'platform:vfs:lock:*'
  → (empty)
```

No `platform:vfs:lock:<hash>` keys created or left behind. The SETNX write path is dead code AND dead data — nothing writes to those keys anymore.

### Class-reference sanity — no residual Javadoc/log references

```
docker logs mft-{sftp,ftp,ftp-web,as2,https,storage-manager}-service \
  | grep -c "DistributedVfsLock"  → 0  (each)
```

Zero references to the deleted class across all services. `VfsLockHandle` (new extracted interface) compiles cleanly at every call site; no `ClassNotFoundException` or `NoClassDefFoundError` in any boot log.

---

## 2. Build + class-graph integrity

```
mvn package -DskipTests  → BUILD SUCCESS
```

Full-repo compile passes. The interface extraction (`DistributedVfsLock.LockHandle → VfsLockHandle`) was fanned out across 4 call sites in `VirtualFileSystem` without leaving stale imports elsewhere. `StorageCoordinationClient`'s Javadoc mention of the old class is still present but is historical-context only (not a live reference) — per the R134AC commit message.

---

## 3. Carry-forward Silver holds

### SFTP auth — Mode B did not reproduce

```
SFTP upload globalbank-sftp / partner123 → success
[CredentialService] auth DENIED count in window → 0
```

R134AB self-test at boot: `encoderClass=BCryptPasswordEncoder encodedPrefix='$2a$10$' roundTripOk=true`. The augmented mismatch-branch stays latent another cycle. Two consecutive clean cycles since R134AA's trip.

### BUG 13 canonical signature

```
flow_executions:
  id 3c367edd-3d5c-41a1-9f91-00ad818743d1  status=FAILED
  err="Step 1 (FILE_DELIVERY) failed: partner-sftp-endpoint: 500 …"
```

### Sprint-6 outbox-only (R134X holds)

```
event_outbox:
  account.updated         | 1
  flow.rule.updated       | 1
  keystore.key.rotated    | 1
  server.instance.created | 1
```

### RabbitMQ slim (R134Y holds)

```
notification.events    0  1 consumer
file.upload.events     0  20 consumers
```

Only the two R134Y-designed surviving channels.

---

## 4. Redis container still present (Sprint 9 Phase 1, not final)

Per the R134AC commit message: `DistributedVfsLock` was ONE of ~7 Redis consumers. Others (RateLimit, ProxyGroupService, ActivityMonitor, RedisServiceRegistry, ProxyGroupRegistrar, StorageLocationRegistry, ClusterEventSubscriber) still hold `RedisConnectionFactory` at runtime. Container + YAML bindings stay in place pending R134AD…R134AG migration sequence. This commit is one clean tile in the mosaic, not the mosaic.

---

## Stack health

33 of 35 `(healthy)`; `https-service` in `health: starting` at verification time (same post-boot pattern observed on every clean nuke cycle since R134W — not attributable to R134AC).

---

## Still open

Unchanged except for one closed item:
- **CLOSED:** Redis is no longer on the VFS lock path (R134AC).
- Remaining Redis consumers (≥6) pending R134AD→R134AG sequence; container retirement target is R134AG.
- SFTP Mode B regression (R134V/R134AA) — instrumented by R134W + R134AB; dormant across R134AB and R134AC; still waiting on next trip to earn the decoder's keep.
- `ftp-2` UNKNOWN, demo-onboard 92.1%, coord auth posture review, account handler `type=null` log nit (all carried/cosmetic).

---

## Sprint series state

| Tag | What shipped | Runtime | Pattern |
|---|---|---|---|
| R134T | Sprint 6 dual-path | ✓ | 🥈 |
| R134U | Sprint 7A 3 publishers outbox-only | ✓ | 🥈 |
| R134V | Multi-handler cap | ✓ | 🥈 |
| R134W | SFTP DENY branch observability | latent | 🥉 |
| R134X | Sprint 7B subtractive cutover | ✓ | 🥈 |
| R134Y | Sprint 8 slim RabbitMQ | ✓ | 🥈 |
| R134Z | SSE observability unswallow | ✓ | 🥈 |
| R134AA | SSE auth-context fix | ✓ | 🥈 / 🥉 |
| R134AB | Bytes-level bcrypt observability + self-test | partial | 🥉 / 🥈 |
| **R134AC** | **Sprint 9 P1: delete DistributedVfsLock** | ✓ | 🥈 / 🥈 |

10 atomic R-tags. Sprint 7 + 8 + 9-phase-1 all proven. Phase-wise Redis retirement underway; `DistributedVfsLock` was the easiest consumer to delete because its middle-tier role was never reached at runtime, so R134AC was a pure no-behaviour-change subtraction.

---

**Report author:** Claude (2026-04-22 session). R134AC contribution Silver — subtraction lands cleanly, both positive assertions (`backend=storage-coord`) and negative assertions (`backend=redis-setnx=0`, `platform:vfs:lock:*=0`, `DistributedVfsLock refs=0`) all verify. Product-state Silver — every carry-forward check holds. Diff-read Bronze-capped pre-runtime per no-pre-medal rule; Silver earned cleanly post-runtime.

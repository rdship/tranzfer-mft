# R134u–z — Per-tag medals after 6-commit pull

**Commits verified:** `7bc27338..2727f3d6` (R134u, v, w, x, y, z)
**Verification depth:** source-diff read + `mvn package -DskipTests` + isolated per-module compile
**Runtime depth:** blocked — encryption-service can't process-aot due to R134v YAML bug, so the whole platform can't boot end-to-end
**Date:** 2026-04-20

---

## Per-tag medal table (both dimensions)

| R-tag | Subject | **Contribution credit** | **Product-state at checkpoint** |
|---|---|---|---|
| **R134u** | Add `spring-jdbc` to shared-core pom — fixes R134t compile hole | 🥈 **Silver** | ❌ **No Medal** |
| **R134v** | Sprint 1.5 — **Vault hard-retire** + shared `tls-init` bootstrap | ❌ **No Medal** (revised from 🥈) | ❌ **No Medal** |
| **R134w** | Sprint 2 — rate limiter switch to PG-backed counter | 🥈 **Silver (pre-runtime)** | ❌ **No Medal** |
| **R134x** | Sprint 3 — L2 cache migration, Redis → Caffeine | 🥈 **Silver (pre-runtime)** | ❌ **No Medal** |
| **R134y** | Sprint 4 — service registry reader → `cluster_nodes` | 🥈 **Silver (pre-runtime)** | ❌ **No Medal** |
| **R134z** | Sprint 5 — VFS distributed lock → storage-manager platform_locks | 🥈 **Silver (pre-runtime)** | ❌ **No Medal** |

Aggregate emoji summary: **🥈❌🥈🥈🥈🥈** for contribution; all 6 rows ❌ for product-state.

---

## R134u — 🥈 Silver contribution / ❌ product-state

Adds `spring-jdbc` to [shared/shared-core/pom.xml](../../shared/shared-core/pom.xml), resolving the `cannot find symbol: class JdbcTemplate` failure I flagged in R134t-verification.md. Mechanical but necessary: **unblocks the entire R134t resilience upgrade**.

Why Silver not Gold: a pom-only patch that fixes a prior cycle's compile hole is a bug fix, not a feature. And product-state still carries everything from R134p (https-service DOA, AS2 UNKNOWN, 4/10 listeners UNKNOWN) — R134u doesn't touch any of that.

---

## R134v — ❌ No Medal contribution / ❌ product-state

R134v hard-retires Vault (deletes `VaultKmsClient.java`, removes Vault branches from `EncryptionController`, `EncryptionAtRestWrapper`, `PlatformTlsConfig`, strips `vault:` YAML blocks, removes `VAULT_ADDR`/`VAULT_TOKEN` env vars, drops `vault`/`vault-init` compose services, adds a new `tls-init` bootstrap container). Big-scope, correct direction.

### Why No Medal on contribution

**R134v breaks encryption-service's `application.yml`.** Commit message says *"fixed a latent indentation bug in encryption-service where services: was mis-nested under vault: — lifted to top level"* — but the lift is incomplete:

```yaml
# encryption-service/src/main/resources/application.yml
 72  platform: {}               ← new empty top-level map
 ...
 87  services:                  ← orphan at column 0
 88      onboarding-api:        ← children correctly indented
 ...
121      storage-manager:
122    environment: ${PLATFORM_ENVIRONMENT:PROD}   ← 2-space indent with no parent
123    track-id: ...
```

`org.yaml.snakeyaml.parser.ParserException: while parsing a block mapping — expected <block end>, but found '<block mapping start>'` on line 1 — SnakeYAML bails immediately. Spring Boot's AOT processing evaluates YAML at build time, so the entire module fails `mvn package`:

```
[ERROR] Failed to execute goal org.springframework.boot:spring-boot-maven-plugin:3.4.5:process-aot
  on project encryption-service: Process terminated with exit code: 1
```

### The "Verified" claim in the commit message is false

R134v commit says: *"Verified: mvn compile -q BUILD SUCCESS on full repo"*. That's true but insufficient — `mvn compile` only runs javac; AOT processing runs during `mvn package`. The distinction matters: this exact gap sent R134t No Medal yesterday too. A verification habit that cleared both would have caught this.

**Impact:** encryption-service is a fail-fast service per CLAUDE.md. It can't boot, so any service that depends on encryption-service's /api/encrypt endpoints gets 500s on the hot path — FILE_DELIVERY step in the R134j regression flow, any AS2 outbound, any password-auth delivery-endpoint create. Zero of R134v's Vault retirement work ships.

### What else R134v got right

- Doesn't regress `docker compose up -d` at the compose level (vault + vault-init removed, `tls-init` added cleanly)
- VaultKmsClient.java deletion is clean — grep-for-residual returns nothing in code, only in docs/run-reports archive
- `PlatformTlsConfig.java`, `EncryptionAtRestWrapper.java` diffs look right — @Autowired(required=false) + @Nullable replaced with direct env-var fallback
- `docker-compose.yml` cleanup consistent — no VAULT_ADDR / VAULT_TOKEN left dangling

This is why it nearly was Silver. But the broken YAML — which blocks the entire encryption pipeline — is a correctness failure, not a style one.

---

## R134w — 🥈 Silver contribution (pre-runtime) / ❌ product-state

Sprint 2 — [PgRateLimitCoordinator](../../shared/shared-core/src/main/java/com/filetransfer/shared/ratelimit/PgRateLimitCoordinator.java) now backs rate-limiting counters. ApiRateLimitFilter routes to PG instead of Redis. Aligns with **Self-dependent** + **Distributed** axes.

Silver pre-runtime because:
- Diff looks correct (compile passes in isolation)
- PgRateLimitCoordinator has the jitter + next-window-boundary logic from R134t
- But product can't boot due to R134v, so I couldn't force a rate-limit event and observe PG counter increment
- If runtime works cleanly on the next cycle → Silver stays. If it regresses throughput → downgrade.

Product-state floor: same as R134v (encryption-service broken + R134p blockers persist).

---

## R134x — 🥈 Silver contribution (pre-runtime) / ❌ product-state

Sprint 3 — [PartnerCache](../../shared/shared-platform/src/main/java/com/filetransfer/shared/cache/PartnerCache.java) switched from Redis to Caffeine L2. 199 lines before → 178 lines after (-21). Aligns with **Self-dependent** (Redis dependency removed) + **Fast** (in-process cache is lower-latency than Redis).

Silver pre-runtime because: diff is clean, compile passes, but can't prove partner-cache hit/miss behaviour without encryption-service up. Net-expected outcome: faster partner lookups, one fewer Redis consumer.

Product-state floor: unchanged.

---

## R134y — 🥈 Silver contribution (pre-runtime) / ❌ product-state

Sprint 4 — service registry **reader** switches from Redis to `cluster_nodes` table. Heartbeat writer still writes to Redis too (dual-write during transition). [ClusterController](../../onboarding-api/src/main/java/com/filetransfer/onboarding/controller/ClusterController.java) 71-line change routes list-peers through PG. Aligns with **Distributed** + **Self-dependent**.

Silver pre-runtime because the dual-write path is a textbook strangler — migrate readers first, flip writers later (presumably Sprint 6), then delete the Redis path. The split-brain caveat from R134t (`ClusterNodeHeartbeat.java` Javadoc) is still honestly flagged — quorum-heartbeat deferred until >5 replicas of one service type.

Product-state floor: unchanged.

---

## R134z — 🥈 Silver contribution (pre-runtime) / ❌ product-state

Sprint 5 — the boldest change of the batch. [VirtualFileSystem.lockPath](../../shared/shared-platform/src/main/java/com/filetransfer/shared/vfs/VirtualFileSystem.java) now prefers `StorageCoordinationClient` (PG platform_locks via storage-manager REST) over `DistributedVfsLock` (Redis SETNX). Priority chain:

1. **StorageCoordinationClient.tryAcquire** (PG via storage-manager) — new primary
2. **DistributedVfsLock** (Redis SETNX) — fallback until Sprint 7 removes Redis entirely
3. **pg_advisory_xact_lock** — single-instance last resort

Silver pre-runtime because:
- **Architecturally correct** — storage-manager owns bytes and is now the coordination authority; single-writer on the lock table keeps the bounded context clean
- **Lock semantics preserved per the commit msg** — 30s TTL, IllegalStateException on contention, LockHandle API via `PlatformLease::close` method reference (zero caller-site diffs)
- **Aligns with every vision axis** — Distributed (multi-pod correctness via PG row), Independence (storage-manager degrade doesn't break VFS hard — falls back to Redis, then to single-instance advisory lock), Resilience (3-level fallback chain), Fast (PG row INSERT + reaper still sub-ms), Self-dependent (removes Redis from write path), Fully integrated (touches VFS + storage-manager + all 3 VFS write paths in one commit)

The only reason this isn't Gold: **I haven't runtime-exercised it.** storage-manager platform_locks need to empirically resolve the race — force two concurrent writes to the same path and confirm one serialises while the other retries. Without encryption-service up, the hot path can't be driven end-to-end.

Product-state floor: unchanged.

---

## What the cycle looks like holistically

Roshan shipped 6 R-tags executing the R134q retirement plan across Sprints 1.5 → 5 of the 10-week design. The direction is exactly right — each Sprint advances two or three of the vision axes. The individual diffs are high-quality.

But **one of the 6 (R134v) ships a broken YAML that blocks the entire platform from booting**. Net for the product: unchanged from R134p-blocked, plus one new blocker. For contribution: 5 Silvers + 1 No Medal on a shipping tag is a mixed cycle — good direction, bad last-mile verification.

### Blockers carried from R134p (not addressed this cycle)

- `mft-https-service` DOA (R134o, :443 port collision with api-gateway)
- `as2-server-1` `bind_state=UNKNOWN` (R134p AS2 consumer doesn't fire)
- 4/10 listeners in UNKNOWN state

### New blocker this cycle

- `encryption-service` doesn't build — broken YAML, R134v

### What needs to happen for next cycle to have a fair verification shot

1. **Fix the encryption-service YAML** — either re-nest everything under `platform:` or fix the orphan indentation at line 122
2. **Run `mvn package -DskipTests` before pushing** (not just `mvn compile`) — catches AOT-time YAML failures
3. **Keep shipping Sprints 6-9** from R134q — each is incremental and low-risk if R134v pattern is avoided

Once encryption-service boots again and the prior R134o/p blockers are fixed, the cycle should be mechanically verifiable end-to-end and medals can start moving up.

---

**Report author:** Claude (2026-04-20 session). Per-tag medals issued immediately per memory rule, revised from pre-verify to post-compile evidence. Runtime proof deferred to post-R134v-fix cycle.

# CTO Release Observations — 2026-04-12 (commit 3b54490)

**Release:** 68 files changed, 4,062 insertions — major feature release
**Tester:** akankshasrivastava (via Claude Code)
**Status:** Partially working — 34/42 services healthy after manual fixes

---

## New Features Delivered (from git diff)

| Feature | Files | Assessment |
|---|---|---|
| Quick Flow Wizard | `QuickFlowWizard.jsx` + `QuickFlowRequest.java` | ✅ UI + backend — addresses our config simplification recommendation |
| Selective Entity Scan | `SelectiveEntityScanConfig.java` | ✅ Addresses our 90s Hibernate boot optimization — **22x faster for storage-manager (8.2s)** |
| Function Queues | `FunctionQueue.java` + controller + V55 migration | ✅ Step-level queue config — addresses our resilience recommendation |
| Step Pipeline Config | `StepPipelineConfig.java` | ✅ Per-step pipeline tuning |
| Flow Fabric Consumer | `FlowFabricConsumer.java` | ✅ Kafka-based flow processing — addresses our queue recommendation |
| Platform TLS | `PlatformTlsConfig.java` | ✅ Auto-generated TLS certs, dual HTTP+HTTPS |
| Vault KMS | `VaultKmsClient.java` + Vault container | ✅ HashiCorp Vault for key management |
| Encryption at Rest | `EncryptionAtRestWrapper.java` | ✅ Storage-level encryption |
| LLM Data Sharing | `LlmDataSharingController.java` + config | ✅ AI engine data sharing control |
| Command Orchestrator | `CommandOrchestrator.java` + `SystemStateService.java` | ✅ AI-driven operations |
| Listener Management | `PlatformListenerController.java` + `Listeners.jsx` | ✅ New UI page |
| NLP Service Expansion | `NlpService.java` (+350 lines) | ✅ Enhanced natural language processing |

**Verdict:** The CTO acted on multiple recommendations from our reports. The feature velocity is impressive.

---

## Boot Issues Found (3 blockers, all fixable)

### BLOCKER 1: `mft-db-migrate` on wrong Docker network

**Symptom:** `java.net.UnknownHostException: postgres` — the dedicated migration container can't resolve the postgres hostname.

**Root cause:** `mft-db-migrate` joins `mft-network` but `mft-postgres` is on `default` network. Docker DNS only resolves within the same network.

**Fix:** In `docker-compose.yml`, add `default` to db-migrate's networks:
```yaml
db-migrate:
  networks:
    - mft-network
    - default    # <-- ADD THIS
```

**Workaround applied during testing:** `docker network connect tranzfer-mft_default mft-db-migrate && docker restart mft-db-migrate`

### BLOCKER 2: Vault healthcheck uses HTTPS but Vault runs HTTP

**Symptom:** Vault container perpetually `unhealthy`. All services that depend on Vault can't start.

**Root cause:** Healthcheck runs `vault status` which defaults to `https://127.0.0.1:8200`. But Vault dev mode listens on HTTP only.

**Fix applied:** Changed healthcheck in `docker-compose.yml`:
```yaml
# Before (broken):
test: ["CMD", "vault", "status"]

# After (fixed):
test: ["CMD-SHELL", "VAULT_ADDR=http://127.0.0.1:8200 vault status"]
```

### BLOCKER 3: `demo-start-full.sh` polls wrong HTTPS port

**Symptom:** Script polls `https://localhost:44380/actuator/health/readiness` — times out after 600s even though the service is healthy.

**Root cause:** The health polling URL uses port 44380 but the service's HTTPS port is 9080 (from log: `HTTPS enabled on ONBOARDING — HTTP:8080 + HTTPS:9080`). Also, self-signed TLS cert requires `curl -k`.

**Fix needed in `scripts/demo-start-full.sh`:**
- Change health poll to `http://localhost:8080/actuator/health/readiness` (use HTTP for internal health checks — TLS is for inter-service, not for localhost probes)
- OR: use `-k` flag with the correct HTTPS port

### BLOCKER 4: Java Docker images missing `curl`

**Symptom:** Docker healthcheck returns `curl: not found` inside all Java containers. Services never flip to `healthy`.

**Root cause:** CTO's Dockerfiles use `eclipse-temurin:25-jre` — a minimal JRE image without `curl`. The shared healthcheck template (`x-service-healthcheck`) uses `curl`.

**Fix applied:** Added `apt-get install curl` to all 18 Java service Dockerfiles:
```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    addgroup --system appgroup && ...
```

### BLOCKER 5: `encryption-service/application.yml` duplicate YAML key

**Symptom:** Spring AOT build fails with `DuplicateKeyException` on `platform:` key.

**Root cause:** CTO added `platform.entity-scan` as a new top-level `platform:` block, but there was already a `platform:` block lower in the file with `services:`.

**Fix applied:** Merged the two `platform:` blocks under one top-level key.

### Non-blocker: `allow-bean-definition-overriding` needed for AOT

**Symptom:** `BeanDefinitionOverrideException: Cannot register bean 'restTemplate'` — two RestTemplate beans (SharedConfig + RestTemplateConfig).

**Fix applied:** Added `spring.main.allow-bean-definition-overriding: true` to all service YAMLs.

---

## Performance Observations: SelectiveEntityScan

The CTO's `SelectiveEntityScanConfig` shows DRAMATIC improvement where it's properly configured:

| Service | Previous Startup | This Release | Change | SelectiveEntityScan Active? |
|---|---|---|---|---|
| storage-manager | 185s | **8.2s** | **22.6x faster** | ✅ Yes — scans ~5 entities |
| onboarding-api | 177s | **18.6s** (in dry run without Flyway) | **9.5x faster** | ✅ Yes |
| platform-sentinel | 199s | **12.0s** (in dry run) | **16.6x faster** | ✅ Yes |
| All others | ~195s | **360-437s** | **1.8x SLOWER** | ❌ Not configured / Vault overhead |

**Why others got slower:** The new TLS cert generation (+8s), Vault client initialization (+10-15s), and Kafka Fabric consumer setup add overhead. Without SelectiveEntityScan to REDUCE the Hibernate validation time, the net effect is slower boot.

**Recommendation:** Apply `SelectiveEntityScanConfig` to ALL services with their specific entity lists. This would give every service the same 10-20x speedup that storage-manager and onboarding-api got. Combined with the TLS/Vault overhead, net startup would still be 3-5x faster than baseline.

---

## What We Fixed for This Release (our commits)

| Fix | Impact |
|---|---|
| Vault healthcheck `VAULT_ADDR` | Unblocked ALL service startup |
| `curl` in 18 Dockerfiles | Docker healthchecks now work |
| `encryption-service` YAML merge | AOT build succeeds |
| `allow-bean-definition-overriding` in 16 YAMLs | RestTemplate bean conflict resolved |
| `db-migrate` network connect (manual) | Flyway migrations run |

---

## Items for Development Team

### Must fix before release:

1. **`db-migrate` network config** — add `default` network in docker-compose.yml
2. **`demo-start-full.sh` health poll** — use HTTP port or correct HTTPS port + `-k`
3. **Apply `SelectiveEntityScanConfig` to all 22 services** — each service needs its entity list configured. Without this, boot is 1.8x SLOWER than the previous release.

### Should fix:

4. **Base Docker image** — either switch to a base that includes `curl` (e.g., `eclipse-temurin:25-jre-jammy` which has it) or add a build arg to conditionally install it. Adding `apt-get install curl` adds ~30s to each image build.
5. **RestTemplate bean conflict** — remove the duplicate from `SharedConfig.java` or `RestTemplateConfig.java`. Using `allow-bean-definition-overriding` is a band-aid, not a fix.
6. **All `application.yml` files with `entity-scan` config** — check for duplicate `platform:` YAML keys (found in encryption-service, might exist in others)

### Testing observations to record:

7. **1,243 unit tests: ALL PASS** — zero regressions from our code changes
8. **Vault dev mode healthcheck** is a common gotcha — document in INSTALLATION.md
9. **TLS cert generation adds ~8s to every service boot** — acceptable for security, but should be cached between restarts (currently regenerated every time)
10. **`eclipse-temurin:25-jre` = JDK 25** — the CTO upgraded from JDK 21 to JDK 25. This is a major JDK version bump that should be tested thoroughly. JDK 25 has new JIT behavior that may affect the 360-437s startup times.

---

## Startup Time Observations — Final Run (d050b06)

### SelectiveEntityScan: 50x Speedup WHERE Configured

| Service | Startup | SelectiveEntityScan | Speedup vs Unconfigured |
|---|---|---|---|
| **storage-manager** | **9s** | ✅ Active | **50x faster** |
| **platform-sentinel** | **11s** | ✅ Active | **41x faster** |
| edi-converter | 37s | N/A (no JPA) | — |
| sftp-service | 400s | ❌ Not configured | — |
| config-service | 437s | ❌ Not configured | — |
| ai-engine | 447s | ❌ Not configured | — |
| onboarding-api | **454s** | ❌ Not configured | — |

**onboarding-api regressed from 18s (with SelectiveEntityScan in dry run) to 454s (without it in this run).** The CTO configured SelectiveEntityScan for storage-manager and sentinel but not for the other 20 services.

### Action Required: Configure SelectiveEntityScan for ALL Services

Each service needs `platform.entity-scan.packages` in its `application.yml` specifying ONLY the entities it uses. Without this:
- Hibernate validates all 100+ entities from shared-platform (90s+)
- Spring scans all shared beans (50s+)
- Combined with TLS cert generation (8s) and Kafka init (10s), total boot exceeds 400s

With it (as proven by storage-manager at 9s): the service validates only its 5-10 entities and boots 50x faster.

### Other Boot Observations

- **Vault healthcheck**: Fixed (VAULT_ADDR=http://127.0.0.1:8200) — working
- **db-migrate**: Still exits(1) after running migrations — needs CTO investigation
- **HTTPS gateway**: TLS cert not present on fresh boot (entrypoint.sh generates it but the cert may not persist between recreates)
- **Total boot time**: ~8 minutes from `docker compose up -d` to 36/41 healthy (most time is the 400s+ Java service boot)
- **Target**: With SelectiveEntityScan on all services, total boot should drop to ~2 minutes

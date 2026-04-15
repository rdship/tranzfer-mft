# TranzFer MFT — Memory & CPU Deep Dive Analysis

**Date:** 2026-04-15  
**Build:** R45/R47 (latest measured)  
**Source:** Heap dumps, thread dumps, Docker resource stats, JVM flags from tester  
**Purpose:** Right-size services for Docker, Kubernetes, and on-premise deployment

---

## 1. Actual Memory Usage (Measured, Not Estimated)

### Per-Service Docker Container Memory

| Service | Docker Used | Docker Limit | % Used | Heap Used | Heap Max | Metaspace | Risk |
|---------|------------|-------------|--------|-----------|----------|-----------|------|
| onboarding-api | **765.5M** | 768M | **99.7%** | — | 384M | — | CRITICAL |
| gateway-service | **762.7M** | 768M | **99.3%** | 188M | 384M | 49M | CRITICAL |
| forwarder-service | **761.0M** | 768M | **99.1%** | 154M | 384M | 104M | CRITICAL |
| config-service | **760.7M** | 768M | **99.1%** | — | 384M | — | CRITICAL |
| notification-service | **755.9M** | 768M | **98.4%** | 146M | 384M | 89M | HIGH |
| sftp-service | **744.9M** | 768M | **97.0%** | — | 384M | — | HIGH |
| ai-engine | **744.7M** | 768M | **97.0%** | 240M | 384M | 77M | HIGH |
| as2-service | **833.0M** | no limit | — | — | 384M | — | UNBOUNDED |
| ftp-service | **703.2M** | 768M | **91.6%** | — | 384M | — | MODERATE |
| ftp-web-service | **702.3M** | 768M | **91.4%** | — | 384M | — | MODERATE |
| platform-sentinel | **687.8M** | 768M | **89.6%** | 134M | 384M | 38M | MODERATE |
| screening-service | **675.0M** | 768M | **87.9%** | 128M | 384M | 31M | MODERATE |
| storage-manager | **666.4M** | 768M | **86.8%** | 132M | 384M | 56M | MODERATE |
| encryption-service | **651.3M** | 768M | **84.8%** | 140M | 384M | 83M | OK |
| analytics-service | **636.9M** | 768M | **82.9%** | 142M | 384M | 48M | OK |
| license-service | **618.7M** | 768M | **80.6%** | 132M | 384M | 66M | OK |
| keystore-manager | **591.7M** | 768M | **77.0%** | 142M | 384M | 57M | OK |
| edi-converter | **352.0M** | 768M | **45.8%** | — | 384M | — | OK |
| dmz-proxy | **230.4M** | no limit | — | — | — | — | OK |

### Infrastructure Services

| Service | Docker Used | Notes |
|---------|------------|-------|
| PostgreSQL | 221.2M | Shared DB, max_connections=400 |
| Redpanda (Kafka) | 217.0M | Single broker |
| RabbitMQ | 154.2M | Message broker |
| Redis | 10.6M | Cache + pub/sub |

---

## 2. Where JVM Memory Goes

### Breakdown for a Typical Heavy Service (onboarding-api: 765.5M total)

| Component | Size | % of Total | Notes |
|-----------|------|-----------|-------|
| Java Heap (ZGC) | 384M | 50% | `-Xmx384m` — ZGC can use up to max |
| Metaspace | 80-105M | 13% | Classes loaded: entities, repos, Spring beans |
| ZGC Structures | 50-100M | 10% | Page tables, forwarding tables, GC metadata |
| JIT Code Cache | 30-80M | 8% | C1+C2 compiled native code |
| Thread Stacks | 14-20M | 2% | 14-20 threads × 1M default stack |
| Native/Direct Buffers | 20-50M | 5% | JDBC, NIO, Netty, Kafka client |
| JVM Internals | 50-80M | 8% | Symbol tables, string table, class data |
| **TOTAL** | **628-819M** | | **Matches observed 600-766M** |

### Metaspace Varies 3x Across Services

| Service | Metaspace | Why |
|---------|-----------|-----|
| screening-service | **31M** | Core-only scan (21 entities, 20 repos) |
| platform-sentinel | **38M** | Core+transfer+security (47 entities) |
| analytics-service | **48M** | Core only + local entities |
| gateway-service | **49M** | Core+transfer+security+integration+vfs (63 entities) |
| storage-manager | **56M** | Core only + VFS |
| keystore-manager | **57M** | Core only |
| license-service | **66M** | Core only |
| ai-engine | **77M** | All 5 subpackages + 13 local entities |
| encryption-service | **83M** | Core only (high due to BouncyCastle crypto classes) |
| notification-service | **89M** | Core+integration+security |
| forwarder-service | **104M** | All 5 subpackages (highest — most beans loaded) |

**Key insight:** Metaspace is driven by the number of classes loaded. Services that scan more entities + load more shared beans use more Metaspace. Our R37 selective scanning reduced Metaspace for lightweight services.

---

## 3. CPU Usage (Measured at Steady State)

| Service | CPU % | Classification |
|---------|-------|---------------|
| gateway-service | **6.1%** | Highest — proxies all HTTP traffic |
| sftp-service | **4.0%** | High — handles file I/O |
| as2-service | **3.8%** | High — message processing |
| ftp-service | **3.8%** | High — handles file I/O |
| ftp-web-service | **3.8%** | High — handles file I/O |
| onboarding-api | **3.3%** | Moderate — central API |
| redpanda | **3.2%** | Moderate — Kafka broker |
| config-service | **3.0%** | Moderate |
| forwarder-service | **2.5%** | Moderate |
| platform-sentinel | **1.8%** | Low — monitoring |
| keystore-manager | **1.6%** | Low |
| storage-manager | **1.5%** | Low |
| postgres | **1.4%** | Low |
| encryption-service | **1.1%** | Low |
| screening-service | **1.0%** | Low |
| notification-service | **1.0%** | Low |
| ai-engine | **0.9%** | Low |
| license-service | **0.8%** | Low |
| analytics-service | **0.6%** | Low |
| rabbitmq | **0.4%** | Minimal |
| redis | **0.4%** | Minimal |
| edi-converter | **0.2%** | Minimal |
| dmz-proxy | **0.2%** | Minimal |

**Total steady-state CPU:** ~42% of 9 cores = ~3.8 cores used. Plenty of headroom after boot.

---

## 4. Boot Time (Measured R45)

| Service | Boot Time | Category |
|---------|-----------|----------|
| dmz-proxy | **23.8s** | No DB |
| edi-converter | **23.4s** | No DB |
| screening-service | **131.7s** | Lightweight (core+security) |
| encryption-service | **138.5s** | Lightweight (core only) |
| storage-manager | **141.2s** | Lightweight (core only) |
| analytics-service | **153.5s** | Lightweight (core only) |
| notification-service | **162.6s** | Lightweight (core+integration+security) |
| platform-sentinel | **169.2s** | Lightweight (core+transfer+security) |
| ftp-service | **175.1s** | Protocol (all entities) |
| ftp-web-service | **173.7s** | Protocol |
| gateway-service | **175.8s** | Protocol |
| config-service | **175.6s** | Central |
| sftp-service | **176.7s** | Protocol |
| as2-service | **175.6s** | Protocol |
| ai-engine | **176.7s** | Central |
| onboarding-api | **179.3s** | Central |
| forwarder-service | **181.8s** | Protocol |

---

## 5. Recommendations for Docker / Kubernetes / On-Premise

### Docker Compose (Dev/Test)

| Service Tier | Services | Heap | Docker Memory Limit | Docker CPU Limit |
|-------------|----------|------|--------------------|-----------------| 
| **Protocol** | sftp, ftp, ftp-web, gateway, as2, forwarder | 384M | **1024M** | 1.5 |
| **Central** | onboarding-api, config-service, ai-engine | 384M | **1024M** | 1.5 |
| **Lightweight DB** | encryption, analytics, screening, notification, sentinel, storage, license, keystore | 256M | **768M** | 1.0 |
| **No DB** | dmz-proxy, edi-converter | 256M | **512M** | 0.5 |
| **Infrastructure** | postgres, redis, rabbitmq, redpanda | — | **512M-1G** | 1.0 |

### Kubernetes (Production)

```yaml
# Protocol / Central services (onboarding, sftp, ftp, gateway, config, ai-engine, as2, forwarder)
resources:
  requests:
    memory: "768Mi"
    cpu: "500m"
  limits:
    memory: "1024Mi"
    cpu: "2000m"

# Lightweight DB services (encryption, analytics, screening, notification, sentinel, storage, license, keystore)
resources:
  requests:
    memory: "512Mi"
    cpu: "250m"
  limits:
    memory: "768Mi"
    cpu: "1000m"

# No-DB services (dmz-proxy, edi-converter)
resources:
  requests:
    memory: "256Mi"
    cpu: "100m"
  limits:
    memory: "512Mi"
    cpu: "500m"
```

### On-Premise (Bare Metal / VM)

| Deployment Size | Services | Minimum RAM | Recommended RAM | CPU Cores |
|----------------|----------|-------------|-----------------|-----------|
| **Small** (all 22 services on 1 host) | 22 | 16 GB | 24 GB | 8 |
| **Medium** (split DB/protocol) | 22 across 2 hosts | 32 GB total | 48 GB total | 16 total |
| **Production** (HA, 2 replicas each) | 44 instances | 64 GB total | 96 GB total | 32 total |

### JVM Flags by Tier

```bash
# Protocol / Central (heavy — scans all entities, runs routing pipeline)
-Xmx384m -Xms256m -XX:ActiveProcessorCount=2

# Lightweight DB (core-only scan, no routing)  
-Xmx256m -Xms128m -XX:ActiveProcessorCount=2

# No DB (dmz-proxy, edi-converter)
-Xmx256m -Xms128m -XX:ActiveProcessorCount=1
```

---

## 6. Key Findings

### Finding 1: 768M Docker limit is too tight for heavy services
4 services (onboarding, gateway, forwarder, config) are at 99%+ of the 768M limit. Any memory spike = OOM-kill. The 384M heap + Metaspace + ZGC overhead = ~750M baseline. Need 1024M limit.

### Finding 2: Metaspace is the variable cost
Services scanning more entities have higher Metaspace (31M to 104M). Our R37 selective entity scanning directly reduces Metaspace for lightweight services.

### Finding 3: Heap usage is well below max
Most services use 128-188M heap against a 384M max. ZGC capacity expands gradually. The heap max could be reduced to 256M for lightweight services, saving ~128M per service.

### Finding 4: Steady-state CPU is low
After boot, total platform uses ~3.8 cores. Boot is the CPU problem (272+ threads competing), not steady-state. The `ActiveProcessorCount=2` fix addresses boot.

### Finding 5: Thread counts are lean
14-20 threads per service. Virtual threads (JDK 25) handle concurrency without platform thread overhead. No thread pool bloat observed.

### Finding 6: as2-service has no Docker memory limit
It uses 833M with no cgroup constraint. Should have the same limit as other protocol services.

---

## 7. Immediate Actions

| Action | Impact | Risk |
|--------|--------|------|
| Increase Docker limit to 1024M for protocol/central services | Prevents OOM-kill | None |
| Reduce heap to 256M for lightweight services | Saves 128M × 8 = 1GB total | Low — heap usage is 128-142M |
| Add memory limit to as2-service, dmz-proxy | Prevents runaway memory | None |
| Set Kubernetes resource requests/limits per tier | Proper scheduling | None |

---

## 8. Total Platform Memory Budget

### Current (R47)

| Component | Count | Memory Each | Total |
|-----------|-------|-------------|-------|
| Protocol services | 6 | 750M | 4.5 GB |
| Central services | 3 | 765M | 2.3 GB |
| Lightweight DB services | 8 | 650M | 5.2 GB |
| No-DB services | 2 | 300M | 0.6 GB |
| PostgreSQL | 1 | 220M | 0.2 GB |
| Redis | 1 | 11M | 0.01 GB |
| RabbitMQ | 1 | 154M | 0.2 GB |
| Redpanda | 1 | 217M | 0.2 GB |
| **TOTAL** | **23** | | **13.2 GB** |

### Optimized (with tiered heap)

| Component | Count | Memory Each | Total | Savings |
|-----------|-------|-------------|-------|---------|
| Protocol services | 6 | 750M | 4.5 GB | — |
| Central services | 3 | 765M | 2.3 GB | — |
| Lightweight DB services | 8 | **520M** | **4.2 GB** | **1.0 GB** |
| No-DB services | 2 | **280M** | **0.6 GB** | — |
| Infrastructure | 4 | ~150M avg | 0.6 GB | — |
| **TOTAL** | **23** | | **12.2 GB** | **1.0 GB saved** |

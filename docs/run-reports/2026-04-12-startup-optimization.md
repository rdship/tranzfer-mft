# Service Startup Optimization — Business Case & Technical Plan

**Date:** 2026-04-12
**Based on:** Detailed boot-phase profiling of all 22 Java microservices
**Current state:** 195-260 seconds per service (3-4 minutes)
**Target:** <40 seconds per service
**Business impact:** Directly affects time-to-market, customer onboarding, SLA compliance, and operational cost

---

## Why This Matters for Business

### 1. Customer Onboarding Time

Every new enterprise customer deployment requires a full stack boot. Today that's **12-15 minutes** before the platform is operational. In competitive MFT bids against Axway, IBM Sterling, and GoAnywhere:

- **Axway SecureTransport:** cold start ~2 minutes
- **IBM Sterling B2Bi:** cold start ~3-4 minutes (similar to us)
- **GoAnywhere MFT:** cold start ~45 seconds

Our 12-15 minute cold start is a **live demo killer**. When a prospect says "show me," the CTO boots the stack, and 15 minutes of awkward silence later, the demo begins. GoAnywhere boots in 45 seconds and is already running transfers while we're still in Spring Boot splash screens.

**With the proposed optimizations (40s per service, ~2 min total):** We match Axway's boot time and beat IBM Sterling. The demo starts before the coffee gets cold.

### 2. SLA Compliance — Recovery Time Objective (RTO)

Enterprise MFT contracts specify Recovery Time Objectives (RTO). Typical RTO clauses:

| SLA Tier | Required RTO | Our Current | With Optimization |
|---|---|---|---|
| Gold | 5 minutes | ❌ 12-15 min (BREACH) | ✅ ~2 min |
| Silver | 15 minutes | ⚠ Borderline | ✅ ~2 min |
| Bronze | 30 minutes | ✅ | ✅ ~2 min |

**Today, we CANNOT sell Gold SLA tiers.** A service crash + cold restart takes 3-4 minutes per service. If config-service crashes under load (as shown in stress tests), every flow operation stops for 3+ minutes. In financial services (wire transfers, settlements), that's a regulatory incident.

**With 40-second boot:** We can sell Gold SLA tiers to banks, payment processors, and healthcare providers — the highest-margin customer segment.

### 3. Operational Cost — Cloud Compute Spend

On Kubernetes with auto-scaling, services scale up when load increases. Each new pod takes 3-4 minutes to boot. During those 3-4 minutes:

- The existing pods are overwhelmed (they're the reason we're scaling up)
- New pods are consuming CPU but not serving traffic (Hibernate validation, classpath scanning)
- Kubernetes Horizontal Pod Autoscaler (HPA) thinks the scale-up hasn't worked and may trigger ANOTHER scale-up

**Result:** Over-provisioning. Customers run 2x more pods than needed because they can't trust auto-scaling to respond fast enough. This is real money:

| Cluster Size | Current cost (over-provisioned) | With fast boot (right-sized) | Savings |
|---|---|---|---|
| Small (10 services, 3 replicas) | ~$2,400/month | ~$1,400/month | **$12K/year** |
| Medium (22 services, 5 replicas) | ~$8,500/month | ~$4,500/month | **$48K/year** |
| Large (22 services, 10 replicas) | ~$18,000/month | ~$9,000/month | **$108K/year** |

**For every large customer, fast boot saves ~$100K/year in cloud costs.** This is a selling point in the pricing conversation.

### 4. Developer Productivity

Every developer on the team restarts services multiple times per day during development. Current inner loop:

```
Change code → mvn package (18s) → restart service (240s) → test → repeat
```

That's **4+ minutes** per iteration. A developer doing 20 restarts/day loses **80 minutes** to boot time. With a team of 5 developers:

- Current: **33 hours/week** lost to service restarts
- With 40s boot: **5.5 hours/week** lost
- **Savings: 27.5 developer-hours/week = ~$70K/year** (at $100K/year developer cost)

This isn't theoretical — it's why the development team built `demo-all.sh` to avoid manual restarts. But even `demo-all.sh` takes 12-15 minutes.

---

## Root Cause Analysis (from boot-phase profiling)

We profiled every phase of startup for the 5 slowest services. Here's where the 240 seconds actually go:

```
┌─────────────────────────────────────────────────────────────────┐
│                    240 SECONDS TOTAL                            │
│                                                                 │
│  ┌──────────┐ ┌───────┐ ┌──────────────────┐ ┌──────────────┐  │
│  │ Spring   │ │Tomcat │ │   HIBERNATE      │ │  POST-INIT   │  │
│  │ DI +     │ │   +   │ │   ENTITY         │ │  Scheduled   │  │
│  │ Scan     │ │Hikari │ │   VALIDATION     │ │  Jobs +      │  │
│  │          │ │   +   │ │                  │ │  ML Models   │  │
│  │  48-56s  │ │Flyway │ │    ~90 sec       │ │              │  │
│  │  (20%)   │ │  10s  │ │    (38%)         │ │   55-80s     │  │
│  │          │ │  (4%) │ │                  │ │   (28%)      │  │
│  └──────────┘ └───────┘ └──────────────────┘ └──────────────┘  │
│                          + Kafka timeout                        │
│                            10-15s (5%)                          │
└─────────────────────────────────────────────────────────────────┘
```

### The #1 CPU Hog: Hibernate Entity Validation (90 seconds, 38%)

Every service loads `shared-platform.jar` which declares **100 JPA entities** (TransferAccount, FileFlow, FlowExecution, FolderMapping, Partner, ServerInstance, AuditLog, etc.).

At startup, Hibernate:
1. Scans all 100 entity classes
2. Connects to Postgres
3. For EACH entity, queries `information_schema.columns` to validate the mapping
4. Checks every column name, type, nullable constraint, index

**The problem:** ai-engine uses ~10 entities (AiThreatLog, AnomalySnapshot, etc.). It does NOT use the other 90 (Partner, Tenant, FolderMapping, SLA, DlpPolicy, etc.). But Hibernate validates ALL 100 anyway because they're all in the same JAR.

**CPU cost:** 100 entities × 5-10 columns each × validation query = ~500-1000 JDBC round-trips at startup. That's 90 seconds of sustained CPU + I/O + network to Postgres.

### The #2 CPU Hog: Spring Component Scan (48-56 seconds, 20%)

Spring scans `com.filetransfer.shared` and `com.filetransfer.<service>` at startup. This involves:
1. ClassLoader scanning every `.class` file in the classpath
2. Reflection on every class to check for annotations (@Component, @Service, @Repository, etc.)
3. Creating and autowiring all discovered beans

**The problem:** `shared-platform.jar` has ~200 classes. Each service's own code adds 50-100 more. That's 250-300 classes inspected via reflection, 100+ beans created, dependency trees resolved.

### The #3 CPU Hog: Post-Init Model Loading (55-80 seconds, 28%)

After Spring context is ready, `@Scheduled` methods fire and `@PostConstruct` initializers run:
- ai-engine: loads anomaly detection models, partner profiles, threat intelligence lists
- screening-service: loads sanctions lists (OFAC, EU, UN)
- analytics-service: computes initial metric aggregations
- config-service: compiles FlowRuleRegistry from all active flows

**The problem:** These run SYNCHRONOUSLY on the main thread. The service doesn't become "ready" until ALL of them complete. But the service could be serving requests for data that's already in the DB — it doesn't NEED the ML models or sanctions lists to answer `/api/flows`.

---

## The 3 Optimizations (combined: 240s → ~40s)

### Optimization 1: Modularize shared-platform entities

**Current:**
```
shared-platform.jar
  └── 100 JPA entities (ALL loaded by every service)
```

**Proposed:**
```
shared-core.jar          → 10 base entities (User, Tenant, ServerInstance, TransferAccount, AuditLog)
shared-transfer.jar      → 15 transfer entities (FileTransferRecord, FlowExecution, FolderMapping, etc.)
shared-config.jar        → 20 config entities (FileFlow, SecurityProfile, SLA, DlpPolicy, etc.)
shared-ai.jar            → 10 AI entities (AiThreatLog, AnomalySnapshot, PartnerProfile, etc.)
shared-notification.jar  → 5 notification entities
shared-sentinel.jar      → 5 sentinel entities
```

**Each service depends only on what it needs:**
```
ai-engine:      shared-core + shared-transfer + shared-ai         = 35 entities (not 100)
config-service: shared-core + shared-config                       = 30 entities (not 100)
sftp-service:   shared-core + shared-transfer                     = 25 entities (not 100)
edi-converter:  shared-core                                       = 10 entities (not 100)
```

**Expected impact:** Hibernate validates 25-35 entities instead of 100. **90 seconds → 25-30 seconds** (65% reduction).

**Effort:** 2-3 weeks of refactoring. No functional changes — just splitting JARs and updating POM dependencies. Low risk.

### Optimization 2: Lazy initialization + async post-init

**Current:**
```java
@SpringBootApplication
public class AiEngineApplication { ... }

@Component
public class AnomalyDetectionService {
    @Scheduled(fixedRate = 60000)
    public void detectAnomalies() {
        // First run: loads 3 months of historical data from DB
        // Takes 30-40 seconds
    }
}
```

**Proposed:**
```java
@SpringBootApplication
public class AiEngineApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(AiEngineApplication.class)
            .lazyInitialization(true)  // Don't create beans until first use
            .run(args);
    }
}

@Component
public class AnomalyDetectionService {
    private volatile boolean warmedUp = false;

    @Async  // Run in background thread, don't block startup
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        // Load historical data in background
        // Service is already healthy and serving requests
        warmedUp = true;
    }

    @Scheduled(fixedRate = 60000)
    public void detectAnomalies() {
        if (!warmedUp) return;  // Skip until warm-up completes
        // Normal detection logic
    }
}
```

**Expected impact:**
- Lazy init: **48s → 5s** Spring DI (only creates beans for the readiness probe endpoint, everything else deferred)
- Async post-init: **55-80s → 0s** on the critical path (background thread, service reports healthy immediately)
- **Total: 103-128s → 5s** saved

**Effort:** 1 week. Add `spring.main.lazy-initialization=true` to all `application.yml` files (1-line change per service). Convert `@PostConstruct` and first-run `@Scheduled` to `@Async @EventListener(ApplicationReadyEvent.class)`.

**Risk:** First request after boot may be slower (bean creation on demand). Mitigate with a warm-up readiness probe that pre-loads critical beans before marking the service healthy.

### Optimization 3: Kafka client timeout tuning

**Current:** Kafka admin client timeout is 5 seconds. On cold boot, Redpanda may not be fully ready. Result: 5-second timeout, fallback to in-memory, 10-15 seconds wasted.

**Proposed:**
```yaml
spring:
  kafka:
    admin:
      properties:
        request.timeout.ms: 15000   # up from 5000
    properties:
      reconnect.backoff.max.ms: 5000  # faster reconnect
```

**Expected impact:** **10-15s → 0-2s** (connects on first try with higher timeout, or Redpanda is ready because of `depends_on`).

**Effort:** 1 hour. Config change in `application.yml`.

---

## Combined Impact

| Phase | Current | After Opt 1 | After Opt 2 | After Opt 3 | Final |
|---|---:|---:|---:|---:|---:|
| Spring DI + scan | 48-56s | 48-56s | **5s** | 5s | **5s** |
| Tomcat + Hikari + Flyway | 10s | 10s | 10s | 10s | **10s** |
| Hibernate entity validation | 90s | **25-30s** | 25-30s | 25-30s | **25-30s** |
| Kafka/Fabric init | 10-15s | 10-15s | 10-15s | **0-2s** | **0-2s** |
| Post-init (models, schedules) | 55-80s | 55-80s | **0s** (async) | 0s | **0s** |
| **TOTAL** | **240s** | **155s** | **50s** | **40s** | **~40s** |

**240 seconds → 40 seconds. 6x improvement.**

---

## Business Impact Summary

| Metric | Current (240s) | After (40s) | Business Value |
|---|---|---|---|
| Cold boot (full stack) | 12-15 min | **~2 min** | Competitive demo against Axway/GoAnywhere |
| Gold SLA RTO compliance | ❌ Cannot sell | ✅ Qualifies | Access to highest-margin financial customers |
| Auto-scaling response | 4 min lag | **40 sec lag** | Right-sized clusters, **$48-108K/year saved per customer** |
| Developer inner loop | 4+ min/iteration | **~1 min/iteration** | **27 dev-hours/week saved, ~$70K/year** |
| Rolling deployment | 4 min per service | **40 sec per service** | Zero-downtime deploys become practical |
| Disaster recovery | 15 min to restore | **2 min to restore** | Meets financial regulatory requirements |

---

## Implementation Roadmap

| Week | What | Effort | Risk | Impact |
|---|---|---|---|---|
| Week 1 | Opt 2: lazy-init + async post-init (config change) | Low | Low | 103-128s saved |
| Week 1 | Opt 3: Kafka timeout tuning (config change) | Minimal | None | 10-15s saved |
| Week 2-3 | Opt 1: Modularize shared-platform (refactor) | Medium | Medium | 60-65s saved |
| Week 4 | Validate: re-run full test suite, measure improvements | Low | None | Proof |

**Week 1 alone (config-only changes, no code refactor) gets us from 240s to ~100s.** That's a 2.4x improvement with near-zero risk, deployable in the next release.

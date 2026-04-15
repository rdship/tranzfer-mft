# TranzFer MFT — Expert Architect Review & Strategic Direction

**Reviewer:** Senior Distributed Systems Architect (Peer Review)  
**Date:** 2026-04-15  
**Build Reviewed:** R47  
**Context:** 22-microservice MFT platform, Spring Boot 3.2.3, JDK 25, PostgreSQL 16, RabbitMQ, Redis, Redpanda (Kafka), Docker Compose today, Kubernetes target

---

## 1. Memory Architecture Assessment

### 1.1 The 384M-Everywhere Heap Problem

The measured data tells a clear story. Every service runs `-Xmx384m` but actual heap usage ranges from 128M (screening-service) to 240M (ai-engine). Four services sit at 99%+ of the 768M Docker limit and are one allocation spike from OOM-kill. Meanwhile, edi-converter uses 352M total with a 384M heap it will never fill.

**Verdict: 384M for all services is wrong.** The data supports three tiers:

| Tier | Services | Recommended Heap | Rationale |
|------|----------|-----------------|-----------|
| Heavy | onboarding-api, gateway-service, forwarder-service, config-service, ai-engine, as2-service | 384M | Heap usage 154-240M, scan all 63 entities, Metaspace 49-104M |
| Medium | sftp-service, ftp-service, ftp-web-service, notification-service, platform-sentinel | 256M | Heap usage 128-146M, moderate entity scan |
| Light | encryption-service, analytics-service, screening-service, storage-manager, license-service, keystore-manager | 192M | Heap usage 128-142M, core-only entity scan, Metaspace 31-66M |
| No-DB | dmz-proxy, edi-converter | 128M | No JPA, no Hibernate, no entity scanning |

This saves approximately 1.2-1.5 GB across the platform compared to the current uniform allocation.

### 1.2 ZGC at 384M: The Wrong Collector

ZGC Generational is currently set in Docker Compose. This is architecturally questionable for these heap sizes.

**The problem:** ZGC's page table structures, forwarding tables, and GC metadata consume 50-100M of overhead per JVM. ZGC was designed for heaps of 4GB+ where its sub-millisecond pause times justify the overhead. At 384M, you are paying a 15-25% memory tax for pause time improvements that are invisible at this heap size — G1GC pauses at 384M are already single-digit milliseconds.

**The K8s manifests already know this.** The k8s/configmap.yaml uses `-XX:+UseG1GC -XX:MaxGCPauseMillis=100`, while Docker Compose uses ZGC. This inconsistency will cause different memory footprints between dev and prod.

**Recommendation:** Standardize on G1GC for all services at these heap sizes. Estimated savings: 50-100M per JVM × 17 DB services = 850M-1.7GB.

### 1.3 Metaspace Limits

No `-XX:MaxMetaspaceSize` is set anywhere. Measured Metaspace ranges from 31M (screening-service) to 104M (forwarder-service). Without a cap, a classloader leak will consume unbounded native memory.

**Recommendation:**
- Heavy services: `-XX:MaxMetaspaceSize=150M`
- Light services: `-XX:MaxMetaspaceSize=100M`
- No-DB services: `-XX:MaxMetaspaceSize=64M`

---

## 2. Boot Time Architecture

### 2.1 Current State: 131-182 Seconds is Not Production-Ready

For Kubernetes:
- **Rolling updates with maxUnavailable=0**: a 3-minute boot means a deployment of 2 replicas takes 6+ minutes
- **HPA pod autoscaling**: a traffic spike requiring new pods takes 3 minutes before a pod can serve traffic
- **Node failure recovery**: replacement pods take 3 minutes each

**Realistic production target:** 30-45 seconds.

### 2.2 Root Cause: 63 Entities × 61 Repositories Scanned by Every Service

Phase 1 (Hibernate entity binding) + Phase 3 (JPA repo proxies) together consume 100-140 seconds, and both are proportional to the number of entities/repositories scanned.

### 2.3 Spring Boot 3.2.3 → 3.4 Upgrade: What It Unlocks

| Feature | Version | Impact |
|---------|---------|--------|
| `jarmode=tools` extract | 3.3+ | N29 blocked on this |
| Micrometer deadlock fix | 3.3+ | N28 caused by this |
| CDS (Class Data Sharing) | 3.3+ | 10-15% boot time reduction |
| Virtual thread auto-config | 3.4+ | Simplifies virtual thread setup |
| Structured logging | 3.4+ | Addresses observability gap |
| Improved AOT | 3.3+ | Faster context initialization |

**Verdict:** Spring Boot upgrade is P0. Two critical bugs are caused by being on 3.2.3.

### 2.4 GraalVM Native Images

Would cut boot to 2-5 seconds but requires Spring Boot 3.4+, extensive reflection hints, and testing. **6-12 month strategic investment, not a near-term fix.**

### 2.5 Combining Lightweight Services

Eight lightweight services each consume 600-700M for very low CPU (0.6-1.8%). Combining into a single "platform-core" JVM would save 7 JVMs × ~300M overhead = 2.1 GB memory. **Valid P2 optimization.** These services are architecturally more like modules than services.

---

## 3. Shared Library Architecture

### 3.1 shared-platform Is the Root Cause of Most Problems

The issues tracker tells the story: N11, N21, N26, N27, N28, N30 — all trace back to shared-platform loading 63 entities + 61 repos + 66+ beans into every service.

### 3.2 Recommended Module Split (P1)

- `shared-entities-core` — ~20 core entities most services need
- `shared-entities-domain` — ~43 domain entities only specific services need
- `shared-services` — 66+ beans as a separate dependency

### 3.3 Long-Term: Database-per-Bounded-Context

Use PostgreSQL schemas (not separate instances) to isolate:
- Protocol context (transfer tables)
- Security context (security tables)
- Integration context (integration tables)
- Core context (core tables)

---

## 4. Infrastructure Architecture

### 4.1 Single PostgreSQL: Connection Pool Math

With 2 replicas per service in K8s: **342 connections** against 400 max = 85% capacity with zero headroom.

**Recommendation:** PgBouncer in transaction pooling mode for K8s production.

### 4.2 RabbitMQ AND Kafka

Both are architecturally justified (different messaging patterns). Long-term consolidation to Kafka-only is P3.

---

## 5. Deployment Architecture

### 5.1 K8s Configmap Gap

K8s configmap.yaml is missing critical flags present in Docker Compose: Hibernate fast-boot, Micrometer deadlock fix, JPA lazy bootstrap, ActiveProcessorCount. **First K8s deployment will hit every boot bug already fixed in Docker.**

### 5.2 Health Check Probes

Startup probe window (150s) is below measured boot time (179s). Pods will restart-loop. Increase `failureThreshold` from 30 to 60 until boot time is reduced.

---

## 6. Security Architecture

### 6.1 SPIFFE/SPIRE: Production-Ready with Caveats
- Static join tokens must be replaced with node attestation for K8s
- Need SVID rotation monitoring/alerting

### 6.2 JWT: Critical Gaps
- No refresh tokens (15-min expiry in K8s, 8-hour band-aid in Docker)
- No token revocation mechanism
- Single JWT secret across all services
- **P0: Implement refresh token rotation**

### 6.3 Rate Limiting: Must Replace
- ConcurrentHashMap is per-JVM — bypassed by multi-replica K8s
- **P0: Redis-backed sliding window rate limiter**

---

## 7. Observability Gaps

| Gap | Impact | Fix |
|-----|--------|-----|
| No distributed tracing | Can't trace file transfer across 5+ services | Add OpenTelemetry Java agent (P1) |
| JPA metrics disabled | No query latency visibility | Upgrade to Spring Boot 3.3+ (P0) |
| No structured logging correlation | Can't correlate logs across services | Add MDC trackId propagation (P1) |

---

## 8. Prioritized Roadmap

### P0: Must Fix Before Production

1. **Spring Boot 3.2.3 → 3.4 upgrade** (fixes N28 Micrometer deadlock, enables CDS, unblocks N29)
2. **Sync K8s configmap with Docker Compose flags** (prevents known deadlocks in K8s)
3. **Fix K8s memory requests** (384Mi → 768Mi for heavy services)
4. **Implement JWT refresh tokens** (15-min expiry with no refresh = broken UX)
5. **Replace in-memory rate limiter with Redis** (N9, bypassed in multi-replica)
6. **Fix K8s startup probe window** (150s < 179s boot time = restart loop)

### P1: Within 30 Days

1. Switch ZGC → G1GC (saves 850M-1.7GB)
2. Add OpenTelemetry distributed tracing
3. Split shared-platform entities into core vs domain modules
4. Deploy PgBouncer for K8s
5. Tier heap sizes per service classification
6. Add MDC trace correlation across services

### P2: 30-90 Days

1. Evaluate combining 8 lightweight services into platform-core
2. Enable CDS after Spring Boot 3.4
3. Database-per-bounded-context via PostgreSQL schemas
4. Complete Helm chart
5. JWT secrets from Vault (not env vars)

### P3: 90+ Days

1. Consolidate RabbitMQ into Kafka
2. GraalVM native images for lightweight services
3. Event sourcing for transfer records
4. Multi-tenancy with database isolation
5. SPIRE Federation for cross-cluster trust

---

## Summary

The platform is architecturally ambitious and largely well-structured. The three systemic issues are:

1. **The shared-platform monolith inside microservices.** 63 entities and 66+ beans loaded by every service negates the isolation benefit of separate JVMs. Root cause of boot time, memory usage, and most N-series bugs.

2. **Spring Boot 3.2.3 is holding the platform back.** Two critical bugs are fixed in later versions. CDS, structured logging, and improved AOT are unavailable.

3. **Dev/prod configuration divergence.** Docker Compose has 19 JAVA_TOOL_OPTIONS flags not fully replicated in K8s. The first K8s deployment will hit a cascade of bugs already fixed in Docker.

None of these are architectural mistakes — they are engineering debt from building fast. The platform is at the inflection point where a 2-week investment in the P0 items will determine whether K8s deployment succeeds or fails.

# TranzFer MFT — Customer Demo Readiness Report

**Date:** 2026-04-13  
**Context:** Pre-demo validation for potential licensing customer  
**Status:** NOT READY — critical blockers identified  

---

## Critical Blockers for Customer Demo

### BLOCKER 1: Auth Rate Limiter Locks Out ALL API Access (SEVERITY: SHOWSTOPPER)

**What happens:** After the Playwright test suite runs ~200+ login attempts, the IP-based rate limiter on `/api/auth/login` returns 429 `TOO_MANY_REQUESTS`. Once locked, ALL API calls fail with 403 because no new JWT tokens can be obtained.

**Why it persists:**
- Rate limiter is in-memory in `AuthController` / `BruteForceProtection`
- NOT stored in Redis (Redis flush doesn't clear it)
- `docker restart` does NOT reliably clear it — the container recycles too fast, JVM state may persist in Docker's memory-mapped volumes
- Only a full `docker stop + docker rm + docker compose up` clears it

**Impact for demo:** If a customer explores the login page or if any automated health check hits the login endpoint repeatedly, the entire platform becomes inaccessible. No way to recover without downtime.

**Permanent fix needed:**
1. Rate limiter should use **Redis** (not in-memory) so it can be flushed and shared across instances
2. Add admin endpoint `POST /api/admin/rate-limit/reset` to clear the limiter without restart
3. Rate limit should be per-user (email), not per-IP — a proxy/load balancer means ALL users share one IP
4. Default limit of 20 requests/minute is too low for a multi-user environment
5. Rate limiter window should auto-decay (sliding window, not fixed block)
6. The Playwright test suite and any CI/CD pipeline need a service account that bypasses rate limiting

### BLOCKER 2: File Upload → Routing Pipeline Completely Disconnected (SEVERITY: SHOWSTOPPER)

**What happens:** Files uploaded via SFTP land on the server's filesystem but never trigger the routing engine. Activity Monitor shows 0 records even after uploading files. The entire file transfer use case does not work.

**Root cause chain:**
```
1. docker-compose sets JAVA_TOOL_OPTIONS with -Dspring.main.lazy-initialization=true
2. This forces ALL Spring beans to be lazy-initialized
3. FileUploadQueueConfig (declares RabbitMQ queues) is never loaded
4. FileUploadEventConsumer (listens for upload events) is never loaded
5. FlowRuleEventListener (listens for rule changes via RabbitMQ) is never loaded
6. RabbitMQ has exchanges but ZERO queues and ZERO consumers
7. SFTP upload completion event has no listener
8. RoutingEngine.onFileUploaded() is never called
9. No FlowExecution created, no FileTransferRecord created, no activity
```

**CTO's @Lazy(false) fix didn't work because:**
The `@Lazy(false)` annotation on individual beans is overridden by the global `-Dspring.main.lazy-initialization=true` JVM flag in `JAVA_TOOL_OPTIONS`. Spring's global lazy-init flag takes precedence over per-bean annotations.

**Permanent fix needed:**
1. Remove `-Dspring.main.lazy-initialization=true` from JAVA_TOOL_OPTIONS entirely
2. Instead, use `@Lazy` on specific heavy beans that benefit from lazy loading (Hibernate, Kafka admin)
3. OR use Spring Boot's `spring.main.lazy-initialization-excludes` property (Spring Boot 3.2+):
```yaml
spring:
  main:
    lazy-initialization: true
    lazy-initialization-excludes:
      - com.filetransfer.shared.routing.FileUploadQueueConfig
      - com.filetransfer.shared.routing.FileUploadEventConsumer
      - com.filetransfer.shared.routing.StepPipelineConfig
      - com.filetransfer.shared.matching.FlowRuleEventListener
      - com.filetransfer.shared.matching.FlowRuleRegistryInitializer
      - com.filetransfer.shared.cache.PartnerCache
      - com.filetransfer.shared.cache.PartnerCacheEvictionListener
      - org.springframework.amqp.rabbit.**
```
4. Verify fix by checking `docker exec mft-rabbitmq rabbitmqctl list_queues` shows `file.upload.events` queue with consumers

### BLOCKER 3: RabbitMQ Exchange Not Auto-Declared (SEVERITY: HIGH)

**What happens:** The `file-transfer.events` topic exchange does not exist after fresh deployment. Services that try to publish to it get `NOT_FOUND - no exchange 'file-transfer.events'` and give up after ~10 retries.

**Why:** No service declares the exchange eagerly. `FileUploadQueueConfig.fileUploadBinding()` creates it implicitly via `TopicExchange(exchange)`, but this bean is never loaded (see Blocker 2).

**Permanent fix needed:**
1. Add exchange declaration to RabbitMQ container startup via `definitions.json`:
```json
{
  "exchanges": [
    { "name": "file-transfer.events", "type": "topic", "durable": true },
    { "name": "file-transfer.events.dlx", "type": "topic", "durable": true }
  ]
}
```
2. Mount in docker-compose:
```yaml
rabbitmq:
  volumes:
    - ./config/rabbitmq/definitions.json:/etc/rabbitmq/definitions.json
  environment:
    RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS: -rabbitmq_management load_definitions "/etc/rabbitmq/definitions.json"
```

### BLOCKER 4: Redis @Cacheable Serialization Bug (SEVERITY: HIGH)

**What happens:** `FlowExecutionController.getLiveStats()` uses `@Cacheable` which stores `ResponseEntity<Map>` in Redis. On the next read, Jackson fails to deserialize `ResponseEntity` because it has no default constructor. Dashboard shows error.

**Workaround:** `FLUSHALL` on Redis clears the poisoned cache. But it re-poisons on the very next call.

**Permanent fix needed:**
```java
// Cache the DATA, not the HTTP wrapper
@Cacheable(value = "live-stats", key = "'all'")
public Map<String, Object> computeLiveStats() {
    // ... query logic ...
    return Map.of("processing", ..., "pending", ..., "paused", ..., "failed", ...);
}

@GetMapping("/live-stats")
public ResponseEntity<Map<String, Object>> getLiveStats() {
    return ResponseEntity.ok(computeLiveStats());
}
```

### BLOCKER 5: CORS Not Configured for HTTPS (SEVERITY: HIGH)

**What happens:** CTO switched platform to HTTPS-only, but `cors.allowed-origins` defaults to `http://localhost:3000,http://localhost:3001,http://localhost:3002`. Browser sends `Origin: https://localhost` → Spring Security rejects with 403.

**Our workaround:** nginx strips Origin header before proxying. This works but is a band-aid.

**Permanent fix needed:**
1. Add CORS config to **shared-platform** `SharedSecurityConfig` so ALL services get it:
```java
config.setAllowedOriginPatterns(List.of(
    "http://localhost*", "https://localhost*",
    "http://*.filetransfer.io", "https://*.filetransfer.io"
));
```
2. Or use `allowedOriginPatterns("*")` in dev mode (controlled by profile)
3. Each service currently has its own SecurityConfig with different CORS — consolidate to one shared config

---

## UI Issues Found

### 3 Pages Crash (Gateway, DMZ Proxy, Cluster)
- **Error:** `Cannot access 'It' before initialization`
- **Root cause:** Vite production build circular dependency in these page components
- **Fix:** Run `npx vite build --mode development` to find the real import cycle. Likely in `api/gateway.js` ↔ `api/dmz.js` ↔ component imports.

### Flows Modal Doesn't Close on Escape
- The Quick Flow / Create Flow modal ignores Escape key
- All other modals (Partners, Accounts, Users) close correctly
- **Fix:** Check if a child component calls `event.stopPropagation()` on keydown, or if the Modal component's onClose isn't wired to the Flows modal

### Missing Backend Endpoints (UI Shows Error Toast)
| UI Page | Missing Endpoint | Error |
|---------|-----------------|-------|
| Compliance | `/api/compliance` | 500 — `No static resource api/compliance` |
| License | `/api/v1/licenses/status` | 500 |
| Gateway | `/api/gateway/status` | 500 |
| DMZ Proxy | `/api/dmz/status` | 404 |

### Activity Monitor Empty
- Shows 0 records because file pipeline is disconnected (Blocker 2)
- Once pipeline is fixed, uploads will appear here

---

## What Works Well (Demo-Safe Features)

| Feature | Status | Notes |
|---------|--------|-------|
| Login (HTTPS) | ✓ | Works if rate limiter not tripped |
| Dashboard | ✓ | Shows KPIs, service health (after Redis flush) |
| Partner Management | ✓ | Full CRUD, lifecycle, search, detail view |
| Account Management | ✓ | CRUD, all protocols, QoS settings |
| User Management | ✓ | CRUD, all roles, password reset |
| File Flow Configuration | ✓ | All step types, templates, priority |
| Folder Mappings | ✓ | CRUD with pattern matching |
| Server Instances | ✓ | New ServerConfig format working |
| Security Profiles | ✓ | CRUD working |
| Screening / DLP | ✓ | Policy management working |
| Keystore | ✓ | Key listing working |
| Sentinel | ✓ | Health score, findings, rules |
| Analytics | ✓ | Dashboard, predictions |
| AS2 Partnerships | ✓ | CRUD (AES only, 3DES blocked) |
| Scheduler | ✓ | Task management |
| SLA | ✓ | Rule management |
| Notifications | ✓ | Rules and templates |
| Connectors | ✓ | Webhook management |
| Audit Logs | ✓ | Immutable log viewing |
| Partner Portal | ✓ | Login page accessible |
| File Portal | ✓ | Login page accessible |
| 57/60 UI pages | ✓ | Load without crash |
| API Performance | ✓ | <35ms latency, 312 req/sec concurrent |

---

## Service Boot Times (Need Improvement)

### Fast (CTO's optimization applied)
| Service | Boot Time |
|---------|-----------|
| analytics-service | 20.4s |
| config-service | 20.9s |
| license-service | 21.8s |
| onboarding-api | 22.1s |

### Slow (Optimization NOT yet effective)
| Service | Boot Time |
|---------|-----------|
| screening-service | 170.2s |
| forwarder-service | 172.6s |
| as2-service | 172.8s |
| storage-manager | 172.3s |
| keystore-manager | 170.0s |
| ftp-web-service | 177.4s |
| notification-service | 180.2s |
| encryption-service | 180.6s |
| storage-manager | 184.3s |
| platform-sentinel | 184.6s |
| ai-engine | 186.7s |

**Total platform cold start: ~3.5 minutes** (limited by slowest service)

**Fix:** The JAVA_TOOL_OPTIONS in docker-compose overrides the application.yml Hibernate settings. The fast-boot flags need to be in JAVA_TOOL_OPTIONS:
```
-Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false
-Dspring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false
```
These are already there but may be using wrong property key format (dot vs underscore).

---

## Recommendations for Customer Demo

### Before Demo (Required)
1. **Fix file pipeline** — without this, cannot demonstrate file transfer (core product)
2. **Fix Redis cache serialization** — dashboard errors look unprofessional
3. **Fix CORS properly** — our nginx workaround works but is fragile
4. **Increase auth rate limit** to at least 100/min or disable for demo
5. **Pre-seed demo data** — run updated demo-onboard.sh for realistic content

### During Demo (Avoid)
1. Do NOT click Gateway, DMZ Proxy, or Cluster pages (they crash)
2. Do NOT navigate away from Activity Monitor (it's empty)
3. Do NOT repeatedly try login if it fails (triggers rate limit)
4. Do NOT show Compliance, License, or Gateway Status pages (500 errors)

### Safe Demo Path
```
Login → Dashboard → Partners (create one) → Accounts (create SFTP) →
Flows (create with steps) → Sentinel (health score) → Analytics →
Screening (DLP policies) → Keystore → Notifications → Audit Logs →
AS2 Partnerships → Scheduler → SLA Rules
```

This path covers 14 features, all working, without hitting any broken page.

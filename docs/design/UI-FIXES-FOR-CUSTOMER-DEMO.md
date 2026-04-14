# TranzFer MFT — UI Fixes Required for Customer Demo

**Date:** 2026-04-14  
**Author:** QA Team  
**Audience:** CTO Roshan Dubey, Frontend Dev  
**Priority:** P0 — customer demo blocked  

---

## Fix 1: Service Health Checks Use Wrong URLs (Every Service Shows "OFFLINE")

**File:** `ui-service/src/context/ServiceContext.jsx` lines 18-44

**Problem:** All 20 health check URLs are hardcoded to `http://localhost:PORT` — direct service ports that only work in dev mode. In Docker, the browser cannot reach these internal ports. Every service shows "OFFLINE" on the Dashboard and on individual service pages (e.g., Sentinel shows "OFFLINE" badge despite Platform Health score of 100).

**Current (broken):**
```javascript
const SERVICE_HEALTH_ENDPOINTS = {
  onboarding: { url: 'http://localhost:8080/actuator/health', port: 8080 },
  config:     { url: 'http://localhost:8084/actuator/health', port: 8084 },
  sftp:       { url: 'http://localhost:8081/actuator/health', port: 8081 },
  gateway:    { url: 'http://localhost:8085/actuator/health', port: 8085 },
  sentinel:   { url: 'http://localhost:8098/api/v1/sentinel/health', port: 8098 },
  // ... 15 more hardcoded localhost:PORT URLs
}
```

**Fix:**
```javascript
const SERVICE_HEALTH_ENDPOINTS = {
  onboarding: { url: '/actuator/health' },           // gateway routes to onboarding:8080
  config:     { url: '/api/servers/health' },          // gateway routes to config:8084
  sftp:       { url: '/api/sftp/health' },             // add route in nginx
  gateway:    { url: '/api/gateway/health' },          // gateway routes to gateway:8085
  sentinel:   { url: '/api/v1/sentinel/health' },      // already routed
  // ... all relative paths through the gateway
}
```

And use the authenticated API client (which already has the base URL and Bearer token) instead of raw fetch:
```javascript
// BEFORE: raw fetch to localhost:PORT (broken in Docker)
const resp = await fetch(endpoint.url)

// AFTER: use the existing axios instance through gateway
const resp = await onboardingApi.get(endpoint.url)
```

For services that don't have a dedicated health endpoint through the gateway, add nginx routes:
```nginx
location /api/sftp/health       { set $up sftp-service:8081;       proxy_pass http://$up/actuator/health; }
location /api/ftp/health        { set $up ftp-service:8082;        proxy_pass http://$up/actuator/health; }
location /api/encryption/health { set $up encryption-service:8086; proxy_pass http://$up/actuator/health; }
# ... etc for all services
```

**Impact:** Every service badge on Dashboard + every service page header shows correct ONLINE/OFFLINE status.

**Effort:** 30 minutes — change 20 URLs + add nginx routes + use axios client.

---

## Fix 2: Activity Monitor Must Look Professional at Any Record Count

**Problem:** Activity Monitor shows an empty table when there are 0 records. Previously it crashed on empty data (fixed with safeLazy). But even without crashing, an empty table looks broken to a customer. The Activity Monitor is THE core feature — it must look intentional and polished whether there are 0 or 10 million records.

**Design for Zero Records:**
```
┌──────────────────────────────────────────────────────────────┐
│  Activity Monitor                              [Auto-refresh]│
├──────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌─── Stats Strip ──────────────────────────────────────┐    │
│  │ 📊 0          ✅ --       ❌ 0       ⏱ --     🔒 --  │    │
│  │ Total        Success    Failed    Avg Time  Integrity│    │
│  └──────────────────────────────────────────────────────┘    │
│                                                               │
│           ┌─────────────────────────────┐                    │
│           │                             │                    │
│           │     📁  No transfers yet    │                    │
│           │                             │                    │
│           │  Upload a file via SFTP     │                    │
│           │  to see activity here.      │                    │
│           │                             │                    │
│           │  ┌─────────────────────┐    │                    │
│           │  │  Quick Start Guide  │    │                    │
│           │  └─────────────────────┘    │                    │
│           │                             │                    │
│           └─────────────────────────────┘                    │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

**Key requirements:**
1. **Never crash** on empty data — already fixed with safeLazy
2. **Never show an empty table** — show purposeful empty state card instead
3. **Stats strip shows dashes, not zeros** — `--` for rates and averages, `0` for counts
4. **Include a call to action** — "Upload a file via SFTP to see activity here" with link to Quick Start
5. **Filters and controls still visible** — user should see the full filter bar even with 0 records, so they know the feature exists
6. **Auto-refresh still works** — when the first file comes in, it appears automatically without page refresh
7. **Fabric KPI strip still shows** — "In Fabric: 0, Healthy Pods: N, Stuck: 0" tells the operator the system is healthy and waiting

**Design for 1-10 Records (Early Adoption):**
- Show the table normally
- Stats strip shows real numbers
- No empty state card
- A subtle banner: "Showing all N transfers. Set up more flows to process files automatically." with link to Flows page

**Design for 1000+ Records (Production):**
- Full table with all features (sort, filter, paginate, export, saved views)
- Stats strip with live KPIs
- Fabric context strip with real metrics
- No banners — the product speaks for itself

---

## Fix 3: File Upload → Routing Pipeline Disconnected (Activity Monitor Will Always Be Empty Until Fixed)

**Problem:** Even after Fix 2 makes the UI look clean, the Activity Monitor will show 0 records forever because the file upload pipeline is broken.

**Root cause:** `JAVA_TOOL_OPTIONS` in docker-compose.yml contains `-Dspring.main.lazy-initialization=true`. This prevents `FileUploadQueueConfig`, `FileUploadEventConsumer`, and `FlowRuleEventListener` beans from loading. RabbitMQ queues are never declared. SFTP upload events have no listener. Files land on disk but never trigger routing.

**Fix:** Remove `-Dspring.main.lazy-initialization=true` from JAVA_TOOL_OPTIONS in docker-compose.yml.

**Why this is safe:** The lazy-init flag was added for boot speed but causes more problems than it solves. Boot speed should come from entity scan filtering (the modularization CTO already started), not from lazy-init which breaks listeners, schedulers, and queue declarers.

**Verification after fix:**
```bash
# Upload a file
sshpass -p 'password' sftp -P 2222 user@localhost <<< "put test.csv inbox/test.csv"

# Check RabbitMQ has consumers
docker exec mft-rabbitmq rabbitmqctl list_queues name consumers

# Check Activity Monitor
curl http://localhost/api/activity-monitor?page=0&size=1
# Should show totalElements > 0
```

---

## Fix 4: Redis @Cacheable Poisons live-stats Endpoint

**Problem:** `FlowExecutionController.getLiveStats()` caches `ResponseEntity<Map>` in Redis. `ResponseEntity` cannot be deserialized (no default constructor). Dashboard shows "Couldn't load data" error toast every 5 seconds.

**Current workaround:** `redis-cli FLUSHALL` after every restart. Re-poisons within seconds of first call.

**Fix:**
```java
@Cacheable(value = "live-stats", key = "'all'")
public Map<String, Object> computeLiveStats() { ... }

@GetMapping("/live-stats")
public ResponseEntity<Map<String, Object>> getLiveStats() {
    return ResponseEntity.ok(computeLiveStats());
}
```

---

## Summary: 4 Fixes for Customer-Ready Demo

| # | Fix | Effort | Impact |
|---|-----|--------|--------|
| 1 | Health check URLs through gateway | 30 min | All services show ONLINE |
| 2 | Activity Monitor empty state design | 1 hour | Professional look at 0 records |
| 3 | Remove lazy-init from JAVA_TOOL_OPTIONS | 1 min | File pipeline works, Activity Monitor fills |
| 4 | Cache Map not ResponseEntity | 5 min | Dashboard stops showing error toasts |

**Total: ~2 hours of dev work to make the platform customer-demo ready.**

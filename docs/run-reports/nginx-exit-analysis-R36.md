# TranzFer MFT — Nginx Service Exit Analysis (R36)

**Date:** 2026-04-15
**Severity:** CRITICAL — platform loses ALL HTTP access

---

## What Happened

4 nginx-based containers exited within 37 seconds of each other, killing all HTTP access:

| Service | Exit Time | Signal | Source PID |
|---------|-----------|--------|-----------|
| api-gateway | 14:40:00 | SIGQUIT (3) from PID 395 | Internal |
| ftp-web-ui | 14:40:19 | SIGQUIT (3) from PID 398 | Internal |
| partner-portal | 14:40:29 | SIGQUIT (3) from PID 412 | Internal |
| ui-service | 14:40:37 | SIGQUIT (3) from PID 402 | Internal |

All 4 received SIGQUIT — the graceful shutdown signal for nginx (`nginx -s quit`).

## Root Cause Chain

### 1. Memory Pressure
```
14:31:52 RabbitMQ alarm: system_memory_high_watermark
```
RabbitMQ detected system memory exceeding the high watermark threshold 8 minutes before the exits. This means Docker Desktop's VM was running out of memory.

**Memory math:**
- 22 Java services × 384MB heap = 8.4GB (just JVM heap, actual RSS is ~1.5x = 12.6GB)
- PostgreSQL: ~256MB
- Redis: ~100MB
- RabbitMQ: ~256MB
- Redpanda (Kafka): ~512MB
- SPIRE, Vault, Grafana, Loki, Prometheus, Promtail, Alertmanager: ~1GB
- 4 nginx containers: ~50MB total
- **Total estimated: ~14.5GB**

Docker Desktop default memory: 8GB. Even if increased to 16GB, 14.5GB leaves no headroom.

### 2. Docker Kills Lightest Containers Under Memory Pressure
When the Docker VM runs out of memory, the Linux OOM killer or Docker's own resource management signals containers to shut down. Nginx containers (~12MB each) are the lightest — they get killed first.

The SIGQUIT (not SIGTERM, not SIGKILL) suggests Docker sent a graceful shutdown signal. The PIDs (395, 398, 402, 412) are the `curl` processes from the healthcheck — when Docker decides to stop a container, it sends the signal through the healthcheck process.

### 3. No Restart Policy
All 4 nginx containers have `restart: no` (default). Once Docker stops them, they stay dead. Java services have `restart: unless-stopped` so they restart after being killed.

## Evidence

### api-gateway log (last lines before exit):
```
14:39:57 GET /api/compliance/violations/count HTTP/1.1 403  ← normal traffic
14:39:58 GET /api/v1/analytics/dashboard HTTP/1.1 403       ← normal traffic
14:40:00 POST /reader-update HTTP/1.1 405                   ← Promtail (normal)
14:40:00 signal 3 (SIGQUIT) received from 395, shutting down ← KILLED
14:40:00 gracefully shutting down (all 9 workers)
14:40:00 exit
```

### RabbitMQ memory alarm:
```
14:31:52 alarm_handler: {set,{system_memory_high_watermark,[]}}
```

### Container inspect (all 4 identical pattern):
```
OOMKilled: false           ← Docker didn't OOM-kill directly
ExitCode: 0                ← Graceful shutdown (SIGQUIT)
RestartPolicy: no          ← No auto-restart
StartPeriod: 15s           ← Short healthcheck window
```

### Healthcheck configs:
```
api-gateway:    curl -skf https://localhost:8443/nginx-health  start_period=15s retries=3
ui-service:     curl -sf http://localhost:8080/nginx-health    start_period=15s retries=3
partner-portal: curl -sf http://localhost:8080/nginx-health    start_period=15s retries=3
ftp-web-ui:     curl -sf http://localhost:8080/nginx-health    start_period=15s retries=3
```

## Why This Matters

When these 4 containers exit:
- **ALL HTTP API calls fail** — no gateway
- **Admin UI unavailable** — no ui-service
- **Partner portal dead** — no partner-portal
- **File portal dead** — no ftp-web-ui
- **Platform appears completely down** despite 30 healthy Java services

## Fixes Required

### 1. Reduce Memory Footprint
- Lower Java heap from 384MB to 256MB for lightweight services (license, keystore, notification, edi-converter)
- Or reduce JVM count: combine lightweight services into fewer JVMs
- Or increase Docker Desktop memory allocation to 20GB

### 2. Add Restart Policy to Nginx Containers
These 4 services are the ONLY ones without `restart: unless-stopped`:
```yaml
api-gateway:
  restart: unless-stopped    # ADD THIS
ui-service:
  restart: unless-stopped    # ADD THIS
partner-portal:
  restart: unless-stopped    # ADD THIS
ftp-web-ui:
  restart: unless-stopped    # ADD THIS
```

### 3. Match Healthcheck to Java Services
Nginx containers have `start_period: 15s` while Java services have `start_period: 600s`. Use consistent healthcheck:
```yaml
healthcheck:
  test: ["CMD-SHELL", "curl -sf http://localhost:8080/nginx-health || exit 1"]
  interval: 10s
  timeout: 3s
  retries: 3
  start_period: 30s    # nginx starts in <1s, 30s is generous
```

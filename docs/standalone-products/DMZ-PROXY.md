# DMZ Proxy — Standalone Product Guide

> **AI-powered security reverse proxy.** Protect any TCP service with real-time threat scoring, rate limiting, protocol detection, and 5-verdict security decisions. No database required.

**Port:** 8088 | **Dependencies:** None | **Database:** Not required | **Auth:** X-Internal-Key header

---

## Why Use This

- **Zero-dependency** — Runs standalone with no database
- **AI verdicts** — ALLOW / THROTTLE / CHALLENGE / BLOCK / BLACKHOLE
- **Hot-configurable** — Add/remove port mappings at runtime via REST
- **Protocol detection** — Auto-detect SFTP, FTP, HTTP, HTTPS from first bytes
- **Rate limiting** — Per-IP and global rate limiting (token bucket, lock-free)
- **Graceful degradation** — Falls back to ALLOW if AI engine is unavailable

---

## Quick Start

```bash
java -jar dmz-proxy/target/dmz-proxy-*.jar
curl http://localhost:8088/api/proxy/health
```

```json
{
  "status": "UP",
  "service": "dmz-proxy",
  "activeMappings": 3,
  "securityEnabled": true,
  "aiEngineAvailable": false,
  "activeConnections": 0,
  "features": ["hot-config", "rate-limiting", "protocol-detection", "ai-verdicts"]
}
```

---

## API Reference

### 1. List Port Mappings

**GET** `/api/proxy/mappings`

```bash
curl http://localhost:8088/api/proxy/mappings \
  -H "X-Internal-Key: internal_control_secret"
```

**Response:**
```json
[
  {
    "name": "sftp-gateway",
    "listenPort": 2222,
    "targetHost": "gateway-service",
    "targetPort": 2220,
    "active": true,
    "bytesForwarded": 1048576,
    "activeConnections": 3,
    "securityEnabled": true
  },
  {
    "name": "ftp-gateway",
    "listenPort": 21,
    "targetHost": "gateway-service",
    "targetPort": 2121,
    "active": true,
    "bytesForwarded": 524288,
    "activeConnections": 1,
    "securityEnabled": true
  }
]
```

### 2. Add Port Mapping (Hot-Config)

**POST** `/api/proxy/mappings`

Add a new proxy mapping at runtime — no restart needed.

```bash
curl -X POST http://localhost:8088/api/proxy/mappings \
  -H "X-Internal-Key: internal_control_secret" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-sftp-server",
    "listenPort": 2223,
    "targetHost": "10.0.1.50",
    "targetPort": 22
  }'
```

Now external clients connect to port 2223 on the proxy, and traffic is forwarded to `10.0.1.50:22` through the security pipeline.

### 3. Remove Port Mapping

**DELETE** `/api/proxy/mappings/{name}`

```bash
curl -X DELETE http://localhost:8088/api/proxy/mappings/my-sftp-server \
  -H "X-Internal-Key: internal_control_secret"
```

### 4. Security Statistics

**GET** `/api/proxy/security/stats`

```bash
curl http://localhost:8088/api/proxy/security/stats \
  -H "X-Internal-Key: internal_control_secret"
```

**Response:**
```json
{
  "securityEnabled": true,
  "metrics": {
    "totalConnections": 15000,
    "blockedConnections": 42,
    "throttledConnections": 156
  },
  "connections": {
    "active": 12,
    "total": 15000
  },
  "rateLimiter": {
    "globalRatePerMinute": 10000,
    "defaultPerIpPerMinute": 60
  },
  "aiEngine": {
    "available": true,
    "verdictCacheSize": 250,
    "pendingEvents": 3
  }
}
```

### 5. Connection Details

**GET** `/api/proxy/security/connections`

```bash
curl http://localhost:8088/api/proxy/security/connections \
  -H "X-Internal-Key: internal_control_secret"
```

### 6. IP Intelligence

**GET** `/api/proxy/security/ip/{ip}`

```bash
curl http://localhost:8088/api/proxy/security/ip/203.0.113.42 \
  -H "X-Internal-Key: internal_control_secret"
```

**Response:**
```json
{
  "ip": "203.0.113.42",
  "status": "tracked",
  "totalConnections": 45,
  "blockedConnections": 0,
  "lastSeen": "2026-04-05T14:30:00Z",
  "rateLimit": {
    "connectionsThisMinute": 3,
    "bytesThisMinute": 1048576
  }
}
```

### 7. Rate Limit Status

**GET** `/api/proxy/security/rate-limits`

```bash
curl http://localhost:8088/api/proxy/security/rate-limits \
  -H "X-Internal-Key: internal_control_secret"
```

### 8. Security Summary

**GET** `/api/proxy/security/summary`

```bash
curl http://localhost:8088/api/proxy/security/summary \
  -H "X-Internal-Key: internal_control_secret"
```

---

## Security Pipeline

Every incoming connection passes through this pipeline:

```
Client → [Rate Limiter] → [AI Verdict] → [Protocol Detect] → [Proxy] → Backend
              │                │                │
              ▼                ▼                ▼
         BLOCK if          BLOCK/THROTTLE/   Log protocol
         over limit        CHALLENGE/ALLOW    (SFTP/FTP/HTTP)
```

### Verdict Actions

| Verdict | Action | Description |
|---------|--------|-------------|
| **ALLOW** | Forward | Connection proceeds normally |
| **THROTTLE** | Rate limit | Apply restrictive rate limits |
| **CHALLENGE** | Allow + monitor | Permit but increase monitoring |
| **BLOCK** | Reject | Close connection with TCP FIN |
| **BLACKHOLE** | Silent drop | Drop connection (SO_LINGER=0) |

### Rate Limiting

| Parameter | Default | Description |
|-----------|---------|-------------|
| Per-IP connections/min | 60 | Max connections per IP per minute |
| Per-IP concurrent | 20 | Max simultaneous connections per IP |
| Per-IP bytes/min | 500 MB | Max bytes per IP per minute |
| Global connections/min | 10,000 | Max total connections per minute |

---

## Integration Examples

### Python — Protect Any TCP Service
```python
import requests

PROXY = "http://localhost:8088"
KEY = "internal_control_secret"
HEADERS = {"X-Internal-Key": KEY}

# Expose your internal Redis through the security proxy
requests.post(f"{PROXY}/api/proxy/mappings",
    headers={**HEADERS, "Content-Type": "application/json"},
    json={
        "name": "redis-secured",
        "listenPort": 16379,
        "targetHost": "10.0.1.100",
        "targetPort": 6379
    }
)

# Monitor connections
stats = requests.get(f"{PROXY}/api/proxy/security/stats", headers=HEADERS).json()
print(f"Active: {stats['connections']['active']}, Blocked: {stats['metrics']['blockedConnections']}")
```

### Docker Compose — DMZ Architecture
```yaml
services:
  dmz-proxy:
    build: ./dmz-proxy
    ports:
      - "2222:2222"   # SFTP (public)
      - "21:21"       # FTP (public)
      - "8088:8088"   # Management (internal only)
    environment:
      DMZ_SECURITY_ENABLED: "true"
      AI_ENGINE_URL: "http://ai-engine:8091"
      DEFAULT_RATE_PER_MINUTE: "60"
      CONTROL_API_KEY: "your-secret-key"

  # Your internal service (not exposed)
  sftp-server:
    image: your-sftp-server
    # Only accessible via dmz-proxy
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `CONTROL_API_KEY` | `internal_control_secret` | API key for management endpoints |
| `DMZ_SECURITY_ENABLED` | `true` | Enable/disable security pipeline |
| `AI_ENGINE_URL` | `http://ai-engine:8091` | AI engine URL for verdicts |
| `VERDICT_TIMEOUT_MS` | `200` | AI verdict timeout (ms) |
| `DEFAULT_RATE_PER_MINUTE` | `60` | Default per-IP rate limit |
| `DEFAULT_MAX_CONCURRENT` | `20` | Default per-IP concurrent limit |
| `DEFAULT_MAX_BYTES_PER_MINUTE` | `500000000` | Per-IP bytes/min (500 MB) |
| `GLOBAL_RATE_PER_MINUTE` | `10000` | Global rate limit |
| `server.port` | `8088` | Management API port |

---

## All Endpoints Summary

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/proxy/mappings` | X-Internal-Key | List port mappings |
| POST | `/api/proxy/mappings` | X-Internal-Key | Add port mapping |
| DELETE | `/api/proxy/mappings/{name}` | X-Internal-Key | Remove port mapping |
| GET | `/api/proxy/security/stats` | X-Internal-Key | Security statistics |
| GET | `/api/proxy/security/connections` | X-Internal-Key | Connection details |
| GET | `/api/proxy/security/ip/{ip}` | X-Internal-Key | IP intelligence |
| GET | `/api/proxy/security/rate-limits` | X-Internal-Key | Rate limit status |
| GET | `/api/proxy/security/summary` | X-Internal-Key | Security summary |
| GET | `/api/proxy/health` | None | Health check |

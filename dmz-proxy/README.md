# DMZ Proxy

> AI-powered reverse proxy with real-time threat detection, rate limiting, and protocol-aware security.

**Port:** 8088 (Management API) + configurable listen ports | **Database:** None | **Required:** Recommended

---

## Overview

The DMZ proxy is the outermost security layer of the platform. It accepts all incoming connections (SFTP, FTP, HTTPS) and applies AI-powered security analysis before forwarding traffic to internal services. Key capabilities:

- **Protocol detection** — Zero-copy identification (SSH, FTP, FTPS, HTTP, TLS) from first bytes
- **Rate limiting** — Token bucket per-IP and global, with AI-driven adaptive limits
- **AI verdict system** — ALLOW / THROTTLE / CHALLENGE / BLOCK / BLACKHOLE
- **Connection tracking** — Per-IP state (active connections, bytes, protocols, countries)
- **Threat event reporting** — Batched async events to AI engine
- **Hot-configurable port mappings** — Add/remove listeners without restart
- **No database** — Stateless by design, runs in the DMZ network zone
- **Graceful degradation** — Conservative local heuristics if AI engine is unreachable

---

## Quick Start

```bash
docker compose up -d gateway-service ai-engine dmz-proxy

# Health check
curl http://localhost:8088/api/proxy/health

# Security dashboard
curl http://localhost:8088/api/proxy/security/stats \
  -H "X-Internal-Key: internal_control_secret"
```

---

## API Endpoints

All endpoints except `/api/proxy/health` require the `X-Internal-Key` header.

### Port Mapping Management

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/proxy/mappings` | List all port mappings with live stats |
| POST | `/api/proxy/mappings` | Hot-add a new port mapping |
| DELETE | `/api/proxy/mappings/{name}` | Remove a port mapping |

**List mappings:**
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
    "activeConnections": 5,
    "bytesForwarded": 1073741824
  }
]
```

**Hot-add a mapping:**
```bash
curl -X POST http://localhost:8088/api/proxy/mappings \
  -H "X-Internal-Key: internal_control_secret" \
  -H "Content-Type: application/json" \
  -d '{"name":"new-sftp","listenPort":2223,"targetHost":"sftp-2","targetPort":2222}'
```

### Security Intelligence

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/proxy/security/stats` | Full security metrics dump |
| GET | `/api/proxy/security/connections` | Connection tracker statistics |
| GET | `/api/proxy/security/ip/{ip}` | Per-IP security intelligence |
| GET | `/api/proxy/security/rate-limits` | Rate limiter state |
| GET | `/api/proxy/security/summary` | Quick security summary |

**Per-IP intelligence:**
```bash
curl http://localhost:8088/api/proxy/security/ip/203.0.113.5 \
  -H "X-Internal-Key: internal_control_secret"
```

**Response:**
```json
{
  "ip": "203.0.113.5",
  "activeConnections": 2,
  "totalConnections": 47,
  "rejectedConnections": 3,
  "bytesIn": 52428800,
  "bytesOut": 104857600,
  "protocols": ["SSH"],
  "portsUsed": [2222],
  "country": "US",
  "lastSeen": "2026-04-05T10:30:00Z"
}
```

**Full security stats:**
```bash
curl http://localhost:8088/api/proxy/security/stats \
  -H "X-Internal-Key: internal_control_secret"
```

**Response includes:**
- Connection counters (total, allowed, throttled, blocked, blackholed, rate-limited)
- Throughput (bytes in/out)
- AI engine metrics (verdict requests, cache hits, fallbacks)
- Protocol distribution
- Port distribution
- Top 10 IPs by connection count
- Uptime

### Health

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/api/proxy/health` | None | Service health + features |

**Response:**
```json
{
  "status": "UP",
  "activeMappings": 3,
  "securityEnabled": true,
  "aiEngineAvailable": true,
  "features": ["RATE_LIMITING", "AI_VERDICT", "PROTOCOL_DETECTION", "THREAT_REPORTING"]
}
```

---

## Security Layer Architecture

```
  Client Connection
       │
       ▼
┌──────────────────────────────────────────────────────┐
│  IntelligentProxyHandler (Netty ChannelHandler)      │
│                                                      │
│  1. Rate Limiter Check (instant, no network)         │
│     └── Token bucket: per-IP + global                │
│                                                      │
│  2. AI Verdict (cached or sync query, 200ms timeout) │
│     ├── ALLOW → continue                             │
│     ├── THROTTLE → allow with rate limits            │
│     ├── CHALLENGE → allow but monitor                │
│     ├── BLOCK → send RST, close                      │
│     └── BLACKHOLE → silent drop                      │
│                                                      │
│  3. Register Connection (ConnectionTracker)          │
│                                                      │
│  4. Protocol Detection (on first bytes)              │
│     └── SSH, FTP, FTPS, HTTP, TLS                    │
│                                                      │
│  5. Byte Rate Limit (on data transfer)               │
│                                                      │
│  6. Connection Close (report to ThreatEventReporter) │
└──────────────────────────────────────────────────────┘
       │
       ▼
  RelayHandler (bidirectional byte forwarding to backend)
```

### Components

| Component | Purpose |
|-----------|---------|
| **IntelligentProxyHandler** | Orchestrates all security checks per connection |
| **ProtocolDetector** | Identifies protocol from first 3+ bytes without consuming them |
| **ConnectionTracker** | Per-IP state: channels, timestamps, bytes, protocols (max 50K IPs) |
| **RateLimiter** | Token bucket with per-IP and global limits (lock-free AtomicLong) |
| **AiVerdictClient** | Queries AI engine with caching (100K entries) and graceful degradation |
| **ThreatEventReporter** | Batches events (queue of 10K, flush every 5s or batch of 50) |
| **SecurityMetrics** | Thread-safe counters for all security activity |

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8088` | Management API port |
| `DMZ_SECURITY_ENABLED` | `true` | Enable/disable AI security layer |
| `AI_ENGINE_URL` | `http://ai-engine:8091` | AI engine base URL |
| `VERDICT_TIMEOUT_MS` | `200` | Max wait for AI verdict (ms) |
| `DEFAULT_RATE_PER_MINUTE` | `60` | Per-IP connection rate limit |
| `DEFAULT_MAX_CONCURRENT` | `20` | Per-IP concurrent connection limit |
| `DEFAULT_MAX_BYTES_PER_MINUTE` | `500000000` | Per-IP bandwidth limit (500 MB) |
| `GLOBAL_RATE_PER_MINUTE` | `10000` | Global DDoS threshold |
| `CONTROL_API_KEY` | `internal_control_secret` | Management API key |
| `GATEWAY_HOST` | `gateway-service` | Default SFTP/FTP target |
| `FTPWEB_HOST` | `ftp-web-service` | Default HTTPS target |

### Default Port Mappings

| Name | Listen Port | Target | Purpose |
|------|------------|--------|---------|
| sftp-gateway | 2222 | gateway-service:2220 | SFTP access |
| ftp-gateway | 21 | gateway-service:2121 | FTP access |
| ftp-web | 443 | ftp-web-service:8083 | HTTPS access |

---

## Standalone Mode

The DMZ proxy can run without the AI engine:
```bash
java -jar target/dmz-proxy-*.jar --DMZ_SECURITY_ENABLED=false
```

In this mode, all connections are forwarded without security checks. Useful for development and testing.

With security enabled but AI engine unreachable, the proxy falls back to conservative local heuristics:
- New IPs: rate limited to 30 connections/min, 5 concurrent
- Existing IPs: default limits apply
- No protocol-specific analysis

---

## Dependencies

- **AI Engine** — Optional but recommended. Provides verdicts and threat intelligence.
- **Gateway Service** — Default backend for SFTP/FTP connections.
- **No database** — By design. All state is in-memory.

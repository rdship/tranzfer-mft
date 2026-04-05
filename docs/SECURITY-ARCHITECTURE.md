# TranzFer MFT — Security Architecture

This document covers the AI-powered security layer that protects the DMZ reverse proxy. It explains the threat model, how connections are evaluated, and how to configure and monitor the security system.

---

## Table of Contents

1. [Overview](#overview)
2. [Threat Model](#threat-model)
3. [Connection Lifecycle](#connection-lifecycle)
4. [DMZ Proxy Security Components](#dmz-proxy-security-components)
5. [AI Engine Intelligence Services](#ai-engine-intelligence-services)
6. [Verdict System](#verdict-system)
7. [Rate Limiting](#rate-limiting)
8. [Protocol Detection](#protocol-detection)
9. [IP Reputation System](#ip-reputation-system)
10. [Geo Anomaly Detection](#geo-anomaly-detection)
11. [Connection Pattern Analysis](#connection-pattern-analysis)
12. [Protocol Threat Detection](#protocol-threat-detection)
13. [Graceful Degradation](#graceful-degradation)
14. [Monitoring & Observability](#monitoring--observability)
15. [Configuration Reference](#configuration-reference)
16. [Production Hardening Checklist](#production-hardening-checklist)

---

## Overview

The security system operates as a **two-tier architecture**:

```
┌───────────────────────────────────────────────────┐
│  DMZ PROXY (Lightweight Sensor + Enforcer)         │
│                                                     │
│  - Rate limiting (instant, no network call)         │
│  - Verdict caching (sub-millisecond lookups)        │
│  - Protocol detection (zero-copy from first bytes)  │
│  - Connection tracking (per-IP state)               │
│  - Action enforcement (ALLOW/BLOCK/BLACKHOLE)       │
│  - Event reporting (batched, async)                 │
│                                                     │
│  Design: Stateless. No database. Minimal memory.    │
│  If compromised: Cannot access any internal data.   │
└──────────────────────┬────────────────────────────┘
                       │ HTTP (async)
                       ▼
┌───────────────────────────────────────────────────┐
│  AI ENGINE (Intelligence Brain)                     │
│                                                     │
│  - IP reputation scoring (0-100, decay model)       │
│  - Protocol-specific threat detection               │
│  - Connection pattern analysis (DDoS, brute force)  │
│  - Geo anomaly detection (impossible travel, Tor)   │
│  - Weighted composite risk scoring                  │
│  - Verdict computation with TTL                     │
│                                                     │
│  Design: Learns over time. All intelligence here.   │
│  Proxy never blocks on AI Engine.                   │
└───────────────────────────────────────────────────┘
```

**Key design principles:**
1. **Never block the hot path** — Rate limiting is instant. AI verdicts are cached. No connection waits more than 200ms.
2. **Graceful degradation** — If the AI engine is down, the proxy continues with conservative local heuristics.
3. **Product-agnostic** — Works with any TCP protocol (SSH, FTP, HTTP, TLS, custom). No business logic.
4. **Air-gapped isolation** — DMZ proxy has zero database access. Cannot leak data.

---

## Threat Model

### What we protect against

| Threat | Detection Method | Response |
|--------|-----------------|----------|
| **Brute force SSH** | Auth failure count per IP | Auto-block after threshold |
| **DDoS / Connection flood** | Global connection rate > 5000/min | Global rate limiting |
| **Port scanning** | Single IP connecting to multiple ports | Increase risk score → THROTTLE/BLOCK |
| **Slow loris** | Long-lived connections with minimal data | Pattern analysis flag |
| **Banner grabbing** | Connect → read banner → disconnect quickly | Short duration + low bytes pattern |
| **FTP bounce attack** | PORT command to non-standard IP | Protocol threat detection |
| **Directory traversal** | `../../` in FTP paths | Protocol threat detection |
| **SQL injection in HTTP** | SQL patterns in HTTP request line | Protocol threat detection |
| **Suspicious SSH clients** | Known attack tool identifiers (ncrack, hydra, nmap) | High risk score → BLOCK |
| **TLS downgrade** | SSLv2/SSLv3 version in ClientHello | Protocol threat detection |
| **Tor exit nodes** | IP in Tor exit node list | Geo risk increase |
| **Impossible travel** | Same account, different country, < 60 min | Geo anomaly flag |
| **IP reputation decay** | Known bad actors | Score-based escalation |

### What is NOT in scope (v1)

- Deep packet inspection (DPI) of encrypted traffic
- Application-layer WAF rules (beyond basic pattern matching)
- Client certificate authentication
- Mutual TLS between proxy and AI engine
- Persistent reputation storage (lost on restart — see [GAP-ANALYSIS.md](GAP-ANALYSIS.md#F3))

---

## Connection Lifecycle

Every connection goes through this exact sequence:

```
Client connects to DMZ Proxy (e.g., port 2222)
│
├── STEP 1: Rate Limit Check (instant, < 1 microsecond)
│   ├── Check global rate (10,000/min default)
│   ├── Check per-IP rate (60/min default)
│   ├── Check per-IP concurrent (20 default)
│   │
│   ├── IF RATE LIMITED:
│   │   ├── Report RATE_LIMIT_HIT event (async)
│   │   ├── Increment metrics
│   │   └── Close connection → DONE
│   │
│   └── IF OK: Continue ▼
│
├── STEP 2: AI Verdict Query (< 200ms, usually < 1ms cache hit)
│   ├── Check verdict cache (key: IP:port)
│   │
│   ├── IF CACHE HIT (< 1ms):
│   │   └── Use cached verdict
│   │
│   ├── IF CACHE MISS:
│   │   ├── HTTP POST to AI Engine /api/v1/proxy/verdict
│   │   ├── Timeout after 200ms
│   │   └── Cache result with TTL from response
│   │
│   ├── IF AI ENGINE UNAVAILABLE:
│   │   └── Use fallback verdict: ALLOW, 30/min, 10 concurrent, 30s TTL
│   │
│   └── Apply verdict action ▼
│
├── STEP 3: Apply Verdict
│   ├── BLACKHOLE → Set SO_LINGER=0, close (no FIN/RST sent)
│   ├── BLOCK → Close connection normally
│   ├── THROTTLE → Apply custom rate limits from verdict, continue
│   ├── CHALLENGE → Allow + set monitoring flag, continue
│   └── ALLOW → Continue normally
│
├── STEP 4: Register Connection
│   ├── Add to ConnectionTracker (per-IP state)
│   ├── Report CONNECTION_OPENED event (async, queued)
│   └── Continue ▼
│
├── STEP 5: First Data Arrives (channelRead)
│   ├── ProtocolDetector.detect() — identify protocol from first bytes
│   │   └── SSH banner? FTP 220? HTTP verb? TLS ClientHello?
│   ├── Update connection state with detected protocol
│   ├── Re-query AI verdict async (with protocol info)
│   ├── Check byte rate limit
│   └── Pass data to relay handler → forwarded to backend
│
├── STEP 6: Ongoing Traffic
│   ├── Bidirectional byte relay (client ↔ backend)
│   ├── Byte counting for rate limits and metrics
│   └── Continue until close
│
└── STEP 7: Connection Closes
    ├── Retrieve final stats (duration, bytes in/out, protocol)
    ├── Report CONNECTION_CLOSED event (async, queued)
    ├── Release rate limiter token
    └── DONE
```

---

## DMZ Proxy Security Components

Six Java classes in `dmz-proxy/src/main/java/com/filetransfer/dmz/security/`:

### IntelligentProxyHandler

The orchestrator. A Netty `ChannelInboundHandlerAdapter` that is the **first handler** in the pipeline for every connection.

**Source:** `dmz-proxy/src/main/java/com/filetransfer/dmz/security/IntelligentProxyHandler.java`

### ConnectionTracker

Maintains per-IP state in memory. Max 50,000 tracked IPs with LRU eviction.

**Per-IP state tracked:**
- Active channels (open connections)
- Connection timestamps (last 500, for rate calculation)
- Ports used (set of target ports — for port scan detection)
- Total bytes in/out
- Total connections and rejected connections
- Detected protocol and country
- Last seen timestamp

**Source:** `dmz-proxy/src/main/java/com/filetransfer/dmz/security/ConnectionTracker.java`

### RateLimiter

Lock-free token bucket implementation using `AtomicLong` / `AtomicInteger`.

**Three dimensions of limiting:**
1. **Connections per minute** per IP (default: 60)
2. **Concurrent connections** per IP (default: 20)
3. **Bytes per minute** per IP (default: 500 MB)
4. **Global connections per minute** (default: 10,000 — DDoS threshold)

Per-IP limits can be dynamically overridden by the AI engine.

**Source:** `dmz-proxy/src/main/java/com/filetransfer/dmz/security/RateLimiter.java`

### AiVerdictClient

HTTP client that queries the AI engine and caches results.

**Cache:** Up to 100,000 entries. Key = `IP:port`. TTL from AI engine response (typically 60s).

**Graceful degradation:** If AI engine is unreachable, returns a conservative fallback verdict (ALLOW with strict limits) and starts async health checks every 30s.

**Source:** `dmz-proxy/src/main/java/com/filetransfer/dmz/security/AiVerdictClient.java`

### ProtocolDetector

Zero-copy protocol identification from the first 3+ bytes of a connection.

| Bytes | Protocol | Confidence |
|-------|----------|-----------|
| `SSH-` | SSH | 99% |
| `220 ` | FTP server banner | 90% |
| `USER ` | FTP client command | 80% |
| `AUTH TLS` / `AUTH SSL` | FTPS (explicit) | 85% |
| `GET ` / `POST ` / etc. | HTTP | 95% |
| `0x16 0x03 0x03` | TLS 1.2 | 95% |
| `0x16 0x03 0x04` | TLS 1.3 | 95% |
| TLS on port 990 | FTPS (implicit) | 95% |
| Unrecognized + known port | Port-based fallback | 30% |

**Source:** `dmz-proxy/src/main/java/com/filetransfer/dmz/security/ProtocolDetector.java`

### ThreatEventReporter

Batched async event reporter. Events are queued in a `BlockingQueue` (capacity: 10,000) and flushed to the AI engine every 5 seconds or when 50 events accumulate.

**Event types:** `CONNECTION_OPENED`, `CONNECTION_CLOSED`, `RATE_LIMIT_HIT`, `REJECTED`, `AUTH_FAILURE`, `BYTES_TRANSFERRED`

**Source:** `dmz-proxy/src/main/java/com/filetransfer/dmz/security/ThreatEventReporter.java`

### SecurityMetrics

Atomic counters for observability: total/allowed/throttled/blocked/blackholed connections, bytes, cache hit rates, per-protocol and per-action distributions.

**Source:** `dmz-proxy/src/main/java/com/filetransfer/dmz/security/SecurityMetrics.java`

---

## AI Engine Intelligence Services

Four services in `ai-engine/src/main/java/com/filetransfer/ai/service/proxy/`:

### ProxyIntelligenceService (Orchestrator)

Computes verdicts by querying all four analyzers and combining their signals with weighted scoring.

**Source:** `ai-engine/src/main/java/com/filetransfer/ai/service/proxy/ProxyIntelligenceService.java`

### IpReputationService

Tracks reputation per IP. Score 0-100. New IPs start at 50 (neutral).

- Good behavior: +0.5 per success
- Bad behavior: -2 to -15 depending on severity
- Scores decay toward 50 over time (every 5 min)
- Score <= 5: auto-blocklist
- Max 100,000 tracked IPs

**Source:** `ai-engine/src/main/java/com/filetransfer/ai/service/proxy/IpReputationService.java`

### ProtocolThreatDetector

Detects protocol-specific attacks:
- Suspicious SSH clients (libssh, ncrack, hydra, nmap)
- SSH banner grab (connect + disconnect quickly)
- FTP bounce attack (PORT to non-standard IP)
- FTP directory traversal (`../../`)
- Suspicious HTTP paths (`/wp-admin`, `/.env`, `/.git`)
- SQL injection patterns in HTTP
- Scanning user agents (nikto, sqlmap)
- TLS version downgrade
- Zero-byte port probes

**Source:** `ai-engine/src/main/java/com/filetransfer/ai/service/proxy/ProtocolThreatDetector.java`

### ConnectionPatternAnalyzer

Detects behavioral patterns:
- Connection burst (>60/min per IP)
- DDoS (>5000/min global)
- Port scanning (one IP hitting many ports)
- Slow loris (long connections, few bytes)
- Banner grabbing (short connections, minimal data)
- Connection recycling (rapid connect/disconnect)

**Source:** `ai-engine/src/main/java/com/filetransfer/ai/service/proxy/ConnectionPatternAnalyzer.java`

### GeoAnomalyDetector

Geographic anomaly detection:
- High-risk country tracking
- Tor exit node detection
- VPN prefix detection
- Impossible travel (same account, different countries, < 60 min)

**Source:** `ai-engine/src/main/java/com/filetransfer/ai/service/proxy/GeoAnomalyDetector.java`

---

## Verdict System

### Risk Score Computation

The AI engine computes a composite risk score (0-100) using weighted signals:

```
composite_risk = (reputation_risk * 0.35)
               + (pattern_risk   * 0.30)
               + (geo_risk       * 0.15)
               + (new_ip_risk    * 0.10)
               + (protocol_risk  * 0.10)
```

| Factor | Weight | Source | Range |
|--------|--------|--------|-------|
| IP Reputation | 35% | `IpReputationService` | 0-100 (inverted: score 100 = risk 0) |
| Connection Patterns | 30% | `ConnectionPatternAnalyzer` | 0-100 |
| Geographic Risk | 15% | `GeoAnomalyDetector` | 0-100 |
| New IP Penalty | 10% | First-time IPs get 40 risk | 0 or 40 |
| Protocol Baseline | 10% | SSH=15, FTP=20, HTTP=10, TLS=5 | 0-20 |

### Verdict Actions

| Risk Score | Action | What Happens |
|-----------|--------|-------------|
| 0-39 | **ALLOW** | Connection passes through normally |
| 40-59 (new IP) | **THROTTLE** | Allowed with strict rate limits |
| 60-84 | **THROTTLE** | Allowed with rate limits from verdict |
| 85-94 | **BLOCK** | Connection rejected (TCP FIN) |
| 95-100 | **BLACKHOLE** | Silent drop (no RST/FIN — attacker doesn't know it arrived) |

### Verdict Response Format

```json
{
  "action": "ALLOW",
  "riskScore": 15,
  "reason": "Known IP, good reputation",
  "ttlSeconds": 3600,
  "rateLimit": {
    "maxConnectionsPerMinute": 100,
    "maxConcurrentConnections": 50,
    "maxBytesPerMinute": 1000000000
  },
  "signals": ["GOOD_REPUTATION"]
}
```

### Threat Signals

Signals are tags that explain why a verdict was given:

| Signal | Meaning |
|--------|---------|
| `NEW_IP` | First time seeing this IP |
| `GOOD_REPUTATION` | IP has high reputation score |
| `LOW_REPUTATION` | IP has poor reputation history |
| `BLOCKED_IP` | IP is on the blocklist |
| `ALLOWED_IP` | IP is on the allowlist |
| `HIGH_RISK_REGION` | IP is from a high-risk country |
| `TOR_EXIT` | IP is a known Tor exit node |
| `VPN_DETECTED` | IP matches a VPN prefix |
| `IMPOSSIBLE_TRAVEL` | Account accessed from distant locations too quickly |
| `CONNECTION_BURST` | Abnormally high connection rate |
| `PORT_SCAN` | IP connecting to many different ports |
| `BRUTE_FORCE` | Repeated auth failures detected |
| `SUSPICIOUS_CLIENT` | Known attack tool in SSH banner |
| `PROTOCOL_ANOMALY` | Protocol-specific attack pattern detected |

---

## Rate Limiting

### How it works

Token bucket algorithm with three dimensions:

```
┌──────────────────────────────────────────────────┐
│  Per-IP Bucket                                    │
│                                                    │
│  tokens: 60 ──────── refills at maxPerMinute/60   │
│  concurrent: 0/20 ── increments on connect,       │
│                       decrements on disconnect     │
│  bytesThisMinute: 0 ─ resets every 60 seconds     │
│                                                    │
│  All operations are lock-free (AtomicLong)        │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│  Global Bucket                                    │
│                                                    │
│  tokens: 10000 ──── refills at globalMaxPerMinute │
│  If depleted: ALL new connections rejected        │
│  (DDoS protection)                                │
└──────────────────────────────────────────────────┘
```

### Default limits

| Limit | Default | Env Variable |
|-------|---------|-------------|
| Per-IP connections/min | 60 | `DEFAULT_RATE_PER_MINUTE` |
| Per-IP concurrent | 20 | `DEFAULT_MAX_CONCURRENT` |
| Per-IP bytes/min | 500 MB | `DEFAULT_MAX_BYTES_PER_MINUTE` |
| Global connections/min | 10,000 | `GLOBAL_RATE_PER_MINUTE` |

### AI-driven adaptive limits

The AI engine can override per-IP limits via the verdict response. For example, a trusted partner might get:

```json
{
  "rateLimit": {
    "maxConnectionsPerMinute": 500,
    "maxConcurrentConnections": 100,
    "maxBytesPerMinute": 5000000000
  }
}
```

While a suspicious IP might get:

```json
{
  "rateLimit": {
    "maxConnectionsPerMinute": 5,
    "maxConcurrentConnections": 1,
    "maxBytesPerMinute": 10000000
  }
}
```

---

## Protocol Detection

The ProtocolDetector reads the first bytes of a connection **without consuming them** (zero-copy) to identify the protocol:

```
First 3+ bytes arrive
│
├── Starts with "SSH-" ?
│   └── YES → SSH (99% confidence)
│       Extract version: "SSH-2.0-OpenSSH_8.9"
│
├── Starts with "220 " ?
│   └── YES → FTP server banner (90%)
│
├── Starts with "USER " / "FEAT" / "SYST" ?
│   └── YES → FTP client command (80%)
│
├── Starts with "AUTH TLS" / "AUTH SSL" ?
│   └── YES → FTPS explicit (85%)
│
├── Starts with HTTP verb? (GET/POST/PUT/DELETE/HEAD/OPTIONS/PATCH/CONNECT)
│   └── YES → HTTP (95%)
│
├── Byte[0]=0x16 AND Byte[1]=0x03 ?
│   └── YES → TLS ClientHello
│       ├── Byte[2]=0x03 → TLS 1.2
│       ├── Byte[2]=0x04 → TLS 1.3
│       └── Port 990? → FTPS implicit (95%)
│
├── Less than 3 bytes?
│   └── UNKNOWN (0% confidence)
│
└── Check port number for fallback:
    ├── 22/2222 → SSH (30%)
    ├── 21 → FTP (30%)
    ├── 990 → FTPS (30%)
    ├── 80/8080 → HTTP (30%)
    ├── 443/8443 → TLS (30%)
    └── Other → UNKNOWN (0%)
```

---

## IP Reputation System

### Score model

```
Score: 0 ──────── 50 ──────── 100
       │          │           │
     Blocked   Neutral    Trusted
     (bad)    (new IP)   (allowlisted)
```

### Score adjustments

| Event | Score Change |
|-------|-------------|
| Successful connection | +0.5 |
| Auth failure | -2 |
| Exploit attempt | -10 |
| Exploit attack | -15 |
| Connection rejected | -1 |
| Manual block | Set to 0 |
| Manual allow | Set to 100 |
| Unblock | Restore to 25 |

### Auto-blocklist

When an IP's score drops to 5 or below, it is automatically added to the blocklist. All future connections from this IP receive a `BLACKHOLE` verdict.

### Score decay

Every 5 minutes, all scores drift toward 50 (neutral):
- Scores above 50 decrease slightly
- Scores below 50 increase slightly
- This ensures IPs can recover from temporary bad behavior

---

## Graceful Degradation

If the AI engine is unreachable:

```
Normal mode:                      Degraded mode:
┌────────────┐                   ┌────────────┐
│ Rate limit │                   │ Rate limit │  ← Still works (local)
│     ↓      │                   │     ↓      │
│ AI verdict │ ← HTTP call      │  Fallback  │  ← No HTTP call
│     ↓      │                   │  verdict   │
│ Protocol   │                   │     ↓      │
│ detect     │                   │ Protocol   │  ← Still works (local)
│     ↓      │                   │ detect     │
│ Relay      │                   │     ↓      │
└────────────┘                   │ Relay      │  ← Still works
                                 └────────────┘
```

**Fallback verdict:**
```json
{
  "action": "ALLOW",
  "riskScore": 0,
  "reason": "AI engine unavailable — conservative fallback",
  "ttlSeconds": 30,
  "rateLimit": {
    "maxConnectionsPerMinute": 30,
    "maxConcurrentConnections": 10,
    "maxBytesPerMinute": 100000000
  }
}
```

The proxy starts async health checks every 30 seconds. When the AI engine recovers, normal mode resumes automatically.

---

## Monitoring & Observability

### Security dashboard

```bash
curl -H "X-Internal-Key: $CONTROL_API_KEY" \
  http://dmz-proxy:8088/api/proxy/security/stats
```

Returns:
```json
{
  "metrics": {
    "totalConnections": 125000,
    "allowedConnections": 120000,
    "blockedConnections": 3500,
    "blackholedConnections": 500,
    "throttledConnections": 1000,
    "rateLimitedConnections": 2500,
    "totalBytesIn": 50000000000,
    "totalBytesOut": 80000000000,
    "blockRate": "3.2%",
    "cacheHitRate": "94.5%"
  },
  "connections": {
    "trackedIps": 4250,
    "activeConnections": 342
  }
}
```

### Per-IP intelligence

```bash
curl -H "X-Internal-Key: $CONTROL_API_KEY" \
  http://dmz-proxy:8088/api/proxy/security/ip/203.0.113.5
```

### AI engine dashboard

```bash
curl http://ai-engine:8091/api/v1/proxy/dashboard
```

Returns comprehensive data: verdicts, IP reputation, connection patterns, geo intelligence, traffic analysis.

### Blocklist management

```bash
# View blocked IPs
curl http://ai-engine:8091/api/v1/proxy/blocklist

# Block an IP
curl -X POST http://ai-engine:8091/api/v1/proxy/blocklist \
  -H "Content-Type: application/json" \
  -d '{"ip": "203.0.113.5", "reason": "Manual block — suspected scanning"}'

# Unblock an IP
curl -X DELETE http://ai-engine:8091/api/v1/proxy/blocklist/203.0.113.5
```

---

## Configuration Reference

### DMZ Proxy (`dmz-proxy/src/main/resources/application.yml`)

```yaml
dmz:
  security:
    enabled: ${DMZ_SECURITY_ENABLED:true}
    ai-engine-url: ${AI_ENGINE_URL:http://ai-engine:8091}
    verdict-timeout-ms: ${VERDICT_TIMEOUT_MS:200}
    default-rate-per-minute: ${DEFAULT_RATE_PER_MINUTE:60}
    default-max-concurrent: ${DEFAULT_MAX_CONCURRENT:20}
    default-max-bytes-per-minute: ${DEFAULT_MAX_BYTES_PER_MINUTE:500000000}
    global-rate-per-minute: ${GLOBAL_RATE_PER_MINUTE:10000}
    event-queue-capacity: 10000
    event-batch-size: 50
    event-flush-interval-ms: 5000
```

### AI Engine (`ai-engine/src/main/resources/application.yml`)

```yaml
ai:
  proxy:
    enabled: true
    decay-interval-ms: 300000          # 5 min reputation decay
    max-tracked-ips: 100000
    max-connections-per-ip-per-minute: 60
    ddos-threshold-per-minute: 5000
```

---

## Production Hardening Checklist

- [ ] Change all default secrets (JWT, API keys, DB passwords, encryption keys)
- [ ] Enable TLS on DMZ proxy management port (8088) or restrict to localhost
- [ ] Never expose port 8088, 8091, or any management port to the internet
- [ ] Set `GLOBAL_RATE_PER_MINUTE` based on expected traffic (default 10,000)
- [ ] Configure `VERDICT_TIMEOUT_MS` based on network latency to AI engine
- [ ] Set up firewall rules per [ARCHITECTURE.md](ARCHITECTURE.md#firewall-rules-production)
- [ ] Monitor `blockRate` and `cacheHitRate` — alert if cache hit rate drops below 80%
- [ ] Feed Tor exit node list and VPN prefixes to AI engine via `/api/v1/proxy/geo/tor-nodes`
- [ ] Set high-risk countries via `/api/v1/proxy/geo/high-risk-countries`
- [ ] Review [GAP-ANALYSIS.md](GAP-ANALYSIS.md) for known limitations
- [ ] Consider mTLS between DMZ proxy and AI engine for high-security deployments
- [ ] Back up AI engine regularly (reputation data is in-memory only in v1)

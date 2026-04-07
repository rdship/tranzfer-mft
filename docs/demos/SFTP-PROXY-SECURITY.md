# SFTP Server Security via DMZ Proxy

How TranzFer MFT secures SFTP servers using the DMZ reverse proxy — a deep dive into the connection lifecycle, security pipeline, and resilience mechanisms.

## Architecture Overview

```
                          DMZ Zone                           Internal Zone
                    ┌─────────────────────┐            ┌──────────────────────┐
  SFTP Client ──────┤  DMZ Proxy (:32222) ├────────────┤  Gateway (:2220)     │
  (Internet)        │                     │            │       │              │
                    │  9 Security Layers  │            │       ├── SFTP-1     │
                    │  AI-Powered Verdict │            │       ├── SFTP-2     │
                    │  Zone Enforcement   │            │       └── SFTP-3     │
                    │  Health Checking    │            │                      │
                    │  Audit Logging      │            │  (User Routing)      │
                    └─────────────────────┘            └──────────────────────┘
```

**Key principle:** The SFTP servers never expose ports to the internet. All external traffic enters through the DMZ proxy, which sits in the DMZ network zone and is the only component accessible from outside.

## Connection Lifecycle

### Phase 1: TCP Accept + Security Pipeline (~0-50ms)

When an SFTP client connects to port 32222, the DMZ proxy processes the connection through 9 sequential security layers before any bytes reach the backend.

#### Layer 1: TLS Termination (Optional)
If TLS is enabled for this mapping:
- Netty `SslHandler` performs TLS handshake
- Supports TLSv1.2 and TLSv1.3 (TLSv1.0/1.1 rejected)
- mTLS (mutual TLS) when `requireClientCert=true`
- Cipher suite filtering: NULL, EXPORT, DES, RC4, MD5 excluded
- Certificate chain validation with expiry warnings (<30 days)
- OCSP stapling support
- Audit: TLS version, cipher suite, client cert subject logged

#### Layer 2: Intelligent Security Handler
The `IntelligentProxyHandler` runs the AI-powered security pipeline:

**Step 2a: Manual Security Filter** (<1ms)
- IP whitelist check (exact match + CIDR ranges)
- IP blacklist check
- Geo-blocking (country code lookup)
- Transfer window enforcement (day-of-week + time range)
- All O(1) lookups via pre-computed HashSets — zero network calls

**Step 2b: Rate Limiting** (<1ms)
- Per-IP token bucket: default 60 connections/minute, 20 concurrent
- Per-port overrides for mapping-specific limits
- Global rate: 10,000 connections/minute across all IPs
- Byte-rate limiting: 500MB/minute per IP default
- Lock-free implementation using AtomicLong/AtomicInteger

**Step 2c: AI Verdict** (~0-50ms)
- Queries AI engine at `/api/v1/proxy/verdict` with:
  - Source IP, target port, detected protocol, security tier
- AI engine evaluates: IP reputation, geo-anomaly, protocol analysis, connection patterns, threat intel
- **Cache hit (90%+ rate):** <1ms — TTL 15s for ALLOW, 300s for BLOCK
- **Cache miss:** 5-50ms network call with configurable timeout (200ms default)
- **AI engine down:** Graceful degradation — ALLOW with conservative limits (30/min, 10 concurrent, 100MB/min)
- Five possible verdicts:

| Verdict | Action | Connection |
|---------|--------|------------|
| ALLOW | Proceed normally | Open |
| THROTTLE | Proceed with reduced limits | Open |
| CHALLENGE | Proceed with enhanced logging | Open |
| BLOCK | Close with TCP RST | Closed |
| BLACKHOLE | Immediate close, SO_LINGER=0 | Dropped |

**Step 2d: Protocol Detection** (on first bytes)
- Zero-copy inspection of initial bytes
- SSH detection: checks for `SSH-` prefix (SSH-2.0 banner)
- Confidence level logged
- Protocol context fed to AI for async re-evaluation

**Step 2e: Deep Packet Inspection (DPI)**
- Inspects payload for malware signatures, exfiltration patterns
- Protocol-specific validation

#### Layer 3: Backend Health Check
- Active TCP probing every 10 seconds
- Unhealthy after 3 consecutive failures
- Healthy after 1 success
- If backend unhealthy: connection rejected immediately, client gets TCP RST
- Prevents connection buildup against dead backends

#### Layer 4: Zone Enforcement (<1ms)
- Classifies source IP into zone: EXTERNAL, DMZ, INTERNAL, MANAGEMENT
- Pre-resolved target zone (cached at mapping creation — no DNS on event loop)
- Validates zone transition against 12 default rules:
  - EXTERNAL → DMZ: **Allowed** (ingress)
  - DMZ → INTERNAL: **Allowed** (proxy forwarding)
  - EXTERNAL → INTERNAL: **Blocked** (no bypass)
  - EXTERNAL → MANAGEMENT: **Blocked** (protect admin)
- Anti-evasion: normalizes obfuscated IPs (octal, hex, URL-encoded, IPv6-mapped)

#### Layer 5: Egress Filter (pre-checked at startup)
- Backend destination validated once at mapping creation, not per-connection
- Blocks: SMTP (25), DNS (53), SMB (135-139, 445)
- Blocks: loopback, link-local, cloud metadata (169.254.169.254)
- DNS pinning: caches resolved IP, detects rebinding attacks

### Phase 2: Backend Connection + SSH Banner Relay

After all security checks pass:

```
Client ←──[RelayHandler]──→ DMZ Proxy ←──[RelayHandler]──→ Gateway
                                │
                          PROXY Protocol v1
                          (client IP preserved)
```

1. **Backend Bootstrap:** Opens TCP to `gateway-service:2220` on the client's event loop
2. **PROXY Protocol Header:** If enabled, sends `PROXY TCP4 <clientIP> <proxyIP> <clientPort> <proxyPort>\r\n` so the gateway sees the real client IP
3. **Bidirectional Relay:** Two `RelayHandler` instances forward bytes:
   - Client → Backend: client SSH handshake, authentication, SFTP commands
   - Backend → Client: SSH banner (`SSH-2.0-...`), key exchange, SFTP responses
4. **Backpressure:** `AUTO_READ=false` with explicit `.read()` calls — only reads when the paired channel has flushed its write, preventing buffer overflow
5. **Byte Counting:** Every relayed `ByteBuf` adds to `bytesForwarded` atomic counter

### Phase 3: SSH Protocol Exchange (through proxy)

The proxy is protocol-agnostic at this layer. The SSH protocol flows transparently:

```
1. ← SSH-2.0-Apache-SSHD banner (gateway → client, through proxy)
2. → SSH-2.0-OpenSSH_9.x banner (client → gateway, through proxy)
3. ←→ Key Exchange (ECDH-SHA2-NISTP256)
4. → Password authentication (encrypted in SSH channel)
5. ←→ SFTP subsystem negotiation
6. ←→ SFTP file operations (ls, put, get, mkdir, etc.)
```

### Phase 4: Gateway User Routing

The gateway receives the SSH connection (with real client IP via PROXY protocol) and:
1. Extracts username from SSH authentication
2. Queries config-service for user's assigned SFTP server instance
3. Opens upstream SSH connection to the target SFTP server (e.g., `sftp-service:2222`)
4. Relays SFTP operations bidirectionally

### Phase 5: Connection Teardown + Audit

On disconnect:
- `channelInactive()` fires on both relay handlers
- Backend channel closed when client disconnects (and vice versa)
- `activeConnections` counter decremented
- Connection tracker records: duration, bytes in/out, protocol
- Audit log entry written: event=CLOSE, source IP, port, mapping, duration, bytes

## Security Features Summary

| Feature | Overhead | Description |
|---------|----------|-------------|
| Manual IP Filter | <1ms | Whitelist/blacklist/CIDR with O(1) lookup |
| Rate Limiting | <1ms | Per-IP token bucket, lock-free atomics |
| AI Verdict (cached) | <1ms | 90%+ cache hit rate, 15s TTL |
| AI Verdict (miss) | 5-50ms | ML model evaluation + threat intel |
| Zone Enforcement | <1ms | CIDR classification, no DNS |
| Backend Health Check | 0ms | Pre-computed, checked at connection time |
| DPI | <1ms | Zero-copy buffer inspection |
| Audit Logging | <1ms | Async buffered write, JSONL format |
| PROXY Protocol | <1ms | One-time header on first write |

**Total security overhead:** <1ms for cached connections, 5-50ms for first-seen IPs.

## Resilience Mechanisms

### AI Engine Unavailable
When the AI engine is unreachable:
- Proxy does NOT block connections
- Falls back to local heuristics: ALLOW with conservative limits
  - 30 connections/minute (vs 60 normal)
  - 10 concurrent (vs 20 normal)
  - 100MB/minute (vs 500MB normal)
- "FALLBACK" signal added to audit trail
- Async health polling every 30s to detect recovery
- Full AI evaluation resumes immediately when engine returns

### Backend Server Down
- Health checker marks backend UNHEALTHY after 3 failed probes
- New connections rejected instantly (no timeout wait)
- Existing connections unaffected (already relayed)
- Single successful probe restores HEALTHY status
- Client receives immediate TCP close (clear failure signal)

### Connection Draining on Shutdown
- `stop()` closes the server channel (no new connections)
- Existing relay channels continue until natural close
- Event loop groups shut down gracefully

### Backpressure Protection
- `AUTO_READ=false` prevents unbounded buffer growth
- Read only triggered after paired channel's write completes
- If either side stalls, the other stops reading — natural flow control
- Prevents OOM from fast producers / slow consumers

## Audit Trail

Every SFTP connection generates audit entries in `audit-logs/audit-YYYY-MM-DD.jsonl`:

```json
{"ts":"2026-04-07T00:07:55Z","type":"CONNECTION","event":"OPEN","src":"192.168.65.1","port":2222,"mapping":"sftp-gateway","tier":"AI","verdict":"ALLOW","risk":12,"protocol":"SSH","detail":"connected"}
{"ts":"2026-04-07T00:07:55Z","type":"VERDICT","src":"192.168.65.1","port":2222,"mapping":"sftp-gateway","action":"ALLOW","risk":12,"reason":"low_risk_ip","llmUsed":false}
{"ts":"2026-04-07T00:08:01Z","type":"CONNECTION","event":"CLOSE","src":"192.168.65.1","port":2222,"mapping":"sftp-gateway","tier":"AI","verdict":"ALLOW","risk":12,"protocol":"SSH","detail":"duration=6s,bytesIn=4096,bytesOut=2048"}
```

Audit files rotate daily and by size (100MB). Retained for 90 days. JSONL format for SIEM ingestion.

## Configuration

### DMZ Proxy (application.yml)
```yaml
dmz:
  mappings:
    - name: sftp-gateway
      listen-port: 2222
      target-host: gateway-service
      target-port: 2220
      proxy-protocol-enabled: true
      audit-enabled: true

  security:
    enabled: true
    ai-engine-url: http://ai-engine:8091
    verdict-timeout-ms: 200
    default-rate-limit: 60
    default-max-concurrent: 20
```

### Docker Compose Port Mapping
```yaml
dmz-proxy:
  ports:
    - "32222:2222"   # SFTP via proxy
```

## Verified Test Results

```
$ sshpass -p 'B0bSecure!Pass' sftp -P 32222 -o StrictHostKeyChecking=no bob-sftp@127.0.0.1
Connected to 127.0.0.1.
sftp> cd inbox
sftp> put /tmp/test-invoice-proxy.csv
Uploading /tmp/test-invoice-proxy.csv to /inbox/test-invoice-proxy.csv
sftp> ls
test-invoice-proxy.csv
```

Connection path: Client → :32222 → DMZ Proxy (9 security layers) → Gateway :2220 → SFTP Server :2222

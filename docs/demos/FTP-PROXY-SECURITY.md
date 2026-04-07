# FTP Server Security via DMZ Proxy

How TranzFer MFT secures FTP servers using the DMZ reverse proxy — covering the FTP-specific security challenges, command filtering, and passive mode handling.

## Architecture Overview

```
                          DMZ Zone                           Internal Zone
                    ┌─────────────────────┐            ┌──────────────────────┐
  FTP Client ───────┤  DMZ Proxy (:32121) ├────────────┤  Gateway (:2122)     │
  (Internet)        │                     │            │       │              │
                    │  9 Security Layers  │            │       ├── FTP-1 (:21)│
                    │  + FTP Command      │            │       ├── FTP-2      │
                    │    Filtering        │            │       └── FTP-3      │
                    │  + DPI for FTP      │            │                      │
                    └─────────────────────┘            └──────────────────────┘
```

**Why FTP needs extra protection:** Unlike SFTP (which tunnels everything through encrypted SSH), FTP transmits commands in plaintext on the control channel. This makes it vulnerable to command injection, FTP bounce attacks, and data exfiltration via covert channels. The DMZ proxy adds FTP-aware security on top of the standard security pipeline.

## FTP-Specific Security Layers

### 1. FTP Command Filtering

The proxy inspects every FTP control channel command in real-time:

**Blocked Commands:**
| Command | Risk | Reason |
|---------|------|--------|
| `SITE` | High | Arbitrary server-side command execution |
| `DELE` | High | Unauthorized file deletion |
| `RMD` | High | Directory removal |
| `RNFR/RNTO` | Medium | Unauthorized file renaming |
| `PORT` (to non-client IP) | Critical | FTP bounce attack — using server as proxy |

**FTP Bounce Attack Prevention:**
The classic FTP bounce attack uses `PORT` to make the server connect to arbitrary hosts:
```
> PORT 10,0,0,5,0,80    ← Attacker tells FTP server to connect to 10.0.0.5:80
> RETR /etc/passwd       ← Server sends file to 10.0.0.5:80 instead of client
```

The proxy:
1. Parses PORT/EPRT commands
2. Extracts target IP from the command
3. Compares against the client's source IP
4. **Blocks** if target IP differs from source IP
5. Audit logs the blocked bounce attempt

### 2. Deep Packet Inspection for FTP

Beyond command filtering, the DPI engine inspects FTP traffic for:
- **Malformed commands:** Oversized arguments, non-ASCII control characters
- **Directory traversal:** `RETR ../../../etc/passwd`, `CWD ../../../../`
- **Data exfiltration patterns:** Unusual file names, encoded payloads in STOR commands
- **Protocol violations:** Out-of-sequence commands, invalid state transitions

### 3. Passive Mode (PASV/EPSV) Considerations

FTP passive mode is inherently complex for proxying because the server assigns a random data port for each transfer:

```
Client                    Proxy                    Gateway/FTP Server
  │                         │                            │
  │──── PASV ──────────────→│──── PASV ─────────────────→│
  │                         │                            │
  │←── 227 (proxy_ip,port)──│←── 227 (server_ip,port) ──│
  │                         │                            │
  │──── DATA CONN ─────────→│──── DATA CONN ────────────→│
  │                         │    (to server's data port)  │
```

**Current behavior:** The DMZ proxy relays the control channel. The PASV response from the gateway contains the gateway's IP/port, which the client connects to directly (since the gateway is behind the proxy, this requires the gateway to handle passive data connections within its own FTP proxy implementation).

**Security implications:**
- Data channel connections are separate TCP streams
- Each data channel goes through the full security pipeline if routed through the proxy
- The gateway's FTP proxy rewrites PASV responses with its own address

## Standard Security Pipeline (shared with SFTP)

The FTP mapping goes through the identical 9-layer security pipeline as SFTP before the FTP-specific layers fire:

| Layer | Component | What It Does |
|-------|-----------|--------------|
| 1 | TLS Termination | FTPS (FTP over TLS) if configured |
| 2a | Manual Filter | IP whitelist/blacklist, geo-blocking |
| 2b | Rate Limiter | Per-IP connection/byte limits |
| 2c | AI Verdict | ML-based threat assessment |
| 2d | Protocol Detection | Identifies FTP from first bytes (`220 `) |
| 2e | DPI | Deep packet inspection |
| 3 | Health Check | Backend FTP gateway alive? |
| 4 | Zone Enforcement | EXTERNAL → DMZ allowed |
| 5 | Egress Filter | Backend target pre-validated |
| 6 | FTP Command Filter | FTP-specific command blocking |
| 7 | Backend Relay | PROXY protocol + bidirectional relay |

## FTPS (FTP over TLS)

When TLS termination is enabled for the FTP mapping:

```yaml
dmz:
  mappings:
    - name: ftp-gateway
      listen-port: 21
      target-host: gateway-service
      target-port: 2122
      tls-policy:
        enabled: true
        cert-path: /certs/ftp-cert.pem
        key-path: /certs/ftp-key.pem
        min-tls-version: TLSv1.2
        require-client-cert: false
```

- Explicit FTPS (`AUTH TLS`): Client upgrades control channel to TLS
- The proxy terminates TLS, inspects FTP commands in plaintext, then re-encrypts to backend if needed
- This enables DPI and command filtering even over encrypted connections

## Connection Lifecycle

### Phase 1: TCP Accept + Security (~0-50ms)
Same as SFTP — 9-layer security pipeline processes the connection.

### Phase 2: FTP Banner Relay
```
1. Backend sends: 220 File Transfer Gateway Ready
2. Proxy relays banner to client (backendCh.read() triggers this)
3. Client sees the 220 banner and begins FTP dialog
```

### Phase 3: FTP Command Exchange
```
Client → Proxy → Gateway → FTP Server
  USER bob-ftp →
  ← 331 User name okay, need password
  PASS ******* →
  ← 230 User logged in
  PWD →
  ← 257 "/" is current directory
  PASV →
  ← 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
  LIST →
  ← 150 Here comes the directory listing
  ← (data channel: directory listing)
  ← 226 Directory send OK
```

Each command passes through DPI and FTP command filtering before forwarding.

### Phase 4: File Transfer
```
  STOR invoice.csv →       (checked by DPI + file extension filter)
  ← 150 Ok to send data
  → (data channel: file content, byte-rate limited)
  ← 226 Transfer complete
```

### Phase 5: Audit + Teardown
- QUIT command forwarded, 221 response relayed
- Connection duration, bytes, commands logged
- Rate limiter releases connection slot

## FTP vs SFTP Security Comparison

| Aspect | SFTP via Proxy | FTP via Proxy |
|--------|---------------|---------------|
| Encryption | SSH tunnel (always) | Optional (FTPS) |
| Command visibility | Encrypted — DPI limited | Plaintext — full DPI |
| Auth exposure | Encrypted in SSH | PASS in cleartext (without TLS) |
| Bounce attacks | N/A (no PORT concept) | Actively prevented |
| Command filtering | N/A (SFTP binary) | Full command inspection |
| Data channel | Single connection | Separate PASV/PORT connections |
| Protocol detection | `SSH-` prefix | `220 ` banner |
| Security overhead | Same (~1ms cached) | Same + command parsing |

## Resilience

All resilience mechanisms from the SFTP proxy apply equally:
- **AI engine down:** Conservative ALLOW fallback
- **Backend FTP server down:** Instant reject via health check
- **Backpressure:** AUTO_READ=false flow control
- **Connection draining:** Graceful shutdown

## Configuration

### DMZ Proxy (application.yml)
```yaml
dmz:
  mappings:
    - name: ftp-gateway
      listen-port: 21
      target-host: ${GATEWAY_HOST:gateway-service}
      target-port: ${GATEWAY_FTP_PORT:2122}
      proxy-protocol-enabled: true
      audit-enabled: true
```

### Docker Compose
```yaml
dmz-proxy:
  ports:
    - "32121:21"     # FTP control channel via proxy
```

## Verified Test Results

```
$ curl -v -u bob-ftp:password ftp://127.0.0.1:32121/
* Connected to 127.0.0.1 port 32121
< 220 File Transfer Gateway Ready       ← Banner relayed through proxy
> USER bob-ftp
< 220 Service ready for new user.
< 331 User name okay, need password     ← FTP dialog flowing through proxy
```

Connection path: Client → :32121 → DMZ Proxy (9 security layers + FTP filters) → Gateway :2122 → FTP Server :21

## Security Recommendations

1. **Always enable FTPS** — FTP credentials are plaintext without TLS
2. **Restrict PASV port range** — Minimize exposed data channel ports
3. **Use AI tier for FTP** — FTP's larger attack surface benefits from AI analysis
4. **Block SITE/DELE commands** — Default policy should deny destructive operations
5. **Prefer SFTP over FTP** — When possible, migrate to SFTP for inherently encrypted transfers
6. **Enable audit logging** — FTP command history is critical for compliance (SOX, HIPAA)

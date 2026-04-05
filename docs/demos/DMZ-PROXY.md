# DMZ Proxy -- Demo & Quick Start Guide

> AI-powered TCP reverse proxy that sits in your DMZ, detects protocols, rate-limits connections, and integrates with an AI verdict engine -- zero dependencies, single JAR.

---

## What This Service Does

- **TCP reverse proxy** -- Maps external ports (SFTP on 2222, FTP on 21, HTTPS on 443) to internal services, keeping backend servers completely hidden from the internet
- **Protocol detection** -- Identifies SSH, FTP, FTPS, HTTP, and TLS from the first bytes of every connection, without requiring configuration per port
- **Per-IP rate limiting** -- Token bucket algorithm with per-IP connection rates, concurrent connection caps, and byte-per-minute limits to prevent abuse
- **AI verdict integration** -- Queries an AI engine for allow/throttle/block/blackhole decisions on every new connection, with sub-200ms timeout and graceful fallback
- **Security metrics and monitoring** -- Real-time counters for total/allowed/blocked/throttled connections, protocol distribution, per-port stats, and byte throughput

## What You Need (Prerequisites Checklist)

- [ ] **Operating System:** Linux, macOS, or Windows
- [ ] **Docker** (recommended) OR **Java 21 + Maven** -- [Install guide](PREREQUISITES.md)
- [ ] **curl** (for testing the management API) -- pre-installed on Linux/macOS; Windows: `winget install cURL.cURL`
- [ ] **Ports available:** `8088` (management API), plus any proxied ports you configure (defaults: `2222`, `21`, `443`)

No database. No message broker. The AI engine integration is optional and degrades gracefully.

## Install & Start

### Method 1: Docker (Any OS -- 30 Seconds)

```bash
docker run -d \
  --name dmz-proxy \
  -p 8088:8088 \
  -p 2222:2222 \
  -p 2121:21 \
  -p 8443:443 \
  -e CONTROL_API_KEY=my-secret-key \
  -e GATEWAY_HOST=host.docker.internal \
  -e FTPWEB_HOST=host.docker.internal \
  -e DMZ_SECURITY_ENABLED=true \
  ghcr.io/rdship/tranzfer-mft/dmz-proxy:latest
```

> **Note:** `host.docker.internal` lets the container reach services running on your host machine. On Linux, you may need to add `--add-host=host.docker.internal:host-gateway`.

### Method 2: Docker Compose (With Backend Services)

If you want to proxy traffic to real backend services, use this minimal compose file:

```yaml
# docker-compose-dmz-demo.yml
services:
  dmz-proxy:
    image: ghcr.io/rdship/tranzfer-mft/dmz-proxy:latest
    ports:
      - "8088:8088"   # Management API
      - "2222:2222"   # SFTP proxy
      - "2121:21"     # FTP proxy
      - "8443:443"    # HTTPS proxy
    environment:
      CONTROL_API_KEY: my-secret-key
      GATEWAY_HOST: gateway-service
      FTPWEB_HOST: ftp-web-service
      DMZ_SECURITY_ENABLED: "true"
      AI_ENGINE_URL: "http://ai-engine:8091"
```

```bash
docker compose -f docker-compose-dmz-demo.yml up -d
```

### Method 3: From Source (Any OS)

```bash
# Clone (if not done)
git clone https://github.com/rdship/tranzfer-mft.git
cd tranzfer-mft

# Build just the DMZ Proxy
mvn clean package -DskipTests -pl dmz-proxy -am

# Run with a custom control API key
CONTROL_API_KEY=my-secret-key java -jar dmz-proxy/target/dmz-proxy-1.0.0-SNAPSHOT.jar
```

You should see output ending with:

```
Started DmzProxyApplication in X.XXX seconds
```

## Verify It's Running

The health endpoint does not require the `X-Internal-Key` header:

```bash
curl -s http://localhost:8088/api/proxy/health
```

Expected output:

```json
{
  "status": "UP",
  "service": "dmz-proxy",
  "activeMappings": 3,
  "securityEnabled": true,
  "aiEngineAvailable": false,
  "activeConnections": 0,
  "totalConnections": 0,
  "features": [
    "protocol_detection",
    "ai_verdict",
    "rate_limiting",
    "connection_tracking",
    "threat_event_reporting",
    "adaptive_rate_limits",
    "graceful_degradation"
  ]
}
```

> **Note:** `aiEngineAvailable` will be `false` unless you have the AI Engine service running at the configured URL. This is expected -- the proxy degrades gracefully and still provides rate limiting and protocol detection without it.

---

## Demo 1: View and Manage Port Mappings

All management endpoints require the `X-Internal-Key` header. The default key is `internal_control_secret` (override with the `CONTROL_API_KEY` environment variable).

### Step 1: List current port mappings

```bash
curl -s http://localhost:8088/api/proxy/mappings \
  -H "X-Internal-Key: my-secret-key" | python3 -m json.tool
```

Windows (PowerShell):

```powershell
Invoke-RestMethod -Uri http://localhost:8088/api/proxy/mappings `
  -Headers @{ "X-Internal-Key" = "my-secret-key" } | ConvertTo-Json -Depth 5
```

Expected output:

```json
[
  {
    "name": "sftp-gateway",
    "listenPort": 2222,
    "targetHost": "gateway-service",
    "targetPort": 2220,
    "active": true,
    "connections": 0,
    "bytesIn": 0,
    "bytesOut": 0
  },
  {
    "name": "ftp-gateway",
    "listenPort": 21,
    "targetHost": "gateway-service",
    "targetPort": 2121,
    "active": true,
    "connections": 0,
    "bytesIn": 0,
    "bytesOut": 0
  },
  {
    "name": "ftp-web",
    "listenPort": 443,
    "targetHost": "ftp-web-service",
    "targetPort": 8083,
    "active": true,
    "connections": 0,
    "bytesIn": 0,
    "bytesOut": 0
  }
]
```

### Step 2: Hot-add a new port mapping (no restart needed)

```bash
curl -s -X POST http://localhost:8088/api/proxy/mappings \
  -H "X-Internal-Key: my-secret-key" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "https-api-gateway",
    "listenPort": 9443,
    "targetHost": "api-gateway",
    "targetPort": 8080
  }'
```

Expected output:

```json
{
  "name": "https-api-gateway",
  "listenPort": 9443,
  "targetHost": "api-gateway",
  "targetPort": 8080,
  "active": true
}
```

### Step 3: Remove a port mapping (hot-remove)

```bash
curl -s -X DELETE http://localhost:8088/api/proxy/mappings/https-api-gateway \
  -H "X-Internal-Key: my-secret-key"
```

Returns HTTP 204 No Content on success.

### Step 4: Verify what happens without the key

```bash
curl -s -w "\nHTTP Status: %{http_code}\n" \
  http://localhost:8088/api/proxy/mappings
```

Expected output:

```
HTTP Status: 401
```

---

## Demo 2: The Security Pipeline in Action

This demo walks through the full security stack: rate limiting, connection tracking, protocol detection, and AI verdicts.

### Step 1: View the full security dashboard

```bash
curl -s http://localhost:8088/api/proxy/security/stats \
  -H "X-Internal-Key: my-secret-key" | python3 -m json.tool
```

Expected output:

```json
{
  "securityEnabled": true,
  "metrics": {
    "upSince": "2026-04-05T10:00:00Z",
    "uptimeSeconds": 120,
    "connections": {
      "total": 0,
      "allowed": 0,
      "throttled": 0,
      "blocked": 0,
      "blackholed": 0,
      "rateLimited": 0
    },
    "throughput": {
      "totalBytesIn": 0,
      "totalBytesOut": 0,
      "totalBytes": 0
    },
    "aiEngine": {
      "verdictRequests": 0,
      "cacheHits": 0,
      "fallbacks": 0
    },
    "protocols": {},
    "ports": {}
  },
  "connections": {
    "trackedIps": 0,
    "activeConnections": 0,
    "totalConnections": 0
  },
  "rateLimiter": {
    "trackedIps": 0,
    "defaultMaxPerMinute": 60,
    "defaultMaxConcurrent": 20,
    "defaultMaxBytesPerMinute": 500000000,
    "globalMaxPerMinute": 10000,
    "globalTokensRemaining": 10000
  },
  "aiEngine": {
    "available": false,
    "verdictCacheSize": 0,
    "pendingEvents": 0
  }
}
```

### Step 2: View the security summary (compact version)

```bash
curl -s http://localhost:8088/api/proxy/security/summary \
  -H "X-Internal-Key: my-secret-key" | python3 -m json.tool
```

Expected output:

```json
{
  "securityEnabled": true,
  "aiEngineAvailable": false,
  "connectionSummary": {
    "total": 0,
    "allowed": 0,
    "blocked": 0,
    "throttled": 0,
    "rateLimited": 0
  },
  "trackedIps": 0,
  "activeConnections": 0,
  "verdictCacheSize": 0
}
```

### Step 3: Simulate traffic and check per-IP details

If you have an SFTP client, connect to the proxy to generate traffic:

```bash
# This will fail to authenticate (no backend), but the proxy will track the connection
sftp -P 2222 testuser@localhost <<< "exit" 2>/dev/null || true
```

Then check the IP details:

```bash
curl -s http://localhost:8088/api/proxy/security/ip/127.0.0.1 \
  -H "X-Internal-Key: my-secret-key" | python3 -m json.tool
```

Expected output (if connection was tracked):

```json
{
  "ip": "127.0.0.1",
  "totalConnections": 1,
  "activeConnections": 0,
  "firstSeen": "2026-04-05T10:05:00Z",
  "lastSeen": "2026-04-05T10:05:01Z",
  "protocols": ["SSH"],
  "ports": [2222],
  "verdict": "ALLOW",
  "bytesIn": 1024,
  "bytesOut": 512
}
```

If the IP has not been seen yet:

```json
{
  "ip": "127.0.0.1",
  "status": "not_tracked"
}
```

### Step 4: Check rate limiter state

```bash
curl -s http://localhost:8088/api/proxy/security/rate-limits \
  -H "X-Internal-Key: my-secret-key" | python3 -m json.tool
```

Expected output:

```json
{
  "trackedIps": 0,
  "defaultMaxPerMinute": 60,
  "defaultMaxConcurrent": 20,
  "defaultMaxBytesPerMinute": 500000000,
  "globalMaxPerMinute": 10000,
  "globalTokensRemaining": 9998
}
```

### Step 5: Check connection tracker

```bash
curl -s http://localhost:8088/api/proxy/security/connections \
  -H "X-Internal-Key: my-secret-key" | python3 -m json.tool
```

---

## Demo 3: Integration Pattern -- Monitoring Dashboard

### Python -- Security Monitoring Script

```python
import requests
import time

DMZ_URL = "http://localhost:8088/api/proxy"
HEADERS = {"X-Internal-Key": "my-secret-key"}

def check_dmz_health():
    """Poll DMZ proxy health and security metrics."""
    health = requests.get(f"{DMZ_URL}/health").json()
    print(f"Status: {health['status']}, Active Mappings: {health['activeMappings']}")
    print(f"Security: {'ENABLED' if health.get('securityEnabled') else 'DISABLED'}")

    if health.get("securityEnabled"):
        stats = requests.get(f"{DMZ_URL}/security/stats", headers=HEADERS).json()
        metrics = stats.get("metrics", {})
        conns = metrics.get("connections", {})
        print(f"Connections: {conns.get('total', 0)} total, "
              f"{conns.get('blocked', 0)} blocked, "
              f"{conns.get('rateLimited', 0)} rate-limited")

        # Check for suspicious IPs
        summary = requests.get(f"{DMZ_URL}/security/summary", headers=HEADERS).json()
        if summary.get("trackedIps", 0) > 100:
            print("WARNING: High number of tracked IPs -- possible scan in progress")

check_dmz_health()
```

### Java -- Health Check Integration

```java
import java.net.http.*;
import java.net.URI;

HttpClient client = HttpClient.newHttpClient();

// Check health (no auth required)
HttpRequest healthReq = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:8088/api/proxy/health"))
    .GET().build();

HttpResponse<String> healthResp = client.send(healthReq, HttpResponse.BodyHandlers.ofString());
System.out.println("DMZ Health: " + healthResp.body());

// Check security stats (auth required)
HttpRequest statsReq = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:8088/api/proxy/security/stats"))
    .header("X-Internal-Key", "my-secret-key")
    .GET().build();

HttpResponse<String> statsResp = client.send(statsReq, HttpResponse.BodyHandlers.ofString());
System.out.println("Security Stats: " + statsResp.body());
```

### Node.js -- Automated Port Mapping Management

```javascript
const DMZ_URL = "http://localhost:8088/api/proxy";
const HEADERS = {
  "X-Internal-Key": "my-secret-key",
  "Content-Type": "application/json"
};

// Add a new port mapping at runtime
async function addMapping(name, listenPort, targetHost, targetPort) {
  const response = await fetch(`${DMZ_URL}/mappings`, {
    method: "POST",
    headers: HEADERS,
    body: JSON.stringify({ name, listenPort, targetHost, targetPort })
  });

  if (response.status === 201) {
    console.log(`Mapping '${name}' created: port ${listenPort} -> ${targetHost}:${targetPort}`);
  } else {
    console.error(`Failed to create mapping: ${response.status}`);
  }
}

// List all mappings
async function listMappings() {
  const response = await fetch(`${DMZ_URL}/mappings`, { headers: HEADERS });
  const mappings = await response.json();
  console.log("Active mappings:");
  mappings.forEach(m =>
    console.log(`  ${m.name}: :${m.listenPort} -> ${m.targetHost}:${m.targetPort} (${m.active ? "UP" : "DOWN"})`)
  );
}

// Example: add a temporary mapping for a migration
await addMapping("migration-sftp", 2223, "migration-server", 22);
await listMappings();
```

---

## Use Cases

- **DMZ isolation** -- Place the proxy in a network DMZ so backend SFTP/FTP servers never have public IP addresses
- **Protocol-agnostic ingress** -- Accept SFTP, FTP, FTPS, and HTTPS on standard ports and route each to the correct internal service
- **DDoS mitigation** -- Per-IP and global rate limiting stops volumetric attacks before they reach backend services
- **Connection auditing** -- Track every IP, protocol, byte count, and connection duration for compliance (PCI-DSS, SOX)
- **Hot reconfiguration** -- Add or remove port mappings at runtime via the REST API without any downtime
- **AI-powered threat response** -- Integrate with the AI Engine to automatically block, throttle, or blackhole suspicious connections based on behavioral analysis
- **Multi-tenant isolation** -- Run multiple proxy instances with different port mappings for different tenants or environments
- **Zero-trust network architecture** -- Every connection is authenticated by protocol detection + AI verdict before being forwarded

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8088` | Management REST API port |
| `CONTROL_API_KEY` | `internal_control_secret` | Secret key for `X-Internal-Key` header on management endpoints |
| `GATEWAY_HOST` | `gateway-service` | Hostname of the SFTP/FTP gateway backend |
| `FTPWEB_HOST` | `ftp-web-service` | Hostname of the FTP web UI backend |
| `DMZ_SECURITY_ENABLED` | `true` | Enable/disable the AI security layer |
| `AI_ENGINE_URL` | `http://ai-engine:8091` | URL of the AI Engine for verdict queries |
| `VERDICT_TIMEOUT_MS` | `200` | Timeout (ms) for AI verdict queries; falls back to ALLOW on timeout |
| `DEFAULT_RATE_PER_MINUTE` | `60` | Default max connections per IP per minute |
| `DEFAULT_MAX_CONCURRENT` | `20` | Default max concurrent connections per IP |
| `DEFAULT_MAX_BYTES_PER_MINUTE` | `500000000` | Default max bytes per IP per minute (500 MB) |
| `GLOBAL_RATE_PER_MINUTE` | `10000` | Global max connections per minute (DDoS threshold) |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label (PROD, STAGING, DEV) |

## Default Port Mappings

These are configured at startup via `application.yml` and can be changed via environment variables or the REST API:

| Name | Listen Port | Target Host | Target Port | Purpose |
|------|-------------|-------------|-------------|---------|
| `sftp-gateway` | 2222 | `gateway-service` | 2220 | SFTP file transfers |
| `ftp-gateway` | 21 | `gateway-service` | 2121 | FTP file transfers |
| `ftp-web` | 443 | `ftp-web-service` | 8083 | HTTPS web interface |

## API Reference (All Endpoints)

### No Authentication Required

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/proxy/health` | Health check with security status and feature list |

### Requires `X-Internal-Key` Header

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/proxy/mappings` | List all port mappings with live connection stats |
| `POST` | `/api/proxy/mappings` | Hot-add a new port mapping (returns 201) |
| `DELETE` | `/api/proxy/mappings/{name}` | Hot-remove a port mapping (returns 204) |
| `GET` | `/api/proxy/security/stats` | Full security metrics (connections, throughput, AI engine, protocols, ports) |
| `GET` | `/api/proxy/security/summary` | Compact security summary |
| `GET` | `/api/proxy/security/connections` | Connection tracker statistics |
| `GET` | `/api/proxy/security/ip/{ip}` | Per-IP security details (connections, protocols, verdict, bytes) |
| `GET` | `/api/proxy/security/rate-limits` | Rate limiter state (tracked IPs, limits, remaining tokens) |

## Security Architecture

The DMZ Proxy processes every inbound TCP connection through a multi-stage security pipeline:

```
Inbound Connection
       |
       v
  [Rate Limiter] --- per-IP token bucket (60 conn/min, 20 concurrent)
       |               |
       |          RATE_LIMITED --> connection dropped
       v
  [Protocol Detector] --- reads first bytes: SSH? FTP? TLS? HTTP?
       |
       v
  [AI Verdict Client] --- asks AI Engine: ALLOW / THROTTLE / BLOCK / BLACKHOLE
       |                     |
       |                 timeout (200ms) --> graceful fallback to ALLOW
       v
  [Connection Tracker] --- records IP, protocol, port, bytes, timestamps
       |
       v
  [TCP Proxy] --- forwards to internal target host:port
       |
       v
  [Security Metrics] --- counters for dashboards and alerting
```

**Graceful degradation:** If the AI Engine is unreachable, the proxy continues to operate with rate limiting and protocol detection. No single-point-of-failure.

---

## Demo 4: Protecting an External Product (Third-Party Integration)

This demo shows how a **completely separate product** (not part of TranzFer) can use the DMZ Proxy to add AI-powered security, rate limiting, and protocol detection to its own services. We'll protect:
- A standalone **Nginx web server** (representing any HTTP product)
- A standalone **OpenSSH server** (representing any SSH/SFTP product)

### Step 1: Start the External Products

These represent your existing infrastructure that you want to protect:

```yaml
# Save as: docker-compose-external-demo.yml
services:
  # ── YOUR EXISTING PRODUCTS (nothing to do with TranzFer) ──────────

  # A simple web application (could be any HTTP service)
  my-web-app:
    image: nginx:alpine
    container_name: my-web-app
    # NOT exposed to the internet — only reachable internally

  # An SSH/SFTP server (could be any SSH-based service)
  my-sftp-server:
    image: atmoz/sftp:alpine
    container_name: my-sftp-server
    command: demo_user:demo_pass:1001
    # NOT exposed to the internet — only reachable internally

  # ── TRANZFER DMZ PROXY (the security layer) ──────────────────────

  dmz-proxy:
    build: ./dmz-proxy       # or use: image: ghcr.io/rdship/tranzfer-mft/dmz-proxy:latest
    container_name: dmz-proxy
    ports:
      - "8088:8088"          # Management API
      - "443:443"            # Public HTTPS → proxied to my-web-app
      - "2222:2222"          # Public SFTP → proxied to my-sftp-server
    environment:
      CONTROL_API_KEY: my-secret-key-123
      DMZ_SECURITY_ENABLED: "true"
      # Override default mappings to point to OUR services
      GATEWAY_HOST: my-sftp-server
      FTPWEB_HOST: my-web-app

  # ── OPTIONAL: AI Engine for intelligent threat detection ──────────

  ai-engine:
    build: ./ai-engine
    container_name: ai-engine
    ports:
      - "8091:8091"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      SERVER_PORT: 8091
    depends_on:
      postgres:
        condition: service_healthy

  postgres:
    image: postgres:16-alpine
    container_name: demo-postgres
    environment:
      POSTGRES_DB: filetransfer
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 3s
      retries: 5
```

```bash
# Start everything
docker compose -f docker-compose-external-demo.yml up -d

# Wait for services to be ready
sleep 10
```

### Step 2: Replace Default Mappings with Your Own

The DMZ Proxy starts with default TranzFer mappings. Replace them with mappings to your products:

```bash
# Remove the default mappings
curl -s -X DELETE http://localhost:8088/api/proxy/mappings/sftp-gateway \
  -H "X-Internal-Key: my-secret-key-123"

curl -s -X DELETE http://localhost:8088/api/proxy/mappings/ftp-gateway \
  -H "X-Internal-Key: my-secret-key-123"

curl -s -X DELETE http://localhost:8088/api/proxy/mappings/ftp-web \
  -H "X-Internal-Key: my-secret-key-123"

# Add mapping: Public port 443 → your Nginx web app on port 80
curl -s -X POST http://localhost:8088/api/proxy/mappings \
  -H "X-Internal-Key: my-secret-key-123" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-web-app",
    "listenPort": 443,
    "targetHost": "my-web-app",
    "targetPort": 80
  }' | python3 -m json.tool
```

Expected output:

```json
{
  "name": "my-web-app",
  "listenPort": 443,
  "targetHost": "my-web-app",
  "targetPort": 80,
  "active": true
}
```

```bash
# Add mapping: Public port 2222 → your SFTP server on port 22
curl -s -X POST http://localhost:8088/api/proxy/mappings \
  -H "X-Internal-Key: my-secret-key-123" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-sftp-server",
    "listenPort": 2222,
    "targetHost": "my-sftp-server",
    "targetPort": 22
  }' | python3 -m json.tool
```

```bash
# Verify your custom mappings are active
curl -s http://localhost:8088/api/proxy/mappings \
  -H "X-Internal-Key: my-secret-key-123" | python3 -m json.tool
```

Expected output:

```json
[
  {
    "name": "my-web-app",
    "listenPort": 443,
    "targetHost": "my-web-app",
    "targetPort": 80,
    "active": true,
    "bytesForwarded": 0,
    "activeConnections": 0,
    "securityEnabled": true
  },
  {
    "name": "my-sftp-server",
    "listenPort": 2222,
    "targetHost": "my-sftp-server",
    "targetPort": 22,
    "active": true,
    "bytesForwarded": 0,
    "activeConnections": 0,
    "securityEnabled": true
  }
]
```

### Step 3: Access Your Products Through the Secure Proxy

Every connection now flows through the DMZ Proxy security pipeline (rate limiting, protocol detection, AI verdicts) before reaching your services.

**Access the web app (HTTP through port 443):**

```bash
# Linux / macOS
curl -s http://localhost:443/

# Windows (PowerShell)
Invoke-WebRequest -Uri http://localhost:443/ | Select-Object -ExpandProperty Content
```

You should see the Nginx welcome page HTML. The DMZ Proxy detected the HTTP protocol and forwarded to your Nginx container.

**Access the SFTP server (SSH through port 2222):**

```bash
# Linux / macOS — connect via SFTP through the proxy
sftp -P 2222 -o StrictHostKeyChecking=no demo_user@localhost
# Password: demo_pass
# You're now in the SFTP server, proxied through the DMZ security layer

# Upload a test file
echo "test data" > /tmp/test-upload.txt
sftp -P 2222 -o StrictHostKeyChecking=no -b - demo_user@localhost <<'EOF'
put /tmp/test-upload.txt /upload/test-upload.txt
ls /upload/
bye
EOF
```

```bash
# Windows (PowerShell) — using OpenSSH client (built into Windows 10+)
sftp -P 2222 demo_user@localhost
# Password: demo_pass
```

### Step 4: Monitor Security — See the Proxy Protecting Your Products

```bash
# Check what the proxy has seen
curl -s http://localhost:8088/api/proxy/security/stats \
  -H "X-Internal-Key: my-secret-key-123" | python3 -m json.tool
```

Expected output (after a few connections):

```json
{
  "securityEnabled": true,
  "metrics": {
    "uptimeSeconds": 120,
    "connections": {
      "total": 5,
      "allowed": 5,
      "throttled": 0,
      "blocked": 0,
      "blackholed": 0,
      "rateLimited": 0,
      "blockRate": "0.00%"
    },
    "protocols": {
      "HTTP": 2,
      "SSH": 3
    },
    "ports": {
      "443": 2,
      "2222": 3
    }
  }
}
```

```bash
# Check your own IP's security profile
curl -s http://localhost:8088/api/proxy/security/ip/127.0.0.1 \
  -H "X-Internal-Key: my-secret-key-123" | python3 -m json.tool
```

Expected output:

```json
{
  "ip": "127.0.0.1",
  "activeConnections": 0,
  "totalConnections": 5,
  "rejectedConnections": 0,
  "bytesIn": 2048,
  "bytesOut": 15360,
  "portsUsed": [443, 2222],
  "detectedProtocol": "SSH",
  "lastSeen": "2026-04-05T10:05:00Z"
}
```

### Step 5: Simulate an Attack — See Rate Limiting in Action

```bash
# Rapid-fire 100 connections in 5 seconds (exceeds 60/min default)
for i in $(seq 1 100); do
  curl -s -o /dev/null -w "%{http_code} " http://localhost:443/ &
done
wait
echo ""

# Check how many were rate-limited
curl -s http://localhost:8088/api/proxy/security/rate-limits \
  -H "X-Internal-Key: my-secret-key-123" | python3 -m json.tool
```

```bash
# Windows (PowerShell) equivalent
1..100 | ForEach-Object { Start-Job { Invoke-WebRequest -Uri http://localhost:443/ -UseBasicParsing } }
Get-Job | Wait-Job | Remove-Job

Invoke-RestMethod -Uri http://localhost:8088/api/proxy/security/rate-limits `
  -Headers @{ "X-Internal-Key" = "my-secret-key-123" }
```

```bash
# View the security summary — blocked connections should appear
curl -s http://localhost:8088/api/proxy/security/summary \
  -H "X-Internal-Key: my-secret-key-123" | python3 -m json.tool
```

Expected output:

```json
{
  "securityEnabled": true,
  "aiEngineAvailable": true,
  "connectionSummary": {
    "total": 105,
    "allowed": 65,
    "blocked": 0,
    "throttled": 0,
    "rateLimited": 40
  },
  "trackedIps": 1,
  "activeConnections": 0,
  "verdictCacheSize": 1
}
```

### Step 6: Add More Products Dynamically (Hot Add)

The best part — add new protected services **without restarting anything**:

```bash
# Suppose you just launched a new internal API on port 3000
# Expose it securely on public port 9443 through the DMZ proxy

curl -s -X POST http://localhost:8088/api/proxy/mappings \
  -H "X-Internal-Key: my-secret-key-123" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-internal-api",
    "listenPort": 9443,
    "targetHost": "my-api-server.internal",
    "targetPort": 3000
  }' | python3 -m json.tool

# The new mapping is live immediately — no restart, no downtime
```

### Why This Matters for External Products

| Without DMZ Proxy | With DMZ Proxy |
|-------------------|----------------|
| Backend servers exposed directly to internet | Backend servers hidden behind proxy |
| No rate limiting | Per-IP token bucket (60 conn/min, 20 concurrent) |
| No protocol awareness | Auto-detects SSH, FTP, HTTP, TLS |
| No threat intelligence | AI verdicts: ALLOW / THROTTLE / BLOCK / BLACKHOLE |
| Manual firewall rules | Dynamic port mappings via REST API |
| No connection visibility | Real-time per-IP monitoring and metrics |
| Restart to add services | Hot-add new mappings without downtime |

### Cleanup (External Demo)

```bash
docker compose -f docker-compose-external-demo.yml down -v
```

---

## Cleanup

### Docker

```bash
docker stop dmz-proxy && docker rm dmz-proxy
```

### Docker Compose

```bash
docker compose -f docker-compose-dmz-demo.yml down
```

### From source

Press `Ctrl+C` in the terminal where the JAR is running.

## Troubleshooting

### Linux

**Port 21 requires root (privileged port):**

```bash
# Option 1: Run as root (not recommended for production)
sudo java -jar dmz-proxy-1.0.0-SNAPSHOT.jar

# Option 2: Use a non-privileged port and redirect with iptables
java -jar dmz-proxy-1.0.0-SNAPSHOT.jar  # listens on 2121 instead
sudo iptables -t nat -A PREROUTING -p tcp --dport 21 -j REDIRECT --to-port 2121

# Option 3: Grant the Java binary the CAP_NET_BIND_SERVICE capability
sudo setcap 'cap_net_bind_service=+ep' $(readlink -f $(which java))
```

**Docker: "host.docker.internal" not resolving:**

```bash
# Add the --add-host flag on Linux
docker run --add-host=host.docker.internal:host-gateway ...
```

**Firewall blocking proxied ports:**

```bash
sudo ufw allow 8088/tcp
sudo ufw allow 2222/tcp
sudo ufw allow 443/tcp
```

### macOS

**Port 443 already in use (by AirPlay Receiver or other services):**

```bash
lsof -i :443
# If it's AirPlay Receiver, disable it in System Settings > General > AirDrop & Handoff
# Or use a different port:
docker run -p 8443:443 ...
```

**Port 8088 already in use:**

```bash
lsof -i :8088
kill -9 <PID>
```

### Windows

**Port 443 already in use (by IIS, Skype, or another service):**

```powershell
netstat -ano | findstr :443
taskkill /PID <PID> /F
# Or map to a different host port:
# docker run -p 8443:443 ...
```

**Port 8088 already in use:**

```powershell
netstat -ano | findstr :8088
taskkill /PID <PID> /F
```

**"java is not recognized":**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
```

### All Platforms

**401 Unauthorized on management endpoints:**

Make sure you are passing the correct `X-Internal-Key` header. The default key is `internal_control_secret`. If you set `CONTROL_API_KEY` at startup, use that value instead.

```bash
# Check what key the service expects
# (it's the value of the CONTROL_API_KEY environment variable)
curl -s http://localhost:8088/api/proxy/mappings \
  -H "X-Internal-Key: internal_control_secret"
```

**Backend service unreachable (connection refused on target):**

The proxy will accept the TCP connection but fail to forward it. Check that the target service is running:

```bash
# Verify target is reachable
curl -s http://gateway-service:2220 2>&1 || echo "Target not reachable"
```

**AI Engine not available (aiEngineAvailable: false):**

This is normal if you are running the DMZ Proxy standalone. The proxy operates fully without the AI Engine -- rate limiting and protocol detection still work. To enable AI verdicts, start the AI Engine service at the URL configured in `AI_ENGINE_URL`.

## What's Next

- **[EDI Converter](EDI-CONVERTER.md)** -- Convert EDI documents that flow through the proxy
- **[Prerequisites](PREREQUISITES.md)** -- Full install guide for Docker and Java 21
- **[Architecture](../ARCHITECTURE.md)** -- How DMZ Proxy fits into the TranzFer MFT platform
- **[Security Architecture](../SECURITY-ARCHITECTURE.md)** -- Deep dive into the platform's security model
- **[API Reference](../API-REFERENCE.md)** -- Complete API documentation for all services

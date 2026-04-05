# SFTP Service — Standalone Product Guide

> **Production SFTP server.** Apache MINA SSHD-based SFTP server with password + public key authentication, user sandboxing, automatic directory provisioning, and file routing integration.

**Port:** 2222 (SFTP) + 8081 (HTTP) | **Dependencies:** PostgreSQL, RabbitMQ | **Auth:** Username/password or SSH public key

---

## Why Use This

- **Production-grade SFTP** — Built on Apache MINA SSHD (battle-tested SSH library)
- **Dual auth** — Password and public key authentication
- **Auth hardening** — Failed-attempt lockout (5 failures triggers 15-min lock), IP allowlist/denylist
- **User sandboxing** — Each user sees only their home directory
- **Auto-provisioning** — Creates inbox/outbox/archive/sent directories per user
- **Connection management** — Global and per-user connection limits, idle timeout, session duration caps, configurable banner
- **File operation controls** — Max upload size, per-user disk quota, allowed/denied file extensions, symlink traversal prevention
- **SSH algorithm configuration** — Restrict ciphers, MACs, and key exchange algorithms for compliance (FIPS, PCI-DSS)
- **Bandwidth throttling** — Per-user upload and download speed limits
- **Audit logging** — Structured JSON events for LOGIN, LOGIN_FAILED, UPLOAD, DOWNLOAD, DELETE, MKDIR, RENAME, DISCONNECT
- **Graceful shutdown** — Drains active connections with configurable timeout before forced disconnect
- **Enhanced health endpoint** — Active connections, disk usage, locked accounts, authentication statistics
- **File routing** — Uploaded files automatically routed through platform flows
- **Event streaming** — Account events published to RabbitMQ

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq sftp-service

# Connect with any SFTP client
sftp -P 2222 username@localhost
```

### Verify
```bash
curl http://localhost:8081/internal/health
```

```json
{
  "status": "UP",
  "sftpServerRunning": true,
  "sftpPort": 2222,
  "instanceId": "sftp-1",
  "activeConnections": 0,
  "diskUsage": {
    "totalBytes": 107374182400,
    "freeBytes": 85899345920,
    "usableBytes": 85899345920,
    "usedPercent": 20.0
  },
  "lockedAccounts": [],
  "authStats": {
    "totalLogins": 0,
    "failedLogins": 0,
    "lockoutsTriggered": 0
  }
}
```

---

## Connecting as a Client

### Command Line (sftp)
```bash
# Password auth
sftp -P 2222 partner_acme@mft.example.com

# Public key auth
sftp -P 2222 -i ~/.ssh/partner_key partner_acme@mft.example.com
```

### Python (paramiko)
```python
import paramiko

# Password auth
transport = paramiko.Transport(("localhost", 2222))
transport.connect(username="partner_acme", password="secure_password")
sftp = paramiko.SFTPClient.from_transport(transport)

# Upload file
sftp.put("local-report.csv", "/inbox/report.csv")

# Download file
sftp.get("/outbox/results.csv", "local-results.csv")

# List files
for entry in sftp.listdir("/inbox"):
    print(entry)

sftp.close()
transport.close()
```

### Java (JSch)
```java
JSch jsch = new JSch();
Session session = jsch.getSession("partner_acme", "localhost", 2222);
session.setPassword("secure_password");
session.setConfig("StrictHostKeyChecking", "no");
session.connect();

ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
sftp.connect();

// Upload
sftp.put("local-file.csv", "/inbox/file.csv");

// Download
sftp.get("/outbox/result.csv", "local-result.csv");

sftp.disconnect();
session.disconnect();
```

### Node.js (ssh2-sftp-client)
```javascript
const SftpClient = require('ssh2-sftp-client');

const sftp = new SftpClient();
await sftp.connect({
  host: 'localhost',
  port: 2222,
  username: 'partner_acme',
  password: 'secure_password'
});

// Upload
await sftp.put('local-file.csv', '/inbox/file.csv');

// Download
await sftp.get('/outbox/result.csv', 'local-result.csv');

// List
const list = await sftp.list('/inbox');
list.forEach(f => console.log(f.name, f.size));

await sftp.end();
```

---

## Directory Structure (Per User)

```
/home/partner_acme/
├── inbox/      ← Upload files here (triggers routing)
├── outbox/     ← Files delivered to this user appear here
├── archive/    ← Processed files moved here
└── sent/       ← Copies of sent files
```

---

## HTTP API

### Receive Internal File Forward

**POST** `/internal/files/receive`

```bash
curl -X POST http://localhost:8081/internal/files/receive \
  -H "X-Internal-Key: internal_control_secret" \
  -H "Content-Type: application/json" \
  -d '{
    "recordId": "a1b2c3d4-...",
    "destinationUsername": "partner_acme",
    "destinationAbsolutePath": "/outbox/report.csv",
    "fileContentBase64": "aGVsbG8gd29ybGQ=",
    "originalFilename": "report.csv"
  }'
```

---

## Configuration

All new variables have backward-compatible defaults. Existing deployments require no configuration changes.

### Core

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `SFTP_PORT` | `2222` | SFTP server port |
| `SFTP_HOST_KEY_PATH` | `./sftp_host_key` | SSH host key file (auto-generated if missing) |
| `SFTP_HOME_BASE` | `/data/sftp` | Base directory for user homes |
| `INSTANCE_ID` | `sftp-1` | Instance identifier for clustering |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `JWT_SECRET` | (shared) | JWT signing key (must match Onboarding API) |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key |
| `server.port` | `8081` | HTTP management port |

### Connection Management

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_MAX_CONNECTIONS` | `0` (unlimited) | Maximum total concurrent SFTP connections |
| `SFTP_MAX_CONNECTIONS_PER_USER` | `0` (unlimited) | Maximum concurrent connections per username |
| `SFTP_IDLE_TIMEOUT_SECONDS` | `0` (no timeout) | Disconnect sessions idle longer than this many seconds |
| `SFTP_MAX_SESSION_DURATION_SECONDS` | `0` (unlimited) | Maximum session lifetime in seconds |
| `SFTP_BANNER_MESSAGE` | (empty) | Pre-authentication banner message |

### Auth Hardening

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_AUTH_MAX_FAILED_ATTEMPTS` | `5` | Failed attempts before lockout (0 = disable lockout) |
| `SFTP_AUTH_LOCKOUT_DURATION_SECONDS` | `900` (15 min) | Account lockout duration after max failures |
| `SFTP_IP_ALLOWLIST` | (empty = disabled) | Comma-separated IPs/CIDRs allowed to connect |
| `SFTP_IP_DENYLIST` | (empty = disabled) | Comma-separated IPs/CIDRs blocked from connecting |

### File Operation Controls

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_MAX_UPLOAD_SIZE_BYTES` | `0` (unlimited) | Maximum single-file upload size in bytes |
| `SFTP_DISK_QUOTA_BYTES` | `0` (unlimited) | Maximum total disk usage per user home directory |
| `SFTP_ALLOWED_EXTENSIONS` | (empty = all allowed) | Comma-separated permitted extensions (e.g., `.csv,.xml,.txt`) |
| `SFTP_DENIED_EXTENSIONS` | (empty = none denied) | Comma-separated blocked extensions (e.g., `.exe,.bat,.sh`) |
| `SFTP_PREVENT_SYMLINK_TRAVERSAL` | `true` | Block symlinks escaping user home directory |

### SSH Algorithm Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_CIPHERS` | (empty = MINA defaults) | Comma-separated cipher names (e.g., `aes256-ctr,aes128-ctr`) |
| `SFTP_MACS` | (empty = MINA defaults) | Comma-separated MAC algorithm names (e.g., `hmac-sha2-256`) |
| `SFTP_KEX` | (empty = MINA defaults) | Comma-separated key exchange algorithm names |

### Bandwidth Throttling

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_THROTTLE_UPLOAD_BPS` | `0` (unlimited) | Per-user upload speed limit in bytes/sec |
| `SFTP_THROTTLE_DOWNLOAD_BPS` | `0` (unlimited) | Per-user download speed limit in bytes/sec |

### Graceful Shutdown

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_SHUTDOWN_DRAIN_TIMEOUT_SECONDS` | `30` | Seconds to wait for active connections to drain before forced disconnect |

---

## All Endpoints Summary

| Protocol | Port | Path | Description |
|----------|------|------|-------------|
| SFTP | 2222 | N/A | SFTP protocol (SSH-based file transfer) |
| HTTP | 8081 | POST `/internal/files/receive` | Receive forwarded file |
| HTTP | 8081 | GET `/internal/health` | Enhanced health check (connections, disk, locked accounts, auth stats) |

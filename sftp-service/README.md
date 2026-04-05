# SFTP Service

> SSH-based secure file transfer server with public key and password authentication, connection management, auth hardening, file controls, bandwidth throttling, audit logging, and graceful shutdown.

**Port:** 2222 (SFTP) / 8081 (Management API) | **Database:** PostgreSQL | **Messaging:** RabbitMQ | **Required:** Yes

---

## Overview

The SFTP service provides a fully functional SFTP server built on Apache MINA SSHD. It handles:

- **SFTP file transfers** via SSH (port 2222)
- **Password and public key authentication** against the platform database
- **User sandboxing** — each user is restricted to their home directory
- **Auto-provisioning** — creates inbox/outbox/archive/sent directories on first login
- **File routing integration** — uploads trigger the platform's flow engine
- **Credential caching** with RabbitMQ-driven invalidation
- **Clustering** — multiple instances with unique instance IDs
- **Connection management** — global and per-user connection limits, idle timeout, max session duration, configurable banner
- **Auth hardening** — failed-attempt lockout (default: 5 failures triggers 15-minute lockout), IP allowlist/denylist
- **File operation controls** — max upload size, disk quota per user, allowed/denied file extensions, symlink traversal prevention
- **SSH algorithm configuration** — restrict ciphers, MACs, and key exchange algorithms for compliance
- **Bandwidth throttling** — per-user upload and download speed limits
- **Audit logging** — structured JSON events for LOGIN, LOGIN_FAILED, UPLOAD, DOWNLOAD, DELETE, MKDIR, RENAME, DISCONNECT
- **Graceful shutdown** — drains active connections with configurable timeout
- **Enhanced health endpoint** — active connections, disk usage, locked accounts, auth stats

---

## Quick Start

```bash
# Start with dependencies
docker compose up -d postgres rabbitmq onboarding-api sftp-service

# Verify management API
curl http://localhost:8081/internal/health

# Connect via SFTP
sftp -P 2222 myuser@localhost
```

---

## API Endpoints

### Internal Management API (port 8081)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/internal/health` | None | Service health with SFTP server status |
| POST | `/internal/files/receive` | X-Internal-Key | Receive forwarded files from other instances |

**Health check:**
```bash
curl http://localhost:8081/internal/health
```

**Response:**
```json
{
  "status": "UP",
  "sftpServerRunning": true,
  "sftpPort": 2222,
  "instanceId": "sftp-1",
  "activeConnections": 3,
  "diskUsage": {
    "totalBytes": 107374182400,
    "freeBytes": 85899345920,
    "usableBytes": 85899345920,
    "usedPercent": 20.0
  },
  "lockedAccounts": ["partner3"],
  "authStats": {
    "totalLogins": 142,
    "failedLogins": 7,
    "lockoutsTriggered": 1
  }
}
```

**Receive forwarded file:**
```bash
curl -X POST http://localhost:8081/internal/files/receive \
  -H "X-Internal-Key: internal_control_secret" \
  -H "Content-Type: application/json" \
  -d '{"trackId":"TRZA3X5T3LUY","filename":"invoice.csv","targetAccount":"partner-a","targetPath":"/inbox"}'
```

---

## SFTP Protocol Details

### Authentication Methods

**Password authentication:**
- Credentials verified against `transfer_accounts` table via BCrypt
- Login attempts logged as audit events (LOGIN or LOGIN_FAILED)

**Public key authentication:**
- Keys stored in OpenSSH `authorized_keys` format in the account record
- Supports RSA, ECDSA, Ed25519 keys

**Auth hardening:**
- After `SFTP_AUTH_MAX_FAILED_ATTEMPTS` consecutive failures (default: 5), the account is locked for `SFTP_AUTH_LOCKOUT_DURATION_SECONDS` (default: 900 = 15 minutes)
- Set `SFTP_AUTH_MAX_FAILED_ATTEMPTS=0` to disable lockout
- IP-based access control via `SFTP_IP_ALLOWLIST` and `SFTP_IP_DENYLIST` (comma-separated CIDRs or addresses)
- Allowlist takes priority: if set, only listed IPs may connect; denylist is checked afterward

### User Directory Structure

On first login, these directories are created:
```
/data/sftp/{username}/
├── inbox/      ← Receive files here
├── outbox/     ← Drop files for processing
├── archive/    ← Processed file copies
└── sent/       ← Forwarded file copies
```

### File Routing

When a user uploads a file:
1. SFTP service detects the upload via `SftpRoutingEventListener`
2. Routing engine assigns a Track ID
3. Matching file flows are executed (encrypt, compress, screen, etc.)
4. File is delivered to destination account or external endpoint

Downloads are also tracked for audit purposes.

---

## Connection Management

Control how many connections the server accepts and how long sessions may last.

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_MAX_CONNECTIONS` | `0` (unlimited) | Maximum total concurrent SFTP connections |
| `SFTP_MAX_CONNECTIONS_PER_USER` | `0` (unlimited) | Maximum concurrent connections per username |
| `SFTP_IDLE_TIMEOUT_SECONDS` | `0` (no timeout) | Disconnect sessions idle longer than this |
| `SFTP_MAX_SESSION_DURATION_SECONDS` | `0` (unlimited) | Maximum session lifetime regardless of activity |
| `SFTP_BANNER_MESSAGE` | (empty) | Pre-authentication banner displayed to clients |

When `SFTP_MAX_CONNECTIONS` is reached, new connection attempts are rejected with a message indicating the server is at capacity. Per-user limits work independently, preventing a single account from monopolizing all slots.

---

## File Operation Controls

Enforce size limits, quotas, and extension policies on file operations.

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_MAX_UPLOAD_SIZE_BYTES` | `0` (unlimited) | Maximum single-file upload size in bytes |
| `SFTP_DISK_QUOTA_BYTES` | `0` (unlimited) | Maximum total disk usage per user home directory |
| `SFTP_ALLOWED_EXTENSIONS` | (empty = all allowed) | Comma-separated list of permitted file extensions (e.g., `.csv,.xml,.txt`) |
| `SFTP_DENIED_EXTENSIONS` | (empty = none denied) | Comma-separated list of blocked file extensions (e.g., `.exe,.bat,.sh`) |
| `SFTP_PREVENT_SYMLINK_TRAVERSAL` | `true` | Reject operations that would follow symlinks outside the user home |

Extension filtering rules:
- If `SFTP_ALLOWED_EXTENSIONS` is set, only files with those extensions may be uploaded; all others are rejected.
- If `SFTP_DENIED_EXTENSIONS` is set and allowed is empty, all extensions are permitted except those listed.
- If both are set, the allowlist takes precedence.
- `SFTP_PREVENT_SYMLINK_TRAVERSAL` ensures users cannot create or follow symbolic links that escape their sandboxed home directory.

---

## SSH Algorithm Configuration

Restrict the SSH algorithms offered by the server to meet compliance requirements (e.g., FIPS 140-2, PCI-DSS).

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_CIPHERS` | (empty = MINA defaults) | Comma-separated cipher list (e.g., `aes256-ctr,aes128-ctr`) |
| `SFTP_MACS` | (empty = MINA defaults) | Comma-separated MAC list (e.g., `hmac-sha2-256,hmac-sha2-512`) |
| `SFTP_KEX` | (empty = MINA defaults) | Comma-separated key exchange list (e.g., `ecdh-sha2-nistp256,diffie-hellman-group16-sha512`) |

When left empty, Apache MINA SSHD uses its built-in defaults. Setting a value restricts the server to only the listed algorithms. Clients that do not support any of the offered algorithms will fail to connect.

---

## Bandwidth Throttling

Limit upload and download throughput per user to prevent any single user from saturating the server's network bandwidth.

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_THROTTLE_UPLOAD_BPS` | `0` (unlimited) | Maximum upload speed per user in bytes per second |
| `SFTP_THROTTLE_DOWNLOAD_BPS` | `0` (unlimited) | Maximum download speed per user in bytes per second |

Example: setting `SFTP_THROTTLE_UPLOAD_BPS=1048576` limits each user to 1 MB/s upload speed.

---

## Audit Logging

Every significant action is logged as a structured JSON event to the application log output. These events are designed for ingestion by log aggregation systems (ELK, Splunk, Datadog, etc.).

**Event types:**

| Event | Description |
|-------|-------------|
| `LOGIN` | Successful authentication (password or public key) |
| `LOGIN_FAILED` | Failed authentication attempt (includes username and source IP) |
| `UPLOAD` | File uploaded to the server |
| `DOWNLOAD` | File downloaded from the server |
| `DELETE` | File deleted by the user |
| `MKDIR` | Directory created by the user |
| `RENAME` | File or directory renamed/moved |
| `DISCONNECT` | Session ended (normal or forced) |

**Example audit log entry:**
```json
{
  "timestamp": "2026-04-05T14:23:01.456Z",
  "event": "UPLOAD",
  "username": "partner1",
  "sourceIp": "192.168.1.50",
  "path": "/inbox/invoice.csv",
  "sizeBytes": 2048,
  "instanceId": "sftp-1"
}
```

---

## Graceful Shutdown

When the service receives a shutdown signal (SIGTERM, container stop, etc.), it:

1. Stops accepting new connections immediately
2. Waits for active sessions to complete their in-progress transfers
3. Forcefully disconnects any sessions still open after the drain timeout

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_SHUTDOWN_DRAIN_TIMEOUT_SECONDS` | `30` | Maximum time to wait for active connections to drain before forced disconnect |

This ensures that files being transferred at shutdown time are not corrupted.

---

## Enhanced Health Endpoint

The health endpoint at `GET /internal/health` returns extended operational data:

```json
{
  "status": "UP",
  "sftpServerRunning": true,
  "sftpPort": 2222,
  "instanceId": "sftp-1",
  "activeConnections": 3,
  "diskUsage": {
    "totalBytes": 107374182400,
    "freeBytes": 85899345920,
    "usableBytes": 85899345920,
    "usedPercent": 20.0
  },
  "lockedAccounts": ["partner3"],
  "authStats": {
    "totalLogins": 142,
    "failedLogins": 7,
    "lockoutsTriggered": 1
  }
}
```

- **activeConnections** — number of currently connected SFTP sessions
- **diskUsage** — disk space information for the SFTP home base directory
- **lockedAccounts** — list of accounts currently locked due to failed-attempt lockout
- **authStats** — cumulative authentication statistics since service start

---

## Configuration

All new variables have backward-compatible defaults. Existing deployments require no changes.

### Core Settings

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8081` | Management API port |
| `SFTP_PORT` | `2222` | SFTP listen port |
| `SFTP_HOST_KEY_PATH` | `./sftp_host_key` | SSH host key file path |
| `SFTP_HOME_BASE` | `/data/sftp` | Root directory for user homes |
| `INSTANCE_ID` | `sftp-1` | Instance identifier for clustering |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `JWT_SECRET` | (shared) | JWT signing key |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `CONTROL_API_KEY` | `internal_control_secret` | Key for internal endpoints |

### Connection Management

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_MAX_CONNECTIONS` | `0` (unlimited) | Maximum total concurrent SFTP connections |
| `SFTP_MAX_CONNECTIONS_PER_USER` | `0` (unlimited) | Maximum concurrent connections per username |
| `SFTP_IDLE_TIMEOUT_SECONDS` | `0` (no timeout) | Disconnect idle sessions after this many seconds |
| `SFTP_MAX_SESSION_DURATION_SECONDS` | `0` (unlimited) | Maximum session lifetime in seconds |
| `SFTP_BANNER_MESSAGE` | (empty) | Pre-authentication banner message |

### Auth Hardening

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_AUTH_MAX_FAILED_ATTEMPTS` | `5` | Failed attempts before lockout (0 = disable lockout) |
| `SFTP_AUTH_LOCKOUT_DURATION_SECONDS` | `900` (15 min) | Duration of account lockout after max failures |
| `SFTP_IP_ALLOWLIST` | (empty = disabled) | Comma-separated IPs/CIDRs allowed to connect |
| `SFTP_IP_DENYLIST` | (empty = disabled) | Comma-separated IPs/CIDRs denied from connecting |

### File Operation Controls

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_MAX_UPLOAD_SIZE_BYTES` | `0` (unlimited) | Maximum single-file upload size |
| `SFTP_DISK_QUOTA_BYTES` | `0` (unlimited) | Maximum disk usage per user home directory |
| `SFTP_ALLOWED_EXTENSIONS` | (empty = all allowed) | Comma-separated permitted extensions (e.g., `.csv,.xml`) |
| `SFTP_DENIED_EXTENSIONS` | (empty = none denied) | Comma-separated blocked extensions (e.g., `.exe,.bat`) |
| `SFTP_PREVENT_SYMLINK_TRAVERSAL` | `true` | Block symlinks that escape user home |

### SSH Algorithms

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_CIPHERS` | (empty = MINA defaults) | Comma-separated cipher list |
| `SFTP_MACS` | (empty = MINA defaults) | Comma-separated MAC algorithm list |
| `SFTP_KEX` | (empty = MINA defaults) | Comma-separated key exchange algorithm list |

### Bandwidth Throttling

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_THROTTLE_UPLOAD_BPS` | `0` (unlimited) | Per-user upload speed limit (bytes/sec) |
| `SFTP_THROTTLE_DOWNLOAD_BPS` | `0` (unlimited) | Per-user download speed limit (bytes/sec) |

### Graceful Shutdown

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_SHUTDOWN_DRAIN_TIMEOUT_SECONDS` | `30` | Seconds to wait for active connections before forced disconnect |

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                       sftp-service                            │
├──────────────────────────────────────────────────────────────┤
│  SSH/SFTP Server (Apache MINA SSHD)                          │
│  ├── SftpPasswordAuthenticator                               │
│  ├── SftpPublicKeyAuthenticator                              │
│  ├── SftpFileSystemFactory (sandboxed per user)              │
│  ├── SftpRoutingEventListener (upload/download)              │
│  ├── ConnectionManager (limits, idle timeout, session max)   │
│  ├── AuthHardeningFilter (lockout, IP allow/deny)            │
│  ├── FileOperationControls (size, quota, extensions)         │
│  ├── BandwidthThrottler (per-user upload/download limits)    │
│  ├── SSH Algorithm Config (ciphers, MACs, KEX)               │
│  └── AuditLogger (structured JSON events)                    │
├──────────────────────────────────────────────────────────────┤
│  CredentialService (cached, RabbitMQ-invalidated)            │
├──────────────────────────────────────────────────────────────┤
│  GracefulShutdownHandler (drain timeout)                     │
├──────────────────────────────────────────────────────────────┤
│  Enhanced Health Endpoint (connections, disk, auth stats)    │
├──────────────────────────────────────────────────────────────┤
│  RabbitMQ Consumer: sftp.account.events                      │
│  Binding: account.* from file-transfer.events                │
└──────────────────────────────────────────────────────────────┘
```

---

## Messaging

Listens to RabbitMQ queue `sftp.account.events` (binding `account.*`):
- **account.created** — Creates home directories, evicts cache
- **account.updated** — Evicts credential cache for the user

---

## Dependencies

- **PostgreSQL** — Required. Account credentials and transfer records.
- **RabbitMQ** — Required. Cache invalidation on account changes.
- **shared** module — Entities, routing engine, audit service.

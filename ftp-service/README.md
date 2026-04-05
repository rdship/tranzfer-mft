# FTP Service

> Production-grade FTP/FTPS file transfer server with connection management, auth hardening, bandwidth throttling, structured audit logging, and graceful shutdown.

**Port:** 21 (FTP) / 8082 (Management API) | **Database:** PostgreSQL | **Messaging:** RabbitMQ | **Required:** Optional

---

## Overview

The FTP service provides a production-ready FTP server built on Apache FtpServer. It handles:

- **FTP file transfers** with active and passive mode
- **FTPS** (FTP over TLS) with explicit and implicit modes, configurable cipher suites and TLS versions
- **Password authentication** against the platform database
- **User home directories** with configurable permissions
- **File routing integration** -- uploads trigger the platform's flow engine
- **Credential caching** with RabbitMQ-driven invalidation
- **Clustering** -- multiple instances with unique instance IDs
- **Auto-generated TLS certificates** when FTPS is enabled

### Production Hardening Features

- **Active mode control** -- Enable/disable PORT and EPRT commands, configurable source port range, bounce attack prevention for both PORT and EPRT
- **Connection management** -- Global, per-user, and per-IP connection limits with configurable idle and data timeouts
- **Auth hardening** -- Failed login lockout (5 failures = 15 min lockout), IP allowlist/denylist, optional anonymous FTP
- **FTPS hardening** -- Configurable TLS versions (disable TLSv1.0/1.1), cipher suite selection, implicit FTPS, require-TLS mode, PROT P enforcement, client certificate auth (none/want/need), keystore type selection (JKS/PKCS12)
- **File operation controls** -- Max upload file size, disk quota per user, file extension allowlist/denylist
- **Bandwidth throttling** -- Per-user upload/download speed limits
- **Structured audit logging** -- JSON events for LOGIN, LOGIN_FAILED, UPLOAD, DOWNLOAD, DELETE, MKDIR, RENAME, DISCONNECT
- **FTP bounce prevention** -- PORT and EPRT commands restricted to client's own IP
- **Graceful shutdown** -- Drains active connections on SIGTERM with configurable timeout
- **Enhanced health endpoint** -- Active connections, disk usage, uptime, TLS status with certificate expiry, locked accounts

---

## Quick Start

```bash
# Start with dependencies
docker compose up -d postgres rabbitmq onboarding-api ftp-service

# Verify
curl http://localhost:8082/internal/health

# Connect via FTP
ftp localhost 21
```

---

## API Endpoints

### Internal Management API (port 8082)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/internal/health` | None | Enhanced health with connections, disk, TLS, locked accounts |
| POST | `/internal/files/receive` | X-Internal-Key | Receive forwarded files |

**Health check:**
```bash
curl http://localhost:8082/internal/health
```

**Response:**
```json
{
  "status": "UP",
  "ftpServerStopped": false,
  "ftpServerSuspended": false,
  "instanceId": "ftp-1",
  "uptimeSeconds": 3600,
  "uptimeHuman": "1h 0m 0s",
  "connections": {
    "active": 3,
    "maxTotal": 200,
    "maxPerUser": 10,
    "maxPerIp": 10,
    "perUser": {"ftpuser1": 2, "ftpuser2": 1},
    "perIp": {"192.168.1.10": 2, "10.0.0.5": 1}
  },
  "tls": {
    "enabled": true,
    "protocol": "TLSv1.2",
    "implicit": false,
    "requireTls": false,
    "requireDataTls": false,
    "clientAuth": "NONE",
    "keystoreType": "JKS",
    "certificates": {
      "ftps": {
        "subject": "CN=TranzFer FTPS,O=TranzFer MFT,C=US",
        "issuer": "CN=TranzFer FTPS,O=TranzFer MFT,C=US",
        "notAfter": "2027-04-05T10:00:00Z",
        "sigAlgorithm": "SHA256withRSA",
        "expired": false,
        "expiringSoon": false
      }
    }
  },
  "activeModeEnabled": true,
  "disk": {
    "path": "/data/ftp",
    "totalBytes": 107374182400,
    "freeBytes": 53687091200,
    "usableBytes": 48318382080,
    "usedPercent": 55.0
  },
  "lockedAccounts": ["brute_force_user"],
  "lockedAccountCount": 1,
  "jvmUptimeMs": 3600000
}
```

---

## FTP Protocol Details

### Authentication
- Password-only authentication (BCrypt hash verification)
- Failed login tracking with account lockout (configurable threshold and duration)
- IP allowlist/denylist filtering
- Optional anonymous FTP (disabled by default)
- Audit logging for all login attempts (success and failure)

### Connection Limits
- Global max connections: 200 (configurable)
- Max connections per user: 10 (configurable)
- Max connections per IP: 10 (configurable)
- Max idle time: 300 seconds (configurable)
- Data connection timeout: 120 seconds (configurable)
- Configurable 220 banner message

### Active Mode (PORT/EPRT)
Active FTP mode allows the server to connect back to the client for data transfers. This is the original FTP data connection model:

- **Enable/disable** -- Active mode is enabled by default (`FTP_ACTIVE_MODE_ENABLED=true`). Disable to force clients into passive mode only.
- **Bounce prevention** -- PORT and EPRT commands are validated to ensure the target IP matches the client's connection IP, preventing FTP bounce attacks. This applies to both IPv4 (PORT) and IPv6 (EPRT).
- **Source port range** -- Configure the local port range for outbound active connections with `FTP_ACTIVE_DATA_PORT_MIN` and `FTP_ACTIVE_DATA_PORT_MAX` (0 = OS-assigned).
- **Timeout** -- `FTP_ACTIVE_DATA_TIMEOUT_SECONDS` controls the timeout for establishing active data connections (default: 30s).

### Passive Mode (PASV/EPSV)
FTP passive mode is required for clients behind NAT/firewalls. The server announces a public IP and a port range for data connections. EPSV (Extended Passive) is supported. Active and passive mode work side by side.

### FTPS (FTP over TLS)
- Explicit FTPS (AUTH TLS on port 21) -- default
- Implicit FTPS (direct TLS, typically port 990)
- Configurable TLS protocol versions (disable TLSv1.0/1.1)
- Cipher suite selection
- Require-TLS mode (reject plain FTP)
- PROT P enforcement (require encrypted data channel)
- Client certificate authentication: none, want, or need (mutual TLS)
- Truststore for client certificate validation
- Configurable keystore type (JKS or PKCS12)
- Certificate info exposed in health endpoint (subject, expiry, algorithm)

### File Operation Controls
- Max upload file size enforcement
- Per-user disk quota
- File extension allowlist/denylist

### Bandwidth Throttling
- Per-user upload rate limit (bytes/second)
- Per-user download rate limit (bytes/second)

### Security
- FTP bounce attack prevention (PORT and EPRT commands restricted to client IP)
- Active mode can be disabled entirely to force passive-only connections
- Account lockout after configurable number of failed logins
- IP-based access control

### File Routing
Uploads and downloads are tracked via `FtpletRoutingAdapter`:
1. Client uploads a file
2. `onUploadEnd()` fires -- routing engine processes the file
3. Downloads tracked via `onDownloadEnd()`
4. Deletes, renames, and mkdir operations are audit-logged

### Audit Logging
All operations are logged as structured JSON to the `FTP_AUDIT` logger:
```json
{"timestamp":"2026-04-05T10:00:00Z","event":"UPLOAD","username":"ftpuser1","ip":"192.168.1.10","instance":"ftp-1","filename":"invoice.csv","bytes":2048,"duration_ms":150}
```

Events: `LOGIN`, `LOGIN_FAILED`, `UPLOAD`, `DOWNLOAD`, `DELETE`, `MKDIR`, `RENAME`, `DISCONNECT`

### Graceful Shutdown
On SIGTERM, the server:
1. Stops accepting new connections
2. Waits up to `ftp.shutdown.drain-timeout-seconds` for active connections to finish
3. Forces shutdown of remaining connections

---

## Configuration

### Core Settings

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8082` | Management API port |
| `FTP_PORT` | `21` | FTP listen port |
| `FTP_PUBLIC_HOST` | `127.0.0.1` | Public IP for passive mode |
| `FTP_PASSIVE_PORTS` | `21000-21010` | Passive port range |
| `FTP_HOME_BASE` | `/data/ftp` | Root directory for user homes |
| `FTP_INSTANCE_ID` | `null` | Instance ID for clustering |

### Connection Management

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_MAX_CONNECTIONS` | `200` | Global max simultaneous connections |
| `FTP_MAX_CONNECTIONS_PER_USER` | `10` | Max connections per username |
| `FTP_MAX_CONNECTIONS_PER_IP` | `10` | Max connections per source IP |
| `FTP_MAX_LOGINS` | `100` | Max concurrent authenticated sessions |
| `FTP_IDLE_TIMEOUT_SECONDS` | `300` | Idle connection timeout |
| `FTP_DATA_CONNECTION_TIMEOUT_SECONDS` | `120` | Data channel idle timeout |
| `FTP_BANNER_MESSAGE` | `220 TranzFer MFT FTP Service Ready` | FTP 220 banner message |

### Authentication Hardening

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_MAX_LOGIN_FAILURES` | `5` | Failed logins before lockout |
| `FTP_LOCKOUT_DURATION_SECONDS` | `900` | Lockout duration (15 min) |
| `FTP_IP_ALLOWLIST` | _(empty)_ | Comma-separated allowed IPs (empty = all) |
| `FTP_IP_DENYLIST` | _(empty)_ | Comma-separated denied IPs |
| `FTP_ANONYMOUS_ENABLED` | `false` | Enable anonymous FTP |
| `FTP_ANONYMOUS_HOME_DIR` | `/data/ftp/anonymous` | Anonymous user home directory |
| `FTP_BOUNCE_PREVENTION` | `true` | Block FTP bounce attacks (PORT to foreign IP) |

### FTPS (TLS) Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_FTPS_ENABLED` | `false` | Enable FTPS |
| `FTP_TLS_KEYSTORE_FILE` | `./ftp-keystore.jks` | TLS keystore path |
| `FTP_TLS_KEYSTORE_PASSWORD` | `changeit` | Keystore password |
| `FTP_TLS_KEYSTORE_TYPE` | `JKS` | Keystore type: `JKS` or `PKCS12` |
| `FTP_FTPS_PROTOCOL` | `TLSv1.2` | SSL context protocol version |
| `FTP_TLS_CLIENT_AUTH` | `none` | Client cert auth: `none`, `want`, or `need` |
| `FTP_IMPLICIT_TLS` | `false` | Use implicit FTPS (port 990) |
| `FTP_REQUIRE_TLS` | `false` | Reject plain FTP (require FTPS) |
| `FTP_REQUIRE_DATA_TLS` | `false` | Require encrypted data channel (PROT P) |
| `FTP_TLS_CIPHER_SUITES` | _(empty)_ | Comma-separated cipher suites (empty = JVM defaults) |
| `FTP_TLS_PROTOCOLS` | _(empty)_ | Comma-separated TLS versions (e.g., `TLSv1.2,TLSv1.3`) |
| `FTP_TLS_TRUSTSTORE_FILE` | _(empty)_ | Truststore for client cert validation |
| `FTP_TLS_TRUSTSTORE_PASSWORD` | `changeit` | Truststore password |

### File Operation Controls

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_MAX_UPLOAD_SIZE` | `0` | Max upload file size in bytes (0 = unlimited) |
| `FTP_DISK_QUOTA` | `0` | Per-user disk quota in bytes (0 = unlimited) |
| `FTP_ALLOWED_EXTENSIONS` | _(empty)_ | Comma-separated allowed extensions (empty = all) |
| `FTP_DENIED_EXTENSIONS` | _(empty)_ | Comma-separated denied extensions (e.g., `exe,bat,sh`) |

### Bandwidth Throttling

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_MAX_UPLOAD_RATE` | `0` | Max upload speed in bytes/sec (0 = unlimited) |
| `FTP_MAX_DOWNLOAD_RATE` | `0` | Max download speed in bytes/sec (0 = unlimited) |

### Active Mode (PORT/EPRT)

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_ACTIVE_MODE_ENABLED` | `true` | Enable active mode (PORT/EPRT commands) |
| `FTP_ACTIVE_DATA_PORT_MIN` | `0` | Min source port for active connections (0 = OS assigned) |
| `FTP_ACTIVE_DATA_PORT_MAX` | `0` | Max source port for active connections (0 = OS assigned) |
| `FTP_ACTIVE_DATA_TIMEOUT_SECONDS` | `30` | Active data connection establishment timeout |

### Passive Mode

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_EPSV_ENABLED` | `true` | Enable Extended Passive (EPSV) mode |

### Audit and Lifecycle

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_AUDIT_ENABLED` | `true` | Enable structured JSON audit logging |
| `FTP_SHUTDOWN_DRAIN_TIMEOUT_SECONDS` | `30` | Graceful shutdown drain timeout |

### Infrastructure

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ hostname |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `RABBITMQ_USERNAME` | `guest` | RabbitMQ username |
| `RABBITMQ_PASSWORD` | `guest` | RabbitMQ password |
| `JWT_SECRET` | _(change in production)_ | JWT signing secret |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key |

---

## Architecture

```
+----------------------------------------------------+
|                    ftp-service                      |
+----------------------------------------------------+
|  FTP Server (Apache FtpServer)                     |
|  +-- FtpUserManager (auth + lockout + IP filter)   |
|  +-- FtpsConfig (TLS hardening + cert info)        |
|  +-- FtpBounceFilter (PORT/EPRT restriction)       |
|  +-- FileOperationFilter (size/quota/extensions)   |
|  +-- FtpletRoutingAdapter (upload/download/audit)  |
|  +-- Active mode (PORT/EPRT) + Passive (PASV/EPSV) |
+----------------------------------------------------+
|  ConnectionTracker (global/per-user/per-IP limits) |
|  LoginLockoutService (failure tracking + lockout)  |
|  IpFilterService (allowlist/denylist)              |
|  BandwidthThrottleService (upload/download rates)  |
|  AuditEventLogger (structured JSON events)         |
+----------------------------------------------------+
|  CredentialService (cached BCrypt auth)            |
+----------------------------------------------------+
|  GracefulShutdownHandler (drain on SIGTERM)        |
+----------------------------------------------------+
|  RabbitMQ Consumer: ftp.account.events             |
|  Binding: account.* from file-transfer.events      |
+----------------------------------------------------+
```

---

## Security Hardening Checklist

For production deployments:

1. **Enable FTPS**: `FTP_FTPS_ENABLED=true` with a proper CA-signed certificate
2. **Require TLS**: `FTP_REQUIRE_TLS=true` to reject plain FTP
3. **Require data TLS**: `FTP_REQUIRE_DATA_TLS=true` to enforce PROT P (encrypted data channel)
4. **Restrict TLS versions**: `FTP_TLS_PROTOCOLS=TLSv1.2,TLSv1.3`
5. **Restrict cipher suites**: `FTP_TLS_CIPHER_SUITES=TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256`
6. **Use PKCS12 keystore**: `FTP_TLS_KEYSTORE_TYPE=PKCS12` (modern format, recommended over JKS)
7. **Consider client certs**: `FTP_TLS_CLIENT_AUTH=need` for mutual TLS with a truststore
8. **Disable active mode** (if not needed): `FTP_ACTIVE_MODE_ENABLED=false` to reduce attack surface
9. **Set IP restrictions**: `FTP_IP_ALLOWLIST=10.0.0.0/8,192.168.0.0/16` or deny known bad actors
10. **Tune connection limits**: Set `FTP_MAX_CONNECTIONS`, `FTP_MAX_CONNECTIONS_PER_USER`, `FTP_MAX_CONNECTIONS_PER_IP`
11. **Block dangerous files**: `FTP_DENIED_EXTENSIONS=exe,bat,sh,cmd,ps1`
12. **Set upload limits**: `FTP_MAX_UPLOAD_SIZE=104857600` (100 MB) and `FTP_DISK_QUOTA=1073741824` (1 GB)
13. **Enable bandwidth limits**: `FTP_MAX_UPLOAD_RATE=10485760` (10 MB/s)
14. **Keep anonymous disabled**: `FTP_ANONYMOUS_ENABLED=false` (default)
15. **Keep bounce prevention on**: `FTP_BOUNCE_PREVENTION=true` (default)

---

## Dependencies

- **PostgreSQL** -- Required. Account credentials and transfer records.
- **RabbitMQ** -- Required. Cache invalidation on account changes.
- **shared** module -- Entities, routing engine, audit service.

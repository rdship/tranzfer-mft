# FTP Service -- Standalone Product Guide

> **Production-grade FTP/FTPS server.** Apache FtpServer-based with passive mode, TLS encryption, connection limits, bandwidth throttling, account lockout, structured audit logging, file operation controls, and graceful shutdown.

**Port:** 21 (FTP) + 8082 (HTTP) | **Dependencies:** PostgreSQL, RabbitMQ | **Auth:** Username/password

---

## Why Use This

- **Production FTP server** -- Built on Apache FtpServer with enterprise hardening
- **Active and passive mode** -- Full support for PORT/EPRT (active) and PASV/EPSV (passive) with independent controls
- **FTPS support** -- Explicit and implicit TLS with configurable cipher suites, protocol versions, PROT P enforcement, and client certificate auth (none/want/need)
- **Connection management** -- Global, per-user, and per-IP connection limits with idle timeouts
- **Auth hardening** -- Failed login lockout, IP allowlist/denylist, optional anonymous FTP
- **File operation controls** -- Max upload size, disk quota, file extension restrictions
- **Bandwidth throttling** -- Per-user upload/download speed limits
- **Structured audit logging** -- JSON events for all operations (LOGIN, UPLOAD, DOWNLOAD, DELETE, etc.)
- **FTP bounce prevention** -- PORT and EPRT commands restricted to client IP
- **Graceful shutdown** -- Drains active connections on SIGTERM
- **Enhanced health endpoint** -- Active connections, disk usage, uptime, TLS status with certificate expiry, locked accounts
- **Auto-provisioning** -- Creates user home directories automatically
- **File routing** -- Uploaded files routed through platform flows

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq ftp-service

# Connect with any FTP client
ftp localhost 21
```

---

## Connecting as a Client

### Command Line (ftp)
```bash
ftp localhost 21
# Username: partner_acme
# Password: secure_password
ftp> cd inbox
ftp> put local-file.csv
ftp> ls
ftp> get outbox/result.csv
ftp> bye
```

### Python (ftplib)
```python
from ftplib import FTP

ftp = FTP()
ftp.connect("localhost", 21)
ftp.login("partner_acme", "secure_password")

# Upload
with open("report.csv", "rb") as f:
    ftp.storbinary("STOR /inbox/report.csv", f)

# Download
with open("result.csv", "wb") as f:
    ftp.retrbinary("RETR /outbox/result.csv", f.write)

# List
ftp.retrlines("LIST /inbox")

ftp.quit()
```

### Python (FTPS -- TLS)
```python
from ftplib import FTP_TLS

ftps = FTP_TLS()
ftps.connect("localhost", 21)
ftps.auth()              # Upgrade to TLS
ftps.prot_p()            # Protect data channel
ftps.login("partner_acme", "secure_password")

with open("sensitive.csv", "rb") as f:
    ftps.storbinary("STOR /inbox/sensitive.csv", f)

ftps.quit()
```

### Java (Apache Commons Net)
```java
FTPClient ftp = new FTPClient();
ftp.connect("localhost", 21);
ftp.login("partner_acme", "secure_password");
ftp.enterLocalPassiveMode();
ftp.setFileType(FTP.BINARY_FILE_TYPE);

// Upload
try (InputStream is = new FileInputStream("report.csv")) {
    ftp.storeFile("/inbox/report.csv", is);
}

// Download
try (OutputStream os = new FileOutputStream("result.csv")) {
    ftp.retrieveFile("/outbox/result.csv", os);
}

ftp.logout();
ftp.disconnect();
```

---

## Configuration

### Core

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `FTP_PORT` | `21` | FTP server port |
| `FTP_PASSIVE_PORTS` | `21000-21010` | Passive mode port range |
| `FTP_PUBLIC_HOST` | `127.0.0.1` | Public IP for passive mode |
| `FTP_HOME_BASE` | `/data/ftp` | User home base directory |
| `FTP_INSTANCE_ID` | `null` | Instance ID for clustering |
| `server.port` | `8082` | HTTP management port |

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
| `FTP_IP_ALLOWLIST` | _(empty)_ | Comma-separated allowed IPs |
| `FTP_IP_DENYLIST` | _(empty)_ | Comma-separated denied IPs |
| `FTP_ANONYMOUS_ENABLED` | `false` | Enable anonymous FTP |
| `FTP_BOUNCE_PREVENTION` | `true` | Block FTP bounce attacks |

### FTPS (TLS)

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_FTPS_ENABLED` | `false` | Enable FTPS |
| `FTP_FTPS_PROTOCOL` | `TLSv1.2` | SSL context protocol version |
| `FTP_IMPLICIT_TLS` | `false` | Use implicit FTPS (port 990) |
| `FTP_REQUIRE_TLS` | `false` | Reject plain FTP connections |
| `FTP_REQUIRE_DATA_TLS` | `false` | Require encrypted data channel (PROT P) |
| `FTP_TLS_CIPHER_SUITES` | _(empty)_ | Allowed cipher suites (empty = JVM defaults) |
| `FTP_TLS_PROTOCOLS` | _(empty)_ | Allowed TLS versions (e.g., `TLSv1.2,TLSv1.3`) |
| `FTP_TLS_CLIENT_AUTH` | `none` | Client cert auth: `none`, `want`, or `need` |
| `FTP_TLS_KEYSTORE_FILE` | `./ftp-keystore.jks` | TLS keystore path |
| `FTP_TLS_KEYSTORE_PASSWORD` | `changeit` | Keystore password |
| `FTP_TLS_KEYSTORE_TYPE` | `JKS` | Keystore type: `JKS` or `PKCS12` |
| `FTP_TLS_TRUSTSTORE_FILE` | _(empty)_ | Client cert truststore path |
| `FTP_TLS_TRUSTSTORE_PASSWORD` | `changeit` | Truststore password |

### Active Mode (PORT/EPRT)

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_ACTIVE_MODE_ENABLED` | `true` | Enable active mode (PORT/EPRT commands) |
| `FTP_ACTIVE_DATA_PORT_MIN` | `0` | Min source port for active connections (0 = OS assigned) |
| `FTP_ACTIVE_DATA_PORT_MAX` | `0` | Max source port for active connections (0 = OS assigned) |
| `FTP_ACTIVE_DATA_TIMEOUT_SECONDS` | `30` | Active data connection establishment timeout |

### File Operation Controls

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_MAX_UPLOAD_SIZE` | `0` | Max upload file size in bytes (0 = unlimited) |
| `FTP_DISK_QUOTA` | `0` | Per-user disk quota in bytes (0 = unlimited) |
| `FTP_ALLOWED_EXTENSIONS` | _(empty)_ | Comma-separated allowed file extensions |
| `FTP_DENIED_EXTENSIONS` | _(empty)_ | Comma-separated denied file extensions |

### Bandwidth Throttling

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_MAX_UPLOAD_RATE` | `0` | Upload rate limit in bytes/sec (0 = unlimited) |
| `FTP_MAX_DOWNLOAD_RATE` | `0` | Download rate limit in bytes/sec (0 = unlimited) |

### Audit and Lifecycle

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_AUDIT_ENABLED` | `true` | Enable structured JSON audit logging |
| `FTP_SHUTDOWN_DRAIN_TIMEOUT_SECONDS` | `30` | Graceful shutdown drain timeout |

---

## All Endpoints Summary

| Protocol | Port | Path | Description |
|----------|------|------|-------------|
| FTP | 21 | N/A | FTP control channel |
| FTP | 21000-21010 | N/A | Passive mode data channels |
| FTP | 20000-20010 | N/A | Active mode source ports (if configured) |
| HTTP | 8082 | POST `/internal/files/receive` | Receive forwarded file |
| HTTP | 8082 | GET `/internal/health` | Health check (connections, disk, TLS, certs, active mode, lockouts) |

---

## Health Endpoint Response

```json
{
  "status": "UP",
  "ftpServerStopped": false,
  "ftpServerSuspended": false,
  "instanceId": "ftp-1",
  "uptimeSeconds": 86400,
  "uptimeHuman": "1d 0h 0m 0s",
  "connections": {
    "active": 5,
    "maxTotal": 200,
    "maxPerUser": 10,
    "maxPerIp": 10,
    "perUser": {"partner_acme": 3, "partner_beta": 2},
    "perIp": {"10.0.0.5": 3, "192.168.1.20": 2}
  },
  "tls": {
    "enabled": true,
    "protocol": "TLSv1.2",
    "implicit": false,
    "requireTls": true,
    "requireDataTls": false,
    "clientAuth": "NONE",
    "keystoreType": "JKS",
    "certificates": {
      "ftps": {
        "subject": "CN=TranzFer FTPS,O=TranzFer MFT,C=US",
        "notAfter": "2027-04-05T10:00:00Z",
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
  "lockedAccounts": [],
  "lockedAccountCount": 0,
  "jvmUptimeMs": 86400000
}
```

---

## Audit Log Events

All operations are logged as structured JSON to the `FTP_AUDIT` logger:

| Event | Fields | Description |
|-------|--------|-------------|
| `LOGIN` | username, ip | Successful authentication |
| `LOGIN_FAILED` | username, ip, reason, failure_count | Failed authentication |
| `UPLOAD` | username, ip, filename, bytes, duration_ms | File uploaded |
| `DOWNLOAD` | username, ip, filename, bytes, duration_ms | File downloaded |
| `DELETE` | username, ip, filename | File deleted |
| `MKDIR` | username, ip, directory | Directory created |
| `RENAME` | username, ip, argument | File/directory renamed |
| `DISCONNECT` | username, ip | Client disconnected |

---

## Security Hardening Checklist

1. Enable FTPS: `FTP_FTPS_ENABLED=true`
2. Require TLS: `FTP_REQUIRE_TLS=true`
3. Require data TLS: `FTP_REQUIRE_DATA_TLS=true` (enforce PROT P)
4. Restrict TLS versions: `FTP_TLS_PROTOCOLS=TLSv1.2,TLSv1.3`
5. Set cipher suites: `FTP_TLS_CIPHER_SUITES=TLS_AES_256_GCM_SHA384,...`
6. Use PKCS12 keystore: `FTP_TLS_KEYSTORE_TYPE=PKCS12`
7. Consider client certs: `FTP_TLS_CLIENT_AUTH=need` with a truststore
8. Disable active mode if not needed: `FTP_ACTIVE_MODE_ENABLED=false`
9. Block dangerous extensions: `FTP_DENIED_EXTENSIONS=exe,bat,sh,cmd`
10. Set upload limits: `FTP_MAX_UPLOAD_SIZE=104857600`
11. Set disk quotas: `FTP_DISK_QUOTA=1073741824`
12. Tune connection limits: `FTP_MAX_CONNECTIONS=200`
13. Keep bounce prevention enabled (default)
14. Keep anonymous FTP disabled (default)

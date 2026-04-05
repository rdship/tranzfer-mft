# FTP Service

> FTP/FTPS file transfer server with passive mode and TLS support.

**Port:** 21 (FTP) / 8082 (Management API) | **Database:** PostgreSQL | **Messaging:** RabbitMQ | **Required:** Optional

---

## Overview

The FTP service provides a fully functional FTP server built on Apache FtpServer. It handles:

- **FTP file transfers** with active and passive mode
- **FTPS** (FTP over TLS) with explicit and implicit modes
- **Password authentication** against the platform database
- **User home directories** with configurable permissions
- **File routing integration** — uploads trigger the platform's flow engine
- **Credential caching** with RabbitMQ-driven invalidation
- **Clustering** — multiple instances with unique instance IDs
- **Auto-generated TLS certificates** when FTPS is enabled

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
| GET | `/internal/health` | None | Service health with FTP server status |
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
  "instanceId": "ftp-1"
}
```

---

## FTP Protocol Details

### Authentication
- Password-only authentication
- BCrypt hash verification against `transfer_accounts` table
- Audit logging for all login attempts

### Connection Limits
- Max idle time: 300 seconds
- Max concurrent logins: 10 per account
- Max concurrent users: 5

### Passive Mode
FTP passive mode is required for clients behind NAT/firewalls. The server announces a public IP and a port range for data connections.

```
# Client connects to control port (21)
# Server responds with passive port (21000-21010)
# Client opens data connection to passive port
```

### FTPS (FTP over TLS)
When enabled, supports both explicit (AUTH TLS on port 21) and implicit modes. Self-signed certificates are auto-generated if no keystore exists.

### File Routing
Uploads and downloads are tracked via `FtpletRoutingAdapter`:
1. Client uploads a file
2. `onUploadEnd()` fires → routing engine processes the file
3. Downloads tracked via `onDownloadEnd()`

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8082` | Management API port |
| `FTP_PORT` | `21` | FTP listen port |
| `FTP_PUBLIC_HOST` | `127.0.0.1` | Public IP for passive mode (**set to server's public IP**) |
| `FTP_PASSIVE_PORT_START` | `21000` | Passive port range start |
| `FTP_PASSIVE_PORT_END` | `21010` | Passive port range end |
| `FTP_HOME_BASE` | `/data/ftp` | Root directory for user homes |
| `INSTANCE_ID` | `ftp-1` | Instance ID for clustering |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key |
| `ftp.ftps.enabled` | `false` | Enable FTPS |
| `ftp.ftps.keystore-path` | `./ftp-keystore.jks` | TLS keystore path |
| `ftp.ftps.keystore-password` | `changeit` | Keystore password |
| `ftp.ftps.protocol` | `TLSv1.2` | TLS protocol version |
| `ftp.ftps.client-auth` | `false` | Require client certificates |

---

## Architecture

```
┌──────────────────────────────────────────────────┐
│                   ftp-service                     │
├──────────────────────────────────────────────────┤
│  FTP Server (Apache FtpServer)                   │
│  ├── FtpUserManager (credential lookup)          │
│  ├── FtpsConfig (optional TLS)                   │
│  └── FtpletRoutingAdapter (upload/download)      │
├──────────────────────────────────────────────────┤
│  CredentialService (cached, BCrypt)              │
├──────────────────────────────────────────────────┤
│  RabbitMQ Consumer: ftp.account.events           │
│  Binding: account.* from file-transfer.events    │
└──────────────────────────────────────────────────┘
```

---

## Dependencies

- **PostgreSQL** — Required. Account credentials and transfer records.
- **RabbitMQ** — Required. Cache invalidation on account changes.
- **shared** module — Entities, routing engine, audit service.

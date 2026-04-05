# Gateway Service

> Protocol gateway that routes SFTP and FTP connections to the correct backend based on username.

**Port:** 2220 (SFTP) / 2121 (FTP) / 8085 (Management API) | **Database:** PostgreSQL | **Required:** Recommended

---

## Overview

The gateway service is a protocol-aware reverse proxy that sits between the DMZ proxy and the internal transfer services. It routes connections based on the authenticating username:

- **Known user with assigned server** → Routes to the assigned server instance
- **Known user without assignment** → Routes to the default internal service
- **Unknown user** → Routes to a configured legacy server (if any)
- **No route found** → Connection rejected

This enables multi-server deployments, legacy system integration, and user-based load distribution.

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq onboarding-api sftp-service gateway-service

# Verify
curl http://localhost:8085/internal/gateway/status

# Connect via gateway SFTP
sftp -P 2220 myuser@localhost
```

---

## API Endpoints

### Internal Management API (port 8085)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/internal/gateway/status` | X-Internal-Key | Gateway status (ports, running state) |
| GET | `/internal/gateway/legacy-servers` | X-Internal-Key | List legacy server configs |

**Status check:**
```bash
curl http://localhost:8085/internal/gateway/status \
  -H "X-Internal-Key: internal_control_secret"
```

**Response:**
```json
{
  "sftpGatewayPort": 2220,
  "ftpGatewayPort": 2121,
  "sftpServerRunning": true
}
```

**List legacy servers (optional protocol filter):**
```bash
curl "http://localhost:8085/internal/gateway/legacy-servers?protocol=SFTP" \
  -H "X-Internal-Key: internal_control_secret"
```

---

## How Routing Works

### SFTP Gateway (port 2220)
Built on Apache MINA SSHD:
1. Client connects to gateway on port 2220
2. Client authenticates with username/password
3. Gateway looks up the user → resolves backend SFTP server
4. Gateway connects to backend and authenticates on behalf of the user
5. SFTP filesystem is transparently proxied to the backend

### FTP Gateway (port 2121)
Built on Netty:
1. Client connects → gateway sends `220 File Transfer Gateway Ready`
2. Client sends `USER username`
3. Gateway looks up the user → resolves backend FTP server
4. Gateway connects to backend and replays the `USER` command
5. All subsequent FTP commands are bidirectionally forwarded

**Error responses:**
- Unknown user with no legacy server: `530 User unknown and no legacy server configured`
- Backend connection failure: `421 Backend connection failed`
- Command before USER: `530 Please send USER first`

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8085` | Management API port |
| `GATEWAY_SFTP_PORT` | `2220` | SFTP gateway listen port |
| `GATEWAY_FTP_PORT` | `2121` | FTP gateway listen port |
| `GATEWAY_HOST_KEY_PATH` | `./gateway_host_key` | SSH host key for SFTP proxy |
| `INTERNAL_SFTP_HOST` | `sftp-service` | Default SFTP backend host |
| `INTERNAL_SFTP_PORT` | `2222` | Default SFTP backend port |
| `INTERNAL_FTP_HOST` | `ftp-service` | Default FTP backend host |
| `INTERNAL_FTP_PORT` | `21` | Default FTP backend port |
| `INTERNAL_FTPWEB_HOST` | `ftp-web-service` | Default FTP-Web backend host |
| `INTERNAL_FTPWEB_PORT` | `8083` | Default FTP-Web backend port |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key |

---

## Architecture

```
  Client                Gateway Service              Backend
    │                        │                          │
    │──── SSH connect ──────>│                          │
    │                        │── lookup user ──> DB     │
    │                        │<── route decision ──     │
    │                        │                          │
    │                        │──── SSH connect ────────>│
    │                        │     (authenticate)       │
    │<─── SFTP session ─────>│<──── SFTP proxied ─────>│
    │     (transparent)      │                          │
```

---

## Dependencies

- **PostgreSQL** — Required. User routing lookups.
- **sftp-service** / **ftp-service** — Backend protocol services.
- **shared** module — Entities, server instance lookup.

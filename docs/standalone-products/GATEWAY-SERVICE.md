# Gateway Service — Standalone Product Guide

> **Protocol gateway with user-based routing.** Route SFTP and FTP connections to the right backend server based on user identity — known users go to assigned instances, unknown users go to legacy servers.

**Port:** 2220 (SFTP) + 2121 (FTP) + 8085 (HTTP) | **Dependencies:** PostgreSQL | **Auth:** Pass-through

---

## Why Use This

- **User-based routing** — Route connections by username to different backend servers
- **Protocol multiplexing** — Single entry point for SFTP and FTP
- **Legacy server support** — Route unknown users to existing (legacy) FTP/SFTP servers
- **Transparent proxy** — Clients connect as if talking to the real server
- **Migration-friendly** — Gradually migrate users from legacy to new platform

---

## Quick Start

```bash
docker compose up -d postgres gateway-service sftp-service ftp-service
curl http://localhost:8085/internal/gateway/status
```

```json
{
  "sftpGatewayPort": 2220,
  "ftpGatewayPort": 2121,
  "sftpGatewayRunning": true
}
```

---

## Routing Logic

```
Client connects to Gateway (port 2220/2121)
          │
          ▼
    Is user known?
    ├── YES: Has server assignment?
    │   ├── YES → Route to assigned ServerInstance
    │   └── NO  → Route to default internal service (sftp-service:2222)
    └── NO: Legacy server configured?
        ├── YES → Route to legacy server
        └── NO  → Reject connection
```

---

## Connecting Through the Gateway

### SFTP (port 2220)
```bash
# User with server assignment → routed to specific instance
sftp -P 2220 partner_acme@gateway.example.com

# Unknown user → routed to legacy server (if configured)
sftp -P 2220 legacy_user@gateway.example.com
```

### FTP (port 2121)
```bash
ftp gateway.example.com 2121
```

---

## HTTP API

### Gateway Status

**GET** `/internal/gateway/status`

```bash
curl http://localhost:8085/internal/gateway/status
```

### List Legacy Servers

**GET** `/internal/gateway/legacy-servers`

```bash
curl http://localhost:8085/internal/gateway/legacy-servers

# Filter by protocol
curl "http://localhost:8085/internal/gateway/legacy-servers?protocol=SFTP"
```

**Response:**
```json
[
  {"id": "...", "protocol": "SFTP", "host": "legacy-sftp.internal", "port": 22, "active": true},
  {"id": "...", "protocol": "FTP", "host": "legacy-ftp.internal", "port": 21, "active": true}
]
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `GATEWAY_SFTP_PORT` | `2220` | SFTP gateway listen port |
| `GATEWAY_FTP_PORT` | `2121` | FTP gateway listen port |
| `INTERNAL_SFTP_HOST` | `sftp-service` | Default SFTP backend host |
| `INTERNAL_SFTP_PORT` | `2222` | Default SFTP backend port |
| `INTERNAL_FTP_HOST` | `ftp-service` | Default FTP backend host |
| `INTERNAL_FTP_PORT` | `21` | Default FTP backend port |
| `server.port` | `8085` | HTTP management port |

---

## All Endpoints Summary

| Protocol | Port | Path | Description |
|----------|------|------|-------------|
| SFTP | 2220 | N/A | SFTP gateway (user-based routing) |
| FTP | 2121 | N/A | FTP gateway (user-based routing) |
| HTTP | 8085 | GET `/internal/gateway/status` | Gateway status |
| HTTP | 8085 | GET `/internal/gateway/legacy-servers` | List legacy servers |

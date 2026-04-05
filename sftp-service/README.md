# SFTP Service

> SSH-based secure file transfer server with public key and password authentication.

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
  "instanceId": "sftp-1"
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
- Login attempts logged as audit events (LOGIN or LOGIN_FAIL)

**Public key authentication:**
- Keys stored in OpenSSH `authorized_keys` format in the account record
- Supports RSA, ECDSA, Ed25519 keys

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

## Configuration

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

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                  sftp-service                        │
├──────────────────────────────────────────────────────┤
│  SSH/SFTP Server (Apache MINA SSHD)                 │
│  ├── SftpPasswordAuthenticator                      │
│  ├── SftpPublicKeyAuthenticator                     │
│  ├── SftpFileSystemFactory (sandboxed per user)     │
│  └── SftpRoutingEventListener (upload/download)     │
├──────────────────────────────────────────────────────┤
│  CredentialService (cached, RabbitMQ-invalidated)   │
├──────────────────────────────────────────────────────┤
│  RabbitMQ Consumer: sftp.account.events             │
│  Binding: account.* from file-transfer.events       │
└──────────────────────────────────────────────────────┘
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

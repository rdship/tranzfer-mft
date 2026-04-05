# Onboarding API — Standalone Product Guide

> **Central platform API.** Authentication, account management, file transfers, partner portal, multi-tenancy, 2FA, peer-to-peer transfers, and blockchain audit trails.

**Port:** 8080 | **Dependencies:** PostgreSQL, RabbitMQ | **Auth:** JWT Bearer token

---

## Why Use This

- **Complete auth system** — Register, login, JWT tokens (15-min expiry)
- **Account management** — CRUD for SFTP/FTP transfer accounts
- **File transfer API** — Upload files with tracking, batch transfers, delivery receipts
- **Partner portal** — Self-service API for trading partners
- **Multi-tenancy** — Tenant signup with isolated environments
- **2FA/TOTP** — Two-factor authentication with backup codes
- **P2P transfers** — Peer-to-peer file transfer with tickets
- **Blockchain audit** — SHA-256 chain with Merkle root anchoring

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq onboarding-api

# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@example.com", "password": "securepass123"}'

# Login
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@example.com", "password": "securepass123"}' | jq -r '.accessToken')
```

---

## API Reference — Authentication

### Register

**POST** `/api/auth/register`

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "securepass123"}'
```

**Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### Login

**POST** `/api/auth/login`

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com", "password": "securepass123"}'
```

---

## API Reference — Accounts

### Create Transfer Account

**POST** `/api/accounts`

```bash
curl -X POST http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "protocol": "SFTP",
    "username": "partner_acme",
    "password": "strong_password_123",
    "publicKey": "ssh-rsa AAAAB3...",
    "permissions": {"upload": true, "download": true, "delete": false}
  }'
```

**Response:**
```json
{
  "id": "a1b2c3d4-...",
  "protocol": "SFTP",
  "username": "partner_acme",
  "homeDir": "/data/sftp/partner_acme",
  "permissions": {"upload": true, "download": true, "delete": false},
  "active": true,
  "connectionInstructions": "sftp -P 2222 partner_acme@mft.example.com"
}
```

### Get / Update / Delete Account

```bash
# Get
curl http://localhost:8080/api/accounts/{id}

# Update
curl -X PATCH http://localhost:8080/api/accounts/{id} \
  -H "Content-Type: application/json" \
  -d '{"active": false}'

# Delete
curl -X DELETE http://localhost:8080/api/accounts/{id}
```

---

## API Reference — File Transfer

### Upload & Transfer File

**POST** `/api/v2/transfer`

```bash
curl -X POST http://localhost:8080/api/v2/transfer \
  -F "file=@/path/to/invoice.csv" \
  -F "sender=partner_acme" \
  -F "destination=partner_vendor" \
  -F "webhookUrl=https://myapp.com/webhook/transfer-complete"
```

**Response:**
```json
{
  "trackId": "TRZA3X5T3LUY",
  "filename": "invoice.csv",
  "sizeBytes": 1048576,
  "sender": "partner_acme",
  "destination": "partner_vendor",
  "status": "ACCEPTED",
  "message": "File accepted for processing",
  "pollUrl": "/api/v2/transfer/TRZA3X5T3LUY",
  "receiptUrl": "/api/v2/transfer/TRZA3X5T3LUY/receipt"
}
```

### Track Transfer Status

**GET** `/api/v2/transfer/{trackId}`

```bash
curl http://localhost:8080/api/v2/transfer/TRZA3X5T3LUY
```

**Response:**
```json
{
  "trackId": "TRZA3X5T3LUY",
  "filename": "invoice.csv",
  "status": "COMPLETED",
  "sizeBytes": 1048576,
  "uploadedAt": "2026-04-05T14:00:00Z",
  "completedAt": "2026-04-05T14:00:05Z",
  "sourceChecksum": "sha256:abc123...",
  "destinationChecksum": "sha256:abc123...",
  "integrityVerified": true
}
```

### Batch Transfer

**POST** `/api/v2/transfer/batch`

```bash
curl -X POST http://localhost:8080/api/v2/transfer/batch \
  -F "files=@file1.csv" \
  -F "files=@file2.csv" \
  -F "files=@file3.csv" \
  -F "sender=partner_acme"
```

### Get Delivery Receipt

**GET** `/api/v2/transfer/{trackId}/receipt`

```bash
curl http://localhost:8080/api/v2/transfer/TRZA3X5T3LUY/receipt
```

---

## API Reference — Partner Portal

### Partner Login

**POST** `/api/partner/login`

```bash
curl -X POST http://localhost:8080/api/partner/login \
  -H "Content-Type: application/json" \
  -d '{"username": "partner_acme", "password": "partner_password"}'
```

### Partner Dashboard

**GET** `/api/partner/dashboard`

### Partner Transfers

**GET** `/api/partner/transfers?page=0&size=20`

### Track File

**GET** `/api/partner/track/{trackId}`

### Delivery Receipt

**GET** `/api/partner/receipt/{trackId}`

### Rotate SSH Key

**POST** `/api/partner/rotate-key`

```bash
curl -X POST http://localhost:8080/api/partner/rotate-key \
  -H "Authorization: Bearer $PARTNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"publicKey": "ssh-rsa AAAAB3NzaC1yc2EA..."}'
```

---

## API Reference — 2FA/TOTP

```bash
# Enable 2FA
curl -X POST http://localhost:8080/api/2fa/enable \
  -H "Content-Type: application/json" \
  -d '{"username": "partner_acme"}'

# Verify code
curl -X POST http://localhost:8080/api/2fa/verify \
  -H "Content-Type: application/json" \
  -d '{"username": "partner_acme", "code": "123456"}'

# Check status
curl http://localhost:8080/api/2fa/status/partner_acme

# Disable
curl -X POST http://localhost:8080/api/2fa/disable \
  -H "Content-Type: application/json" \
  -d '{"username": "partner_acme"}'
```

---

## API Reference — Multi-Tenancy

### Tenant Signup

**POST** `/api/v1/tenants/signup`

```bash
curl -X POST http://localhost:8080/api/v1/tenants/signup \
  -H "Content-Type: application/json" \
  -d '{
    "slug": "acme-corp",
    "companyName": "Acme Corporation",
    "email": "admin@acme.com"
  }'
```

**Response:**
```json
{
  "tenantId": "a1b2c3d4-...",
  "slug": "acme-corp",
  "domain": "acme-corp.tranzfer.io",
  "plan": "TRIAL (30 days)",
  "sftpHost": "acme-corp.sftp.tranzfer.io",
  "portalUrl": "https://acme-corp.portal.tranzfer.io"
}
```

---

## API Reference — Transfer Journey

**GET** `/api/journey/{trackId}`

```bash
curl http://localhost:8080/api/journey/TRZA3X5T3LUY
```

**Response:**
```json
{
  "trackId": "TRZA3X5T3LUY",
  "filename": "invoice.csv",
  "overallStatus": "COMPLETED",
  "stages": [
    {"order": 1, "stage": "UPLOAD", "status": "COMPLETED", "timestamp": "..."},
    {"order": 2, "stage": "ROUTING", "status": "COMPLETED", "timestamp": "..."},
    {"order": 3, "stage": "DELIVERY", "status": "COMPLETED", "timestamp": "..."}
  ],
  "integrityStatus": "VERIFIED",
  "totalDurationMs": 5230
}
```

---

## API Reference — Blockchain Audit

**GET** `/api/v1/blockchain/verify/{trackId}`

```bash
curl http://localhost:8080/api/v1/blockchain/verify/TRZA3X5T3LUY
```

**Response:**
```json
{
  "verified": true,
  "trackId": "TRZA3X5T3LUY",
  "sha256": "abc123...",
  "merkleRoot": "def456...",
  "anchoredAt": "2026-04-05T15:00:00Z"
}
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `JWT_SECRET` | (insecure default) | JWT signing secret |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier |
| `server.port` | `8080` | HTTP port |

---

## All Endpoints Summary

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/auth/register` | None | Register user |
| POST | `/api/auth/login` | None | Login |
| POST | `/api/accounts` | JWT | Create transfer account |
| GET | `/api/accounts/{id}` | JWT | Get account |
| PATCH | `/api/accounts/{id}` | JWT | Update account |
| DELETE | `/api/accounts/{id}` | JWT | Delete account |
| POST | `/api/v2/transfer` | None | Upload & transfer file |
| GET | `/api/v2/transfer/{trackId}` | None | Track transfer status |
| POST | `/api/v2/transfer/batch` | None | Batch transfer |
| GET | `/api/v2/transfer/{trackId}/receipt` | None | Delivery receipt |
| POST | `/api/partner/login` | None | Partner login |
| GET | `/api/partner/dashboard` | JWT | Partner dashboard |
| GET | `/api/partner/transfers` | JWT | Partner transfer history |
| GET | `/api/partner/track/{trackId}` | JWT | Track file |
| POST | `/api/partner/rotate-key` | JWT | Rotate SSH key |
| POST | `/api/partner/change-password` | JWT | Change password |
| GET | `/api/partner/sla` | JWT | SLA compliance |
| POST | `/api/2fa/enable` | None | Enable 2FA |
| POST | `/api/2fa/verify` | None | Verify TOTP code |
| GET | `/api/2fa/status/{username}` | None | 2FA status |
| POST | `/api/v1/tenants/signup` | None | Tenant signup |
| GET | `/api/v1/tenants` | None | List tenants |
| GET | `/api/journey/{trackId}` | None | Transfer journey |
| GET | `/api/v1/blockchain/verify/{trackId}` | None | Blockchain verify |
| GET | `/api/v1/blockchain/anchors` | None | Blockchain anchors |
| GET | `/api/servers` | None | List servers |
| POST | `/api/servers` | None | Create server |
| GET | `/api/clusters` | Admin | List clusters |
| GET | `/api/service-registry` | None | Service registry |
| POST | `/api/cli/execute` | Admin | CLI command |
| POST | `/api/v1/auto-onboard/detect` | None | Auto-onboard detect |
| POST | `/api/p2p/tickets` | None | Create P2P ticket |

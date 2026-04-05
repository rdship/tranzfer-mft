# Onboarding API

> Central platform API — authentication, accounts, transfers, clustering, and partner onboarding.

**Port:** 8080 | **Database:** PostgreSQL | **Messaging:** RabbitMQ | **Required:** Yes

---

## Overview

The onboarding-api is the primary REST API for the TranzFer MFT platform. It handles:

- **User registration and login** with JWT authentication
- **Transfer account management** (SFTP, FTP, FTP-Web, AS2)
- **File transfer tracking** with end-to-end journey audit
- **Folder mappings** to route files between accounts
- **Cluster management** for multi-node deployments
- **Partner portal API** for self-service partner access
- **Peer-to-peer transfers** with server coordination
- **Zero-touch partner onboarding** with behavior learning
- **Two-factor authentication** (TOTP/RFC 6238)
- **Multi-tenancy** for SaaS deployments
- **Blockchain anchoring** for non-repudiation

---

## Quick Start

```bash
# 1. Start dependencies
docker compose up -d postgres rabbitmq

# 2. Build shared library + this service
mvn clean install -pl shared -DskipTests
mvn clean package -pl onboarding-api -DskipTests

# 3. Run
cd onboarding-api
mvn spring-boot:run

# 4. Verify
curl http://localhost:8080/actuator/health
```

Or with Docker:
```bash
docker compose up -d postgres rabbitmq onboarding-api
```

---

## API Endpoints

### Authentication

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/auth/register` | None | Register a new user |
| POST | `/api/auth/login` | None | Login, returns JWT token |

**Register:**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@company.com","password":"MyPassword123!"}'
```

**Response:**
```json
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 900000
}
```

**Login:**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@company.com","password":"MyPassword123!"}'
```

**Password requirements** (PCI DSS 8.2): minimum 8 characters, at least 1 uppercase, 1 lowercase, 1 digit, 1 special character.

**Brute force protection** (PCI DSS 8.1.6): 6 failed attempts → 30-minute lockout.

---

### Transfer Accounts

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/accounts` | Bearer | Create transfer account |
| GET | `/api/accounts/{id}` | Bearer | Get account details |
| PATCH | `/api/accounts/{id}` | Bearer | Update account |
| DELETE | `/api/accounts/{id}` | Bearer | Soft-delete account |

**Create account:**
```bash
curl -X POST http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "protocol": "SFTP",
    "username": "partner-acme",
    "password": "SecurePass123!",
    "permissions": {"read": true, "write": true, "delete": false}
  }'
```

**Response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "protocol": "SFTP",
  "username": "partner-acme",
  "homeDir": "/data/sftp/partner-acme",
  "permissions": {"read": true, "write": true, "delete": false},
  "active": true,
  "createdAt": "2026-04-05T10:30:00Z",
  "connectionInstructions": "sftp -P 2222 partner-acme@your-server"
}
```

**Update account:**
```bash
curl -X PATCH http://localhost:8080/api/accounts/{id} \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"active": false}'
```

---

### Folder Mappings

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/folder-mappings` | Bearer | Create folder mapping |
| GET | `/api/folder-mappings?accountId={id}` | Bearer | List mappings for account |
| GET | `/api/folder-mappings/{id}` | Bearer | Get single mapping |
| PATCH | `/api/folder-mappings/{id}/active?value=true` | Bearer | Enable/disable mapping |
| DELETE | `/api/folder-mappings/{id}` | Bearer | Delete mapping |

**Create mapping:**
```bash
curl -X POST http://localhost:8080/api/folder-mappings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "uuid-of-partner-a",
    "sourcePath": "/outbox",
    "destinationAccountId": "uuid-of-internal-team",
    "destinationPath": "/inbox",
    "filenamePattern": "*.csv"
  }'
```

---

### Server Instances

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/api/servers` | Bearer | List server instances |
| GET | `/api/servers/{id}` | Bearer | Get server by UUID |
| GET | `/api/servers/by-instance/{instanceId}` | Bearer | Get by instance ID string |
| POST | `/api/servers` | Bearer | Create server instance |
| PATCH | `/api/servers/{id}` | Bearer | Update server |
| DELETE | `/api/servers/{id}` | Bearer | Soft-delete server |

**Query parameters for GET /api/servers:**
- `activeOnly` (boolean, default false) — filter to active only
- `protocol` (SFTP/FTP/FTP_WEB/HTTPS/AS2) — filter by protocol

---

### Transfer Journey (File Tracking)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/api/journey/{trackId}` | Bearer | Complete transfer journey |
| GET | `/api/journey?filename=...&status=...&limit=20` | Bearer | Search journeys |

**Track a transfer:**
```bash
curl http://localhost:8080/api/journey/TRZA3X5T3LUY \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
{
  "trackId": "TRZA3X5T3LUY",
  "filename": "invoice-2026.csv",
  "fileSize": 245760,
  "status": "COMPLETED",
  "stages": [
    {"stage": "FILE_RECEIVED", "timestamp": "2026-04-05T10:30:00Z"},
    {"stage": "AI_CLASSIFICATION", "timestamp": "2026-04-05T10:30:01Z"},
    {"stage": "FLOW_PROCESSING", "timestamp": "2026-04-05T10:30:02Z"},
    {"stage": "SCREENING", "timestamp": "2026-04-05T10:30:03Z"},
    {"stage": "DELIVERY", "timestamp": "2026-04-05T10:30:04Z"},
    {"stage": "COMPLETE", "timestamp": "2026-04-05T10:30:05Z"}
  ],
  "sourceChecksum": "sha256:abcdef...",
  "destinationChecksum": "sha256:abcdef...",
  "integrityVerified": true,
  "totalDurationMs": 5000,
  "auditTrail": [...]
}
```

---

### Transfer API v2 (Single-Call File Transfer)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/v2/transfer` | Bearer | Upload file (multipart) |
| GET | `/api/v2/transfer/{trackId}` | Bearer | Poll transfer status |
| POST | `/api/v2/transfer/batch` | Bearer | Upload multiple files |
| GET | `/api/v2/transfer/{trackId}/receipt` | Bearer | Get delivery receipt |

**Upload file:**
```bash
curl -X POST http://localhost:8080/api/v2/transfer \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@invoice.csv" \
  -F "sender=partner-acme" \
  -F "flow=encrypt-and-screen"
```

**Response (202 Accepted):**
```json
{
  "trackId": "TRZA3X5T3LUY",
  "filename": "invoice.csv",
  "sizeBytes": 245760,
  "status": "ACCEPTED",
  "pollUrl": "/api/v2/transfer/TRZA3X5T3LUY",
  "receiptUrl": "/api/v2/transfer/TRZA3X5T3LUY/receipt"
}
```

---

### Peer-to-Peer Transfers

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/p2p/presence` | Bearer | Register client presence |
| GET | `/api/p2p/presence` | Bearer | List online clients |
| POST | `/api/p2p/tickets` | Bearer | Request transfer ticket |
| POST | `/api/p2p/tickets/{id}/validate` | Bearer | Validate ticket |
| POST | `/api/p2p/tickets/{id}/complete` | Bearer | Mark transfer complete |
| GET | `/api/p2p/tickets?username=...` | Bearer | List tickets for user |

**How P2P works:**
1. Both clients register presence with the server
2. Sender requests a transfer ticket (server validates both accounts)
3. Sender connects directly to receiver using ticket info
4. Receiver validates the ticket token with the server
5. File transfers directly between clients
6. Completion reported to server for audit trail

---

### Two-Factor Authentication

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/2fa/enable` | Bearer | Enable 2FA for user |
| POST | `/api/2fa/verify` | None | Verify TOTP code |
| GET | `/api/2fa/status/{username}` | Bearer | Check 2FA status |
| POST | `/api/2fa/disable` | Bearer | Disable 2FA |

**Enable 2FA:**
```bash
curl -X POST http://localhost:8080/api/2fa/enable \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin@company.com","method":"TOTP_APP"}'
```

**Response:**
```json
{
  "username": "admin@company.com",
  "secret": "JBSWY3DPEHPK3PXP",
  "method": "TOTP_APP",
  "provisioningUri": "otpauth://totp/TranzFer:admin@company.com?secret=JBSWY3DPEHPK3PXP&issuer=TranzFer",
  "backupCodes": ["12345678", "23456789", "..."],
  "instructions": "Scan the QR code with your authenticator app"
}
```

---

### Partner Portal API

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/partner/login` | None | Partner login (account credentials) |
| GET | `/api/partner/dashboard` | Bearer | Partner overview dashboard |
| GET | `/api/partner/transfers?page=0&size=20&status=...` | Bearer | List partner transfers |
| GET | `/api/partner/track/{trackId}` | Bearer | Track single transfer |
| GET | `/api/partner/receipt/{trackId}` | Bearer | Get delivery receipt |
| GET | `/api/partner/test-connection` | Bearer | Test connectivity info |
| POST | `/api/partner/rotate-key` | Bearer | Upload new SSH key |
| POST | `/api/partner/change-password` | Bearer | Change password |
| GET | `/api/partner/sla` | Bearer | SLA compliance (last 7 days) |

**Partner login** (uses transfer account credentials, not user credentials):
```bash
curl -X POST http://localhost:8080/api/partner/login \
  -H "Content-Type: application/json" \
  -d '{"username":"partner-acme","password":"SecurePass123!"}'
```

---

### Autonomous Onboarding (Zero-Touch)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/v1/auto-onboard/detect` | Bearer | Auto-detect unknown client |
| POST | `/api/v1/auto-onboard/learn` | Bearer | Report file pattern for learning |
| GET | `/api/v1/auto-onboard/sessions` | Bearer | List onboarding sessions |
| GET | `/api/v1/auto-onboard/sessions/{id}` | Bearer | Get session details |
| POST | `/api/v1/auto-onboard/sessions/{id}/approve` | Bearer | Admin approval |
| GET | `/api/v1/auto-onboard/stats` | Bearer | Onboarding statistics |

**How zero-touch onboarding works:**
1. **DETECT** — Unknown client connects; platform auto-generates credentials
2. **ACCOUNT_CREATED** — Transfer account provisioned automatically
3. **LEARNING** — Platform observes file patterns (types, sizes, frequency)
4. **COMPLETE** — After 24 hours or admin approval, onboarding completes

---

### Multi-Tenancy

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/v1/tenants/signup` | None | Self-service tenant signup |
| GET | `/api/v1/tenants` | Bearer | List all tenants |
| GET | `/api/v1/tenants/{slug}` | Bearer | Get tenant details |
| GET | `/api/v1/tenants/{slug}/usage` | Bearer | Get tenant usage stats |

---

### Blockchain Anchoring

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/api/v1/blockchain/verify/{trackId}` | Bearer | Verify blockchain proof |
| GET | `/api/v1/blockchain/anchors` | Bearer | List recent anchors |

Blockchain anchoring runs automatically every hour, building Merkle trees from recent transfers for non-repudiation proof.

---

### Cluster Management (Admin Only)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/api/clusters` | ADMIN | List all clusters |
| GET | `/api/clusters/{clusterId}` | ADMIN | Get cluster details |
| PUT | `/api/clusters/{clusterId}` | ADMIN | Update cluster settings |
| GET | `/api/clusters/communication-mode` | ADMIN | Get communication mode |
| PUT | `/api/clusters/communication-mode` | ADMIN | Change communication mode |
| GET | `/api/clusters/topology` | ADMIN | Full cluster topology |

---

### Admin CLI API (Admin Only)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/cli/execute` | ADMIN | Execute admin command |

**Available commands:** `help`, `status`, `accounts list|create|enable|disable`, `users list|promote|demote`, `flows list|info`, `track <trackId>`, `search file|account|recent`, `services`, `cluster status|list|services|mode`, `logs recent`, `onboard <email> <password>`, `version`, `whoami`

---

### Service Registry (Admin Only)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/api/service-registry` | ADMIN | List active service registrations |

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8080` | HTTP port |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | JWT signing key (**change in production**) |
| `JWT_EXPIRATION_MS` | `900000` | Token expiry (15 min) |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ port |
| `SFTP_HOME_BASE` | `/data/sftp` | SFTP home directory root |
| `FTP_HOME_BASE` | `/data/ftp` | FTP home directory root |
| `FTPWEB_HOME_BASE` | `/data/ftpweb` | FTP-Web home directory root |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment name |
| `TRACK_ID_PREFIX` | `TRZ` | Transfer tracking ID prefix |

**Connection pool:** 8 max connections, 3 min idle, 5-minute idle timeout, 20-minute max lifetime.

---

## Architecture

```
┌────────────────────────────────────────────────────────┐
│                   onboarding-api                       │
├──────────────┬──────────────┬──────────────────────────┤
│  Controllers │   Services   │    Security              │
│  (16 REST)   │  (4 core)    │  JWT + RBAC + 2FA        │
├──────────────┼──────────────┼──────────────────────────┤
│  AuthCtrl    │  AuthService │  SecurityConfig          │
│  AccountCtrl │  AccountSvc  │  JwtAuthFilter           │
│  FolderMap   │  FolderMap   │  BruteForceProtection    │
│  ServerInst  │  ServerInst  │  PasswordPolicy          │
│  Journey     │              │                          │
│  TransferV2  │              │                          │
│  P2P         │              │                          │
│  Partner     │              │                          │
│  2FA (TOTP)  │              │                          │
│  AutoOnboard │              │                          │
│  Cluster     │              │                          │
│  Blockchain  │              │                          │
│  CLI         │              │                          │
│  Tenant      │              │                          │
│  ServiceReg  │              │                          │
├──────────────┴──────────────┴──────────────────────────┤
│  RabbitMQ Events: account.created, account.updated     │
│  Exchange: file-transfer.events (Topic)                │
└────────────────────────────────────────────────────────┘
```

**Role-based access:**
- Public: `/api/auth/**`, `/api/partner/login`, `/api/2fa/verify`, `/actuator/health`
- ADMIN only: `/api/cli/**`, `/api/clusters/**`, `/api/service-registry`
- Authenticated: Everything else

---

## Dependencies

- **PostgreSQL** — Required. User accounts, transfer records, audit logs.
- **RabbitMQ** — Required. Publishes `account.created` and `account.updated` events.
- **shared** module — Required. Entities, repositories, JWT, routing engine.
- **sftp-service** / **ftp-service** — Called for credential provisioning.

---

## Development

```bash
# Build
mvn clean install -pl shared -DskipTests && mvn clean package -pl onboarding-api -DskipTests

# Run tests
mvn test -pl onboarding-api

# Run locally
mvn spring-boot:run -pl onboarding-api \
  -Dspring-boot.run.arguments="--DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer"
```

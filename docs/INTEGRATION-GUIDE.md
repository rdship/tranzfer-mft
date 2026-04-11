# TranzFer MFT -- Integration Guide

> The complete reference for integrating TranzFer Managed File Transfer services into your
> existing infrastructure. Whether you need the full platform, a single microservice, or a
> headless API backend -- this guide gets you from zero to production.

---

## Table of Contents

- [Who This Is For](#who-this-is-for)
- [Integration Patterns](#integration-patterns)
  - [Pattern 1: Full Platform (All Services)](#pattern-1-full-platform-all-services)
  - [Pattern 2: File Transfer Core Only](#pattern-2-file-transfer-core-only)
  - [Pattern 3: Add Screening and Compliance](#pattern-3-add-screening-and-compliance)
  - [Pattern 4: API-First Integration (Headless)](#pattern-4-api-first-integration-headless)
  - [Pattern 5: Single Service Extraction](#pattern-5-single-service-extraction)
- [Authentication](#authentication)
  - [For External API Consumers](#for-external-api-consumers-partners-clis-custom-uis)
  - [For Service-to-Service (Internal)](#for-service-to-service-internal)
  - [For Partner Portal](#for-partner-portal)
- [API Integration by Use Case](#api-integration-by-use-case)
  - [Use Case 1: Receive Files from Partners via SFTP](#use-case-1-receive-files-from-partners-via-sftp)
  - [Use Case 2: Send Files to External Partners](#use-case-2-send-files-to-external-partners)
  - [Use Case 3: Screen Files for Sanctions and DLP](#use-case-3-screen-files-for-sanctions-and-dlp)
  - [Use Case 4: Encrypt and Decrypt Files](#use-case-4-encrypt-and-decrypt-files)
  - [Use Case 5: Convert EDI Documents](#use-case-5-convert-edi-documents)
  - [Use Case 6: Monitor Platform Health](#use-case-6-monitor-platform-health)
  - [Use Case 7: Classify Files Using AI](#use-case-7-classify-files-using-ai)
  - [Use Case 8: Manage Cryptographic Keys](#use-case-8-manage-cryptographic-keys)
  - [Use Case 9: AS2/AS4 Trading Partner Communication](#use-case-9-as2as4-trading-partner-communication)
  - [Use Case 10: Real-Time Notifications](#use-case-10-real-time-notifications)
  - [Use Case 11: Migrate from a Legacy MFT Product](#use-case-11-migrate-from-a-legacy-mft-product)
  - [Use Case 12: Content-Addressable Storage](#use-case-12-content-addressable-storage)
  - [Use Case 13: Threat Intelligence](#use-case-13-threat-intelligence)
  - [Use Case 14: Build a Custom UI](#use-case-14-build-a-custom-ui)
- [Webhook Integration](#webhook-integration)
- [SDK and Client Libraries](#sdk-and-client-libraries)
  - [JavaScript / TypeScript](#javascript--typescript)
  - [Java](#java)
  - [cURL Quick Reference](#curl-quick-reference)
  - [Python](#python)
- [Deployment Options](#deployment-options)
  - [Docker Compose (Development)](#docker-compose-development)
  - [Kubernetes (Production)](#kubernetes-production)
  - [Bare Metal](#bare-metal)
  - [Cloud (AWS/Azure/GCP)](#cloud-awsazuregcp)
- [Troubleshooting Integration Issues](#troubleshooting-integration-issues)
- [Service Port Map](#service-port-map)
- [Health Check Endpoints](#health-check-endpoints)

---

## Who This Is For

| Audience | Start Here |
|---|---|
| **Enterprise architects** evaluating which services to adopt | [Integration Patterns](#integration-patterns) |
| **Developers** integrating TranzFer into existing systems | [API Integration by Use Case](#api-integration-by-use-case) |
| **DevOps teams** deploying individual services | [Deployment Options](#deployment-options) |
| **Partners** building integrations against TranzFer APIs | [Authentication](#authentication) then [SDK and Client Libraries](#sdk-and-client-libraries) |
| **Security teams** evaluating compliance capabilities | [Use Case 3: Screening](#use-case-3-screen-files-for-sanctions-and-dlp) and [Use Case 13: Threat Intel](#use-case-13-threat-intelligence) |

---

## Integration Patterns

### Pattern 1: Full Platform (All Services)

Deploy everything. You get the complete Managed File Transfer platform with all 19 services,
three frontend applications, SPIFFE zero-trust identity, and full observability.

**What you get:**

- SFTP/FTP/HTTPS/AS2/AS4 file ingestion
- Multi-step flow processing (encrypt, compress, screen, convert, route)
- AI classification, anomaly detection, and threat intelligence
- OFAC/EU/UN sanctions screening with DLP and antivirus
- Centralized key management with automatic rotation
- EDI conversion across 110 format paths
- Content-addressable storage with deduplication and tiered lifecycle
- Real-time notifications (Slack, Teams, PagerDuty, webhook)
- Platform health monitoring with Sentinel
- Admin UI, Partner Portal, and FTP-Web UI

**Deploy:**

```bash
cd file-transfer-platform
docker compose up -d
```

This starts all infrastructure (PostgreSQL, Redis, RabbitMQ, SPIRE) and all services.
The `spire-init` container auto-bootstraps workload identities -- no manual steps needed.

**First steps after deployment:**

1. Open the Admin UI: http://localhost:3000
2. Login: `admin@tranzfer.io` / `admin123`
3. Create your first partner and transfer account via the onboarding wizard
4. Test SFTP connectivity: `sftp -P 2222 <username>@localhost`

**Resource requirements:** 8 GB RAM minimum (16 GB recommended), 4 CPU cores.

---

### Pattern 2: File Transfer Core Only

Minimum viable deployment for SFTP file reception, storage, and basic routing.

**Services needed:**

| Service | Port | Purpose |
|---|---|---|
| onboarding-api | 8080 | Core API, user auth, partner/account management |
| config-service | 8084 | Flow definitions, connectors, folder mappings |
| sftp-service | 8081/2222 | SFTP file reception |
| storage-manager | 8096 | Content-addressable file storage |
| PostgreSQL | 5432 | Shared database |
| RabbitMQ | 5672 | Event bus |
| Redis | 6379 | Caching, session registry |

**Deploy selectively:**

```bash
docker compose up -d postgres redis rabbitmq \
  onboarding-api config-service sftp-service storage-manager
```

**What this gives you:**

- SFTP file reception with virtual filesystem
- Partner and account management via REST API
- Configurable multi-step flows (compress, rename, route)
- Content-addressable storage with SHA-256 deduplication
- Activity monitoring and audit trails

**What you do NOT get:**

- No AI classification or anomaly detection
- No sanctions screening or DLP
- No encryption service (unless you add it)
- No analytics dashboard
- No Sentinel health monitoring
- No EDI conversion

**Resource requirements:** 2 GB RAM, 2 CPU cores.

---

### Pattern 3: Add Screening and Compliance

Add sanctions screening, DLP, and AI classification to Pattern 2.

**Additional services:**

| Service | Port | Purpose |
|---|---|---|
| screening-service | 8092 | OFAC/EU/UN sanctions, DLP, antivirus |
| ai-engine | 8091 | Data classification, anomaly detection |
| encryption-service | 8086 | AES-256-GCM and PGP encryption |
| keystore-manager | 8093 | Centralized key/certificate management |

**Deploy:**

```bash
docker compose up -d postgres redis rabbitmq \
  onboarding-api config-service sftp-service storage-manager \
  screening-service ai-engine encryption-service keystore-manager
```

**What this adds:**

- Every file is screened against OFAC, EU, and UN sanctions lists (auto-refreshed every 6 hours)
- DLP policy enforcement (PCI, PII, PHI pattern detection)
- AI-powered data classification with confidence scoring
- PGP and AES-256-GCM encryption/decryption
- Centralized key lifecycle management

**Resource requirements:** 4 GB RAM, 3 CPU cores.

---

### Pattern 4: API-First Integration (Headless)

Use TranzFer as a backend engine. Bring your own UI. All functionality is available via REST APIs.

**Key decisions:**

1. **Authentication:** POST `/api/auth/login` to get a JWT, include it as `Authorization: Bearer {token}`
2. **Gateway mode:** Set `VITE_API_GATEWAY_URL` if using a single gateway, or call services directly on their ports
3. **Event-driven:** Subscribe to RabbitMQ exchange `file-transfer.events` for real-time file events

**API surface overview:**

| Domain | Service | Base Path | Key Operations |
|---|---|---|---|
| Auth | onboarding-api:8080 | `/api/auth` | register, login |
| Partners | onboarding-api:8080 | `/api/partners` | CRUD, activate, suspend |
| Accounts | onboarding-api:8080 | `/api/accounts` | CRUD for SFTP/FTP accounts |
| Servers | onboarding-api:8080 | `/api/servers` | CRUD for protocol server instances |
| Transfers | onboarding-api:8080 | `/api/v2/transfer` | Single-call file transfer |
| Flows | config-service:8084 | `/api/flows` | CRUD, step types, executions |
| Connectors | config-service:8084 | `/api/connectors` | Slack/Teams/PagerDuty/webhook |
| Screening | screening-service:8092 | `/api/v1/screening` | Scan files, view results |
| Quarantine | screening-service:8092 | `/api/v1/quarantine` | Release/delete quarantined files |
| DLP | screening-service:8092 | `/api/v1/dlp` | Policy CRUD, manual scan |
| AI | ai-engine:8091 | `/api/v1/ai` | Classify, anomalies, NLP |
| Keys | keystore-manager:8093 | `/api/v1/keys` | Generate, import, rotate |
| Encryption | encryption-service:8086 | `/api/encrypt` | Encrypt/decrypt files |
| EDI | edi-converter:8095 | `/api/v1/convert` | Detect, parse, convert, validate |
| Storage | storage-manager:8096 | `/api/v1/storage` | Store, retrieve, stream |
| Notifications | notification-service:8097 | `/api/notifications` | Templates, rules, test |
| Sentinel | platform-sentinel:8098 | `/api/v1/sentinel` | Health score, findings, rules |
| Threats | ai-engine:8091 | `/api/v1/threats` | Indicators, MITRE mapping, hunt |
| AS2 | config-service:8084 | `/api/as2-partnerships` | Partnership CRUD |
| Migration | config-service:8084 | `/api/v1/migration` | Legacy MFT migration |
| Analytics | analytics-service:8090 | `/api/analytics` | Dashboard, predictions |

---

### Pattern 5: Single Service Extraction

Run just one TranzFer service standalone for a specific capability.

**Stateless services (no database required):**

| Service | Standalone Use Case |
|---|---|
| edi-converter (8095) | EDI format detection, parsing, conversion, validation |

**Services requiring only PostgreSQL:**

| Service | Standalone Use Case |
|---|---|
| encryption-service (8086) | File encryption/decryption as a microservice |
| keystore-manager (8093) | Centralized key/certificate management |
| screening-service (8092) | Sanctions screening and DLP as a service |
| ai-engine (8091) | AI classification and threat scoring |
| storage-manager (8096) | Content-addressable file storage |

**Minimum environment variables for standalone:**

```bash
# For any service needing PostgreSQL
DATABASE_URL=jdbc:postgresql://your-db:5432/filetransfer?stringtype=unspecified
DB_USERNAME=postgres
DB_PASSWORD=your_password
JWT_SECRET=your_256bit_secret_key
SERVER_PORT=8095  # set to service's default port

# For services needing RabbitMQ
RABBITMQ_HOST=your-rabbitmq
RABBITMQ_PORT=5672

# For services needing Redis
REDIS_HOST=your-redis
REDIS_PORT=6379

# SPIFFE (can be disabled for standalone)
SPIFFE_ENABLED=false
```

**Example: Run EDI Converter standalone:**

```bash
# No database, no message broker, no Redis -- pure stateless
java -jar edi-converter/target/edi-converter-*.jar \
  --server.port=8095 \
  --spring.profiles.active=standalone
```

**Example: Run Screening Service standalone:**

```bash
java -jar screening-service/target/screening-service-*.jar \
  --server.port=8092 \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/filetransfer \
  --spring.datasource.username=postgres \
  --spring.datasource.password=postgres \
  --jwt.secret=change_me_in_production_256bit_secret_key!! \
  --spiffe.enabled=false
```

---

## Authentication

### For External API Consumers (Partners, CLIs, Custom UIs)

All admin API requests require a JWT token. The flow:

**Step 1: Register (first time only)**

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "developer@yourcompany.com",
    "password": "securePassword123"
  }'
```

Response:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZlb...",
  "tokenType": "Bearer",
  "expiresIn": 900000
}
```

**Step 2: Login**

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "developer@yourcompany.com",
    "password": "securePassword123"
  }'
```

Response (same format as register):

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZlb...",
  "tokenType": "Bearer",
  "expiresIn": 900000
}
```

**Step 3: Use the token on all subsequent requests**

```bash
export TOKEN="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZXZlb..."

curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/partners
```

**Token details:**

| Property | Value |
|---|---|
| Algorithm | HS256 |
| Expiry | 15 minutes (900000 ms) |
| Payload claims | `sub` (email), `role` (ADMIN/USER/PARTNER), `iat`, `exp` |
| Refresh | Re-call `/api/auth/login` before expiry |

**Rate limiting:** 20 authentication requests per minute per IP address. Exceeding this returns
HTTP 429 (Too Many Requests).

**Optional: Two-Factor Authentication (TOTP)**

```bash
# Enable 2FA
curl -X POST http://localhost:8080/api/2fa/enable \
  -H "Authorization: Bearer $TOKEN"

# Returns a QR code URI -- scan with Google Authenticator / Authy

# Verify enrollment
curl -X POST http://localhost:8080/api/2fa/verify \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"code": "123456"}'
```

---

### For Service-to-Service (Internal)

TranzFer uses SPIFFE/SPIRE for all inter-service authentication. There is no shared API key --
each service has a cryptographic workload identity.

**How it works:**

1. SPIRE Agent issues short-lived JWT-SVIDs (JSON Web Tokens with SPIFFE identifiers) to each service
2. `BaseServiceClient` auto-attaches the JWT-SVID to outbound HTTP requests
3. `PlatformJwtAuthFilter` on the receiving service validates through 3 paths:
   - **Path 0:** mTLS peer certificate (X.509-SVID)
   - **Path 1:** SPIFFE JWT-SVID in Authorization header
   - **Path 2:** Platform JWT (admin/partner tokens)

**Trust domain:** `filetransfer.io`

**SPIFFE IDs follow the pattern:**
```
spiffe://filetransfer.io/service/<service-name>
```

**To register your own service as a SPIFFE workload:**

1. Add an entry to `spire/auto-bootstrap.sh`:
   ```bash
   register_entry "your-service" "/your-service"
   ```
2. Mount the SPIRE Agent socket into your container:
   ```yaml
   volumes:
     - spire-socket:/run/spire/sockets:ro
   environment:
     SPIFFE_ENABLED: "true"
     SPIFFE_TRUST_DOMAIN: filetransfer.io
     SPIFFE_SOCKET: unix:/run/spire/sockets/agent.sock
   ```
3. Use `SpiffeWorkloadClient` from `shared-core` to fetch SVIDs, or use any SPIFFE-compliant workload API client

**Fallback behavior:** If SPIRE Agent is unavailable on startup, `SpiffeWorkloadClient` retries
every 15 seconds until the agent comes online. Services remain functional for platform JWT
(admin/partner) requests during this time.

---

### For Partner Portal

Partners authenticate with their **transfer account credentials** (username/password, not email).
The Partner Portal issues a separate JWT with `PARTNER` role.

```bash
curl -X POST http://localhost:8080/api/partner/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "acme-sftp",
    "password": "partnerPassword123"
  }'
```

Response:

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "username": "acme-sftp",
  "protocol": "SFTP",
  "homeDir": "/data/sftp/acme-sftp",
  "role": "PARTNER"
}
```

Partners can only see their own data: their transfers, receipts, SLA status, and connection info.
They cannot access admin endpoints.

**Available partner endpoints:**

| Method | Path | Description |
|---|---|---|
| POST | `/api/partner/login` | Authenticate with transfer account credentials |
| GET | `/api/partner/dashboard` | Overview stats for this partner |
| GET | `/api/partner/transfers` | Paginated transfer history |
| GET | `/api/partner/track/{trackId}` | Single transfer journey |
| GET | `/api/partner/receipt/{trackId}` | Delivery receipt (proof of delivery) |
| GET | `/api/partner/test-connection` | Connection test info (host, port, protocol) |
| POST | `/api/partner/rotate-key` | Upload new SSH public key |
| POST | `/api/partner/change-password` | Change transfer account password |
| GET | `/api/partner/sla` | SLA compliance status |

---

## API Integration by Use Case

### Use Case 1: Receive Files from Partners via SFTP

Set up a new partner with an SFTP account so they can send you files.

**Step 1: Authenticate**

```bash
# Get admin JWT token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@tranzfer.io","password":"admin123"}' | jq -r '.accessToken')
```

**Step 2: Create a partner**

```bash
curl -X POST http://localhost:8080/api/partners \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "companyName": "Acme Corporation",
    "displayName": "ACME",
    "industry": "Financial Services",
    "partnerType": "EXTERNAL",
    "slaTier": "STANDARD",
    "maxFileSizeBytes": 1073741824,
    "maxTransfersPerDay": 1000,
    "retentionDays": 90,
    "protocolsEnabled": ["SFTP"],
    "contacts": [
      {
        "name": "John Smith",
        "email": "john@acme.com",
        "phone": "+1-555-0100",
        "role": "Technical Contact",
        "isPrimary": true
      }
    ],
    "notes": "Q2 onboarding - payment files"
  }'
```

Response (Partner entity):

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "companyName": "Acme Corporation",
  "displayName": "ACME",
  "status": "PENDING",
  "partnerType": "EXTERNAL",
  "slaTier": "STANDARD",
  "contacts": [...],
  "createdAt": "2026-04-10T12:00:00Z"
}
```

**Step 3: Activate the partner**

```bash
curl -X POST http://localhost:8080/api/partners/a1b2c3d4-e5f6-7890-abcd-ef1234567890/activate \
  -H "Authorization: Bearer $TOKEN"
```

**Step 4: Create a transfer account for the partner**

```bash
curl -X POST http://localhost:8080/api/partners/a1b2c3d4-e5f6-7890-abcd-ef1234567890/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "protocol": "SFTP",
    "username": "acme-sftp",
    "password": "secureP@ssw0rd!",
    "publicKey": "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQ... acme@workstation",
    "permissions": {
      "read": true,
      "write": true,
      "delete": false
    },
    "qos": {
      "uploadBytesPerSecond": 10485760,
      "downloadBytesPerSecond": 10485760,
      "maxConcurrentSessions": 5,
      "priority": 3
    }
  }'
```

Response:

```json
{
  "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "username": "acme-sftp",
  "protocol": "SFTP",
  "homeDir": "/data/sftp/acme-sftp",
  "active": true,
  "partnerId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**Step 5: Create a server instance (if you need a dedicated listener)**

```bash
curl -X POST http://localhost:8080/api/servers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Primary SFTP Server",
    "protocol": "SFTP",
    "instanceId": "sftp-1",
    "clientConnectionHost": "sftp.yourcompany.com",
    "clientConnectionPort": 2222
  }'
```

**Step 6: Assign account to server**

```bash
curl -X POST http://localhost:8080/api/servers/{serverId}/accounts/{accountId} \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "canRead": true,
    "canWrite": true,
    "canDelete": false,
    "canRename": true,
    "canMkdir": true,
    "maxConcurrentSessions": 5,
    "maxUploadBytesPerSecond": 10485760
  }'
```

**Step 7: Partner connects via SFTP**

```bash
# Password auth
sftp -P 2222 acme-sftp@sftp.yourcompany.com

# Key-based auth
sftp -P 2222 -i ~/.ssh/acme_key acme-sftp@sftp.yourcompany.com

# Upload a file
sftp> put payment-file.csv /inbox/
```

**Step 8: Monitor the transfer**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/activity-monitor?sourceUsername=acme-sftp&size=10"
```

Response (paginated):

```json
{
  "content": [
    {
      "trackId": "TRZ-20260410-A1B2C3",
      "filename": "payment-file.csv",
      "status": "COMPLETED",
      "fileSizeBytes": 245678,
      "sourceUsername": "acme-sftp",
      "sourceProtocol": "SFTP",
      "integrityStatus": "VERIFIED",
      "sourceChecksum": "sha256:a1b2c3d4e5f6...",
      "destinationChecksum": "sha256:a1b2c3d4e5f6...",
      "uploadedAt": "2026-04-10T12:05:00Z",
      "routedAt": "2026-04-10T12:05:01Z",
      "completedAt": "2026-04-10T12:05:02Z"
    }
  ],
  "totalElements": 1,
  "totalPages": 1
}
```

---

### Use Case 2: Send Files to External Partners

Configure outbound delivery to an external SFTP/FTP/HTTPS destination.

**Step 1: Create an external destination**

```bash
curl -X POST http://localhost:8084/api/external-destinations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Acme Inbound Server",
    "type": "SFTP",
    "host": "sftp.acme.com",
    "port": 22,
    "username": "tranzfer-inbound",
    "password": "encrypted-password-here",
    "remotePath": "/incoming/payments",
    "active": true
  }'
```

**Step 2: Create a flow with a DELIVER step**

```bash
curl -X POST http://localhost:8084/api/flows \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Payment File Delivery",
    "description": "Encrypt and deliver payment files to Acme",
    "active": true,
    "priority": 10,
    "steps": [
      {
        "stepType": "ENCRYPT_PGP",
        "stepOrder": 1,
        "config": {"keyId": "acme-pgp-public"}
      },
      {
        "stepType": "COMPRESS_GZIP",
        "stepOrder": 2,
        "config": {}
      },
      {
        "stepType": "FILE_DELIVERY",
        "stepOrder": 3,
        "config": {"destinationId": "acme-inbound-server-id"}
      }
    ],
    "matchCriteria": {
      "filenamePattern": "payment_*.csv",
      "sourceAccount": "internal-payments"
    }
  }'
```

**Step 3: Files matching the criteria are automatically encrypted, compressed, and delivered**

**Step 4: Available flow step types**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8084/api/flows/step-types
```

Response:

```json
{
  "encryption": ["ENCRYPT_PGP", "DECRYPT_PGP", "ENCRYPT_AES", "DECRYPT_AES"],
  "compression": ["COMPRESS_GZIP", "DECOMPRESS_GZIP", "COMPRESS_ZIP", "DECOMPRESS_ZIP"],
  "transform": ["RENAME"],
  "security": ["SCREEN"],
  "scripting": ["EXECUTE_SCRIPT"],
  "delivery": ["MAILBOX", "FILE_DELIVERY"],
  "routing": ["ROUTE"],
  "conversion": ["CONVERT_EDI"]
}
```

---

### Use Case 3: Screen Files for Sanctions and DLP

Screen files for OFAC/EU/UN sanctions hits and DLP policy violations.

**Option A: Direct API scan (manual)**

```bash
curl -X POST http://localhost:8092/api/v1/screening/scan \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@customer-list.csv" \
  -F "trackId=MANUAL-001" \
  -F "account=acme-sftp"
```

Response:

```json
{
  "overallResult": "CLEAN",
  "antivirusResult": {
    "status": "CLEAN",
    "engine": "ClamAV",
    "scannedAt": "2026-04-10T12:00:00Z"
  },
  "dlpResult": {
    "status": "CLEAN",
    "policiesChecked": 5,
    "violations": []
  },
  "sanctionsResult": {
    "outcome": "CLEAR",
    "entitiesScanned": 150,
    "listsChecked": ["OFAC_SDN", "EU_CONSOLIDATED", "UN_CONSOLIDATED"],
    "hits": []
  }
}
```

**Option B: Automatic screening via flow step**

Create a flow with a `SCREEN` step -- every file matching the flow criteria is automatically
screened:

```bash
curl -X POST http://localhost:8084/api/flows \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Compliance Screening",
    "active": true,
    "priority": 1,
    "steps": [
      {"stepType": "SCREEN", "stepOrder": 1, "config": {}}
    ]
  }'
```

**Managing quarantined files:**

```bash
# List all quarantined files
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8092/api/v1/quarantine

# List only active quarantine records
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8092/api/v1/quarantine?status=QUARANTINED"

# Get quarantine statistics
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8092/api/v1/quarantine/stats

# Release a quarantined file (requires ADMIN role, re-scans first)
curl -X POST http://localhost:8092/api/v1/quarantine/{id}/release \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"notes": "False positive, reviewed by compliance team"}'

# Permanently delete a quarantined file (irreversible)
curl -X DELETE http://localhost:8092/api/v1/quarantine/{id} \
  -H "Authorization: Bearer $TOKEN"
```

**Managing DLP policies:**

```bash
# List all DLP policies
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8092/api/v1/dlp/policies

# Create a new DLP policy
curl -X POST http://localhost:8092/api/v1/dlp/policies \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Credit Card Detection",
    "description": "Block files containing credit card numbers",
    "patterns": ["\\b4[0-9]{12}(?:[0-9]{3})?\\b", "\\b5[1-5][0-9]{14}\\b"],
    "action": "QUARANTINE",
    "active": true
  }'

# Manual DLP scan
curl -X POST http://localhost:8092/api/v1/dlp/scan \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@suspect-file.csv" \
  -F "trackId=DLP-MANUAL-001"
```

**Sanctions list status:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8092/api/v1/screening/lists

# Force refresh sanctions lists
curl -X POST http://localhost:8092/api/v1/screening/lists/refresh \
  -H "Authorization: Bearer $TOKEN"
```

---

### Use Case 4: Encrypt and Decrypt Files

**Step 1: Create an encryption key via keystore**

```bash
# Generate a PGP keypair
curl -X POST http://localhost:8093/api/v1/keys/generate/pgp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "acme-pgp",
    "identity": "acme@yourcompany.com",
    "passphrase": "strongPassphrase123"
  }'

# Generate an AES-256 symmetric key
curl -X POST http://localhost:8093/api/v1/keys/generate/aes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "acme-aes-key",
    "ownerService": "encryption-service"
  }'
```

Response (PGP example):

```json
{
  "id": "c3d4e5f6-a7b8-9012-cdef-123456789012",
  "alias": "acme-pgp",
  "keyType": "PGP_KEYPAIR",
  "ownerService": null,
  "partnerAccount": null,
  "active": true,
  "expiresAt": null,
  "createdAt": "2026-04-10T12:00:00Z"
}
```

**Step 2: Encrypt a file**

```bash
# Encrypt using the encryption service (multipart upload)
curl -X POST http://localhost:8086/api/encrypt/encrypt \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@sensitive-data.csv" \
  -F "keyId=c3d4e5f6-a7b8-9012-cdef-123456789012" \
  --output sensitive-data.csv.enc
```

**Step 3: Decrypt a file**

```bash
curl -X POST http://localhost:8086/api/encrypt/decrypt \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@sensitive-data.csv.enc" \
  -F "keyId=c3d4e5f6-a7b8-9012-cdef-123456789012" \
  -F "passphrase=strongPassphrase123" \
  --output sensitive-data-decrypted.csv
```

**Base64 mode (for API-to-API integration):**

```bash
# Encrypt
curl -X POST "http://localhost:8086/api/encrypt/encrypt/base64?keyId=c3d4e5f6-..." \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/plain" \
  -d "$(base64 < sensitive-data.csv)"

# Decrypt
curl -X POST "http://localhost:8086/api/encrypt/decrypt/base64?keyId=c3d4e5f6-..." \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/plain" \
  -d "<base64-encrypted-data>"
```

**Or use flow-based encryption (automatic):**

Create a flow with `ENCRYPT_PGP` or `ENCRYPT_AES` step -- every matching file is encrypted
automatically during processing.

---

### Use Case 5: Convert EDI Documents

The EDI Converter is stateless (no database) and supports 110 conversion paths across 11 input
formats and 10 output formats.

**Detect format:**

```bash
curl -X POST http://localhost:8095/api/v1/convert/detect \
  -H "Content-Type: application/json" \
  -d '{
    "content": "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *260410*1200*^*00501*000000001*0*P*:~GS*PO*SENDER*RECEIVER*20260410*1200*1*X*005010~"
  }'
```

Response:

```json
{
  "format": "X12"
}
```

**Parse:**

```bash
curl -X POST http://localhost:8095/api/v1/convert/parse \
  -H "Content-Type: application/json" \
  -d '{
    "content": "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *260410*1200*^*00501*000000001*0*P*:~GS*PO*SENDER*RECEIVER*20260410*1200*1*X*005010~ST*850*0001~BEG*00*NE*PO-12345**20260410~SE*3*0001~GE*1*1~IEA*1*000000001~"
  }'
```

**Convert (X12 to JSON):**

```bash
curl -X POST http://localhost:8095/api/v1/convert/convert \
  -H "Content-Type: application/json" \
  -d '{
    "content": "ISA*00*          *00*          *ZZ*SENDER...",
    "target": "JSON"
  }'
```

**Convert via file upload:**

```bash
curl -X POST http://localhost:8095/api/v1/convert/convert/file \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@invoice.edi" \
  -F "target=JSON"
```

**Validate:**

```bash
curl -X POST http://localhost:8095/api/v1/convert/validate \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*..."}'
```

**Explain (human-readable):**

```bash
curl -X POST http://localhost:8095/api/v1/convert/explain \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*..."}'
```

**Self-heal (auto-fix common errors):**

```bash
curl -X POST http://localhost:8095/api/v1/convert/heal \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*...", "format": "X12"}'
```

**Natural language EDI creation:**

```bash
curl -X POST http://localhost:8095/api/v1/convert/create \
  -H "Content-Type: application/json" \
  -d '{"text": "Create a purchase order from ACME Corp to Widget Co for 100 units of item SKU-5678 at $25.00 each, ship by April 30"}'
```

**Supported formats:**

| Input | Output |
|---|---|
| X12, EDIFACT, TRADACOMS, SWIFT_MT, HL7, NACHA, BAI2, ISO20022, FIX, PEPPOL, AUTO | JSON, XML, CSV, YAML, FLAT, TIF, X12, EDIFACT, HL7, SWIFT_MT |

#### Map-Based EDI Conversion (Production)

For production integrations, use the map-based conversion endpoint which applies structured
field-by-field mapping with transforms, code table lookups, and confidence scoring.

**Step 1: List available maps**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8095/api/v1/convert/maps
```

**Step 2: Convert using a specific map**

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  http://localhost:8095/api/v1/convert/convert/map \
  -d '{
    "content": "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *260410*1200*^*00501*000000001*0*P*:~GS*PO*SENDER*RECEIVER*20260410*1200*1*X*005010~ST*850*0001~BEG*00*NE*PO-12345**20260410~SE*3*0001~GE*1*1~IEA*1*000000001~",
    "sourceType": "X12_850",
    "targetType": "PURCHASE_ORDER_INH"
  }'
```

**Step 3: Clone and customize a map for your partner**

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  http://localhost:8091/api/v1/edi/maps/clone \
  -d '{
    "sourceMapId": "X12_850--INHOUSE_PURCHASE_ORDER",
    "partnerId": "uuid",
    "name": "Acme Custom PO Map"
  }'
```

Map cascade priority: Partner custom → Trained (AI) → Standard (classpath). When a `partnerId`
is provided, the converter checks for a partner-specific map first, then falls back to trained
maps, and finally to the 31 standard maps that ship in the JAR.

---

### Use Case 6: Monitor Platform Health

Platform Sentinel continuously analyzes all services for security issues, performance problems,
and correlated incidents.

**Get the Sentinel dashboard:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8098/api/v1/sentinel/dashboard
```

Response:

```json
{
  "healthScore": {
    "overallScore": 92,
    "infrastructureScore": 95,
    "dataScore": 90,
    "securityScore": 88,
    "recordedAt": "2026-04-10T12:00:00Z"
  },
  "openBySeverity": {
    "CRITICAL": 0,
    "HIGH": 1,
    "MEDIUM": 3,
    "LOW": 5
  },
  "totalOpen": 9,
  "totalToday": 2,
  "recentFindings": [...],
  "correlationGroups": 2
}
```

**Get health score (single value):**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8098/api/v1/sentinel/health-score
```

**Get health score history:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8098/api/v1/sentinel/health-score/history?hours=24"
```

**List findings (paginated, filterable):**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8098/api/v1/sentinel/findings?severity=CRITICAL&status=OPEN&page=0&size=20"
```

**Check individual service health:**

```bash
# Any service -- use /actuator/health
curl http://localhost:8080/actuator/health         # onboarding-api
curl http://localhost:8081/actuator/health         # sftp-service
curl http://localhost:8092/actuator/health         # screening-service

# Or use service-specific health endpoints
curl -H "Authorization: Bearer $TOKEN" http://localhost:8092/api/v1/screening/health
curl -H "Authorization: Bearer $TOKEN" http://localhost:8093/api/v1/keys/health
curl -H "Authorization: Bearer $TOKEN" http://localhost:8096/api/v1/storage/health
curl -H "Authorization: Bearer $TOKEN" http://localhost:8098/api/v1/sentinel/health
```

**Prometheus metrics:**

```bash
curl http://localhost:8080/actuator/prometheus
```

**Circuit breaker status:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8098/api/v1/sentinel/circuit-breakers
```

---

### Use Case 7: Classify Files Using AI

Automatically classify file content as PCI, PHI, PII, FINANCIAL, or GENERAL.

**Classify a file:**

```bash
curl -X POST http://localhost:8091/api/v1/ai/classify \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@customer-data.csv"
```

Response:

```json
{
  "classification": "PII",
  "confidence": 0.94,
  "detectedPatterns": [
    "Social Security Numbers (12 matches)",
    "Email addresses (45 matches)",
    "Phone numbers (38 matches)"
  ],
  "recommendation": "REVIEW",
  "classifiedAt": "2026-04-10T12:00:00Z"
}
```

**Classify inline text:**

```bash
curl -X POST http://localhost:8091/api/v1/ai/classify/text \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "John Smith, SSN: 123-45-6789, DOB: 01/15/1980",
    "filename": "customer-record.txt"
  }'
```

**Compute risk score:**

```bash
curl -X POST http://localhost:8091/api/v1/ai/risk-score \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "newIp": true,
    "unusualHour": false,
    "fileSizeMb": 250,
    "containsPci": true,
    "containsPii": false
  }'
```

Response:

```json
{
  "score": 70,
  "level": "HIGH",
  "action": "REVIEW",
  "factors": [
    "Login from new IP (+30)",
    "Large file >100MB (+15)",
    "Contains PCI data (+25)"
  ]
}
```

**Natural language query:**

```bash
curl -X POST http://localhost:8091/api/v1/ai/ask \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"question": "How many files failed screening today?"}'
```

**NLP command translation:**

```bash
curl -X POST http://localhost:8091/api/v1/ai/nlp/command \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query": "show me all failed transfers from acme in the last week"}'
```

---

### Use Case 8: Manage Cryptographic Keys

Keystore Manager provides centralized key/certificate management for the entire platform.

**Generate keys:**

```bash
# SSH host key (for SFTP servers)
curl -X POST http://localhost:8093/api/v1/keys/generate/ssh-host \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"alias": "sftp-1-host-key", "ownerService": "sftp-service"}'

# SSH user key (for partner authentication)
curl -X POST http://localhost:8093/api/v1/keys/generate/ssh-user \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"alias": "acme-user-key", "partnerAccount": "acme-sftp", "keySize": "4096"}'

# AES-256 symmetric key
curl -X POST http://localhost:8093/api/v1/keys/generate/aes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"alias": "file-encryption-key", "ownerService": "encryption-service"}'

# TLS certificate
curl -X POST http://localhost:8093/api/v1/keys/generate/tls \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"alias": "gateway-tls", "cn": "gateway.yourcompany.com", "validDays": "365"}'

# HMAC signing secret
curl -X POST http://localhost:8093/api/v1/keys/generate/hmac \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"alias": "webhook-signing", "ownerService": "notification-service"}'

# PGP keypair
curl -X POST http://localhost:8093/api/v1/keys/generate/pgp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"alias": "partner-pgp", "identity": "partner@yourcompany.com", "passphrase": "strongpass"}'
```

**Import an existing key:**

```bash
curl -X POST http://localhost:8093/api/v1/keys/import \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "partner-public-key",
    "keyType": "PGP_PUBLIC",
    "keyMaterial": "-----BEGIN PGP PUBLIC KEY BLOCK-----\nmQGNBGYR...\n-----END PGP PUBLIC KEY BLOCK-----",
    "description": "Acme Corp PGP public key for encryption",
    "ownerService": "encryption-service",
    "partnerAccount": "acme-sftp"
  }'
```

**Rotate a key:**

```bash
curl -X POST http://localhost:8093/api/v1/keys/acme-pgp/rotate \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"newAlias": "acme-pgp-2026Q2"}'
```

**List keys (filtered):**

```bash
# By type
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8093/api/v1/keys?type=SSH_USER_KEY"

# By service
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8093/api/v1/keys?service=sftp-service"

# By partner
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8093/api/v1/keys?partner=acme-sftp"

# Expiring within 30 days
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8093/api/v1/keys/expiring?days=30"
```

**Download public key (safe for external sharing):**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8093/api/v1/keys/acme-pgp/public
```

**Supported key types:**

| Type | Description |
|---|---|
| SSH_HOST_KEY | SFTP/SSH server host key |
| SSH_USER_KEY | SFTP user authentication key |
| PGP_PUBLIC | PGP public key for encryption |
| PGP_PRIVATE | PGP private key for decryption |
| PGP_KEYPAIR | PGP keypair for sign + encrypt |
| AES_SYMMETRIC | AES-256 symmetric encryption key |
| TLS_CERTIFICATE | X.509 TLS certificate + private key |
| TLS_KEYSTORE | Java keystore (JKS/PKCS12) |
| HMAC_SECRET | HMAC-SHA256 signing secret |
| API_KEY | Inter-service API key |

---

### Use Case 9: AS2/AS4 Trading Partner Communication

**Step 1: Create an AS2 partnership**

```bash
curl -X POST http://localhost:8084/api/as2-partnerships \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "partnerName": "Acme Trading",
    "partnerAs2Id": "ACME-AS2-ID",
    "ourAs2Id": "TRANZFER-AS2-ID",
    "endpointUrl": "https://as2.acme.com/receive",
    "partnerCertificate": "-----BEGIN CERTIFICATE-----\nMIID...\n-----END CERTIFICATE-----",
    "signingAlgorithm": "SHA-256",
    "encryptionAlgorithm": "AES256",
    "mdnRequired": true,
    "mdnAsync": false,
    "compressionEnabled": true,
    "protocol": "AS2",
    "active": true
  }'
```

**Step 2: Inbound AS2 messages**

Partners send AS2 messages to your platform's AS2 endpoint:

```
POST http://your-server:8094/as2/receive
```

The AS2 service automatically:
- Validates the digital signature
- Decrypts the message
- Sends back an MDN (receipt) -- synchronous or asynchronous based on partnership config
- Triggers flow processing for the received file

**Step 3: List partnerships**

```bash
# All active partnerships
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8084/api/as2-partnerships

# Filter by protocol
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8084/api/as2-partnerships?protocol=AS4"
```

**Allowed signing algorithms:** SHA-256, SHA-384, SHA-512
**Allowed encryption algorithms:** AES128, AES192, AES256

Deprecated algorithms (MD5, SHA-1, DES, 3DES, RC2) are rejected at creation time.

---

### Use Case 10: Real-Time Notifications

Configure automated notifications via Slack, Teams, PagerDuty, or webhooks.

**Step 1: Create a connector**

```bash
# Slack connector
curl -X POST http://localhost:8084/api/connectors \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Ops Slack Channel",
    "type": "SLACK",
    "url": "https://example.com/slack-webhook-placeholder",
    "active": true
  }'

# Generic webhook connector
curl -X POST http://localhost:8084/api/connectors \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Internal Webhook",
    "type": "WEBHOOK",
    "url": "https://your-system.com/webhooks/mft-events",
    "active": true
  }'
```

**Available connector types:**

| Type | Description |
|---|---|
| SLACK | Post to Slack channel via incoming webhook |
| TEAMS | Post to Microsoft Teams via connector URL |
| PAGERDUTY | Trigger PagerDuty events |
| SERVICENOW | Create ServiceNow incidents |
| OPSGENIE | Create OpsGenie alerts |
| WEBHOOK | POST JSON to any URL |

**Step 2: Test the connector**

```bash
curl -X POST http://localhost:8084/api/connectors/{connectorId}/test \
  -H "Authorization: Bearer $TOKEN"
```

Response:

```json
{
  "status": "OK",
  "httpCode": "200"
}
```

**Step 3: Create a notification rule**

```bash
curl -X POST http://localhost:8097/api/notifications/rules \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Alert on Failed Transfers",
    "eventTypePattern": "TRANSFER_FAILED",
    "channel": "SLACK",
    "recipients": "https://example.com/slack-webhook-placeholder",
    "enabled": true,
    "conditions": "{\"minSeverity\": \"HIGH\"}"
  }'
```

**Step 4: Send a test notification**

```bash
curl -X POST http://localhost:8097/api/notifications/test \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "SLACK",
    "recipient": "https://example.com/slack-webhook-placeholder",
    "subject": "Test Alert",
    "body": "This is a test notification from TranzFer MFT"
  }'
```

**View notification logs:**

```bash
# Recent notifications
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8097/api/notifications/logs/recent

# By track ID
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8097/api/notifications/logs/by-track-id/TRZ-20260410-A1B2C3
```

---

### Use Case 11: Migrate from a Legacy MFT Product

TranzFer includes a Migration Center for gradual, zero-downtime migration from legacy MFT products
(Sterling, GoAnywhere, MOVEit, etc.).

**Step 1: Configure the legacy server**

```bash
curl -X POST http://localhost:8084/api/legacy-servers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Legacy Sterling SFTP",
    "host": "sterling.yourcompany.com",
    "port": 22,
    "protocol": "SFTP",
    "username": "gateway-proxy",
    "password": "encrypted-password",
    "active": true
  }'
```

The gateway service automatically routes unknown users to the legacy server. Known TranzFer
accounts connect to TranzFer directly.

**Step 2: Start migration for a specific partner**

```bash
curl -X POST http://localhost:8084/api/v1/migration/partners/{partnerId}/start \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "source": "Sterling File Gateway",
    "notes": "Q2 migration - payment processing team"
  }'
```

**Step 3: Enable shadow mode (run both systems in parallel)**

```bash
curl -X POST http://localhost:8084/api/v1/migration/partners/{partnerId}/shadow \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "legacyHost": "sterling.yourcompany.com",
    "legacyPort": 22,
    "legacyUsername": "acme-legacy"
  }'
```

In shadow mode, files are received by both TranzFer and the legacy system simultaneously.
You can compare results without risk.

**Step 4: Verify**

```bash
# Start verification phase
curl -X POST http://localhost:8084/api/v1/migration/partners/{partnerId}/verify \
  -H "Authorization: Bearer $TOKEN"

# Record verification result
curl -X POST http://localhost:8084/api/v1/migration/partners/{partnerId}/verify/record \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"transferCount": 150, "passed": true, "details": "All 150 transfers matched"}'
```

**Step 5: Complete migration**

```bash
curl -X POST http://localhost:8084/api/v1/migration/partners/{partnerId}/complete \
  -H "Authorization: Bearer $TOKEN"
```

**Step 6: Monitor progress**

```bash
# Migration dashboard
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8084/api/v1/migration/dashboard

# Connection split statistics (per partner: legacy vs. platform)
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8084/api/v1/migration/connection-stats

# Partner event history
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8084/api/v1/migration/partners/{partnerId}/events
```

**Rollback (if needed):**

```bash
curl -X POST http://localhost:8084/api/v1/migration/partners/{partnerId}/rollback \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Performance regression detected in shadow mode"}'
```

---

### Use Case 12: Content-Addressable Storage

TranzFer's storage-manager provides content-addressable storage with automatic SHA-256
deduplication and tiered lifecycle management.

**Store a file (multipart):**

```bash
curl -X POST http://localhost:8096/api/v1/storage/store \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@large-dataset.csv" \
  -F "trackId=TRZ-20260410-XYZ" \
  -F "account=acme-sftp"
```

Response:

```json
{
  "status": "STORED",
  "tier": "HOT",
  "backend": "local",
  "trackId": "TRZ-20260410-XYZ",
  "sha256": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
  "sizeBytes": 15728640,
  "throughputMbps": 245.3,
  "durationMs": 61
}
```

**Store a file (streaming, no multipart overhead):**

```bash
curl -X POST "http://localhost:8096/api/v1/storage/store-stream?filename=large-file.dat&trackId=TRZ-001&account=acme" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @large-file.dat
```

**Retrieve by track ID (streaming):**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8096/api/v1/storage/retrieve/TRZ-20260410-XYZ \
  --output large-dataset.csv
```

**Retrieve by SHA-256 (zero-copy streaming):**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8096/api/v1/storage/stream/a1b2c3d4e5f6... \
  --output large-dataset.csv
```

Response headers include:
- `X-SHA256`: content hash
- `X-Storage-Tier`: HOT / WARM / COLD / ARCHIVE
- `X-Storage-Backend`: local / s3

**Deduplication is automatic:** uploading the same content returns `DEDUPLICATED` status with zero
additional disk usage.

**List stored objects:**

```bash
# By account
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8096/api/v1/storage/objects?account=acme-sftp"

# By tier
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8096/api/v1/storage/objects?tier=HOT"
```

**Storage metrics:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8096/api/v1/storage/metrics
```

**Tiered lifecycle:** Objects automatically transition HOT -> WARM -> COLD -> ARCHIVE based on
access patterns. Trigger a manual tiering cycle:

```bash
curl -X POST http://localhost:8096/api/v1/storage/lifecycle/tier \
  -H "Authorization: Bearer $TOKEN"
```

---

### Use Case 13: Threat Intelligence

AI-powered threat detection, MITRE ATT&CK mapping, and threat hunting.

**Get the threat dashboard:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8091/api/v1/threats/dashboard
```

**Add a threat indicator:**

```bash
curl -X POST http://localhost:8091/api/v1/threats/indicators \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "value": "192.168.1.100",
    "type": "IP_ADDRESS",
    "threatLevel": "HIGH",
    "source": "internal-soc",
    "description": "Suspicious IP observed in brute force attempts",
    "tags": ["brute-force", "external"]
  }'
```

**Hunt for threats:**

```bash
curl -X POST http://localhost:8091/api/v1/threats/hunt \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "192.168.1.100",
    "timeRange": "24h"
  }'
```

**MITRE ATT&CK mapping:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8091/api/v1/threats/mitre/mapping
```

**Threat scoring for a specific event:**

```bash
curl -X POST http://localhost:8091/api/v1/ai/threat-score \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "acme-sftp",
    "ipAddress": "203.0.113.42",
    "action": "FILE_UPLOAD",
    "filename": "payment_batch.csv",
    "fileSizeBytes": 52428800
  }'
```

---

### Use Case 14: Build a Custom UI

All TranzFer functionality is available through REST APIs. The built-in React UI serves as a
reference implementation.

**Step 1: Configure your API client**

In gateway mode (production), all requests go through one URL:

```javascript
// .env
VITE_API_GATEWAY_URL=https://mft.yourcompany.com
```

In direct mode (development), each service has its own port:

```javascript
import axios from 'axios'

const GATEWAY_URL = process.env.VITE_API_GATEWAY_URL

// Create per-service clients (gateway mode routes automatically)
const onboardingApi = axios.create({
  baseURL: GATEWAY_URL || 'http://localhost:8080'
})
const configApi = axios.create({
  baseURL: GATEWAY_URL || 'http://localhost:8084'
})
const screeningApi = axios.create({
  baseURL: GATEWAY_URL || 'http://localhost:8092'
})
// ... same pattern for all services

// Add auth interceptor
onboardingApi.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// Handle 401 globally
onboardingApi.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token')
      window.location.href = '/login'
    }
    return Promise.reject(err)
  }
)
```

**Step 2: Implement auth flow**

```javascript
// Login
const response = await onboardingApi.post('/api/auth/login', {
  email: 'admin@tranzfer.io',
  password: 'admin123'
})
localStorage.setItem('token', response.data.accessToken)

// All subsequent requests automatically include the token via interceptor
const partners = await onboardingApi.get('/api/partners')
```

**Step 3: Use the correlation ID for debugging**

Every API error response includes an `X-Correlation-Id` header. Log it for cross-service
request tracing:

```javascript
onboardingApi.interceptors.response.use(
  (res) => res,
  (err) => {
    const correlationId = err?.response?.headers?.['x-correlation-id']
    if (correlationId) {
      console.debug(
        `[API] ${err.config?.method?.toUpperCase()} ${err.config?.url}` +
        ` -> ${err.response?.status} [${correlationId}]`
      )
    }
    return Promise.reject(err)
  }
)
```

**Service port reference for direct mode:**

| Service | Port | API Client Variable |
|---|---|---|
| onboarding-api | 8080 | `onboardingApi` |
| config-service | 8084 | `configApi` |
| gateway-service | 8085 | `gatewayApi` |
| encryption-service | 8086 | -- (internal) |
| dmz-proxy | 8088 | `dmzApi` |
| license-service | 8089 | `licenseApi` |
| analytics-service | 8090 | `analyticsApi` |
| ai-engine | 8091 | `aiApi` |
| screening-service | 8092 | `screeningApi` |
| keystore-manager | 8093 | `keystoreApi` |
| edi-converter | 8095 | `ediApi` |
| storage-manager | 8096 | `storageApi` |
| notification-service | 8097 | `notificationApi` |
| platform-sentinel | 8098 | `sentinelApi` |

---

## Webhook Integration

### Outbound Webhooks (TranzFer to Your System)

Configure via the Connectors API. TranzFer sends event notifications to your registered webhook URLs.

**Configure a webhook connector:**

```bash
curl -X POST http://localhost:8084/api/connectors \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "My System Webhook",
    "type": "WEBHOOK",
    "url": "https://your-system.com/webhooks/mft",
    "active": true
  }'
```

**Event types:**

| Event | Description |
|---|---|
| `TRANSFER_COMPLETED` | File successfully transferred and delivered |
| `TRANSFER_FAILED` | File transfer failed after retries |
| `QUARANTINE` | File quarantined by screening service |
| `AI_BLOCKED` | File blocked by AI classification |
| `SCREENING_HIT` | Sanctions/DLP hit detected |
| `KEY_EXPIRING` | Cryptographic key nearing expiration |
| `HEALTH_DEGRADED` | Platform health score dropped below threshold |
| `CIRCUIT_BREAKER_OPEN` | Service circuit breaker tripped |
| `PARTNER_ACTIVATED` | Partner status changed to ACTIVE |
| `PARTNER_SUSPENDED` | Partner status changed to SUSPENDED |

**Webhook payload format:**

```json
{
  "event": "TRANSFER_COMPLETED",
  "timestamp": "2026-04-10T12:05:02Z",
  "trackId": "TRZ-20260410-A1B2C3",
  "data": {
    "filename": "payment-file.csv",
    "sizeBytes": 245678,
    "sourceAccount": "acme-sftp",
    "destinationAccount": "internal-payments",
    "sourceChecksum": "sha256:a1b2c3...",
    "destinationChecksum": "sha256:a1b2c3...",
    "integrityVerified": true,
    "flowName": "Payment Processing",
    "duration": "2.1s"
  },
  "source": "tranzfer-mft",
  "correlationId": "corr-a1b2c3d4"
}
```

**Retry policy:** 3 attempts with exponential backoff (1s, 5s, 25s).

**Signature verification:** Webhook payloads are signed with HMAC-SHA256. Verify using the
connector's signing secret in the `X-Webhook-Signature` header.

---

### Inbound Webhooks (Your System to TranzFer)

**Transfer API v2 -- single-call file transfer:**

```bash
curl -X POST http://localhost:8080/api/v2/transfer \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@payment-batch.csv" \
  -F "sender=acme-sftp" \
  -F "destination=internal-payments" \
  -F "flow=Payment Processing" \
  -F "webhookUrl=https://your-system.com/callbacks/transfer"
```

Response:

```json
{
  "trackId": "TRZ-20260410-D4E5F6",
  "filename": "payment-batch.csv",
  "sizeBytes": 1048576,
  "sender": "acme-sftp",
  "destination": "internal-payments",
  "flow": "Payment Processing",
  "status": "ACCEPTED",
  "message": "File accepted for processing. Track with GET /api/v2/transfer/TRZ-20260410-D4E5F6",
  "pollUrl": "/api/v2/transfer/TRZ-20260410-D4E5F6",
  "receiptUrl": "/api/v2/transfer/TRZ-20260410-D4E5F6/receipt",
  "timestamp": "2026-04-10T12:10:00Z"
}
```

**Poll transfer status:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v2/transfer/TRZ-20260410-D4E5F6
```

Response:

```json
{
  "trackId": "TRZ-20260410-D4E5F6",
  "filename": "payment-batch.csv",
  "status": "COMPLETED",
  "sizeBytes": 1048576,
  "uploadedAt": "2026-04-10T12:10:00Z",
  "routedAt": "2026-04-10T12:10:02Z",
  "completedAt": "2026-04-10T12:10:03Z",
  "sourceChecksum": "sha256:a1b2c3...",
  "destinationChecksum": "sha256:a1b2c3...",
  "integrityVerified": true,
  "retryCount": 0,
  "flowName": "Payment Processing",
  "flowStatus": "COMPLETED",
  "flowStep": "3/3"
}
```

**Batch transfer -- multiple files in one call:**

```bash
curl -X POST http://localhost:8080/api/v2/transfer/batch \
  -H "Authorization: Bearer $TOKEN" \
  -F "files=@file1.csv" \
  -F "files=@file2.csv" \
  -F "files=@file3.csv" \
  -F "sender=acme-sftp"
```

Response:

```json
{
  "totalFiles": 3,
  "sender": "acme-sftp",
  "results": [
    {"trackId": "TRZ-20260410-001", "filename": "file1.csv", "status": "ACCEPTED"},
    {"trackId": "TRZ-20260410-002", "filename": "file2.csv", "status": "ACCEPTED"},
    {"trackId": "TRZ-20260410-003", "filename": "file3.csv", "status": "ACCEPTED"}
  ],
  "timestamp": "2026-04-10T12:15:00Z"
}
```

**Get delivery receipt:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v2/transfer/TRZ-20260410-D4E5F6/receipt
```

Response:

```json
{
  "receiptId": "RCP-TRZ-20260410-D4E5F6",
  "trackId": "TRZ-20260410-D4E5F6",
  "filename": "payment-batch.csv",
  "status": "COMPLETED",
  "sizeBytes": 1048576,
  "sourceChecksum": "sha256:a1b2c3d4e5f6...",
  "destinationChecksum": "sha256:a1b2c3d4e5f6...",
  "integrityVerified": true,
  "uploadedAt": "2026-04-10T12:10:00Z",
  "deliveredAt": "2026-04-10T12:10:02Z",
  "generatedAt": "2026-04-10T12:20:00Z"
}
```

---

## SDK and Client Libraries

### JavaScript / TypeScript

Complete example using axios (same pattern used by the built-in Admin UI):

```javascript
import axios from 'axios'

// --- Configuration ---
const BASE_URL = process.env.TRANZFER_URL || 'http://localhost:8080'

const api = axios.create({ baseURL: BASE_URL })

// Auto-attach JWT to all requests
api.interceptors.request.use((config) => {
  const token = process.env.TRANZFER_TOKEN || localStorage?.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// --- Auth ---
async function login(email, password) {
  const { data } = await api.post('/api/auth/login', { email, password })
  return data.accessToken // Store this for subsequent requests
}

// --- Partners ---
async function createPartner(partner) {
  const { data } = await api.post('/api/partners', partner)
  return data
}

// --- Accounts ---
async function createAccount(partnerId, account) {
  const { data } = await api.post(`/api/partners/${partnerId}/accounts`, account)
  return data
}

// --- Transfer API v2 ---
async function transferFile(filePath, sender, options = {}) {
  const FormData = (await import('form-data')).default
  const fs = await import('fs')

  const form = new FormData()
  form.append('file', fs.createReadStream(filePath))
  form.append('sender', sender)
  if (options.destination) form.append('destination', options.destination)
  if (options.webhookUrl) form.append('webhookUrl', options.webhookUrl)

  const { data } = await api.post('/api/v2/transfer', form, {
    headers: form.getHeaders()
  })
  return data
}

// --- Poll until complete ---
async function waitForTransfer(trackId, timeoutMs = 30000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    const { data } = await api.get(`/api/v2/transfer/${trackId}`)
    if (data.status === 'COMPLETED' || data.status === 'FAILED') return data
    await new Promise(r => setTimeout(r, 2000))
  }
  throw new Error(`Transfer ${trackId} timed out after ${timeoutMs}ms`)
}

// --- Complete Example ---
async function main() {
  // 1. Login
  const token = await login('admin@tranzfer.io', 'admin123')
  process.env.TRANZFER_TOKEN = token

  // 2. Create partner
  const partner = await createPartner({
    companyName: 'Acme Corp',
    partnerType: 'EXTERNAL',
    slaTier: 'STANDARD',
    protocolsEnabled: ['SFTP']
  })
  console.log('Partner created:', partner.id)

  // 3. Activate partner
  await api.post(`/api/partners/${partner.id}/activate`)

  // 4. Create account
  const account = await createAccount(partner.id, {
    protocol: 'SFTP',
    username: 'acme-sftp',
    password: 'securePassword123'
  })
  console.log('Account created:', account.username)

  // 5. Transfer a file
  const transfer = await transferFile('./payment.csv', 'acme-sftp', {
    webhookUrl: 'https://my-system.com/callback'
  })
  console.log('Transfer accepted:', transfer.trackId)

  // 6. Wait for completion
  const result = await waitForTransfer(transfer.trackId)
  console.log('Transfer status:', result.status)
  console.log('Integrity verified:', result.integrityVerified)
}

main().catch(console.error)
```

---

### Java

Complete example using Spring's RestTemplate:

```java
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.File;
import java.util.Map;

public class TranzFerClient {

    private final RestTemplate rest = new RestTemplate();
    private final String baseUrl;
    private String token;

    public TranzFerClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    // --- Auth ---

    public void login(String email, String password) {
        Map<String, String> body = Map.of("email", email, "password", password);
        ResponseEntity<Map> response = rest.postForEntity(
            baseUrl + "/api/auth/login", body, Map.class);
        this.token = (String) response.getBody().get("accessToken");
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders authJsonHeaders() {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // --- Partners ---

    public Map createPartner(Map<String, Object> partner) {
        HttpEntity<Map<String, Object>> request =
            new HttpEntity<>(partner, authJsonHeaders());
        return rest.postForObject(
            baseUrl + "/api/partners", request, Map.class);
    }

    // --- Screening ---

    public Map screenFile(File file) {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));

        HttpEntity<MultiValueMap<String, Object>> request =
            new HttpEntity<>(body, headers);
        return rest.postForObject(
            baseUrl.replace("8080", "8092") + "/api/v1/screening/scan",
            request, Map.class);
    }

    // --- Transfer API v2 ---

    public Map transfer(File file, String sender) {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(file));
        body.add("sender", sender);

        HttpEntity<MultiValueMap<String, Object>> request =
            new HttpEntity<>(body, headers);
        return rest.postForObject(
            baseUrl + "/api/v2/transfer", request, Map.class);
    }

    public Map getTransferStatus(String trackId) {
        HttpEntity<?> request = new HttpEntity<>(authHeaders());
        return rest.exchange(
            baseUrl + "/api/v2/transfer/" + trackId,
            HttpMethod.GET, request, Map.class).getBody();
    }

    // --- Complete Example ---

    public static void main(String[] args) {
        TranzFerClient client = new TranzFerClient("http://localhost:8080");

        // 1. Login
        client.login("admin@tranzfer.io", "admin123");

        // 2. Screen a file
        Map screenResult = client.screenFile(new File("customer-list.csv"));
        System.out.println("Screening result: " + screenResult.get("overallResult"));

        // 3. Transfer a file
        Map transfer = client.transfer(new File("payment.csv"), "acme-sftp");
        String trackId = (String) transfer.get("trackId");
        System.out.println("Transfer accepted: " + trackId);

        // 4. Poll for completion
        for (int i = 0; i < 15; i++) {
            Map status = client.getTransferStatus(trackId);
            String s = (String) status.get("status");
            if ("COMPLETED".equals(s) || "FAILED".equals(s)) {
                System.out.println("Final status: " + s);
                System.out.println("Integrity: " + status.get("integrityVerified"));
                break;
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
        }
    }
}
```

---

### cURL Quick Reference

**Authentication:**

```bash
# Login and extract token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@tranzfer.io","password":"admin123"}' | jq -r '.accessToken')

# Verify token works
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/partners
```

**Partner lifecycle:**

```bash
# Create
curl -X POST http://localhost:8080/api/partners \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"companyName":"Acme Corp","partnerType":"EXTERNAL"}'

# Activate
curl -X POST http://localhost:8080/api/partners/{id}/activate \
  -H "Authorization: Bearer $TOKEN"

# List
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/partners
```

**File transfer (single call):**

```bash
# Upload
curl -X POST http://localhost:8080/api/v2/transfer \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@myfile.csv" -F "sender=acme-sftp"

# Check status
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v2/transfer/{trackId}

# Get receipt
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v2/transfer/{trackId}/receipt
```

**Screening:**

```bash
# Full pipeline scan
curl -X POST http://localhost:8092/api/v1/screening/scan \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@customer.csv"

# Sanctions-only scan
curl -X POST http://localhost:8092/api/v1/screening/scan/sanctions \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@customer.csv"
```

**AI classification:**

```bash
curl -X POST http://localhost:8091/api/v1/ai/classify \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@sensitive-data.csv"
```

**Key management:**

```bash
# Generate PGP keypair
curl -X POST http://localhost:8093/api/v1/keys/generate/pgp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"alias":"my-pgp","identity":"me@company.com","passphrase":"pass"}'

# List all keys
curl -H "Authorization: Bearer $TOKEN" http://localhost:8093/api/v1/keys
```

**EDI conversion:**

```bash
# Detect format
curl -X POST http://localhost:8095/api/v1/convert/detect \
  -H "Content-Type: application/json" \
  -d '{"content":"ISA*00*..."}'

# Convert to JSON
curl -X POST http://localhost:8095/api/v1/convert/convert \
  -H "Content-Type: application/json" \
  -d '{"content":"ISA*00*...","target":"JSON"}'
```

**Storage:**

```bash
# Store
curl -X POST http://localhost:8096/api/v1/storage/store \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@data.bin" -F "trackId=TRK-001"

# Stream retrieve
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8096/api/v1/storage/stream/{sha256} --output data.bin
```

**Sentinel health:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8098/api/v1/sentinel/dashboard
```

---

### Python

Complete example using the `requests` library:

```python
import requests
import os
import time

class TranzFerClient:
    def __init__(self, base_url="http://localhost:8080"):
        self.base_url = base_url
        self.session = requests.Session()

    def login(self, email, password):
        """Authenticate and store JWT token."""
        resp = self.session.post(
            f"{self.base_url}/api/auth/login",
            json={"email": email, "password": password}
        )
        resp.raise_for_status()
        token = resp.json()["accessToken"]
        self.session.headers["Authorization"] = f"Bearer {token}"
        return token

    def create_partner(self, company_name, partner_type="EXTERNAL", **kwargs):
        """Create a new partner."""
        data = {"companyName": company_name, "partnerType": partner_type, **kwargs}
        resp = self.session.post(f"{self.base_url}/api/partners", json=data)
        resp.raise_for_status()
        return resp.json()

    def activate_partner(self, partner_id):
        """Activate a partner."""
        resp = self.session.post(f"{self.base_url}/api/partners/{partner_id}/activate")
        resp.raise_for_status()
        return resp.json()

    def create_account(self, partner_id, protocol, username, password, **kwargs):
        """Create a transfer account for a partner."""
        data = {"protocol": protocol, "username": username, "password": password, **kwargs}
        resp = self.session.post(
            f"{self.base_url}/api/partners/{partner_id}/accounts", json=data)
        resp.raise_for_status()
        return resp.json()

    def transfer_file(self, file_path, sender, destination=None, webhook_url=None):
        """Upload a file via Transfer API v2."""
        files = {"file": open(file_path, "rb")}
        data = {"sender": sender}
        if destination:
            data["destination"] = destination
        if webhook_url:
            data["webhookUrl"] = webhook_url
        resp = self.session.post(
            f"{self.base_url}/api/v2/transfer", files=files, data=data)
        resp.raise_for_status()
        return resp.json()

    def get_transfer_status(self, track_id):
        """Poll transfer status."""
        resp = self.session.get(f"{self.base_url}/api/v2/transfer/{track_id}")
        resp.raise_for_status()
        return resp.json()

    def wait_for_transfer(self, track_id, timeout=30):
        """Block until transfer completes or times out."""
        start = time.time()
        while time.time() - start < timeout:
            status = self.get_transfer_status(track_id)
            if status["status"] in ("COMPLETED", "FAILED"):
                return status
            time.sleep(2)
        raise TimeoutError(f"Transfer {track_id} did not complete in {timeout}s")

    def screen_file(self, file_path, screening_url="http://localhost:8092"):
        """Screen a file through the full pipeline (AV + DLP + Sanctions)."""
        files = {"file": open(file_path, "rb")}
        resp = self.session.post(
            f"{screening_url}/api/v1/screening/scan", files=files)
        resp.raise_for_status()
        return resp.json()

    def classify_file(self, file_path, ai_url="http://localhost:8091"):
        """Classify file content using AI."""
        files = {"file": open(file_path, "rb")}
        resp = self.session.post(f"{ai_url}/api/v1/ai/classify", files=files)
        resp.raise_for_status()
        return resp.json()

    def generate_pgp_key(self, alias, identity, passphrase="",
                          keystore_url="http://localhost:8093"):
        """Generate a PGP keypair."""
        resp = self.session.post(
            f"{keystore_url}/api/v1/keys/generate/pgp",
            json={"alias": alias, "identity": identity, "passphrase": passphrase})
        resp.raise_for_status()
        return resp.json()

    def convert_edi(self, content, target="JSON",
                     edi_url="http://localhost:8095"):
        """Convert EDI content to the target format."""
        resp = self.session.post(
            f"{edi_url}/api/v1/convert/convert",
            json={"content": content, "target": target})
        resp.raise_for_status()
        return resp.text

    def get_sentinel_dashboard(self, sentinel_url="http://localhost:8098"):
        """Get platform health dashboard."""
        resp = self.session.get(f"{sentinel_url}/api/v1/sentinel/dashboard")
        resp.raise_for_status()
        return resp.json()


# --- Complete Example ---

if __name__ == "__main__":
    client = TranzFerClient()

    # 1. Login
    client.login("admin@tranzfer.io", "admin123")

    # 2. Create and activate partner
    partner = client.create_partner("Acme Corp", slaTier="STANDARD")
    client.activate_partner(partner["id"])
    print(f"Partner created: {partner['id']}")

    # 3. Create transfer account
    account = client.create_account(
        partner["id"], "SFTP", "acme-sftp", "securePass123!")
    print(f"Account created: {account['username']}")

    # 4. Transfer a file
    result = client.transfer_file("payment.csv", "acme-sftp")
    print(f"Transfer accepted: {result['trackId']}")

    # 5. Wait for completion
    final = client.wait_for_transfer(result["trackId"])
    print(f"Status: {final['status']}, Integrity: {final.get('integrityVerified')}")

    # 6. Check platform health
    dashboard = client.get_sentinel_dashboard()
    score = dashboard.get("healthScore", {}).get("overallScore", "N/A")
    print(f"Platform health score: {score}/100")
```

---

## Deployment Options

### Docker Compose (Development)

**Full platform:**

```bash
cd file-transfer-platform
docker compose up -d
```

**Selective deployment (core only):**

```bash
docker compose up -d postgres redis rabbitmq \
  onboarding-api config-service sftp-service storage-manager
```

**With screening and AI:**

```bash
docker compose up -d postgres redis rabbitmq \
  onboarding-api config-service sftp-service storage-manager \
  screening-service ai-engine encryption-service keystore-manager
```

**Useful Docker Compose commands:**

```bash
# View service logs
docker compose logs -f onboarding-api

# Check service status
docker compose ps

# Restart a single service
docker compose restart screening-service

# Scale SFTP horizontally (3 replicas already configured)
# sftp-service, sftp-service-2, sftp-service-3 are pre-configured in docker-compose.yml

# Stop everything
docker compose down

# Stop and remove all data
docker compose down -v
```

**Key environment variables:**

| Variable | Default | Description |
|---|---|---|
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | JWT signing secret (CHANGE IN PRODUCTION) |
| `CONTROL_API_KEY` | `internal_control_secret` | HMAC secret for PCI audit log signing |
| `DATABASE_URL` | `jdbc:postgresql://postgres:5432/filetransfer` | PostgreSQL connection |
| `RABBITMQ_HOST` | `rabbitmq` | RabbitMQ hostname |
| `REDIS_HOST` | `redis` | Redis hostname |
| `SPIFFE_ENABLED` | `true` | Enable SPIFFE zero-trust identity |
| `PLATFORM_ENVIRONMENT` | `DEV` | Environment label (DEV/STG/PROD) |
| `CLUSTER_ID` | `cluster-1` | Cluster identifier |
| `ENCRYPTION_MASTER_KEY` | (insecure default) | Master key for credential encryption (CHANGE IN PRODUCTION) |

---

### Kubernetes (Production)

**Prerequisites:**

- Kubernetes 1.28+ cluster
- Helm 3.x
- Red Hat SPIRE Operator (replaces static join tokens with automatic attestation)
- Persistent volume provisioner for PostgreSQL, Redis, RabbitMQ
- Ingress controller (nginx or similar)

**Deployment strategy:**

```yaml
# Example: onboarding-api Deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: onboarding-api
  labels:
    app: tranzfer-mft
    component: onboarding-api
spec:
  replicas: 2
  selector:
    matchLabels:
      component: onboarding-api
  template:
    metadata:
      labels:
        component: onboarding-api
    spec:
      containers:
        - name: onboarding-api
          image: tranzfer/onboarding-api:latest
          ports:
            - containerPort: 8080
          env:
            - name: DATABASE_URL
              valueFrom:
                secretKeyRef:
                  name: tranzfer-db
                  key: url
            - name: JWT_SECRET
              valueFrom:
                secretKeyRef:
                  name: tranzfer-secrets
                  key: jwt-secret
            - name: SPIFFE_ENABLED
              value: "true"
            - name: SPIFFE_TRUST_DOMAIN
              value: "filetransfer.io"
          volumeMounts:
            - name: spire-agent-socket
              mountPath: /run/spire/sockets
              readOnly: true
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 5
          resources:
            requests:
              memory: "384Mi"
              cpu: "250m"
            limits:
              memory: "768Mi"
              cpu: "1000m"
      volumes:
        - name: spire-agent-socket
          csi:
            driver: "spiffe.csi.cert-manager.io"
            readOnly: true
```

**SPIRE with K8s:** Use the SPIRE Operator for automatic workload attestation via Kubernetes
service accounts. No static join tokens needed.

---

### Bare Metal

**Prerequisites:**

- Java 21+ (JDK 25 tested and supported)
- PostgreSQL 16+
- RabbitMQ 3.13+
- Redis 7+

**JAR deployment per service:**

```bash
# Build all services
mvn clean package -DskipTests

# Start individual services
java -Xmx384m -Xms192m \
  -DDATABASE_URL=jdbc:postgresql://db-host:5432/filetransfer \
  -DDB_USERNAME=postgres \
  -DDB_PASSWORD=secure_password \
  -DJWT_SECRET=your_production_256bit_secret \
  -DRABBITMQ_HOST=rabbitmq-host \
  -DREDIS_HOST=redis-host \
  -DSPIFFE_ENABLED=false \
  -jar onboarding-api/target/onboarding-api-*.jar
```

**Systemd unit file example:**

```ini
[Unit]
Description=TranzFer MFT - Onboarding API
After=network.target postgresql.service rabbitmq-server.service

[Service]
Type=simple
User=tranzfer
WorkingDirectory=/opt/tranzfer
ExecStart=/usr/bin/java -Xmx384m -Xms192m \
  -jar /opt/tranzfer/onboarding-api.jar \
  --spring.profiles.active=production
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

---

### Cloud (AWS/Azure/GCP)

**AWS:**

| TranzFer Component | AWS Service |
|---|---|
| PostgreSQL | Amazon RDS (PostgreSQL 16) |
| RabbitMQ | Amazon MQ (RabbitMQ) |
| Redis | Amazon ElastiCache (Redis 7) |
| Storage backend | Amazon S3 (set `storage.backend=s3`) |
| Container runtime | Amazon ECS / EKS |
| Load balancer | ALB (Application Load Balancer) |
| Secrets | AWS Secrets Manager |

Key environment variables for AWS:

```bash
DATABASE_URL=jdbc:postgresql://tranzfer-db.xxxxx.us-east-1.rds.amazonaws.com:5432/filetransfer
RABBITMQ_HOST=b-xxxxx.mq.us-east-1.amazonaws.com
REDIS_HOST=tranzfer-cache.xxxxx.use1.cache.amazonaws.com
STORAGE_BACKEND=s3
STORAGE_S3_BUCKET=tranzfer-files
STORAGE_S3_REGION=us-east-1
```

**Azure:**

| TranzFer Component | Azure Service |
|---|---|
| PostgreSQL | Azure Database for PostgreSQL Flexible Server |
| RabbitMQ | Azure Service Bus (or self-managed RabbitMQ on AKS) |
| Redis | Azure Cache for Redis |
| Storage backend | Azure Blob Storage (via S3 API compatibility layer) |
| Container runtime | Azure Container Apps / AKS |

**GCP:**

| TranzFer Component | GCP Service |
|---|---|
| PostgreSQL | Cloud SQL (PostgreSQL 16) |
| RabbitMQ | Self-managed on GKE (or Pub/Sub with adapter) |
| Redis | Memorystore for Redis |
| Storage backend | Cloud Storage (S3-compatible via interop) |
| Container runtime | Cloud Run / GKE |

---

## Troubleshooting Integration Issues

### Common Problems

**1. 401 Unauthorized**

```
Cause: JWT token expired, missing, or malformed.
Fix:
  - Token expires in 15 minutes. Re-authenticate via POST /api/auth/login.
  - Ensure header format is exactly: Authorization: Bearer <token>
  - Check that JWT_SECRET matches between services.
  - For service-to-service: verify SPIRE Agent is running (check /run/spire/sockets/agent.sock).
```

**2. 404 Not Found**

```
Cause: Wrong service/port, service not running, or incorrect path.
Fix:
  - Verify the service is running: curl http://localhost:{port}/actuator/health
  - Double-check the API path (e.g., /api/v1/screening/scan, not /api/screening/scan)
  - In gateway mode, ensure the gateway routes to the correct backend service.
```

**3. 500 Internal Server Error**

```
Cause: Service-level error (database connection, downstream service failure, etc.).
Fix:
  - Check the X-Correlation-Id header in the response for log correlation.
  - Check service logs: docker compose logs {service-name}
  - Check service health: curl http://localhost:{port}/actuator/health
  - Verify PostgreSQL is reachable: docker compose ps postgres
```

**4. Connection Refused**

```
Cause: Service not running, wrong port, or network isolation.
Fix:
  - docker compose ps -- check all services are "Up"
  - Verify port mappings: docker compose port {service} {port}
  - Inside Docker: services communicate via container names (not localhost)
  - Outside Docker: use published ports (e.g., localhost:8080)
```

**5. File Not Processed After Upload**

```
Cause: No matching flow, no folder mapping, or screening quarantined the file.
Fix:
  - Check flow match criteria: GET /api/flows (config-service:8084)
  - Check quarantine: GET /api/v1/quarantine (screening-service:8092)
  - Check flow executions: GET /api/flows/executions?trackId={id} (config-service:8084)
  - Verify the transfer account has an active folder mapping
```

**6. Notifications Not Firing**

```
Cause: RabbitMQ connection issue, connector misconfigured, or rule disabled.
Fix:
  - Check RabbitMQ: http://localhost:15672 (guest/guest) -- verify exchange exists
  - Test connector: POST /api/connectors/{id}/test (config-service:8084)
  - Check notification rules: GET /api/notifications/rules (notification-service:8097)
  - Check notification logs: GET /api/notifications/logs/recent
```

**7. SFTP Connection Refused on Port 2222**

```
Cause: SFTP service not started, or account not assigned to server instance.
Fix:
  - Verify service: curl http://localhost:8081/actuator/health
  - Check account-to-server assignment: GET /api/servers/{id}/accounts
  - Check access: GET /api/servers/{id}/access-check/{username}
  - Verify partner and account are both ACTIVE
```

**8. Screening Service Shows "Loading..."**

```
Cause: Sanctions lists are still downloading on first startup.
Fix:
  - Check sanctions list status: GET /api/v1/screening/lists
  - Wait for initial load (typically 30-60 seconds)
  - Force refresh: POST /api/v1/screening/lists/refresh
```

---

## Service Port Map

Quick reference for all service ports:

| Service | HTTP Port | Protocol Port(s) | Database |
|---|---|---|---|
| onboarding-api | 8080 | -- | PostgreSQL |
| sftp-service | 8081 | SSH: 2222 | PostgreSQL |
| ftp-service | 8082 | FTP: 21, Passive: 21000-21010 | PostgreSQL |
| ftp-web-service | 8083 | -- | PostgreSQL |
| config-service | 8084 | -- | PostgreSQL |
| gateway-service | 8085 | SSH: 2220, FTP: 2122 | PostgreSQL |
| encryption-service | 8086 | -- | PostgreSQL |
| external-forwarder-service | 8087 | -- | PostgreSQL |
| dmz-proxy | 8088 | SSH: 2222, FTP: 21, HTTPS: 443 | **None** (Redis only) |
| license-service | 8089 | -- | PostgreSQL |
| analytics-service | 8090 | -- | PostgreSQL |
| ai-engine | 8091 | -- | PostgreSQL |
| screening-service | 8092 | -- | PostgreSQL |
| keystore-manager | 8093 | -- | PostgreSQL |
| as2-service | 8094 | -- | PostgreSQL |
| edi-converter | 8095 | -- | **None** (stateless) |
| storage-manager | 8096 | -- | PostgreSQL |
| notification-service | 8097 | -- | PostgreSQL |
| platform-sentinel | 8098 | -- | PostgreSQL |

**UI applications:**

| Application | Port |
|---|---|
| Admin UI | 3000 |
| FTP-Web UI | 3001 |
| Partner Portal | 3002 |

**Infrastructure:**

| Component | Port(s) |
|---|---|
| PostgreSQL | 5432 |
| Redis | 6379 |
| RabbitMQ (AMQP) | 5672 |
| RabbitMQ (Management) | 15672 |
| Redpanda (Kafka API) | 9092 |
| Redpanda (Admin) | 9644 |
| Prometheus | 9090 |
| Grafana | 3030 |
| Loki | 3100 |
| AlertManager | 9093 |
| MinIO (optional) | 9000 / 9001 |

---

## Dynamic Flow Fabric

TranzFer ships with a Kafka-compatible event fabric (Redpanda by default) that runs in parallel with RabbitMQ. Which one is used depends on the deployment tier — both are production paths and the platform dual-publishes critical events so you can migrate consumers independently.

**When Fabric is active:**
- Every flow step writes a checkpoint (`fabric_checkpoints`) with a 5-minute lease
- Pod heartbeats land in `fabric_instances` every 30s
- Crash recovery: `LeaseReaperJob` detects expired leases and schedules `FlowRestartService.restartFromBeginning` via the existing `scheduledRetryAt` column — multi-instance safe, no double-fire
- Poison messages go to `<topic>.dlq` after 5 attempts (configurable)

**Controlling Fabric from environment:**

| Variable | Default | Notes |
|---|---|---|
| `FABRIC_ENABLED` | `true` | Master switch. `false` → pure RabbitMQ, Tier 1 behavior |
| `FABRIC_BROKER_URL` | `redpanda:9092` | Kafka bootstrap servers |
| `FABRIC_FLOW_PARTITION_COUNT` | `32` | Partition count applied when topics are auto-created |
| `FABRIC_CONSUMER_MAX_DELIVERY_ATTEMPTS` | `5` | Redelivery attempts before routing to `<topic>.dlq`. Set `0` to disable DLQ |
| `FABRIC_FLOW_LEASE_DURATION_SECONDS` | `300` | Per-step lease duration |

**Graceful fallback:** if the broker is unreachable at startup, services fall back to an in-memory fabric client and continue to boot. They log a warning and retry health on subsequent publishes. Services never crash because of broker trouble.

**Observability endpoints** (onboarding-api, role=OPERATOR):

| Endpoint | Description |
|---|---|
| `GET /api/fabric/track/{trackId}/timeline` | Full per-step timeline for a trackId |
| `GET /api/fabric/queues` | In-progress count per step type |
| `GET /api/fabric/instances` | Active and dead instances from heartbeats |
| `GET /api/fabric/stuck?page=&size=` | Paginated list of stuck work items (IN_PROGRESS with expired lease) |
| `GET /api/fabric/latency?hours=1&sample=10000` | p50/p95/p99 step latency over a bounded time window |

The admin UI exposes all of the above on the `/fabric` page (Intelligence group in the sidebar).

---

## Health Check Endpoints

Every service exposes Spring Boot Actuator health endpoints:

| Endpoint | Auth Required | Description |
|---|---|---|
| `/actuator/health` | No | Aggregate health status (UP/DOWN) |
| `/actuator/health/liveness` | No | Kubernetes liveness probe |
| `/actuator/health/readiness` | No | Kubernetes readiness probe |
| `/actuator/prometheus` | No | Prometheus metrics (counter, gauge, histogram) |
| `/actuator/info` | No | Build info and version |

**Service-specific health endpoints (require JWT):**

| Service | Endpoint | Returns |
|---|---|---|
| screening-service | `/api/v1/screening/health` | Pipeline status, ClamAV connectivity, sanctions lists |
| keystore-manager | `/api/v1/keys/health` | Total keys, keys by type |
| storage-manager | `/api/v1/storage/health` | Backend type, tier distribution, features |
| ai-engine | `/api/v1/ai/health` | Feature flags, version |
| edi-converter | `/api/v1/convert/health` | Format counts, template counts, partner profiles |
| platform-sentinel | `/api/v1/sentinel/health` | Rule count, open findings |
| notification-service | `/api/notifications/health` | Templates, rules, sent/failed counts (24h) |

**Log correlation:**

All HTTP responses include an `X-Correlation-Id` header. Use this to trace a request across
multiple services in your log aggregation system (Loki, Elasticsearch, Splunk):

```bash
# Extract correlation ID from a failed request
curl -v http://localhost:8080/api/partners 2>&1 | grep -i x-correlation-id

# Search logs by correlation ID
docker compose logs | grep "corr-a1b2c3d4"
```

---

## What's Next

After completing your integration:

1. **Security hardening:** See `docs/SECURITY-ARCHITECTURE.md` and `docs/PCI-DSS-COMPLIANCE.md`
2. **Capacity planning:** See `docs/CAPACITY-PLANNING.md`
3. **Kubernetes deployment:** See `docs/INSTALL-KUBERNETES.md`
4. **Full API reference:** See `docs/API-REFERENCE.md`
5. **Feature deep-dive:** See `docs/FEATURE-GUIDE.md`
6. **Configuration reference:** See `docs/CONFIGURATION.md`

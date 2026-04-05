# Onboarding API -- Demo & Quick Start Guide

> Central platform API for TranzFer MFT: authentication, accounts, file transfers, partner portal, multi-tenancy, 2FA, peer-to-peer transfers, blockchain audit, and admin CLI.

---

## What This Service Does

- **Authentication & Authorization** -- Register system users, log in, receive JWTs. All other endpoints require a valid Bearer token.
- **Transfer Account Management** -- Create SFTP/FTP accounts, folder mappings, and server instances used by the data-plane services.
- **File Transfer API (v2)** -- Single-call file upload, batch transfer, status polling, and delivery receipts with end-to-end checksum verification.
- **Partner Self-Service Portal** -- Partners log in with their transfer-account credentials and can view dashboards, transfer history, SLA status, rotate SSH keys, and download delivery receipts -- all scoped to their own data.
- **Multi-Tenancy** -- Self-service tenant signup with isolated namespaces, custom domains, and usage tracking.
- **Two-Factor Authentication (TOTP)** -- RFC 6238 TOTP with QR provisioning, backup codes, and enrollment verification.
- **Peer-to-Peer Transfers** -- Server-coordinated direct client-to-client file transfers with presence tracking and transfer tickets.
- **Blockchain Audit Trail** -- Merkle-tree-anchored cryptographic proofs of every file transfer for non-repudiation.
- **Transfer Journey Tracker** -- Full lifecycle view of a transfer across every microservice (upload, AI classification, flow processing, screening, routing, delivery).
- **Admin CLI** -- Text-command interface (`help`, `status`, `accounts list`, `track <id>`, `cluster status`, etc.) powering the Admin UI terminal.
- **Autonomous Onboarding** -- Zero-touch partner detection, auto-provisioning, behavior learning, and auto-flow creation.
- **Cluster Management** -- View topology, manage communication modes (within-cluster vs. cross-cluster federation), and inspect registered services.

## What You Need (Prerequisites Checklist)

| Prerequisite | Version | Why |
|---|---|---|
| Java (JDK) | 21+ | Runtime (Eclipse Temurin recommended) |
| Maven | 3.9+ | Build tool (only for "From Source") |
| Docker | 24+ | Container runtime |
| Docker Compose | v2+ | Orchestrate postgres + rabbitmq |
| PostgreSQL | 16+ | Primary data store (or use the Docker method) |
| RabbitMQ | 3.13+ | Event bus (or use the Docker method) |
| curl | any | Demo commands |
| jq (optional) | any | Pretty-print JSON responses |

> See `docs/PREREQUISITES.md` for installation instructions per OS.

---

## Install & Start

### Method 1: Docker (Any OS -- quickest)

Pull and run the pre-built image. You must supply a running PostgreSQL and RabbitMQ.

```bash
# 1. Start infrastructure (if you don't already have it)
docker run -d --name mft-postgres \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine

docker run -d --name mft-rabbitmq \
  -e RABBITMQ_DEFAULT_USER=guest \
  -e RABBITMQ_DEFAULT_PASS=guest \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:3.13-management-alpine

# 2. Build the image (from the repo root)
cd /path/to/file-transfer-platform
docker build -t tranzfer/onboarding-api ./onboarding-api

# 3. Run
docker run -d --name mft-onboarding-api \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e RABBITMQ_HOST=host.docker.internal \
  -e JWT_SECRET=change_me_in_production_256bit_secret_key!! \
  -p 8080:8080 \
  tranzfer/onboarding-api
```

> **Linux users**: Replace `host.docker.internal` with `172.17.0.1` or use `--network host`.

### Method 2: Docker Compose (recommended for demos)

From the repository root, start only the services you need:

```bash
cd /path/to/file-transfer-platform

# Start postgres + rabbitmq + onboarding-api
docker compose up -d postgres rabbitmq onboarding-api
```

This starts:
- PostgreSQL on port **5432**
- RabbitMQ on port **5672** (management UI on **15672**)
- Onboarding API on port **8080**

### Method 3: From Source (Any OS)

```bash
cd /path/to/file-transfer-platform

# 1. Build the shared module first (required dependency)
mvn -pl shared install -DskipTests

# 2. Build and run onboarding-api
mvn -pl onboarding-api spring-boot:run \
  -Dspring-boot.run.arguments="\
    --DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer \
    --DB_USERNAME=postgres \
    --DB_PASSWORD=postgres \
    --RABBITMQ_HOST=localhost"
```

> **Windows (PowerShell)**: Replace `\` line continuations with backtick `` ` `` or put all arguments on one line.

---

## Verify It's Running

```bash
curl -s http://localhost:8080/actuator/health | jq .
```

Expected response:

```json
{
  "status": "UP"
}
```

---

## Demo 1: Register, Login, and Manage Accounts

This is the foundational flow. Every other demo builds on the JWT obtained here.

### Step 1: Register a new system user

```bash
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@acme.com",
    "password": "SecurePass123!"
  }' | jq .
```

Expected response:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbkBhY21lLmNvbSIs...",
  "tokenType": "Bearer",
  "expiresIn": 900000
}
```

### Step 2: Log in (obtain a fresh JWT)

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@acme.com",
    "password": "SecurePass123!"
  }' | jq .
```

Expected response (same shape as register):

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900000
}
```

Save the token for subsequent requests:

```bash
# Linux / macOS
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@acme.com","password":"SecurePass123!"}' | jq -r '.accessToken')

echo $TOKEN
```

```powershell
# Windows (PowerShell)
$resp = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login `
  -ContentType "application/json" `
  -Body '{"email":"admin@acme.com","password":"SecurePass123!"}'
$TOKEN = $resp.accessToken
```

### Step 3: Create an SFTP transfer account

```bash
curl -s -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "protocol": "SFTP",
    "username": "partner_alpha",
    "password": "P@rtner2024!",
    "permissions": {"read": true, "write": true, "delete": false}
  }' | jq .
```

Expected response:

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "protocol": "SFTP",
  "username": "partner_alpha",
  "homeDir": "/data/sftp/partner_alpha",
  "permissions": {
    "read": true,
    "write": true,
    "delete": false
  },
  "active": true,
  "serverInstance": null,
  "createdAt": "2026-04-05T10:00:00Z",
  "connectionInstructions": "sftp -P 2222 partner_alpha@<server_host>"
}
```

### Step 4: Create a second account (destination)

```bash
curl -s -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "protocol": "SFTP",
    "username": "partner_beta",
    "password": "B3ta$ecure!",
    "permissions": {"read": true, "write": true, "delete": false}
  }' | jq .
```

### Step 5: Retrieve an account

```bash
curl -s http://localhost:8080/api/accounts/a1b2c3d4-e5f6-7890-abcd-ef1234567890 \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### Step 6: Create a folder mapping (route files between accounts)

```bash
curl -s -X POST http://localhost:8080/api/folder-mappings \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceAccountId": "<partner_alpha_id>",
    "sourcePath": "/inbox",
    "destinationAccountId": "<partner_beta_id>",
    "destinationPath": "/outbox",
    "filenamePattern": ".*\\.csv$"
  }' | jq .
```

Expected response:

```json
{
  "id": "f1a2b3c4-d5e6-7890-abcd-ef1234567890",
  "sourceAccountId": "a1b2c3d4-...",
  "sourceUsername": "partner_alpha",
  "sourcePath": "/inbox",
  "destinationAccountId": "b2c3d4e5-...",
  "destinationUsername": "partner_beta",
  "destinationPath": "/outbox",
  "filenamePattern": ".*\\.csv$",
  "active": true,
  "createdAt": "2026-04-05T10:05:00Z"
}
```

---

## Demo 2: File Transfer (Upload, Track, Receipt)

Use the v2 Transfer API for a single-call file transfer with status polling and delivery receipts.

### Step 1: Upload a file

```bash
# Create a sample file
echo "id,name,amount" > /tmp/payment_batch_001.csv
echo "1,ACME Corp,50000.00" >> /tmp/payment_batch_001.csv
echo "2,Globex Inc,75000.00" >> /tmp/payment_batch_001.csv

# Upload via API
curl -s -X POST http://localhost:8080/api/v2/transfer \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/payment_batch_001.csv" \
  -F "sender=partner_alpha" \
  -F "destination=partner_beta" | jq .
```

Expected response:

```json
{
  "trackId": "TRZ-20260405-ABC123",
  "filename": "payment_batch_001.csv",
  "sizeBytes": 72,
  "sender": "partner_alpha",
  "destination": "partner_beta",
  "flow": null,
  "status": "ACCEPTED",
  "message": "File accepted for processing. Track with GET /api/v2/transfer/TRZ-20260405-ABC123",
  "pollUrl": "/api/v2/transfer/TRZ-20260405-ABC123",
  "receiptUrl": "/api/v2/transfer/TRZ-20260405-ABC123/receipt",
  "timestamp": "2026-04-05T10:10:00Z"
}
```

### Step 2: Poll transfer status

```bash
curl -s http://localhost:8080/api/v2/transfer/TRZ-20260405-ABC123 \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Expected response:

```json
{
  "trackId": "TRZ-20260405-ABC123",
  "filename": "payment_batch_001.csv",
  "status": "ROUTED",
  "sizeBytes": 72,
  "uploadedAt": "2026-04-05T10:10:00Z",
  "routedAt": "2026-04-05T10:10:02Z",
  "completedAt": null,
  "sourceChecksum": "a1b2c3d4e5f6...",
  "destinationChecksum": "a1b2c3d4e5f6...",
  "integrityVerified": true,
  "retryCount": 0,
  "error": null
}
```

### Step 3: Get a delivery receipt

```bash
curl -s http://localhost:8080/api/v2/transfer/TRZ-20260405-ABC123/receipt \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Expected response:

```json
{
  "receiptId": "RCP-TRZ-20260405-ABC123",
  "trackId": "TRZ-20260405-ABC123",
  "filename": "payment_batch_001.csv",
  "status": "ROUTED",
  "sizeBytes": 72,
  "sourceChecksum": "a1b2c3d4e5f6...",
  "destinationChecksum": "a1b2c3d4e5f6...",
  "integrityVerified": true,
  "uploadedAt": "2026-04-05T10:10:00Z",
  "deliveredAt": "2026-04-05T10:10:02Z",
  "generatedAt": "2026-04-05T10:15:00Z"
}
```

### Step 4: Batch transfer (multiple files)

```bash
echo "record1" > /tmp/file_a.txt
echo "record2" > /tmp/file_b.txt

curl -s -X POST http://localhost:8080/api/v2/transfer/batch \
  -H "Authorization: Bearer $TOKEN" \
  -F "files=@/tmp/file_a.txt" \
  -F "files=@/tmp/file_b.txt" \
  -F "sender=partner_alpha" | jq .
```

Expected response:

```json
{
  "totalFiles": 2,
  "results": [
    { "trackId": "TRZ-20260405-DEF456", "filename": "file_a.txt", "status": "ACCEPTED" },
    { "trackId": "TRZ-20260405-GHI789", "filename": "file_b.txt", "status": "ACCEPTED" }
  ],
  "sender": "partner_alpha",
  "timestamp": "2026-04-05T10:20:00Z"
}
```

### Step 5: View the full transfer journey

```bash
curl -s http://localhost:8080/api/journey/TRZ-20260405-ABC123 \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Expected response:

```json
{
  "trackId": "TRZ-20260405-ABC123",
  "filename": "payment_batch_001.csv",
  "overallStatus": "ROUTED",
  "stages": [
    {
      "order": 1,
      "service": "sftp-service / ftp-service",
      "stage": "FILE_RECEIVED",
      "status": "COMPLETED",
      "detail": "File: payment_batch_001.csv",
      "metadata": {
        "filename": "payment_batch_001.csv",
        "source": "/data/sftp/partner_alpha/inbox/payment_batch_001.csv",
        "size": "72 bytes",
        "sourceChecksum": "a1b2c3d4e5f6..."
      },
      "timestamp": "2026-04-05T10:10:00Z"
    },
    {
      "order": 20,
      "service": "routing-engine",
      "stage": "FILE_ROUTED",
      "status": "COMPLETED",
      "detail": "Destination: /data/sftp/partner_beta/outbox/payment_batch_001.csv",
      "timestamp": "2026-04-05T10:10:02Z"
    }
  ],
  "auditTrail": [],
  "integrityStatus": "VERIFIED",
  "sourceChecksum": "a1b2c3d4e5f6...",
  "destinationChecksum": "a1b2c3d4e5f6...",
  "totalDurationMs": 2000
}
```

### Step 6: Search transfer journeys

```bash
# Search by filename
curl -s "http://localhost:8080/api/journey?filename=payment&limit=10" \
  -H "Authorization: Bearer $TOKEN" | jq .

# Search by status
curl -s "http://localhost:8080/api/journey?status=FAILED&limit=5" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

## Demo 3: Partner Portal (Self-Service)

Partners authenticate with their transfer-account credentials (not system user credentials) and can only see their own data.

### Step 1: Partner login

```bash
curl -s -X POST http://localhost:8080/api/partner/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "partner_alpha",
    "password": "P@rtner2024!"
  }' | jq .
```

Expected response:

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "username": "partner_alpha",
  "protocol": "SFTP",
  "homeDir": "/data/sftp/partner_alpha",
  "role": "PARTNER"
}
```

```bash
PARTNER_TOKEN=$(curl -s -X POST http://localhost:8080/api/partner/login \
  -H "Content-Type: application/json" \
  -d '{"username":"partner_alpha","password":"P@rtner2024!"}' | jq -r '.token')
```

### Step 2: View partner dashboard

```bash
curl -s http://localhost:8080/api/partner/dashboard \
  -H "Authorization: Bearer $PARTNER_TOKEN" | jq .
```

Expected response:

```json
{
  "username": "partner_alpha",
  "totalTransfers": 3,
  "todayTransfers": 1,
  "weekTransfers": 3,
  "failedTransfers": 0,
  "successRate": 100.0,
  "lastTransfer": "2026-04-05T10:10:00Z"
}
```

### Step 3: View transfer history (paginated)

```bash
curl -s "http://localhost:8080/api/partner/transfers?page=0&size=10" \
  -H "Authorization: Bearer $PARTNER_TOKEN" | jq .
```

Expected response:

```json
[
  {
    "trackId": "TRZ-20260405-ABC123",
    "filename": "payment_batch_001.csv",
    "status": "ROUTED",
    "sizeBytes": 72,
    "uploadedAt": "2026-04-05T10:10:00Z",
    "routedAt": "2026-04-05T10:10:02Z",
    "completedAt": null,
    "sourceChecksum": "a1b2c3d4e5f6...",
    "destinationChecksum": "a1b2c3d4e5f6...",
    "integrityVerified": true,
    "retryCount": 0
  }
]
```

### Step 4: Track a specific transfer (partner-scoped)

```bash
curl -s http://localhost:8080/api/partner/track/TRZ-20260405-ABC123 \
  -H "Authorization: Bearer $PARTNER_TOKEN" | jq .
```

Expected response:

```json
{
  "trackId": "TRZ-20260405-ABC123",
  "filename": "payment_batch_001.csv",
  "status": "ROUTED",
  "integrity": "VERIFIED",
  "sourceChecksum": "a1b2c3d4e5f6...",
  "stages": [
    { "stage": "RECEIVED", "status": "COMPLETED", "timestamp": "2026-04-05T10:10:00Z", "detail": "File received: payment_batch_001.csv" },
    { "stage": "DELIVERED", "status": "COMPLETED", "timestamp": "2026-04-05T10:10:02Z", "detail": "Routed to destination" }
  ],
  "uploadedAt": "2026-04-05T10:10:00Z",
  "completedAt": ""
}
```

### Step 5: Get a delivery receipt (partner-scoped)

```bash
curl -s http://localhost:8080/api/partner/receipt/TRZ-20260405-ABC123 \
  -H "Authorization: Bearer $PARTNER_TOKEN" | jq .
```

Expected response:

```json
{
  "receiptId": "RCP-TRZ-20260405-ABC123",
  "trackId": "TRZ-20260405-ABC123",
  "filename": "payment_batch_001.csv",
  "sender": "partner_alpha",
  "status": "ROUTED",
  "fileSizeBytes": 72,
  "sourceChecksum": "a1b2c3d4e5f6...",
  "destinationChecksum": "a1b2c3d4e5f6...",
  "integrityVerified": true,
  "uploadedAt": "2026-04-05T10:10:00Z",
  "deliveredAt": "2026-04-05T10:10:02Z",
  "completedAt": "",
  "generatedAt": "2026-04-05T10:30:00Z",
  "platform": "TranzFer MFT",
  "notice": "This receipt confirms the file was received, processed, and delivered with cryptographic integrity verification."
}
```

### Step 6: Test connectivity and rotate SSH key

```bash
# Test connection info
curl -s http://localhost:8080/api/partner/test-connection \
  -H "Authorization: Bearer $PARTNER_TOKEN" | jq .
```

Expected response:

```json
{
  "username": "partner_alpha",
  "protocol": "SFTP",
  "homeDir": "/data/sftp/partner_alpha",
  "active": true,
  "serverHost": "Use the hostname provided by your administrator",
  "serverPort": 2222,
  "instructions": "sftp -P 2222 partner_alpha@<server_host>"
}
```

```bash
# Rotate SSH key
curl -s -X POST http://localhost:8080/api/partner/rotate-key \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $PARTNER_TOKEN" \
  -d '{"publicKey": "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINew...key partner@host"}' | jq .
```

Expected response:

```json
{
  "status": "KEY_ROTATED",
  "username": "partner_alpha",
  "oldKeyPrefix": "none",
  "newKeyPrefix": "ssh-ed25519 AAAAC3...",
  "rotatedAt": "2026-04-05T10:35:00Z",
  "note": "New key is active immediately. Test your connection."
}
```

### Step 7: View SLA status

```bash
curl -s http://localhost:8080/api/partner/sla \
  -H "Authorization: Bearer $PARTNER_TOKEN" | jq .
```

Expected response:

```json
{
  "username": "partner_alpha",
  "period": "last 7 days",
  "totalTransfers": 3,
  "failedTransfers": 0,
  "errorRate": "0.0%",
  "avgDeliveryTimeMs": 2000,
  "slaCompliant": true
}
```

---

## Demo 4: Two-Factor Authentication (TOTP)

### Step 1: Enable 2FA for a user

```bash
curl -s -X POST http://localhost:8080/api/2fa/enable \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "username": "partner_alpha",
    "method": "TOTP_APP",
    "email": "alpha@acme.com"
  }' | jq .
```

Expected response:

```json
{
  "username": "partner_alpha",
  "secret": "JBSWY3DPEHPK3PXP...",
  "method": "TOTP_APP",
  "provisioningUri": "otpauth://totp/TranzFer:partner_alpha?secret=JBSWY3DPEHPK3PXP...&issuer=TranzFer&digits=6&period=30",
  "backupCodes": [
    "04821937",
    "18273645",
    "29384756",
    "30495867",
    "41506978",
    "52617089",
    "63728190",
    "74839201",
    "85940312",
    "96051423"
  ],
  "qrContent": "otpauth://totp/TranzFer:partner_alpha?secret=JBSWY3DPEHPK3PXP...&issuer=TranzFer&digits=6&period=30",
  "instructions": "Scan the QR code with Google Authenticator, Authy, or Microsoft Authenticator. Then verify with a code."
}
```

> Copy the `provisioningUri` into a QR code generator or paste the `secret` directly into your authenticator app.

### Step 2: Verify a TOTP code (completes enrollment)

```bash
curl -s -X POST http://localhost:8080/api/2fa/verify \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "username": "partner_alpha",
    "code": "123456"
  }' | jq .
```

Expected response (on valid code):

```json
{
  "valid": true,
  "username": "partner_alpha",
  "enrolled": true
}
```

### Step 3: Check 2FA status

```bash
curl -s http://localhost:8080/api/2fa/status/partner_alpha \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Expected response:

```json
{
  "enabled": true,
  "enrolled": true,
  "method": "TOTP_APP",
  "username": "partner_alpha",
  "lastUsed": "2026-04-05T10:40:00Z"
}
```

### Step 4: Disable 2FA

```bash
curl -s -X POST http://localhost:8080/api/2fa/disable \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"username": "partner_alpha"}' | jq .
```

Expected response:

```json
{
  "status": "DISABLED",
  "username": "partner_alpha"
}
```

---

## Demo 5: Multi-Tenancy and Tenant Signup

### Step 1: Self-service tenant signup

```bash
curl -s -X POST http://localhost:8080/api/v1/tenants/signup \
  -H "Content-Type: application/json" \
  -d '{
    "slug": "acme-corp",
    "companyName": "ACME Corporation",
    "email": "admin@acme-corp.com"
  }' | jq .
```

Expected response:

```json
{
  "tenantId": "d1e2f3a4-b5c6-7890-abcd-ef1234567890",
  "slug": "acme-corp",
  "domain": "acme-corp.tranzfer.io",
  "plan": "TRIAL (30 days)",
  "sftpHost": "acme-corp.tranzfer.io",
  "sftpPort": 2222,
  "portalUrl": "https://acme-corp.tranzfer.io/portal",
  "apiUrl": "https://acme-corp.tranzfer.io/api"
}
```

### Step 2: List all tenants

```bash
curl -s http://localhost:8080/api/v1/tenants \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### Step 3: Get a specific tenant

```bash
curl -s http://localhost:8080/api/v1/tenants/acme-corp \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### Step 4: View tenant usage

```bash
curl -s http://localhost:8080/api/v1/tenants/acme-corp/usage \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Expected response:

```json
{
  "slug": "acme-corp",
  "plan": "TRIAL",
  "transfersUsed": 0,
  "transferLimit": 1000,
  "trialEndsAt": "2026-05-05T10:45:00Z",
  "active": true
}
```

---

## Demo 6: Blockchain Audit and Verification

### Step 1: Verify a transfer's blockchain proof

```bash
curl -s http://localhost:8080/api/v1/blockchain/verify/TRZ-20260405-ABC123 \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Expected response:

```json
{
  "verified": true,
  "trackId": "TRZ-20260405-ABC123",
  "filename": "payment_batch_001.csv",
  "sha256": "a1b2c3d4e5f6...",
  "chain": "INTERNAL",
  "merkleRoot": "e7f8a9b0c1d2...",
  "anchoredAt": "2026-04-05T11:00:00Z",
  "proof": "merkle_root=e7f8a9b0c1d2...;leaf=a1b2c3d4e5f6...;batch_size=5",
  "nonRepudiation": "This cryptographic proof confirms the file existed with this exact content at the stated time."
}
```

### Step 2: List recent blockchain anchors

```bash
curl -s http://localhost:8080/api/v1/blockchain/anchors \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

## Demo 7: Integration Patterns -- Python, Java, Node.js

### Python

```python
import requests

BASE = "http://localhost:8080"

# 1. Login
auth = requests.post(f"{BASE}/api/auth/login", json={
    "email": "admin@acme.com",
    "password": "SecurePass123!"
}).json()
headers = {"Authorization": f"Bearer {auth['accessToken']}"}

# 2. Upload a file
with open("/tmp/payment_batch_001.csv", "rb") as f:
    resp = requests.post(
        f"{BASE}/api/v2/transfer",
        headers=headers,
        files={"file": ("payment_batch_001.csv", f, "text/csv")},
        data={"sender": "partner_alpha", "destination": "partner_beta"}
    )
track_id = resp.json()["trackId"]
print(f"Track ID: {track_id}")

# 3. Poll status
import time
for _ in range(10):
    status = requests.get(f"{BASE}/api/v2/transfer/{track_id}", headers=headers).json()
    print(f"Status: {status['status']}")
    if status["status"] in ("COMPLETED", "FAILED", "ROUTED"):
        break
    time.sleep(2)

# 4. Get receipt
receipt = requests.get(f"{BASE}/api/v2/transfer/{track_id}/receipt", headers=headers).json()
print(f"Integrity verified: {receipt['integrityVerified']}")
```

### Java (HttpClient -- JDK 11+)

```java
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

public class TranzFerDemo {
    static final String BASE = "http://localhost:8080";
    static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        // 1. Login
        var loginReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"email\":\"admin@acme.com\",\"password\":\"SecurePass123!\"}"))
            .build();
        var loginResp = client.send(loginReq, HttpResponse.BodyHandlers.ofString());
        // Parse token from JSON (use Jackson/Gson in production)
        String token = loginResp.body().split("\"accessToken\":\"")[1].split("\"")[0];

        // 2. Upload a file (multipart)
        String boundary = "----TranzFerBoundary";
        byte[] fileBytes = Files.readAllBytes(Path.of("/tmp/payment_batch_001.csv"));
        String body = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"payment_batch_001.csv\"\r\n"
            + "Content-Type: text/csv\r\n\r\n"
            + new String(fileBytes) + "\r\n"
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"sender\"\r\n\r\npartner_alpha\r\n"
            + "--" + boundary + "--\r\n";

        var uploadReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/v2/transfer"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        var uploadResp = client.send(uploadReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Upload response: " + uploadResp.body());
    }
}
```

### Node.js (fetch -- Node 18+)

```javascript
const BASE = "http://localhost:8080";
const fs = require("fs");
const path = require("path");

async function demo() {
  // 1. Login
  const loginResp = await fetch(`${BASE}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: "admin@acme.com", password: "SecurePass123!" }),
  });
  const { accessToken } = await loginResp.json();
  const headers = { Authorization: `Bearer ${accessToken}` };

  // 2. Upload a file
  const FormData = (await import("formdata-node")).FormData;
  const { fileFromPath } = await import("formdata-node/file-from-path");
  const form = new FormData();
  form.set("file", await fileFromPath("/tmp/payment_batch_001.csv"));
  form.set("sender", "partner_alpha");
  form.set("destination", "partner_beta");

  const uploadResp = await fetch(`${BASE}/api/v2/transfer`, {
    method: "POST",
    headers,
    body: form,
  });
  const { trackId } = await uploadResp.json();
  console.log(`Track ID: ${trackId}`);

  // 3. Poll status
  let status;
  for (let i = 0; i < 10; i++) {
    const resp = await fetch(`${BASE}/api/v2/transfer/${trackId}`, { headers });
    status = await resp.json();
    console.log(`Status: ${status.status}`);
    if (["COMPLETED", "FAILED", "ROUTED"].includes(status.status)) break;
    await new Promise((r) => setTimeout(r, 2000));
  }

  // 4. Get receipt
  const receiptResp = await fetch(`${BASE}/api/v2/transfer/${trackId}/receipt`, { headers });
  const receipt = await receiptResp.json();
  console.log(`Integrity verified: ${receipt.integrityVerified}`);
}

demo().catch(console.error);
```

---

## Demo 8: Admin CLI and Autonomous Onboarding

### Admin CLI -- execute commands via the API

```bash
# Platform status overview
curl -s -X POST http://localhost:8080/api/cli/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"command": "status"}' | jq -r '.output'
```

Expected output:

```
=== Platform Status ===
Users:             1
Accounts:          2 active / 2 total
Transfer Records:  3
Active Flows:      0
Flow Executions:   0
Services:          0 registered
Cluster:           default-cluster
Comm. Mode:        WITHIN_CLUSTER
Known Clusters:    1 (default-cluster)
```

```bash
# List all commands
curl -s -X POST http://localhost:8080/api/cli/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"command": "help"}' | jq -r '.output'

# List transfer accounts
curl -s -X POST http://localhost:8080/api/cli/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"command": "accounts list"}' | jq -r '.output'

# Quick-onboard a user (creates system user + SFTP account)
curl -s -X POST http://localhost:8080/api/cli/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"command": "onboard partner_gamma@corp.com Gamma2024!"}' | jq -r '.output'

# Track a transfer by ID
curl -s -X POST http://localhost:8080/api/cli/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"command": "track TRZ-20260405-ABC123"}' | jq -r '.output'

# View cluster topology
curl -s -X POST http://localhost:8080/api/cli/execute \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"command": "cluster status"}' | jq -r '.output'
```

### Autonomous Onboarding -- zero-touch partner detection

```bash
# Simulate unknown client detection
curl -s -X POST http://localhost:8080/api/v1/auto-onboard/detect \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "sourceIp": "192.168.1.100",
    "clientVersion": "OpenSSH_9.6",
    "capabilities": {
      "ciphers": "aes256-gcm@openssh.com,chacha20-poly1305@openssh.com",
      "macs": "hmac-sha2-256-etm@openssh.com"
    }
  }' | jq .
```

Expected response:

```json
{
  "sessionId": "e1f2a3b4-c5d6-7890-abcd-ef1234567890",
  "phase": "ACCOUNT_CREATED",
  "username": "auto_192_168_1_100_4521",
  "tempPassword": "AutoKjR5mNpq!1",
  "protocol": "SFTP",
  "securityProfile": "MODERN",
  "message": "Account auto-created. Partner can connect immediately. System is learning file patterns.",
  "nextSteps": [
    "Partner connects with generated credentials",
    "System observes first 5 file transfers",
    "Optimal processing flow auto-created",
    "Admin notified for review (optional)"
  ]
}
```

```bash
# View all onboarding sessions
curl -s http://localhost:8080/api/v1/auto-onboard/sessions \
  -H "Authorization: Bearer $TOKEN" | jq .

# View onboarding stats
curl -s http://localhost:8080/api/v1/auto-onboard/stats \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Expected stats response:

```json
{
  "totalSessions": 1,
  "completed": 0,
  "learning": 0,
  "detected": 1,
  "avgFilesToLearn": 0.0
}
```

---

## Use Cases

1. **Financial institution B2B file exchange** -- Onboard banking partners with SFTP accounts, folder mappings for payment file routing, 2FA enforcement, and blockchain-anchored delivery receipts for regulatory compliance.
2. **Healthcare data interchange** -- Multi-tenant setup where each hospital system gets an isolated namespace, partner portal for self-service, and end-to-end integrity verification (HIPAA audit trail via blockchain).
3. **Supply chain EDI** -- Autonomous onboarding detects new trading partners automatically, learns their file patterns (`.edi`, `.x12`), and creates processing flows without admin intervention.
4. **SaaS platform integration** -- Use the v2 Transfer API from application code (Python/Java/Node.js) to send/receive files programmatically with webhook callbacks for async notification.
5. **Managed file transfer consolidation** -- Replace legacy FTP servers by onboarding existing partners, mapping their folder structures, and providing a partner portal with dashboard, SLA metrics, and SSH key rotation.
6. **Multi-region deployment** -- Use cluster management to federate multiple TranzFer instances across regions with cross-cluster communication mode, then manage topology via the Admin CLI.
7. **Compliance and audit** -- Track every file through its complete journey (upload, AI classification, screening, routing, delivery) and verify blockchain proofs for non-repudiation in audits.

---

## Environment Variables (Full Table)

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ hostname |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `RABBITMQ_USERNAME` | `guest` | RabbitMQ username |
| `RABBITMQ_PASSWORD` | `guest` | RabbitMQ password |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | Secret for signing JWTs (change in production!) |
| `JWT_EXPIRATION` | `900000` | JWT token lifetime in milliseconds (15 min) |
| `SFTP_CONTROL_URL` | `http://localhost:8081` | SFTP service control API URL |
| `FTP_CONTROL_URL` | `http://localhost:8082` | FTP service control API URL |
| `CONTROL_API_KEY` | `internal_control_secret` | Shared key for inter-service communication |
| `SFTP_HOME_BASE` | `/data/sftp` | Base directory for SFTP user home dirs |
| `FTP_HOME_BASE` | `/data/ftp` | Base directory for FTP user home dirs |
| `FTPWEB_HOME_BASE` | `/data/ftpweb` | Base directory for FTP-Web user home dirs |
| `CLUSTER_ID` | `default-cluster` | Identifier for this cluster |
| `CLUSTER_HOST` | `localhost` | Hostname of this instance |
| `CLUSTER_COMM_MODE` | `WITHIN_CLUSTER` | `WITHIN_CLUSTER` or `CROSS_CLUSTER` |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label (DEV, TEST, CERT, PROD) |
| `TRACK_ID_PREFIX` | `TRZ` | Prefix for generated track IDs |
| `FLOW_MAX_CONCURRENT` | `50` | Max concurrent flow executions |
| `FLOW_WORK_DIR` | `/tmp/mft-flow-work` | Temporary directory for flow processing |
| `PROXY_ENABLED` | `false` | Enable DMZ proxy for outbound connections |
| `PROXY_TYPE` | `HTTP` | Proxy type: `HTTP`, `SOCKS5`, `DMZ` |
| `PROXY_HOST` | `dmz-proxy` | Proxy hostname |
| `PROXY_PORT` | `8088` | Proxy port |
| `PROXY_NO_PROXY_HOSTS` | `localhost,127.0.0.1,postgres,rabbitmq` | Hosts that bypass the proxy |

---

## Cleanup

```bash
# Docker Compose
docker compose down -v   # stops containers AND removes volumes

# Standalone Docker
docker rm -f mft-onboarding-api mft-postgres mft-rabbitmq
docker volume prune -f

# From Source -- just Ctrl+C the running process
```

---

## Troubleshooting

### All platforms

| Symptom | Cause | Fix |
|---|---|---|
| `Connection refused` on port 8080 | Service not started or still starting | Wait 15-30 seconds for Spring Boot startup. Check `docker logs mft-onboarding-api`. |
| `401 Unauthorized` | Missing or expired JWT | Re-login with `POST /api/auth/login` and use the new `accessToken`. |
| `500 Internal Server Error` on startup | Database not ready | Ensure PostgreSQL is running and accessible. Flyway migrations run on first start. |
| `Account already exists` | Duplicate username | Each transfer account username must be unique per protocol. |

### Linux

| Symptom | Cause | Fix |
|---|---|---|
| `host.docker.internal` not resolving | Docker < 20.10 on Linux | Use `--add-host=host.docker.internal:host-gateway` or `--network host`. |
| Permission denied on `/data/sftp` | Volume mount permissions | Run `sudo chown -R 1000:1000 /data/sftp` or use Docker volumes. |

### macOS

| Symptom | Cause | Fix |
|---|---|---|
| Port 5432 already in use | Local PostgreSQL running | Stop it with `brew services stop postgresql` or use a different port mapping. |
| Docker Desktop memory | Default 2GB is too low | Increase to 4GB+ in Docker Desktop > Settings > Resources. |

### Windows

| Symptom | Cause | Fix |
|---|---|---|
| `curl` not found | PowerShell aliases `curl` to `Invoke-WebRequest` | Use `curl.exe` (the real curl ships with Windows 10+) or install via `winget install curl`. |
| Line continuation `\` not working | PowerShell uses backtick | Replace `\` with `` ` `` at end of lines, or put the entire command on one line. |
| WSL2 networking issues | Docker Desktop WSL backend | Access services via `localhost` from Windows. Inside WSL, use `localhost` as well. |

---

## What's Next

- **Config Service** (`docs/demos/CONFIG-SERVICE.md`) -- Create file processing flows, connectors, security profiles, scheduled tasks, delivery endpoints, and platform settings.
- **SFTP Service** -- Connect actual SFTP clients and transfer files that get routed through the folder mappings you configured here.
- **Admin UI** -- Graphical dashboard that calls these same API endpoints for point-and-click management.
- **Full Platform** -- Run `docker compose up -d` from the repo root to start all 20+ microservices together.

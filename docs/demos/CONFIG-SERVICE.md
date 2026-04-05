# Config Service -- Demo & Quick Start Guide

> Centralized configuration management for the TranzFer MFT platform: file flows, webhook connectors, security profiles, scheduling, delivery endpoints, AS2 partnerships, SLA agreements, encryption keys, platform settings, and server configuration.

---

## What This Service Does

- **File Flows** -- Define multi-step processing pipelines (decrypt, decompress, screen, rename, route) that are applied to files matching specific patterns. Track flow executions in real time.
- **Webhook Connectors** -- Configure Slack, Microsoft Teams, PagerDuty, ServiceNow, OpsGenie, and generic webhook integrations that fire on transfer events (failures, quarantines, anomalies).
- **Security Profiles** -- Define SSH cipher suites, MAC algorithms, key exchange algorithms, and TLS settings that are applied to SFTP/FTPS server instances.
- **Scheduled Tasks** -- Cron-driven automation for running flows, pulling/pushing files, executing scripts, and cleanup tasks.
- **Delivery Endpoints** -- Configure external delivery targets (SFTP, FTP, FTPS, HTTP, HTTPS, API) with per-endpoint authentication, TLS, proxy, and retry settings.
- **AS2/AS4 Partnerships** -- Configure B2B trading partner connections with signing, encryption, MDN receipts, and compression settings.
- **SLA Agreements** -- Define partner SLA contracts with expected delivery windows, volume thresholds, and breach detection.
- **Encryption Keys** -- Manage PGP and AES keys per transfer account for flow-based encryption/decryption steps.
- **Platform Settings** -- Database-backed configuration scoped by environment (DEV/TEST/CERT/PROD) and service name, with environment cloning.
- **Server Configuration** -- Dynamically configure SFTP/FTP/Gateway service instances and legacy fallback servers.
- **Activity Monitoring** -- Real-time snapshot of active transfers and recent platform events.

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
| Onboarding API | running on 8080 | Some demos (encryption keys) reference accounts created there |

> See `docs/PREREQUISITES.md` for installation instructions per OS.

---

## Install & Start

### Method 1: Docker (Any OS -- quickest)

```bash
# 1. Start infrastructure (if not already running)
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

# 2. Build the image (from repo root)
cd /path/to/file-transfer-platform
docker build -t tranzfer/config-service ./config-service

# 3. Run
docker run -d --name mft-config-service \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e RABBITMQ_HOST=host.docker.internal \
  -e JWT_SECRET=change_me_in_production_256bit_secret_key!! \
  -p 8084:8084 \
  tranzfer/config-service
```

> **Linux users**: Replace `host.docker.internal` with `172.17.0.1` or use `--network host`.

### Method 2: Docker Compose (recommended for demos)

From the repository root:

```bash
cd /path/to/file-transfer-platform

# Start postgres + rabbitmq + config-service (and onboarding-api for account refs)
docker compose up -d postgres rabbitmq config-service onboarding-api
```

This starts:
- PostgreSQL on port **5432**
- RabbitMQ on port **5672** (management UI on **15672**)
- Config Service on port **8084**
- Onboarding API on port **8080** (needed for encryption key demos)

### Method 3: From Source (Any OS)

```bash
cd /path/to/file-transfer-platform

# 1. Build the shared module first (required dependency)
mvn -pl shared install -DskipTests

# 2. Build and run config-service
mvn -pl config-service spring-boot:run \
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
curl -s http://localhost:8084/actuator/health | jq .
```

Expected response:

```json
{
  "status": "UP"
}
```

---

## Demo 1: Create a File Processing Flow (Multi-Step Pipeline)

File flows are the core of TranzFer's processing engine. Each flow is a named pipeline of ordered steps applied to files matching a filename pattern.

### Step 1: View available step types

```bash
curl -s http://localhost:8084/api/flows/step-types | jq .
```

Expected response:

```json
{
  "encryption": ["ENCRYPT_PGP", "DECRYPT_PGP", "ENCRYPT_AES", "DECRYPT_AES"],
  "compression": ["COMPRESS_GZIP", "DECOMPRESS_GZIP", "COMPRESS_ZIP", "DECOMPRESS_ZIP"],
  "transform": ["RENAME"],
  "security": ["SCREEN"],
  "scripting": ["EXECUTE_SCRIPT"],
  "delivery": ["MAILBOX", "FILE_DELIVERY"],
  "routing": ["ROUTE"]
}
```

### Step 2: Create a flow -- PGP decrypt, decompress, screen, route

```bash
curl -s -X POST http://localhost:8084/api/flows \
  -H "Content-Type: application/json" \
  -d '{
    "name": "partner-inbound-secure",
    "description": "Decrypt PGP, decompress GZIP, run sanctions screening, then route to destination",
    "filenamePattern": ".*\\.pgp\\.gz$",
    "sourcePath": "/inbox",
    "priority": 10,
    "steps": [
      { "type": "DECRYPT_PGP", "config": {"keyAlias": "partner-alpha-key"}, "order": 0 },
      { "type": "DECOMPRESS_GZIP", "config": {}, "order": 1 },
      { "type": "SCREEN", "config": {"provider": "OFAC"}, "order": 2 },
      { "type": "RENAME", "config": {"pattern": "${filename}_processed_${timestamp}"}, "order": 3 },
      { "type": "ROUTE", "config": {}, "order": 4 }
    ]
  }' | jq .
```

Expected response:

```json
{
  "id": "f1a2b3c4-d5e6-7890-abcd-ef1234567890",
  "name": "partner-inbound-secure",
  "description": "Decrypt PGP, decompress GZIP, run sanctions screening, then route to destination",
  "filenamePattern": ".*\\.pgp\\.gz$",
  "sourcePath": "/inbox",
  "steps": [
    { "type": "DECRYPT_PGP", "config": {"keyAlias": "partner-alpha-key"}, "order": 0 },
    { "type": "DECOMPRESS_GZIP", "config": {}, "order": 1 },
    { "type": "SCREEN", "config": {"provider": "OFAC"}, "order": 2 },
    { "type": "RENAME", "config": {"pattern": "${filename}_processed_${timestamp}"}, "order": 3 },
    { "type": "ROUTE", "config": {}, "order": 4 }
  ],
  "priority": 10,
  "active": true,
  "createdAt": "2026-04-05T10:00:00Z",
  "updatedAt": "2026-04-05T10:00:00Z"
}
```

### Step 3: Create a second flow -- compress and deliver via HTTP

```bash
curl -s -X POST http://localhost:8084/api/flows \
  -H "Content-Type: application/json" \
  -d '{
    "name": "outbound-api-delivery",
    "description": "Compress to GZIP and deliver via HTTP POST to partner API",
    "filenamePattern": ".*\\.csv$",
    "sourcePath": "/outbox",
    "priority": 20,
    "steps": [
      { "type": "COMPRESS_GZIP", "config": {}, "order": 0 },
      { "type": "FILE_DELIVERY", "config": {"endpointName": "partner-api"}, "order": 1 }
    ]
  }' | jq .
```

### Step 4: List all active flows

```bash
curl -s http://localhost:8084/api/flows | jq .
```

Expected response:

```json
[
  {
    "id": "f1a2b3c4-...",
    "name": "partner-inbound-secure",
    "description": "Decrypt PGP, decompress GZIP, run sanctions screening, then route to destination",
    "filenamePattern": ".*\\.pgp\\.gz$",
    "priority": 10,
    "active": true,
    "steps": [
      { "type": "DECRYPT_PGP", "config": {"keyAlias": "partner-alpha-key"}, "order": 0 },
      { "type": "DECOMPRESS_GZIP", "config": {}, "order": 1 },
      { "type": "SCREEN", "config": {"provider": "OFAC"}, "order": 2 },
      { "type": "RENAME", "config": {"pattern": "${filename}_processed_${timestamp}"}, "order": 3 },
      { "type": "ROUTE", "config": {}, "order": 4 }
    ]
  },
  {
    "id": "a2b3c4d5-...",
    "name": "outbound-api-delivery",
    "priority": 20,
    "active": true,
    "steps": [
      { "type": "COMPRESS_GZIP", "config": {}, "order": 0 },
      { "type": "FILE_DELIVERY", "config": {"endpointName": "partner-api"}, "order": 1 }
    ]
  }
]
```

### Step 5: Get a single flow by ID

```bash
curl -s http://localhost:8084/api/flows/<flow-id> | jq .
```

### Step 6: Update a flow

```bash
curl -s -X PUT http://localhost:8084/api/flows/<flow-id> \
  -H "Content-Type: application/json" \
  -d '{
    "name": "partner-inbound-secure",
    "description": "Updated: added AES encryption step before delivery",
    "filenamePattern": ".*\\.pgp\\.gz$",
    "sourcePath": "/inbox",
    "priority": 10,
    "active": true,
    "steps": [
      { "type": "DECRYPT_PGP", "config": {"keyAlias": "partner-alpha-key"}, "order": 0 },
      { "type": "DECOMPRESS_GZIP", "config": {}, "order": 1 },
      { "type": "SCREEN", "config": {"provider": "OFAC"}, "order": 2 },
      { "type": "ENCRYPT_AES", "config": {"keySize": "256"}, "order": 3 },
      { "type": "ROUTE", "config": {}, "order": 4 }
    ]
  }' | jq .
```

### Step 7: Toggle a flow on/off

```bash
curl -s -X PATCH http://localhost:8084/api/flows/<flow-id>/toggle | jq .
```

### Step 8: Search flow executions

```bash
# All executions (paginated)
curl -s "http://localhost:8084/api/flows/executions?page=0&size=10" | jq .

# Filter by status
curl -s "http://localhost:8084/api/flows/executions?status=FAILED&page=0&size=10" | jq .

# Filter by track ID
curl -s "http://localhost:8084/api/flows/executions?trackId=TRZ-20260405-ABC123" | jq .

# Get a specific execution
curl -s http://localhost:8084/api/flows/executions/TRZ-20260405-ABC123 | jq .
```

### Step 9: Delete (deactivate) a flow

```bash
curl -s -X DELETE http://localhost:8084/api/flows/<flow-id>
# Returns 204 No Content (flow is soft-deleted / set to inactive)
```

---

## Demo 2: Create Webhook Connectors (Slack, PagerDuty, Teams)

Webhook connectors send notifications to external systems when platform events occur (transfer failures, quarantines, anomalies).

### Step 1: View available connector types

```bash
curl -s http://localhost:8084/api/connectors/types | jq .
```

Expected response:

```json
{
  "types": [
    { "id": "SERVICENOW", "name": "ServiceNow", "description": "Create incidents automatically" },
    { "id": "PAGERDUTY", "name": "PagerDuty", "description": "Trigger PagerDuty events" },
    { "id": "SLACK", "name": "Slack", "description": "Post to Slack channel" },
    { "id": "TEAMS", "name": "Microsoft Teams", "description": "Post to Teams channel" },
    { "id": "OPSGENIE", "name": "OpsGenie", "description": "Create OpsGenie alerts" },
    { "id": "WEBHOOK", "name": "Generic Webhook", "description": "POST JSON to any URL" }
  ]
}
```

### Step 2: Create a Slack connector

```bash
curl -s -X POST http://localhost:8084/api/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "ops-slack-alerts",
    "type": "SLACK",
    "url": "https://hooks.example.com/services/T00000000/B00000000/your-webhook-token-here",
    "triggerEvents": ["TRANSFER_FAILED", "AI_BLOCKED", "INTEGRITY_FAIL", "QUARANTINE"],
    "minSeverity": "HIGH",
    "customHeaders": {
      "X-Source": "tranzfer-mft"
    }
  }' | jq .
```

Expected response:

```json
{
  "id": "c1d2e3f4-a5b6-7890-abcd-ef1234567890",
  "name": "ops-slack-alerts",
  "type": "SLACK",
  "url": "https://hooks.example.com/services/T00000000/B00000000/your-webhook-token-here",
  "triggerEvents": ["TRANSFER_FAILED", "AI_BLOCKED", "INTEGRITY_FAIL", "QUARANTINE"],
  "minSeverity": "HIGH",
  "customHeaders": { "X-Source": "tranzfer-mft" },
  "active": true,
  "lastTriggered": null,
  "totalNotifications": 0,
  "createdAt": "2026-04-05T10:15:00Z"
}
```

### Step 3: Create a PagerDuty connector

```bash
curl -s -X POST http://localhost:8084/api/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "critical-pagerduty",
    "type": "PAGERDUTY",
    "url": "https://events.pagerduty.com/v2/enqueue",
    "authToken": "your-pagerduty-integration-key",
    "triggerEvents": ["TRANSFER_FAILED", "LICENSE_EXPIRED"],
    "minSeverity": "CRITICAL"
  }' | jq .
```

### Step 4: Create a Microsoft Teams connector

```bash
curl -s -X POST http://localhost:8084/api/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "teams-mft-channel",
    "type": "TEAMS",
    "url": "https://outlook.office.com/webhook/YOUR-WEBHOOK-URL",
    "triggerEvents": ["TRANSFER_FAILED", "FLOW_FAIL", "ANOMALY_DETECTED"],
    "minSeverity": "MEDIUM"
  }' | jq .
```

### Step 5: Create a ServiceNow connector

```bash
curl -s -X POST http://localhost:8084/api/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "snow-incidents",
    "type": "SERVICENOW",
    "url": "https://your-instance.service-now.com/api/now/table/incident",
    "username": "api_user",
    "password": "api_password",
    "triggerEvents": ["TRANSFER_FAILED", "INTEGRITY_FAIL"],
    "minSeverity": "HIGH",
    "snowInstanceId": "your-instance",
    "snowAssignmentGroup": "MFT Operations",
    "snowCategory": "File Transfer"
  }' | jq .
```

### Step 6: List all active connectors

```bash
curl -s http://localhost:8084/api/connectors | jq .
```

### Step 7: Test a connector

```bash
curl -s -X POST http://localhost:8084/api/connectors/<connector-id>/test | jq .
```

Expected response (success):

```json
{
  "status": "OK",
  "httpCode": "200"
}
```

Expected response (failure):

```json
{
  "status": "FAILED",
  "error": "Connection timed out"
}
```

### Step 8: Update a connector

```bash
curl -s -X PUT http://localhost:8084/api/connectors/<connector-id> \
  -H "Content-Type: application/json" \
  -d '{
    "name": "ops-slack-alerts",
    "type": "SLACK",
    "url": "https://hooks.slack.com/services/T00000000/B00000000/NEW-TOKEN",
    "triggerEvents": ["TRANSFER_FAILED", "AI_BLOCKED", "INTEGRITY_FAIL", "QUARANTINE", "ANOMALY_DETECTED"],
    "minSeverity": "MEDIUM"
  }' | jq .
```

### Step 9: Delete (deactivate) a connector

```bash
curl -s -X DELETE http://localhost:8084/api/connectors/<connector-id>
# Returns 204 No Content
```

---

## Demo 3: Create Security Profiles (SSH and TLS)

Security profiles define cryptographic settings applied to SFTP and FTPS server instances. You can enforce FIPS 140-2 compliance, disable weak ciphers, or create custom profiles per partner.

### Step 1: Create a FIPS 140-2 compliant SSH profile

```bash
curl -s -X POST http://localhost:8084/api/security-profiles \
  -H "Content-Type: application/json" \
  -d '{
    "name": "FIPS-140-2",
    "description": "FIPS 140-2 compliant SSH configuration for regulated partners",
    "type": "SSH",
    "sshCiphers": [
      "aes256-ctr",
      "aes256-gcm@openssh.com",
      "aes128-gcm@openssh.com"
    ],
    "sshMacs": [
      "hmac-sha2-256-etm@openssh.com",
      "hmac-sha2-512-etm@openssh.com"
    ],
    "kexAlgorithms": [
      "ecdh-sha2-nistp384",
      "ecdh-sha2-nistp521",
      "diffie-hellman-group16-sha512"
    ],
    "hostKeyAlgorithms": [
      "ecdsa-sha2-nistp384",
      "rsa-sha2-512",
      "rsa-sha2-256"
    ]
  }' | jq .
```

Expected response:

```json
{
  "id": "s1e2c3u4-r5i6-7890-abcd-ef1234567890",
  "name": "FIPS-140-2",
  "description": "FIPS 140-2 compliant SSH configuration for regulated partners",
  "type": "SSH",
  "sshCiphers": [
    "aes256-ctr",
    "aes256-gcm@openssh.com",
    "aes128-gcm@openssh.com"
  ],
  "sshMacs": [
    "hmac-sha2-256-etm@openssh.com",
    "hmac-sha2-512-etm@openssh.com"
  ],
  "kexAlgorithms": [
    "ecdh-sha2-nistp384",
    "ecdh-sha2-nistp521",
    "diffie-hellman-group16-sha512"
  ],
  "hostKeyAlgorithms": [
    "ecdsa-sha2-nistp384",
    "rsa-sha2-512",
    "rsa-sha2-256"
  ],
  "tlsMinVersion": null,
  "tlsCiphers": null,
  "clientAuthRequired": false,
  "active": true,
  "createdAt": "2026-04-05T10:20:00Z",
  "updatedAt": "2026-04-05T10:20:00Z"
}
```

### Step 2: Create a TLS profile for FTPS

```bash
curl -s -X POST http://localhost:8084/api/security-profiles \
  -H "Content-Type: application/json" \
  -d '{
    "name": "TLS-Strict",
    "description": "Strict TLS 1.3 profile for FTPS connections",
    "type": "TLS",
    "tlsMinVersion": "TLSv1.3",
    "tlsCiphers": [
      "TLS_AES_256_GCM_SHA384",
      "TLS_AES_128_GCM_SHA256",
      "TLS_CHACHA20_POLY1305_SHA256"
    ],
    "clientAuthRequired": true
  }' | jq .
```

### Step 3: List all active security profiles

```bash
curl -s http://localhost:8084/api/security-profiles | jq .
```

### Step 4: Get a specific profile

```bash
curl -s http://localhost:8084/api/security-profiles/<profile-id> | jq .
```

### Step 5: Update a profile

```bash
curl -s -X PUT http://localhost:8084/api/security-profiles/<profile-id> \
  -H "Content-Type: application/json" \
  -d '{
    "name": "FIPS-140-2",
    "description": "Updated: added chacha20 for modern clients",
    "type": "SSH",
    "sshCiphers": [
      "aes256-ctr",
      "aes256-gcm@openssh.com",
      "aes128-gcm@openssh.com",
      "chacha20-poly1305@openssh.com"
    ],
    "sshMacs": [
      "hmac-sha2-256-etm@openssh.com",
      "hmac-sha2-512-etm@openssh.com"
    ],
    "kexAlgorithms": [
      "ecdh-sha2-nistp384",
      "ecdh-sha2-nistp521",
      "diffie-hellman-group16-sha512",
      "curve25519-sha256"
    ],
    "hostKeyAlgorithms": [
      "ecdsa-sha2-nistp384",
      "rsa-sha2-512",
      "ssh-ed25519"
    ],
    "active": true
  }' | jq .
```

### Step 6: Delete (deactivate) a profile

```bash
curl -s -X DELETE http://localhost:8084/api/security-profiles/<profile-id>
# Returns 204 No Content
```

---

## Demo 4: Schedule Tasks, Configure Delivery Endpoints, and SLA

### Scheduled Tasks

Automate recurring operations with cron expressions.

#### Create a scheduled task -- daily flow execution

```bash
curl -s -X POST http://localhost:8084/api/scheduler \
  -H "Content-Type: application/json" \
  -d '{
    "name": "nightly-partner-pull",
    "description": "Pull files from partner SFTP every night at 2:00 AM UTC",
    "cronExpression": "0 0 2 * * *",
    "timezone": "UTC",
    "taskType": "PULL_FILES",
    "referenceId": "partner_alpha",
    "config": {
      "remotePath": "/outgoing",
      "localPath": "/inbox",
      "deleteAfterPull": "true"
    }
  }' | jq .
```

Expected response:

```json
{
  "id": "t1a2s3k4-i5d6-7890-abcd-ef1234567890",
  "name": "nightly-partner-pull",
  "description": "Pull files from partner SFTP every night at 2:00 AM UTC",
  "cronExpression": "0 0 2 * * *",
  "timezone": "UTC",
  "taskType": "PULL_FILES",
  "referenceId": "partner_alpha",
  "config": {
    "remotePath": "/outgoing",
    "localPath": "/inbox",
    "deleteAfterPull": "true"
  },
  "enabled": true,
  "lastRun": null,
  "nextRun": null,
  "lastStatus": null,
  "lastError": null,
  "totalRuns": 0,
  "failedRuns": 0,
  "createdAt": "2026-04-05T10:25:00Z"
}
```

#### Create a cleanup task

```bash
curl -s -X POST http://localhost:8084/api/scheduler \
  -H "Content-Type: application/json" \
  -d '{
    "name": "weekly-cleanup",
    "description": "Clean up processed files older than 30 days every Sunday at 3:00 AM",
    "cronExpression": "0 0 3 * * SUN",
    "timezone": "UTC",
    "taskType": "CLEANUP",
    "config": {
      "retentionDays": "30",
      "paths": "/data/sftp/*/processed"
    }
  }' | jq .
```

#### Create a flow execution task

```bash
curl -s -X POST http://localhost:8084/api/scheduler \
  -H "Content-Type: application/json" \
  -d '{
    "name": "hourly-report-flow",
    "description": "Run the report-generation flow every hour",
    "cronExpression": "0 0 * * * *",
    "timezone": "America/New_York",
    "taskType": "RUN_FLOW",
    "referenceId": "<flow-id>"
  }' | jq .
```

#### List enabled scheduled tasks

```bash
curl -s http://localhost:8084/api/scheduler | jq .
```

#### List all tasks (including disabled)

```bash
curl -s http://localhost:8084/api/scheduler/all | jq .
```

#### Toggle a task on/off

```bash
curl -s -X PATCH http://localhost:8084/api/scheduler/<task-id>/toggle | jq .
```

#### Delete a task

```bash
curl -s -X DELETE http://localhost:8084/api/scheduler/<task-id>
# Returns 204 No Content
```

---

### Delivery Endpoints

Configure external systems where files are delivered by flow steps.

#### View supported protocols and auth types

```bash
# Protocols
curl -s http://localhost:8084/api/delivery-endpoints/protocols | jq .

# Auth types
curl -s http://localhost:8084/api/delivery-endpoints/auth-types | jq .

# Proxy types
curl -s http://localhost:8084/api/delivery-endpoints/proxy-types | jq .
```

Expected protocols response:

```json
["SFTP", "FTP", "FTPS", "HTTP", "HTTPS", "API"]
```

Expected proxy types response:

```json
[
  { "value": "DMZ", "label": "DMZ Proxy", "description": "Route through the platform DMZ proxy (auto-managed TCP relay)" },
  { "value": "HTTP", "label": "HTTP Proxy", "description": "Standard HTTP forward proxy" },
  { "value": "SOCKS5", "label": "SOCKS5 Proxy", "description": "SOCKS5 proxy for TCP-level tunneling" }
]
```

#### Create an SFTP delivery endpoint

```bash
curl -s -X POST http://localhost:8084/api/delivery-endpoints \
  -H "Content-Type: application/json" \
  -d '{
    "name": "partner-beta-sftp",
    "description": "SFTP delivery to Partner Beta production server",
    "protocol": "SFTP",
    "host": "sftp.partner-beta.com",
    "port": 22,
    "basePath": "/incoming/production",
    "authType": "SSH_KEY",
    "username": "tranzfer_delivery",
    "sshPrivateKey": "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----",
    "tlsEnabled": false,
    "connectionTimeoutMs": 30000,
    "readTimeoutMs": 60000,
    "retryCount": 3,
    "retryDelayMs": 5000,
    "tags": "production,partner-beta"
  }' | jq .
```

Expected response:

```json
{
  "id": "d1e2l3i4-v5e6-7890-abcd-ef1234567890",
  "name": "partner-beta-sftp",
  "description": "SFTP delivery to Partner Beta production server",
  "protocol": "SFTP",
  "host": "sftp.partner-beta.com",
  "port": 22,
  "basePath": "/incoming/production",
  "authType": "SSH_KEY",
  "username": "tranzfer_delivery",
  "sshPrivateKey": "ENC[...]",
  "tlsEnabled": false,
  "proxyEnabled": false,
  "connectionTimeoutMs": 30000,
  "readTimeoutMs": 60000,
  "retryCount": 3,
  "retryDelayMs": 5000,
  "tags": "production,partner-beta",
  "active": true,
  "createdAt": "2026-04-05T10:30:00Z",
  "updatedAt": "2026-04-05T10:30:00Z"
}
```

> Note: Secrets (`sshPrivateKey`, `encryptedPassword`, `bearerToken`, `apiKeyValue`) are encrypted at rest before being stored in the database.

#### Create an HTTPS API delivery endpoint (with DMZ proxy)

```bash
curl -s -X POST http://localhost:8084/api/delivery-endpoints \
  -H "Content-Type: application/json" \
  -d '{
    "name": "partner-api",
    "description": "HTTPS POST delivery to Partner API gateway",
    "protocol": "HTTPS",
    "host": "api.partner-corp.com",
    "port": 443,
    "basePath": "/v2/files/upload",
    "authType": "BEARER_TOKEN",
    "bearerToken": "eyJhbGciOiJSUzI1NiIs...",
    "httpMethod": "POST",
    "contentType": "application/octet-stream",
    "httpHeaders": {
      "X-Partner-ID": "tranzfer-mft",
      "X-Correlation-ID": "${trackId}"
    },
    "tlsEnabled": true,
    "tlsTrustAll": false,
    "proxyEnabled": true,
    "proxyType": "DMZ",
    "proxyHost": "dmz-proxy",
    "proxyPort": 8088,
    "retryCount": 5,
    "retryDelayMs": 10000,
    "tags": "api,partner-corp,dmz"
  }' | jq .
```

#### List all delivery endpoints

```bash
# All active
curl -s http://localhost:8084/api/delivery-endpoints | jq .

# Filter by protocol
curl -s "http://localhost:8084/api/delivery-endpoints?protocol=SFTP" | jq .

# Filter by tag
curl -s "http://localhost:8084/api/delivery-endpoints?tag=production" | jq .
```

#### Get endpoint summary

```bash
curl -s http://localhost:8084/api/delivery-endpoints/summary | jq .
```

Expected response:

```json
{
  "totalActive": 2,
  "byProtocol": {
    "SFTP": 1,
    "HTTPS": 1
  }
}
```

#### Toggle an endpoint on/off

```bash
curl -s -X PATCH http://localhost:8084/api/delivery-endpoints/<endpoint-id>/toggle | jq .
```

---

### SLA Agreements

Define formal partner SLA contracts with breach detection.

#### Create an SLA agreement

```bash
curl -s -X POST http://localhost:8084/api/sla \
  -H "Content-Type: application/json" \
  -d '{
    "name": "partner-alpha-daily-sla",
    "description": "Partner Alpha must deliver files between 06:00-09:00 UTC on weekdays",
    "expectedDeliveryStartHour": 6,
    "expectedDeliveryEndHour": 9,
    "expectedDays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
    "minFilesPerWindow": 1,
    "maxErrorRate": 0.05,
    "gracePeriodMinutes": 30,
    "breachAction": "ALERT_AND_ESCALATE"
  }' | jq .
```

Expected response:

```json
{
  "id": "s1l2a3i4-d5e6-7890-abcd-ef1234567890",
  "name": "partner-alpha-daily-sla",
  "description": "Partner Alpha must deliver files between 06:00-09:00 UTC on weekdays",
  "expectedDeliveryStartHour": 6,
  "expectedDeliveryEndHour": 9,
  "expectedDays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
  "minFilesPerWindow": 1,
  "maxErrorRate": 0.05,
  "gracePeriodMinutes": 30,
  "breachAction": "ALERT_AND_ESCALATE",
  "active": true,
  "totalBreaches": 0,
  "lastBreachAt": null,
  "createdAt": "2026-04-05T10:35:00Z"
}
```

#### List active SLA agreements

```bash
curl -s http://localhost:8084/api/sla | jq .
```

#### Check for SLA breaches

```bash
curl -s http://localhost:8084/api/sla/breaches | jq .
```

Expected response (no breaches):

```json
[]
```

---

## Demo 5: Platform Settings and Environment Cloning

Platform settings are database-backed configuration values scoped by environment and service name. They survive restarts and can be cloned across environments.

### Step 1: Create platform settings

```bash
# Global setting -- applies to all services in PROD
curl -s -X POST http://localhost:8084/api/platform-settings \
  -H "Content-Type: application/json" \
  -d '{
    "settingKey": "security.password-min-length",
    "settingValue": "12",
    "environment": "PROD",
    "serviceName": "GLOBAL",
    "dataType": "INTEGER",
    "description": "Minimum password length for all services",
    "category": "Security",
    "sensitive": false
  }' | jq .
```

Expected response:

```json
{
  "id": "p1s2e3t4-t5i6-7890-abcd-ef1234567890",
  "settingKey": "security.password-min-length",
  "settingValue": "12",
  "environment": "PROD",
  "serviceName": "GLOBAL",
  "dataType": "INTEGER",
  "description": "Minimum password length for all services",
  "category": "Security",
  "sensitive": false,
  "active": true,
  "createdAt": "2026-04-05T10:40:00Z",
  "updatedAt": "2026-04-05T10:40:00Z"
}
```

```bash
# Service-specific setting for SFTP in PROD
curl -s -X POST http://localhost:8084/api/platform-settings \
  -H "Content-Type: application/json" \
  -d '{
    "settingKey": "sftp.port",
    "settingValue": "2222",
    "environment": "PROD",
    "serviceName": "SFTP",
    "dataType": "INTEGER",
    "description": "SFTP listening port",
    "category": "Network"
  }' | jq .

# Same key, different value for TEST
curl -s -X POST http://localhost:8084/api/platform-settings \
  -H "Content-Type: application/json" \
  -d '{
    "settingKey": "sftp.port",
    "settingValue": "22222",
    "environment": "TEST",
    "serviceName": "SFTP",
    "dataType": "INTEGER",
    "description": "SFTP listening port (test uses high port)",
    "category": "Network"
  }' | jq .

# Sensitive setting (masked in UI)
curl -s -X POST http://localhost:8084/api/platform-settings \
  -H "Content-Type: application/json" \
  -d '{
    "settingKey": "security.api-encryption-key",
    "settingValue": "AES256-KEY-DO-NOT-SHARE",
    "environment": "PROD",
    "serviceName": "GLOBAL",
    "dataType": "STRING",
    "description": "Master API encryption key",
    "category": "Security",
    "sensitive": true
  }' | jq .
```

### Step 2: Query settings with filters

```bash
# All settings
curl -s http://localhost:8084/api/platform-settings | jq .

# Filter by environment
curl -s "http://localhost:8084/api/platform-settings?env=PROD" | jq .

# Filter by environment + service
curl -s "http://localhost:8084/api/platform-settings?env=PROD&service=SFTP" | jq .

# Filter by category
curl -s "http://localhost:8084/api/platform-settings?category=Security" | jq .

# Filter by service name only
curl -s "http://localhost:8084/api/platform-settings?service=GLOBAL" | jq .
```

### Step 3: View available metadata

```bash
# List distinct environments in use
curl -s http://localhost:8084/api/platform-settings/environments | jq .
```

Expected response:

```json
["PROD", "TEST"]
```

```bash
# List distinct service names
curl -s http://localhost:8084/api/platform-settings/services | jq .
```

Expected response:

```json
["GLOBAL", "SFTP"]
```

```bash
# List distinct categories
curl -s http://localhost:8084/api/platform-settings/categories | jq .
```

Expected response:

```json
["Security", "Network"]
```

### Step 4: Update just the value of a setting

```bash
curl -s -X PATCH http://localhost:8084/api/platform-settings/<setting-id>/value \
  -H "Content-Type: application/json" \
  -d '{"value": "16"}' | jq .
```

### Step 5: Clone an environment

Copy all settings from one environment to another. This is how you promote configuration from TEST to CERT, or seed a new environment.

```bash
curl -s -X POST "http://localhost:8084/api/platform-settings/clone?source=PROD&target=CERT" | jq .
```

Expected response (list of cloned settings):

```json
[
  {
    "id": "n1e2w3i4-d5a6-7890-abcd-ef1234567890",
    "settingKey": "security.password-min-length",
    "settingValue": "12",
    "environment": "CERT",
    "serviceName": "GLOBAL",
    "dataType": "INTEGER",
    "description": "Minimum password length for all services",
    "category": "Security",
    "active": true
  },
  {
    "id": "n2e3w4i5-d6a7-8901-bcde-f12345678901",
    "settingKey": "sftp.port",
    "settingValue": "2222",
    "environment": "CERT",
    "serviceName": "SFTP",
    "dataType": "INTEGER",
    "description": "SFTP listening port",
    "category": "Network",
    "active": true
  }
]
```

### Step 6: Delete a setting

```bash
curl -s -X DELETE http://localhost:8084/api/platform-settings/<setting-id>
# Returns 204 No Content
```

---

## Demo 6: AS2/AS4 Partnerships, External Destinations, and Server Config

### AS2/AS4 Trading Partner Partnerships

Configure B2B message exchange partnerships for EDI, healthcare, and supply chain workflows.

```bash
curl -s -X POST http://localhost:8084/api/as2-partnerships \
  -H "Content-Type: application/json" \
  -d '{
    "partnerName": "Globex Corporation",
    "partnerAs2Id": "GLOBEX-AS2",
    "ourAs2Id": "TRANZFER-AS2",
    "endpointUrl": "https://as2.globex.com/receive",
    "signingAlgorithm": "SHA-256",
    "encryptionAlgorithm": "AES256",
    "mdnRequired": true,
    "mdnAsync": false,
    "compressionEnabled": true,
    "protocol": "AS2"
  }' | jq .
```

Expected response:

```json
{
  "id": "a1s2p3a4-r5t6-7890-abcd-ef1234567890",
  "partnerName": "Globex Corporation",
  "partnerAs2Id": "GLOBEX-AS2",
  "ourAs2Id": "TRANZFER-AS2",
  "endpointUrl": "https://as2.globex.com/receive",
  "signingAlgorithm": "SHA-256",
  "encryptionAlgorithm": "AES256",
  "mdnRequired": true,
  "mdnAsync": false,
  "mdnUrl": null,
  "compressionEnabled": true,
  "protocol": "AS2",
  "active": true
}
```

```bash
# List partnerships (optionally filter by protocol)
curl -s http://localhost:8084/api/as2-partnerships | jq .
curl -s "http://localhost:8084/api/as2-partnerships?protocol=AS2" | jq .

# Toggle partnership active/inactive
curl -s -X PATCH http://localhost:8084/api/as2-partnerships/<id>/toggle | jq .
```

### External Destinations (Kafka, SFTP, FTP forwarding)

```bash
curl -s -X POST http://localhost:8084/api/external-destinations \
  -H "Content-Type: application/json" \
  -d '{
    "name": "analytics-kafka",
    "type": "KAFKA",
    "host": "kafka-broker-1.internal:9092",
    "config": {
      "topic": "mft.file-events",
      "keySerializer": "string",
      "valueSerializer": "json"
    }
  }' | jq .
```

```bash
# List all (or filter by type)
curl -s http://localhost:8084/api/external-destinations | jq .
curl -s "http://localhost:8084/api/external-destinations?type=KAFKA" | jq .
```

### Server Configuration

```bash
# Create a server config for an SFTP instance
curl -s -X POST http://localhost:8084/api/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "sftp-prod-1",
    "type": "SFTP",
    "host": "sftp-1.internal",
    "controlPort": 8081,
    "dataPort": 2222,
    "maxConnections": 200,
    "securityProfileId": "<fips-profile-id>"
  }' | jq .

# List servers (optionally filter by type)
curl -s http://localhost:8084/api/servers | jq .
curl -s "http://localhost:8084/api/servers?type=SFTP" | jq .

# Enable/disable a server
curl -s -X PATCH "http://localhost:8084/api/servers/<id>/active?value=false" | jq .
```

### Legacy Server Configuration

```bash
curl -s -X POST http://localhost:8084/api/legacy-servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "legacy-ftp-fallback",
    "protocol": "FTP",
    "host": "ftp.legacy.internal",
    "port": 21,
    "description": "Fallback FTP for unknown users"
  }' | jq .

curl -s http://localhost:8084/api/legacy-servers | jq .
curl -s "http://localhost:8084/api/legacy-servers?protocol=SFTP" | jq .
```

### Encryption Keys

```bash
# List keys for an account (requires a valid account ID from onboarding-api)
curl -s "http://localhost:8084/api/encryption-keys?accountId=<account-id>" | jq .

# Create a PGP key for an account
curl -s -X POST http://localhost:8084/api/encryption-keys \
  -H "Content-Type: application/json" \
  -d '{
    "account": {"id": "<account-id>"},
    "keyType": "PGP",
    "keyAlias": "partner-alpha-pgp",
    "publicKeyArmor": "-----BEGIN PGP PUBLIC KEY BLOCK-----\n...\n-----END PGP PUBLIC KEY BLOCK-----"
  }' | jq .
```

### Activity Monitoring

```bash
# Platform activity snapshot
curl -s http://localhost:8084/api/activity/snapshot | jq .

# Active transfers in progress
curl -s http://localhost:8084/api/activity/transfers | jq .

# Recent events (configurable limit)
curl -s "http://localhost:8084/api/activity/events?limit=20" | jq .
```

---

## Demo 7: Integration Patterns -- Python, Java, Node.js

### Python

```python
import requests

BASE = "http://localhost:8084"

# 1. Create a file flow
flow = requests.post(f"{BASE}/api/flows", json={
    "name": "python-demo-flow",
    "description": "Created from Python",
    "filenamePattern": ".*\\.dat$",
    "sourcePath": "/inbox",
    "priority": 50,
    "steps": [
        {"type": "DECOMPRESS_GZIP", "config": {}, "order": 0},
        {"type": "SCREEN", "config": {}, "order": 1},
        {"type": "ROUTE", "config": {}, "order": 2}
    ]
}).json()
flow_id = flow["id"]
print(f"Created flow: {flow['name']} (id: {flow_id})")

# 2. Create a Slack connector
connector = requests.post(f"{BASE}/api/connectors", json={
    "name": "python-slack",
    "type": "SLACK",
    "url": "https://hooks.slack.com/services/YOUR/WEBHOOK/URL",
    "triggerEvents": ["TRANSFER_FAILED"],
    "minSeverity": "HIGH"
}).json()
print(f"Created connector: {connector['name']} (id: {connector['id']})")

# 3. Create a security profile
profile = requests.post(f"{BASE}/api/security-profiles", json={
    "name": "python-modern-ssh",
    "type": "SSH",
    "sshCiphers": ["aes256-gcm@openssh.com", "chacha20-poly1305@openssh.com"],
    "sshMacs": ["hmac-sha2-256-etm@openssh.com"],
    "kexAlgorithms": ["curve25519-sha256"],
    "hostKeyAlgorithms": ["ssh-ed25519"]
}).json()
print(f"Created profile: {profile['name']} (id: {profile['id']})")

# 4. Create a platform setting
setting = requests.post(f"{BASE}/api/platform-settings", json={
    "settingKey": "python.demo.enabled",
    "settingValue": "true",
    "environment": "TEST",
    "serviceName": "GLOBAL",
    "dataType": "BOOLEAN",
    "description": "Created from Python",
    "category": "Demo"
}).json()
print(f"Created setting: {setting['settingKey']} = {setting['settingValue']}")
```

### Java (HttpClient -- JDK 11+)

```java
import java.net.URI;
import java.net.http.*;

public class ConfigServiceDemo {
    static final String BASE = "http://localhost:8084";
    static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        // Create a file flow
        var flowReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/flows"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""
                {
                  "name": "java-demo-flow",
                  "description": "Created from Java",
                  "filenamePattern": ".*\\\\.xml$",
                  "sourcePath": "/inbox",
                  "priority": 50,
                  "steps": [
                    {"type": "SCREEN", "config": {}, "order": 0},
                    {"type": "ROUTE", "config": {}, "order": 1}
                  ]
                }
                """))
            .build();
        var resp = client.send(flowReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Flow created: " + resp.body());

        // Create a connector
        var connReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/api/connectors"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""
                {
                  "name": "java-webhook",
                  "type": "WEBHOOK",
                  "url": "https://httpbin.org/post",
                  "triggerEvents": ["TRANSFER_FAILED"],
                  "minSeverity": "HIGH"
                }
                """))
            .build();
        resp = client.send(connReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Connector created: " + resp.body());
    }
}
```

### Node.js (fetch -- Node 18+)

```javascript
const BASE = "http://localhost:8084";

async function demo() {
  // 1. Create a file flow
  const flowResp = await fetch(`${BASE}/api/flows`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: "node-demo-flow",
      description: "Created from Node.js",
      filenamePattern: ".*\\.json$",
      sourcePath: "/inbox",
      priority: 50,
      steps: [
        { type: "SCREEN", config: {}, order: 0 },
        { type: "COMPRESS_GZIP", config: {}, order: 1 },
        { type: "ROUTE", config: {}, order: 2 },
      ],
    }),
  });
  const flow = await flowResp.json();
  console.log(`Created flow: ${flow.name} (id: ${flow.id})`);

  // 2. Create a security profile
  const profileResp = await fetch(`${BASE}/api/security-profiles`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: "node-modern-profile",
      type: "SSH",
      sshCiphers: ["aes256-gcm@openssh.com"],
      sshMacs: ["hmac-sha2-256-etm@openssh.com"],
      kexAlgorithms: ["curve25519-sha256"],
      hostKeyAlgorithms: ["ssh-ed25519"],
    }),
  });
  const profile = await profileResp.json();
  console.log(`Created profile: ${profile.name} (id: ${profile.id})`);

  // 3. Schedule a task
  const taskResp = await fetch(`${BASE}/api/scheduler`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: "node-hourly-check",
      description: "Hourly flow execution from Node.js",
      cronExpression: "0 0 * * * *",
      timezone: "UTC",
      taskType: "RUN_FLOW",
      referenceId: flow.id,
    }),
  });
  const task = await taskResp.json();
  console.log(`Scheduled task: ${task.name} (cron: ${task.cronExpression})`);

  // 4. Clone PROD settings to STAGING
  const cloneResp = await fetch(
    `${BASE}/api/platform-settings/clone?source=PROD&target=STAGING`,
    { method: "POST" }
  );
  const cloned = await cloneResp.json();
  console.log(`Cloned ${cloned.length} settings from PROD to STAGING`);
}

demo().catch(console.error);
```

---

## Use Cases

1. **Multi-step secure file pipeline** -- Create a flow that decrypts PGP-encrypted files, decompresses them, runs sanctions screening, and routes the clean files to a destination account. Used by banks for inbound payment file processing.
2. **Real-time incident management** -- Connect Slack, PagerDuty, and ServiceNow so transfer failures trigger immediate alerts, create ITSM tickets, and page on-call engineers automatically.
3. **FIPS 140-2 compliance** -- Create security profiles with approved cipher suites and assign them to SFTP server instances. Audit which algorithms are in use across the platform.
4. **Automated partner file exchange** -- Schedule nightly pull tasks that retrieve files from partner SFTP servers, run them through processing flows, and push results to delivery endpoints.
5. **B2B EDI with AS2** -- Configure AS2 partnerships with trading partners including signing, encryption, and MDN receipt handling. Link them to delivery endpoints for automated EDI document exchange.
6. **SLA monitoring and breach detection** -- Define SLA agreements specifying delivery windows and volume thresholds. The platform detects breaches and triggers connector notifications automatically.
7. **Environment promotion** -- Use platform settings scoped by environment (DEV/TEST/CERT/PROD) to manage configuration across environments. Clone settings from TEST to CERT for pre-production validation.
8. **DMZ proxy delivery** -- Configure delivery endpoints to route outbound file transfers through the platform DMZ proxy, keeping internal servers off the public network while delivering to external partners.

---

## Environment Variables (Full Table)

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ hostname |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | Secret for JWT validation (must match Onboarding API) |
| `JWT_EXPIRATION` | `900000` | JWT token lifetime in milliseconds (15 min) |
| `CONTROL_API_KEY` | `internal_control_secret` | Shared key for inter-service communication |
| `CLUSTER_ID` | `default-cluster` | Identifier for this cluster |
| `CLUSTER_HOST` | `localhost` | Hostname of this instance |
| `CLUSTER_COMM_MODE` | `WITHIN_CLUSTER` | `WITHIN_CLUSTER` or `CROSS_CLUSTER` |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label (DEV, TEST, CERT, PROD) |
| `TRACK_ID_PREFIX` | `TRZ` | Prefix for generated track IDs |
| `SFTP_HOME_BASE` | `/data/sftp` | Base directory for SFTP user home dirs |
| `FTP_HOME_BASE` | `/data/ftp` | Base directory for FTP user home dirs |
| `FTPWEB_HOME_BASE` | `/data/ftpweb` | Base directory for FTP-Web user home dirs |
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
docker rm -f mft-config-service mft-postgres mft-rabbitmq
docker volume prune -f

# From Source -- just Ctrl+C the running process
```

---

## Troubleshooting

### All Platforms

| Symptom | Cause | Fix |
|---|---|---|
| `Connection refused` on port 8084 | Service not started or still starting | Wait 15-30 seconds for Spring Boot startup. Check `docker logs mft-config-service`. |
| `500 Internal Server Error` on startup | Database not ready | Ensure PostgreSQL is running and accessible. Flyway migrations run on first start. |
| `Flow name already exists` | Duplicate flow name | Each flow name must be unique. Use a different name or delete the existing flow first. |
| `Security profile name already exists` | Duplicate profile name | Each security profile name must be unique. |
| `Endpoint name already exists` | Duplicate delivery endpoint name | Each delivery endpoint name must be unique. |
| Connector test returns `FAILED` | Invalid webhook URL or network issue | Verify the URL is reachable from the container. Check firewall rules and DNS resolution. |

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

- **Onboarding API** (`docs/demos/ONBOARDING-API.md`) -- Register users, create transfer accounts, upload files, and use the partner portal.
- **SFTP Service** -- Connect actual SFTP clients; files will be processed by the flows and delivered to the endpoints you configured here.
- **Flow Engine** -- Executes the file flows you defined. Start it alongside the Config Service to see end-to-end pipeline processing.
- **Admin UI** -- Graphical dashboard for managing all Config Service entities with point-and-click interfaces.
- **Full Platform** -- Run `docker compose up -d` from the repo root to start all 20+ microservices together.

# Integration Test Guide: Full Platform Deployment

**3 SFTP Servers + 3 FTP Servers + DMZ Proxy + 2 AI Engines + Keystore Manager**

This guide walks through standing up the complete TranzFer MFT platform with multi-instance
file servers, AI-powered security, centralized key management, and end-to-end file transfer
testing.

---

## Architecture Under Test

```
                     Internet / Clients
                           │
               ┌───────────▼───────────┐
               │     DMZ PROXY         │
               │  :32222 (SFTP)        │
               │  :32121 (FTP)         │
               │  :4443  (HTTPS)       │
               │  :8088  (Mgmt API)    │
               │                       │
               │  Security Pipeline:   │
               │  ┌─ ManualFilter     │
               │  ├─ RateLimiter      │
               │  ├─ AI Verdict ──────┼──► AI Engine 1 (:8091)
               │  ├─ Zone Enforcer    │   AI Engine 2 (:8091 @127.0.0.2)
               │  ├─ DPI             │
               │  └─ Audit Logger    │
               └───────────┬───────────┘
                           │
               ┌───────────▼───────────┐
               │   GATEWAY SERVICE     │
               │  :2220 (SFTP relay)   │
               │  :2122 (FTP relay)    │
               │  Routes by username   │
               └───┬───────┬───────┬───┘
                   │       │       │
          ┌────────▼──┐ ┌──▼─────┐ ┌▼────────┐
          │ SFTP-1    │ │ SFTP-2 │ │ SFTP-3  │
          │ sftp-1    │ │ sftp-2 │ │ sftp-3  │
          │ :2222     │ │ :2222  │ │ :2222   │
          └───────────┘ └────────┘ └─────────┘
          ┌───────────┐ ┌────────┐ ┌─────────┐
          │ FTP-1     │ │ FTP-2  │ │ FTP-3   │
          │ ftp-1     │ │ ftp-2  │ │ ftp-3   │
          │ :21       │ │ :21    │ │ :21     │
          └───────────┘ └────────┘ └─────────┘

  ┌──────────────┐  ┌──────────────┐  ┌───────────┐
  │ Keystore Mgr │  │ Config Svc   │  │ Onboard   │
  │ :8093        │  │ :8084        │  │ API :8080 │
  │ SSH keys     │  │ File flows   │  │ Users     │
  │ TLS certs    │  │ Connectors   │  │ Accounts  │
  └──────────────┘  └──────────────┘  └───────────┘

  ┌──────────┐  ┌────────────┐  ┌──────────┐
  │ PostgreSQL│  │ RabbitMQ   │  │ Admin UI │
  │ :5432    │  │ :5672/15672│  │ :3000    │
  └──────────┘  └────────────┘  └──────────┘
```

---

## Prerequisites

- Docker Desktop with at least **8 GB RAM** allocated
- Docker Compose v2.x
- `curl` and `jq` installed
- An SFTP client (`sftp` CLI, FileZilla, WinSCP)
- An FTP client (`ftp`, `lftp`, FileZilla)
- Java 25 and Maven 3.9+ (for building from source)

---

## Step 1: Build All Services

```bash
cd /path/to/file-transfer-platform

# Build everything (skip tests for speed — tests already passed)
mvn clean package -DskipTests -q

# Verify JARs exist
ls -la */target/*.jar | grep -v original
```

**Expected output**: 19 JAR files, one per service module.

---

## Step 2: Start Infrastructure

```bash
# Start database and message broker first
docker compose up -d postgres rabbitmq

# Wait for health checks
docker compose ps --format "table {{.Name}}\t{{.Status}}"
```

Wait until both show `(healthy)` before proceeding (~15 seconds).

---

## Step 3: Start Core Services

```bash
# Keystore Manager first (SFTP-3 depends on it for host keys)
docker compose up -d keystore-manager

# Core API + config
docker compose up -d onboarding-api config-service

# Wait for health
sleep 10
curl -sf http://localhost:8080/actuator/health | jq .status
curl -sf http://localhost:8084/actuator/health | jq .status
curl -sf http://localhost:8093/actuator/health | jq .status
```

All three should return `"UP"`.

---

## Step 4: Start File Servers (3 SFTP + 3 FTP)

```bash
# Start all 6 file server instances
docker compose up -d \
  sftp-service sftp-service-2 sftp-service-3 \
  ftp-service ftp-service-2 ftp-service-3

# Verify all healthy
sleep 15
for svc in sftp-service sftp-service-2 sftp-service-3; do
  echo "$svc: $(curl -sf http://127.0.0.1:8081/actuator/health 2>/dev/null | jq -r .status)"
done
for svc in ftp-service ftp-service-2 ftp-service-3; do
  echo "$svc: $(curl -sf http://127.0.0.1:8082/actuator/health 2>/dev/null | jq -r .status)"
done
```

### Instance Details

| Instance   | Container              | SFTP/FTP Port        | REST Port          | Instance ID |
|-----------|------------------------|----------------------|--------------------|-------------|
| SFTP-1    | mft-sftp-service       | 127.0.0.1:2222       | 127.0.0.1:8081     | sftp-1      |
| SFTP-2    | mft-sftp-service-2     | 127.0.0.2:2222       | 127.0.0.2:8081     | sftp-2      |
| SFTP-3    | mft-sftp-service-3     | 127.0.0.3:2222       | 127.0.0.3:8081     | sftp-3      |
| FTP-1     | mft-ftp-service        | 127.0.0.1:21         | 127.0.0.1:8082     | ftp-1       |
| FTP-2     | mft-ftp-service-2      | 127.0.0.2:21         | 127.0.0.2:8082     | ftp-2       |
| FTP-3     | mft-ftp-service-3      | 127.0.0.3:21         | 127.0.0.3:8082     | ftp-3       |

> **Note**: SFTP-3 has `SFTP_KEYSTORE_MANAGER_ENABLED=true`, meaning it retrieves its SSH host
> key from the centralized Keystore Manager instead of generating a local one.

---

## Step 5: Start Gateway + AI Engines + DMZ Proxy

```bash
# Gateway routes connections to the correct backend instance
docker compose up -d gateway-service

# Two AI engine instances for redundancy
docker compose up -d ai-engine ai-engine-2

# DMZ Proxy — the security perimeter
docker compose up -d dmz-proxy

# Verify
sleep 10
curl -sf http://localhost:8085/actuator/health | jq .status   # gateway
curl -sf http://localhost:8091/actuator/health | jq .status   # ai-engine-1
curl -sf http://127.0.0.2:8091/actuator/health | jq .status  # ai-engine-2
curl -sf http://localhost:8088/api/proxy/health | jq .        # dmz-proxy
```

The DMZ proxy health response shows all active features:
```json
{
  "status": "UP",
  "service": "dmz-proxy",
  "activeMappings": 3,
  "securityEnabled": true,
  "aiEngineAvailable": true,
  "features": [
    "protocol_detection", "ai_verdict", "rate_limiting",
    "connection_tracking", "threat_event_reporting",
    "adaptive_rate_limits", "graceful_degradation",
    "zone_enforcement", "egress_filtering",
    "deep_packet_inspection", "ftp_command_filter",
    "backend_health_check", "audit_logging",
    "proxy_protocol", "connection_draining"
  ]
}
```

---

## Step 6: Start Admin UI

```bash
docker compose up -d ui-service
```

Open http://localhost:3000 in your browser.

---

## Step 7: Onboard Users

### Approach 1: Admin UI (Browser)

1. Navigate to http://localhost:3000
2. **Register** an admin account (email + password)
3. Go to **Accounts** page
4. Click **Create Account**:
   - Protocol: `SFTP`
   - Username: `alice-sftp`
   - Password: `SecurePass123!`
   - Home Directory: `/data/sftp/alice`
5. Create a second account:
   - Protocol: `FTP`
   - Username: `alice-ftp`
   - Password: `SecurePass123!`
   - Home Directory: `/data/ftp/alice`

### Approach 2: REST API (curl)

```bash
# 1. Register admin user
TOKEN=$(curl -sf http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@test.com","password":"Admin123!"}' \
  | jq -r .token)

echo "JWT Token: $TOKEN"

# 2. Create SFTP account
curl -sf http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "bob-sftp",
    "password": "BobPass456!",
    "protocol": "SFTP",
    "homeDir": "/data/sftp/bob"
  }' | jq .

# 3. Create FTP account
curl -sf http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "bob-ftp",
    "password": "BobPass456!",
    "protocol": "FTP",
    "homeDir": "/data/ftp/bob"
  }' | jq .

# 4. Create a partner company
curl -sf http://localhost:8080/api/partners \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "companyName": "Acme Corp",
    "partnerType": "EXTERNAL",
    "protocolsEnabled": ["SFTP", "FTP"]
  }' | jq .
```

### Approach 3: CLI

```bash
# Using the admin CLI endpoint
curl -sf http://localhost:8080/api/cli/execute \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/plain" \
  -d 'accounts create --protocol SFTP --username carol-sftp --password CarolPass789!'

curl -sf http://localhost:8080/api/cli/execute \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/plain" \
  -d 'accounts create --protocol FTP --username carol-ftp --password CarolPass789!'
```

### Approach 4: MFT Client Installer

```bash
cd installer
./mft setup
# Follow prompts:
#   Server: localhost
#   Port: 8080
#   Email: admin@test.com
#   Password: Admin123!

./mft send testfile.txt
```

---

## Step 8: Register Server Instances

Register all 6 server instances so the platform knows about them:

```bash
# SFTP instances
for i in 1 2 3; do
  curl -sf http://localhost:8080/api/servers \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"instanceId\": \"sftp-$i\",
      \"protocol\": \"SFTP\",
      \"name\": \"SFTP Server $i\",
      \"description\": \"SFTP instance $i\",
      \"internalHost\": \"sftp-service$([ $i -gt 1 ] && echo "-$i" || echo "")\",
      \"internalPort\": 2222,
      \"useProxy\": true,
      \"proxyHost\": \"dmz-proxy\",
      \"proxyPort\": 32222,
      \"maxConnections\": 500,
      \"active\": true
    }" | jq '{id:.id, instanceId:.instanceId, name:.name}'
done

# FTP instances
for i in 1 2 3; do
  curl -sf http://localhost:8080/api/servers \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"instanceId\": \"ftp-$i\",
      \"protocol\": \"FTP\",
      \"name\": \"FTP Server $i\",
      \"description\": \"FTP instance $i\",
      \"internalHost\": \"ftp-service$([ $i -gt 1 ] && echo "-$i" || echo "")\",
      \"internalPort\": 21,
      \"useProxy\": true,
      \"proxyHost\": \"dmz-proxy\",
      \"proxyPort\": 32121,
      \"maxConnections\": 200,
      \"active\": true
    }" | jq '{id:.id, instanceId:.instanceId, name:.name}'
done
```

---

## Step 9: Configure Folder Mappings (Inbox / Outbox / Sent)

Each user gets a standard MFT folder layout:

```
/data/sftp/bob/
  ├── inbox/      ← files arrive here (from partners or other users)
  ├── outbox/     ← bob places files here for delivery
  └── sent/       ← copies of delivered files (audit trail)
```

### Create Folder Mappings

```bash
# Get bob-sftp account ID
BOB_SFTP_ID=$(curl -sf http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" \
  | jq -r '.[] | select(.username=="bob-sftp") | .id')

# Get carol-sftp account ID
CAROL_SFTP_ID=$(curl -sf http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" \
  | jq -r '.[] | select(.username=="carol-sftp") | .id')

# Mapping: Bob's outbox → Carol's inbox
curl -sf http://localhost:8080/api/folder-mappings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceAccountId\": \"$BOB_SFTP_ID\",
    \"sourcePath\": \"/outbox\",
    \"destinationAccountId\": \"$CAROL_SFTP_ID\",
    \"destinationPath\": \"/inbox\",
    \"filenamePattern\": \".*\\\\.csv\",
    \"encryptionOption\": \"NONE\"
  }" | jq .

# Mapping: Carol's outbox → Bob's inbox
curl -sf http://localhost:8080/api/folder-mappings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceAccountId\": \"$CAROL_SFTP_ID\",
    \"sourcePath\": \"/outbox\",
    \"destinationAccountId\": \"$BOB_SFTP_ID\",
    \"destinationPath\": \"/inbox\",
    \"encryptionOption\": \"NONE\"
  }" | jq .
```

---

## Step 10: Create a File Flow

```bash
curl -sf http://localhost:8084/api/flows \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"bob-to-carol-flow\",
    \"description\": \"Compress and deliver CSV files from Bob to Carol\",
    \"filenamePattern\": \".*\\\\.csv\",
    \"sourceAccountId\": \"$BOB_SFTP_ID\",
    \"sourcePath\": \"/outbox\",
    \"steps\": [
      {\"type\": \"COMPRESS_GZIP\", \"config\": {}, \"order\": 0},
      {\"type\": \"SCREEN\", \"config\": {\"action\": \"log\"}, \"order\": 1}
    ],
    \"destinationAccountId\": \"$CAROL_SFTP_ID\",
    \"destinationPath\": \"/inbox\",
    \"priority\": 10,
    \"active\": true
  }" | jq .
```

---

## Step 11: Test Login and File Transfer

### SFTP Login (via DMZ Proxy)

```bash
# Connect through the proxy (port 32222)
sftp -P 32222 bob-sftp@localhost
# Password: BobPass456!

# Once connected:
sftp> ls
sftp> mkdir inbox outbox sent
sftp> cd outbox
sftp> put test-invoice.csv
sftp> ls /inbox
sftp> exit
```

### FTP Login (via DMZ Proxy)

```bash
# Connect through the proxy (port 32121)
ftp localhost 32121
# Username: bob-ftp
# Password: BobPass456!

ftp> ls
ftp> mkdir inbox
ftp> mkdir outbox
ftp> mkdir sent
ftp> cd outbox
ftp> put test-invoice.csv
ftp> ls /inbox
ftp> bye
```

### Direct SFTP Login (bypassing proxy — for comparison)

```bash
# Direct to SFTP-1 (no security pipeline)
sftp -P 2222 bob-sftp@127.0.0.1
```

### Track the Transfer

```bash
# List recent transfers
curl -sf http://localhost:8084/api/flows/executions?page=0&size=5 \
  -H "Authorization: Bearer $TOKEN" | jq '.content[] | {trackId, status, originalFilename}'

# Track specific transfer
TRACK_ID="TRZ-XXXXXXXX"
curl -sf http://localhost:8084/api/flows/executions/$TRACK_ID \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

## Step 12: Verify Security

### Check Proxy Security Stats

```bash
curl -sf http://localhost:8088/api/proxy/security/stats \
  -H "X-Internal-Key: internal_control_secret" | jq .
```

### Check AI Engine Verdicts

```bash
# Recent verdicts from AI engine
curl -sf http://localhost:8091/api/v1/proxy/verdicts \
  -H "X-Internal-Key: internal_control_secret" | jq '.[0:3]'
```

### Check Backend Health

```bash
curl -sf http://localhost:8088/api/proxy/backends/health \
  -H "X-Internal-Key: internal_control_secret" | jq .
```

### Check Audit Logs

```bash
curl -sf http://localhost:8088/api/proxy/audit/stats \
  -H "X-Internal-Key: internal_control_secret" | jq .
```

---

## Step 13: Generate SSH Keys via Keystore Manager

```bash
# Generate an SSH host key for SFTP-3
curl -sf http://localhost:8093/api/v1/keys/generate/ssh-host \
  -H "Content-Type: application/json" \
  -d '{"alias": "sftp-host-key-sftp-3", "ownerService": "sftp-service-3"}' | jq .

# Generate a user SSH key for bob
curl -sf http://localhost:8093/api/v1/keys/generate/ssh-user \
  -H "Content-Type: application/json" \
  -d '{"alias": "bob-ssh-key", "partnerAccount": "bob-sftp", "keySize": 4096}' | jq .

# List all managed keys
curl -sf http://localhost:8093/api/v1/keys | jq '.[].alias'
```

---

## Cleanup

```bash
# Stop everything
docker compose down

# Stop and remove volumes (full reset)
docker compose down -v
```

---

## Quick Reference: All Ports

| Service            | Port(s)                           |
|--------------------|-----------------------------------|
| PostgreSQL         | 5432                              |
| RabbitMQ           | 5672 (AMQP), 15672 (Management)  |
| Onboarding API     | 8080                              |
| SFTP-1/2/3         | 127.0.0.{1,2,3}:2222 + :8081     |
| FTP-1/2/3          | 127.0.0.{1,2,3}:21 + :8082       |
| Config Service     | 8084                              |
| Gateway            | 2220 (SFTP), 2122 (FTP), 8085    |
| DMZ Proxy          | 32222 (SFTP), 32121 (FTP), 4443 (HTTPS), 8088 (API) |
| AI Engine 1        | 8091                              |
| AI Engine 2        | 127.0.0.2:8091                    |
| Keystore Manager   | 8093                              |
| Admin UI           | 3000                              |

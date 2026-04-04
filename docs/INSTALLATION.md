# TranzFer MFT — Complete Installation Guide

## Platform Overview

TranzFer has **17 services** organized into 5 tiers. You only install what you need.

```
┌─────────────────────────────────────────────────────────────────────┐
│                        TIER 1: INFRASTRUCTURE                        │
│                     (Required — always install)                       │
│  ┌────────────┐  ┌────────────┐                                      │
│  │ PostgreSQL │  │ RabbitMQ   │                                      │
│  │   :5432    │  │   :5672    │                                      │
│  └────────────┘  └────────────┘                                      │
├─────────────────────────────────────────────────────────────────────┤
│                      TIER 2: CORE PLATFORM                           │
│                  (Required — the minimum install)                     │
│  ┌──────────────┐  ┌─────────────┐                                   │
│  │ Onboarding   │  │ Config      │                                   │
│  │ API :8080    │  │ Service     │                                   │
│  │ Auth,Users,  │  │ :8084       │                                   │
│  │ Accounts     │  │ Flows,Certs │                                   │
│  └──────────────┘  └─────────────┘                                   │
├─────────────────────────────────────────────────────────────────────┤
│                    TIER 3: TRANSFER SERVICES                         │
│              (Install per protocol you need)                         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │ SFTP     │ │ FTP      │ │ FTP-Web  │ │ Gateway  │ │ DMZ      │  │
│  │ :2222    │ │ :21      │ │ :8083    │ │ :2220    │ │ Proxy    │  │
│  │ :8081    │ │ :8082    │ │          │ │ :2121    │ │ :8088    │  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
├─────────────────────────────────────────────────────────────────────┤
│                    TIER 4: INTELLIGENCE                               │
│              (Optional — adds AI, screening, analytics)              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │ Encrypt  │ │ Forward  │ │ AI       │ │ Screen   │ │ Analytics│  │
│  │ :8086    │ │ :8087    │ │ Engine   │ │ Service  │ │ :8090    │  │
│  │ AES/PGP  │ │ Ext SFTP │ │ :8091    │ │ :8092    │ │ Metrics  │  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
├─────────────────────────────────────────────────────────────────────┤
│                      TIER 5: FRONTENDS                               │
│                 (Optional — UIs + license)                            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                             │
│  │ Admin UI │ │ File     │ │ License  │                             │
│  │ :3000    │ │ Portal   │ │ Service  │                             │
│  │ React    │ │ :3001    │ │ :8089    │                             │
│  └──────────┘ └──────────┘ └──────────┘                             │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Installation Recipes

### Recipe 1: SFTP Server Only (Minimal)

**What:** Accept files via SFTP from partners.
**Services:** 5

```bash
# Build
mvn clean package -DskipTests -pl shared,onboarding-api,sftp-service,config-service

# Run
docker compose up -d postgres rabbitmq onboarding-api sftp-service config-service
```

| Service | Port | Purpose |
|---------|------|---------|
| postgres | 5432 | Database |
| rabbitmq | 5672 | Events |
| onboarding-api | 8080 | Auth + accounts |
| sftp-service | 2222/8081 | SFTP server |
| config-service | 8084 | Flows + config |

---

### Recipe 2: SFTP + FTP (Multi-Protocol)

**What:** Accept files via both SFTP and FTP.
**Services:** 6

```bash
mvn clean package -DskipTests -pl shared,onboarding-api,sftp-service,ftp-service,config-service

docker compose up -d postgres rabbitmq onboarding-api sftp-service ftp-service config-service
```

---

### Recipe 3: Full Transfer Platform (No AI)

**What:** All transfer protocols + gateway + DMZ + encryption + forwarding.
**Services:** 12

```bash
mvn clean package -DskipTests

docker compose up -d postgres rabbitmq \
  onboarding-api sftp-service ftp-service ftp-web-service \
  config-service gateway-service encryption-service \
  external-forwarder-service dmz-proxy admin-ui
```

---

### Recipe 4: Full Platform + AI + Screening

**What:** Everything. Full enterprise deployment.
**Services:** 17

```bash
mvn clean package -DskipTests
docker compose up --build -d
```

| Tier | Services | Ports |
|------|----------|-------|
| Infrastructure | postgres, rabbitmq | 5432, 5672 |
| Core | onboarding-api, config-service | 8080, 8084 |
| Transfer | sftp, ftp, ftp-web, gateway, dmz-proxy | 2222, 21, 8083, 2220, 8088 |
| Intelligence | encryption, forwarder, ai-engine, screening, analytics | 8086-8092 |
| Frontend | admin-ui, ftp-web-ui, license-service | 3000, 3001, 8089 |

---

### Recipe 5: SFTP + Screening (Compliance)

**What:** SFTP with OFAC/AML screening on every file.
**Services:** 7

```bash
mvn clean package -DskipTests -pl shared,onboarding-api,sftp-service,config-service,screening-service

docker compose up -d postgres rabbitmq \
  onboarding-api sftp-service config-service screening-service admin-ui
```

Then create a flow with the SCREEN step:
```bash
curl -X POST http://localhost:8084/api/flows -H "Content-Type: application/json" -d '{
  "name": "screen-all-inbound",
  "steps": [{"type": "SCREEN", "config": {}, "order": 0}, {"type": "ROUTE", "config": {}, "order": 1}]
}'
```

---

### Recipe 6: AI-Powered MFT (Intelligence + Transfer)

**What:** SFTP with AI classification, anomaly detection, and NLP admin.
**Services:** 8

```bash
docker compose up -d postgres rabbitmq \
  onboarding-api sftp-service config-service \
  ai-engine analytics-service admin-ui
```

---

## Step-by-Step Installation

### Prerequisites

| Requirement | Version | Check |
|-------------|---------|-------|
| Java | 21+ | `java --version` |
| Maven | 3.8+ | `mvn --version` |
| Docker | 24+ | `docker --version` |
| Docker Compose | v2+ | `docker compose version` |
| Git | any | `git --version` |

### Step 1: Clone

```bash
git clone https://github.com/rdship/tranzfer-mft.git
cd tranzfer-mft
```

### Step 2: Configure Environment

```bash
cp .env.production .env
```

Edit `.env` — at minimum change these:

```env
JWT_SECRET=<run: openssl rand -base64 32>
CONTROL_API_KEY=<run: openssl rand -hex 16>
DB_PASSWORD=<your-db-password>
TRACK_ID_PREFIX=TRZ          # Your 3-char prefix for tracking IDs
CLAUDE_API_KEY=               # Optional: for AI NLP features
```

### Step 3: Build

```bash
# Set JAVA_HOME if needed
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Build all
mvn clean package -DskipTests

# Or build only what you need
mvn clean package -DskipTests -pl shared,onboarding-api,sftp-service
```

### Step 4: Start

```bash
# Full platform
docker compose up --build -d

# Or selective (see recipes above)
docker compose up -d postgres rabbitmq onboarding-api sftp-service config-service
```

### Step 5: Create Admin User

```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@yourcompany.com","password":"Str0ng!Pass"}'

# Promote to admin
docker exec mft-postgres psql -U postgres -d filetransfer \
  -c "UPDATE users SET role='ADMIN' WHERE email='admin@yourcompany.com';"
```

### Step 6: Verify

```bash
# Run the test suite
./tests/run-tests.sh --html

# Open the report
open tests/reports/test-report-*.html
```

### Step 7: Access

| URL | What |
|-----|------|
| http://localhost:3000 | Admin Dashboard (18 pages) |
| http://localhost:3001 | User File Portal |
| http://localhost:8080/api/cli/execute | Admin CLI API |
| http://localhost:8092/api/v1/screening/health | Screening status |
| http://localhost:8091/api/v1/ai/health | AI engine status |

---

## Kubernetes Installation

See [INSTALL-KUBERNETES.md](INSTALL-KUBERNETES.md) for Helm chart deployment.

```bash
# Build images
for svc in onboarding-api sftp-service ftp-service ftp-web-service \
  config-service gateway-service encryption-service external-forwarder-service \
  dmz-proxy license-service analytics-service ai-engine screening-service \
  admin-ui ftp-web-ui; do
  docker build -t your-registry.com/mft/${svc}:latest ./${svc}
  docker push your-registry.com/mft/${svc}:latest
done

# Install with Helm (selective)
helm install mft ./helm/mft-platform --namespace mft \
  --set sftpService.enabled=true \
  --set ftpService.enabled=false \
  --set aiEngine.enabled=true \
  --set screeningService.enabled=true
```

---

## MFT Client Installation

See [mft-client/README.md](../mft-client/README.md).

```bash
# Build client bundles for all platforms
./mft-client/scripts/bundle/build-bundles.sh

# Distribute to partners:
#   mft-client-linux-x64.tar.gz
#   mft-client-macos-arm64.tar.gz
#   mft-client-windows-x64.zip

# Partners just extract and run:
tar xzf mft-client-linux-x64.tar.gz
cd mft-client-linux-x64
# Edit mft-client.yml with server details
./mft-client
```

---

## Service Reference

| Service | Port(s) | Module | Database | MQ | Required By |
|---------|---------|--------|:--------:|:--:|-------------|
| **postgres** | 5432 | — | — | — | All Java services |
| **rabbitmq** | 5672, 15672 | — | — | — | Account sync |
| **onboarding-api** | 8080 | onboarding-api | Yes | Yes | Everything |
| **config-service** | 8084 | config-service | Yes | Yes | Flows, security profiles |
| **sftp-service** | 2222, 8081 | sftp-service | Yes | Yes | SFTP transfers |
| **ftp-service** | 21, 8082 | ftp-service | Yes | Yes | FTP transfers |
| **ftp-web-service** | 8083 | ftp-web-service | Yes | Yes | Web file upload |
| **gateway-service** | 2220, 2121, 8085 | gateway-service | Yes | Yes | Protocol routing |
| **encryption-service** | 8086 | encryption-service | Yes | No | AES/PGP encryption |
| **forwarder-service** | 8087 | external-forwarder-service | Yes | Yes | External SFTP/FTP |
| **dmz-proxy** | 8088, 2222, 2121, 443 | dmz-proxy | **No** | No | DMZ isolation |
| **license-service** | 8089 | license-service | Yes | No | Licensing |
| **analytics-service** | 8090 | analytics-service | Yes | Yes | Metrics, predictions |
| **ai-engine** | 8091 | ai-engine | Yes | No | AI features |
| **screening-service** | 8092 | screening-service | Yes | No | OFAC/AML screening |
| **admin-ui** | 3000 | admin-ui (React) | No | No | Admin dashboard |
| **ftp-web-ui** | 3001 | ftp-web-ui (React) | No | No | User file portal |

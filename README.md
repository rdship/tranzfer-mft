<div align="center">

# ⚡ TranzFer MFT

### AI-First Enterprise Managed File Transfer

[![CI — Build & Test](https://github.com/rdship/tranzfer-mft/actions/workflows/ci.yml/badge.svg)](https://github.com/rdship/tranzfer-mft/actions/workflows/ci.yml)
[![License: BSL 1.1](https://img.shields.io/badge/License-BSL_1.1-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21_LTS-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.2](https://img.shields.io/badge/Spring_Boot-3.2-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![React 18](https://img.shields.io/badge/React-18-61DAFB.svg)](https://react.dev/)
[![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-336791.svg)](https://www.postgresql.org/)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED.svg)](https://www.docker.com/)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-Helm-326CE5.svg)](https://kubernetes.io/)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

**20 microservices** · **34 admin UI pages** · **SFTP/FTP/FTPS/HTTPS** · **AI classification** · **OFAC screening** · **PCI compliant**

</div>

---

## What is TranzFer?

TranzFer is a **production-grade, AI-first Managed File Transfer platform** — cloud-native, AI-powered, and built to scale.

---

# 🚀 Getting Started (Pick Your Path)

> **You only need Docker installed on your computer.** That's it.  
> Don't have Docker? [Get it here](https://www.docker.com/products/docker-desktop/) — just download, install, and open it.

---

## Path 1: "I want to run the full server" 🖥️

This sets up the entire TranzFer platform on your machine — all services, the admin dashboard, everything.

```bash
# Step 1: Download the code
git clone https://github.com/rdship/tranzfer-mft.git
cd tranzfer-mft

# Step 2: Build it (one command, wait ~2 minutes)
mvn clean package -DskipTests

# Step 3: Start it (one command, wait ~3 minutes)
docker compose up --build -d

# Step 4: Create your admin account
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@company.com","password":"MyPassword123!"}'
```

**Done!** Open your browser:
- **Admin Dashboard** → [http://localhost:3000](http://localhost:3000)
- **File Upload Portal** → [http://localhost:3001](http://localhost:3001)

> **Don't want everything?** Just pick what you need:
> ```bash
> # Only SFTP server + admin dashboard (minimal setup)
> docker compose up -d postgres rabbitmq onboarding-api sftp-service config-service admin-ui
>
> # Add AI features
> docker compose up -d ai-engine
>
> # Add OFAC sanctions screening
> docker compose up -d screening-service
>
> # Add encryption
> docker compose up -d encryption-service
> ```

---

## Path 2: "I want to use the CLI installer" 🧙

The interactive installer asks you questions and sets everything up for you. No guessing.

```bash
# Step 1: Download
git clone https://github.com/rdship/tranzfer-mft.git
cd tranzfer-mft

# Step 2: Run the installer
chmod +x installer/tranzfer
./installer/tranzfer
```

You'll see a friendly menu:

```
  ╔══════════════════════════════════════════════════╗
  ║        ⚡ TranzFer MFT Installer v1.0.0         ║
  ║     AI-First Enterprise File Transfer Platform    ║
  ╚══════════════════════════════════════════════════╝

  What would you like to do?
  1) Install Server      — Full platform with all services
  2) Install Client      — Lightweight client for sending/receiving files
  3) Doctor              — Diagnose problems with your installation
  4) Status              — Check what's running
  5) Migrate             — Migrate from another MFT product
  6) Quit
```

Just type a number and follow the prompts. The installer handles everything.

**Quick shortcuts** (skip the menu):
```bash
./installer/tranzfer install server     # Jump straight to server install
./installer/tranzfer install client     # Jump straight to client install
./installer/tranzfer doctor             # Diagnose issues
./installer/tranzfer status             # What's running?
```

---

## Path 3: "I'm a client — I just want to send/receive files" 📁

You don't need Docker. You don't need Maven. You don't even need Java installed.  
The client comes with everything it needs built-in.

```bash
# Step 1: Download
git clone https://github.com/rdship/tranzfer-mft.git
cd tranzfer-mft

# Step 2: Set up the client CLI
chmod +x installer/mft

# Step 3: Configure your connection (the server admin gives you these details)
./installer/mft setup
```

It asks you a few questions:
```
  Server hostname: files.yourcompany.com
  Port: 2222
  Protocol (sftp/ftp): sftp
  Username: my-username
  Password: ********
  ✓ Connected successfully!
```

Now you can send and receive files:

```bash
# Send a file to the server
./installer/mft send invoice.csv

# Send a file directly to another person (P2P)
./installer/mft send report.pdf @bob

# Check your inbox
./installer/mft inbox

# Track where your file went
./installer/mft track TRZA3X5T3LUY

# See who's online for direct transfers
./installer/mft peers

# Start auto-sync (watches your outbox folder, sends automatically)
./installer/mft start

# Check connection status
./installer/mft status
```

---

## Path 4: "I'm migrating from another product" 🚚

Moving from another MFT product? The migration tool handles everything:

```bash
# Step 1: Make sure TranzFer server is running (Path 1 or 2 above)

# Step 2: Run the migration tool
chmod +x installer/migration/migrate.sh
./installer/migration/migrate.sh
```

It walks you through 6 phases:

```
  Phase 1: Connect to source system
  Phase 2: Extract users, keys, certificates
  Phase 3: Extract flows, routes, schedules
  Phase 4: Import into TranzFer
  Phase 5: Validate everything matches
  Phase 6: Parallel run (old + new side by side)
```

**What gets migrated:**
- ✅ All user accounts and permissions
- ✅ SSH keys and TLS certificates
- ✅ File flows and processing rules
- ✅ Routing tables
- ✅ Schedules (cron jobs)
- ✅ SLA agreements
- ✅ Connector configurations

**Quick shortcuts:**
```bash
# Import from CSV file
./installer/migration/migrate.sh --import-csv users.csv

# Resume a migration that was interrupted
./installer/migration/migrate.sh --resume <session-id>

# Validate a completed migration
./installer/migration/migrate.sh --validate <session-id>

# Rollback if something goes wrong
./installer/migration/migrate.sh --rollback <session-id>
```

---

# 📖 What Can I Do After Setup?

## The Admin Dashboard (http://localhost:3000)

Once logged in, you'll see a sidebar with everything organized:

| Section | What's Inside |
|---------|--------------|
| **Overview** | Dashboard, Transfer Journey (track any file), Live Activity, Service Health |
| **File Transfer** | Manage accounts, users, folder mappings, processing flows, P2P transfers, external destinations |
| **Infrastructure** | Server config, security profiles, encryption keys, keystore, 2FA, storage, gateway, scheduler |
| **Compliance** | OFAC screening, SLA agreements, blockchain proof, connectors (Slack, PagerDuty, etc.) |
| **Developer** | Transfer API v2, EDI translation, multi-tenant management |
| **Observability** | AI recommendations, analytics, predictions, logs |
| **Administration** | NLP terminal (type in English!), license management, branding/settings |

> **The dashboard only shows pages for services that are running.** If you didn't start the screening service, you won't see the OFAC page. No clutter.

## The Admin Terminal

The coolest feature — type commands in plain English:

```
> show me all active transfers
> how many files did we process today
> create a new account for partner acme-corp
> what's the status of the sftp server
> show me failed transfers in the last hour
```

---

# 🔧 Common Tasks (Copy-Paste Ready)

### Create a new transfer account
```bash
curl -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{"name":"acme-corp","homeDir":"/data/sftp/acme","enabled":true}'
```

### Set up a file flow (auto-process files)
Go to Admin Dashboard → Processing Flows → Create New Flow  
Or use the API:
```bash
curl -X POST http://localhost:8084/api/v1/flows \
  -H "Content-Type: application/json" \
  -d '{"name":"Compress and Screen","triggerPattern":"*.csv","steps":[
    {"type":"COMPRESS_GZIP","order":1},
    {"type":"SCREEN","order":2}
  ]}'
```

### Check if everything is healthy
```bash
./installer/tranzfer doctor
# or manually:
curl http://localhost:8080/actuator/health
curl http://localhost:8091/api/v1/ai/health
```

### Stop everything
```bash
docker compose down          # Stop all services (keeps data)
docker compose down -v       # Stop all + delete data (fresh start)
```

### Restart a single service
```bash
docker compose restart sftp-service
docker compose restart ai-engine
```

### View logs
```bash
docker compose logs -f sftp-service       # Follow SFTP logs
docker compose logs -f onboarding-api     # Follow API logs
docker compose logs --tail=100 ai-engine  # Last 100 lines of AI engine
```

---

# 🏗️ Architecture (for the curious)

```
┌─────────────────────────────────────────────────────────────────────┐
│  INFRASTRUCTURE                                                      │
│  PostgreSQL 16     RabbitMQ 3.13                                    │
├─────────────────────────────────────────────────────────────────────┤
│  CORE                                                                │
│  Onboarding API :8080     Config Service :8084                      │
├─────────────────────────────────────────────────────────────────────┤
│  TRANSFER                                                            │
│  SFTP :2222   FTP :21   FTP-Web :8083   Gateway :2220   DMZ :8088  │
├─────────────────────────────────────────────────────────────────────┤
│  INTELLIGENCE                                                        │
│  AI Engine :8091     Screening :8092    Encryption :8086            │
│  Analytics :8090     Keystore :8093     Storage :8094               │
│  Forwarder :8087     EDI Converter :8095                            │
├─────────────────────────────────────────────────────────────────────┤
│  FRONTENDS                                                           │
│  Admin UI :3000   File Portal :3001   Partner Portal :3002          │
│  License :8089                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

**20 microservices** — install only what you need.

---

# 🔌 All Microservices at a Glance

| Service | Port | What It Does |
|---------|:----:|-------------|
| onboarding-api | 8080 | The brain — auth, accounts, folder mappings, P2P, journey tracker |
| config-service | 8084 | Flows, servers, security profiles, scheduler, SLA, connectors |
| sftp-service | 2222 | SFTP server — clients connect here to upload/download files |
| ftp-service | 21 | FTP/FTPS server — for legacy FTP clients |
| ftp-web-service | 8083 | HTTP file upload/download API |
| gateway-service | 2220 | Protocol gateway — single entry point for SFTP/FTP |
| dmz-proxy | 8088 | DMZ reverse proxy — sits in the DMZ for security |
| encryption-service | 8086 | Encrypts/decrypts files (AES-256, PGP) |
| external-forwarder | 8087 | Forwards files to external SFTP/FTP/Kafka |
| license-service | 8089 | License management — 30 day free trial built in |
| analytics-service | 8090 | Metrics, scaling predictions, alerting |
| ai-engine | 8091 | AI brain — classification, NLP, anomaly detection, recommendations |
| screening-service | 8092 | OFAC/AML sanctions screening (18,698 real entries) |
| keystore-manager | 8093 | Central key and certificate management |
| storage-manager | 8094 | Enterprise storage — tiered (hot/warm/cold), dedup, parallel I/O |
| edi-converter | 8095 | EDI translation — X12, EDIFACT, HL7, SWIFT, 10 formats |
| admin-ui | 3000 | Admin dashboard — 34 pages, white-label ready |
| ftp-web-ui | 3001 | Simple file upload portal for end users |
| partner-portal | 3002 | Self-service portal for trading partners |
| mft-client | — | Desktop client — bundled JRE, works on any OS |

---

# 🤖 AI Features

**All AI features work without any LLM.** Zero configuration needed.

| Feature | What It Does | Needs LLM? |
|---------|-------------|:----------:|
| PCI/PII Detection | Scans files for credit card numbers, SSNs, etc. | No |
| OFAC Screening | Checks names against 18,698 US Treasury sanctions entries | No |
| Anomaly Detection | Spots unusual file sizes, times, patterns | No |
| Smart Retry | Decides whether to retry a failed transfer and how long to wait | No |
| Threat Scoring | Calculates risk score for each transfer | No |
| Partner Profiling | Learns each partner's normal behavior, flags deviations | No |
| Predictive SLA | Predicts which SLAs will breach before they do | No |
| Auto-Remediation | Fixes common problems automatically | No |
| File Format Detection | Identifies file types by content (not just extension) | No |
| AI Recommendations | Analyzes Prometheus metrics, suggests optimizations | No |
| **NLP Terminal** | **Type commands in English** | **Optional** (works 90% without) |
| **Flow Builder** | **Describe what you need in English** | **Optional** |

> **Want smarter English understanding?** Just set one environment variable:
> ```bash
> # In your .env file or docker-compose:
> CLAUDE_API_KEY=sk-ant-your-key-here
> docker compose restart ai-engine
> ```
> That's it. Everything else stays the same.

---

# 📊 How a File Moves Through the System

```
Client A uploads invoice.csv via SFTP
         │
         ▼
  ┌─────────────────────┐
  │  SFTP Service        │  Receives file, generates Track ID: TRZA3X5T3LUY
  └────────┬────────────┘
           ▼
  ┌─────────────────────┐
  │  AI Engine           │  Scans for PCI/PII data → "No sensitive data found ✓"
  └────────┬────────────┘
           ▼
  ┌─────────────────────┐
  │  Flow Engine         │  Runs your processing steps:
  │                      │    1. COMPRESS_GZIP (1ms)
  │                      │    2. RENAME (0ms)
  │                      │    3. SCREEN — OFAC check (clear)
  └────────┬────────────┘
           ▼
  ┌─────────────────────┐
  │  Routing Engine      │  Routes to Client B's inbox
  └────────┬────────────┘
           ▼
  Client B downloads invoice.csv from their inbox
  
  Track ID: TRZA3X5T3LUY — every step logged, every checksum verified
```

---

# 📚 Documentation

> **Full documentation index:** [docs/README.md](docs/README.md) | **Product guide:** [docs/PRODUCT-GUIDE.md](docs/PRODUCT-GUIDE.md)
>
> **Every microservice has its own detailed README.md** — click any module directory on GitHub to see it.

| Document | What's Inside |
|----------|--------------|
| [Product Guide](docs/PRODUCT-GUIDE.md) | **Start here** — Complete product reference, concepts, file flow, editions |
| [Architecture](docs/ARCHITECTURE.md) | System architecture, service communication, data flows, deployment topologies |
| [Service Catalog](docs/SERVICES.md) | Every service explained — what it does, ports, config, how to run it |
| [Security Architecture](docs/SECURITY-ARCHITECTURE.md) | DMZ proxy + AI engine security deep-dive, threat model, monitoring |
| [API Reference](docs/API-REFERENCE.md) | All REST endpoints across all 20 services |
| [Configuration](docs/CONFIGURATION.md) | Every environment variable, port, and default in one place |
| [Developer Guide](docs/DEVELOPER-GUIDE.md) | Build, test, debug, contribute — IDE setup, common issues |
| [Gap Analysis](docs/GAP-ANALYSIS.md) | Known gaps in docs, security, testing, and operations |
| [Installation](docs/INSTALLATION.md) | Complete install guide — hardware, DB, network, firewall, 6 deployment recipes |
| [Capacity Planning](docs/CAPACITY-PLANNING.md) | How to scale to 100K partners / 10M files per day |
| [PCI Compliance](docs/PCI-DSS-COMPLIANCE.md) | PCI DSS compliance mapping |
| [Kubernetes](docs/INSTALL-KUBERNETES.md) | Kubernetes + Helm chart deployment |
| [On-Premise](docs/INSTALL-ON-PREMISE.md) | Bare metal / on-premise setup |
| [Migration](docs/MIGRATION-CHECKLIST.md) | 130-item checklist for enterprise migration |
| [Roadmap](docs/PRODUCT-ROADMAP.md) | What's coming next |
| **[Standalone Products](docs/standalone-products/)** | **Each microservice as an independent product — curl examples, integration code, Docker setup** |
| **[Demos & Quick Start](docs/demos/)** | **First-principles, cross-OS demo guides for every microservice — prerequisites, install, working demos** |

---

# 🛠️ Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 3.2 |
| Database | PostgreSQL 16 |
| Messaging | RabbitMQ 3.13 |
| SFTP | Apache MINA SSHD 2.12 |
| FTP | Apache FTPServer 1.2 |
| Encryption | Bouncy Castle 1.77 (AES-256, PGP) |
| Frontend | React 18, Vite 5, TailwindCSS 3, Recharts |
| Container | Docker, Kubernetes (Helm) |
| AI | Claude API (optional), regex classification, Jaro-Winkler matching |
| Monitoring | Micrometer + Prometheus + OpenTelemetry |

---

# ❓ Troubleshooting

### "Docker says port is already in use"
```bash
# Find what's using the port (example: 8080)
lsof -i :8080
# Kill it, or change the port in docker-compose.yml
```

### "Services won't start"
```bash
# Run the built-in doctor
./installer/tranzfer doctor

# Or check Docker logs
docker compose logs onboarding-api
```

### "Database connection errors"
```bash
# Make sure PostgreSQL is healthy
docker compose ps postgres
# Check if it's accepting connections
docker compose exec postgres pg_isready -U postgres
```

### "I want to start fresh"
```bash
docker compose down -v    # Stops everything and deletes all data
docker compose up --build -d   # Rebuild and start
```

### "mvn command not found"
You need Java 21 and Maven. Install them:
```bash
# macOS
brew install openjdk@21 maven

# Ubuntu/Debian
sudo apt install openjdk-21-jdk maven

# Or just use Docker (no Java/Maven needed on your machine):
docker run -v $(pwd):/app -w /app maven:3.9-eclipse-temurin-21 mvn clean package -DskipTests
```

---

## License

[Business Source License 1.1](LICENSE) — free for non-production use.
Production use permitted except as a competing hosted service.
Converts to Apache 2.0 after 4 years.

See [EULA.md](EULA.md) for enterprise licensing tiers.

## Legal

- [SECURITY.md](SECURITY.md) — Vulnerability reporting
- [DISCLAIMER.md](DISCLAIMER.md) — OFAC, PCI, HIPAA, GDPR disclaimers
- [PRIVACY.md](PRIVACY.md) — Data handling practices
- [EXPORT_CONTROL.md](EXPORT_CONTROL.md) — U.S. export regulations (ECCN 5D002)
- [CLA.md](CLA.md) — Contributor License Agreement
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) — Community standards
- [NOTICE](NOTICE) — Third-party attributions

> **Export Notice:** This software contains encryption technology subject to
> U.S. Export Administration Regulations (EAR). See [EXPORT_CONTROL.md](EXPORT_CONTROL.md).

---

<div align="center">

**Built by [Roshan Dubey](https://github.com/rdship)**

Copyright 2024-2026 Roshan Dubey. All rights reserved.

If this project is useful, give it a star.

</div>

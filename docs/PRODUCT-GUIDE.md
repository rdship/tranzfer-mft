# TranzFer MFT — Product Guide

The complete product reference for TranzFer Managed File Transfer platform.

---

## Table of Contents

1. [What is TranzFer MFT?](#what-is-tranzfer-mft)
2. [Platform at a Glance](#platform-at-a-glance)
3. [Core Concepts](#core-concepts)
4. [How Files Move Through the Platform](#how-files-move-through-the-platform)
5. [Supported Protocols](#supported-protocols)
6. [Product Editions](#product-editions)
7. [Microservices Map](#microservices-map)
8. [User Interfaces](#user-interfaces)
9. [Client Tools](#client-tools)
10. [Security & Compliance](#security--compliance)
11. [AI Capabilities](#ai-capabilities)
12. [Integration Points](#integration-points)
13. [Deployment Options](#deployment-options)
14. [Quick Start Scenarios](#quick-start-scenarios)

---

## What is TranzFer MFT?

TranzFer MFT is an enterprise managed file transfer platform that lets organizations securely send, receive, and process files with trading partners, internal systems, and cloud services.

**In plain language:** It is a central hub where files arrive (via SFTP, FTP, HTTPS, AS2, or web upload), get processed (encrypted, compressed, screened, converted), and are delivered to their destination — with full tracking, audit trails, and AI-powered security.

**Key differentiators:**
- **AI-first security** — Real-time threat detection, anomaly scoring, and autonomous remediation
- **Protocol-agnostic** — SFTP, FTP, FTPS, HTTPS, AS2, AS4, and web uploads through a single platform
- **Zero-touch partner onboarding** — Partners can self-service without IT involvement
- **Complete audit trail** — Every file is tracked from ingress to delivery with checksums and blockchain anchoring
- **Modern architecture** — 20+ microservices, containerized, Kubernetes-ready

---

## Platform at a Glance

```
┌─────────────────────────────────────────────────────────────────────┐
│                        INTERNET / PARTNERS                         │
└───────────┬──────────────┬──────────────┬──────────────┬───────────┘
            │ SFTP (2222)  │ FTP (21)     │ HTTPS (443)  │ AS2 (8094)
            ▼              ▼              ▼              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  DMZ PROXY — AI-powered security layer                             │
│  Rate limiting │ Protocol detection │ IP reputation │ Geo analysis  │
└───────────┬──────────────┬──────────────┬──────────────┬───────────┘
            ▼              ▼              ▼              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  GATEWAY SERVICE — Routes connections to the right backend          │
└───────────┬──────────────┬──────────────┬──────────────┬───────────┘
            ▼              ▼              ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ SFTP Service │ │ FTP Service  │ │ FTP-Web Svc  │ │ AS2 Service  │
│ (SSH-based)  │ │ (FTP/FTPS)   │ │ (HTTP upload)│ │ (B2B EDI)    │
└──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
       └────────────────┴────────────────┴────────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │     ROUTING ENGINE      │
                    │  Track ID → Flow Match  │
                    │  → Step Execution       │
                    └────────────┬────────────┘
                                 │
        ┌──────────┬─────────────┼─────────────┬──────────┐
        ▼          ▼             ▼             ▼          ▼
  ┌──────────┐┌──────────┐┌──────────┐┌──────────┐┌──────────┐
  │ Encrypt  ││ Screen   ││ Classify ││ Compress ││ Convert  │
  │ AES/PGP  ││ OFAC     ││ PCI/PII  ││ GZIP/ZIP ││ EDI→JSON │
  └──────────┘└──────────┘└──────────┘└──────────┘└──────────┘
                                 │
                    ┌────────────▼────────────┐
                    │   DELIVERY / STORAGE    │
                    │  Forward │ Store │ Route │
                    └─────────────────────────┘
```

---

## Core Concepts

### Transfer Account
A named identity that connects to the platform via a specific protocol. Each account has a username, credentials (password and/or SSH key), a home directory, and permissions (read/write/delete). Accounts are created by admins or automatically via zero-touch onboarding.

### File Flow
A processing pipeline that runs when a file arrives. Flows consist of ordered steps like: encrypt → compress → screen → forward. Flows match files by filename pattern or source account. Multiple flows can run in priority order.

### Track ID
Every file that enters the platform gets a unique 12-character tracking ID (e.g., `TRZA3X5T3LUY`). This ID follows the file through every processing step, enabling end-to-end audit and journey tracking.

### Folder Mapping
Routes files from one account's folder to another account's folder. For example: when `partner-a` uploads to `/outbox/*.csv`, deliver to `internal-team`'s `/inbox/`.

### Server Instance
A deployed protocol server (e.g., sftp-1, ftp-2). Multiple instances of each protocol can run for high availability. Accounts can be pinned to specific instances.

### Security Profile
A reusable policy that defines TLS versions, cipher suites, SSH algorithms, and authentication requirements. Profiles are assigned to server instances.

### Delivery Endpoint
An external target where files are forwarded — an SFTP server, FTP server, HTTP API, or Kafka topic owned by a partner or internal system.

### Partner Agreement (SLA)
Defines delivery time expectations with a trading partner. The platform monitors compliance and alerts when SLAs are at risk.

---

## How Files Move Through the Platform

### Step 1 — Connection
A client connects via SFTP, FTP, HTTPS, or AS2. The DMZ proxy inspects the connection for threats (rate limits, IP reputation, protocol anomalies). If the connection passes, it is routed to the appropriate protocol service.

### Step 2 — Authentication
The protocol service authenticates the user (password, SSH key, or JWT token). Failed attempts are logged and tracked for brute-force detection.

### Step 3 — File Upload
The client uploads a file. The protocol service writes it to the user's home directory and notifies the routing engine.

### Step 4 — Track ID Assignment
The routing engine assigns a unique Track ID and creates a `FileTransferRecord` in the database.

### Step 5 — Flow Matching & Processing
The engine finds matching file flows based on filename pattern and source account. Each flow step runs in order:
- **ENCRYPT_AES / ENCRYPT_PGP** — Encrypt the file
- **COMPRESS_GZIP / COMPRESS_ZIP** — Compress the file
- **SCREEN** — Run OFAC sanctions screening on file contents
- **CLASSIFY** — Scan for PCI/PII/PHI sensitive data
- **VALIDATE** — Check CSV/EDI structure
- **CONVERT** — Transform EDI ↔ JSON/CSV
- **RENAME** — Apply filename pattern
- **SCRIPT** — Run custom processing script

### Step 6 — Delivery
Based on folder mappings and external destinations, the file is delivered:
- **Internal routing** — Copied to another account's inbox
- **External forwarding** — Sent via SFTP, FTP, FTPS, HTTP, Kafka, AS2, or AS4
- **Storage tiering** — Archived to hot/warm/cold storage

### Step 7 — Audit & Completion
The transfer record is updated with delivery timestamps, destination checksums, and integrity verification. Blockchain anchoring provides non-repudiation proof.

---

## Supported Protocols

| Protocol | Direction | Service | Port | Use Case |
|----------|-----------|---------|------|----------|
| **SFTP** | Inbound + Outbound | sftp-service | 2222 | Secure file transfer (SSH-based). Most common. |
| **FTP** | Inbound + Outbound | ftp-service | 21 | Legacy file transfer. Passive mode supported. |
| **FTPS** | Inbound + Outbound | ftp-service | 21 (explicit) / 990 (implicit) | FTP over TLS. |
| **HTTPS** | Inbound | ftp-web-service | 8083 | Web browser upload/download. REST API. |
| **AS2** | Inbound + Outbound | as2-service | 8094 | B2B EDI exchange (RFC 4130). MDN receipts. |
| **AS4** | Inbound + Outbound | as2-service | 8094 | Modern B2B (ebMS3/SOAP). |
| **HTTP/API** | Outbound | external-forwarder | — | Forward files to REST APIs. |
| **Kafka** | Outbound | external-forwarder | — | Stream files to Kafka topics. |

---

## Product Editions

| Feature | Standard | Professional | Enterprise |
|---------|----------|-------------|------------|
| **SFTP Server** | Yes | Yes | Yes |
| **FTP/FTPS Server** | Yes | Yes | Yes |
| **Web File Portal** | Yes | Yes | Yes |
| **Admin Dashboard** | Yes | Yes | Yes |
| **Encryption (AES/PGP)** | Yes | Yes | Yes |
| **Keystore Management** | Yes | Yes | Yes |
| **External Forwarding** | Yes | Yes | Yes |
| **Gateway Service** | Yes | Yes | Yes |
| **AS2/AS4 Protocol** | — | Yes | Yes |
| **Analytics & Predictions** | — | Yes | Yes |
| **EDI Converter** | — | Yes | Yes |
| **DMZ Proxy** | — | Yes | Yes |
| **Partner Portal** | — | Yes | Yes |
| **AI Engine** | — | — | Yes |
| **Sanctions Screening** | — | — | Yes |
| **Storage Manager** | — | — | Yes |
| **Max Concurrent Connections** | 10 | 100 | Unlimited |
| **Max Server Instances** | 1 | 5 | Unlimited |

**Trial:** 30-day free trial with Standard edition features. No license key required.

---

## Microservices Map

Every service is independently deployable. Here is what each one does and when you need it:

| Service | Port | Required? | What It Does |
|---------|------|-----------|-------------|
| [onboarding-api](../onboarding-api/) | 8080 | **Yes** | Central API — accounts, auth, transfers, cluster |
| [config-service](../config-service/) | 8084 | **Yes** | Flows, connectors, security profiles, scheduling |
| [sftp-service](../sftp-service/) | 2222/8081 | **Yes** | SFTP server (SSH-based file transfer) |
| [ftp-service](../ftp-service/) | 21/8082 | Optional | FTP/FTPS server |
| [ftp-web-service](../ftp-web-service/) | 8083 | Optional | Web browser file upload/download API |
| [gateway-service](../gateway-service/) | 8085 | Recommended | Routes connections to correct backend |
| [dmz-proxy](../dmz-proxy/) | 8088 | Recommended | AI-powered security proxy for all protocols |
| [ai-engine](../ai-engine/) | 8091 | Recommended | AI brain — classification, threat scoring, NLP |
| [encryption-service](../encryption-service/) | 8086 | Optional | AES-256 and PGP file encryption |
| [keystore-manager](../keystore-manager/) | 8093 | Optional | SSH keys, TLS certs, PGP keys lifecycle |
| [screening-service](../screening-service/) | 8092 | Optional | OFAC/EU/UN sanctions screening |
| [license-service](../license-service/) | 8089 | Optional | License validation and entitlements |
| [analytics-service](../analytics-service/) | 8090 | Optional | Metrics, dashboards, predictive scaling |
| [storage-manager](../storage-manager/) | 8094 | Optional | Tiered storage with deduplication |
| [edi-converter](../edi-converter/) | 8095 | Optional | EDI ↔ JSON/CSV format conversion |
| [external-forwarder](../external-forwarder-service/) | 8087 | Optional | Forward files to external SFTP/FTP/HTTP/Kafka |
| [as2-service](../as2-service/) | 8094 | Optional | AS2/AS4 B2B protocol handler |
| [ui-service](../ui-service/) | 3000 | Recommended | Admin dashboard (React) |
| [ftp-web-ui](../ftp-web-ui/) | 3001 | Optional | File browser for end users (React) |
| [partner-portal](../partner-portal/) | 3002 | Optional | Partner self-service portal (React) |
| [api-gateway](../api-gateway/) | 80/443 | Recommended | Nginx reverse proxy for all services |
| [shared](../shared/) | — | **Yes** | Shared library (entities, security, routing) |
| [cli](../cli/) | — | Optional | Admin CLI tool |
| [mft-client](../mft-client/) | — | Optional | Desktop sync client |

> Each service has its own README with detailed API endpoints, configuration, and setup instructions. Click the service name to navigate there.

---

## User Interfaces

### Admin Dashboard (ui-service)
Full-featured enterprise admin console with 34+ pages:
- **Dashboard** — Real-time transfer stats, protocol breakdown, alerts
- **Accounts** — Create, edit, enable/disable transfer accounts
- **Flows** — Design file processing pipelines (encrypt → compress → screen → deliver)
- **Servers** — Manage protocol server instances
- **Analytics** — Charts, predictions, scaling recommendations
- **Security** — Encryption keys, 2FA, keystore, screening results
- **EDI** — EDI format detection, conversion, validation
- **Monitoring** — Logs, activity feed, transfer journeys
- **Platform Config** — Environment-scoped settings, connectors, SLAs
- **API Console** — Test API endpoints directly from the browser

### File Browser (ftp-web-ui)
Simple, clean file manager for end users:
- Directory navigation with breadcrumbs
- Drag-and-drop upload with progress
- Download, delete, rename, create folders
- File type icons and size formatting

### Partner Portal (partner-portal)
Self-service portal for trading partners:
- Transfer dashboard with KPIs (success rate, SLA compliance)
- File tracking by Track ID
- Transfer history with filtering
- SSH key rotation and password changes
- Delivery receipts

---

## Client Tools

### CLI (cli)
Interactive command-line tool for administrators:
```bash
$ tranzfer login admin@company.com
$ tranzfer accounts list
$ tranzfer track TRZA3X5T3LUY
$ tranzfer install server    # Interactive installation wizard
```

### MFT Client (mft-client)
Cross-platform desktop sync client:
- **Auto-upload**: Drop files in `./outbox/` → automatically uploaded to server
- **Auto-download**: Server delivers files → appear in `./inbox/`
- **P2P transfers**: Send files directly to other connected clients
- **Self-contained**: Bundled with JRE 21 (no Java installation needed)
- **Platforms**: Linux (x86_64, ARM64), macOS (Intel, Apple Silicon), Windows

---

## Security & Compliance

### Authentication
- JWT tokens with 15-minute expiry
- Password policy: 8+ chars, uppercase, lowercase, digit, special character (PCI DSS 8.2)
- Brute force protection: 6 attempts → 30-minute lockout (PCI DSS 8.1.6)
- Two-factor authentication: TOTP (RFC 6238) with backup codes
- SSH public key authentication for SFTP

### Encryption
- **In transit**: TLS 1.2+ for HTTPS/FTPS, SSH for SFTP
- **At rest**: AES-256-GCM or PGP encryption via encryption-service
- **Credentials**: Encrypted in database with master key

### Network Security
- DMZ proxy with AI-powered threat detection
- Per-IP rate limiting (token bucket)
- Protocol-aware attack detection (banner grabs, port scans, SQL injection, path traversal)
- IP reputation scoring with automatic blocklisting
- Geo-anomaly detection (Tor, VPN, impossible travel)
- Graceful degradation when AI engine is unavailable

### Compliance
- **OFAC/EU/UN sanctions screening** — Screen file contents against sanctions lists
- **PCI DSS** — Detect and block unencrypted credit card numbers
- **PII/PHI detection** — SSN, passport, medical record detection
- **Blockchain anchoring** — Merkle tree proofs for non-repudiation
- **Complete audit trail** — Every action logged with user, timestamp, IP address

---

## AI Capabilities

The AI engine provides intelligence across three tiers:

### Tier 1 — Foundational Intelligence
- **Data classification**: Scan files for PCI, PII, PHI, financial data
- **Anomaly detection**: Detect volume spikes, unusual file sizes, off-hours transfers
- **Smart retry**: Classify failures (network, auth, storage, integrity) and recommend retry strategy
- **Content validation**: Verify CSV/TSV structure, detect schema drift
- **Threat scoring**: Score each transfer operation (0-100) based on 9 risk signals

### Tier 2 — Proxy Intelligence (DMZ Security Brain)
- **Real-time verdicts**: ALLOW / THROTTLE / CHALLENGE / BLOCK / BLACKHOLE
- **IP reputation**: Continuous 0-100 scoring with decay toward neutral
- **Protocol threat detection**: SSH brute force, FTP bounce, HTTP path probing, TLS downgrade
- **Connection pattern analysis**: Flood, slow-loris, banner grab, port scan, DDoS
- **Geo-anomaly detection**: Tor exit nodes, VPN detection, impossible travel

### Tier 3 — Advanced Intelligence
- **Partner profiling**: Learn each partner's normal behavior (hours, file sizes, patterns)
- **SLA prediction**: Forecast delivery drift and upcoming breaches
- **Auto-remediation**: Automatically retry failed transfers, clear stuck jobs, alert on non-retryable failures
- **Natural language Q&A**: Ask questions like "how many files failed today?" or "is partner-x on track?"
- **Self-driving infrastructure**: Pre-scale based on predicted traffic patterns
- **Intelligence network**: Privacy-preserving threat signal sharing

---

## Integration Points

### Inbound (files coming in)
- SFTP client → sftp-service
- FTP/FTPS client → ftp-service
- Web browser → ftp-web-ui → ftp-web-service
- AS2/AS4 partner → as2-service
- REST API → onboarding-api (Transfer API v2)
- MFT Client → sftp-service or ftp-service

### Outbound (files going out)
- External SFTP/FTP/FTPS server → external-forwarder-service
- HTTP/REST API → external-forwarder-service
- Kafka topic → external-forwarder-service
- AS2/AS4 partner → external-forwarder-service

### Webhooks & Alerts
- Slack, Microsoft Teams, PagerDuty, OpsGenie, ServiceNow
- Custom webhook URLs
- Configurable via config-service connectors

### Monitoring
- Prometheus metrics (all services expose `/actuator/metrics`)
- Spring Boot Actuator health endpoints
- RabbitMQ management UI (port 15672)

---

## Deployment Options

### Docker Compose (Development / Small Production)
```bash
# Full platform
docker compose up --build -d

# Minimal (SFTP only)
docker compose up -d postgres rabbitmq onboarding-api sftp-service config-service ui-service
```

### Kubernetes with Helm (Production)
```bash
helm install tranzfer ./helm/mft-platform -f values-production.yaml
```

### On-Premise
See [INSTALL-ON-PREMISE.md](INSTALL-ON-PREMISE.md) for bare-metal installation.

### Resource Requirements

| Topology | Containers | RAM | Use Case |
|----------|-----------|-----|----------|
| Minimal | 5 | ~2 GB | Development, testing |
| Standard | 11 | ~6 GB | Small team, low volume |
| Full | 22+ | ~12 GB | Production, all features |

---

## Quick Start Scenarios

### Scenario 1: "I just want secure file transfer"
```bash
# Start minimum services
docker compose up -d postgres rabbitmq onboarding-api sftp-service config-service ui-service

# Open admin dashboard
open http://localhost:3000

# Create an account and connect via SFTP
sftp -P 2222 myuser@localhost
```

### Scenario 2: "I need to exchange files with a trading partner"
1. Start the platform with gateway + DMZ proxy
2. Create a transfer account for the partner
3. Set up a folder mapping (partner outbox → internal inbox)
4. Configure a file flow (screen → encrypt → deliver)
5. Share connection details with the partner
6. Monitor via admin dashboard or partner portal

### Scenario 3: "I need EDI/AS2 B2B integration"
1. Start with AS2 service + EDI converter
2. Create an AS2 partnership configuration
3. Set up EDI conversion flow (X12 → JSON)
4. Partner sends AS2 messages
5. Platform converts, screens, and delivers

### Scenario 4: "I need compliance (PCI/OFAC)"
1. Enable screening-service and ai-engine
2. Create a file flow with SCREEN and CLASSIFY steps
3. Configure `AI_BLOCK_UNENCRYPTED_PCI=true`
4. Files with unencrypted credit card numbers are blocked
5. Files with sanctioned entity names are flagged/blocked

---

## Further Reading

| Document | Description |
|----------|-------------|
| [Architecture](ARCHITECTURE.md) | System design, data flows, deployment topologies |
| [Services Catalog](SERVICES.md) | Quick reference for all services |
| [API Reference](API-REFERENCE.md) | All REST endpoints across all services |
| [Configuration](CONFIGURATION.md) | Every environment variable and port |
| [Security Architecture](SECURITY-ARCHITECTURE.md) | DMZ proxy, AI security layer, threat model |
| [Developer Guide](DEVELOPER-GUIDE.md) | Build, test, debug, contribute |
| [Gap Analysis](GAP-ANALYSIS.md) | Known gaps and improvement priorities |

> Each microservice also has its own `README.md` with detailed API endpoints, configuration, and architecture. Navigate to any service directory on GitHub to see it.

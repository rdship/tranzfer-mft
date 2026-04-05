# TranzFer MFT — Demos & Quick Start Guides

> **First-principles, environment-agnostic demos for every microservice.** Each guide starts from zero — no assumptions about your OS, tools, or experience level. Pick a service, follow the steps, see it work.

---

## Before You Begin

**[Read the Prerequisites Guide (PREREQUISITES.md)](PREREQUISITES.md)** — It covers everything you need to install (Docker, Java, PostgreSQL, RabbitMQ) with step-by-step instructions for **Linux, macOS, and Windows**. Nothing will surprise you.

---

## Pick Your Starting Point

### Tier 1 — Zero Dependencies (Start Here)

These services need nothing except Docker (or Java). No database, no message broker. Perfect for a first demo.

| Service | What It Does | Demo Guide |
|---------|-------------|------------|
| **EDI Converter** | Convert EDI/CSV/XML/JSON between 66+ format pairs, detect formats, validate compliance, NLP-powered document creation | [EDI-CONVERTER.md](EDI-CONVERTER.md) |
| **DMZ Proxy** | AI-powered security reverse proxy with rate limiting, threat scoring, protocol detection | [DMZ-PROXY.md](DMZ-PROXY.md) |

### Tier 2 — Requires PostgreSQL

These services need a PostgreSQL database. Follow [Prerequisites Step 2](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it) first.

| Service | What It Does | Demo Guide |
|---------|-------------|------------|
| **Encryption Service** | AES-256-GCM and PGP encryption/decryption for files and data | [ENCRYPTION-SERVICE.md](ENCRYPTION-SERVICE.md) |
| **Keystore Manager** | Generate, store, and rotate SSH keys, TLS certs, AES keys, HMAC secrets | [KEYSTORE-MANAGER.md](KEYSTORE-MANAGER.md) |
| **Screening Service** | OFAC/EU/UN sanctions screening with fuzzy matching | [SCREENING-SERVICE.md](SCREENING-SERVICE.md) |
| **License Service** | RSA-signed license keys, trial generation, product catalog | [LICENSE-SERVICE.md](LICENSE-SERVICE.md) |
| **Storage Manager** | Tiered storage (hot/warm/cold/backup) with SHA-256 deduplication | [STORAGE-MANAGER.md](STORAGE-MANAGER.md) |
| **AS2 Service** | B2B AS2/AS4 protocol with MDN receipts and MIC integrity | [AS2-SERVICE.md](AS2-SERVICE.md) |
| **AI Engine** | File classification, threat scoring, NLP, anomaly detection, smart retry | [AI-ENGINE.md](AI-ENGINE.md) |

### Tier 3 — Requires PostgreSQL + RabbitMQ (Protocol Servers)

These are file transfer protocol servers. Follow [Prerequisites Steps 2 & 3](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it) first.

| Service | What It Does | Demo Guide |
|---------|-------------|------------|
| **Analytics Service** | Dashboards, predictions, time series, alert rules | [ANALYTICS-SERVICE.md](ANALYTICS-SERVICE.md) |
| **SFTP Service** | Production SFTP server with per-user isolation and SSH key auth | [SFTP-SERVICE.md](SFTP-SERVICE.md) |
| **FTP Service** | FTP/FTPS server with TLS, passive mode, virtual filesystem | [FTP-SERVICE.md](FTP-SERVICE.md) |
| **FTP-Web Service** | HTTP-based file operations (upload, download, list, mkdir) | [FTP-WEB-SERVICE.md](FTP-WEB-SERVICE.md) |

### Tier 4 — Platform Services (Requires PostgreSQL + RabbitMQ)

These orchestrate the platform. Best explored after understanding individual services.

| Service | What It Does | Demo Guide |
|---------|-------------|------------|
| **Gateway Service** | Protocol gateway with user-based routing to SFTP/FTP/HTTP backends | [GATEWAY-SERVICE.md](GATEWAY-SERVICE.md) |
| **External Forwarder** | Forward files via 7 protocols (SFTP, FTP, FTPS, HTTP, AS2, AS4, Kafka) | [EXTERNAL-FORWARDER.md](EXTERNAL-FORWARDER.md) |
| **Onboarding API** | Central API: auth, accounts, transfers, partner portal, multi-tenancy, 2FA | [ONBOARDING-API.md](ONBOARDING-API.md) |
| **Config Service** | File flows, connectors, security profiles, scheduling, platform settings | [CONFIG-SERVICE.md](CONFIG-SERVICE.md) |

---

## Recommended Learning Path

```
Start here (no dependencies):
  1. EDI Converter ──── Understand format processing
  2. DMZ Proxy ──────── Understand security layer

Add PostgreSQL:
  3. Encryption ─────── Understand data protection
  4. Keystore Manager ─ Understand key lifecycle
  5. Screening ──────── Understand compliance
  6. AI Engine ──────── Understand intelligence layer

Add RabbitMQ:
  7. SFTP Service ───── Understand file transfer protocol
  8. FTP-Web Service ── Understand HTTP file ops
  9. Onboarding API ─── Understand the central platform

Full platform:
  10. Config Service ── Understand orchestration
  11. Gateway Service ─ Understand routing
  12. Analytics ─────── Understand monitoring
```

---

## Demo Guide Structure

Every demo guide follows the same structure so you always know what to expect:

```
1. WHAT THIS SERVICE DOES        — Plain-English overview
2. WHAT YOU NEED                  — Exact prerequisites checklist
3. INSTALL & START                — Docker + Source methods, all OS
4. VERIFY IT'S RUNNING            — Health check commands
5. DEMO 1: Basic Operation        — Simplest possible use case
6. DEMO 2: Real-World Scenario    — Production-like use case
7. DEMO 3: Integration Pattern    — Connecting with other services or apps
8. USE CASES                      — When and why to use this service
9. ENVIRONMENT VARIABLES          — Every configurable option
10. TROUBLESHOOTING               — OS-specific common issues
```

---

## Quick Reference — All Services at a Glance

| Service | Port | Dependencies | Endpoints | Docker Image |
|---------|------|-------------|-----------|-------------|
| EDI Converter | 8095 | None | 26 | `mft-edi-converter` |
| DMZ Proxy | 8088 | None | 9 | `mft-dmz-proxy` |
| Encryption | 8086 | PG | 6 | `mft-encryption-service` |
| Keystore | 8093 | PG | 12 | `mft-keystore-manager` |
| Screening | 8092 | PG | 8 | `mft-screening-service` |
| License | 8089 | PG | 10 | `mft-license-service` |
| Storage | 8094 | PG | 8 | `mft-storage-manager` |
| AS2 | 8094 | PG | 5 | `mft-as2-service` |
| AI Engine | 8091 | PG | 40+ | `mft-ai-engine` |
| Analytics | 8090 | PG + RMQ | 8 | `mft-analytics-service` |
| SFTP | 2222/8081 | PG + RMQ | — | `mft-sftp-service` |
| FTP | 21/8082 | PG + RMQ | — | `mft-ftp-service` |
| FTP-Web | 8083 | PG + RMQ | 8 | `mft-ftp-web-service` |
| Gateway | 2220/8085 | PG + RMQ | 4 | `mft-gateway-service` |
| Forwarder | 8087 | PG + RMQ | 4 | `mft-forwarder-service` |
| Onboarding | 8080 | PG + RMQ | 30+ | `mft-onboarding-api` |
| Config | 8084 | PG + RMQ | 30+ | `mft-config-service` |

**PG** = PostgreSQL | **RMQ** = RabbitMQ

---

## Run the Full Platform (All Services)

If you want to see everything at once:

```bash
# Clone the repository
git clone https://github.com/rdship/tranzfer-mft.git
cd tranzfer-mft

# Start everything (pulls images, starts ~25 containers)
docker compose up -d

# Watch the startup (services will become healthy in 30-90 seconds)
docker compose ps

# When all services show "healthy":
# - Admin UI:        http://localhost:3000
# - Partner Portal:  http://localhost:3002
# - File Browser:    http://localhost:3001
# - API Gateway:     http://localhost:80
# - RabbitMQ UI:     http://localhost:15672
```

**Requirements for full platform:** 4+ CPU cores, 8+ GB RAM, 10+ GB disk.

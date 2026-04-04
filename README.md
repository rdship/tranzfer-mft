<div align="center">

# ⚡ TranzFer MFT

### AI-First Enterprise Managed File Transfer

[![CI — Build & Test](https://github.com/rdship/tranzfer-mft/actions/workflows/ci.yml/badge.svg)](https://github.com/rdship/tranzfer-mft/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21_LTS-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.2](https://img.shields.io/badge/Spring_Boot-3.2-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![React 18](https://img.shields.io/badge/React-18-61DAFB.svg)](https://react.dev/)
[![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-336791.svg)](https://www.postgresql.org/)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED.svg)](https://www.docker.com/)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-Helm-326CE5.svg)](https://kubernetes.io/)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

**20 microservices** · **26 admin UI pages** · **SFTP/FTP/FTPS/HTTPS** · **AI classification** · **OFAC screening** · **PCI compliant**

[Quick Start](#quick-start) · [Architecture](#architecture) · [Installation](docs/INSTALLATION.md) · [Capacity Planning](docs/CAPACITY-PLANNING.md) · [Contributing](CONTRIBUTING.md)

</div>

---

## What is TranzFer?

TranzFer is a **production-grade, AI-first Managed File Transfer platform** built with microservices. It's an open-source alternative to IBM Sterling, Axway, and GoAnywhere — but cloud-native, AI-powered, and designed for scale.

### Demo

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│   Client A                    TranzFer Server                   Client B    │
│   ────────                    ──────────────                    ────────    │
│                                                                             │
│   $ ./mft-client                                                            │
│   ╔══════════════════════════════════╗                                       │
│   ║ TranzFer MFT Client v2.1.0      ║                                       │
│   ╚══════════════════════════════════╝                                       │
│   Connected to SFTP server                                                  │
│                                                                             │
│   $ cp invoice.csv ./outbox/     ──SFTP──→  /inbox/invoice.csv              │
│                                                │                            │
│                                     Track ID: TRZA3X5T3LUY                  │
│                                                │                            │
│                                     ┌──────────▼─────────┐                  │
│                                     │ AI: No PCI data ✓  │                  │
│                                     │ Flow: COMPRESS_GZIP │                  │
│                                     │ Flow: RENAME        │                  │
│                                     │ Screen: OFAC CLEAR  │                  │
│                                     │ Route → Client B    │                  │
│                                     └──────────┬─────────┘                  │
│                                                │                            │
│                                  invoice.csv#TRZA3X5T3LUY                   │
│                                                │                            │
│                                                └──────────→  ./inbox/       │
│                                                              $ ls inbox/    │
│                                                              invoice.csv    │
│                                                              #TRZA3X5T3LUY  │
└─────────────────────────────────────────────────────────────────────────────┘
```

> Every file gets a **12-character Track ID** embedded in the filename.
> Full journey visible in Admin UI — every microservice touched, every step timed, every checksum verified.

---

## Key Features

| Category | Features |
|----------|---------|
| **Transfer** | SFTP, FTP, FTPS (TLS), HTTPS, P2P direct client-to-client |
| **Processing** | File flows: encrypt, decrypt, compress, decompress, rename, screen, script |
| **AI** | PCI/PII/PHI classification, anomaly detection, NLP admin terminal, smart retry, threat scoring, partner profiling, predictive SLA, auto-remediation |
| **Compliance** | PCI DSS (audit trail + HMAC), OFAC/AML sanctions screening (18,698 entries), SLA agreements |
| **Security** | Security profiles (SSH ciphers/MACs/KEX), password policy, brute-force lockout, centralized keystore |
| **Reliability** | Zero file loss (SHA-256 checksums, 10x retry, quarantine), guaranteed delivery |
| **Storage** | GPFS-style tiered storage (hot/warm/cold), parallel I/O, deduplication, AI-powered lifecycle |
| **Integration** | ServiceNow, PagerDuty, Slack, Teams, OpsGenie, generic webhooks |
| **Operations** | Scheduler (cron), activity monitor, scaling predictions, observability recommendations |
| **UI** | 26-page admin dashboard, user file portal, CLI terminal, white-labeling |
| **Deployment** | Docker Compose, Kubernetes Helm charts, cross-platform client (bundled JRE) |
| **Licensing** | Built-in license server, 30-day free trial, per-microservice licensing |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  TIER 1: INFRASTRUCTURE                                              │
│  PostgreSQL 16     RabbitMQ 3.13                                    │
├─────────────────────────────────────────────────────────────────────┤
│  TIER 2: CORE                                                        │
│  Onboarding API :8080     Config Service :8084                      │
├─────────────────────────────────────────────────────────────────────┤
│  TIER 3: TRANSFER                                                    │
│  SFTP :2222   FTP :21   FTP-Web :8083   Gateway :2220   DMZ :8088  │
├─────────────────────────────────────────────────────────────────────┤
│  TIER 4: INTELLIGENCE                                                │
│  Encryption :8086    Forwarder :8087    AI Engine :8091              │
│  Screening :8092     Analytics :8090    Storage :8094                │
│  Keystore :8093                                                      │
├─────────────────────────────────────────────────────────────────────┤
│  TIER 5: FRONTENDS                                                   │
│  Admin UI :3000   File Portal :3001   License :8089                 │
└─────────────────────────────────────────────────────────────────────┘
```

**20 microservices** · Install only what you need · [6 deployment recipes](docs/INSTALLATION.md#installation-recipes)

---

## Quick Start

```bash
# Clone
git clone https://github.com/rdship/tranzfer-mft.git
cd tranzfer-mft

# Build (requires Java 21 + Maven)
mvn clean package -DskipTests

# Start everything (requires Docker)
docker compose up --build -d

# Create admin user
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@company.com","password":"Str0ng!Pass"}'

# Open Admin UI
open http://localhost:3000
```

**Selective install** (only what you need):
```bash
# SFTP + Screening only (7 services)
docker compose up -d postgres rabbitmq onboarding-api sftp-service config-service screening-service admin-ui
```

---

## Microservices

| Service | Port | Purpose |
|---------|:----:|---------|
| onboarding-api | 8080 | Auth, accounts, folder mappings, P2P, journey tracker, CLI |
| config-service | 8084 | Flows, servers, security profiles, scheduler, SLA, connectors |
| sftp-service | 2222 | SFTP server (Apache MINA SSHD) |
| ftp-service | 21 | FTP/FTPS server (Apache FTPServer) |
| ftp-web-service | 8083 | REST API for HTTP file upload/download |
| gateway-service | 2220 | SFTP/FTP protocol gateway |
| dmz-proxy | 8088 | Stateless TCP proxy for DMZ |
| encryption-service | 8086 | AES-256 / PGP encryption |
| external-forwarder | 8087 | Forward to external SFTP/FTP/Kafka |
| license-service | 8089 | Per-microservice licensing + trial |
| analytics-service | 8090 | Metrics, scaling predictions, alerts |
| ai-engine | 8091 | Classification, NLP, anomaly, threat scoring, recommendations |
| screening-service | 8092 | OFAC/AML sanctions (18,698 entries from US Treasury) |
| keystore-manager | 8093 | Central key/certificate management |
| storage-manager | 8094 | GPFS-style tiered storage + parallel I/O |
| admin-ui | 3000 | React dashboard (26 pages) |
| ftp-web-ui | 3001 | User file portal |
| mft-client | — | Cross-platform client (bundled JRE, no install) |

---

## Documentation

| Document | Description |
|----------|-------------|
| [INSTALLATION.md](docs/INSTALLATION.md) | Complete install guide: hardware, DB, network, firewall, 6 recipes |
| [CAPACITY-PLANNING.md](docs/CAPACITY-PLANNING.md) | Scale to 100K partners / 10M files/day (~$40K/month) |
| [PCI-DSS-COMPLIANCE.md](docs/PCI-DSS-COMPLIANCE.md) | PCI DSS control mapping |
| [INSTALL-KUBERNETES.md](docs/INSTALL-KUBERNETES.md) | Helm chart deployment |
| [INSTALL-ON-PREMISE.md](docs/INSTALL-ON-PREMISE.md) | Bare metal / Docker Compose |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 3.2 |
| Database | PostgreSQL 16 |
| Messaging | RabbitMQ 3.13 |
| SFTP | Apache MINA SSHD 2.12 |
| FTP | Apache FTPServer 1.2 |
| Encryption | Bouncy Castle 1.77 |
| Frontend | React 18, Vite 5, TailwindCSS 3, Recharts |
| Container | Docker, Kubernetes (Helm) |
| AI | Claude API (optional), regex classification, Jaro-Winkler matching |
| Monitoring | Micrometer + Prometheus + OpenTelemetry |

---

## License

[MIT](LICENSE) — use it however you want.

---

<div align="center">

**Built by [Roshan Dubey](https://github.com/rdship)**

If this project is useful, give it a ⭐

</div>

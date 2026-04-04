<div align="center">

# ⚡ TranzFer MFT Platform

### Enterprise Managed File Transfer — Open Source

A production-grade, microservices-based Managed File Transfer (MFT) platform built with **Java 21**, **Spring Boot 3**, **React 18**, and **PostgreSQL**.

Designed to handle **500M+ file transfers/day** across global deployments.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.2](https://img.shields.io/badge/Spring_Boot-3.2-green.svg)](https://spring.io/projects/spring-boot)

</div>

---

## What is TranzFer?

TranzFer is a complete MFT platform that lets organizations securely transfer files between internal systems, external partners, and cloud services using **SFTP**, **FTP**, and **HTTPS** protocols.

Think of it as an open-source alternative to IBM Sterling, Axway, or GoAnywhere — but cloud-native and microservices-based.

### Key Features

| Feature | Description |
|---------|-------------|
| **Multi-Protocol** | SFTP, FTP, FTP-over-HTTP (web upload/download) |
| **File Routing** | Folder-based routing with regex matching, encryption, and forwarding |
| **Security Profiles** | Configurable SSH ciphers, MACs, KEX algorithms, TLS settings |
| **Encryption** | AES-256 and PGP encryption/decryption at rest and in transit |
| **DMZ Proxy** | Stateless reverse proxy for DMZ deployments (no database access) |
| **Gateway** | Protocol gateway for proxying SFTP/FTP through a single entry point |
| **Analytics** | Real-time transfer metrics, success rates, latency tracking |
| **Scaling Predictions** | ML-based traffic prediction for Kubernetes auto-scaling |
| **Licensing** | Built-in license server with trial mode (30-day free) |
| **Admin Dashboard** | Full React UI for administration and monitoring |
| **User File Portal** | Web-based file browser for end users (drag & drop) |
| **White-Labeling** | Custom logo, company name, and brand colors |
| **Helm Charts** | Production-ready Kubernetes deployment |
| **OTEL Support** | OpenTelemetry metrics and traces export |
| **Stateless Services** | All configs externalized — recreate environments from env vars |

---

## Architecture

```
                                    ┌─────────────────────────────────────────┐
                                    │           INTERNET / PARTNERS           │
                                    └────────────────────┬────────────────────┘
                                                         │
                                    ┌────────────────────▼────────────────────┐
                                    │             DMZ PROXY (8088)            │
                                    │     Stateless TCP reverse proxy         │
                                    │     No DB — isolated by design          │
                                    └──────┬──────────┬──────────┬────────────┘
                                           │          │          │
                              SFTP:2222  ──┘   FTP:21 ┘   HTTPS:443
                                           │          │          │
                           ┌───────────────▼──────────▼──────────▼────────────┐
                           │              GATEWAY SERVICE (8085)              │
                           │       Protocol routing & authentication          │
                           └──────┬───────────────────────────┬───────────────┘
                                  │                           │
                    ┌─────────────▼──────────┐  ┌─────────────▼──────────┐
                    │   SFTP SERVICE (8081)   │  │   FTP SERVICE (8082)   │
                    │   Apache MINA SSHD      │  │   Apache FTPServer     │
                    │   Port 2222             │  │   Port 21              │
                    └─────────────┬──────────┘  └─────────────┬──────────┘
                                  │                           │
                    ┌─────────────▼───────────────────────────▼──────────┐
                    │              ROUTING ENGINE (shared library)        │
                    │    Folder mappings → Encryption → Forwarding        │
                    └──────┬──────────────┬───────────────┬──────────────┘
                           │              │               │
              ┌────────────▼───┐  ┌───────▼────────┐  ┌──▼──────────────────┐
              │  ENCRYPTION    │  │  FTP-WEB        │  │  EXTERNAL FORWARDER │
              │  SERVICE       │  │  SERVICE        │  │  SERVICE            │
              │  (8086)        │  │  (8083)         │  │  (8087)             │
              │  AES/PGP       │  │  REST file ops  │  │  SFTP/FTP/Kafka     │
              └────────────────┘  └─────────────────┘  └─────────────────────┘

     ┌────────────────────────────────────────────────────────────────────────┐
     │                         PLATFORM SERVICES                              │
     ├──────────────────┬──────────────────┬──────────────────────────────────┤
     │  ONBOARDING API  │  CONFIG SERVICE  │  LICENSE SERVICE  │  ANALYTICS  │
     │  (8080)          │  (8084)          │  (8089)           │  (8090)     │
     │  Auth, Accounts  │  Server configs  │  Trial & License  │  Metrics    │
     │  Folder mappings │  Security profs  │  Per-service keys │  Predictions│
     │  User mgmt       │  Ext. dests      │  RSA-2048 signed  │  Alerts     │
     └──────────────────┴──────────────────┴──────────────────┴─────────────┘

     ┌────────────────────────────────────────────────────────────────────────┐
     │                           FRONTENDS                                    │
     ├───────────────────────────────┬────────────────────────────────────────┤
     │  ADMIN UI (3000)             │  FILE PORTAL (3001)                     │
     │  React 18 + TailwindCSS      │  React 18 — User file browser          │
     │  16 pages: Dashboard,        │  Drag & drop upload, download           │
     │  Analytics, Predictions,     │  White-label ready                      │
     │  License, Security Profiles  │                                         │
     └───────────────────────────────┴────────────────────────────────────────┘

     ┌────────────────────────────────────────────────────────────────────────┐
     │                         INFRASTRUCTURE                                 │
     ├──────────────────────────────┬─────────────────────────────────────────┤
     │  PostgreSQL 16               │  RabbitMQ 3.13                          │
     │  16 tables, JSONB support    │  Account events, routing events         │
     └──────────────────────────────┴─────────────────────────────────────────┘
```

---

## Microservices

| Service | Port | Description | Stateless? |
|---------|------|-------------|:----------:|
| `onboarding-api` | 8080 | Auth, accounts, folder mappings, user management | ✅ |
| `sftp-service` | 2222/8081 | SFTP server (Apache MINA SSHD 2.12) | ✅* |
| `ftp-service` | 21/8082 | FTP server (Apache FTPServer 1.2) | ✅* |
| `ftp-web-service` | 8083 | REST API for file upload/download | ✅* |
| `config-service` | 8084 | Server configs, security profiles, encryption keys | ✅ |
| `gateway-service` | 2220/2121/8085 | SFTP/FTP protocol gateway & router | ✅ |
| `encryption-service` | 8086 | AES-256 and PGP encryption/decryption | ✅ |
| `external-forwarder-service` | 8087 | Forward files to external SFTP/FTP/Kafka | ✅ |
| `dmz-proxy` | 8088 | Stateless TCP proxy for DMZ isolation | ✅ |
| `license-service` | 8089 | License validation, trial management | ✅ |
| `analytics-service` | 8090 | Metrics aggregation, predictions, alerts | ✅ |
| `admin-ui` | 3000 | Admin dashboard (React) | ✅ |
| `ftp-web-ui` | 3001 | User file portal (React) | ✅ |

> *\*Requires shared storage (PVC) for file data in clustered mode*

---

## Quick Start (Docker Compose)

### Prerequisites

- **Java 21** (for building)
- **Maven 3.8+**
- **Docker** and **Docker Compose**
- **Node.js 20+** (for UI development only)

### 1. Clone the repo

```bash
git clone https://github.com/rdship/tranzfer-mft.git
cd tranzfer-mft
```

### 2. Build all services

```bash
# Set JAVA_HOME if needed (macOS example)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

mvn clean package -DskipTests
```

### 3. Start everything

```bash
docker compose up --build -d
```

This starts **15 containers**: PostgreSQL, RabbitMQ, 11 Java services, and 2 React UIs.

### 4. Create an admin user

```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@yourcompany.com","password":"YourPassword@123"}'

# Promote to admin
docker exec mft-postgres psql -U postgres -d filetransfer \
  -c "UPDATE users SET role='ADMIN' WHERE email='admin@yourcompany.com';"
```

### 5. Access the UIs

| URL | Purpose |
|-----|---------|
| http://localhost:3000 | Admin Dashboard |
| http://localhost:3001 | User File Portal |
| http://localhost:15672 | RabbitMQ Management (guest/guest) |

---

## Install Individual Microservices

You don't have to install everything. Each microservice can run independently:

### Minimum viable setup

| What you need | Services required |
|---|---|
| **SFTP server only** | `postgres` + `rabbitmq` + `onboarding-api` + `sftp-service` |
| **FTP server only** | `postgres` + `rabbitmq` + `onboarding-api` + `ftp-service` |
| **Web file transfer** | `postgres` + `rabbitmq` + `onboarding-api` + `ftp-web-service` + `ftp-web-ui` |
| **Full platform** | All services |

### Run a single service

```bash
# Build just what you need
mvn clean package -DskipTests -pl shared,onboarding-api,sftp-service

# Run with environment variables
export DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer
export RABBITMQ_HOST=localhost
export JWT_SECRET=your-256-bit-secret-key-here-at-least-32chars

java -jar sftp-service/target/sftp-service-*.jar
```

### Docker (single service)

```bash
docker run -d \
  -e DATABASE_URL=jdbc:postgresql://your-db:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=changeme \
  -e RABBITMQ_HOST=your-rabbitmq \
  -e JWT_SECRET=your-secret \
  -p 2222:2222 -p 8081:8081 \
  mft/sftp-service:latest
```

---

## Kubernetes Installation (Helm)

### Prerequisites

- Kubernetes 1.25+
- Helm 3.10+
- Ingress controller (nginx recommended)
- PostgreSQL (managed or in-cluster)
- RabbitMQ (managed or in-cluster)

### 1. Build and push images

```bash
# Build all service images
for svc in onboarding-api sftp-service ftp-service ftp-web-service \
  config-service gateway-service encryption-service \
  external-forwarder-service dmz-proxy license-service \
  analytics-service admin-ui ftp-web-ui; do
  docker build -t your-registry.com/mft/${svc}:2.0.0 ./${svc}
  docker push your-registry.com/mft/${svc}:2.0.0
done
```

### 2. Install with Helm

```bash
# Minimal install (uses in-cluster PostgreSQL and RabbitMQ)
helm install mft-platform ./helm/mft-platform \
  --namespace mft --create-namespace \
  --set global.jwtSecret="$(openssl rand -base64 32)" \
  --set global.controlApiKey="$(openssl rand -hex 16)" \
  --set global.imageRegistry="your-registry.com/"

# Production install (external managed DB)
helm install mft-platform ./helm/mft-platform \
  --namespace mft --create-namespace \
  -f helm/values-production.yaml
```

### 3. Select which services to install

Every service can be enabled/disabled in `values.yaml`:

```yaml
# Only install SFTP + minimal management
sftpService:
  enabled: true
  replicaCount: 3
ftpService:
  enabled: false        # Skip FTP
encryptionService:
  enabled: false        # Skip encryption
externalForwarderService:
  enabled: false        # Skip external forwarding
```

### 4. Ingress

The Helm chart creates an Ingress with three hosts:

```yaml
ingress:
  enabled: true
  hosts:
    admin: admin.mft.yourcompany.com    # Admin dashboard
    portal: files.mft.yourcompany.com   # User file portal
    api: api.mft.yourcompany.com        # REST APIs
  tls:
    enabled: true
    secretName: mft-tls
```

---

## Multi-Cluster Deployment

TranzFer supports multi-cluster/multi-region deployments:

```
┌──────────────────────┐     ┌──────────────────────┐     ┌──────────────────────┐
│    CLUSTER: US-EAST  │     │    CLUSTER: EU-WEST   │     │    CLUSTER: APAC     │
│                      │     │                       │     │                      │
│  ┌─── SFTP ×3 ────┐ │     │  ┌─── SFTP ×2 ────┐  │     │  ┌─── SFTP ×5 ────┐ │
│  ├─── FTP ×2 ─────┤ │     │  ├─── FTP ×1 ─────┤  │     │  ├─── FTP ×3 ─────┤ │
│  ├─── Gateway ×2 ─┤ │     │  ├─── Gateway ×2 ─┤  │     │  ├─── Gateway ×3 ─┤ │
│  ├─── Admin UI ───┤ │     │  ├─── Admin UI ───┤  │     │  ├─── Admin UI ───┤ │
│  ├─── Onboarding ─┤ │     │  ├─── Onboarding ─┤  │     │  ├─── Onboarding ─┤ │
│  └─── Analytics ──┘ │     │  └─── Analytics ──┘  │     │  └─── Analytics ──┘ │
│                      │     │                       │     │                      │
│  PostgreSQL (RDS)    │     │  PostgreSQL (RDS)     │     │  PostgreSQL (RDS)    │
│  RabbitMQ (Amazon)   │     │  RabbitMQ (CloudAMQP) │     │  RabbitMQ (Amazon)   │
└──────────────────────┘     └───────────────────────┘     └──────────────────────┘
         │                              │                            │
         └──────────────────────────────┼────────────────────────────┘
                                        │
                            ┌───────────▼──────────┐
                            │   SHARED (optional)   │
                            │   License Server      │
                            │   Central DB replica  │
                            └──────────────────────┘
```

### How it works

1. **Each cluster is independent** — has its own PostgreSQL, RabbitMQ, and full set of services
2. **Cluster ID** — each cluster registers with a unique `CLUSTER_ID` environment variable
3. **Service Registry** — services self-register on startup, enabling admin UI to show all nodes
4. **Shared License Server** — optional: one license server can validate all clusters
5. **Replica counts per cluster** — scale SFTP in APAC (high traffic region) independently

### Deploy a new cluster

```bash
# Cluster 1: US East
helm install mft-us-east ./helm/mft-platform \
  --namespace mft-us \
  --set global.clusterId="us-east-1" \
  --set sftpService.replicaCount=3

# Cluster 2: EU West
helm install mft-eu-west ./helm/mft-platform \
  --namespace mft-eu \
  --set global.clusterId="eu-west-1" \
  --set sftpService.replicaCount=2

# Cluster 3: APAC (heavy SFTP traffic)
helm install mft-apac ./helm/mft-platform \
  --namespace mft-apac \
  --set global.clusterId="apac-1" \
  --set sftpService.replicaCount=5 \
  --set sftpService.autoscaling.enabled=true
```

---

## Environment Variables

All services are **stateless** — configuration is entirely via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL connection URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ hostname |
| `RABBITMQ_PORT` | `5672` | RabbitMQ port |
| `JWT_SECRET` | *(dev default)* | **Must change in production** — 256-bit secret |
| `CONTROL_API_KEY` | `internal_control_secret` | Inter-service auth key |
| `CLUSTER_ID` | `default-cluster` | Unique cluster identifier |
| `CLUSTER_HOST` | `localhost` | This node's hostname |
| `ENCRYPTION_MASTER_KEY` | *(dev default)* | 256-bit hex key for AES encryption |
| `LICENSE_ADMIN_KEY` | *(dev default)* | License service admin API key |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | For external Kafka forwarding |

> **Security note**: Default values are for development only. All secrets MUST be changed in production. See `.env.production` for a template.

---

## Licensing

TranzFer uses a built-in license server with **three modes**:

| Mode | Cost | Limits |
|------|------|--------|
| **Trial** | Free (30 days) | 1 instance/service, 10 concurrent connections |
| **Standard** | $999/yr | 5 accounts, 1 replica/service |
| **Professional** | $4,999/yr | Unlimited accounts, 5 replicas, analytics |
| **Enterprise** | Custom | Unlimited everything, HA, 24/7 support |

**Trial mode activates automatically** — no license key needed. Just start the platform and use it for 30 days.

---

## Project Structure

```
tranzfer-mft/
├── shared/                     # Shared JPA entities, repositories, DTOs
├── onboarding-api/             # Auth, accounts, folder mappings
├── sftp-service/               # SFTP server (Apache MINA SSHD)
├── ftp-service/                # FTP server (Apache FTPServer)
├── ftp-web-service/            # REST API for file operations
├── config-service/             # Server config, security profiles
├── gateway-service/            # SFTP/FTP protocol gateway
├── encryption-service/         # AES/PGP encryption
├── external-forwarder-service/ # Forward to external SFTP/FTP/Kafka
├── dmz-proxy/                  # Stateless DMZ reverse proxy
├── license-service/            # License validation & trial management
├── analytics-service/          # Metrics, predictions, alerts
├── cli/                        # Spring Shell CLI admin tool
├── admin-ui/                   # React admin dashboard (16 pages)
├── ftp-web-ui/                 # React user file portal
├── helm/                       # Kubernetes Helm charts
│   ├── mft-platform/           # Main chart
│   └── values-production.yaml  # Production overrides
├── docker-compose.yml          # Local development
├── otel-config.yml             # OpenTelemetry collector config
├── .env.production             # Production env var template
└── pom.xml                     # Maven multi-module root
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 3.2.3 |
| Database | PostgreSQL 16 |
| Messaging | RabbitMQ 3.13 |
| SFTP | Apache MINA SSHD 2.12 |
| FTP | Apache FTPServer 1.2 |
| Proxy | Netty 4.1 |
| Encryption | Bouncy Castle 1.77 (PGP + AES) |
| Auth | JWT (JJWT 0.12.6) |
| Frontend | React 18, Vite 5, TailwindCSS 3, Recharts |
| Data Fetch | TanStack Query v5 |
| Container | Docker, Kubernetes |
| Orchestration | Helm 3 |
| Monitoring | Micrometer + Prometheus + OpenTelemetry |
| Build | Maven, npm |

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

This project is licensed under the Apache License 2.0 — see [LICENSE](LICENSE) for details.

---

<div align="center">

**Built with determination by [Roshan Dubey](https://github.com/rdship)**

</div>

# TranzFer MFT — Architecture Guide

This document explains how TranzFer's 20 microservices fit together, how data flows between them, and how to deploy subsets of the platform.

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Tier Architecture](#tier-architecture)
3. [Service Communication](#service-communication)
4. [Data Flow: File Transfer](#data-flow-file-transfer)
5. [Data Flow: Security (DMZ)](#data-flow-security-dmz)
6. [Database Architecture](#database-architecture)
7. [Messaging Architecture](#messaging-architecture)
8. [Deployment Topologies](#deployment-topologies)
9. [Network Diagram](#network-diagram)

---

## System Overview

TranzFer is a **modular, microservice-based** Managed File Transfer platform. Each service is independently deployable. You install only what you need.

```
                    ┌──────────────────────────────────────────────┐
                    │              EXTERNAL CLIENTS                 │
                    │   SFTP clients, FTP clients, Web browsers,   │
                    │   REST APIs, AS2 partners, MFT Client CLI    │
                    └───────────────┬──────────────────────────────┘
                                    │
                    ┌───────────────▼──────────────────────────────┐
                    │         TIER 1: SECURITY BOUNDARY             │
                    │                                               │
                    │   DMZ Proxy (:2222, :21, :443, :8088)        │
                    │   ├── AI-powered threat detection             │
                    │   ├── Rate limiting (per-IP + global)         │
                    │   ├── Protocol detection (SSH/FTP/HTTP/TLS)   │
                    │   └── Verdict caching + graceful degradation  │
                    │                                               │
                    │   API Gateway — Nginx (:80, :443)             │
                    │   └── Routes HTTP/REST traffic                │
                    └───────────────┬──────────────────────────────┘
                                    │
                    ┌───────────────▼──────────────────────────────┐
                    │         TIER 2: PROTOCOL GATEWAY              │
                    │                                               │
                    │   Gateway Service (:2220 SFTP, :2122 FTP)    │
                    │   ├── User-aware routing                      │
                    │   ├── Multi-instance load balancing            │
                    │   └── Legacy server fallback                  │
                    └───────────────┬──────────────────────────────┘
                                    │
                    ┌───────────────▼──────────────────────────────┐
                    │         TIER 3: TRANSFER SERVICES             │
                    │                                               │
                    │   SFTP Service (:2222)    FTP Service (:21)   │
                    │   FTP-Web Service (:8083) AS2 Service (:8094) │
                    │                                               │
                    │   Each receives files, generates Track IDs,   │
                    │   publishes events to RabbitMQ                │
                    └───────────────┬──────────────────────────────┘
                                    │
                    ┌───────────────▼──────────────────────────────┐
                    │         TIER 4: BUSINESS LOGIC                │
                    │                                               │
                    │   Onboarding API (:8080) — Accounts, auth     │
                    │   Config Service (:8084) — Flows, rules       │
                    │   AI Engine (:8091)      — Classification     │
                    │   Screening (:8092)      — OFAC/sanctions     │
                    │   Encryption (:8086)     — AES/PGP            │
                    │   Analytics (:8090)      — Metrics            │
                    │   Forwarder (:8087)      — External delivery  │
                    │   Keystore (:8093)       — PKI management     │
                    │   Storage (:8094)        — Tiered storage     │
                    │   EDI Converter (:8095)  — Format conversion  │
                    │   License (:8089)        — License mgmt       │
                    └───────────────┬──────────────────────────────┘
                                    │
                    ┌───────────────▼──────────────────────────────┐
                    │         TIER 5: INFRASTRUCTURE                │
                    │                                               │
                    │   PostgreSQL (:5432)  — Shared database       │
                    │   RabbitMQ (:5672)    — Event messaging       │
                    └──────────────────────────────────────────────┘

                    ┌──────────────────────────────────────────────┐
                    │         FRONTENDS                             │
                    │                                               │
                    │   Admin UI (:3000)       — 34-page dashboard  │
                    │   FTP Web UI (:3001)     — File browser       │
                    │   Partner Portal (:3002) — Partner self-serve │
                    └──────────────────────────────────────────────┘
```

---

## Tier Architecture

### Tier 1: Security Boundary (Required for external access)

| Service | Port(s) | Role |
|---------|---------|------|
| **dmz-proxy** | 2222, 21, 443, 8088 | AI-powered reverse proxy. Sits in DMZ. Relays TCP traffic. |
| **api-gateway** | 80, 443 | Nginx reverse proxy for all HTTP/REST traffic. |

**Key point:** The DMZ proxy handles protocol traffic (SFTP, FTP, FTPS). The API gateway handles HTTP/REST traffic. They serve different purposes.

### Tier 2: Protocol Gateway (Required for multi-instance)

| Service | Port(s) | Role |
|---------|---------|------|
| **gateway-service** | 2220 (SFTP), 2122 (FTP), 8085 (API) | Routes protocol connections to the correct backend instance based on username. |

**Key point:** If you run a single instance of each protocol service, you can skip the gateway and point DMZ proxy directly at the services.

### Tier 3: Transfer Services (Pick the protocols you need)

| Service | Port(s) | Protocol | Role |
|---------|---------|----------|------|
| **sftp-service** | 2222, 8081 | SFTP (SSH) | Receives/sends files over SFTP |
| **ftp-service** | 21, 21000-21010, 8082 | FTP/FTPS | Receives/sends files over FTP |
| **ftp-web-service** | 8083 | HTTP/HTTPS | Web-based file upload/download API |
| **as2-service** | 8094 | AS2 (HTTP) | B2B file exchange via AS2 protocol |

### Tier 4: Business Logic (Install what you need)

| Service | Port | What it adds |
|---------|------|-------------|
| **onboarding-api** | 8080 | User accounts, authentication, folder mappings, P2P transfers |
| **config-service** | 8084 | File flows, connectors, security profiles, scheduler, SLA |
| **ai-engine** | 8091 | AI classification, anomaly detection, NLP, proxy intelligence |
| **screening-service** | 8092 | OFAC/EU/UN sanctions screening |
| **encryption-service** | 8086 | AES-256 and PGP encryption/decryption |
| **analytics-service** | 8090 | Transfer metrics, predictions, dashboards |
| **external-forwarder** | 8087 | Forward files to external SFTP/FTP/Kafka |
| **keystore-manager** | 8093 | Centralized PKI, certificates, SSH keys |
| **storage-manager** | 8094 | Tiered storage (hot/warm/cold), dedup, parallel I/O |
| **edi-converter** | 8095 | EDI format translation (X12, EDIFACT, HL7, SWIFT) |
| **license-service** | 8089 | License validation, 30-day trial |

### Tier 5: Infrastructure (Required)

| Service | Port | Role |
|---------|------|------|
| **PostgreSQL** | 5432 | Shared relational database |
| **RabbitMQ** | 5672, 15672 | Event messaging between services |

---

## Service Communication

Services communicate through three mechanisms:

### 1. REST/HTTP (Synchronous) — Unified Service Client Framework

All inter-service HTTP calls use the **unified service client framework** in the `shared` module. Every microservice can call any other microservice through typed client classes that provide:

- **Centralized URL configuration** via `platform.services.*` in `application.yml`
- **Common authentication** via `X-Internal-Key` header (automatic)
- **Consistent error handling** — fail-fast, graceful degradation, or swallow-and-log per client
- **Health check support** — `client.isHealthy()` on any service
- **Environment variable overrides** — e.g. `ENCRYPTION_SERVICE_URL=http://custom:8086`

**Available service clients** (all in `com.filetransfer.shared.client`):

| Client Class | Target Service | Port | Error Strategy |
|---|---|---|---|
| `EncryptionServiceClient` | Encryption Service | 8086 | Fail-fast |
| `ScreeningServiceClient` | Screening Service | 8092 | Fail-fast |
| `AnalyticsServiceClient` | Analytics Service | 8090 | Graceful degradation |
| `KeystoreServiceClient` | Keystore Manager | 8093 | Swallow-and-log |
| `StorageServiceClient` | Storage Manager | 8096 | Fail-fast (store/retrieve) |
| `EdiConverterClient` | EDI Converter | 8095 | Fail-fast |
| `ConfigServiceClient` | Config Service | 8084 | Fail-fast |
| `ForwarderServiceClient` | External Forwarder | 8087 | Fail-fast |
| `GatewayServiceClient` | Gateway Service | 8085 | Graceful degradation |
| `As2ServiceClient` | AS2 Service | 8094 | Fail-fast |
| `AiEngineClient` | AI Engine | 8091 | Graceful degradation |
| `LicenseServiceClient` | License Service | 8089 | Graceful + caching |
| `DmzProxyClient` | DMZ Proxy | 8088 | Fail-fast (config) |
| `OnboardingApiClient` | Onboarding API | 8080 | Fail-fast |

**Usage example** — inject any client into your service:

```java
@Service
@RequiredArgsConstructor
public class MyService {
    private final EncryptionServiceClient encryptionService;
    private final ScreeningServiceClient screeningService;
    private final StorageServiceClient storageService;

    public void processFile(Path file, String trackId) {
        // Screen the file
        Map<String, Object> scanResult = screeningService.scanFile(file, trackId, "acme");

        // Encrypt credentials
        String encrypted = encryptionService.encryptCredential("secret");

        // Store in tiered storage
        storageService.store(file, trackId, "acme");
    }
}
```

**Legacy diagram** (still applies for understanding the flow):

```
Admin UI  ──HTTP──►  Onboarding API (:8080)
Admin UI  ──HTTP──►  Config Service (:8084)
Admin UI  ──HTTP──►  Analytics Service (:8090)
DMZ Proxy ──HTTP──►  AI Engine (:8091)        [verdict queries]
Services  ──HTTP──►  Encryption Service (:8086) [encrypt/decrypt]
Services  ──HTTP──►  Screening Service (:8092)  [compliance checks]
```

### 2. RabbitMQ (Asynchronous)

Used for event-driven communication. Fire-and-forget.

```
Exchange: file-transfer.events

SFTP Service ──publish──► RabbitMQ ──consume──► Analytics Service
FTP Service  ──publish──► RabbitMQ ──consume──► Config Service (flow triggers)
                                   ──consume──► External Forwarder
                                   ──consume──► AI Engine (anomaly data)
```

**Queue bindings:**
- `sftp.account.events` — SFTP account lifecycle events
- `ftp.account.events` — FTP account lifecycle events
- `ftpweb.account.events` — FTP-Web account events

### 3. Raw TCP (Protocol Relay)

Used for protocol traffic through the proxy chain.

```
Client ──TCP──► DMZ Proxy ──TCP──► Gateway ──TCP──► SFTP/FTP Service
```

The DMZ proxy and gateway service relay raw bytes — they don't interpret the protocol content (except for initial detection).

---

## Data Flow: File Transfer

Here's what happens when a partner uploads a file:

```
Step 1: Connection
────────────────────────────────────────────────
Client connects to DMZ Proxy port 2222 (SFTP)
  → DMZ Proxy checks rate limit (instant)
  → DMZ Proxy queries AI Engine for verdict
  → AI Engine returns: ALLOW, risk=5
  → DMZ Proxy relays TCP to Gateway Service :2220

Step 2: Routing
────────────────────────────────────────────────
Gateway Service receives SFTP connection
  → Reads SSH banner, waits for USER
  → Looks up user in PostgreSQL
  → Routes to assigned SFTP Service instance (or default)
  → Proxies SFTP session to SFTP Service :2222

Step 3: Authentication
────────────────────────────────────────────────
SFTP Service authenticates user
  → Checks credentials against database
  → Maps to home directory (e.g., /data/sftp/acme/)
  → Creates SFTP session

Step 4: File Upload
────────────────────────────────────────────────
Client uploads invoice.csv
  → SFTP Service writes to /data/sftp/acme/inbox/invoice.csv
  → Generates Track ID: TRZA3X5T3LUY
  → Publishes FILE_RECEIVED event to RabbitMQ

Step 5: Processing (if configured)
────────────────────────────────────────────────
Config Service picks up event
  → Matches file to processing flow (*.csv → "Compress & Screen")
  → Step 1: AI Engine classifies file → "No PCI/PII detected"
  → Step 2: Screening Service OFAC check → "Clear"
  → Step 3: Compression → invoice.csv.gz
  → Publishes PROCESSING_COMPLETE event

Step 6: Delivery
────────────────────────────────────────────────
Routing engine delivers to recipient
  → Local: Copies to /data/sftp/partner-b/inbox/
  → External: Forwarder Service sends via SFTP to partner-b.com
  → Publishes DELIVERY_COMPLETE event

Step 7: Tracking
────────────────────────────────────────────────
Every step is logged in the audit trail
  → Track ID: TRZA3X5T3LUY
  → Viewable in Admin UI → Transfer Journey
  → Checksums verified at every hop
```

---

## Data Flow: Security (DMZ)

The DMZ proxy security layer operates in real-time:

```
┌─────────────────────────────────────────────────────────────────┐
│                    DMZ PROXY (Netty)                              │
│                                                                   │
│  ┌──────────┐    ┌───────────┐    ┌──────────┐    ┌──────────┐ │
│  │  Rate     │───►│ AI Verdict│───►│ Protocol │───►│  Relay   │ │
│  │  Limiter  │    │  Client   │    │ Detector │    │ Handler  │ │
│  └──────────┘    └─────┬─────┘    └──────────┘    └──────────┘ │
│       │                │                                         │
│       │          ┌─────▼─────┐                                  │
│       │          │  Verdict   │                                  │
│       │          │   Cache    │                                  │
│       │          └─────┬─────┘                                  │
│       │                │ cache miss                              │
│  ┌────▼────┐    ┌─────▼─────┐    ┌──────────┐                  │
│  │Connection│    │  Threat   │    │ Security │                  │
│  │ Tracker  │    │  Event    │    │ Metrics  │                  │
│  └─────────┘    │ Reporter  │    └──────────┘                  │
│                  └─────┬─────┘                                  │
│                        │ batch POST every 5s                    │
└────────────────────────┼────────────────────────────────────────┘
                         │
              ┌──────────▼──────────┐
              │    AI ENGINE (:8091) │
              │                      │
              │  IP Reputation       │
              │  Protocol Threats    │
              │  Connection Patterns │
              │  Geo Anomalies       │
              │                      │
              │  Weighted Verdict:   │
              │  reputation   35%    │
              │  patterns     30%    │
              │  geo          15%    │
              │  new IP       10%    │
              │  protocol     10%    │
              └──────────────────────┘
```

For detailed security documentation, see [SECURITY-ARCHITECTURE.md](SECURITY-ARCHITECTURE.md).

---

## Database Architecture

All services share a single PostgreSQL instance with schema-based isolation via Flyway migrations.

```
PostgreSQL :5432
└── database: filetransfer
    ├── Tables owned by onboarding-api:
    │   ├── users, transfer_accounts, folder_mappings
    │   ├── ssh_keys, transfer_records, audit_logs
    │   └── p2p_transfers, server_instances
    ├── Tables owned by config-service:
    │   ├── file_flows, flow_steps, connectors
    │   ├── security_profiles, sla_agreements
    │   └── delivery_endpoints, schedulers
    ├── Tables owned by analytics-service:
    │   └── transfer_metrics, aggregated_stats
    ├── Tables owned by screening-service:
    │   └── screening_results, sanctions_cache
    ├── Tables owned by license-service:
    │   └── licenses, license_usage
    └── Tables owned by other services...
```

**Connection pools per service:**

| Service | Pool Name | Max | Min Idle |
|---------|-----------|-----|----------|
| onboarding-api | Onboarding-Pool | 8 | 3 |
| config-service | Config-Pool | 5 | 2 |
| gateway-service | Gateway-Pool | 5 | 2 |
| sftp-service | SFTP-Pool | 5 | 2 |
| ftp-service | FTP-Pool | 5 | 2 |
| ftp-web-service | FTPWeb-Pool | 5 | 2 |
| analytics-service | Analytics-Pool | 5 | 2 |
| ai-engine | AIEngine-Pool | 3 | 1 |
| screening-service | Screening-Pool | 3 | 1 |
| encryption-service | Encryption-Pool | 3 | 1 |
| keystore-manager | Keystore-Pool | 3 | 1 |
| license-service | License-Pool | 3 | 1 |
| storage-manager | Storage-Pool | 5 | 2 |
| as2-service | AS2-Pool | 10 | 3 |
| **Total** | | **68** | **25** |

> **Note:** PostgreSQL default `max_connections = 150`. The combined pool maximum (68) fits within this limit with headroom for admin connections.

---

## Messaging Architecture

```
RabbitMQ :5672
├── Exchange: file-transfer.events (topic)
│   ├── sftp.account.events  → SFTP Service publishes, Onboarding API consumes
│   ├── ftp.account.events   → FTP Service publishes, Onboarding API consumes
│   └── ftpweb.account.events → FTP-Web publishes, Onboarding API consumes
│
├── Event types published:
│   ├── FILE_RECEIVED     — File uploaded by client
│   ├── FILE_DELIVERED    — File delivered to recipient
│   ├── FILE_PROCESSED    — Flow processing complete
│   ├── ACCOUNT_CREATED   — New transfer account
│   ├── ACCOUNT_UPDATED   — Account modified
│   └── TRANSFER_FAILED   — Transfer error
│
└── Management UI: http://localhost:15672 (guest/guest)
```

---

## Deployment Topologies

### Minimal (SFTP only)

```bash
docker compose up -d postgres rabbitmq onboarding-api sftp-service admin-ui
```

```
PostgreSQL ← Onboarding API → Admin UI
                   ↓
             SFTP Service (:2222)
                   ↑
              SFTP Clients
```

5 containers. ~2 GB RAM.

### Standard (SFTP + FTP + Web + AI)

```bash
docker compose up -d postgres rabbitmq onboarding-api config-service \
  sftp-service ftp-service ftp-web-service gateway-service \
  ai-engine admin-ui ftp-web-ui
```

11 containers. ~6 GB RAM.

### Full Platform (Everything)

```bash
docker compose up --build -d
```

22+ containers. ~12 GB RAM.

### Production (DMZ + Internal)

```
┌─────────── DMZ Network ───────────┐    ┌────── Internal Network ──────┐
│                                    │    │                               │
│  DMZ Proxy (:2222, :21, :443)     │───►│  Gateway Service              │
│  API Gateway (:80, :443)          │    │  SFTP/FTP/FTP-Web Services    │
│                                    │    │  Onboarding API               │
│  (No database access)             │    │  Config Service                │
│  (AI Engine accessible via HTTP)  │    │  AI Engine                     │
│                                    │    │  All other services            │
│                                    │    │  PostgreSQL                    │
│                                    │    │  RabbitMQ                      │
└────────────────────────────────────┘    └───────────────────────────────┘
```

The DMZ proxy is intentionally designed with **no database access** — it cannot leak internal data even if compromised.

---

## Network Diagram

### Port Map (External → Internal)

```
Internet-facing ports (exposed by DMZ Proxy):
  32222 → dmz-proxy:2222 → gateway:2220 → sftp-service:2222    [SFTP]
  32121 → dmz-proxy:21   → gateway:2121 → ftp-service:21       [FTP]
   4443 → dmz-proxy:443  → ftp-web-service:8083                [HTTPS]

HTTP API ports (exposed by API Gateway):
     80 → api-gateway → onboarding-api:8080                    [REST API]
    443 → api-gateway → onboarding-api:8080                    [REST API + TLS]

Management ports (internal only — do NOT expose):
   8080 → onboarding-api      8084 → config-service
   8085 → gateway-service      8086 → encryption-service
   8087 → forwarder            8088 → dmz-proxy management
   8089 → license-service      8090 → analytics-service
   8091 → ai-engine            8092 → screening-service
   8093 → keystore-manager     8094 → storage-manager
   8095 → edi-converter

Frontend ports:
   3000 → admin-ui             3001 → ftp-web-ui
   3002 → partner-portal

Infrastructure:
   5432 → postgresql           5672 → rabbitmq (AMQP)
  15672 → rabbitmq (management UI)
```

### Firewall Rules (Production)

```
INBOUND (from Internet):
  Allow TCP 32222  (SFTP)
  Allow TCP 32121  (FTP)
  Allow TCP 4443   (HTTPS)
  Allow TCP 80/443 (REST API via API Gateway)
  Deny everything else

INTERNAL (between DMZ and Internal):
  DMZ Proxy → Gateway Service :2220, :2121       [TCP relay]
  DMZ Proxy → FTP-Web Service :8083              [TCP relay]
  DMZ Proxy → AI Engine :8091                    [HTTP verdict queries]
  API Gateway → Onboarding API :8080             [HTTP]
  API Gateway → Config Service :8084             [HTTP]
  API Gateway → Analytics Service :8090          [HTTP]
  API Gateway → License Service :8089            [HTTP]
  API Gateway → Admin UI :3000                   [HTTP]

INTERNAL (service-to-service):
  All services → PostgreSQL :5432                [JDBC]
  All services → RabbitMQ :5672                  [AMQP]
  Transfer services → Encryption Service :8086   [HTTP]
  Transfer services → Screening Service :8092    [HTTP]
  Transfer services → AI Engine :8091            [HTTP]
```

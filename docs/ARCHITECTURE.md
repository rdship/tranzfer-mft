# TranzFer MFT — Architecture Guide

This document explains how TranzFer's 20 microservices fit together, how data flows between them, and how to deploy subsets of the platform.

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Tier Architecture](#tier-architecture)
3. [Service Communication](#service-communication)
4. [Data Flow: File Transfer](#data-flow-file-transfer)
5. [Data Flow: Security (DMZ)](#data-flow-security-dmz)
6. [Partner Management](#partner-management)
7. [Keystore Manager](#keystore-manager)
8. [Database Architecture](#database-architecture)
9. [Messaging Architecture](#messaging-architecture)
10. [Deployment Topologies](#deployment-topologies)
11. [Platform Maturity Infrastructure](#platform-maturity-infrastructure)
12. [AI Engine: Cybersecurity Intelligence](#ai-engine-cybersecurity-intelligence)
13. [Network Diagram](#network-diagram)

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
                    │   Admin UI (:3000)       — 38-page dashboard  │
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
| **ai-engine** | 8091 | AI classification, anomaly detection, NLP, proxy intelligence, threat intelligence, MITRE ATT&CK, automated response |
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
              │  reputation   25%    │
              │  patterns     20%    │
              │  threat intel 20%    │
              │  geo          10%    │
              │  network beh. 10%    │
              │  new IP        5%    │
              │  protocol     10%    │
              │                      │
              │  + MITRE ATT&CK map  │
              │  + Attack chain track│
              │  + Playbook trigger  │
              └──────────────────────┘
```

For detailed security documentation, see [SECURITY-ARCHITECTURE.md](SECURITY-ARCHITECTURE.md).

---

## Partner Management

TranzFer includes a unified **Partner Management** system that models external and internal organizations exchanging files through the platform. Partners serve as the top-level organizational entity linking accounts, file flows, delivery endpoints, and SLA agreements.

### Partner Entity and Relationships

```
Partner (partners)
├── PartnerContact (partner_contacts)  — 1:N contact persons per partner
├── TransferAccount (transfer_accounts) — 1:N accounts linked via partner_id FK
├── FileFlow (file_flows)              — 1:N flows linked via partner_id FK
├── DeliveryEndpoint (delivery_endpoints) — 1:N endpoints linked via partner_id FK
└── PartnerAgreement (partner_agreements) — 1:N SLA agreements linked via partner_id FK
```

The `Partner` entity stores company metadata (name, slug, industry, website, logo), protocol configuration (JSONB array of enabled protocols such as `["SFTP","AS2"]`), and SLA parameters (tier, max file size, max transfers/day, retention days).

**Partner types:** `INTERNAL`, `EXTERNAL`, `VENDOR`, `CLIENT`

### Partner Lifecycle

Partners progress through a status lifecycle managed by the `PartnerService`:

```
PENDING ──activate──► ACTIVE ──suspend──► SUSPENDED
   │                    ▲                      │
   │                    └────── activate ───────┘
   │
   └── delete (soft) ──► OFFBOARDED
                              ▲
              ACTIVE ─── delete (soft) ──┘
```

| Status | Meaning |
|--------|---------|
| `PENDING` | Partner created but not yet activated. Default initial status. |
| `ACTIVE` | Partner is live and transferring files. |
| `SUSPENDED` | Partner temporarily disabled. Can be re-activated. |
| `OFFBOARDED` | Partner permanently retired (soft delete). |

### Onboarding Phases

Each partner tracks an onboarding phase that represents how far along they are in the setup process:

```
SETUP ──► CREDENTIALS ──► TESTING ──► LIVE
```

| Phase | Description |
|-------|-------------|
| `SETUP` | Initial company info and protocol selection. Default phase for new partners. |
| `CREDENTIALS` | Account credentials and keys are being provisioned. |
| `TESTING` | Partner is performing test transfers to validate connectivity. |
| `LIVE` | Onboarding complete. Set automatically when a partner is activated. |

The Admin UI provides a 6-step onboarding wizard at `/partner-setup` that walks operators through: Company Info, Protocols, Contacts, Account Setup, SLA Configuration, and Review.

### Partner Management API

All endpoints are served by the `PartnerManagementController` on the **onboarding-api** service (`:8080`).

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/partners` | Create a new partner (with optional contacts) |
| `GET` | `/api/partners` | List all partners (filterable by `?status=` and `?type=`) |
| `GET` | `/api/partners/{id}` | Get partner detail (includes contacts, account/flow/endpoint counts) |
| `PUT` | `/api/partners/{id}` | Update partner fields (partial update) |
| `DELETE` | `/api/partners/{id}` | Soft-delete partner (sets status to OFFBOARDED) |
| `POST` | `/api/partners/{id}/activate` | Activate partner (status=ACTIVE, phase=LIVE) |
| `POST` | `/api/partners/{id}/suspend` | Suspend partner (status=SUSPENDED) |
| `GET` | `/api/partners/stats` | Get partner counts by status |
| `GET` | `/api/partners/{id}/accounts` | List transfer accounts linked to partner |
| `POST` | `/api/partners/{id}/accounts` | Create and link a new transfer account to partner |
| `GET` | `/api/partners/{id}/flows` | List file flows linked to partner |
| `GET` | `/api/partners/{id}/endpoints` | List delivery endpoints linked to partner |

### Admin UI Pages

| Route | Page | Description |
|-------|------|-------------|
| `/partners` | Partners List | Table with partner stats, status filters, and CRUD operations |
| `/partners/:id` | Partner Detail | 5-tab view: Overview, Accounts, Flows, Endpoints, Settings |
| `/partner-setup` | Partner Onboarding Wizard | 6-step wizard for onboarding new partners |
| `/services` | Service Management | Microservice health dashboard with architecture overview |

---

## Keystore Manager

The **Keystore Manager** (port 8093) is the centralized PKI and key management service. All other services request keys through `KeystoreServiceClient` instead of managing keys locally.

### Supported Key Types

| Key Type | Algorithm | Use Case |
|----------|-----------|----------|
| `SSH_HOST_KEY` | EC P-256 | SFTP/SSH server identity |
| `SSH_USER_KEY` | RSA-2048/4096 | Partner authentication |
| `PGP_KEYPAIR` | RSA-4096 | File signing & encryption |
| `PGP_PUBLIC` | — | Partner's PGP public key (imported) |
| `PGP_PRIVATE` | — | PGP decryption key (imported) |
| `AES_SYMMETRIC` | AES-256 | File-level encryption |
| `TLS_CERTIFICATE` | RSA-2048 | HTTPS/TLS endpoints |
| `TLS_KEYSTORE` | — | Java keystore (JKS/PKCS12) |
| `HMAC_SECRET` | HmacSHA256 | Message signing & webhook verification |
| `API_KEY` | — | Inter-service authentication |

### Key Lifecycle

```
Generate/Import → Active → Rotate (deactivate old → generate new)
                        └→ Deactivate (soft-delete)
```

- Keys are **never hard-deleted** — deactivated keys retain `rotatedToAlias` pointers for audit
- Daily scheduled job (8am UTC, ShedLock-protected) warns about keys expiring within 30 days
- Auto-rotation supported for: `AES_SYMMETRIC`, `SSH_HOST_KEY`, `HMAC_SECRET`

### Keystore Manager API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/keys` | List active keys (filter: `?type=`, `?service=`, `?partner=`) |
| GET | `/api/v1/keys/{alias}` | Get full key details |
| GET | `/api/v1/keys/{alias}/public` | Get public key material (PEM, safe to share) |
| GET | `/api/v1/keys/{alias}/download?part=public\|private` | Download key as file |
| POST | `/api/v1/keys/generate/ssh-host` | Generate SSH host key |
| POST | `/api/v1/keys/generate/ssh-user` | Generate SSH user key |
| POST | `/api/v1/keys/generate/pgp` | Generate PGP keypair (RSA-4096) |
| POST | `/api/v1/keys/generate/aes` | Generate AES-256 symmetric key |
| POST | `/api/v1/keys/generate/tls` | Generate self-signed TLS certificate |
| POST | `/api/v1/keys/generate/hmac` | Generate HMAC-SHA256 key |
| POST | `/api/v1/keys/import` | Import existing key/certificate |
| POST | `/api/v1/keys/{alias}/rotate` | Rotate a key |
| DELETE | `/api/v1/keys/{alias}` | Deactivate (soft-delete) a key |
| GET | `/api/v1/keys/stats` | Key statistics (by type, service, expiring) |
| GET | `/api/v1/keys/expiring?days=30` | List keys expiring within N days |
| GET | `/api/v1/keys/types` | List all supported key types |
| GET | `/api/v1/keys/health` | Health check with key counts |

### Admin UI — Keystore Manager Page

| Feature | Description |
|---------|-------------|
| Stats Dashboard | Total active keys, counts by category (Protocol, Encryption, Certificate, Signing) |
| Key List | Searchable, filterable table with type icons, fingerprints, expiry warnings |
| Generate Wizard | 2-step: select key type card → fill type-specific form |
| Import Key | Paste PEM or upload file, select type, assign owner/partner |
| Key Detail Modal | Full metadata, public key viewer, copy-to-clipboard, download (public/private) |
| Rotate | Confirmation dialog, generates replacement, deactivates old key |
| Deactivate | Confirmation dialog, soft-deletes key |
| Expiry Alerts | Banner showing keys expiring within 30 days with quick-rotate action |

---

## Network & Proxy Management

The platform has three proxy-layer services that form the inbound/outbound network path:

| Service | Port | Role |
|---------|------|------|
| **DMZ Proxy** | 8088 (mgmt), 2222/21/443 (traffic) | AI-powered reverse proxy in DMZ zone |
| **Gateway Service** | 8085 (mgmt), 2220/2121 (traffic) | Protocol gateway with user-based routing |
| **External Forwarder** | 8087 | Multi-protocol outbound file delivery |

### Gateway Service API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/internal/gateway/status` | Gateway ports and running state |
| GET | `/internal/gateway/routes` | Full route table (default + instance + legacy) |
| GET | `/internal/gateway/stats` | Gateway statistics (instances, accounts, protocols) |
| GET | `/internal/gateway/legacy-servers` | Legacy server fallback configurations |

### DMZ Proxy API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/proxy/mappings` | List all port mappings with live stats |
| POST | `/api/proxy/mappings` | Hot-add a new port mapping |
| DELETE | `/api/proxy/mappings/{name}` | Remove a port mapping |
| GET | `/api/proxy/health` | Health check with features list |
| GET | `/api/proxy/security/stats` | Full security metrics dump |
| GET | `/api/proxy/security/connections` | Connection tracker statistics |
| GET | `/api/proxy/security/ip/{ip}` | Per-IP security intelligence |
| GET | `/api/proxy/security/rate-limits` | Rate limiter state |
| GET | `/api/proxy/security/summary` | Quick security summary |

### External Forwarder API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/forward/transfers/active` | Active transfer monitoring (real-time) |
| GET | `/api/forward/health` | Forwarder health with active transfer count |
| POST | `/api/forward/{destinationId}` | Forward file to legacy destination |
| POST | `/api/forward/deliver/{endpointId}` | Deliver file via modern endpoint |

### Admin UI — Network & Proxy Page

| Tab | Features |
|-----|----------|
| **Overview** | Architecture diagram, route table (internal/instance/legacy), DMZ mapping preview |
| **DMZ Security** | Connection stats, AI verdict breakdown, throughput, protocol distribution, cache hit rate |
| **Port Mappings** | Manage DMZ port forwarding rules (add/remove), live connection counts, bytes forwarded |
| **Active Transfers** | Real-time transfer monitoring with progress bars, stall detection, bytes/percentage tracking |

---

## Database Architecture

All services share a single PostgreSQL instance with schema-based isolation via Flyway migrations.

```
PostgreSQL :5432
└── database: filetransfer
    ├── Tables owned by onboarding-api:
    │   ├── users, transfer_accounts, folder_mappings
    │   ├── ssh_keys, transfer_records, audit_logs
    │   ├── p2p_transfers, server_instances
    │   ├── partners, partner_contacts
    │   └── (partner_id FK on transfer_accounts, delivery_endpoints,
    │        file_flows, partner_agreements)
    ├── Tables owned by config-service:
    │   ├── file_flows, flow_steps, connectors
    │   ├── security_profiles, sla_agreements
    │   └── delivery_endpoints, schedulers
    ├── Tables owned by ai-engine:
    │   ├── threat_indicators, security_alerts, security_events
    │   ├── threat_actors, attack_campaigns
    │   ├── verdict_records, security_incidents
    │   └── (V15 migration — threat intelligence & incident management)
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

## Platform Maturity Infrastructure

The platform includes enterprise-grade infrastructure for resilience, observability, security, and API quality — all centralized in the shared module so every service inherits automatically.

### Resilience (Resilience4j)

All inter-service REST communication is protected by circuit breakers and retry with exponential backoff.

| Pattern | Default Config | Purpose |
|---------|---------------|---------|
| Circuit Breaker | 50% failure rate, 10-call window, 30s open wait | Prevents cascading failures when a service goes down |
| Retry | 3 attempts, 500ms backoff, connection errors only | Handles transient network issues |
| Timeout | Configurable per-service via `readTimeoutMs` | Prevents thread blocking on slow services |

**Key class**: `ResilientServiceClient` extends `BaseServiceClient` — subclasses get protection automatically.

**Configuration** (`application.yml`):
```yaml
platform:
  resilience:
    circuit-breaker:
      failure-rate-threshold: 50
      sliding-window-size: 10
      wait-duration-seconds: 30
    retry:
      max-attempts: 3
      wait-duration-ms: 500
```

All 14 service clients are resilient: ConfigServiceClient, OnboardingApiClient, StorageServiceClient, ScreeningServiceClient, DmzProxyClient, LicenseServiceClient, AiEngineClient, As2ServiceClient, GatewayServiceClient, EdiConverterClient, ForwarderServiceClient, EncryptionServiceClient, AnalyticsServiceClient, KeystoreServiceClient.

### Observability

#### Correlation IDs (Distributed Tracing)

Every HTTP request gets a correlation ID that propagates across all service calls:

1. `CorrelationIdFilter` checks for incoming `X-Correlation-ID` header
2. If absent, generates a short UUID (8 chars)
3. Puts it into SLF4J MDC — all log lines include `[correlationId]`
4. `BaseServiceClient` propagates it in outgoing `X-Correlation-ID` headers
5. Response includes the correlation ID for client-side debugging

Log format: `2024-01-15 10:30:00.123 [a1b2c3d4] [onboarding-api] INFO  ...`

#### Prometheus Metrics

All services expose JVM and business metrics at `/actuator/prometheus`:

| Metric Category | Metrics |
|----------------|---------|
| JVM Memory | Heap/non-heap usage, buffer pools |
| JVM GC | GC pauses, collections, allocation rates |
| JVM Threads | Thread states, daemon/non-daemon counts |
| System | CPU usage, system load, uptime |
| HTTP | Request counts, latencies, error rates (via Spring Boot Actuator) |

Every metric is tagged with `service` and `cluster` for Grafana filtering.

#### Structured Logging

Shared logback configuration (`logback-platform.xml`) provides 3 appenders:
- **CONSOLE**: Human-readable with correlation IDs
- **FILE**: Rolling file appender (50MB/file, 1GB total, 30-day retention)
- **JSON**: Structured JSON for ELK/Datadog ingestion

### Error Handling

Standardized error responses across all 20 services via `PlatformExceptionHandler`:

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "code": "ENTITY_NOT_FOUND",
  "message": "Account with ID abc123 not found",
  "path": "/api/accounts/abc123",
  "correlationId": "a1b2c3d4"
}
```

| Error Code | HTTP Status | When |
|-----------|-------------|------|
| ENTITY_NOT_FOUND | 404 | Requested resource doesn't exist |
| VALIDATION_FAILED | 400 | @Valid constraint violations |
| ILLEGAL_ARGUMENT | 400 | Invalid request parameter |
| ACCESS_DENIED | 403 | RBAC role check failed |
| UNAUTHORIZED | 401 | Missing/invalid JWT |
| SERVICE_UNAVAILABLE | 503 | Downstream service unreachable |
| CIRCUIT_OPEN | 503 | Circuit breaker tripped |
| LICENSE_EXPIRED | 402 | License validation failed |
| QUOTA_EXCEEDED | 429 | Transfer quota limit hit |
| INTERNAL_ERROR | 500 | Unhandled exception |

**Opt-out**: Set `platform.exception-handler.shared=false` for custom handling.

### Role-Based Access Control (RBAC)

Six roles with hierarchical permissions:

| Role | Description | Access Level |
|------|------------|-------------|
| ADMIN | Full platform administration | Everything |
| OPERATOR | Day-to-day operations | Transfers, monitoring, config |
| USER | Standard file transfer user | Own transfers, limited views |
| VIEWER | Read-only access | Dashboards, logs, reports |
| PARTNER | External partner portal | Partner-specific resources |
| SYSTEM | Internal service-to-service | Internal endpoints only |

**Usage in controllers**:
```java
@PreAuthorize(Roles.ADMIN)           // Admin only
@PreAuthorize(Roles.OPERATOR)        // Admin + Operator
@PreAuthorize(Roles.USER)            // Admin + Operator + User
@PreAuthorize(Roles.VIEWER)          // All authenticated roles
@PreAuthorize(Roles.PARTNER)         // Admin + Partner
```

Method-level security enabled via `@EnableMethodSecurity` in `PlatformSecurityConfig`. All 29 user-facing controllers have class-level `@PreAuthorize` annotations. Internal/protocol controllers (health checks, file receive, AS2/AS4 inbound, DMZ proxy, AI engine, encryption) use header-based auth (X-Internal-Key) instead.

### Entity Auditing

Base class `Auditable` provides automatic audit fields for any JPA entity:

| Column | Annotation | Auto-populated |
|--------|-----------|----------------|
| created_at | @CreatedDate | On insert |
| updated_at | @LastModifiedDate | On insert + update |
| created_by | @CreatedBy | From SecurityContext |
| updated_by | @LastModifiedBy | From SecurityContext |

Flyway migrations add audit columns to 18 tables:
- `V13__add_audit_columns.sql`: users, transfer_accounts, folder_mappings, server_configs, file_flows, security_profiles, partners, external_destinations, delivery_endpoints
- `V14__add_audit_columns_phase2.sql`: scheduled_tasks, webhook_connectors, as2_partnerships, legacy_server_configs, platform_settings, encryption_keys, tenants, partner_agreements, partner_contacts

All 18 entity classes extend `Auditable` — no manual timestamp management needed.

### OpenAPI Documentation

Every service automatically gets Swagger UI and OpenAPI 3.0 spec:

| Endpoint | Description |
|----------|------------|
| `/swagger-ui.html` | Interactive API documentation |
| `/v3/api-docs` | OpenAPI 3.0 JSON spec |
| `/v3/api-docs.yaml` | OpenAPI 3.0 YAML spec |

Security schemes configured: JWT Bearer token and X-Internal-Key header.

### Environment Profiles

| Profile | Activation | Purpose |
|---------|-----------|---------|
| `dev` | `SPRING_PROFILES_ACTIVE=dev` | Local development, debug logging, Swagger UI enabled |
| `test` | `SPRING_PROFILES_ACTIVE=test` | CI pipeline, minimal logging, Swagger disabled |
| `prod` | `SPRING_PROFILES_ACTIVE=prod` | Production, env-var secrets, tuned resilience, Swagger disabled |

### Frontend Error Handling

The admin UI includes:
- **ErrorBoundary**: Catches React render errors, shows recovery UI
- **PageErrorBoundary**: Per-page isolation so one page crash doesn't affect others
- **useApiError hook**: Consistent API error parsing with correlation ID logging
- **Correlation ID interceptor**: Logs `X-Correlation-ID` from all API error responses

### Pre-Flight Security Checks

Before deploying to production, operators MUST run the pre-flight security check:

```bash
./scripts/preflight-check.sh --env          # Check current environment variables
./scripts/preflight-check.sh --compose docker-compose.yml  # Audit compose file
```

The script detects 9 categories of insecure defaults:

| Secret | Default Value | Risk |
|--------|--------------|------|
| JWT_SECRET | `change_me_in_production_256bit_secret_key!!` | Critical — token forgery |
| CONTROL_API_KEY | `internal_control_secret` | Critical — service impersonation |
| ENCRYPTION_MASTER_KEY | 64 zeros | Critical — encryption nullified |
| DB_PASSWORD | `postgres` | High — direct DB access |
| KEYSTORE_MASTER_PASSWORD | `change-this-master-password` | High — key/cert exposure |
| LICENSE_ADMIN_KEY | `license_admin_secret_key` | High — license bypass |
| RABBITMQ_PASSWORD | `guest` | Medium — message queue access |
| FTP TLS keystore/truststore | `changeit` | Medium — TLS compromise |

When `PLATFORM_ENVIRONMENT=PROD`, all issues are **hard failures** (exit code 1). In dev/test, they are warnings.

### Database Backup & Restore

Production backup/restore is managed via scripts in `scripts/`:

| Script | Purpose |
|--------|---------|
| `scripts/backup.sh` | Automated pg_dump with integrity verification and retention pruning |
| `scripts/restore.sh` | Safe restore with pre-restore backup, confirmation, and validation |
| `scripts/backup-cron-setup.sh` | Set up daily cron job or generate Kubernetes CronJob YAML |

```bash
# Docker mode (development)
./scripts/backup.sh --docker --keep 30

# Direct mode (production)
DB_HOST=db.prod DB_PASSWORD=... ./scripts/backup.sh --host --backup-dir /backups

# Restore (requires --confirm)
./scripts/restore.sh --docker backups/mft-backup-2026-04-05-020000.dump --confirm

# Set up daily cron at 2 AM
./scripts/backup-cron-setup.sh --mode docker --keep 30
```

For Kubernetes: `./scripts/backup-cron-setup.sh --k8s-only` generates a complete CronJob + PVC + ServiceAccount manifest.

### Integration Testing

The platform has layered test coverage:

| Layer | Tests | Framework |
|-------|-------|-----------|
| Unit tests (shared) | 112 tests — routing, resilience, validation | JUnit 5, Mockito |
| Unit tests (AI engine) | 79 tests — intelligence, classification, reputation, threat detection | JUnit 5 |
| Unit tests (DMZ proxy) | 29 tests — rate limiting, protocol detection | JUnit 5 |
| Integration: AI engine REST | 12 tests — full HTTP verdict/event/blocklist cycle | SpringBootTest + TestRestTemplate |
| Integration: DMZ ↔ AI engine | 15 tests — client cache, timeout, fallback, recovery | WireMock |

**DMZ ↔ AI Engine integration tests** (`AiVerdictClientIntegrationTest`) verify:
- Verdict request/response parsing over real HTTP
- Local TTL cache behavior (hit, miss, expiry)
- Graceful fallback when AI engine is unreachable or times out
- Health-check-based recovery after engine comes back
- Async event reporting (single + batch)
- Cache invalidation (per-IP and global)

**AI Engine controller tests** (`ProxyIntelligenceControllerIntegrationTest`) verify:
- Full verdict lifecycle: new IP → ALLOW, blocked IP → BLACKHOLE, allowlisted → zero risk
- Blocklist/allowlist CRUD operations via REST
- Event processing (single + batch)
- Dashboard, audit trail, and IP intelligence endpoints

### CI/CD Pipeline

Tests are enabled in the CI pipeline with:
- `mvn clean package -B` (tests run during build)
- Dedicated `mvn test -B --fail-at-end` step with test profile
- PostgreSQL 16 service container for integration tests
- Secret scanning and Dockerfile validation
- Pre-flight security check (`scripts/preflight-check.sh --env`) in deployment stage

---

## AI Engine: Cybersecurity Intelligence

The AI Engine (port 8091) was upgraded from a classification and proxy intelligence service to a full **cybersecurity intelligence platform**. This section covers the new architecture, which adds 30+ Java files across 6 new packages.

### Architecture Overview

```
                 ┌──────────────────────────────────────────────────────┐
                 │                  OSINT FEEDS                          │
                 │  AbuseIPDB, URLhaus, FeodoTracker, ThreatFox, OTX   │
                 └──────────────┬───────────────────────────────────────┘
                                │ every 15 min
                 ┌──────────────▼───────────────────────────────────────┐
                 │         BACKGROUND AGENTS (AgentManager)             │
                 │                                                       │
                 │  OsintCollectorAgent    — Threat feed ingestion (15m) │
                 │  CveMonitorAgent        — CVE/NVD monitoring (1h)    │
                 │  ThreatCorrelationAgent — Cross-source correlation(2m)│
                 │  ReputationDecayAgent   — IP decay & cleanup (5m)    │
                 └──────────────┬───────────────────────────────────────┘
                                │
                 ┌──────────────▼───────────────────────────────────────┐
                 │         THREAT INTELLIGENCE STORE                     │
                 │                                                       │
                 │  In-memory + PostgreSQL persistence                   │
                 │  IOC lookup, dedup, aging, search                     │
                 │  ThreatKnowledgeGraph (IPs→domains→actors→campaigns) │
                 │  GeoIpResolver (ip-api.com + 50K cache)              │
                 │  MitreAttackMapper (50+ techniques, kill chain)      │
                 └──────────────┬───────────────────────────────────────┘
                                │
                 ┌──────────────▼───────────────────────────────────────┐
                 │         ML DETECTION PIPELINE                         │
                 │                                                       │
                 │  AnomalyEnsemble — Isolation Forest + Z-score +      │
                 │                    seasonal baselines                  │
                 │  NetworkBehaviorAnalyzer — Beaconing (FFT/Goertzel), │
                 │    DGA detection (Shannon entropy + bigrams),         │
                 │    DNS tunneling, data exfil, port scan               │
                 │  AttackChainDetector — MITRE ATT&CK kill chain       │
                 │    progression tracking per entity                    │
                 │  ExplainabilityEngine — Human-readable explanations   │
                 └──────────────┬───────────────────────────────────────┘
                                │
                 ┌──────────────▼───────────────────────────────────────┐
                 │         VERDICT PATH (ProxyIntelligenceService)       │
                 │                                                       │
                 │  Existing signals + threat intel store (0.20 weight)  │
                 │  + network behavior analysis (0.10 weight)            │
                 │  + MITRE ATT&CK mapping on all alerts                │
                 │  + attack chain tracking on high-risk connections     │
                 │  All new signals are in-memory, zero latency impact   │
                 └──────────────┬───────────────────────────────────────┘
                                │
                 ┌──────────────▼───────────────────────────────────────┐
                 │         RESPONSE AUTOMATION                           │
                 │                                                       │
                 │  PlaybookEngine — 8 built-in playbooks, rate-limited │
                 │  IncidentManager — Lifecycle, timeline, reports       │
                 │  Automated playbook triggering on alerts              │
                 └───────────────────────────────────────────────────────┘
```

### Background Agent System

The `AgentManager` manages 4 autonomous background agents, each with lifecycle management, metrics, and health checks. Agents are registered via `AgentRegistrar` (Spring configuration).

| Agent | Schedule | Purpose |
|-------|----------|---------|
| `OsintCollectorAgent` | Every 15 min | Collects IOCs from 5 OSINT feeds: AbuseIPDB, URLhaus, FeodoTracker, ThreatFox, AlienVault OTX |
| `CveMonitorAgent` | Every 1 hour | Monitors CVE/NVD for new vulnerabilities relevant to the platform |
| `ThreatCorrelationAgent` | Every 2 min | Correlates indicators across sources, detects kill chain progressions, clusters campaigns |
| `ReputationDecayAgent` | Every 5 min | Decays IP reputation scores over time, cleans up stale indicators |

### ML Detection Pipeline

**Anomaly Ensemble** (`AnomalyEnsemble`): Combines three detection methods:
- **Isolation Forest** (pure Java implementation) -- detects outliers in multi-dimensional feature space
- **Z-score detector** -- flags values beyond configurable standard deviation thresholds
- **Seasonal baselines** -- learns normal patterns and detects deviations from expected behavior

**Network Behavior Analyzer** (`NetworkBehaviorAnalyzer`): Detects advanced threats:
- **Beaconing detection** -- FFT/Goertzel algorithm identifies periodic C2 callbacks
- **DGA detection** -- Shannon entropy + bigram frequency analysis identifies algorithmically generated domains
- **DNS tunneling** -- Detects data exfiltration via DNS queries
- **Data exfiltration** -- Monitors for unusual outbound data volumes
- **Port scan detection** -- Identifies horizontal and vertical port scanning activity

**Attack Chain Detector** (`AttackChainDetector`): Tracks MITRE ATT&CK kill chain progression per entity. Builds human-readable narratives of multi-stage attacks as they unfold.

**Explainability Engine** (`ExplainabilityEngine`): Generates human-readable explanations for every verdict, anomaly detection, and attack chain, supporting audit and compliance requirements.

### MITRE ATT&CK Integration

`MitreAttackMapper` provides a full MITRE ATT&CK technique/tactic database with 50+ techniques. Capabilities include:

- Mapping detected behaviors to specific MITRE techniques and tactics
- Kill chain analysis showing progression through reconnaissance, initial access, execution, persistence, etc.
- Coverage reporting showing which ATT&CK techniques the platform can detect
- Technique database queryable via REST endpoints

### Threat Knowledge Graph

`ThreatKnowledgeGraph` maintains an in-memory graph of threat relationships:

```
IP Address ──related──► Domain ──used_by──► Threat Actor ──part_of──► Campaign
     │                                            │
     └──────────── targeted ──────────────────────┘
```

Capabilities:
- **BFS path finding** -- Discover shortest relationship path between any two IOCs
- **PageRank** -- Identify the most significant threat actors and infrastructure
- **Cluster detection** -- Group related indicators into threat clusters
- Graph statistics available via REST endpoint

### Response Automation

**Playbook Engine** (`PlaybookEngine`): 8 built-in security playbooks with rate-limited execution and full audit trails:

| Playbook | Trigger |
|----------|---------|
| Brute Force | Repeated authentication failures from same IP |
| Port Scan | Horizontal/vertical scanning detected |
| Data Exfiltration | Anomalous outbound data volume |
| C2 Beaconing | Periodic callback pattern detected |
| Kill Chain | Multi-stage attack progression |
| DGA Activity | Domain generation algorithm detected |
| DDoS | Volumetric traffic anomaly |
| Threat Intel Match | IP/domain matches known threat indicator |

**Incident Manager** (`IncidentManager`): Full security incident lifecycle management with timeline tracking and report generation.

### New REST Endpoints

The `ThreatIntelligenceController` exposes 30+ endpoints at `/api/v1/threats/`:

| Category | Endpoint(s) | Description |
|----------|-------------|-------------|
| Indicators | `/threats/indicators` | IOC CRUD + search |
| Hunting | `/threats/hunt` | Threat hunting across all data stores |
| Network | `/threats/analyze/network` | Network behavior analysis |
| Anomaly | `/threats/analyze/anomaly` | Anomaly detection |
| Attack Chains | `/threats/chains` | Active attack chain listing |
| MITRE Coverage | `/threats/mitre/coverage` | ATT&CK coverage report |
| MITRE Techniques | `/threats/mitre/techniques` | Technique database query |
| Graph Stats | `/threats/graph/stats` | Knowledge graph statistics |
| Graph Lookup | `/threats/graph/related/{ioc}` | Related threat discovery from IOC |
| Playbooks | `/threats/playbooks` | Playbook management and execution |
| Incidents | `/threats/incidents` | Incident lifecycle management |
| Agents | `/threats/agents` | Background agent management and health |
| Dashboard | `/threats/dashboard` | Comprehensive security dashboard |
| GeoIP | `/threats/geo/resolve/{ip}` | IP geolocation resolution |
| Health | `/threats/health` | Threat intelligence subsystem health |

### Database Schema (V15 Migration)

The V15 Flyway migration adds 7 new tables:

| Table | Purpose |
|-------|---------|
| `threat_indicators` | IOCs (IPs, domains, hashes, URLs) with type, severity, source, confidence |
| `security_alerts` | Generated alerts with MITRE technique mapping |
| `security_events` | Unified security event log (connections, verdicts, detections) |
| `threat_actors` | Known threat actor profiles |
| `attack_campaigns` | Campaigns linking multiple actors and indicators |
| `verdict_records` | Persisted verdict history for every proxy decision |
| `security_incidents` | Incident lifecycle records with timeline |

### Configuration

New `application.yml` sections for the cybersecurity features:

```yaml
ai:
  intelligence:
    threat-store:
      max-indicators: 100000
      aging-hours: 720          # 30 days
    geoip:
      provider: ip-api          # ip-api.com (free tier)
      cache-size: 50000
  agents:
    osint:
      interval-minutes: 15
    cve-monitor:
      interval-minutes: 60
    correlation:
      interval-minutes: 2
    reputation-decay:
      interval-minutes: 5
  detection:
    isolation-forest:
      num-trees: 100
      sample-size: 256
    beaconing:
      min-connections: 10
      periodicity-threshold: 0.7
    dga:
      entropy-threshold: 3.5
    attack-chain:
      max-age-hours: 24
  response:
    playbook:
      rate-limit-per-minute: 10
      audit-enabled: true
```

### Performance Optimizations

The verdict hot path has been tuned for zero end-user impact with 5 optimizations:

| # | Optimization | What Changed | Impact |
|---|-------------|--------------|--------|
| 1 | **Async alert enrichment** | `raiseAlert()` (MITRE mapping, attack chain, explainability) now runs on a dedicated 2-thread daemon pool via `ExecutorService`, off the verdict thread | Verdict returns immediately; enrichment completes async |
| 2 | **Verdict caching** | `ConcurrentHashMap<String, CachedVerdict>` with 10s TTL, max 50K entries. Cache invalidated on block/allow/unblock changes | Repeat IPs served from cache — 0.3µs vs 4.8µs |
| 3 | **Lock-free ring buffer** | Replaced `synchronized(Deque)` audit trail with fixed-size `Map[]` array + `AtomicInteger` head pointer. Power-of-2 size for bitwise modulo | Zero lock contention on verdict path under concurrency |
| 4 | **Pattern analyzer LRU cap** | `ConnectionPatternAnalyzer.profiles` capped at 100K entries. When full, evicts oldest 10% by `lastSeen` | Stable memory under sustained load with millions of unique IPs |
| 5 | **Batch verdict API** | New `POST /api/v1/proxy/verdicts/batch` accepts array of `{sourceIp, targetPort, detectedProtocol}`, returns array of verdicts | Reduces HTTP round-trips for proxy burst scenarios |

**Benchmark results** (Apple M-series, single JVM, no external I/O):

| Path | Avg Latency | P99 Latency | Throughput |
|------|------------|-------------|------------|
| Blocklist fast-path | 0.5 µs | 1.9 µs | 1.8M/sec |
| Core verdict (4 analyzers) | 4.8 µs | 7.8 µs | 210K/sec |
| Full AI stack (MITRE+network+explain) | 5.7 µs | 6.0 µs | 176K/sec |
| 8-thread concurrent | 13.2 µs | 173 µs | 185K/sec |

End-user impact: verdict adds **0.02%** overhead vs a 50ms connection RTT and **0.002%** vs a 500ms file transfer.

### Security Hardening (Cache & API)

The verdict caching layer and proxy intelligence API have been hardened against the following attack vectors:

| # | Vulnerability | Fix | Effect |
|---|--------------|-----|--------|
| 1 | **AI engine endpoints had no authentication** — blocklist/allowlist/dashboard/geo-feeds were unprotected | All `/api/v1/proxy/*` endpoints now require `X-Internal-Key` header validated against `platform.security.control-api-key` | Unauthorized callers cannot manipulate blocklists, allowlists, TOR node feeds, or read intelligence |
| 2 | **Cache key was IP-only** — verdict for SSH:22 was reused for FTP:21 | Composite cache key: `ip:port:protocol` | Each IP+port+protocol combination is evaluated independently |
| 3 | **ALLOW verdicts cached 5 minutes** — large window for attackers to exploit after clean probe | Risk-based asymmetric TTL: BLOCK=5min, THROTTLE=30s, ALLOW=10s (trusted=30s). Borderline verdicts (risk 30–59) are **never cached** | Stale ALLOW window reduced from 300s to 10–30s; borderline cases always re-evaluated |
| 4 | **No cross-service cache invalidation** — DMZ proxy retained stale ALLOW after AI engine blocked IP | DMZ proxy caps local TTL: ALLOW≤15s, THROTTLE≤60s, BLOCK≤300s regardless of AI engine response | Even if AI engine sends a long TTL, DMZ proxy enforces its own strict ceiling |
| 5 | **Verdict audit trail leaked full IPs** — `/verdicts` endpoint exposed exact IPs with risk scores | IPs masked in ring buffer: `192.168.1.100` → `192.168.1.***` | Audit trail useful for pattern analysis without exposing exact intelligence targets |
| 6 | **DMZ→AI engine communication unauthenticated** — any network peer could forge verdicts | All HTTP requests from DMZ proxy to AI engine include `X-Internal-Key` header | Man-in-the-middle or rogue services on the network cannot manipulate verdicts |
| 7 | **Actuator heapdump could expose cache data** — IPs, verdicts, signals stored in cleartext heap | `heapdump`, `env`, `configprops` endpoints disabled; only `health`, `prometheus`, `info` exposed | Heap inspection cannot leak intelligence data |

**Architecture principle**: the verdict cache follows an *asymmetric trust model* — denials (BLOCK/BLACKHOLE) are cached aggressively because false-positives are correctable, while permissions (ALLOW) are cached minimally because false-negatives are exploitable.

### Security Hardening (Input Validation & Defense-in-Depth)

Additional hardening against input validation attacks, reputation manipulation, and reconnaissance:

| # | Vulnerability | Fix | Effect |
|---|--------------|-----|--------|
| 8 | **Reputation manipulation via fake events** — attacker submits AUTH_FAILURE events to auto-blocklist victim IPs | Per-IP event rate limiting: max 30 events/IP/minute. Excess events silently dropped | Bounds damage from event flooding; 170 of 200 flood events are discarded |
| 9 | **No input validation on endpoints** — sourceIp, targetPort, eventType, metadata all accepted without checks | Full validation: IPv4/IPv6 regex + octet range, port 0–65535, event type whitelist (6 values), metadata capped at 50 keys, country code alpha-only max 3 chars | Invalid, oversized, or injection payloads rejected at controller boundary |
| 10 | **Log injection via IP field** — attacker sends `"10.0.0.1\n[WARN] Fake entry"` as sourceIp | Control characters stripped from all string inputs; IPs validated against regex before any processing; log messages use `maskIp()` | Forged log entries impossible; real IPs never appear in logs |
| 11 | **Timing attack on API key** — `String.equals()` leaks key length via response timing | Both controllers use `MessageDigest.isEqual()` for constant-time comparison | Brute-force attacks cannot extract key character-by-character |
| 12 | **SSRF via port mapping** — management API allows mapping to `127.0.0.1`, `169.254.169.254` (cloud metadata) | `validateMapping()` blocks loopback, link-local, cloud metadata, and `0.0.0.0` targets | Cannot pivot to internal services or cloud credential endpoints |
| 13 | **Error messages leak internal state** — `e.getMessage()` returned in REST responses | Generic error messages returned; full exceptions logged server-side only | Attackers cannot extract Java class names, stack details, or internal paths |
| 14 | **CORS allows all origins with credentials** — any website can make authenticated requests | CORS restricted to `http(s)://localhost:*`; production deployments must override | Cross-site credential theft prevented |
| 15 | **HTTP client follows redirects** — compromised AI engine URL could redirect to SSRF target | `HttpClient.Redirect.NEVER` on DMZ proxy's verdict client | Redirect-chain SSRF attacks blocked |
| 16 | **Race condition in reputation scoring** — `setScore()` not synchronized while `adjustScore()` was | `setScore()` now `synchronized`, consistent with `adjustScore()` | Concurrent decay + failure events cannot corrupt score |

### Security Hardening (Production Safety Guards & Transport Security)

Runtime guards that prevent insecure configurations from reaching production and enforce transport security:

| # | Vulnerability | Fix | Effect |
|---|--------------|-----|--------|
| 17 | **Default secrets ship in source** — JWT secret, control API key, and DB password all have weak defaults that work in production | `SecretSafetyValidator` component in shared module: blocks startup in PROD/STAGING/CERT if defaults detected; validates minimum secret lengths (JWT ≥ 32 chars, API key ≥ 16 chars) | Platform cannot start with known-weak credentials in production |
| 18 | **GeoIP resolver uses plain HTTP** — IP addresses sent to ip-api.com over unencrypted HTTP | Default changed to HTTPS; redirect prevention (`HttpClient.Redirect.NEVER`); startup validation blocks loopback/internal URLs | GeoIP lookups encrypted in transit; SSRF via redirect impossible |
| 19 | **FTPS keystore password "changeit"** — Java default password allows key extraction with filesystem access | Production guard in `FtpsConfig`: throws `IllegalStateException` in PROD/STAGING/CERT if password is "changeit"; warns in DEV/TEST | FTPS cannot start with default keystore password in production |
| 20 | **`tlsTrustAll` bypasses certificate validation** — disabling TLS verification enables MITM attacks | Production guard in `HttpForwarderService`: throws `SecurityException` in PROD/STAGING/CERT; warns in DEV/TEST | Certificate validation cannot be bypassed in production environments |
| 21 | **Service URLs use plain HTTP** — all 17 inter-service URLs default to `http://` | `SecretSafetyValidator` scans all `ServiceClientProperties` URLs; logs ERROR in production for HTTP URLs | Visibility into HTTP-only service communication; prerequisite for mTLS migration |
| 22 | **OpenTelemetry data in plaintext** — traces and metrics sent to collector over HTTP with `tls.insecure: true` | Production Helm values changed to `https://`; OTLP exporter `insecure` changed to `false` | Observability data encrypted in transit |

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

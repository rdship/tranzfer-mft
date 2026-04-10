# TranzFer MFT -- Complete Feature Guide

> Definitive technical reference for every service, feature, API endpoint, UI page, and configuration property in the TranzFer Managed File Transfer platform.

---

## Table of Contents

- [Platform Overview](#platform-overview)
- [Service Map](#service-map)
- [Infrastructure Dependencies](#infrastructure-dependencies)
- [Feature Guide by Service](#feature-guide-by-service)
  - [1. Onboarding API (Port 8080)](#1-onboarding-api-port-8080)
  - [2. Config Service (Port 8084)](#2-config-service-port-8084)
  - [3. Gateway Service (Port 8085)](#3-gateway-service-port-8085)
  - [4. Encryption Service (Port 8086)](#4-encryption-service-port-8086)
  - [5. External Forwarder Service (Port 8087)](#5-external-forwarder-service-port-8087)
  - [6. DMZ Proxy (Port 8088)](#6-dmz-proxy-port-8088)
  - [7. License Service (Port 8089)](#7-license-service-port-8089)
  - [8. Analytics Service (Port 8090)](#8-analytics-service-port-8090)
  - [9. AI Engine (Port 8091)](#9-ai-engine-port-8091)
  - [10. Screening Service (Port 8092)](#10-screening-service-port-8092)
  - [11. Keystore Manager (Port 8093)](#11-keystore-manager-port-8093)
  - [12. AS2 Service (Port 8094)](#12-as2-service-port-8094)
  - [13. EDI Converter (Port 8095)](#13-edi-converter-port-8095)
  - [14. Storage Manager (Port 8096)](#14-storage-manager-port-8096)
  - [15. Notification Service (Port 8097)](#15-notification-service-port-8097)
  - [16. Platform Sentinel (Port 8098)](#16-platform-sentinel-port-8098)
  - [17. SFTP Service (Port 8081)](#17-sftp-service-port-8081)
  - [18. FTP Service (Port 8082)](#18-ftp-service-port-8082)
  - [19. FTP-Web Service (Port 8083)](#19-ftp-web-service-port-8083)
  - [20. Shared Platform (Library)](#20-shared-platform-library)
- [Cross-Cutting Concerns](#cross-cutting-concerns)
- [Configuration Reference](#configuration-reference)
- [UI Page Map](#ui-page-map)
- [Production Security Checklist](#production-security-checklist)

---

## Platform Overview

### What Is TranzFer MFT

TranzFer MFT is a production-grade Managed File Transfer platform built as a Java 21 / Spring Boot microservices architecture. It handles secure file ingestion over SFTP, FTP, FTP-over-HTTPS, AS2, and AS4 protocols; processes files through configurable multi-step flows (encryption, compression, screening, EDI conversion, forwarding); and delivers them to internal or external destinations with full audit trails, blockchain-anchored non-repudiation, and compliance enforcement.

### Architecture Summary

- **22 Maven modules**: 19 deployable services + 1 shared library (split into shared-core, shared-platform, shared-tunnel)
- **Shared PostgreSQL database**: single database, Flyway-managed schema (V1 through V49+)
- **RabbitMQ event bus**: exchange `file-transfer.events`, binding key `account.*`
- **Redis**: caching, pub/sub for live activity, SFTP session registry, proxy group discovery, storage location registry
- **SPIFFE/SPIRE**: zero-trust workload identity for all service-to-service authentication (JWT-SVID and X.509-SVID)
- **3 frontend applications**: Admin UI (React, port 3000), Partner Portal (port 3002), FTP-Web UI (port 3001)
- **Observability stack**: Prometheus, Grafana, Loki, Promtail, AlertManager

### Quick Start

```bash
cd file-transfer-platform
docker compose up -d
```

This starts all infrastructure (PostgreSQL, Redis, RabbitMQ, SPIRE), all 19 services, and the UI. The `spire-init` container auto-bootstraps workload identities -- no manual steps required.

- Admin UI: http://localhost:3000
- Default login: `admin@tranzfer.io` / `admin123`
- RabbitMQ Management: http://localhost:15672 (guest/guest)
- Grafana: http://localhost:3030 (admin/admin)
- Prometheus: http://localhost:9090

---

## Service Map

| Service | Port | Protocol Ports | Database | Purpose | Failure Mode |
|---|---|---|---|---|---|
| onboarding-api | 8080 | -- | PostgreSQL | Core API: users, partners, accounts, flows, transfers | **Required** |
| sftp-service | 8081 | SSH: 2222 | PostgreSQL | SFTP file reception and virtual filesystem | **Required** |
| ftp-service | 8082 | FTP: 21, Passive: 21000-21010 | PostgreSQL | FTP/FTPS file reception | **Required** |
| ftp-web-service | 8083 | -- | PostgreSQL | Browser-based file upload/download over HTTPS | **Required** |
| config-service | 8084 | -- | PostgreSQL | Flow definitions, connectors, SLAs, security profiles | **Fail-fast** |
| gateway-service | 8085 | SSH: 2220, FTP: 2122 | PostgreSQL | Protocol gateway, user routing, legacy fallback | **Required** |
| encryption-service | 8086 | -- | PostgreSQL | AES-256-GCM and PGP encryption/decryption | **Fail-fast** |
| external-forwarder-service | 8087 | -- | PostgreSQL | Outbound delivery: SFTP, FTP, HTTPS, AS2, AS4, Kafka | **Required** |
| dmz-proxy | 8088 | SSH: 2222, FTP: 21, HTTPS: 443, Tunnel: 9443 | **None** (Redis only) | Reverse proxy, rate limiting, security enforcement | **Required** |
| license-service | 8089 | -- | PostgreSQL | License validation, trial management, component catalog | Graceful (24h cache) |
| analytics-service | 8090 | -- | PostgreSQL | Dashboard aggregation, predictions, observatory | Graceful (empty) |
| ai-engine | 8091 | -- | PostgreSQL | Classification, anomaly detection, NLP, threat intel | Graceful (ALLOWED) |
| screening-service | 8092 | -- | PostgreSQL | OFAC/EU/UN sanctions, DLP, quarantine, AV scanning | **Fail-fast** |
| keystore-manager | 8093 | -- | PostgreSQL | Key/certificate generation, rotation, expiry monitoring | Graceful (local fallback) |
| as2-service | 8094 | -- | PostgreSQL | AS2/AS4 message reception and MDN handling | **Required** |
| edi-converter | 8095 | -- | **None** | EDI format detection, parsing, conversion, validation | Stateless |
| storage-manager | 8096 | -- | PostgreSQL | Content-addressable storage, dedup, tiered lifecycle | **Fail-fast** |
| notification-service | 8097 | -- | PostgreSQL | Email, Slack, Teams, webhook notifications | **Required** |
| platform-sentinel | 8098 | -- | PostgreSQL | Security/performance analysis, health scoring, alerting | **Required** |

---

## Infrastructure Dependencies

| Component | Docker Image | Port | Purpose |
|---|---|---|---|
| PostgreSQL | postgres:16-alpine | 5432 | Shared relational database (max_connections=300) |
| Redis | redis:7-alpine | 6379 | Caching, pub/sub, session registry, proxy discovery |
| RabbitMQ | rabbitmq:3.13-management-alpine | 5672 / 15672 | Event bus (file-transfer.events exchange) |
| SPIRE Server | ghcr.io/spiffe/spire-server:1.9.6 | -- | Workload identity authority (trust domain: filetransfer.io) |
| SPIRE Agent | ghcr.io/spiffe/spire-agent:1.9.6 | -- | Issues JWT-SVIDs/X.509-SVIDs to services |
| Prometheus | prom/prometheus:v2.51.0 | 9090 | Metrics collection (30d retention) |
| Grafana | grafana/grafana:10.4.0 | 3030 | Dashboards and visualization |
| Loki | grafana/loki:2.9.0 | 3100 | Log aggregation |
| AlertManager | prom/alertmanager:v0.27.0 | 9093 | Alert routing |
| MinIO (optional) | minio/minio:latest | 9000 / 9001 | S3-compatible storage (profile: minio) |

---

## Feature Guide by Service

---

### 1. Onboarding API (Port 8080)

The central orchestration service. Handles user authentication, partner lifecycle, transfer account management, flow execution control, and the public Transfer API. All admin UI requests route through this service.

---

#### 1.1 User Authentication & Management

**What it does**: Manages platform users with JWT-based authentication, role-based access control (ADMIN/USER roles), and optional TOTP two-factor authentication. User registration creates an account with hashed credentials; login returns a JWT token valid for 15 minutes.

**API Endpoints** (Service: onboarding-api, base port 8080):

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/register` | Register a new user (email, password, role) |
| POST | `/api/auth/login` | Authenticate and receive JWT token |
| GET | `/api/users` | List all users (ADMIN) |
| PATCH | `/api/users/{id}` | Update user properties |
| DELETE | `/api/users/{id}` | Delete a user |

**TOTP Two-Factor Authentication**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/2fa/enable` | Generate TOTP secret, return QR code URI |
| POST | `/api/2fa/verify` | Verify a TOTP code to complete enrollment |
| GET | `/api/2fa/status/{username}` | Check if 2FA is enabled for a user |
| POST | `/api/2fa/disable` | Disable 2FA for a user |

**UI Pages**:
- `/login` -- Login page
- `/users` -- User management (sidebar: Partners & Accounts > Users, ADMIN only)
- `/2fa` -- Two-factor authentication setup (sidebar: Security & Compliance > Two-Factor Auth)

**Configuration**:
```yaml
jwt:
  secret: ${JWT_SECRET:change_me_in_production_256bit_secret_key!!}
  expiration: 900000  # 15 minutes in milliseconds
```

**How to integrate**:
1. POST `/api/auth/register` with `{ "email": "user@example.com", "password": "...", "role": "ADMIN" }`
2. POST `/api/auth/login` with the same credentials; extract the `token` from the response
3. Include `Authorization: Bearer <token>` on all subsequent requests
4. Optionally enable 2FA: POST `/api/2fa/enable`, then scan the QR code and verify with POST `/api/2fa/verify`

**Dependencies**: None (self-contained authentication).

---

#### 1.2 Partner Management

**What it does**: Partners represent external organizations that exchange files with your platform. Each partner has a lifecycle (PENDING -> ACTIVE -> SUSPENDED) and can own multiple transfer accounts. The onboarding wizard creates a partner, its accounts, folder mappings, and server assignments in a single transaction.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/partners` | Create a new partner |
| GET | `/api/partners` | List all partners |
| GET | `/api/partners/{id}` | Get partner details |
| PUT | `/api/partners/{id}` | Update partner |
| DELETE | `/api/partners/{id}` | Delete partner |
| POST | `/api/partners/{id}/activate` | Activate partner |
| POST | `/api/partners/{id}/suspend` | Suspend partner |
| GET | `/api/partners/stats` | Partner statistics |
| GET | `/api/partners/{id}/accounts` | List partner's accounts |
| POST | `/api/partners/{id}/accounts` | Create account for partner |
| GET | `/api/partners/{id}/flows` | List partner's flows |
| GET | `/api/partners/{id}/endpoints` | List partner's endpoints |

**Unified Onboarding (single-call wizard)**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/onboard` | Create partner + account + folders + server assignment in one call |

**Partner Webhooks**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/partner-webhooks` | List webhooks |
| POST | `/api/partner-webhooks` | Create webhook |
| PUT | `/api/partner-webhooks/{id}` | Update webhook |
| DELETE | `/api/partner-webhooks/{id}` | Delete webhook |
| POST | `/api/partner-webhooks/{id}/test` | Test webhook delivery |

**UI Pages**:
- `/partners` -- Partner list and detail view (sidebar: Partners & Accounts > Partner Management)
- `/partners/:id` -- Partner detail page with accounts, flows, endpoints tabs
- `/partner-setup` -- Onboarding wizard (sidebar: Partners & Accounts > Onboard Partner, ADMIN only)

**Configuration**: No service-specific config. Uses standard database and JWT settings.

**How to implement**:
1. For manual setup: POST `/api/partners` with name, contact info, then create accounts separately
2. For quick onboarding: POST `/api/v1/onboard` with a combined payload containing partner details, account credentials, folder paths, and server assignments
3. Activate the partner: POST `/api/partners/{id}/activate`
4. Optionally register webhooks to notify the partner of transfer events

**Dependencies**: config-service (for flows), sftp-service/ftp-service (for account provisioning on protocol servers).

---

#### 1.3 Transfer Account Management

**What it does**: Transfer accounts are the credentials that partners use to connect via SFTP, FTP, or FTP-Web. Each account has a username, password (hashed), optional SSH public key, QoS settings, and is assigned to one or more server instances. Accounts are always scoped to a partner.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/accounts` | List all transfer accounts (supports ?partnerId filter) |
| POST | `/api/accounts` | Create a transfer account |
| GET | `/api/accounts/{id}` | Get account details |
| PUT | `/api/accounts/{id}` | Full update |
| PATCH | `/api/accounts/{id}` | Partial update (e.g., change password) |
| DELETE | `/api/accounts/{id}` | Delete account |

**Server-Account Assignments**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/servers/{serverId}/accounts` | List accounts on a server |
| POST | `/api/servers/{serverId}/accounts/{accountId}` | Assign account to server |
| PUT | `/api/servers/{serverId}/accounts/{accountId}` | Update assignment (home dir, etc.) |
| DELETE | `/api/servers/{serverId}/accounts/{accountId}` | Remove assignment |
| POST | `/api/servers/{serverId}/accounts/bulk` | Bulk assign accounts |
| GET | `/api/servers/{serverId}/access-check/{username}` | Check if user has access |
| GET | `/api/accounts/{accountId}/servers` | List servers an account is assigned to |
| POST | `/api/accounts/{accountId}/servers/{serverId}` | Assign from account side |
| DELETE | `/api/accounts/{accountId}/servers/{serverId}` | Remove from account side |
| POST | `/api/servers/{serverId}/maintenance` | Toggle maintenance mode |

**UI Page**: `/accounts` -- Transfer Accounts (sidebar: Partners & Accounts > Transfer Accounts)

**How to implement**:
1. Create an account: POST `/api/accounts` with `{ "username": "partner1", "password": "...", "partnerId": "<uuid>", "protocol": "SFTP" }`
2. Assign to a server: POST `/api/servers/{serverId}/accounts/{accountId}`
3. The partner can now connect to the assigned protocol server with these credentials

**Dependencies**: sftp-service, ftp-service, ftp-web-service (account provisioning is pushed to protocol servers via REST).

---

#### 1.4 Server Instance Management

**What it does**: Server instances represent the running protocol servers (SFTP, FTP, FTP-Web, AS2, Gateway) in the cluster. Each instance has an ID, protocol type, host, port, and maintenance mode flag. Used for capacity planning and account assignment targeting.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/servers` | List all server instances (supports ?protocol filter) |
| GET | `/api/servers/{id}` | Get server instance by UUID |
| GET | `/api/servers/by-instance/{instanceId}` | Get by instance ID (e.g., "sftp-1") |
| POST | `/api/servers` | Register a new server instance |
| PATCH | `/api/servers/{id}` | Update server properties |
| DELETE | `/api/servers/{id}` | Deregister server |

**UI Page**: `/server-instances` -- Server Instances (sidebar: Servers & Infrastructure > Server Instances, ADMIN only)

**Configuration**:
```yaml
# Each protocol service registers itself with these identifiers
SFTP_INSTANCE_ID: sftp-1    # unique per replica
FTP_INSTANCE_ID: ftp-1
FTPWEB_INSTANCE_ID: ftpweb-1
AS2_INSTANCE_ID: as2-1
```

**Dependencies**: None (registry only).

---

#### 1.5 Folder Mappings

**What it does**: Folder mappings define routing rules that determine where incoming files are placed and where outbound files are picked up. Each mapping connects a source path pattern (e.g., `/inbound/**`) to a destination path on one or more accounts. They are the glue between protocol server file events and the flow engine.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/folder-mappings` | Create mapping |
| GET | `/api/folder-mappings` | List all (supports ?accountId filter) |
| GET | `/api/folder-mappings/{id}` | Get by ID |
| PUT | `/api/folder-mappings/{id}` | Update mapping |
| PATCH | `/api/folder-mappings/{id}/active` | Toggle active flag |
| DELETE | `/api/folder-mappings/{id}` | Delete mapping |

**UI Page**: `/folder-mappings` -- Folder Mappings (sidebar: File Processing > Folder Mappings)

**How to implement**:
1. Create a mapping: POST `/api/folder-mappings` with `{ "accountId": "<uuid>", "sourcePath": "/inbound", "destinationPath": "/outbound", "active": true }`
2. When a file lands in the source path on the assigned server, the flow engine picks it up and routes it

**Dependencies**: Accounts must exist and be assigned to servers.

---

#### 1.6 Flow Execution Engine

**What it does**: The flow execution engine runs file processing flows -- ordered sequences of steps (encrypt, compress, screen, convert, forward, etc.) that are applied to files matching a flow's criteria. This controller provides operational control: view execution status, restart failed flows, skip steps, terminate runaway flows, and manage approval gates.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/flow-executions/{trackId}` | Get execution details for a transfer |
| GET | `/api/flow-executions/flow-events/{trackId}` | Get flow event journal for a transfer |
| POST | `/api/flow-executions/{trackId}/restart` | Restart a failed execution |
| POST | `/api/flow-executions/bulk-restart` | Bulk restart multiple executions |
| POST | `/api/flow-executions/{trackId}/skip/{step}` | Skip a specific step |
| POST | `/api/flow-executions/{trackId}/restart/{step}` | Restart from a specific step |
| POST | `/api/flow-executions/{trackId}/terminate` | Terminate an execution |
| GET | `/api/flow-executions/pending-approvals` | List executions awaiting approval |
| POST | `/api/flow-executions/{trackId}/approve` | Approve a paused execution |
| POST | `/api/flow-executions/{trackId}/reject` | Reject a paused execution |
| GET | `/api/flow-executions/{trackId}/history` | Execution history (restarts, etc.) |
| GET | `/api/flow-executions/live-stats` | Live execution statistics |
| POST | `/api/flow-executions/{trackId}/schedule-retry` | Schedule automatic retry |
| DELETE | `/api/flow-executions/{trackId}/schedule-retry` | Cancel scheduled retry |
| GET | `/api/flow-executions/scheduled-retries` | List all scheduled retries |

**Flow Step Preview**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/flow-steps/{trackId}` | List all steps for a flow execution |
| GET | `/api/flow-steps/{trackId}/{stepIndex}` | Get details of a specific step |
| GET | `/api/flow-steps/{trackId}/{stepIndex}/{direction}/content` | Preview input/output content of a step |

**UI Pages**:
- `/flows` -- Processing Flows (sidebar: File Processing > Processing Flows) -- flow list with execution status
- `/journey` -- Transfer Journey (sidebar: Overview > Transfer Journey) -- end-to-end lifecycle view

**Configuration**:
```yaml
platform:
  flow:
    max-concurrent: ${FLOW_MAX_CONCURRENT:50}
    work-dir: ${FLOW_WORK_DIR:/tmp/mft-flow-work}
```

**How to implement**:
1. Flows are defined in config-service (see Section 2.1). When a file arrives, the engine matches it against flow criteria.
2. Monitor executions: GET `/api/flow-executions/{trackId}`
3. If a step fails, either restart from that step: POST `/api/flow-executions/{trackId}/restart/{step}`, or skip it: POST `/api/flow-executions/{trackId}/skip/{step}`
4. For flows with APPROVE steps, check `/api/flow-executions/pending-approvals` and approve/reject

**Dependencies**: config-service (flow definitions), encryption-service, screening-service, storage-manager, external-forwarder-service, edi-converter, ai-engine (depending on which steps are in the flow).

---

#### 1.7 Activity Monitoring

**What it does**: Provides a searchable, filterable, paginated view of all file transfer activity. Supports filtering by date range, partner, account, status, protocol, and free-text search. Used by operations teams to monitor transfer health and troubleshoot issues.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/activity-monitor` | Search transfers with filters (date, partner, status, etc.) |

Query parameters: `from`, `to`, `partner`, `account`, `status`, `protocol`, `search`, `page`, `size`, `sort`.

**UI Page**: `/activity-monitor` -- Activity Monitor (sidebar: Overview > Activity Monitor)

**Dependencies**: None (queries the shared database directly).

---

#### 1.8 Transfer Journey Tracking

**What it does**: Provides an end-to-end lifecycle view of a single file transfer, from the moment it was received through every processing step to final delivery. Shows timing, byte counts, step outcomes, and error details. The journey view aggregates data from flow events, audit logs, and screening results.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/journey/{trackId}` | Full journey for a specific transfer (all events, steps, timings) |
| GET | `/api/journey` | Search journeys with filters |

**UI Page**: `/journey` -- Transfer Journey (sidebar: Overview > Transfer Journey)

**How to use**: Enter a track ID (e.g., `TRZ-20240115-abc123`) in the search box, or click a transfer in the Activity Monitor to navigate to its journey page.

**Dependencies**: Aggregates data from multiple services via the shared database.

---

#### 1.9 Dead Letter Queue Management

**What it does**: When RabbitMQ messages fail processing after exhausting retries, they are routed to the dead letter queue. This controller lets operators inspect, retry, or discard failed messages. Essential for diagnosing and recovering from transient failures.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/dlq/messages` | List all dead-lettered messages (paginated) |
| POST | `/api/dlq/messages/{id}/retry` | Retry a specific message |
| DELETE | `/api/dlq/messages/{id}` | Discard a message |
| POST | `/api/dlq/retry-all` | Retry all dead-lettered messages |

**UI Page**: `/dlq` -- Dead Letter Queue (sidebar: Administration > Dead Letter Queue, ADMIN only)

**Dependencies**: RabbitMQ.

---

#### 1.10 Peer-to-Peer Transfers

**What it does**: Enables direct file transfers between two authenticated users on the platform without requiring pre-configured folder mappings. Uses a ticket-based protocol: the sender creates a transfer ticket, the receiver validates it, and the file is transferred directly. Presence tracking shows which users are currently online.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/p2p/presence` | Announce presence (heartbeat) |
| GET | `/api/p2p/presence` | List online users |
| POST | `/api/p2p/tickets` | Create a transfer ticket |
| POST | `/api/p2p/tickets/{ticketId}/validate` | Validate a ticket (receiver) |
| POST | `/api/p2p/tickets/{ticketId}/complete` | Mark transfer complete |
| GET | `/api/p2p/tickets` | List tickets |

**UI Page**: `/p2p` -- P2P Transfers (sidebar: File Processing > P2P Transfers)

**Dependencies**: Redis (presence tracking).

---

#### 1.11 Blockchain Anchoring

**What it does**: Provides non-repudiation proof for file transfers by computing Merkle tree hashes over transfer metadata and file checksums, then anchoring the root hash to an immutable ledger. Supports three anchor modes: INTERNAL (local database), DOCUMENT (RFC 3161 timestamping), and ETHEREUM (smart contract, future). Each anchored transfer gets a verifiable proof chain.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/blockchain/verify/{trackId}` | Verify blockchain proof for a transfer |
| GET | `/api/v1/blockchain/anchors` | List all anchored transfers |

**UI Page**: `/blockchain` -- Blockchain Proof (sidebar: Security & Compliance > Blockchain Proof)

**Configuration**:
```yaml
blockchain:
  anchor-mode: ${BLOCKCHAIN_ANCHOR_MODE:INTERNAL}  # INTERNAL, DOCUMENT, ETHEREUM
  ethereum:
    rpc-url: ${ETHEREUM_RPC_URL:}
    contract-address: ${ETHEREUM_CONTRACT_ADDRESS:}
  timestamp-service-url: ${TIMESTAMP_SERVICE_URL:}
```

**How to implement**:
1. Set `BLOCKCHAIN_ANCHOR_MODE` to the desired mode
2. For DOCUMENT mode, configure `TIMESTAMP_SERVICE_URL` to a TSA (RFC 3161)
3. Anchoring happens automatically during flow execution when the blockchain step is included in a flow
4. Verify: GET `/api/v1/blockchain/verify/{trackId}` returns the Merkle proof chain and verification status

**Dependencies**: None for INTERNAL mode. External TSA for DOCUMENT mode. Ethereum node for ETHEREUM mode.

---

#### 1.12 Autonomous Onboarding

**What it does**: Uses AI to detect partner connection patterns from gateway/proxy logs and automatically suggests new partner configurations. When the system detects repeated connections from an unknown source that follow consistent patterns (same username format, same directories, regular schedule), it creates an onboarding suggestion that an admin can review and approve.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/auto-onboard/detect` | Trigger detection scan on recent connection logs |
| POST | `/api/v1/auto-onboard/learn` | Learn from a set of connections and generate suggestions |
| GET | `/api/v1/auto-onboard/sessions` | List all auto-onboard sessions |
| GET | `/api/v1/auto-onboard/sessions/{id}` | Get session details |
| POST | `/api/v1/auto-onboard/sessions/{id}/approve` | Approve and execute suggested onboarding |
| GET | `/api/v1/auto-onboard/stats` | Auto-onboarding statistics |

**UI Page**: `/auto-onboarding` -- Auto-Onboarding (sidebar: Intelligence > Auto-Onboarding, ADMIN only)

**Dependencies**: ai-engine (pattern detection), gateway-service (connection logs).

---

#### 1.13 Multi-Tenant Management

**What it does**: Supports multiple independent organizations (tenants) on a single platform instance. Each tenant gets an isolated namespace with its own partners, accounts, and data. Tenants are identified by a URL slug and have usage quotas (max partners, max storage, max transfers per day).

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/tenants/signup` | Register a new tenant |
| GET | `/api/v1/tenants` | List all tenants |
| GET | `/api/v1/tenants/{slug}` | Get tenant by slug |
| PUT | `/api/v1/tenants/{id}` | Update tenant |
| DELETE | `/api/v1/tenants/{id}` | Delete tenant |
| GET | `/api/v1/tenants/{slug}/usage` | Get tenant usage statistics |

**UI Page**: `/tenants` -- Multi-Tenant (sidebar: Administration > Multi-Tenant, ADMIN only)

**Dependencies**: None (tenant scoping is enforced in the shared database via tenant_id columns).

---

#### 1.14 Admin CLI

**What it does**: Provides a server-side command execution interface accessible from the Admin UI terminal page. Commands include user management, account operations, flow control, and diagnostic tools. The CLI executes commands in the context of the authenticated admin user.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/cli/execute` | Execute a CLI command and return output |

**UI Page**: `/terminal` -- Terminal (sidebar: Tools > Terminal, ADMIN only)

**Dependencies**: All other services (commands are proxied to the relevant service).

---

#### 1.15 Transfer API v2

**What it does**: A simplified, single-call API for programmatic file transfers. External systems can upload a file, specify source and destination, and the platform handles routing, flow execution, and delivery. Supports single file transfer, batch transfer, and transfer receipt retrieval.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v2/transfer` | Submit a file transfer (multipart/form-data) |
| GET | `/api/v2/transfer/{trackId}` | Get transfer status |
| POST | `/api/v2/transfer/batch` | Submit multiple transfers |
| GET | `/api/v2/transfer/{trackId}/receipt` | Get transfer receipt (proof of delivery) |

**How to integrate**:
```bash
curl -X POST http://localhost:8080/api/v2/transfer \
  -H "Authorization: Bearer <jwt>" \
  -F "file=@invoice.csv" \
  -F "source=partner1" \
  -F "destination=partner2" \
  -F "metadata={\"type\":\"invoice\",\"priority\":\"high\"}"
```

The response includes a `trackId` that can be used to poll status or retrieve a receipt.

**Dependencies**: All flow-related services (encryption, screening, forwarding, etc.).

---

#### 1.16 Platform Bootstrap & Service Registry

**What it does**: Manages seed data creation for new installations and tracks the health/registration of all running services. The service registry is populated automatically as services start and register via heartbeat.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/service-registry` | List all registered service instances with health status |

**UI Pages**:
- `/services` -- Service Health (sidebar: Administration > Service Health, ADMIN only)
- `/dashboard` -- Dashboard (sidebar: Overview > Dashboard) -- includes service health summary

**Dependencies**: All services report to the registry.

---

#### 1.17 Proxy Group Management

**What it does**: Manages logical groups of DMZ proxy instances. Proxy groups allow multiple proxy instances to be organized by purpose (INTERNAL, EXTERNAL, PARTNER, CLOUD, CUSTOM) with independent scaling and configuration. Each DMZ proxy instance self-registers in Redis under its group name.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/proxy-groups` | List all proxy groups |
| GET | `/api/proxy-groups/{id}` | Get group details |
| GET | `/api/proxy-groups/discover` | Discover live proxy instances from Redis |
| GET | `/api/proxy-groups/by-type/{type}` | List groups by type |
| POST | `/api/proxy-groups` | Create proxy group |
| PUT | `/api/proxy-groups/{id}` | Update proxy group |
| DELETE | `/api/proxy-groups/{id}` | Delete proxy group |

**UI Page**: `/proxy-groups` -- Proxy Groups (sidebar: Servers & Infrastructure > Proxy Groups, ADMIN only)

**Configuration** (on each dmz-proxy instance):
```yaml
proxy:
  group:
    name: ${PROXY_GROUP_NAME:internal}
    type: ${PROXY_GROUP_TYPE:INTERNAL}     # INTERNAL, EXTERNAL, PARTNER, CLOUD, CUSTOM
    instance-id: ${PROXY_INSTANCE_ID:}     # auto-UUID if not set
```

**Dependencies**: Redis (discovery), dmz-proxy instances (registration).

---

#### 1.18 Partner Portal

**What it does**: A self-service portal for external partners. Partners log in with their transfer account credentials (not admin credentials) and can view their transfer history, track individual transfers, download receipts, test their connection, rotate SSH keys, change passwords, and view SLA agreements.

**API Endpoints** (all under `/api/partner`):

| Method | Path | Description |
|---|---|---|
| POST | `/api/partner/login` | Partner login (returns JWT scoped to partner) |
| GET | `/api/partner/dashboard` | Partner dashboard (transfer counts, recent activity) |
| GET | `/api/partner/transfers` | List partner's transfers (paginated, filtered) |
| GET | `/api/partner/track/{trackId}` | Track a specific transfer |
| GET | `/api/partner/receipt/{trackId}` | Download transfer receipt |
| GET | `/api/partner/test-connection` | Test SFTP/FTP connectivity |
| POST | `/api/partner/rotate-key` | Rotate SSH public key |
| POST | `/api/partner/change-password` | Change password |
| GET | `/api/partner/sla` | View SLA agreement |

**UI Pages** (separate layout, no admin sidebar):
- `/portal/login` -- Partner portal login
- `/portal` -- Partner dashboard (index)
- `/portal/transfers` -- Transfer history
- `/portal/settings` -- Connection settings, key rotation, password change

**Dependencies**: onboarding-api (same service, different auth context).

---

#### 1.19 Cluster Management

**What it does**: Manages multi-instance deployment topology. Each service registers in a cluster with a unique host identifier, service type, and communication mode. Supports cluster topology visualization and communication mode switching (WITHIN_CLUSTER for local, CROSS_CLUSTER for WAN).

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/clusters` | List all clusters |
| GET | `/api/clusters/{clusterId}` | Get cluster details |
| PUT | `/api/clusters/{clusterId}` | Update cluster settings |
| GET | `/api/clusters/communication-mode` | Get current communication mode |
| PUT | `/api/clusters/communication-mode` | Change communication mode |
| GET | `/api/clusters/topology` | Get full cluster topology (all services, all instances) |
| GET | `/api/clusters/live` | Live cluster status |

**UI Page**: `/cluster` -- Cluster (sidebar: Servers & Infrastructure > Cluster, ADMIN only)

**Configuration**:
```yaml
cluster:
  id: ${CLUSTER_ID:default-cluster}
  host: ${CLUSTER_HOST:localhost}
  service-type: ONBOARDING          # varies per service
  communication-mode: ${CLUSTER_COMM_MODE:WITHIN_CLUSTER}
```

---

#### 1.20 Audit Logs

**What it does**: Provides read access to the platform audit log. Every significant action (user login, account creation, flow execution, configuration change) is logged with timestamp, actor, action type, and affected entity.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/audit-logs` | List audit logs (paginated, filterable) |

**UI Page**: `/logs` -- Logs (sidebar: Administration > Logs)

---

#### 1.21 Snapshot Retention

**What it does**: Manages retention policies for platform configuration snapshots. Snapshots capture the state of all settings at a point in time and can be used for rollback. Retention policies automatically purge old snapshots.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/snapshot-retention` | Get current retention settings |
| PUT | `/api/snapshot-retention` | Update retention settings |
| POST | `/api/snapshot-retention/purge-now` | Immediately purge expired snapshots |

---

#### 1.22 Dry Run / Flow Testing

**What it does**: Allows testing a file against flow matching criteria without actually processing it. Returns which flow would match, what steps would execute, and estimated timing.

**Controller**: `DryRunController` -- provides simulation endpoints for flow testing.

---

---

### 2. Config Service (Port 8084)

Manages all platform configuration: file flow definitions, webhook connectors, SLA agreements, external destinations, security profiles, compliance profiles, folder templates, notification rules, platform settings, scheduler, VFS storage, and migration center.

---

#### 2.1 File Flow Management

**What it does**: File flows define the processing pipeline applied to files. Each flow has match criteria (filename pattern, source account, directory, file size, content type), an ordered list of processing steps, priority for conflict resolution, and an enabled flag. When a file arrives, the engine evaluates all active flows and executes the highest-priority match.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/flows` | List all flows |
| GET | `/api/flows/{id}` | Get flow details |
| POST | `/api/flows` | Create a new flow |
| PUT | `/api/flows/{id}` | Update flow |
| PATCH | `/api/flows/{id}/toggle` | Toggle flow enabled/disabled |
| DELETE | `/api/flows/{id}` | Delete flow |
| GET | `/api/flows/executions` | List recent flow executions |
| GET | `/api/flows/executions/{trackId}` | Get execution for a specific track ID |
| GET | `/api/flows/step-types` | List available step types |
| GET | `/api/flows/functions/catalog` | List all registered FlowFunction plugins |
| POST | `/api/flows/functions/import` | Import a FlowFunction plugin (gRPC or WASM) |
| GET | `/api/flows/functions/{type}/export` | Export a FlowFunction definition |
| GET | `/api/flows/match-fields` | List available match criteria fields |
| POST | `/api/flows/validate-criteria` | Validate match criteria syntax |
| POST | `/api/flows/test-match` | Test a file against match criteria |

**Built-in FlowFunction Step Types** (17 built-in):
- `COMPRESS_GZIP` / `DECOMPRESS_GZIP` -- GZip compression/decompression (streaming I/O)
- `COMPRESS_ZIP` / `DECOMPRESS_ZIP` -- ZIP compression/decompression (streaming I/O)
- `ENCRYPT_PGP` / `DECRYPT_PGP` -- PGP encryption/decryption (materializing I/O)
- `ENCRYPT_AES` / `DECRYPT_AES` -- AES-256 encryption/decryption (materializing I/O)
- `RENAME` -- File rename with pattern substitution (passthrough I/O)
- `SCREEN` -- Sanctions/DLP screening (materializing I/O)
- `EXECUTE_SCRIPT` -- Run external script (passthrough I/O)
- `MAILBOX` -- Route to mailbox folder (passthrough I/O)
- `FILE_DELIVERY` -- Forward to external destination (passthrough I/O)
- `CONVERT_EDI` -- EDI format conversion (materializing I/O)
- `ROUTE` -- Conditional routing based on file properties (passthrough I/O)
- `APPROVE` -- Pause execution pending human approval (passthrough I/O)
- `CHECKSUM_VERIFY` -- Verify file integrity via SHA-256 checksum

**Extensible Plugins**: gRPC adapters (remote services) and WASM adapters (sandboxed local execution).

**UI Page**: `/flows` -- Processing Flows (sidebar: File Processing > Processing Flows)

**How to implement**:
1. GET `/api/flows/step-types` to see available steps
2. POST `/api/flows` with match criteria and steps array:
   ```json
   {
     "name": "PGP Encrypt and Forward",
     "matchCriteria": { "filenamePattern": "*.csv", "sourceAccount": "partner1" },
     "steps": [
       { "type": "ENCRYPT_PGP", "order": 1, "config": { "keyAlias": "partner1-pgp" } },
       { "type": "FILE_DELIVERY", "order": 2, "config": { "destinationId": "<uuid>" } }
     ],
     "priority": 100,
     "enabled": true
   }
   ```
3. The flow engine automatically picks up matching files

**Dependencies**: All step-specific services (encryption, screening, storage, forwarder, EDI converter).

---

#### 2.2 Webhook Connectors

**What it does**: Connectors integrate the platform with external notification systems. Supported types include Slack (Block Kit), Microsoft Teams (Adaptive Cards), PagerDuty, OpsGenie, and ServiceNow. Each connector is tested before saving to verify connectivity.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/connectors` | List all connectors |
| POST | `/api/connectors` | Create connector |
| PUT | `/api/connectors/{id}` | Update connector |
| DELETE | `/api/connectors/{id}` | Delete connector |
| POST | `/api/connectors/{id}/test` | Test connector (sends a test message) |
| GET | `/api/connectors/types` | List supported connector types |

**UI Page**: `/connectors` -- Connectors (sidebar: Notifications & Integrations > Connectors, ADMIN only)

**Dependencies**: External services (Slack, Teams, PagerDuty, etc.).

---

#### 2.3 SLA Agreements

**What it does**: Defines Service Level Agreements scoped to specific partners. Each SLA specifies acceptable transfer completion times by tier (GOLD, SILVER, BRONZE) and protocol. A background breach detector continuously monitors active transfers and flags SLA violations.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/sla` | List active SLAs |
| POST | `/api/sla` | Create SLA |
| PUT | `/api/sla/{id}` | Update SLA |
| DELETE | `/api/sla/{id}` | Delete SLA |
| GET | `/api/sla/breaches` | List currently active SLA breaches |

**UI Page**: `/sla` -- SLA Agreements (sidebar: Notifications & Integrations > SLA Agreements)

**Dependencies**: analytics-service (breach detection correlates with transfer timing data).

---

#### 2.4 External Destinations

**What it does**: Configures outbound delivery targets for file forwarding. Each destination specifies the protocol (SFTP, FTP, FTPS, HTTPS), host, port, credentials (encrypted at rest), and protocol-specific settings (e.g., passive mode for FTP, key-based auth for SFTP). Destinations are referenced by ID in flow FILE_DELIVERY steps.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/external-destinations` | List all destinations |
| GET | `/api/external-destinations/{id}` | Get destination by ID |
| POST | `/api/external-destinations` | Create destination |
| PUT | `/api/external-destinations/{id}` | Update destination |
| DELETE | `/api/external-destinations/{id}` | Delete destination |

**Delivery Endpoints** (extended destination model with retry, proxy, auth options):

| Method | Path | Description |
|---|---|---|
| GET | `/api/delivery-endpoints` | List endpoints (filterable by protocol, status) |
| GET | `/api/delivery-endpoints/{id}` | Get endpoint by ID |
| POST | `/api/delivery-endpoints` | Create endpoint |
| PUT | `/api/delivery-endpoints/{id}` | Update endpoint |
| PATCH | `/api/delivery-endpoints/{id}/toggle` | Toggle active/inactive |
| DELETE | `/api/delivery-endpoints/{id}` | Delete endpoint |
| GET | `/api/delivery-endpoints/summary` | Aggregated status summary |
| GET | `/api/delivery-endpoints/protocols` | List supported protocols |
| GET | `/api/delivery-endpoints/auth-types` | List supported auth types |
| GET | `/api/delivery-endpoints/proxy-types` | List supported proxy types |

**UI Page**: `/external-destinations` -- External Destinations (sidebar: File Processing > External Destinations)

**Dependencies**: encryption-service (credential encryption), external-forwarder-service (actual delivery).

---

#### 2.5 Security Profiles

**What it does**: Defines allowed cryptographic algorithms for SSH/TLS connections. Each profile specifies permitted ciphers, MACs, key exchange algorithms, and minimum TLS versions. Profiles are assigned to server instances and enforced by the gateway and protocol services.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/security-profiles` | List all profiles |
| GET | `/api/security-profiles/{id}` | Get profile by ID |
| POST | `/api/security-profiles` | Create profile |
| PUT | `/api/security-profiles/{id}` | Update profile |
| DELETE | `/api/security-profiles/{id}` | Delete profile |

**Listener Security Policies** (per-listener/per-destination overrides):

| Method | Path | Description |
|---|---|---|
| GET | `/api/listener-security-policies` | List all policies |
| GET | `/api/listener-security-policies/{id}` | Get by ID |
| GET | `/api/listener-security-policies/server/{serverId}` | Policies for a server |
| GET | `/api/listener-security-policies/destination/{destId}` | Policies for a destination |
| POST | `/api/listener-security-policies` | Create policy |
| PUT | `/api/listener-security-policies/{id}` | Update policy |
| DELETE | `/api/listener-security-policies/{id}` | Delete policy |

**UI Page**: `/security-profiles` -- Security Profiles (sidebar: Security & Compliance > Security Profiles, ADMIN only)

**Configuration** (gateway-service):
```yaml
gateway:
  sftp:
    algorithms:
      ciphers: ${GATEWAY_SFTP_CIPHERS:aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr}
      macs: ${GATEWAY_SFTP_MACS:hmac-sha2-512-etm@openssh.com,hmac-sha2-256-etm@openssh.com,hmac-sha2-512,hmac-sha2-256}
      kex: ${GATEWAY_SFTP_KEX:curve25519-sha256,ecdh-sha2-nistp521,ecdh-sha2-nistp384,diffie-hellman-group18-sha512,diffie-hellman-group16-sha512}
```

---

#### 2.6 Compliance Profiles

**What it does**: Enforces regulatory compliance rules on file transfers. Profiles define restrictions such as PCI-DSS (must encrypt card data), HIPAA/PHI (must screen before forward), PII (must classify and audit), geographic blocking (block transfers to/from specific countries), and business hours enforcement. Violations are tracked and can trigger notifications.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/compliance/profiles` | List active profiles |
| GET | `/api/compliance/profiles/all` | List all profiles including inactive |
| GET | `/api/compliance/profiles/{id}` | Get profile by ID |
| POST | `/api/compliance/profiles` | Create profile |
| PUT | `/api/compliance/profiles/{id}` | Update profile |
| DELETE | `/api/compliance/profiles/{id}` | Delete profile |
| GET | `/api/compliance/violations` | List violations (paginated) |
| GET | `/api/compliance/violations/count` | Unresolved violation count |
| POST | `/api/compliance/violations/{id}/resolve` | Resolve a violation |
| GET | `/api/compliance/violations/server/{serverId}` | Violations for a server |
| GET | `/api/compliance/violations/user/{username}` | Violations for a user |
| PUT | `/api/compliance/servers/{serverId}/profile` | Assign compliance profile to server |

**UI Page**: `/compliance` -- Compliance Profiles (sidebar: Security & Compliance > Compliance Profiles, ADMIN only; badge shows unresolved count)

**Dependencies**: ai-engine (data classification for PCI/PHI/PII detection), screening-service (sanctions).

---

#### 2.7 Folder Templates

**What it does**: Reusable directory structure templates that can be applied when onboarding new partners. A template defines a tree of folders (inbound, outbound, archive, error, etc.) with naming conventions. Templates can be exported to JSON and imported on another environment.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/folder-templates` | List all templates |
| GET | `/api/folder-templates/{id}` | Get template by ID |
| POST | `/api/folder-templates` | Create template |
| PUT | `/api/folder-templates/{id}` | Update template |
| DELETE | `/api/folder-templates/{id}` | Delete template |
| GET | `/api/folder-templates/{id}/export` | Export template as JSON |
| GET | `/api/folder-templates/export` | Export all templates |
| POST | `/api/folder-templates/import` | Import templates from JSON |

**UI Page**: `/folder-templates` -- Folder Templates (sidebar: File Processing > Folder Templates, ADMIN only)

---

#### 2.8 Notification Rules & Templates

**What it does**: See Notification Service (Section 15) for details. Config-service stores notification rules and templates in the shared database; notification-service reads them and dispatches messages.

---

#### 2.9 Platform Settings

**What it does**: Key-value configuration store for environment-specific settings. Supports multiple environments (DEV, STAGING, PROD), service-scoped settings, categories for organization, and cloning settings between environments.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/platform-settings` | List all settings (filterable by env, service, category) |
| GET | `/api/platform-settings/{id}` | Get setting by ID |
| POST | `/api/platform-settings` | Create setting |
| PUT | `/api/platform-settings/{id}` | Update setting |
| PATCH | `/api/platform-settings/{id}/value` | Update just the value |
| DELETE | `/api/platform-settings/{id}` | Delete setting |
| GET | `/api/platform-settings/environments` | List distinct environments |
| GET | `/api/platform-settings/services` | List distinct services |
| GET | `/api/platform-settings/categories` | List distinct categories |
| POST | `/api/platform-settings/clone` | Clone settings to another environment |

**UI Page**: `/platform-config` -- Platform Config (sidebar: Administration > Platform Config, ADMIN only)

---

#### 2.10 Scheduler

**What it does**: Cron-based task scheduler for automating recurring operations. Tasks can trigger flow executions, run cleanup jobs, refresh screening lists, or execute custom actions on a schedule.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/scheduler` | List enabled tasks (ordered by next run) |
| GET | `/api/scheduler/all` | List all tasks including disabled |
| GET | `/api/scheduler/{id}` | Get task by ID |
| POST | `/api/scheduler` | Create scheduled task |
| PUT | `/api/scheduler/{id}` | Update task |
| PATCH | `/api/scheduler/{id}/toggle` | Toggle enabled/disabled |
| DELETE | `/api/scheduler/{id}` | Delete task |

**UI Page**: `/scheduler` -- Scheduler (sidebar: Notifications & Integrations > Scheduler, ADMIN only)

---

#### 2.11 VFS Storage Configuration

**What it does**: Manages the Virtual Filesystem (VFS) layer that abstracts file storage across protocol services. Provides a dashboard of storage utilization, bucket configuration, WAIL (Write-Ahead Intent Log) health monitoring, and per-account usage tracking.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/vfs/dashboard` | VFS storage overview (total size, file counts, health) |
| GET | `/api/vfs/buckets` | List storage buckets |
| GET | `/api/vfs/intents/health` | WAIL health status |
| GET | `/api/vfs/intents/recent` | Recent WAIL intents |
| GET | `/api/vfs/accounts/{accountId}/usage` | Storage usage for a specific account |

**UI Page**: `/vfs-storage` -- VFS Storage (sidebar: Servers & Infrastructure > VFS Storage, ADMIN only)

---

#### 2.12 Migration Center

**What it does**: Manages the migration of partners from a legacy MFT system to TranzFer. Supports a phased approach: registration, shadow mode (dual-write to both systems), verification (compare results), and completion. Connection auditing tracks which partners are still using the legacy system.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/migration/dashboard` | Migration overview (counts by phase) |
| GET | `/api/v1/migration/partners` | List all migration partners |
| GET | `/api/v1/migration/partners/{id}` | Get migration partner details |
| POST | `/api/v1/migration/partners/{id}/start` | Start migration for a partner |
| POST | `/api/v1/migration/partners/{id}/shadow` | Enable shadow mode |
| DELETE | `/api/v1/migration/partners/{id}/shadow` | Disable shadow mode |
| POST | `/api/v1/migration/partners/{id}/verify` | Run verification |
| POST | `/api/v1/migration/partners/{id}/verify/record` | Record verification result |
| POST | `/api/v1/migration/partners/{id}/complete` | Mark migration complete |
| POST | `/api/v1/migration/partners/{id}/rollback` | Rollback to legacy |
| GET | `/api/v1/migration/partners/{id}/events` | Migration event log |
| GET | `/api/v1/migration/partners/{id}/connections` | Connection audit log |
| GET | `/api/v1/migration/connection-stats` | Aggregated connection statistics |
| POST | `/api/v1/migration/audit-connection` | Record a connection audit entry |

**UI Page**: `/migration` -- Migration Center (sidebar: Expert > Migration Center, ADMIN only)

**Dependencies**: gateway-service (connection routing), config-service legacy server configuration.

---

#### 2.13 Legacy Server Configuration

**What it does**: Registers legacy MFT servers so the gateway can route unknown users to them during migration. Each legacy server entry has a host, port, protocol, and optional credential mapping.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/legacy-servers` | List legacy servers |
| GET | `/api/legacy-servers/{id}` | Get by ID |
| POST | `/api/legacy-servers` | Register legacy server |
| PUT | `/api/legacy-servers/{id}` | Update legacy server |
| DELETE | `/api/legacy-servers/{id}` | Remove legacy server |

---

#### 2.14 Live Activity Monitor

**What it does**: Real-time view of active file transfers, recent events, and system snapshot. Uses RabbitMQ event streaming to provide a live dashboard of what is happening right now.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/activity/snapshot` | Current system snapshot (active counts, rates) |
| GET | `/api/activity/transfers` | Currently active transfers |
| GET | `/api/activity/events` | Recent events (default: last 50, configurable with ?limit) |

**UI Page**: `/activity` -- Live Activity (sidebar: Overview > Live Activity)

---

#### 2.15 Encryption Key Management (Config-level)

**What it does**: Manages PGP/AES key assignments per account. Associates encryption keys stored in the Keystore Manager with specific transfer accounts, so the correct key is used during flow execution.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/encryption-keys` | List all key assignments |
| GET | `/api/encryption-keys/{id}` | Get by ID |
| POST | `/api/encryption-keys` | Create key assignment |
| DELETE | `/api/encryption-keys/{id}` | Remove key assignment |

---

#### 2.16 AS2/AS4 Partnership Configuration

**What it does**: Manages AS2 and AS4 trading partner agreements. Each partnership defines the AS2-From, AS2-To, signing certificate, encryption certificate, MDN preferences, and message format settings.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/as2-partnerships` | List partnerships (filterable by type: AS2/AS4) |
| GET | `/api/as2-partnerships/{id}` | Get partnership by ID |
| POST | `/api/as2-partnerships` | Create partnership |
| PUT | `/api/as2-partnerships/{id}` | Update partnership |
| PATCH | `/api/as2-partnerships/{id}/toggle` | Toggle active/inactive |
| DELETE | `/api/as2-partnerships/{id}` | Delete partnership |

**UI Page**: `/as2-partnerships` -- AS2/AS4 Partnerships (sidebar: File Processing > AS2/AS4 Partnerships)

**Dependencies**: as2-service (runtime message handling), keystore-manager (certificates).

---

#### 2.17 Server Configuration (Config-level)

**What it does**: Config-service's own view of server instances with additional configuration metadata (active flag, protocol-specific settings).

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/servers` | List servers (config-service perspective) |
| GET | `/api/servers/{id}` | Get server |
| POST | `/api/servers` | Register server |
| PUT | `/api/servers/{id}` | Update server |
| PATCH | `/api/servers/{id}/active` | Toggle active flag |
| DELETE | `/api/servers/{id}` | Remove server |

---

---

### 3. Gateway Service (Port 8085)

The protocol gateway sits between external clients and internal protocol servers. It provides a single entry point for SFTP (port 2220) and FTP (port 2122), handles user routing (known users go to the platform, unknown users can be routed to legacy servers), and manages tunnel connections to DMZ proxies.

---

#### 3.1 Protocol Gateway

**What it does**: Accepts SFTP and FTP connections on unified ports and routes them to the appropriate internal protocol server. The gateway applies security profiles (algorithm whitelisting), validates user credentials against the platform database, and establishes proxied connections to the backend.

**Configuration**:
```yaml
gateway:
  sftp:
    port: ${GATEWAY_SFTP_PORT:2220}
    host-key-path: ${GATEWAY_HOST_KEY_PATH:./gateway_host_key}
    algorithms:
      ciphers: ${GATEWAY_SFTP_CIPHERS:aes256-gcm@openssh.com,...}
      macs: ${GATEWAY_SFTP_MACS:hmac-sha2-512-etm@openssh.com,...}
      kex: ${GATEWAY_SFTP_KEX:curve25519-sha256,...}
  ftp:
    port: ${GATEWAY_FTP_PORT:2121}
  internal-sftp-host: ${INTERNAL_SFTP_HOST:sftp-service}
```

---

#### 3.2 Gateway Status & Route Management

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/internal/gateway/status` | Gateway health and connection stats |
| GET | `/internal/gateway/legacy-servers` | List configured legacy servers |
| GET | `/internal/gateway/routes` | List active routing rules |
| GET | `/internal/gateway/stats` | Detailed connection statistics |

**Tunnel Management Proxy** (proxies DMZ management calls):

| Method | Path | Description |
|---|---|---|
| GET | `/api/proxy/status` | DMZ proxy status (via gateway tunnel) |
| GET | `/api/proxy/mappings` | List port mappings |
| POST | `/api/proxy/mappings` | Add port mapping |
| PUT | `/api/proxy/mappings/{name}` | Update mapping |
| DELETE | `/api/proxy/mappings/{name}` | Remove mapping |
| PUT | `/api/proxy/mappings/{name}/security-policy` | Set security policy per listener |

**UI Page**: `/gateway` -- Gateway & DMZ (sidebar: Servers & Infrastructure > Gateway & DMZ, ADMIN only)

**Configuration**:
```yaml
TUNNEL_ENABLED: ${TUNNEL_ENABLED:-false}          # Enable single-port tunnel to DMZ
TUNNEL_DMZ_HOST: dmz-proxy
TUNNEL_DMZ_PORT: "9443"
```

**Dependencies**: sftp-service, ftp-service, ftp-web-service (backend routing targets), dmz-proxy (when tunnel enabled).

---

---

### 4. Encryption Service (Port 8086)

Handles all cryptographic operations for the platform. Provides AES-256-GCM symmetric encryption, PGP asymmetric encryption/decryption, Base64 mode for REST payloads, and credential encryption for storing secrets in the database.

---

#### 4.1 AES-256-GCM Encryption/Decryption

**What it does**: Encrypts and decrypts files using AES-256-GCM (authenticated encryption). Used as a flow step for file-at-rest and file-in-transit protection. Supports both file-based (multipart upload) and Base64-encoded (JSON body) modes.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/encrypt/encrypt` | Encrypt a file (multipart/form-data) |
| POST | `/api/encrypt/decrypt` | Decrypt a file (multipart/form-data) |
| POST | `/api/encrypt/encrypt/base64` | Encrypt Base64-encoded data |
| POST | `/api/encrypt/decrypt/base64` | Decrypt Base64-encoded data |

---

#### 4.2 Credential Encryption

**What it does**: Encrypts and decrypts sensitive credentials (passwords, API keys) for secure storage in the database. Uses the platform master key.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/encrypt/credential/encrypt` | Encrypt a credential string |
| POST | `/api/encrypt/credential/decrypt` | Decrypt a credential string |

**Configuration**:
```yaml
encryption:
  master-key: ${ENCRYPTION_MASTER_KEY:0000000000000000000000000000000000000000000000000000000000000000}
```

> **CRITICAL**: The default master key is all zeros. You MUST set `ENCRYPTION_MASTER_KEY` to a 64-character hex string (256-bit key) in production.

**Dependencies**: None (standalone cryptographic service).

---

---

### 5. External Forwarder Service (Port 8087)

Handles all outbound file delivery to external systems. Supports SFTP, FTP/FTPS, HTTP/HTTPS, AS2, AS4 (with WS-Security signing), and Kafka protocols. Includes connection testing, transfer watchdog (stall detection), and retry logic.

---

#### 5.1 File Forwarding

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/forward/{destinationId}` | Forward file to destination (multipart) |
| POST | `/api/forward/{destinationId}/base64` | Forward Base64-encoded file |
| POST | `/api/forward/deliver/{endpointId}` | Deliver to delivery endpoint (multipart) |
| POST | `/api/forward/deliver/{endpointId}/base64` | Deliver Base64-encoded |
| POST | `/api/forward/test-connection` | Test connectivity to a destination |
| GET | `/api/forward/transfers/active` | List currently active transfers |
| GET | `/api/forward/health` | Forwarder health status |

**Configuration**:
```yaml
forwarder:
  transfer:
    stall-timeout-seconds: ${FORWARDER_STALL_TIMEOUT_SECONDS:30}
    watchdog-interval-ms: ${FORWARDER_WATCHDOG_INTERVAL_MS:5000}
    stall-extra-retries: ${FORWARDER_STALL_EXTRA_RETRIES:2}
  as4:
    signing-enabled: ${AS4_SIGNING_ENABLED:true}
    signing-secret: ${AS4_SIGNING_SECRET:platform-default-signing-key}
  kafka:
    enabled: ${FORWARDER_KAFKA_ENABLED:false}

spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

**Supported Protocols**:
- **SFTP**: Key-based or password authentication, configurable ciphers
- **FTP/FTPS**: Active or passive mode, explicit/implicit TLS
- **HTTP/HTTPS**: GET/POST/PUT with custom headers, basic/bearer/certificate auth
- **AS2**: With signed MDN (Message Disposition Notification) support
- **AS4**: With WS-Security XML signing via HMAC secret
- **Kafka**: Conditional (must enable `FORWARDER_KAFKA_ENABLED=true`)

**Transfer Watchdog**: Monitors active transfers and detects stalls (no data transferred for `stall-timeout-seconds`). Stalled transfers are interrupted and retried with `stall-extra-retries` additional attempts.

**Dependencies**: config-service (destination configuration), encryption-service (credential decryption), keystore-manager (certificates for AS2/TLS).

---

---

### 6. DMZ Proxy (Port 8088)

A reverse proxy designed for deployment in a DMZ (demilitarized zone). Provides TCP-level port mapping with AI-powered security, rate limiting, IP blacklisting/whitelisting, network zone enforcement, egress filtering, deep packet inspection, bandwidth QoS, and comprehensive audit logging. Does NOT use a database -- all state is in Redis.

---

#### 6.1 Port Mapping Management

**What it does**: Maps external-facing ports to internal service ports. Mappings can be added/removed at runtime without restart (hot-reload). Default mappings: SFTP (2222 -> gateway:2220), FTP (21 -> gateway:2122), FTP-Web (443 -> ftp-web-service:8083).

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/proxy/mappings` | List all port mappings |
| POST | `/api/proxy/mappings` | Add a port mapping (hot-add) |
| DELETE | `/api/proxy/mappings/{name}` | Remove a port mapping (hot-remove) |
| PUT | `/api/proxy/mappings/{name}/security-policy` | Set security tier per listener |
| GET | `/api/proxy/listeners` | List active TCP listeners |

**Default Mappings** (from application.yml):
```yaml
dmz:
  mappings:
    - name: sftp-gateway
      listen-port: 2222
      target-host: ${GATEWAY_HOST:gateway-service}
      target-port: 2220
    - name: ftp-gateway
      listen-port: 21
      target-host: ${GATEWAY_HOST:gateway-service}
      target-port: ${GATEWAY_FTP_PORT:2122}
    - name: ftp-web
      listen-port: 443
      target-host: ${FTPWEB_HOST:ftp-web-service}
      target-port: 8083
```

---

#### 6.2 Security Features

**Security Tiers** (per-listener):
- `RULES` -- Rule-based: rate limiting, IP blacklist/whitelist, zone enforcement
- `AI` -- Rule-based + AI anomaly detection (calls ai-engine for verdicts)
- `AI_LLM` -- All of the above + LLM-powered analysis for complex threats

**Rate Limiting**:
```yaml
dmz:
  security:
    default-rate-per-minute: ${DEFAULT_RATE_PER_MINUTE:60}
    default-max-concurrent: ${DEFAULT_MAX_CONCURRENT:20}
    default-max-bytes-per-minute: ${DEFAULT_MAX_BYTES_PER_MINUTE:500000000}
    global-rate-per-minute: ${GLOBAL_RATE_PER_MINUTE:10000}
```

**API Endpoints (Security)**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/proxy/security/stats` | Security statistics (blocked, allowed, flagged) |
| GET | `/api/proxy/security/connections` | Active connections |
| GET | `/api/proxy/security/ip/{ip}` | IP reputation details |
| GET | `/api/proxy/security/rate-limits` | Current rate limit state |
| GET | `/api/proxy/security/summary` | Security summary |
| PUT | `/api/proxy/mappings/{name}/security/blacklist` | Add IP to blacklist |
| DELETE | `/api/proxy/mappings/{name}/security/blacklist/{ip}` | Remove from blacklist |
| PUT | `/api/proxy/mappings/{name}/security/whitelist` | Add IP to whitelist |
| DELETE | `/api/proxy/mappings/{name}/security/whitelist/{ip}` | Remove from whitelist |
| POST | `/api/proxy/reachability` | Test if a host:port is reachable |

---

#### 6.3 Zone Enforcement & Egress Filtering

**Zone Enforcement**: Restricts connections based on source IP CIDR ranges (DMZ, INTERNAL, MANAGEMENT zones).

**Egress Filtering**: Prevents SSRF and data exfiltration by blocking connections to metadata services, loopback, link-local, and dangerous ports (SMTP, SMB, etc.).

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/proxy/zones/rules` | List zone enforcement rules |
| GET | `/api/proxy/zones/check` | Check if an IP is allowed by zone rules |
| GET | `/api/proxy/egress/stats` | Egress filter statistics |
| GET | `/api/proxy/egress/check` | Check if an outbound connection is allowed |

**Configuration**:
```yaml
dmz:
  zones:
    enabled: ${DMZ_ZONES_ENABLED:true}
    cidrs:
      DMZ: ["${DMZ_ZONE_CIDR:172.18.0.0/16}"]
      INTERNAL: ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"]
      MANAGEMENT: ["10.255.0.0/16"]
  egress:
    enabled: ${DMZ_EGRESS_ENABLED:true}
    block-metadata-service: true
    block-loopback: true
    block-link-local: true
    dns-pinning: true
    blocked-ports: ["25","53","135","137","138","139","445"]
```

---

#### 6.4 QoS & Health Checks

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/proxy/qos/stats` | Global QoS statistics |
| GET | `/api/proxy/qos/stats/{mappingName}` | Per-mapping QoS stats |
| GET | `/api/proxy/backends/health` | Backend health check results |
| GET | `/api/proxy/health` | Proxy overall health |
| GET | `/api/proxy/info` | Proxy instance info |
| GET | `/api/proxy/metrics` | Prometheus-format metrics |

---

#### 6.5 Audit Logging

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/proxy/audit/stats` | Audit log statistics |
| POST | `/api/proxy/audit/flush` | Force flush audit buffer to disk |

**Configuration**:
```yaml
dmz:
  audit:
    enabled: ${DMZ_AUDIT_ENABLED:true}
    log-directory: ${DMZ_AUDIT_DIR:./audit-logs}
    max-days: 90
    max-file-size-mb: 100
```

**UI Pages**:
- `/dmz-proxy` -- DMZ Proxy (sidebar: Servers & Infrastructure > DMZ Proxy, ADMIN only)

**Dependencies**: ai-engine (AI/AI_LLM security tiers), Redis (state, rate counters), keystore-manager (TLS certificates).

---

---

### 7. License Service (Port 8089)

Manages platform licensing with RSA-signed license keys, trial activation, component entitlement, and activation tracking. License validation results are cached for up to 24 hours for resilience.

---

#### 7.1 License Validation & Management

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/licenses/validate` | Validate a license key |
| POST | `/api/v1/licenses/trial` | Activate a trial license (30 days default) |
| POST | `/api/v1/licenses/issue` | Issue a new license (admin only, requires admin key) |
| GET | `/api/v1/licenses` | List all licenses |
| DELETE | `/api/v1/licenses/{licenseId}/revoke` | Revoke a license |
| GET | `/api/v1/licenses/{licenseId}/activations` | List activations for a license |
| GET | `/api/v1/licenses/health` | License service health |
| GET | `/api/v1/licenses/catalog/components` | List licensable components |
| GET | `/api/v1/licenses/catalog/tiers` | List license tiers (COMMUNITY, PROFESSIONAL, ENTERPRISE) |
| POST | `/api/v1/licenses/catalog/entitled` | Check if components are entitled under current license |

**UI Page**: `/license` -- License (sidebar: Administration > License, ADMIN only)

**Configuration**:
```yaml
license:
  trial-days: 30
  issuer-name: "TranzFer MFT Platform"
  admin-key: ${LICENSE_ADMIN_KEY:license_admin_secret_key}
  validation-cache-hours: 6
```

**Production Security**: The license service fails fast if `LICENSE_ADMIN_KEY` is set to the default value in a PROD environment.

**Dependencies**: None (standalone).

---

---

### 8. Analytics Service (Port 8090)

Provides dashboard aggregation, time-series metrics, scaling predictions, alert rules, and the Observatory (step latency analysis). All queries use windowed time ranges -- never `findAll`.

---

#### 8.1 Dashboard & Time-Series

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/analytics/dashboard` | Aggregated dashboard data (transfer counts, success rates, etc.) |
| GET | `/api/v1/analytics/timeseries` | Time-series data (params: metric, window, interval) |
| GET | `/api/v1/analytics/predictions` | Scaling predictions across all service types |
| GET | `/api/v1/analytics/predictions/{serviceType}` | Predictions for a specific service type |
| GET | `/api/v1/analytics/dedup-stats` | Content-addressable storage deduplication statistics |

---

#### 8.2 Alert Rules

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/analytics/alerts` | List all alert rules |
| POST | `/api/v1/analytics/alerts` | Create alert rule |
| PUT | `/api/v1/analytics/alerts/{id}` | Update alert rule |
| DELETE | `/api/v1/analytics/alerts/{id}` | Delete alert rule |

---

#### 8.3 Observatory

**What it does**: Analyzes step-level latency across all flow executions to identify bottlenecks. Shows which flow steps are slowest, which partners have the highest transfer times, and trend analysis.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/analytics/observatory` | Observatory dashboard (step latency breakdown) |
| GET | `/api/v1/analytics/observatory/step-latency` | Detailed step latency analysis |

**UI Pages**:
- `/analytics` -- Analytics (sidebar: Intelligence > Analytics)
- `/predictions` -- Predictions (sidebar: Intelligence > Predictions)
- `/observatory` -- Observatory (sidebar: Intelligence > Observatory, ADMIN only)

**Dependencies**: Shared database (reads flow execution and event data).

---

---

### 9. AI Engine (Port 8091)

The intelligence layer of the platform. Provides data classification (PCI/PHI/PII detection), anomaly detection (4-algorithm ensemble), NLP command translation (keyword fallback + optional Claude LLM), flow suggestions, risk scoring, threat intelligence, proxy intelligence, EDI processing, and AI-powered recommendations.

---

#### 9.1 Data Classification

**What it does**: Scans file content for sensitive data patterns using regex + heuristics. Detects credit card numbers (PCI), Social Security numbers (PII), medical record numbers (PHI), and custom patterns. Results drive compliance enforcement.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/ai/classify` | Classify a file (multipart) |
| POST | `/api/v1/ai/classify/text` | Classify text content |
| POST | `/api/v1/ai/validate` | Validate file content against rules |

**Configuration**:
```yaml
ai:
  classification:
    enabled: true
    scan-on-upload: true
    max-scan-size-mb: 100
    block-unencrypted-pci: true
```

---

#### 9.2 Anomaly Detection

**What it does**: Monitors transfer patterns using a 4-algorithm ensemble (statistical z-score, moving average deviation, time-of-day profiling, and volume spike detection). Anomalies are flagged when transfers deviate from established baselines by more than the configured threshold.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/ai/anomalies` | List detected anomalies (time-windowed) |

**Configuration**:
```yaml
ai:
  anomaly:
    enabled: true
    threshold-sigma: 3.0    # standard deviations before alert
    lookback-days: 30
```

---

#### 9.3 NLP Command Translation

**What it does**: Translates natural language commands into platform API calls. With keyword fallback (no LLM needed for common commands like "show transfers" or "list partners") and optional Claude LLM integration for complex queries.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/ai/nlp/command` | Translate natural language to API command |
| POST | `/api/v1/ai/nlp/suggest-flow` | Suggest a flow based on description |
| POST | `/api/v1/ai/nlp/explain` | Explain a flow in natural language |
| POST | `/api/v1/ai/ask` | General-purpose AI assistant |

**Configuration**:
```yaml
ai:
  llm:
    enabled: ${AI_LLM_ENABLED:false}    # Must be explicitly enabled
  claude:
    api-key: ${CLAUDE_API_KEY:}
    model: ${CLAUDE_MODEL:claude-sonnet-4-20250514}
    base-url: ${CLAUDE_BASE_URL:https://api.anthropic.com}
```

---

#### 9.4 Risk Scoring & Smart Retry

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/ai/risk-score` | Calculate risk score for a transfer/partner |
| POST | `/api/v1/ai/smart-retry` | AI-recommended retry strategy for failed transfers |
| POST | `/api/v1/ai/threat-score` | Threat score for a connection/IP |

---

#### 9.5 Threat Intelligence

**What it does**: Full-featured threat intelligence platform with indicators of compromise (IOC) management, threat hunting, MITRE ATT&CK mapping, attack chain visualization, knowledge graph, automated response playbooks, and incident management.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/threats/indicators` | List threat indicators (IOCs) |
| POST | `/api/v1/threats/indicators` | Create indicator |
| GET | `/api/v1/threats/indicators/{value}` | Look up specific indicator |
| POST | `/api/v1/threats/hunt` | Run a threat hunt query |
| POST | `/api/v1/threats/analyze/network` | Analyze network traffic for threats |
| POST | `/api/v1/threats/analyze/anomaly` | Analyze anomaly for threat correlation |
| GET | `/api/v1/threats/chains` | List attack chains |
| GET | `/api/v1/threats/chains/{entityId}` | Get chains for a specific entity |
| GET | `/api/v1/threats/mitre/coverage` | MITRE ATT&CK coverage report |
| GET | `/api/v1/threats/mitre/techniques` | List detected MITRE techniques |
| GET | `/api/v1/threats/mitre/technique/{id}` | Get specific technique details |
| GET | `/api/v1/threats/graph/stats` | Knowledge graph statistics |
| GET | `/api/v1/threats/graph/related/{ioc}` | Related entities for an IOC |
| GET | `/api/v1/threats/graph/path` | Find connection path between entities |
| GET | `/api/v1/threats/playbooks` | List response playbooks |
| POST | `/api/v1/threats/playbooks/{id}/trigger` | Trigger a playbook |
| GET | `/api/v1/threats/playbooks/executions` | Playbook execution history |
| GET | `/api/v1/threats/incidents` | List incidents |
| GET | `/api/v1/threats/incidents/{id}` | Get incident details |
| POST | `/api/v1/threats/incidents` | Create incident |
| PATCH | `/api/v1/threats/incidents/{id}` | Update incident |
| GET | `/api/v1/threats/agents` | List autonomous threat agents |
| POST | `/api/v1/threats/agents/{id}/trigger` | Trigger an agent |
| POST | `/api/v1/threats/agents/{id}/pause` | Pause an agent |
| GET | `/api/v1/threats/dashboard` | Threat intelligence dashboard |
| GET | `/api/v1/threats/geo/resolve/{ip}` | GeoIP resolution |
| GET | `/api/v1/threats/health` | Service health |

**Configuration**:
```yaml
ai:
  intelligence:
    enabled: ${AI_INTELLIGENCE_ENABLED:true}
    max-indicators: ${AI_INTEL_MAX_INDICATORS:500000}
    stale-days: ${AI_INTEL_STALE_DAYS:90}
```

**UI Page**: `/threat-intelligence` -- Threat Intel (sidebar: Intelligence > Threat Intel, ADMIN only)

---

#### 9.6 Proxy Intelligence

**What it does**: AI-powered security layer for the DMZ proxy. Analyzes connection patterns, issues ALLOW/DENY/THROTTLE verdicts, maintains dynamic blocklists, tracks IP reputation scores with decay, and performs geo-analysis (high-risk countries, Tor nodes, VPN prefixes).

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/proxy/verdict` | Request verdict for a connection |
| POST | `/api/v1/proxy/verdicts/batch` | Batch verdict requests |
| POST | `/api/v1/proxy/event` | Report a proxy event |
| POST | `/api/v1/proxy/events` | Report batch proxy events |
| GET | `/api/v1/proxy/blocklist` | Get current blocklist |
| POST | `/api/v1/proxy/blocklist` | Add IP to blocklist |
| DELETE | `/api/v1/proxy/blocklist/{ip}` | Remove from blocklist |
| GET | `/api/v1/proxy/allowlist` | Get current allowlist |
| POST | `/api/v1/proxy/allowlist` | Add IP to allowlist |
| GET | `/api/v1/proxy/ip/{ip}` | Full IP intelligence report |
| GET | `/api/v1/proxy/dashboard` | Proxy intelligence dashboard |
| GET | `/api/v1/proxy/verdicts` | Recent verdicts |
| POST | `/api/v1/proxy/geo/high-risk-countries` | Set high-risk country list |
| POST | `/api/v1/proxy/geo/tor-nodes` | Load Tor exit node list |
| POST | `/api/v1/proxy/geo/vpn-prefixes` | Load VPN prefix list |
| GET | `/api/v1/proxy/geo/stats` | Geo-analysis statistics |
| GET | `/api/v1/proxy/overhead-estimates` | AI processing overhead estimates |
| GET | `/api/v1/proxy/health` | Service health |

**Configuration**:
```yaml
ai:
  proxy:
    enabled: true
    decay-interval-ms: 300000
    max-tracked-ips: 100000
    max-connections-per-ip-per-minute: 60
    ddos-threshold-per-minute: 5000
```

**UI Page**: `/proxy-intelligence` -- Proxy Intel (sidebar: Intelligence > Proxy Intel, ADMIN only)

---

#### 9.7 EDI Processing (AI-powered)

**What it does**: AI-assisted EDI document detection, validation, and translation. Uses pattern matching and optional LLM to identify EDI document types and translate to/from JSON/CSV.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/edi/detect` | Detect EDI format |
| POST | `/api/v1/edi/validate` | Validate EDI document |
| POST | `/api/v1/edi/translate/json` | Translate EDI to JSON |
| POST | `/api/v1/edi/translate/csv` | Translate EDI to CSV |
| GET | `/api/v1/edi/types` | List supported EDI types |
| POST | `/api/v1/ai/detect-format` | AI format detection |

---

#### 9.8 EDI AI Training

**What it does**: Train the AI engine on partner-specific EDI mapping patterns. Manages training samples, sessions, and generates mapping rules that are cached and used by the EDI converter.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/edi/training/samples` | Submit training sample |
| POST | `/api/v1/edi/training/samples/bulk` | Bulk submit samples |
| GET | `/api/v1/edi/training/samples/counts` | Sample counts by category |
| GET | `/api/v1/edi/training/samples/{id}` | Get sample by ID |
| DELETE | `/api/v1/edi/training/samples/{id}` | Delete sample |
| POST | `/api/v1/edi/training/samples/{id}/validate` | Validate a sample |
| GET | `/api/v1/edi/training/samples/partner/{partnerId}` | Samples for a partner |
| POST | `/api/v1/edi/training/train` | Start a training session |
| POST | `/api/v1/edi/training/quick-train` | Quick train from minimal samples |
| GET | `/api/v1/edi/training/maps` | List generated maps |
| GET | `/api/v1/edi/training/maps/lookup` | Look up map by criteria |
| GET | `/api/v1/edi/training/maps/history` | Map version history |
| POST | `/api/v1/edi/training/maps/rollback` | Rollback map to previous version |
| POST | `/api/v1/edi/training/maps/refresh-cache` | Refresh cache |
| GET | `/api/v1/edi/training/sessions` | List training sessions |
| GET | `/api/v1/edi/training/sessions/map` | Session-to-map mapping |
| POST | `/api/v1/edi/training/seed` | Seed training data |

**Mapping Correction**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/edi/correction/sessions` | Start a correction session |
| GET | `/api/v1/edi/correction/sessions/{sessionId}` | Get correction session |
| GET | `/api/v1/edi/correction/sessions` | List correction sessions |
| POST | `/api/v1/edi/correction/sessions/{sessionId}/correct` | Submit a correction |
| POST | `/api/v1/edi/correction/sessions/{sessionId}/test` | Test corrected mapping |
| POST | `/api/v1/edi/correction/sessions/{sessionId}/approve` | Approve correction |
| POST | `/api/v1/edi/correction/sessions/{sessionId}/reject` | Reject correction |
| GET | `/api/v1/edi/correction/sessions/{sessionId}/history` | Correction history |

**UI Page**: `/edi-training` -- EDI AI Training (sidebar: Expert > EDI AI Training, ADMIN only)

---

#### 9.9 Recommendations & Self-Driving

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/ai/recommendations` | List AI-generated recommendations |
| GET | `/api/v1/ai/recommendations/summary` | Recommendation summary |
| GET | `/api/v1/ai/self-driving/actions` | List autonomous actions |
| GET | `/api/v1/ai/self-driving/status` | Self-driving mode status |
| GET | `/api/v1/ai/intelligence/signals` | Intelligence signals |
| GET | `/api/v1/ai/intelligence/status` | Intelligence system status |
| GET | `/api/v1/ai/partners` | AI partner profiles |
| GET | `/api/v1/ai/partners/{username}` | Specific partner AI profile |
| POST | `/api/v1/ai/partners/{username}/check` | Run AI check on partner |
| GET | `/api/v1/ai/sla/forecasts` | SLA breach forecasts |
| GET | `/api/v1/ai/remediation/actions` | Suggested remediation actions |

**UI Page**: `/recommendations` -- AI Recommendations (sidebar: Intelligence > AI Recommendations)

**Dependencies**: All other services (AI engine observes the entire platform).

---

---

### 10. Screening Service (Port 8092)

Performs regulatory compliance screening on file transfers and partner names. Includes OFAC/SDN screening (real HTTP download from treasury.gov), EU sanctions screening (CSV parser, 24h refresh), UN sanctions screening (XML parser, 24h refresh), DLP (Data Loss Prevention) policy enforcement, quarantine management, and AV scanning (placeholder for ClamAV).

---

#### 10.1 Sanctions Screening

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/screening/scan` | Full screening pipeline (sanctions + DLP + AV) |
| POST | `/api/v1/screening/scan/sanctions` | Sanctions-only screening |
| POST | `/api/v1/screening/scan/text` | Screen text content |
| GET | `/api/v1/screening/results/{trackId}` | Get screening results for a transfer |
| GET | `/api/v1/screening/results` | List all results |
| GET | `/api/v1/screening/hits` | List screening hits (matches) |
| POST | `/api/v1/screening/lists/refresh` | Force refresh of sanctions lists |
| GET | `/api/v1/screening/lists` | List loaded screening lists and stats |
| GET | `/api/v1/screening/health` | Service health |

**Configuration**:
```yaml
screening:
  quarantine:
    path: ${SCREENING_QUARANTINE_PATH:/data/quarantine}
  ofac:
    sdn-url: https://www.treasury.gov/ofac/downloads/sdn.csv
    refresh-hours: ${SCREENING_REFRESH_HOURS:24}
    enabled: true
  eu:
    enabled: ${EU_SANCTIONS_ENABLED:true}
    url: ${SANCTIONS_EU_URL:https://webgate.ec.europa.eu/...}
    fallback-to-builtin: true
  un:
    enabled: ${UN_SANCTIONS_ENABLED:true}
    url: ${SANCTIONS_UN_URL:https://scsanctions.un.org/resources/xml/en/consolidated.xml}
    fallback-to-builtin: true
  match-threshold: ${SCREENING_THRESHOLD:0.82}
  default-action: ${SCREENING_DEFAULT_ACTION:BLOCK}
```

---

#### 10.2 DLP (Data Loss Prevention)

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/dlp/policies` | List DLP policies |
| GET | `/api/v1/dlp/policies/{id}` | Get policy by ID |
| POST | `/api/v1/dlp/policies` | Create DLP policy |
| PUT | `/api/v1/dlp/policies/{id}` | Update policy |
| DELETE | `/api/v1/dlp/policies/{id}` | Delete policy |
| POST | `/api/v1/dlp/scan` | Scan content against DLP policies |

---

#### 10.3 Quarantine Management

**What it does**: Files flagged by screening are moved to quarantine. Admins can review quarantined files, release them (resume processing), or permanently delete them.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/quarantine` | List quarantined files (paginated) |
| GET | `/api/v1/quarantine/{id}` | Get quarantine entry details |
| POST | `/api/v1/quarantine/{id}/release` | Release file from quarantine |
| DELETE | `/api/v1/quarantine/{id}` | Permanently delete quarantined file |
| GET | `/api/v1/quarantine/stats` | Quarantine statistics |

**UI Pages**:
- `/screening` -- Screening & DLP (sidebar: Security & Compliance > Screening & DLP)
- `/quarantine` -- Quarantine (sidebar: Security & Compliance > Quarantine, ADMIN only)

**Configuration**:
```yaml
clamav:
  host: ${CLAMAV_HOST:localhost}
  port: ${CLAMAV_PORT:3310}
  timeout-ms: ${CLAMAV_TIMEOUT_MS:60000}
  chunk-size: ${CLAMAV_CHUNK_SIZE:8192}
```

**Dependencies**: None for sanctions. ClamAV container required for AV scanning (disabled on Apple Silicon -- no ARM64 image).

---

---

### 11. Keystore Manager (Port 8093)

Central key and certificate management service. All cryptographic material in the platform (SSH keys, AES keys, TLS certificates, HMAC keys, PGP key pairs) is generated, stored, rotated, and monitored through this service.

---

#### 11.1 Key Generation

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/keys/generate/ssh-host` | Generate SSH host key pair |
| POST | `/api/v1/keys/generate/ssh-user` | Generate SSH user key pair |
| POST | `/api/v1/keys/generate/aes` | Generate AES-256 key |
| POST | `/api/v1/keys/generate/tls` | Generate TLS certificate |
| POST | `/api/v1/keys/generate/hmac` | Generate HMAC key |
| POST | `/api/v1/keys/generate/pgp` | Generate PGP key pair |

---

#### 11.2 Key Management

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/keys` | List all keys (filterable by type, purpose, status) |
| GET | `/api/v1/keys/{alias}` | Get key metadata by alias |
| GET | `/api/v1/keys/{alias}/public` | Get public key material |
| POST | `/api/v1/keys/import` | Import an existing key |
| POST | `/api/v1/keys/{alias}/rotate` | Rotate a key (generates new, retires old) |
| DELETE | `/api/v1/keys/{alias}` | Delete a key |
| GET | `/api/v1/keys/{alias}/download` | Download key file |
| GET | `/api/v1/keys/stats` | Key inventory statistics |
| GET | `/api/v1/keys/expiring` | List keys expiring soon |
| GET | `/api/v1/keys/health` | Service health |
| GET | `/api/v1/keys/types` | List supported key types |

**UI Page**: `/keystore` -- Keystore Manager (sidebar: Security & Compliance > Keystore Manager, ADMIN only)

**Configuration**:
```yaml
# docker-compose.yml
KEYSTORE_MASTER_PASSWORD: ${KEYSTORE_MASTER_PASSWORD:-change-this-master-password}
```

**Dependencies**: None (standalone). Other services call Keystore Manager to fetch keys.

---

---

### 12. AS2 Service (Port 8094)

Handles AS2 (Applicability Statement 2) and AS4 inbound message reception and MDN (Message Disposition Notification) callback processing.

---

#### 12.1 AS2 Message Handling

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/as2/receive` | Receive an AS2 message (any content type) |
| POST | `/as2/mdn` | Receive an asynchronous MDN callback |
| POST | `/as4/receive` | Receive an AS4 message (SOAP/XML with attachments) |
| POST | `/internal/files/receive` | Internal file receive (from other services) |
| GET | `/internal/health` | Service health |

**Configuration**:
```yaml
AS2_HOME_BASE: /data/as2
AS2_INSTANCE_ID: as2-1
```

**UI Page**: AS2 partnerships are managed via `/as2-partnerships` (see config-service Section 2.16).

**Dependencies**: config-service (partnership configuration), keystore-manager (signing/encryption certificates), RabbitMQ (event publishing).

---

---

### 13. EDI Converter (Port 8095)

A stateless EDI document processing service. Detects, parses, converts, validates, and generates EDI documents (X12, EDIFACT, CSV, JSON, XML). Does not use a database.

---

#### 13.1 Format Detection & Parsing

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/convert/detect` | Detect EDI format of input |
| POST | `/api/v1/convert/parse` | Parse EDI into structured object |
| POST | `/api/v1/convert/explain` | Human-readable explanation of EDI segments |
| GET | `/api/v1/convert/formats` | List all supported formats and conversions |

---

#### 13.2 Format Conversion

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/convert/convert` | Convert between formats (JSON body) |
| POST | `/api/v1/convert/convert/file` | Convert file (multipart) |
| POST | `/api/v1/convert/stream` | Streaming conversion (large files) |
| POST | `/api/v1/convert/stream/file` | Streaming file conversion (multipart) |
| POST | `/api/v1/convert/canonical` | Convert to canonical (normalized) form |
| POST | `/api/v1/convert/create` | Create EDI document from data |

---

#### 13.3 Validation & Compliance

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/convert/validate` | Validate EDI document against standards |
| POST | `/api/v1/convert/heal` | Auto-fix common EDI errors |
| POST | `/api/v1/convert/diff` | Diff two EDI documents |
| POST | `/api/v1/convert/compliance` | Check EDI compliance rules |

---

#### 13.4 Templates & Mapping

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/convert/templates` | List available EDI templates |
| POST | `/api/v1/convert/templates/{templateId}/generate` | Generate EDI from template |
| POST | `/api/v1/convert/mapping/smart` | AI-powered field mapping |
| POST | `/api/v1/convert/mapping/generate` | Generate mapping rules |
| POST | `/api/v1/convert/mapping/schema` | Generate schema from sample |

---

#### 13.5 Partner EDI Profiles

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/convert/partners` | List EDI partner profiles |
| GET | `/api/v1/convert/partners/{partnerId}` | Get partner profile |
| POST | `/api/v1/convert/partners` | Create partner profile |
| PUT | `/api/v1/convert/partners/{partnerId}` | Update partner profile |
| DELETE | `/api/v1/convert/partners/{partnerId}` | Delete partner profile |
| POST | `/api/v1/convert/partners/{partnerId}/analyze` | Analyze partner's EDI patterns |
| POST | `/api/v1/convert/partners/{partnerId}/apply` | Apply mapping rules to partner |

---

#### 13.6 Trained Conversion (AI-mapped)

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/convert/convert/trained` | Convert using AI-trained mappings |
| GET | `/api/v1/convert/convert/trained/check` | Check if trained mapping exists |
| POST | `/api/v1/convert/convert/test-mappings` | Test mapping rules against sample data |
| POST | `/api/v1/convert/convert/trained/invalidate-cache` | Invalidate mapping cache |
| GET | `/api/v1/convert/convert/trained/maps` | List cached trained maps |
| GET | `/api/v1/convert/convert/trained/cache-stats` | Cache statistics |
| POST | `/api/v1/convert/convert/trained/invalidate-all` | Invalidate entire cache |

---

#### 13.7 Comparison Suite

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/convert/compare/prepare` | Prepare comparison session (JSON body) |
| POST | `/api/v1/convert/compare/prepare/upload` | Prepare comparison session (file upload) |
| POST | `/api/v1/convert/compare/execute/{sessionId}` | Execute comparison |
| GET | `/api/v1/convert/compare/reports/{sessionId}` | Get comparison report |
| GET | `/api/v1/convert/compare/reports/{sessionId}/summary` | Get summary |
| GET | `/api/v1/convert/compare/sessions/{sessionId}` | Get session status |

**UI Page**: `/edi` -- EDI Translation (sidebar: Tools > EDI Translation)

**Dependencies**: ai-engine (for AI-powered mapping and training data).

---

#### 13.8 Map-Based Conversion (NEW)

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/convert/convert/map` | Convert using document-type mapping |
| GET | `/api/v1/convert/maps` | List all available maps |
| GET | `/api/v1/convert/maps/{mapId}` | Get map detail |
| POST | `/api/v1/convert/detect/type` | Detect document type (e.g., "X12_850") |

**Convert via map:**
- Body: `{ "content": "ISA*00*...", "sourceType": "X12_850", "targetType": "PURCHASE_ORDER_INH", "partnerId": "optional" }`
- Returns: converted output + map used + confidence score
- Auto-detects source type if not provided
- Map cascade: Partner custom → Trained → Standard

#### Standard Map Library

- 31 standard maps ship in the JAR (zero boot cost, lazy-loaded)
- Covers: X12, EDIFACT, SWIFT MT, ISO 20022, NACHA, BAI2, TRADACOMS, HIPAA
- Maps named intuitively: `X12_850--INHOUSE_PURCHASE_ORDER.json`
- Auto-discovered at startup via classpath scanning

#### Partner Map Customization (AI Engine)

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/edi/maps/clone` | Clone standard map for partner |
| PUT | `/api/v1/edi/maps/{mapId}` | Edit partner map |
| POST | `/api/v1/edi/maps/{mapId}/test` | Test with sample data |
| POST | `/api/v1/edi/maps/{mapId}/activate` | Activate for production |

#### New Parsers

- TRADACOMS (UK retail), NACHA/ACH (US payments), BAI2 (US banking)
- FIX Protocol (capital markets), HIPAA X12 (US healthcare)

---

---

### 14. Storage Manager (Port 8096)

Content-addressable storage with SHA-256 deduplication, zero-copy streaming, tiered lifecycle management (HOT -> WARM -> COLD -> ARCHIVE), WAIL (Write-Ahead Intent Log), S3 backend support, and multi-replica routing via Redis.

---

#### 14.1 Store & Retrieve

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/storage/store` | Store a file (returns SHA-256 key) |
| POST | `/api/v1/storage/store-bytes` | Store raw bytes |
| POST | `/api/v1/storage/store-stream` | Store via streaming (multipart, large files) |
| GET | `/api/v1/storage/retrieve/{trackId}` | Retrieve by track ID |
| GET | `/api/v1/storage/retrieve-by-key/{sha256}` | Retrieve by SHA-256 content hash |
| GET | `/api/v1/storage/stream/{sha256}` | Stream content by SHA-256 (zero-copy) |
| GET | `/api/v1/storage/ref-count/{sha256}` | Reference count for a stored object |
| DELETE | `/api/v1/storage/objects/{sha256}` | Delete a stored object |
| GET | `/api/v1/storage/objects` | List stored objects |

---

#### 14.2 Lifecycle & Tiering

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/storage/lifecycle/actions` | List pending lifecycle actions |
| POST | `/api/v1/storage/lifecycle/tier` | Move object to a different tier |
| POST | `/api/v1/storage/lifecycle/backup` | Create backup copy |
| GET | `/api/v1/storage/metrics` | Storage utilization metrics |
| GET | `/api/v1/storage/drp-stats` | DRP (Disaster Recovery Pipeline) statistics |
| GET | `/api/v1/storage/health` | Service health |

**Configuration**:
```yaml
storage:
  backend: ${STORAGE_BACKEND:local}           # local or s3
  s3:
    endpoint: ${STORAGE_S3_ENDPOINT:http://mft-minio:9000}
    bucket: ${STORAGE_S3_BUCKET:mft-storage}
    access-key: ${STORAGE_S3_ACCESS_KEY:minioadmin}
    secret-key: ${STORAGE_S3_SECRET_KEY:minioadmin}
    region: ${STORAGE_S3_REGION:us-east-1}
    path-style: ${STORAGE_S3_PATH_STYLE:true}
  instance-url: ${STORAGE_INSTANCE_URL:http://storage-manager:8096}
  hot:
    path: ${STORAGE_HOT_PATH:/data/storage/hot}
    max-size-gb: ${STORAGE_HOT_MAX_GB:100}
  warm:
    path: ${STORAGE_WARM_PATH:/data/storage/warm}
    max-size-gb: ${STORAGE_WARM_MAX_GB:500}
  cold:
    path: ${STORAGE_COLD_PATH:/data/storage/cold}
    max-size-gb: ${STORAGE_COLD_MAX_GB:2000}
  backup:
    path: ${STORAGE_BACKUP_PATH:/data/storage/backup}
  stripe-size-kb: ${STORAGE_STRIPE_SIZE_KB:4096}
  io-threads: ${STORAGE_IO_THREADS:8}
  write-buffer-mb: ${STORAGE_WRITE_BUFFER_MB:64}
  hot-to-warm-hours: ${STORAGE_HOT_WARM_HOURS:168}
  warm-to-cold-days: ${STORAGE_WARM_COLD_DAYS:30}
  cold-retention-days: ${STORAGE_COLD_RETENTION_DAYS:365}
```

**UI Pages**:
- `/storage` -- Storage Manager (sidebar: Servers & Infrastructure > Storage Manager, ADMIN only)
- `/cas-dedup` -- CAS Dedup (sidebar: Servers & Infrastructure > CAS Dedup, ADMIN only)
- `/file-manager` -- File Manager (sidebar: File Processing > File Manager, ADMIN only)

**Dependencies**: Redis (location registry for multi-replica routing), MinIO/S3 (when storage.backend=s3).

---

---

### 15. Notification Service (Port 8097)

Dispatches notifications across multiple channels: Email (SMTP), Slack (Block Kit), Microsoft Teams (Adaptive Cards), and Webhooks. Notifications are triggered by rules that match on transfer events (completed, failed, SLA breach, etc.) and formatted using configurable templates.

---

#### 15.1 Templates

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/notifications/templates` | List all templates |
| GET | `/api/notifications/templates/{id}` | Get template by ID |
| POST | `/api/notifications/templates` | Create template |
| PUT | `/api/notifications/templates/{id}` | Update template |
| DELETE | `/api/notifications/templates/{id}` | Delete template |

---

#### 15.2 Rules

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/notifications/rules` | List all rules |
| GET | `/api/notifications/rules/{id}` | Get rule by ID |
| POST | `/api/notifications/rules` | Create rule |
| PUT | `/api/notifications/rules/{id}` | Update rule |
| DELETE | `/api/notifications/rules/{id}` | Delete rule |

---

#### 15.3 Delivery Logs

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/notifications/logs` | List delivery logs (paginated) |
| GET | `/api/notifications/logs/recent` | Recent delivery logs |
| GET | `/api/notifications/logs/by-track-id/{trackId}` | Logs for a specific transfer |
| POST | `/api/notifications/test` | Send a test notification |
| GET | `/api/notifications/health` | Service health |

**UI Page**: `/notifications` -- Notifications (sidebar: Notifications & Integrations > Notifications, ADMIN only)

**Configuration**:
```yaml
spring:
  mail:
    host: ${MAIL_HOST:localhost}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}
    properties:
      mail:
        smtp:
          auth: ${MAIL_SMTP_AUTH:true}
          starttls:
            enable: ${MAIL_SMTP_STARTTLS:true}

notification:
  from-address: ${NOTIFICATION_FROM:noreply@tranzfer.io}
```

**Dependencies**: External SMTP server (email), Slack/Teams APIs (via connectors configured in config-service).

---

---

### 16. Platform Sentinel (Port 8098)

Continuous platform health monitoring with security analysis (8 rules), performance analysis (6 rules with real disk/connection checks), time-window correlation, and composite health scoring (0-100).

---

#### 16.1 Health Score

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/sentinel/health-score` | Current health score (0-100, weighted composite) |
| GET | `/api/v1/sentinel/health-score/history` | Health score history |

---

#### 16.2 Findings & Correlations

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/sentinel/findings` | List all findings (filterable by severity, category) |
| GET | `/api/v1/sentinel/findings/{id}` | Get finding details |
| POST | `/api/v1/sentinel/findings/{id}/dismiss` | Dismiss a finding |
| POST | `/api/v1/sentinel/findings/{id}/acknowledge` | Acknowledge a finding |
| GET | `/api/v1/sentinel/correlations` | List correlated finding groups |

---

#### 16.3 Rules

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/sentinel/rules` | List all rules |
| POST | `/api/v1/sentinel/rules` | Create rule |
| PUT | `/api/v1/sentinel/rules/{id}` | Update rule |
| DELETE | `/api/v1/sentinel/rules/{id}` | Delete rule |

---

#### 16.4 Dashboard & Analysis

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/sentinel/dashboard` | Sentinel dashboard (scores, findings, correlations) |
| POST | `/api/v1/sentinel/analyze` | Trigger immediate analysis |
| GET | `/api/v1/sentinel/circuit-breakers` | Circuit breaker states across all services |
| GET | `/api/v1/sentinel/health` | Service health |

**UI Pages**:
- `/sentinel` -- Platform Sentinel (sidebar: Intelligence > Platform Sentinel, ADMIN only)
- `/circuit-breakers` -- Circuit Breakers (sidebar: Intelligence > Circuit Breakers, ADMIN only)

**Configuration**:
```yaml
SENTINEL_GITHUB_ENABLED: ${SENTINEL_GITHUB_ENABLED:-false}
SENTINEL_GITHUB_TOKEN: ${SENTINEL_GITHUB_TOKEN:-}
SENTINEL_GITHUB_OWNER: ${SENTINEL_GITHUB_OWNER:-}
SENTINEL_GITHUB_REPO: ${SENTINEL_GITHUB_REPO:-}
```

When GitHub reporting is enabled, findings are automatically created as GitHub Issues.

**Dependencies**: All services (Sentinel queries health endpoints and database tables across the platform).

---

---

### 17. SFTP Service (Port 8081)

Embedded Apache MINA SSHD server that accepts SFTP connections. Authenticates users against the platform database (password or SSH public key), enforces VFS boundaries, and publishes file events to RabbitMQ for flow engine processing.

**Protocol Port**: 2222 (SSH/SFTP)

**Internal API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/internal/files/receive` | Internal file receive (from other services) |
| GET | `/actuator/health` | Health check |

**Configuration**:
```yaml
SFTP_PORT: 2222
SFTP_HOME_BASE: /data/sftp
SFTP_INSTANCE_ID: sftp-1
SFTP_KEYSTORE_MANAGER_ENABLED: "true"           # opt-in: fetch host key from Keystore Manager
SFTP_KEYSTORE_MANAGER_URL: http://keystore-manager:8093
```

**Scaling**: Docker Compose includes `sftp-service`, `sftp-service-2`, and `sftp-service-3` for horizontal scaling. Each replica has a unique `SFTP_INSTANCE_ID`.

**Dependencies**: PostgreSQL, RabbitMQ, Redis (session registry), keystore-manager (optional, for centralized host key).

---

### 18. FTP Service (Port 8082)

Embedded Apache FtpServer that accepts FTP/FTPS connections. Supports active and passive modes, explicit TLS, and virtual filesystem.

**Protocol Ports**: 21 (FTP control), 21000-21010 (passive data)

**Internal API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/internal/files/receive` | Internal file receive |
| GET | `/actuator/health` | Health check |

**Configuration**:
```yaml
FTP_PORT: 21
FTP_PASSIVE_PORTS: 21000-21010
FTP_PUBLIC_HOST: ${FTP_PUBLIC_HOST:-127.0.0.1}
FTP_HOME_BASE: /data/ftp
FTP_INSTANCE_ID: ftp-1
PLATFORM_REPLICA_COUNT: 2
```

**Scaling**: Docker Compose includes `ftp-service`, `ftp-service-2`, and `ftp-service-3`.

**Dependencies**: PostgreSQL, RabbitMQ.

---

### 19. FTP-Web Service (Port 8083)

Browser-based file transfer over HTTPS with chunked upload support. Provides a web UI (ftp-web-ui on port 3001) for drag-and-drop file uploads and downloads without requiring an FTP client.

**API Endpoints**:

| Method | Path | Description |
|---|---|---|
| POST | `/internal/files/receive` | Internal file receive |
| POST | `/api/upload/chunk` | Upload a file chunk (resumable upload) |
| Various | `/api/files/**` | File CRUD operations |

**Configuration**:
```yaml
FTPWEB_HOME_BASE: /data/ftpweb
FTPWEB_INSTANCE_ID: ftpweb-1
```

**Dependencies**: PostgreSQL, RabbitMQ.

---

---

### 20. Shared Platform (Library)

Not a deployable service but a shared Maven library used by all services. Contains JPA entities, security filters, inter-service communication, flow processing infrastructure, and compliance enforcement.

---

#### 20.1 JPA Entities

The shared library defines 32+ JPA entities in the shared database, managed by Flyway migrations (V1 through V49+). Key entities include:

| Entity | Table | Purpose |
|---|---|---|
| TransferAccount | transfer_accounts | User credentials for protocol access |
| FileTransferEvent | file_transfer_events | Audit trail of all transfers |
| FileFlow | file_flows | Processing flow definitions |
| FlowExecution | flow_executions | Runtime flow execution state |
| FlowEvent | flow_events | Step-level event journal |
| Partner | partners | Partner organizations |
| ServerInstance | server_instances | Protocol server registry |
| FolderMapping | folder_mappings | Source/destination routing rules |
| ExternalDestination | external_destinations | Outbound delivery targets |
| SecurityProfile | security_profiles | Algorithm whitelists |
| PartnerAgreement | partner_agreements | SLA definitions |
| EncryptionKey | encryption_keys | Key assignments per account |
| ScreeningResult | screening_results | Sanctions screening outcomes |
| ServiceRegistration | service_registrations | Cluster service registry |
| PlatformSetting | platform_settings | Key-value config store |
| ScheduledTask | scheduled_tasks | Cron job definitions |
| As2Partnership | as2_partnerships | AS2/AS4 trading agreements |
| ComplianceProfile | compliance_profiles | Regulatory rule sets |
| NotificationTemplate | notification_templates | Message templates |
| NotificationRule | notification_rules | Event-to-notification mapping |
| Tenant | tenants | Multi-tenant organizations |
| Quarantine | quarantine | Quarantined files |
| DlpPolicy | dlp_policies | Data loss prevention rules |
| KeyEntry | key_entries | Key/certificate metadata |
| License | licenses | License records |
| LicenseActivation | license_activations | License activation history |

---

#### 20.2 SPIFFE/SPIRE Integration

**What it does**: Every service authenticates to every other service using SPIFFE workload identities. No static tokens or API keys for inter-service auth.

**Key Classes**:
- `SpiffeWorkloadClient` (shared-core) -- Connects to SPIRE Agent, requests JWT-SVIDs, retries every 15s until available
- `SpiffeX509Manager` (shared-core) -- Manages X.509-SVID for mTLS
- `SpiffeProxyAuth` (dmz-proxy) -- SPIFFE auth for the stateless proxy
- `PlatformJwtAuthFilter` -- 3-path authentication: (1) mTLS peer cert, (2) SPIFFE JWT-SVID, (3) Platform JWT
- `BaseServiceClient` -- Auto-attaches JWT-SVID headers (Phase 1) or uses mTLS (Phase 2)

**Configuration**:
```yaml
# Environment variables (set in docker-compose.yml)
SPIFFE_ENABLED: true
SPIFFE_TRUST_DOMAIN: filetransfer.io
SPIFFE_SOCKET: unix:/run/spire/sockets/agent.sock

# Optional Phase 2 mTLS (opt-in per service)
spiffe.mtls-enabled: true  # + use https:// service URLs
```

---

#### 20.3 ResilientServiceClient

**What it does**: HTTP client with circuit breaker (Resilience4j) and retry logic for all inter-service communication. Prevents cascade failures when a downstream service is unavailable.

**Behavior**:
- Circuit opens after configurable failure threshold
- Half-open state allows periodic retry
- Retries with exponential backoff
- Services degrade gracefully when circuit is open (see Service Map failure modes)

---

#### 20.4 FlowFunction Plugin System

**What it does**: Extensible step processing framework. Each flow step is implemented as a `FlowFunction` with a type name, I/O mode (STREAMING, MATERIALIZING, PASSTHROUGH), and execute method.

**17 Built-in Functions**: COMPRESS_GZIP, DECOMPRESS_GZIP, COMPRESS_ZIP, DECOMPRESS_ZIP, ENCRYPT_PGP, DECRYPT_PGP, ENCRYPT_AES, DECRYPT_AES, RENAME, SCREEN, EXECUTE_SCRIPT, MAILBOX, FILE_DELIVERY, CONVERT_EDI, ROUTE, APPROVE, CHECKSUM_VERIFY.

**Extensibility**:
- **gRPC Functions**: Register a remote gRPC service as a flow step via POST `/api/flows/functions/import`
- **WASM Functions**: Register a WebAssembly module for sandboxed local execution

---

#### 20.5 FlowEvent Journal

**What it does**: Records every event in a flow execution lifecycle. Used for audit, debugging, and the Journey view.

**13 Event Types**: FLOW_MATCHED, EXECUTION_STARTED, STEP_STARTED, STEP_COMPLETED, STEP_FAILED, STEP_RETRYING, EXECUTION_PAUSED, APPROVAL_RECEIVED, EXECUTION_RESUMED, EXECUTION_COMPLETED, EXECUTION_FAILED, EXECUTION_TERMINATED, EXECUTION_RESTARTED.

---

#### 20.6 SEDA Processing & I/O Lane Manager

**What it does**: Staged Event-Driven Architecture (SEDA) processing separates I/O-bound and CPU-bound work into lanes with independent thread pools. Streaming steps use zero-copy I/O (FileChannel.transferTo); materializing steps buffer to temp files.

---

---

## Cross-Cutting Concerns

### Authentication & Authorization

Three authentication paths are evaluated in order by `PlatformJwtAuthFilter`:

1. **mTLS Peer Certificate** (Path 0): If the request arrives over HTTPS with a valid client certificate whose SPIFFE ID matches the trust domain, the request is authenticated.
2. **SPIFFE JWT-SVID** (Path 1): The `Authorization` header contains a JWT-SVID issued by the SPIRE server. Validated against the SPIRE trust bundle.
3. **Platform JWT** (Path 2): Standard user JWT issued by `/api/auth/login`. Contains user ID, email, and role.

For the admin UI, partner portal, and CLI: Platform JWT (Path 2).
For inter-service calls: SPIFFE JWT-SVID (Path 1) or mTLS (Path 0).

### Service-to-Service Communication

All services use `ResilientServiceClient` (wrapping Spring `RestTemplate` with Resilience4j circuit breakers). Service URLs are configured in each service's `application.yml`:

```yaml
platform:
  services:
    onboarding-api:
      url: ${ONBOARDING_API_URL:http://onboarding-api:8080}
    config-service:
      url: ${CONFIG_SERVICE_URL:http://config-service:8084}
    encryption-service:
      url: ${ENCRYPTION_SERVICE_URL:http://encryption-service:8086}
    # ... etc for all 19 services
```

### Event System

- **Exchange**: `file-transfer.events` (topic exchange)
- **Binding Key**: `account.*` (e.g., `account.transfer.completed`, `account.file.received`)
- **Consumers**: config-service (live activity), analytics-service, notification-service, ai-engine

### Database

- **Single shared PostgreSQL** database (`filetransfer`)
- **Flyway migrations**: V1 through V49+ in `shared/shared-platform/src/main/resources/db/migration/`
- **Connection pools**: Each service has its own HikariCP pool (sizes vary from 3 to 20 based on load)

### Monitoring

Every service exposes Spring Boot Actuator endpoints:

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Health status (with liveness/readiness probes) |
| `/actuator/health/liveness` | Kubernetes liveness probe |
| `/actuator/health/readiness` | Kubernetes readiness probe |
| `/actuator/prometheus` | Prometheus metrics scraping |
| `/actuator/info` | Application info |
| `/actuator/circuit-breakers` | Circuit breaker states |

### Docker Deployment

```bash
# Start everything
docker compose up -d

# Start with external proxy group
docker compose --profile external-proxy up -d

# Start with MinIO S3 storage
docker compose --profile minio up -d

# Start with both
docker compose --profile external-proxy --profile minio up -d
```

---

## Configuration Reference

### Global Environment Variables

| Variable | Default | Description |
|---|---|---|
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | JWT signing secret (MUST change in production) |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL connection URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ hostname |
| `RABBITMQ_PORT` | `5672` | RabbitMQ port |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier |
| `CLUSTER_COMM_MODE` | `WITHIN_CLUSTER` | Communication mode (WITHIN_CLUSTER or CROSS_CLUSTER) |
| `TRACK_ID_PREFIX` | `TRZ` | Prefix for transfer track IDs |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment name (DEV, STAGING, PROD) |
| `CONTROL_API_KEY` | `internal_control_secret` | HMAC secret for PCI audit log signing |
| `SPIFFE_ENABLED` | `true` | Enable SPIFFE/SPIRE workload identity |
| `SPIFFE_TRUST_DOMAIN` | `filetransfer.io` | SPIRE trust domain |
| `SPIFFE_SOCKET` | `unix:/run/spire/sockets/agent.sock` | SPIRE Agent socket path |
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `PLATFORM_RATE_LIMIT_DEFAULT_LIMIT` | `10000` | Default API rate limit per minute |

### Service-Specific Environment Variables

| Variable | Service | Default | Description |
|---|---|---|---|
| `ENCRYPTION_MASTER_KEY` | encryption-service | `000...000` | AES master key (64 hex chars, MUST change) |
| `KEYSTORE_MASTER_PASSWORD` | keystore-manager | `change-this-master-password` | Keystore master password |
| `LICENSE_ADMIN_KEY` | license-service | `license_admin_secret_key` | License issuance admin key |
| `SFTP_PORT` | sftp-service | `2222` | SFTP listen port |
| `SFTP_HOME_BASE` | sftp-service | `/data/sftp` | SFTP home directory base path |
| `SFTP_INSTANCE_ID` | sftp-service | `sftp-1` | Unique instance identifier |
| `FTP_PORT` | ftp-service | `21` | FTP listen port |
| `FTP_PASSIVE_PORTS` | ftp-service | `21000-21010` | FTP passive port range |
| `FTP_PUBLIC_HOST` | ftp-service | `127.0.0.1` | Public IP for passive mode |
| `FTP_HOME_BASE` | ftp-service | `/data/ftp` | FTP home directory base path |
| `FTPWEB_HOME_BASE` | ftp-web-service | `/data/ftpweb` | FTP-Web home directory base |
| `GATEWAY_SFTP_PORT` | gateway-service | `2220` | Gateway SFTP listen port |
| `GATEWAY_FTP_PORT` | gateway-service | `2121` | Gateway FTP listen port |
| `GATEWAY_SFTP_CIPHERS` | gateway-service | `aes256-gcm@openssh.com,...` | Allowed SSH ciphers |
| `GATEWAY_SFTP_MACS` | gateway-service | `hmac-sha2-512-etm@openssh.com,...` | Allowed SSH MACs |
| `GATEWAY_SFTP_KEX` | gateway-service | `curve25519-sha256,...` | Allowed SSH key exchange algorithms |
| `TUNNEL_ENABLED` | gateway-service | `false` | Enable single-port tunnel to DMZ |
| `PROXY_GROUP_NAME` | dmz-proxy | `internal` | Proxy group name |
| `PROXY_GROUP_TYPE` | dmz-proxy | `INTERNAL` | Group type (INTERNAL, EXTERNAL, PARTNER, CLOUD, CUSTOM) |
| `DMZ_SECURITY_ENABLED` | dmz-proxy | `true` | Enable security layer |
| `DMZ_ZONES_ENABLED` | dmz-proxy | `true` | Enable network zone enforcement |
| `DMZ_EGRESS_ENABLED` | dmz-proxy | `true` | Enable egress filtering |
| `DMZ_AUDIT_ENABLED` | dmz-proxy | `true` | Enable audit logging |
| `DMZ_QOS_ENABLED` | dmz-proxy | `false` | Enable QoS bandwidth management |
| `DMZ_TLS_ENABLED` | dmz-proxy | `false` | Enable TLS termination |
| `DEFAULT_RATE_PER_MINUTE` | dmz-proxy | `60` | Per-IP rate limit |
| `DEFAULT_MAX_CONCURRENT` | dmz-proxy | `20` | Per-IP max concurrent connections |
| `GLOBAL_RATE_PER_MINUTE` | dmz-proxy | `10000` | Global rate limit |
| `BLOCKCHAIN_ANCHOR_MODE` | onboarding-api | `INTERNAL` | Blockchain anchor mode |
| `FLOW_MAX_CONCURRENT` | onboarding-api | `50` | Max concurrent flow executions |
| `AI_LLM_ENABLED` | ai-engine | `false` | Enable LLM features (opt-in) |
| `CLAUDE_API_KEY` | ai-engine | (empty) | Anthropic Claude API key |
| `CLAUDE_MODEL` | ai-engine | `claude-sonnet-4-20250514` | Claude model to use |
| `SCREENING_THRESHOLD` | screening-service | `0.82` | Sanctions match threshold (0.0-1.0) |
| `SCREENING_DEFAULT_ACTION` | screening-service | `BLOCK` | Default action on match (BLOCK, FLAG, ALLOW) |
| `CLAMAV_ENABLED` | screening-service | `false` | Enable ClamAV integration |
| `STORAGE_BACKEND` | storage-manager | `local` | Storage backend (local or s3) |
| `STORAGE_HOT_MAX_GB` | storage-manager | `100` | Max hot tier size |
| `STORAGE_WARM_MAX_GB` | storage-manager | `500` | Max warm tier size |
| `STORAGE_COLD_MAX_GB` | storage-manager | `2000` | Max cold tier size |
| `STORAGE_HOT_WARM_HOURS` | storage-manager | `168` | Hours before hot-to-warm migration |
| `STORAGE_WARM_COLD_DAYS` | storage-manager | `30` | Days before warm-to-cold migration |
| `STORAGE_COLD_RETENTION_DAYS` | storage-manager | `365` | Days before cold tier expiry |
| `STORAGE_IO_THREADS` | storage-manager | `8` | Parallel I/O threads |
| `FORWARDER_STALL_TIMEOUT_SECONDS` | forwarder-service | `30` | Transfer stall detection timeout |
| `FORWARDER_KAFKA_ENABLED` | forwarder-service | `false` | Enable Kafka forwarding |
| `AS4_SIGNING_ENABLED` | forwarder-service | `true` | Enable AS4 WS-Security signing |
| `MAIL_HOST` | notification-service | `localhost` | SMTP server hostname |
| `MAIL_PORT` | notification-service | `587` | SMTP server port |
| `MAIL_USERNAME` | notification-service | (empty) | SMTP username |
| `MAIL_PASSWORD` | notification-service | (empty) | SMTP password |
| `NOTIFICATION_FROM` | notification-service | `noreply@tranzfer.io` | Sender email address |
| `SENTINEL_GITHUB_ENABLED` | platform-sentinel | `false` | Report findings as GitHub Issues |
| `SENTINEL_GITHUB_TOKEN` | platform-sentinel | (empty) | GitHub personal access token |

---

## UI Page Map

| Page Name | Route | Sidebar Group | What It Does | Backend Service |
|---|---|---|---|---|
| Dashboard | `/dashboard` | Overview | Transfer stats, service health, recent activity | onboarding-api, analytics-service |
| Activity Monitor | `/activity-monitor` | Overview | Searchable transfer history with filters | onboarding-api |
| Live Activity | `/activity` | Overview | Real-time transfer events (WebSocket/polling) | config-service |
| Transfer Journey | `/journey` | Overview | End-to-end lifecycle view for a single transfer | onboarding-api |
| Partner Management | `/partners` | Partners & Accounts | CRUD partners, lifecycle management | onboarding-api |
| Partner Detail | `/partners/:id` | Partners & Accounts | Partner details with accounts, flows, endpoints | onboarding-api |
| Onboard Partner | `/partner-setup` | Partners & Accounts | Step-by-step onboarding wizard (ADMIN) | onboarding-api |
| Transfer Accounts | `/accounts` | Partners & Accounts | CRUD transfer accounts, server assignments | onboarding-api |
| Users | `/users` | Partners & Accounts | User management (ADMIN) | onboarding-api |
| Processing Flows | `/flows` | File Processing | Define and manage file processing flows | config-service |
| Folder Mappings | `/folder-mappings` | File Processing | Source/destination routing rules | onboarding-api |
| Folder Templates | `/folder-templates` | File Processing | Reusable folder structure templates (ADMIN) | config-service |
| External Destinations | `/external-destinations` | File Processing | Outbound delivery targets | config-service |
| AS2/AS4 Partnerships | `/as2-partnerships` | File Processing | AS2/AS4 trading partner config | config-service |
| P2P Transfers | `/p2p` | File Processing | Direct peer-to-peer file transfers | onboarding-api |
| File Manager | `/file-manager` | File Processing | Browse and manage files (ADMIN) | onboarding-api |
| Server Instances | `/server-instances` | Servers & Infrastructure | Protocol server registry (ADMIN) | onboarding-api |
| Gateway & DMZ | `/gateway` | Servers & Infrastructure | Gateway status and route management (ADMIN) | gateway-service |
| DMZ Proxy | `/dmz-proxy` | Servers & Infrastructure | Proxy management, security stats (ADMIN) | dmz-proxy |
| Proxy Groups | `/proxy-groups` | Servers & Infrastructure | Proxy group management (ADMIN) | onboarding-api |
| Storage Manager | `/storage` | Servers & Infrastructure | Storage utilization and lifecycle (ADMIN) | storage-manager |
| VFS Storage | `/vfs-storage` | Servers & Infrastructure | Virtual filesystem dashboard (ADMIN) | config-service |
| CAS Dedup | `/cas-dedup` | Servers & Infrastructure | Content deduplication stats (ADMIN) | storage-manager |
| Cluster | `/cluster` | Servers & Infrastructure | Cluster topology and status (ADMIN) | onboarding-api |
| Compliance Profiles | `/compliance` | Security & Compliance | PCI/PHI/PII compliance rules (ADMIN) | config-service |
| Security Profiles | `/security-profiles` | Security & Compliance | SSH/TLS algorithm whitelists (ADMIN) | config-service |
| Keystore Manager | `/keystore` | Security & Compliance | Key/certificate management (ADMIN) | keystore-manager |
| Screening & DLP | `/screening` | Security & Compliance | Sanctions screening and DLP policies | screening-service |
| Quarantine | `/quarantine` | Security & Compliance | Quarantined file management (ADMIN) | screening-service |
| Two-Factor Auth | `/2fa` | Security & Compliance | TOTP 2FA setup | onboarding-api |
| Blockchain Proof | `/blockchain` | Security & Compliance | Non-repudiation verification | onboarding-api |
| Notifications | `/notifications` | Notifications & Integrations | Notification rules and templates (ADMIN) | notification-service |
| Connectors | `/connectors` | Notifications & Integrations | Webhook/Slack/Teams connectors (ADMIN) | config-service |
| SLA Agreements | `/sla` | Notifications & Integrations | Service level agreements | config-service |
| Scheduler | `/scheduler` | Notifications & Integrations | Cron-based task automation (ADMIN) | config-service |
| Observatory | `/observatory` | Intelligence | Step latency analysis (ADMIN) | analytics-service |
| Platform Sentinel | `/sentinel` | Intelligence | Health scoring and security findings (ADMIN) | platform-sentinel |
| AI Recommendations | `/recommendations` | Intelligence | AI-generated platform suggestions | ai-engine |
| Analytics | `/analytics` | Intelligence | Transfer analytics and dashboards | analytics-service |
| Predictions | `/predictions` | Intelligence | Scaling and capacity predictions | analytics-service |
| Circuit Breakers | `/circuit-breakers` | Intelligence | Circuit breaker states (ADMIN) | platform-sentinel |
| Auto-Onboarding | `/auto-onboarding` | Intelligence | AI-detected partner patterns (ADMIN) | onboarding-api |
| Threat Intel | `/threat-intelligence` | Intelligence | Threat indicators, MITRE, playbooks (ADMIN) | ai-engine |
| Proxy Intel | `/proxy-intelligence` | Intelligence | AI proxy verdicts, geo analysis (ADMIN) | ai-engine |
| Migration Center | `/migration` | Expert | Legacy system migration management (ADMIN) | config-service |
| EDI AI Training | `/edi-training` | Expert | Train EDI mapping models (ADMIN) | ai-engine |
| EDI Translation | `/edi` | Tools | Interactive EDI conversion tool | edi-converter |
| API Console | `/api-console` | Tools | Interactive API explorer | all services |
| Terminal | `/terminal` | Tools | CLI command execution (ADMIN) | onboarding-api |
| Platform Config | `/platform-config` | Administration | Key-value settings management (ADMIN) | config-service |
| Multi-Tenant | `/tenants` | Administration | Tenant management (ADMIN) | onboarding-api |
| License | `/license` | Administration | License management (ADMIN) | license-service |
| Service Health | `/services` | Administration | Service registry and health (ADMIN) | onboarding-api |
| Logs | `/logs` | Administration | Audit log viewer | onboarding-api |
| Dead Letter Queue | `/dlq` | Administration | Failed message management (ADMIN) | onboarding-api |
| Partner Portal Login | `/portal/login` | (separate app) | Partner self-service login | onboarding-api |
| Partner Dashboard | `/portal` | (separate app) | Partner transfer overview | onboarding-api |
| Partner Transfers | `/portal/transfers` | (separate app) | Partner transfer history | onboarding-api |
| Partner Settings | `/portal/settings` | (separate app) | Partner connection settings | onboarding-api |

---

## Production Security Checklist

Before deploying to production, these environment variables MUST be changed from their defaults:

| Variable | Why |
|---|---|
| `JWT_SECRET` | Default is publicly known. Use a random 256-bit key. |
| `ENCRYPTION_MASTER_KEY` | Default is all zeros. Use a random 64-character hex string. |
| `KEYSTORE_MASTER_PASSWORD` | Default is `change-this-master-password`. Use a strong password. |
| `LICENSE_ADMIN_KEY` | Default is `license_admin_secret_key`. Use a strong secret. |
| `CONTROL_API_KEY` | Default is `internal_control_secret`. Used for HMAC audit signing. |
| `DB_PASSWORD` | Default is `postgres`. Use a strong database password. |
| `RABBITMQ_DEFAULT_PASS` | Default is `guest`. Change RabbitMQ password. |
| `PLATFORM_ENVIRONMENT` | Set to `PROD` (some services fail-fast on insecure defaults when environment is PROD). |
| `SPIFFE_ENABLED` | Keep `true`. SPIFFE is the only inter-service auth mechanism. |
| `DMZ_TLS_ENABLED` | Set to `true` for external-facing DMZ proxies. |
| `DMZ_ZONES_ENABLED` | Set to `true` for external-facing DMZ proxies. |
| `DMZ_EGRESS_ENABLED` | Set to `true` for external-facing DMZ proxies. |

---

*This document was generated from source code analysis of the TranzFer MFT platform codebase. All endpoint paths, configuration properties, and port numbers are verified against the actual controllers and application.yml files.*

# Shared Library

> Core library providing entities, repositories, security, routing engine, and common utilities for all microservices.

**Type:** Java library (not a standalone service) | **Required:** Yes (dependency for most services)

---

## Overview

The shared module is a JAR library that provides common functionality to all Java microservices. It contains:

- **JPA Entities** — All database models (accounts, transfers, flows, keys, etc.)
- **Repositories** — Spring Data JPA repositories for all entities
- **Routing Engine** — Core file processing pipeline (Track ID → Flow Match → Step Execution)
- **Flow Processing Engine** — Executes ordered flow steps (encrypt, compress, route, etc.)
- **Security** — JWT authentication filter, security config, password utilities
- **Audit Service** — Centralized audit logging
- **Cluster Service** — Service registration and cluster coordination
- **Connector Dispatcher** — Route alerts to webhooks (Slack, PagerDuty, etc.)
- **Database Migrations** — Flyway SQL migrations for the shared schema
- **Enums** — Protocol types, statuses, roles, service types

---

## Build

The shared library must be installed to your local Maven repository before building other modules:

```bash
mvn clean install -pl shared -DskipTests
```

All other Java modules depend on it:
```xml
<dependency>
    <groupId>com.filetransfer</groupId>
    <artifactId>shared</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## Key Components

### Entities (JPA)

| Entity | Table | Description |
|--------|-------|-------------|
| `TransferAccount` | `transfer_accounts` | SFTP/FTP/AS2 user accounts with credentials and permissions |
| `FileTransferRecord` | `file_transfer_records` | Transfer tracking (Track ID, status, checksums, timestamps) |
| `FileFlow` | `file_flows` | Processing pipeline definitions with ordered steps |
| `FlowExecution` | `flow_executions` | Flow execution history and status tracking |
| `FolderMapping` | `folder_mappings` | Source-to-destination folder routing rules |
| `SecurityProfile` | `security_profiles` | TLS/SSH/auth policies |
| `EncryptionKey` | `encryption_keys` | PGP/AES key assignments per account |
| `ExternalDestination` | `external_destinations` | SFTP/FTP/HTTP forwarding targets |
| `DeliveryEndpoint` | `delivery_endpoints` | Modern endpoint configs with auth and proxy |
| `As2Partnership` | `as2_partnerships` | B2B trading partner agreements |
| `As2Message` | `as2_messages` | AS2/AS4 message records |
| `ServerConfig` | `server_configs` | Dynamic service instance settings |
| `ServerInstance` | `server_instances` | Deployed server registrations |
| `ScheduledTask` | `scheduled_tasks` | Cron-based job definitions |
| `AuditLog` | `audit_logs` | Action audit trail (user, timestamp, IP, details) |
| `ActivityEvent` | `activity_events` | File transfer activity events |
| `Tenant` | `tenants` | Multi-tenancy support |
| `TotpConfig` | `totp_configs` | 2FA TOTP configuration per user |
| `BlockchainAnchor` | `blockchain_anchors` | Merkle tree anchoring for non-repudiation |
| `PartnerAgreement` | `partner_agreements` | SLA definitions |
| `ClusterNode` | `cluster_nodes` | Cluster member registrations |
| `ClientPresence` | `client_presences` | P2P client online status |
| `AutoOnboardSession` | `auto_onboard_sessions` | Zero-touch onboarding sessions |
| `PlatformSetting` | `platform_settings` | Environment-scoped configuration |
| `TransferTicket` | `transfer_tickets` | P2P transfer authorization tokens |
| `WebhookConnector` | `webhook_connectors` | Alert webhook configurations |
| `LegacyServerConfig` | `legacy_server_configs` | Fallback server configurations |
| `ManagedKey` | `managed_keys` | Keystore-managed keys and certificates |
| `LicenseRecord` | `license_records` | License records |
| `ScreeningResult` | `screening_results` | OFAC screening results |

### Enums

| Enum | Values |
|------|--------|
| `Protocol` | SFTP, FTP, FTP_WEB, HTTPS, AS2, AS4 |
| `FileTransferStatus` | PENDING, UPLOADED, ROUTED, DOWNLOADED, COMPLETED, FAILED, CANCELLED |
| `UserRole` | ADMIN, PARTNER, USER, READONLY |
| `ServiceType` | ONBOARDING, SFTP, FTP, FTP_WEB, GATEWAY, ENCRYPTION, SCREENING, AI_ENGINE, ANALYTICS, LICENSE, KEYSTORE, STORAGE, AS2, EDI, FORWARDER |
| `Environment` | TEST, CERT, STAGING, PROD |
| `ClusterCommunicationMode` | WITHIN_CLUSTER, CROSS_CLUSTER |

### Routing Engine

The routing engine is the core orchestrator that processes every uploaded file:

```
File Upload → Assign Track ID → Find Matching Flows → Execute Steps → Deliver
```

**Key classes:**
- `RoutingEngine` — Entry point. Called by protocol services on upload/download.
- `FlowProcessingEngine` — Executes ordered flow steps.
- `RoutingEvaluator` — Matches files to flows by pattern and priority.

**Track ID format:** 12 characters, prefix-based (e.g., `TRZA3X5T3LUY`)

### Security

- `PlatformJwtAuthFilter` — JWT Bearer token validation
- `PlatformSecurityConfig` — Spring Security configuration (CORS, CSRF, roles)
- `JwtUtil` — Token generation and parsing

### Services

- `AuditService` — Log actions to `audit_logs` table
- `ClusterService` — Cluster membership and coordination
- `ClusterRegistrationService` — Auto-register service on startup
- `CredentialCryptoClient` — Encrypt/decrypt stored credentials
- `ConnectorDispatcher` — Send alerts to configured webhooks
- `AiClassificationClient` — Call AI engine for file classification
- `GuaranteedDeliveryService` — Retry delivery with backoff

### Database Migrations

Flyway migrations are in `src/main/resources/db/migration/`:
```
V1__initial_schema.sql
V2__add_audit_logs.sql
V3__add_flow_tables.sql
...
```

**Rules:**
- Never modify existing migration files (Flyway checksums will fail)
- Always add new files with incrementing version numbers
- Test on fresh database: `docker compose down -v && docker compose up -d`

---

## Package Structure

```
shared/src/main/java/com/filetransfer/shared/
├── audit/          ← Audit logging service
├── cluster/        ← Cluster coordination
├── config/         ← RabbitMQ config, security config
├── connector/      ← Webhook dispatcher
├── crypto/         ← Credential encryption client
├── dto/            ← Data transfer objects
├── entity/         ← JPA entity classes (25+)
├── enums/          ← Protocol, Status, Role enums
├── repository/     ← Spring Data JPA repositories
├── routing/        ← RoutingEngine, FlowProcessingEngine
├── scheduler/      ← Scheduling utilities
├── security/       ← JWT auth filter, security config
└── util/           ← JwtUtil, common utilities
```

---

## Dependencies

- **Spring Boot Starter Data JPA** — Database access
- **Spring Boot Starter Security** — Authentication/authorization
- **Spring Boot Starter AMQP** — RabbitMQ messaging
- **PostgreSQL Driver** — Database connectivity
- **Flyway** — Database migrations
- **Lombok** — Annotation-based boilerplate reduction
- **JJWT** — JWT token library

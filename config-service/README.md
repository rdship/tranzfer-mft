# Config Service

> Configuration management — file flows, connectors, security profiles, scheduling, and platform settings.

**Port:** 8084 | **Database:** PostgreSQL | **Messaging:** RabbitMQ | **Required:** Yes

---

## Overview

The config-service is the configuration brain of the platform. It manages:

- **File Flows** — Processing pipelines (encrypt → compress → screen → deliver)
- **Connectors** — Webhooks for alerts (Slack, PagerDuty, Teams, ServiceNow)
- **Security Profiles** — TLS/SSH/auth policies
- **Encryption Keys** — PGP/AES key assignments per account
- **External Destinations** — SFTP/FTP/Kafka forwarding targets
- **Delivery Endpoints** — External partner delivery configurations
- **AS2 Partnerships** — B2B trading partner agreements
- **Scheduled Tasks** — Cron-based job scheduling
- **SLA Agreements** — Partner delivery commitments
- **Platform Settings** — Multi-environment, multi-service configuration store
- **Server Configs** — Dynamic service instance management
- **Legacy Servers** — Fallback servers for unknown users

All configuration changes are published to RabbitMQ for real-time propagation.

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq config-service
curl http://localhost:8084/actuator/health
```

---

## API Endpoints

### File Flows

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/flows` | List all flows (ordered by priority) |
| GET | `/api/flows/{id}` | Get flow details |
| POST | `/api/flows` | Create flow (name must be unique) |
| PUT | `/api/flows/{id}` | Update flow |
| PATCH | `/api/flows/{id}/toggle` | Toggle active status |
| DELETE | `/api/flows/{id}` | Soft-delete flow |
| GET | `/api/flows/executions?trackId=&filename=&status=&page=0&size=20` | List flow executions |
| GET | `/api/flows/executions/{trackId}` | Get execution by track ID |
| GET | `/api/flows/step-types` | List available step types |

**Create a flow:**
```bash
curl -X POST http://localhost:8084/api/flows \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Screen and Encrypt",
    "triggerPattern": "*.csv",
    "steps": [
      {"type": "SCREEN", "order": 1},
      {"type": "ENCRYPT_PGP", "order": 2, "config": {"keyAlias": "partner-key"}}
    ]
  }'
```

**Available step types:**
- Encryption: `ENCRYPT_AES`, `ENCRYPT_PGP`, `DECRYPT_AES`, `DECRYPT_PGP`
- Compression: `COMPRESS_GZIP`, `COMPRESS_ZIP`, `DECOMPRESS_GZIP`, `DECOMPRESS_ZIP`
- Transform: `RENAME`, `CONVERT_EDI_JSON`, `CONVERT_EDI_CSV`
- Security: `SCREEN`, `CLASSIFY`
- Scripting: `SCRIPT`
- Delivery: `FORWARD_EXTERNAL`, `FORWARD_SFTP`, `FORWARD_FTP`, `FORWARD_HTTP`
- Routing: `ROUTE_TO_ACCOUNT`

---

### Connectors (Webhooks)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/connectors` | List active connectors |
| POST | `/api/connectors` | Create connector |
| PUT | `/api/connectors/{id}` | Update connector |
| DELETE | `/api/connectors/{id}` | Soft-delete |
| POST | `/api/connectors/{id}/test` | Test connectivity |
| GET | `/api/connectors/types` | List connector types |

**Connector types:** ServiceNow, PagerDuty, Slack, Microsoft Teams, OpsGenie, Generic Webhook

**Create a Slack connector:**
```bash
curl -X POST http://localhost:8084/api/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Ops Slack",
    "type": "SLACK",
    "webhookUrl": "https://hooks.slack.com/services/T.../B.../xxx",
    "active": true
  }'
```

**Test it:**
```bash
curl -X POST http://localhost:8084/api/connectors/{id}/test
# Returns: {"status": "OK", "httpCode": 200}
```

---

### Security Profiles

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/security-profiles` | List active profiles |
| GET | `/api/security-profiles/{id}` | Get profile details |
| POST | `/api/security-profiles` | Create profile (name must be unique) |
| PUT | `/api/security-profiles/{id}` | Update profile |
| DELETE | `/api/security-profiles/{id}` | Soft-delete |

---

### Encryption Keys

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/encryption-keys?accountId={uuid}` | List keys for account |
| GET | `/api/encryption-keys/{id}` | Get key details |
| POST | `/api/encryption-keys` | Create key (requires account ID) |
| DELETE | `/api/encryption-keys/{id}` | Soft-delete |

---

### External Destinations

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/external-destinations` | List destinations (optional `?type=` filter) |
| GET | `/api/external-destinations/{id}` | Get destination |
| POST | `/api/external-destinations` | Create destination |
| PUT | `/api/external-destinations/{id}` | Update destination |
| DELETE | `/api/external-destinations/{id}` | Delete |

---

### Delivery Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/delivery-endpoints` | List endpoints (optional `?protocol=`, `?tag=` filters) |
| GET | `/api/delivery-endpoints/{id}` | Get endpoint |
| POST | `/api/delivery-endpoints` | Create endpoint (secrets auto-encrypted) |
| PUT | `/api/delivery-endpoints/{id}` | Update endpoint |
| PATCH | `/api/delivery-endpoints/{id}/toggle` | Toggle active status |
| DELETE | `/api/delivery-endpoints/{id}` | Soft-delete |
| GET | `/api/delivery-endpoints/summary` | Aggregated stats |
| GET | `/api/delivery-endpoints/protocols` | Available protocols |
| GET | `/api/delivery-endpoints/auth-types` | Available auth types |
| GET | `/api/delivery-endpoints/proxy-types` | DMZ, HTTP, SOCKS5 |

**Supported protocols:** SFTP, FTP, FTPS, HTTP, HTTPS, API
**Auth types:** PASSWORD, BEARER_TOKEN, API_KEY, OAUTH2, SSH_KEY
**Note:** Passwords, tokens, and keys are encrypted before storage.

---

### AS2 Partnerships

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/as2-partnerships` | List partnerships (optional `?protocol=AS2\|AS4`) |
| GET | `/api/as2-partnerships/{id}` | Get partnership |
| POST | `/api/as2-partnerships` | Create partnership |
| PUT | `/api/as2-partnerships/{id}` | Update partnership |
| PATCH | `/api/as2-partnerships/{id}/toggle` | Toggle active |
| DELETE | `/api/as2-partnerships/{id}` | Soft-delete |

**Required fields:** partnerName, partnerAs2Id, ourAs2Id, endpointUrl

---

### Scheduled Tasks

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/scheduler` | List enabled tasks (by next run) |
| GET | `/api/scheduler/all` | List all tasks including disabled |
| GET | `/api/scheduler/{id}` | Get task details |
| POST | `/api/scheduler` | Create task |
| PUT | `/api/scheduler/{id}` | Update task |
| PATCH | `/api/scheduler/{id}/toggle` | Toggle enabled |
| DELETE | `/api/scheduler/{id}` | Delete |

---

### SLA Agreements

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/sla` | List active SLAs |
| POST | `/api/sla` | Create SLA |
| PUT | `/api/sla/{id}` | Update SLA |
| DELETE | `/api/sla/{id}` | Delete |
| GET | `/api/sla/breaches` | List active SLA breaches |

---

### Platform Settings

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/platform-settings` | List settings (`?env=`, `?service=`, `?category=` filters) |
| GET | `/api/platform-settings/{id}` | Get setting |
| POST | `/api/platform-settings` | Create setting |
| PUT | `/api/platform-settings/{id}` | Update setting |
| PATCH | `/api/platform-settings/{id}/value` | Update value only |
| DELETE | `/api/platform-settings/{id}` | Delete |
| GET | `/api/platform-settings/environments` | List distinct environments |
| GET | `/api/platform-settings/services` | List distinct service names |
| GET | `/api/platform-settings/categories` | List distinct categories |
| POST | `/api/platform-settings/clone?source=TEST&target=CERT` | Clone environment settings |

---

### Server Configuration

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/servers` | List servers (optional `?type=` filter) |
| GET | `/api/servers/{id}` | Get server |
| POST | `/api/servers` | Create server config |
| PUT | `/api/servers/{id}` | Update server |
| PATCH | `/api/servers/{id}/active?value=true` | Enable/disable |
| DELETE | `/api/servers/{id}` | Delete |

---

### Legacy Servers

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/legacy-servers` | List (optional `?protocol=` filter) |
| GET | `/api/legacy-servers/{id}` | Get server |
| POST | `/api/legacy-servers` | Create |
| PUT | `/api/legacy-servers/{id}` | Update |
| DELETE | `/api/legacy-servers/{id}` | Delete |

---

### Activity

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/activity/snapshot` | Current activity snapshot |
| GET | `/api/activity/transfers` | Active transfers |
| GET | `/api/activity/events?limit=50` | Recent events |

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8084` | HTTP port |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ port |
| `JWT_SECRET` | (shared) | JWT signing key |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier |

**Connection pool:** 5 max connections, 2 min idle, 5-minute idle timeout.

---

## Event Publishing

Config-service publishes events to RabbitMQ exchange `file-transfer.events` on routing key `config.changed`:

| Event Type | Trigger |
|-----------|---------|
| `platform.setting.created` | New setting created |
| `platform.setting.updated` | Setting value changed |
| `platform.setting.deleted` | Setting removed |
| `server.config.created` | New server config |
| `server.config.updated` | Server config changed |
| `server.config.enabled` | Server activated |
| `server.config.disabled` | Server deactivated |
| `server.config.deleted` | Server removed |

---

## Dependencies

- **PostgreSQL** — Required. All configuration stored in database.
- **RabbitMQ** — Required. Publishes config change events.
- **shared** module — Entities, repositories, enums.

# Config Service — Standalone Product Guide

> **Platform configuration management.** File flows, connectors, security profiles, scheduling, delivery endpoints, AS2 partnerships, SLA agreements, and platform-wide settings.

**Port:** 8084 | **Dependencies:** PostgreSQL, RabbitMQ | **Auth:** JWT Bearer token

---

## Why Use This

- **File flows** — Define multi-step processing pipelines (encrypt → classify → forward)
- **Connectors** — Webhook integrations (Slack, Teams, PagerDuty, ServiceNow, OpsGenie)
- **Security profiles** — Reusable security configurations for accounts and flows
- **Scheduling** — Cron-based task scheduling with enable/disable
- **Delivery endpoints** — Configure external destinations with credentials
- **AS2 partnerships** — Manage B2B AS2/AS4 trading partner agreements
- **Platform settings** — Multi-environment, multi-service configuration store

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq config-service
```

---

## API Reference — File Flows

### Create a File Flow

**POST** `/api/flows`

```bash
curl -X POST http://localhost:8084/api/flows \
  -H "Content-Type: application/json" \
  -d '{
    "name": "PCI Encrypt and Forward",
    "description": "Classify for PCI, encrypt, then forward to archive",
    "priority": 10,
    "active": true,
    "triggerPattern": "*.csv",
    "steps": [
      {"type": "CLASSIFY", "order": 1, "config": {"categories": ["PCI", "PII"]}},
      {"type": "ENCRYPT", "order": 2, "config": {"algorithm": "AES_256_GCM"}},
      {"type": "FORWARD", "order": 3, "config": {"destination": "archive-sftp"}}
    ]
  }'
```

### List Flows / Get Flow / Toggle / Delete

```bash
curl http://localhost:8084/api/flows
curl http://localhost:8084/api/flows/{id}
curl -X PATCH http://localhost:8084/api/flows/{id}/toggle
curl -X DELETE http://localhost:8084/api/flows/{id}
```

### List Flow Step Types

**GET** `/api/flows/step-types`

```bash
curl http://localhost:8084/api/flows/step-types
```

### View Flow Executions

**GET** `/api/flows/executions`

```bash
curl "http://localhost:8084/api/flows/executions?status=COMPLETED&page=0&size=20"
curl http://localhost:8084/api/flows/executions/TRZA3X5T3LUY
```

---

## API Reference — Connectors

### Create Webhook Connector

**POST** `/api/connectors`

```bash
curl -X POST http://localhost:8084/api/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Slack Alert",
    "type": "SLACK",
    "url": "https://hooks.slack.com/services/T.../B.../xxx",
    "events": ["TRANSFER_FAILED", "SLA_BREACH"],
    "active": true
  }'
```

### Test Connector

**POST** `/api/connectors/{id}/test`

```bash
curl -X POST http://localhost:8084/api/connectors/{id}/test
```

### List Connector Types

**GET** `/api/connectors/types`

---

## API Reference — Security Profiles

```bash
# List
curl http://localhost:8084/api/security-profiles

# Create
curl -X POST http://localhost:8084/api/security-profiles \
  -H "Content-Type: application/json" \
  -d '{
    "name": "PCI Compliant",
    "encryptionRequired": true,
    "encryptionAlgorithm": "AES_256_GCM",
    "integrityCheckRequired": true,
    "maxFileSizeMb": 100,
    "allowedFileTypes": ["csv", "xml", "json"]
  }'
```

---

## API Reference — Delivery Endpoints

### Create Delivery Endpoint

**POST** `/api/delivery-endpoints`

```bash
curl -X POST http://localhost:8084/api/delivery-endpoints \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Partner SFTP Server",
    "protocol": "SFTP",
    "host": "sftp.partner.com",
    "port": 22,
    "username": "tranzfer_upload",
    "password": "encrypted-password",
    "remotePath": "/inbox",
    "active": true
  }'
```

### List / Toggle / Summary

```bash
curl http://localhost:8084/api/delivery-endpoints
curl "http://localhost:8084/api/delivery-endpoints?protocol=SFTP"
curl -X PATCH http://localhost:8084/api/delivery-endpoints/{id}/toggle
curl http://localhost:8084/api/delivery-endpoints/summary
curl http://localhost:8084/api/delivery-endpoints/protocols
curl http://localhost:8084/api/delivery-endpoints/auth-types
curl http://localhost:8084/api/delivery-endpoints/proxy-types
```

---

## API Reference — Scheduling

```bash
# Create scheduled task
curl -X POST http://localhost:8084/api/scheduler \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Daily Report Generation",
    "cronExpression": "0 0 6 * * ?",
    "taskType": "FLOW_TRIGGER",
    "config": {"flowId": "abc-123"},
    "enabled": true
  }'

# List / Toggle / Delete
curl http://localhost:8084/api/scheduler
curl -X PATCH http://localhost:8084/api/scheduler/{id}/toggle
curl -X DELETE http://localhost:8084/api/scheduler/{id}
```

---

## API Reference — Platform Settings

```bash
# List settings (with filters)
curl "http://localhost:8084/api/platform-settings?env=PROD&service=sftp-service"

# Create setting
curl -X POST http://localhost:8084/api/platform-settings \
  -H "Content-Type: application/json" \
  -d '{
    "key": "sftp.max-connections",
    "value": "500",
    "environment": "PROD",
    "service": "sftp-service",
    "category": "performance"
  }'

# Update value
curl -X PATCH http://localhost:8084/api/platform-settings/{id}/value \
  -H "Content-Type: application/json" \
  -d '{"value": "1000"}'

# Clone settings between environments
curl -X POST "http://localhost:8084/api/platform-settings/clone?source=PROD&target=STAGING"

# List environments / services / categories
curl http://localhost:8084/api/platform-settings/environments
curl http://localhost:8084/api/platform-settings/services
curl http://localhost:8084/api/platform-settings/categories
```

---

## API Reference — SLA Agreements

```bash
# Create SLA
curl -X POST http://localhost:8084/api/sla \
  -H "Content-Type: application/json" \
  -d '{
    "partnerUsername": "acme_corp",
    "deliveryWindowHours": 4,
    "maxErrorRate": 0.02,
    "active": true
  }'

# Check breaches
curl http://localhost:8084/api/sla/breaches
```

---

## API Reference — Activity Monitor

```bash
curl http://localhost:8084/api/activity/snapshot
curl http://localhost:8084/api/activity/transfers
curl http://localhost:8084/api/activity/events?limit=50
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `JWT_SECRET` | (insecure default) | JWT signing secret |
| `server.port` | `8084` | HTTP port |

---

## All Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/flows` | List file flows |
| POST | `/api/flows` | Create file flow |
| PUT | `/api/flows/{id}` | Update file flow |
| PATCH | `/api/flows/{id}/toggle` | Toggle flow active |
| DELETE | `/api/flows/{id}` | Delete flow |
| GET | `/api/flows/executions` | List flow executions |
| GET | `/api/flows/step-types` | List step types |
| GET | `/api/connectors` | List connectors |
| POST | `/api/connectors` | Create connector |
| POST | `/api/connectors/{id}/test` | Test connector |
| GET | `/api/connectors/types` | List connector types |
| GET | `/api/security-profiles` | List security profiles |
| POST | `/api/security-profiles` | Create security profile |
| GET | `/api/delivery-endpoints` | List delivery endpoints |
| POST | `/api/delivery-endpoints` | Create delivery endpoint |
| PATCH | `/api/delivery-endpoints/{id}/toggle` | Toggle endpoint |
| GET | `/api/delivery-endpoints/summary` | Endpoint summary |
| GET | `/api/scheduler` | List scheduled tasks |
| POST | `/api/scheduler` | Create task |
| PATCH | `/api/scheduler/{id}/toggle` | Toggle task |
| GET | `/api/platform-settings` | List settings |
| POST | `/api/platform-settings` | Create setting |
| PATCH | `/api/platform-settings/{id}/value` | Update value |
| POST | `/api/platform-settings/clone` | Clone environment |
| GET | `/api/sla` | List SLA agreements |
| POST | `/api/sla` | Create SLA |
| GET | `/api/sla/breaches` | Check breaches |
| GET | `/api/as2-partnerships` | List AS2 partnerships |
| POST | `/api/as2-partnerships` | Create partnership |
| GET | `/api/activity/snapshot` | Activity snapshot |
| GET | `/api/activity/events` | Recent events |

# License Service

> License validation, trial management, product catalog, and entitlement enforcement.

**Port:** 8089 | **Database:** PostgreSQL | **Required:** Optional

---

## Overview

The license service manages software licensing for the platform:

- **License validation** — RSA-SHA256 signed license keys
- **Trial mode** — 30-day free trial with no license key
- **License issuance** — Admin API to generate signed licenses
- **Activation tracking** — Per-service, per-host check-in records
- **Product catalog** — Components, tiers, and entitlements
- **Revocation** — Disable compromised or expired licenses

---

## Quick Start

```bash
docker compose up -d postgres license-service

# Health check
curl http://localhost:8089/api/v1/licenses/health

# Start a trial
curl -X POST http://localhost:8089/api/v1/licenses/trial \
  -H "Content-Type: application/json" \
  -d '{"serviceType":"SFTP","hostId":"my-host","customerId":"acme"}'
```

---

## API Endpoints

### License Validation (Public)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/licenses/validate` | Validate a license key |
| POST | `/api/v1/licenses/trial` | Activate trial |

**Validate license:**
```bash
curl -X POST http://localhost:8089/api/v1/licenses/validate \
  -H "Content-Type: application/json" \
  -d '{
    "licenseKey": "eyJ...base64payload.signature",
    "serviceType": "SFTP",
    "hostId": "server-01",
    "customerId": "acme-corp"
  }'
```

**Response:**
```json
{
  "valid": true,
  "edition": "PROFESSIONAL",
  "mode": "LICENSED",
  "expiresAt": "2027-04-05T00:00:00Z",
  "features": ["BASIC_SFTP", "BASIC_FTP", "FTPS", "AS2", "ANALYTICS", "EDI"],
  "maxInstances": 5,
  "maxConcurrentConnections": 100,
  "message": "License valid"
}
```

**Activate trial (no license key needed):**
```bash
curl -X POST http://localhost:8089/api/v1/licenses/trial \
  -H "Content-Type: application/json" \
  -d '{"serviceType":"SFTP","hostId":"dev-laptop","customerId":"eval-user"}'
```

**Response:**
```json
{
  "valid": true,
  "edition": "STANDARD",
  "mode": "TRIAL",
  "trialDaysRemaining": 30,
  "features": ["BASIC_SFTP", "BASIC_FTP", "ADMIN_UI"],
  "maxInstances": 1,
  "maxConcurrentConnections": 10
}
```

### License Administration (Requires `X-Admin-Key` header)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/licenses/issue` | Issue new license |
| GET | `/api/v1/licenses` | List all licenses |
| DELETE | `/api/v1/licenses/{id}/revoke` | Revoke a license |
| GET | `/api/v1/licenses/{id}/activations` | Get activations for license |

**Issue a license:**
```bash
curl -X POST http://localhost:8089/api/v1/licenses/issue \
  -H "X-Admin-Key: license_admin_secret_key" \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "acme-corp",
    "customerName": "ACME Corporation",
    "edition": "PROFESSIONAL",
    "validDays": 365,
    "services": [
      {"serviceType": "SFTP", "maxInstances": 5, "features": ["BASIC_SFTP", "FTPS"]},
      {"serviceType": "FTP", "maxInstances": 3, "features": ["BASIC_FTP"]}
    ]
  }'
```

**Response:**
```json
{"licenseKey": "eyJsaWNlbnNlSWQiOi...base64payload.RSA_signature"}
```

### Product Catalog (Public)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/licenses/catalog/components` | List all licensable components |
| GET | `/api/v1/licenses/catalog/tiers` | List product tiers |
| POST | `/api/v1/licenses/catalog/entitled` | Get entitled components for a license |

---

## Product Tiers

| Tier | Components | Max Concurrent |
|------|-----------|----------------|
| **STANDARD** | SFTP, FTP, Web Portal, Encryption, Keystore, Forwarder, Gateway, Admin UI | 10 |
| **PROFESSIONAL** | Standard + AS2/AS4, Analytics, EDI, DMZ Proxy, Partner Portal | 100 |
| **ENTERPRISE** | All components including AI Engine, Screening, Storage Manager | Unlimited |

---

## License Key Format

Licenses are signed with RSA-SHA256:
```
{base64url_payload}.{base64url_signature}
```

**Payload:**
```json
{
  "licenseId": "LIC-a1b2c3d4",
  "customerId": "acme-corp",
  "customerName": "ACME Corporation",
  "edition": "PROFESSIONAL",
  "issuedAt": "2026-04-05T00:00:00Z",
  "expiresAt": "2027-04-05T00:00:00Z",
  "services": [...]
}
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8089` | API port |
| `LICENSE_ADMIN_KEY` | `license_admin_secret_key` | Admin key for issue/revoke (**must change**) |
| `LICENSE_TRIAL_DAYS` | `30` | Free trial duration |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |

---

## Dependencies

- **PostgreSQL** — Required. License records, activations, fingerprints.
- **shared** module — Entities, repositories.

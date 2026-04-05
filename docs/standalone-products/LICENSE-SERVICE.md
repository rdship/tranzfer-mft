# License Service — Standalone Product Guide

> **License management as a service.** Issue, validate, and manage RSA-signed license keys with trial support, product catalog, and entitlement tracking.

**Port:** 8089 | **Dependencies:** PostgreSQL | **Auth:** X-Admin-Key for admin ops

---

## Why Use This

- **RSA-signed licenses** — Cryptographic license keys that can't be forged
- **Trial management** — 30-day trial with automatic tracking
- **Product catalog** — Component-based entitlements with tier grouping
- **Activation tracking** — Track where licenses are deployed and check-in timestamps
- **Edition support** — Standard, Professional, Enterprise tiers

---

## Quick Start

```bash
docker compose up -d postgres license-service
curl http://localhost:8089/api/v1/licenses/health
```

---

## API Reference

### 1. Validate a License

**POST** `/api/v1/licenses/validate`

```bash
curl -X POST http://localhost:8089/api/v1/licenses/validate \
  -H "Content-Type: application/json" \
  -d '{
    "licenseKey": "TRZ-ENT-abc123...",
    "serviceType": "SFTP",
    "hostId": "server-01",
    "installationFingerprint": "fp-abc-123"
  }'
```

**Response:**
```json
{
  "valid": true,
  "mode": "LICENSED",
  "edition": "ENTERPRISE",
  "message": "License valid",
  "expiresAt": "2027-04-05T00:00:00Z",
  "maxInstances": 10,
  "maxConcurrentConnections": 5000,
  "features": ["SFTP", "FTP", "FTPS", "AS2", "ENCRYPTION", "AI_ENGINE"]
}
```

### 2. Activate Trial

**POST** `/api/v1/licenses/trial`

```bash
curl -X POST http://localhost:8089/api/v1/licenses/trial \
  -H "Content-Type: application/json" \
  -d '{
    "serviceType": "SFTP",
    "hostId": "dev-laptop",
    "fingerprint": "fp-dev-001",
    "customerId": "eval-customer",
    "customerName": "Evaluation User"
  }'
```

**Response:**
```json
{
  "valid": true,
  "mode": "TRIAL",
  "edition": "TRIAL",
  "maxInstances": 1,
  "maxConcurrentConnections": 10,
  "features": ["BASIC_SFTP", "BASIC_FTP", "ADMIN_UI"],
  "trialDaysRemaining": 30
}
```

### 3. Issue a License (Admin)

**POST** `/api/v1/licenses/issue`

```bash
curl -X POST http://localhost:8089/api/v1/licenses/issue \
  -H "X-Admin-Key: license_admin_secret_key" \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-001",
    "customerName": "Acme Corp",
    "edition": "PROFESSIONAL",
    "validDays": 365,
    "services": [
      {"serviceType": "SFTP", "maxInstances": 5, "maxConcurrentConnections": 1000, "features": ["SFTP", "SSH_KEYS"]},
      {"serviceType": "FTP", "maxInstances": 3, "maxConcurrentConnections": 500, "features": ["FTP", "FTPS"]}
    ],
    "notes": "Annual license for Acme Corp"
  }'
```

**Response:**
```json
{
  "licenseKey": "TRZ-PRO-eyJsaWNlbnNlSWQiOiIuLi4..."
}
```

### 4. List All Licenses (Admin)

**GET** `/api/v1/licenses`

```bash
curl http://localhost:8089/api/v1/licenses \
  -H "X-Admin-Key: license_admin_secret_key"
```

### 5. Revoke a License (Admin)

**DELETE** `/api/v1/licenses/{licenseId}/revoke`

```bash
curl -X DELETE http://localhost:8089/api/v1/licenses/a1b2c3d4/revoke \
  -H "X-Admin-Key: license_admin_secret_key"
```

### 6. View Activations (Admin)

**GET** `/api/v1/licenses/{licenseId}/activations`

```bash
curl http://localhost:8089/api/v1/licenses/a1b2c3d4/activations \
  -H "X-Admin-Key: license_admin_secret_key"
```

### 7. Product Catalog — Components

**GET** `/api/v1/licenses/catalog/components`

```bash
curl http://localhost:8089/api/v1/licenses/catalog/components
```

### 8. Product Catalog — Tiers

**GET** `/api/v1/licenses/catalog/tiers`

```bash
curl http://localhost:8089/api/v1/licenses/catalog/tiers
```

### 9. Check Entitlements

**POST** `/api/v1/licenses/catalog/entitled`

```bash
curl -X POST http://localhost:8089/api/v1/licenses/catalog/entitled \
  -H "Content-Type: application/json" \
  -d '{
    "licenseKey": "TRZ-ENT-abc123...",
    "serviceType": "SFTP",
    "hostId": "server-01",
    "installationFingerprint": "fp-abc-123"
  }'
```

**Response:**
```json
{
  "valid": true,
  "edition": "ENTERPRISE",
  "mode": "LICENSED",
  "entitledComponents": [
    {"id": "sftp-server", "name": "SFTP Server", "category": "PROTOCOL_ADAPTERS"},
    {"id": "ai-engine", "name": "AI Engine", "category": "ADVANCED_FEATURES"}
  ],
  "maxInstances": 10,
  "maxConcurrentConnections": 5000
}
```

---

## Integration Examples

### Python — License Validation Gate
```python
import requests

def check_license(license_key, service_type, host_id):
    result = requests.post("http://localhost:8089/api/v1/licenses/validate", json={
        "licenseKey": license_key,
        "serviceType": service_type,
        "hostId": host_id,
        "installationFingerprint": f"fp-{host_id}"
    }).json()

    if not result["valid"]:
        raise Exception(f"License invalid: {result.get('message')}")

    return result

# On startup
license = check_license("TRZ-PRO-abc...", "MY_SERVICE", "prod-01")
print(f"Edition: {license['edition']}, Max connections: {license['maxConcurrentConnections']}")
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `LICENSE_TRIAL_DAYS` | `30` | Trial period duration |
| `LICENSE_ADMIN_KEY` | `license_admin_secret_key` | Admin API key |
| `LICENSE_VALIDATION_CACHE_HOURS` | `6` | Validation cache TTL |
| `server.port` | `8089` | HTTP port |

---

## All Endpoints Summary

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/licenses/validate` | None | Validate license key |
| POST | `/api/v1/licenses/trial` | None | Activate trial |
| POST | `/api/v1/licenses/issue` | X-Admin-Key | Issue new license |
| GET | `/api/v1/licenses` | X-Admin-Key | List all licenses |
| DELETE | `/api/v1/licenses/{id}/revoke` | X-Admin-Key | Revoke license |
| GET | `/api/v1/licenses/{id}/activations` | X-Admin-Key | View activations |
| GET | `/api/v1/licenses/catalog/components` | None | Product components |
| GET | `/api/v1/licenses/catalog/tiers` | None | Product tiers |
| POST | `/api/v1/licenses/catalog/entitled` | None | Check entitlements |
| GET | `/api/v1/licenses/health` | None | Health check |

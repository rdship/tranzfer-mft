# License Service — Demo & Quick Start Guide

> RSA-signed license key generation, validation, trial management, and product catalog for the TranzFer MFT platform.

---

## What This Service Does

- **Issues RSA-signed license keys** — Each license key is a Base64-encoded JSON payload signed with SHA256withRSA (2048-bit). The key contains the customer name, edition, expiry date, per-service limits, and entitled features. Tamper-proof by design.
- **Validates license keys cryptographically** — Any TranzFer microservice can call the license service to verify a key. The service checks the RSA signature, expiration, revocation status, and returns the customer's entitlements.
- **Manages 30-day trials automatically** — When a service starts without a license key, it sends its installation fingerprint. The license service creates a trial record (30 days, 1 instance, 10 concurrent connections) and tracks expiration.
- **Provides a product catalog** — Lists all 25+ licensable components grouped by category (Core, Servers, Clients, Engines, Connectors, Security, etc.) and three product tiers (Standard, Professional, Enterprise) with their included components.
- **Tracks activations per license** — Records which services, on which hosts, have checked in with a given license key. Supports revocation.

---

## What You Need (Prerequisites Checklist)

- [ ] **Operating System:** Linux, macOS, or Windows
- [ ] **Docker** (recommended) OR **Java 21 + Maven** — [Install guide](PREREQUISITES.md)
- [ ] **PostgreSQL 16** — [Install guide](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it)
- [ ] **curl** — pre-installed on Linux/macOS; Windows: `winget install cURL.cURL`
- [ ] **Ports available:** `5432` (PostgreSQL), `8089` (License Service)

---

## Install & Start

### Step 0: Start PostgreSQL (Required)

If you do not already have PostgreSQL running, start it now. Every method below needs it.

```bash
docker run -d \
  --name mft-postgres \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine
```

Verify it is accepting connections:

```bash
docker exec mft-postgres pg_isready -U postgres
```

Expected output:

```
/var/run/postgresql:5432 - accepting connections
```

---

### Method 1: Docker (Any OS)

**Build the image** (from the repository root):

```bash
cd file-transfer-platform

# Build the JAR first (Docker COPY expects it in target/)
mvn clean package -DskipTests -pl license-service -am

# Build the Docker image
docker build -t mft-license-service ./license-service
```

**Run the container:**

```bash
docker run -d \
  --name mft-license-service \
  -p 8089:8089 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e LICENSE_ADMIN_KEY=my-secret-admin-key \
  mft-license-service
```

> **Linux note:** Replace `host.docker.internal` with `172.17.0.1` (the Docker bridge IP) or use `--network host` instead of `-p 8089:8089`.

---

### Method 2: Docker Compose (with PostgreSQL)

Create a file called `docker-compose-license.yml`:

```yaml
version: '3.9'
services:
  postgres:
    image: postgres:16-alpine
    container_name: mft-postgres
    environment:
      POSTGRES_DB: filetransfer
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5

  license-service:
    build: ./license-service
    container_name: mft-license-service
    ports:
      - "8089:8089"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      SERVER_PORT: 8089
      LICENSE_ADMIN_KEY: my-secret-admin-key
    depends_on:
      postgres:
        condition: service_healthy
```

Run from the repository root:

```bash
docker compose -f docker-compose-license.yml up -d
```

---

### Method 3: From Source

```bash
cd file-transfer-platform

# Build (first time takes 5-15 minutes for dependency downloads)
mvn clean package -DskipTests -pl license-service -am

# Run
java -jar license-service/target/license-service-1.0.0-SNAPSHOT.jar
```

The service reads its configuration from `license-service/src/main/resources/application.yml`. Default settings connect to PostgreSQL on `localhost:5432` with database `filetransfer`, user `postgres`, password `postgres`.

To override settings:

```bash
java -jar license-service/target/license-service-1.0.0-SNAPSHOT.jar \
  --license.admin-key=my-secret-admin-key \
  --license.trial-days=14
```

---

## Verify It's Running

```bash
curl -s http://localhost:8089/api/v1/licenses/health | python3 -m json.tool
```

Expected output:

```json
{
    "status": "UP",
    "service": "license-service"
}
```

**Windows PowerShell alternative:**

```powershell
Invoke-RestMethod http://localhost:8089/api/v1/licenses/health | ConvertTo-Json
```

---

## Demo 1: Activate a Trial and Validate It

The trial flow is what happens when a TranzFer service starts for the first time without a purchased license key. The service sends its installation fingerprint, and the license service grants a 30-day trial.

### Step 1 — Activate a trial

```bash
curl -s -X POST http://localhost:8089/api/v1/licenses/trial \
  -H "Content-Type: application/json" \
  -d '{
    "fingerprint": "abc123-machine-id-hash",
    "customerId": "eval-northwind",
    "customerName": "Northwind Traders Inc",
    "serviceType": "SFTP_SERVER",
    "hostId": "dev-laptop-01"
  }' | python3 -m json.tool
```

**Windows PowerShell:**

```powershell
$body = @{
    fingerprint  = "abc123-machine-id-hash"
    customerId   = "eval-northwind"
    customerName = "Northwind Traders Inc"
    serviceType  = "SFTP_SERVER"
    hostId       = "dev-laptop-01"
} | ConvertTo-Json

Invoke-RestMethod -Uri http://localhost:8089/api/v1/licenses/trial `
  -Method Post -ContentType "application/json" -Body $body | ConvertTo-Json -Depth 3
```

Expected output:

```json
{
    "valid": true,
    "edition": "TRIAL",
    "mode": "TRIAL",
    "trialDaysRemaining": 30,
    "expiresAt": "2026-05-05T12:00:00.000Z",
    "features": [
        "BASIC_SFTP",
        "BASIC_FTP",
        "ADMIN_UI"
    ],
    "maxInstances": 1,
    "maxConcurrentConnections": 10,
    "message": "Trial active. 30 days remaining."
}
```

### Understanding the Trial Response

| Field | Value | Meaning |
|-------|-------|---------|
| `valid` | `true` | The trial is active — the service should start normally |
| `mode` | `TRIAL` | This is a trial, not a purchased license |
| `trialDaysRemaining` | `30` | Calendar days until the trial expires |
| `maxInstances` | `1` | Trial allows only 1 instance of each service |
| `maxConcurrentConnections` | `10` | Trial limits concurrent connections to 10 |
| `features` | `["BASIC_SFTP", "BASIC_FTP", "ADMIN_UI"]` | Only basic features are available in trial mode |

### Step 2 — Validate the trial again (same fingerprint)

Calling the trial endpoint again with the same fingerprint does not create a new trial. It returns the existing one with an updated `trialDaysRemaining`.

```bash
curl -s -X POST http://localhost:8089/api/v1/licenses/validate \
  -H "Content-Type: application/json" \
  -d '{
    "serviceType": "SFTP_SERVER",
    "hostId": "dev-laptop-01",
    "installationFingerprint": "abc123-machine-id-hash",
    "customerId": "eval-northwind",
    "customerName": "Northwind Traders Inc"
  }' | python3 -m json.tool
```

Expected output (same trial, no new record created):

```json
{
    "valid": true,
    "edition": "TRIAL",
    "mode": "TRIAL",
    "trialDaysRemaining": 30,
    "expiresAt": "2026-05-05T12:00:00.000Z",
    "features": [
        "BASIC_SFTP",
        "BASIC_FTP",
        "ADMIN_UI"
    ],
    "maxInstances": 1,
    "maxConcurrentConnections": 10,
    "message": "Trial active. 30 days remaining."
}
```

> **Note:** The `/validate` endpoint with no `licenseKey` field (or a blank one) automatically enters trial mode. This is how every TranzFer service checks its license status on startup.

### Step 3 — What happens without a fingerprint

```bash
curl -s -X POST http://localhost:8089/api/v1/licenses/validate \
  -H "Content-Type: application/json" \
  -d '{
    "serviceType": "SFTP_SERVER",
    "hostId": "unknown-host"
  }' | python3 -m json.tool
```

Expected output:

```json
{
    "valid": false,
    "mode": "TRIAL",
    "message": "Installation fingerprint required for trial mode"
}
```

---

## Demo 2: Issue a Full License and Validate It

This demonstrates the admin workflow: issuing a purchased license key and then validating it from a service.

### Step 1 — Issue a license (requires admin key)

```bash
curl -s -X POST http://localhost:8089/api/v1/licenses/issue \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: my-secret-admin-key" \
  -d '{
    "customerId": "cust-contoso",
    "customerName": "Contoso Financial Services",
    "edition": "ENTERPRISE",
    "validDays": 365,
    "services": [
      {
        "serviceType": "SFTP_SERVER",
        "maxInstances": 10,
        "maxConcurrentConnections": 5000,
        "features": ["SFTP_SERVER", "ENCRYPTION_SERVICE", "SCREENING_SERVICE", "AI_ENGINE"]
      },
      {
        "serviceType": "FTP_SERVER",
        "maxInstances": 5,
        "maxConcurrentConnections": 2000,
        "features": ["FTP_SERVER", "ENCRYPTION_SERVICE"]
      }
    ],
    "notes": "Annual enterprise license for Contoso - signed by sales team"
  }' | python3 -m json.tool
```

**Windows PowerShell:**

```powershell
$body = @{
    customerId   = "cust-contoso"
    customerName = "Contoso Financial Services"
    edition      = "ENTERPRISE"
    validDays    = 365
    services     = @(
        @{
            serviceType             = "SFTP_SERVER"
            maxInstances            = 10
            maxConcurrentConnections = 5000
            features                = @("SFTP_SERVER", "ENCRYPTION_SERVICE", "SCREENING_SERVICE", "AI_ENGINE")
        },
        @{
            serviceType             = "FTP_SERVER"
            maxInstances            = 5
            maxConcurrentConnections = 2000
            features                = @("FTP_SERVER", "ENCRYPTION_SERVICE")
        }
    )
    notes = "Annual enterprise license for Contoso - signed by sales team"
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Uri http://localhost:8089/api/v1/licenses/issue `
  -Method Post -ContentType "application/json" `
  -Headers @{ "X-Admin-Key" = "my-secret-admin-key" } `
  -Body $body | ConvertTo-Json
```

Expected output:

```json
{
    "licenseKey": "eyJsaWNlbnNlSWQiOiJMSUMtQTFCMkMzRDQiLCJjdXN0b21lcklkIjoiY3VzdC1jb250b3NvIiwiY3VzdG9tZXJOYW1lIjoiQ29udG9zbyBGaW5hbmNpYWwgU2VydmljZXMiLCJlZGl0aW9uIjoiRU5URVJQUklTRSI9.MEUCIQD_abc123_this_is_the_rsa_signature_part"
}
```

> **Important:** Save the `licenseKey` value. This is the RSA-signed token that services will use to authenticate. The format is `<base64-payload>.<base64-signature>` (similar to a JWT but using RSA instead of HMAC).

### Step 2 — Validate the license key

Copy the `licenseKey` from the previous step and use it here:

```bash
LICENSE_KEY="<paste the licenseKey value here>"

curl -s -X POST http://localhost:8089/api/v1/licenses/validate \
  -H "Content-Type: application/json" \
  -d "{
    \"licenseKey\": \"$LICENSE_KEY\",
    \"serviceType\": \"SFTP_SERVER\",
    \"hostId\": \"prod-sftp-01\",
    \"installationFingerprint\": \"prod-fingerprint-hash\",
    \"customerId\": \"cust-contoso\",
    \"customerName\": \"Contoso Financial Services\"
  }" | python3 -m json.tool
```

Expected output:

```json
{
    "valid": true,
    "edition": "ENTERPRISE",
    "mode": "LICENSED",
    "trialDaysRemaining": null,
    "expiresAt": "2027-04-05T12:00:00.000Z",
    "features": [
        "SFTP_SERVER",
        "ENCRYPTION_SERVICE",
        "SCREENING_SERVICE",
        "AI_ENGINE"
    ],
    "maxInstances": 10,
    "maxConcurrentConnections": 5000,
    "message": "License valid for Contoso Financial Services"
}
```

### Step 3 — Try with a wrong admin key

```bash
curl -s -X POST http://localhost:8089/api/v1/licenses/issue \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: wrong-key" \
  -d '{"customerId": "test", "customerName": "Test", "edition": "STANDARD", "validDays": 30}' \
  | python3 -m json.tool
```

Expected output (HTTP 401):

```json
{
    "error": "Invalid admin key"
}
```

### Step 4 — Try with an invalid license key

```bash
curl -s -X POST http://localhost:8089/api/v1/licenses/validate \
  -H "Content-Type: application/json" \
  -d '{
    "licenseKey": "this-is-not-a-real-key",
    "serviceType": "SFTP_SERVER",
    "hostId": "test-host"
  }' | python3 -m json.tool
```

Expected output:

```json
{
    "valid": false,
    "message": "Invalid license key: Invalid license key format"
}
```

---

## Demo 3: Explore the Product Catalog

The product catalog defines every licensable component in the TranzFer platform and the three product tiers. This is used by the CLI installer to show what customers can enable.

### List all components

```bash
curl -s http://localhost:8089/api/v1/licenses/catalog/components | python3 -m json.tool
```

Expected output (abbreviated):

```json
{
    "CORE": {
        "displayName": "Core Platform",
        "description": "Essential services \u2014 always included",
        "components": [
            {
                "id": "ONBOARDING_API",
                "name": "Onboarding API",
                "description": "Core user/account management and authentication",
                "coreRequired": true,
                "minimumTier": "STANDARD",
                "helmKey": "onboardingApi"
            },
            {
                "id": "CONFIG_SERVICE",
                "name": "Configuration Service",
                "description": "Flow, encryption key, and security profile management",
                "coreRequired": true,
                "minimumTier": "STANDARD",
                "helmKey": "configService"
            },
            {
                "id": "LICENSE_SERVICE",
                "name": "License Service",
                "description": "License validation and activation management",
                "coreRequired": true,
                "minimumTier": "STANDARD",
                "helmKey": "licenseService"
            }
        ]
    },
    "SERVER": {
        "displayName": "Protocol Servers",
        "description": "Inbound file reception over SFTP, FTP, HTTPS, AS2/AS4",
        "components": [
            {
                "id": "SFTP_SERVER",
                "name": "SFTP Server",
                "description": "SSH File Transfer Protocol server (port 2222) with public key + password auth",
                "coreRequired": false,
                "minimumTier": "STANDARD",
                "helmKey": "sftpService"
            },
            {
                "id": "AS2_SERVER",
                "name": "AS2/AS4 Inbound Server",
                "description": "Receive EDI files from trading partners via AS2 (RFC 4130) or AS4 (OASIS ebMS3)",
                "coreRequired": false,
                "minimumTier": "PROFESSIONAL",
                "helmKey": "as2Service"
            }
        ]
    },
    "SECURITY": {
        "displayName": "Security & Compliance",
        "description": "Encryption, sanctions screening, key management",
        "components": [
            {
                "id": "SCREENING_SERVICE",
                "name": "Sanctions Screening",
                "description": "OFAC, EU, and UN sanctions list screening with configurable threshold",
                "coreRequired": false,
                "minimumTier": "ENTERPRISE",
                "helmKey": "screeningService"
            }
        ]
    }
}
```

> The full response contains 9 categories (CORE, SERVER, CLIENT, ENGINE, CONNECTOR, CONVERTER, SECURITY, STORAGE, UI) with 25+ components total.

### List product tiers

```bash
curl -s http://localhost:8089/api/v1/licenses/catalog/tiers | python3 -m json.tool
```

Expected output:

```json
[
    {
        "id": "STANDARD",
        "name": "Standard",
        "description": "Core file transfer with SFTP, FTP, web portal, encryption, and admin UI",
        "maxInstances": 3,
        "maxConcurrentConnections": 500,
        "componentCount": 16,
        "componentIds": [
            "ONBOARDING_API",
            "CONFIG_SERVICE",
            "LICENSE_SERVICE",
            "SFTP_SERVER",
            "FTP_SERVER",
            "FTP_WEB_SERVER",
            "SFTP_CLIENT",
            "FTP_CLIENT",
            "FTPS_CLIENT",
            "HTTP_CLIENT",
            "EXTERNAL_FORWARDER",
            "GATEWAY",
            "ENCRYPTION_SERVICE",
            "KEYSTORE_MANAGER",
            "ADMIN_UI",
            "PARTNER_PORTAL",
            "FTP_WEB_UI",
            "API_GATEWAY"
        ]
    },
    {
        "id": "PROFESSIONAL",
        "name": "Professional",
        "description": "Standard + AS2/AS4, EDI, Kafka, analytics, DMZ proxy for B2B integration",
        "maxInstances": 10,
        "maxConcurrentConnections": 2000,
        "componentCount": 22,
        "componentIds": ["...all Standard components plus AS2, AS4, Kafka, EDI, Analytics, DMZ Proxy..."]
    },
    {
        "id": "ENTERPRISE",
        "name": "Enterprise",
        "description": "All components \u2014 AI classification, sanctions screening, tiered storage, unlimited scale",
        "maxInstances": 100,
        "maxConcurrentConnections": 10000,
        "componentCount": 25,
        "componentIds": ["...every component in the platform..."]
    }
]
```

### Check entitled components for a license

This endpoint validates a license key and returns the specific components the customer is entitled to install:

```bash
curl -s -X POST http://localhost:8089/api/v1/licenses/catalog/entitled \
  -H "Content-Type: application/json" \
  -d "{
    \"licenseKey\": \"$LICENSE_KEY\",
    \"serviceType\": \"SFTP_SERVER\",
    \"hostId\": \"prod-sftp-01\"
  }" | python3 -m json.tool
```

Expected output:

```json
{
    "valid": true,
    "edition": "ENTERPRISE",
    "mode": "LICENSED",
    "entitledComponents": [
        { "id": "ONBOARDING_API", "name": "Onboarding API", "category": "CORE" },
        { "id": "CONFIG_SERVICE", "name": "Configuration Service", "category": "CORE" },
        { "id": "LICENSE_SERVICE", "name": "License Service", "category": "CORE" },
        { "id": "SFTP_SERVER", "name": "SFTP Server", "category": "SERVER" },
        { "id": "ENCRYPTION_SERVICE", "name": "Encryption Service", "category": "SECURITY" },
        { "id": "SCREENING_SERVICE", "name": "Sanctions Screening", "category": "SECURITY" },
        { "id": "AI_ENGINE", "name": "AI Classification Engine", "category": "ENGINE" },
        { "id": "STORAGE_MANAGER", "name": "Storage Manager", "category": "STORAGE" }
    ],
    "maxInstances": 10,
    "maxConcurrentConnections": 5000,
    "expiresAt": "2027-04-05T12:00:00.000Z"
}
```

---

## Demo 4: Admin Operations — List, View Activations, Revoke

### List all licenses (admin)

```bash
curl -s http://localhost:8089/api/v1/licenses \
  -H "X-Admin-Key: my-secret-admin-key" | python3 -m json.tool
```

Expected output:

```json
[
    {
        "id": "d1e2f3a4-b5c6-7890-abcd-ef1234567890",
        "licenseId": "LIC-A1B2C3D4",
        "customerId": "cust-contoso",
        "customerName": "Contoso Financial Services",
        "edition": "ENTERPRISE",
        "issuedAt": "2026-04-05T12:00:00.000Z",
        "expiresAt": "2027-04-05T12:00:00.000Z",
        "services": [
            {
                "serviceType": "SFTP_SERVER",
                "maxInstances": 10,
                "maxConcurrentConnections": 5000,
                "features": ["SFTP_SERVER", "ENCRYPTION_SERVICE", "SCREENING_SERVICE", "AI_ENGINE"]
            },
            {
                "serviceType": "FTP_SERVER",
                "maxInstances": 5,
                "maxConcurrentConnections": 2000,
                "features": ["FTP_SERVER", "ENCRYPTION_SERVICE"]
            }
        ],
        "active": true,
        "notes": "Annual enterprise license for Contoso - signed by sales team",
        "createdAt": "2026-04-05T12:00:00.000Z",
        "updatedAt": "2026-04-05T12:00:00.000Z"
    }
]
```

### View activations for a license

Copy the `licenseId` (e.g., `LIC-A1B2C3D4`) from the list above:

```bash
curl -s http://localhost:8089/api/v1/licenses/LIC-A1B2C3D4/activations \
  -H "X-Admin-Key: my-secret-admin-key" | python3 -m json.tool
```

Expected output:

```json
[
    {
        "id": "e2f3a4b5-c6d7-8901-bcde-f12345678901",
        "serviceType": "SFTP_SERVER",
        "hostId": "prod-sftp-01",
        "activatedAt": "2026-04-05T12:01:00.000Z",
        "lastCheckIn": "2026-04-05T12:01:00.000Z",
        "active": true
    }
]
```

### Revoke a license

```bash
curl -s -X DELETE http://localhost:8089/api/v1/licenses/LIC-A1B2C3D4/revoke \
  -H "X-Admin-Key: my-secret-admin-key" -w "\nHTTP Status: %{http_code}\n"
```

Expected output:

```
HTTP Status: 204
```

After revocation, any service that validates this license key will receive:

```json
{
    "valid": false,
    "message": "License not found or revoked"
}
```

---

## Demo 5: Integration Pattern — Python, Java, Node.js

### Python

```python
import requests
import json

LICENSE_URL = "http://localhost:8089/api/v1/licenses"
ADMIN_KEY = "my-secret-admin-key"

# --- Issue a license ---
issue_response = requests.post(f"{LICENSE_URL}/issue",
    headers={"X-Admin-Key": ADMIN_KEY, "Content-Type": "application/json"},
    json={
        "customerId": "cust-acme",
        "customerName": "Acme Corp",
        "edition": "PROFESSIONAL",
        "validDays": 90,
        "services": [{
            "serviceType": "SFTP_SERVER",
            "maxInstances": 5,
            "maxConcurrentConnections": 1000,
            "features": ["SFTP_SERVER", "ENCRYPTION_SERVICE", "AS2_SERVER"]
        }]
    })
license_key = issue_response.json()["licenseKey"]
print(f"Issued license key: {license_key[:50]}...")

# --- Validate the license ---
val_response = requests.post(f"{LICENSE_URL}/validate", json={
    "licenseKey": license_key,
    "serviceType": "SFTP_SERVER",
    "hostId": "acme-prod-01"
})
result = val_response.json()
print(f"Valid: {result['valid']}, Edition: {result['edition']}, Mode: {result['mode']}")
print(f"Max instances: {result['maxInstances']}, Max connections: {result['maxConcurrentConnections']}")
print(f"Features: {result['features']}")

# --- Activate a trial ---
trial_response = requests.post(f"{LICENSE_URL}/trial", json={
    "fingerprint": "python-demo-fingerprint",
    "customerId": "eval-demo",
    "customerName": "Demo Evaluation",
    "serviceType": "SFTP_SERVER",
    "hostId": "dev-machine"
})
trial = trial_response.json()
print(f"Trial valid: {trial['valid']}, Days remaining: {trial['trialDaysRemaining']}")

# --- Browse the product catalog ---
tiers = requests.get(f"{LICENSE_URL}/catalog/tiers").json()
for tier in tiers:
    print(f"{tier['name']}: {tier['componentCount']} components, "
          f"max {tier['maxInstances']} instances, {tier['maxConcurrentConnections']} connections")
```

### Java

```java
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpResponse.BodyHandlers;

public class LicenseDemo {
    private static final String BASE = "http://localhost:8089/api/v1/licenses";
    private static final String ADMIN_KEY = "my-secret-admin-key";

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // Issue a license
        String issueBody = """
            {
              "customerId": "cust-java-demo",
              "customerName": "JavaCorp Industries",
              "edition": "STANDARD",
              "validDays": 180,
              "services": [{
                "serviceType": "SFTP_SERVER",
                "maxInstances": 3,
                "maxConcurrentConnections": 500,
                "features": ["SFTP_SERVER", "ENCRYPTION_SERVICE"]
              }]
            }
            """;

        HttpRequest issueReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/issue"))
            .header("Content-Type", "application/json")
            .header("X-Admin-Key", ADMIN_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(issueBody))
            .build();

        HttpResponse<String> issueResp = client.send(issueReq, BodyHandlers.ofString());
        System.out.println("Issue response: " + issueResp.body());

        // Validate a trial
        String trialBody = """
            {
              "fingerprint": "java-demo-fp",
              "customerId": "eval-java",
              "customerName": "Java Eval Corp",
              "serviceType": "SFTP_SERVER",
              "hostId": "java-host"
            }
            """;

        HttpRequest trialReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/trial"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(trialBody))
            .build();

        HttpResponse<String> trialResp = client.send(trialReq, BodyHandlers.ofString());
        System.out.println("Trial response: " + trialResp.body());

        // Get product tiers
        HttpRequest tiersReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/catalog/tiers"))
            .GET().build();
        HttpResponse<String> tiersResp = client.send(tiersReq, BodyHandlers.ofString());
        System.out.println("Tiers: " + tiersResp.body());
    }
}
```

### Node.js

```javascript
const BASE = "http://localhost:8089/api/v1/licenses";
const ADMIN_KEY = "my-secret-admin-key";

async function demo() {
  // Issue a license
  const issueResp = await fetch(`${BASE}/issue`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Admin-Key": ADMIN_KEY,
    },
    body: JSON.stringify({
      customerId: "cust-node-demo",
      customerName: "NodeStream Data Services",
      edition: "PROFESSIONAL",
      validDays: 365,
      services: [
        {
          serviceType: "SFTP_SERVER",
          maxInstances: 10,
          maxConcurrentConnections: 2000,
          features: [
            "SFTP_SERVER",
            "ENCRYPTION_SERVICE",
            "AS2_SERVER",
            "EDI_CONVERTER",
          ],
        },
      ],
    }),
  });
  const { licenseKey } = await issueResp.json();
  console.log(`License issued: ${licenseKey.substring(0, 50)}...`);

  // Validate it
  const valResp = await fetch(`${BASE}/validate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      licenseKey,
      serviceType: "SFTP_SERVER",
      hostId: "node-prod-01",
    }),
  });
  const validation = await valResp.json();
  console.log(`Valid: ${validation.valid}, Edition: ${validation.edition}`);
  console.log(`Features: ${validation.features.join(", ")}`);

  // Browse catalog
  const catalogResp = await fetch(`${BASE}/catalog/components`);
  const catalog = await catalogResp.json();
  for (const [category, data] of Object.entries(catalog)) {
    console.log(
      `${data.displayName}: ${data.components.length} components`
    );
  }
}

demo().catch(console.error);
```

---

## API Reference

All endpoints are prefixed with `/api/v1/licenses`.

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/health` | None | Health check |
| `POST` | `/validate` | None | Validate a license key or trigger trial mode (no key = trial) |
| `POST` | `/trial` | None | Activate a trial. Fields: `fingerprint` (required), `customerId`, `customerName`, `serviceType`, `hostId` |
| `POST` | `/issue` | `X-Admin-Key` | Issue a new license. Fields: `customerId`, `customerName`, `edition`, `validDays`, `services[]`, `notes` |
| `GET` | `/` | `X-Admin-Key` | List all license records |
| `DELETE` | `/{licenseId}/revoke` | `X-Admin-Key` | Revoke a license (sets `active = false`) |
| `GET` | `/{licenseId}/activations` | `X-Admin-Key` | List all service activations for a license |
| `GET` | `/catalog/components` | None | List all licensable components grouped by category |
| `GET` | `/catalog/tiers` | None | List product tiers (Standard, Professional, Enterprise) with included components |
| `POST` | `/catalog/entitled` | None | Validate a license and return its entitled components |

**License editions:** `TRIAL`, `STANDARD`, `PROFESSIONAL`, `ENTERPRISE`

**Service types** (used in `services[].serviceType`): Any string that identifies a TranzFer service, e.g., `SFTP_SERVER`, `FTP_SERVER`, `SCREENING`, `AI_ENGINE`, `ENCRYPTION`, etc.

---

## Use Cases

1. **SaaS license gating** — Each TranzFer microservice calls `/validate` on startup. If the response is `valid: false`, the service starts in read-only mode or shuts down. This prevents unlicensed usage.

2. **Self-service trial onboarding** — Potential customers download the platform, and every service automatically enters trial mode. No license key needed. After 30 days, they must purchase or the services stop.

3. **Per-service feature gating** — The `features` array in the license controls what a specific service can do. An SFTP server with `ENCRYPTION_SERVICE` in its features can encrypt files; without it, encryption is disabled.

4. **Multi-tenant SaaS billing** — Issue separate licenses per customer. The `customerId` field lets you track which customer's services are consuming resources. Use the activations endpoint to see exactly which hosts are running.

5. **Capacity planning** — The `maxInstances` and `maxConcurrentConnections` fields enforce scale limits. A Standard license allows 3 instances and 500 connections; Enterprise allows 100 instances and 10,000 connections.

6. **License revocation** — When a customer churns or violates terms, revoke their license with a single API call. Every service that checks in will immediately see `valid: false`.

7. **Compliance audit** — The license record includes `issuedAt`, `expiresAt`, `services`, and `notes` fields. Combined with the activation log, you can prove to auditors exactly what was licensed and when it was used.

8. **CLI installer integration** — The `/catalog/components` and `/catalog/tiers` endpoints power the TranzFer CLI installer's tier selection screen. The installer reads the catalog, shows the customer their options, and generates a Helm values file that enables only the entitled components.

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC connection string |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `LICENSE_ADMIN_KEY` | `license_admin_secret_key` | API key required in the `X-Admin-Key` header for admin operations (issue, list, revoke, view activations). Change this in production. |
| `license.trial-days` | `30` | Number of days for a trial period. Set via `--license.trial-days=N` on the command line or `LICENSE_TRIAL_DAYS` env var if Spring relaxed binding applies. |
| `license.issuer-name` | `TranzFer MFT Platform` | Issuer name embedded in license payloads |
| `license.validation-cache-hours` | `6` | How long validation responses are cached (hours) |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier for multi-instance deployments |
| `CLUSTER_HOST` | `license-service` | Hostname of this instance within the cluster |
| `CONTROL_API_KEY` | `internal_control_secret` | API key for internal control plane communication |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label (PROD, STAGING, DEV) |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | JWT signing secret (shared with other platform services) |
| `JWT_EXPIRATION` | `900000` | JWT token expiration in milliseconds (15 minutes) |

---

## Cleanup

### Stop the license service

```bash
# Docker
docker stop mft-license-service && docker rm mft-license-service

# Docker Compose
docker compose -f docker-compose-license.yml down

# From source — press Ctrl+C in the terminal where it is running
```

### Stop PostgreSQL (if you started it for this demo)

```bash
docker stop mft-postgres && docker rm mft-postgres
```

### Remove Docker volumes (deletes all data)

```bash
docker volume rm $(docker volume ls -q --filter name=mft)
```

---

## Troubleshooting

### All Platforms

**"Invalid admin key" on `/issue`, `/revoke`, or list endpoints**

The `X-Admin-Key` header must match the `LICENSE_ADMIN_KEY` environment variable exactly. Defaults:

- If you started with Docker and set `-e LICENSE_ADMIN_KEY=my-secret-admin-key`, use `my-secret-admin-key`.
- If you started from source without overrides, the default is `license_admin_secret_key`.

Check what value the service is using:

```bash
# Docker
docker exec mft-license-service env | grep LICENSE_ADMIN_KEY

# From source — it is in application.yml under license.admin-key
```

**"License not found or revoked" after issuing**

The license key is cryptographically signed at the time of issue. If you restart the service, a new RSA key pair is generated (the keys are not persisted to disk). Any license keys issued before the restart will fail signature verification.

This is expected in development. In production, configure persistent RSA keys via environment variables or a mounted key file.

**Flyway migration errors on startup**

If the service fails to start with migration errors, it usually means another TranzFer service already created the database schema with a different Flyway version. Reset the schema:

```bash
docker exec mft-postgres psql -U postgres -d filetransfer -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
```

Then restart the service.

---

### Linux

**"Connection refused" to PostgreSQL at 172.17.0.1**

The Docker bridge network may use a different IP. Find it:

```bash
ip addr show docker0 | grep inet
```

Or use `--network host` when running the license service container:

```bash
docker run -d --name mft-license-service --network host \
  -e DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer \
  -e LICENSE_ADMIN_KEY=my-secret-admin-key \
  mft-license-service
```

---

### macOS

**"host.docker.internal" not resolving**

This hostname is supported in Docker Desktop for Mac. If you are using Colima or another Docker runtime:

```bash
# Find your host IP
ifconfig en0 | grep "inet "

# Use that IP in DATABASE_URL
docker run -d --name mft-license-service -p 8089:8089 \
  -e DATABASE_URL=jdbc:postgresql://192.168.1.100:5432/filetransfer \
  -e LICENSE_ADMIN_KEY=my-secret-admin-key \
  mft-license-service
```

---

### Windows

**curl not found**

Use PowerShell's built-in `Invoke-RestMethod` instead (shown in each demo above), or install curl:

```powershell
winget install cURL.cURL
```

**Setting the `X-Admin-Key` header with curl on Windows**

On Windows Command Prompt, use double quotes for headers:

```cmd
curl -X POST http://localhost:8089/api/v1/licenses/issue ^
  -H "Content-Type: application/json" ^
  -H "X-Admin-Key: my-secret-admin-key" ^
  -d "{\"customerId\":\"test\",\"customerName\":\"Test\",\"edition\":\"STANDARD\",\"validDays\":30}"
```

On PowerShell, use backticks for line continuation and `Invoke-RestMethod` for best results (shown in demos above).

**Port 8089 blocked by firewall**

```powershell
# Allow the port through Windows Firewall
New-NetFirewallRule -DisplayName "MFT License" -Direction Inbound -Port 8089 -Protocol TCP -Action Allow
```

---

## What's Next

- **Issue licenses for other services** — Try issuing a STANDARD license and see how the entitled components differ from ENTERPRISE.
- **Connect to the Screening Service** — The screening service validates its own license on startup. See [SCREENING-SERVICE.md](SCREENING-SERVICE.md).
- **Explore the Admin UI** — The admin dashboard at `http://localhost:3000` shows license status, activations, and expiry dates. See the full platform setup in the [Demo Index](README.md).
- **Test license expiration** — Issue a license with `"validDays": 0` and validate it to see the expiration handling.
- **Explore the full platform** — Run `docker compose up -d` from the repository root to start all 17 services and 3 UIs.

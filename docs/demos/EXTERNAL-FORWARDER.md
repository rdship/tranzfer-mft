# External Forwarder Service — Demo & Quick Start Guide

> Forward files to external partners over 7 protocols (SFTP, FTP, FTPS, HTTP/HTTPS, AS2, AS4, Kafka) with smart retry, exponential backoff, and optional DMZ proxy routing.

---

## What This Service Does

- **Delivers files to external systems via 7 protocols** — SFTP (Apache MINA SSHD client), FTP (Apache Commons Net), FTPS (TLS-encrypted FTP), HTTP/HTTPS/API (RestTemplate with multiple auth types), AS2 (RFC 4130 B2B messaging), AS4 (OASIS ebMS3), and Kafka (topic-based byte streaming).
- **Two API models: legacy destinations and delivery endpoints** — `ExternalDestination` is the simpler model (SFTP/FTP/Kafka with host/port/credentials). `DeliveryEndpoint` is the richer model supporting all 7 protocols, multiple auth types (BASIC, BEARER_TOKEN, API_KEY, OAUTH2, NONE), custom HTTP headers, TLS settings, and retry configuration.
- **Smart retry with exponential backoff and jitter** — configurable per endpoint. Formula: `base * 2^attempt * random(0.75, 1.25)`, capped at 2 minutes. Non-transient errors (auth failures, 401/403, permission denied, certificate errors) are detected and not retried.
- **Base64 API for programmatic file delivery** — every endpoint supports both multipart file upload and Base64-encoded body, making it easy to call from any language without multipart form handling.
- **Optional DMZ proxy routing** — delivery endpoints can be configured to route traffic through a DMZ proxy. The forwarder dynamically registers temporary port mappings on the proxy, delivers the file through the DMZ zone, and cleans up the mapping afterward.

---

## What You Need (Prerequisites Checklist)

- [ ] **Operating System:** Linux, macOS, or Windows
- [ ] **Docker** (recommended) OR **Java 21 + Maven** — [Install guide](PREREQUISITES.md)
- [ ] **PostgreSQL 16** — [Install guide](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it)
- [ ] **RabbitMQ 3.13** — [Install guide](PREREQUISITES.md#step-3--install-rabbitmq-if-your-service-needs-it)
- [ ] **curl** — pre-installed on Linux/macOS; Windows: `winget install cURL.cURL`
- [ ] **Ports available:** `5432` (PostgreSQL), `5672` (RabbitMQ), `8087` (Forwarder HTTP API)

---

## Install & Start

### Step 0: Start PostgreSQL and RabbitMQ (Required)

If you do not already have PostgreSQL and RabbitMQ running, start them now.

```bash
# PostgreSQL
docker run -d \
  --name mft-postgres \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine

# RabbitMQ
docker run -d \
  --name mft-rabbitmq \
  -e RABBITMQ_DEFAULT_USER=guest \
  -e RABBITMQ_DEFAULT_PASS=guest \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3.13-management-alpine
```

Verify both are accepting connections:

```bash
docker exec mft-postgres pg_isready -U postgres
docker exec mft-rabbitmq rabbitmq-diagnostics ping
```

Expected output:

```
/var/run/postgresql:5432 - accepting connections
Ping succeeded
```

---

### Method 1: Docker (Any OS)

**Build the image** (from the repository root):

```bash
cd file-transfer-platform

# Build the shared library and forwarder JAR
mvn clean package -DskipTests -pl external-forwarder-service -am

# Build the Docker image
docker build -t mft-forwarder-service ./external-forwarder-service
```

**Run the container:**

```bash
docker run -d \
  --name mft-forwarder-service \
  -p 8087:8087 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e RABBITMQ_HOST=host.docker.internal \
  -e RABBITMQ_PORT=5672 \
  -e SERVER_PORT=8087 \
  mft-forwarder-service
```

> **Linux note:** Replace `host.docker.internal` with `172.17.0.1` (the Docker bridge IP) or use `--network host` instead of `-p 8087:8087`.

---

### Method 2: Docker Compose (with All Dependencies + Target SFTP Server)

This is the recommended demo setup. It includes a target SFTP server (using `atmoz/sftp`) that the forwarder can actually deliver files to, so you can verify end-to-end delivery.

Save this as `docker-compose-forwarder.yml` in the repository root:

```yaml
version: '3.9'
services:
  postgres:
    image: postgres:16-alpine
    container_name: mft-fwd-postgres
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

  rabbitmq:
    image: rabbitmq:3.13-management-alpine
    container_name: mft-fwd-rabbitmq
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest
    ports:
      - "5672:5672"
      - "15672:15672"
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Target SFTP server — the forwarder delivers files here
  target-sftp:
    image: atmoz/sftp
    container_name: mft-fwd-target-sftp
    ports:
      - "2223:22"
    command: testuser:testpass:::upload

  # Simple HTTP receiver — the forwarder delivers files here via HTTP POST
  target-http:
    image: mendhak/http-https-echo:latest
    container_name: mft-fwd-target-http
    ports:
      - "9080:8080"
    environment:
      HTTP_PORT: 8080

  # The External Forwarder itself
  forwarder-service:
    build: ./external-forwarder-service
    container_name: mft-fwd-forwarder
    ports:
      - "8087:8087"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
      SERVER_PORT: 8087
      CLUSTER_HOST: forwarder-service
      JWT_SECRET: change_me_in_production_256bit_secret_key!!
      CONTROL_API_KEY: internal_control_secret
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
```

**Start everything:**

```bash
docker compose -f docker-compose-forwarder.yml up -d --build
```

**Watch startup progress:**

```bash
docker compose -f docker-compose-forwarder.yml ps
```

Expected output (after 30-60 seconds):

```
NAME                    SERVICE              STATUS
mft-fwd-postgres        postgres             running (healthy)
mft-fwd-rabbitmq        rabbitmq             running (healthy)
mft-fwd-target-sftp     target-sftp          running
mft-fwd-target-http     target-http          running
mft-fwd-forwarder       forwarder-service    running
```

---

### Method 3: From Source

```bash
cd file-transfer-platform

# Build everything needed
mvn clean package -DskipTests -pl external-forwarder-service -am

# Run (PostgreSQL and RabbitMQ must already be running on localhost)
java -jar external-forwarder-service/target/*.jar
```

Expected startup log (last few lines):

```
INFO  Started ForwarderServiceApplication in X.XX seconds (process running for X.XX)
```

---

## Verify It's Running

```bash
curl -s http://localhost:8087/actuator/health | python3 -m json.tool
```

Expected output:

```json
{
    "status": "UP"
}
```

---

## Demo 1: Forward a File via HTTP (Using Delivery Endpoints)

This demo creates a delivery endpoint pointing to an HTTP server and sends a file to it. This is the simplest end-to-end demonstration.

### Step 1: Create a Delivery Endpoint in the Database

```bash
# Insert an HTTP delivery endpoint pointing to the target HTTP echo server
docker exec mft-fwd-postgres psql -U postgres -d filetransfer -c "
INSERT INTO delivery_endpoints (id, name, description, protocol, host, port, base_path, auth_type, http_method, active)
VALUES (
  'a0000000-0000-0000-0000-000000000001',
  'Demo HTTP Endpoint',
  'HTTP echo server for demo',
  'HTTP',
  'target-http',
  8080,
  '/',
  'NONE',
  'POST',
  true
) ON CONFLICT (name) DO NOTHING;
"
```

### Step 2: Forward a File Using Multipart Upload

```bash
# Create a test file
echo "Hello from TranzFer External Forwarder" > /tmp/demo-file.txt

# Forward it to the HTTP endpoint
curl -s -X POST \
  "http://localhost:8087/api/forward/deliver/a0000000-0000-0000-0000-000000000001" \
  -F "file=@/tmp/demo-file.txt" \
  -F "trackId=TRZ-DEMO-001" | python3 -m json.tool
```

Expected output:

```json
{
    "status": "delivered",
    "endpoint": "Demo HTTP Endpoint",
    "protocol": "HTTP",
    "file": "demo-file.txt"
}
```

**Windows equivalent (PowerShell):**

```powershell
Set-Content -Path "$env:TEMP\demo-file.txt" -Value "Hello from TranzFer External Forwarder"

$form = @{
    file = Get-Item "$env:TEMP\demo-file.txt"
}
Invoke-RestMethod -Uri "http://localhost:8087/api/forward/deliver/a0000000-0000-0000-0000-000000000001" `
  -Method Post -Form $form | ConvertTo-Json
```

### Step 3: Verify the HTTP Target Received It

The `mendhak/http-https-echo` container logs every request:

```bash
docker logs mft-fwd-target-http 2>&1 | tail -5
```

You will see the POST request with the file content in the logs.

---

## Demo 2: Forward a File via Base64 API

The Base64 endpoint is useful when integrating from applications that cannot easily construct multipart forms (message queues, serverless functions, batch scripts).

### Step 1: Encode a File as Base64

```bash
# Create a test file and encode it
echo "Invoice #12345 - Amount: $1,500.00" > /tmp/invoice.txt
BASE64_CONTENT=$(base64 < /tmp/invoice.txt)
echo "Encoded content: ${BASE64_CONTENT}"
```

### Step 2: Send It via the Base64 API

```bash
curl -s -X POST \
  "http://localhost:8087/api/forward/deliver/a0000000-0000-0000-0000-000000000001/base64?filename=invoice.txt&trackId=TRZ-DEMO-002" \
  -H "Content-Type: text/plain" \
  -d "${BASE64_CONTENT}" | python3 -m json.tool
```

Expected output:

```json
{
    "status": "delivered",
    "endpoint": "Demo HTTP Endpoint",
    "protocol": "HTTP",
    "file": "invoice.txt"
}
```

**Windows equivalent (PowerShell):**

```powershell
$content = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes("Invoice #12345 - Amount: `$1,500.00"))

Invoke-RestMethod -Uri "http://localhost:8087/api/forward/deliver/a0000000-0000-0000-0000-000000000001/base64?filename=invoice.txt&trackId=TRZ-DEMO-002" `
  -Method Post -ContentType "text/plain" -Body $content | ConvertTo-Json
```

---

## Demo 3: Forward a File to an SFTP Server (Legacy Destination API)

This demo uses the legacy `ExternalDestination` model and the target SFTP server from the Docker Compose setup.

### Step 1: Create an External Destination

The `encrypted_password` field normally holds an AES-encrypted password (encrypted by the encryption-service). For this demo, we insert the plaintext password. The SFTP forwarder calls `CredentialCryptoClient.decrypt()`, which may need the encryption-service running for production use. For demo purposes, you can configure a passthrough.

```bash
docker exec mft-fwd-postgres psql -U postgres -d filetransfer -c "
INSERT INTO external_destinations (id, name, type, host, port, username, encrypted_password, remote_path, active)
VALUES (
  'b0000000-0000-0000-0000-000000000001',
  'Demo SFTP Target',
  'SFTP',
  'target-sftp',
  22,
  'testuser',
  'testpass',
  '/upload',
  true
) ON CONFLICT DO NOTHING;
"
```

### Step 2: Forward a File

```bash
echo "Quarterly report data - Q4 2025" > /tmp/report.csv

curl -s -X POST \
  "http://localhost:8087/api/forward/b0000000-0000-0000-0000-000000000001" \
  -F "file=@/tmp/report.csv" | python3 -m json.tool
```

Expected output:

```json
{
    "status": "forwarded",
    "destination": "Demo SFTP Target",
    "file": "report.csv"
}
```

### Step 3: Verify the File Arrived on the Target SFTP Server

```bash
docker exec mft-fwd-target-sftp ls -la /home/testuser/upload/
```

Expected output:

```
-rw-r--r-- 1 testuser users  33 ... report.csv
```

---

## Demo 4: Integration Patterns — Python, Java, Node.js

### Python (requests)

```python
import requests
import base64

FORWARDER_URL = "http://localhost:8087"
ENDPOINT_ID = "a0000000-0000-0000-0000-000000000001"

# --- Method 1: Multipart file upload ---
with open("report.csv", "rb") as f:
    response = requests.post(
        f"{FORWARDER_URL}/api/forward/deliver/{ENDPOINT_ID}",
        files={"file": ("report.csv", f, "text/csv")},
        params={"trackId": "TRZ-PY-001"}
    )
print("Multipart:", response.json())
# {'status': 'delivered', 'endpoint': 'Demo HTTP Endpoint', 'protocol': 'HTTP', 'file': 'report.csv'}

# --- Method 2: Base64 upload ---
file_bytes = b"Hello from Python"
b64_content = base64.b64encode(file_bytes).decode("utf-8")

response = requests.post(
    f"{FORWARDER_URL}/api/forward/deliver/{ENDPOINT_ID}/base64",
    params={"filename": "python-file.txt", "trackId": "TRZ-PY-002"},
    headers={"Content-Type": "text/plain"},
    data=b64_content
)
print("Base64:", response.json())
# {'status': 'delivered', 'endpoint': 'Demo HTTP Endpoint', 'protocol': 'HTTP', 'file': 'python-file.txt'}
```

Install: `pip install requests`

### Java (HttpClient — Java 11+)

```java
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.Path;
import java.util.Base64;

public class ForwarderDemo {
    static final String BASE_URL = "http://localhost:8087";
    static final String ENDPOINT_ID = "a0000000-0000-0000-0000-000000000001";

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // Base64 method (simpler in Java — no multipart needed)
        byte[] fileBytes = "Hello from Java".getBytes();
        String b64 = Base64.getEncoder().encodeToString(fileBytes);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/forward/deliver/" + ENDPOINT_ID
                + "/base64?filename=java-file.txt&trackId=TRZ-JAVA-001"))
            .header("Content-Type", "text/plain")
            .POST(BodyPublishers.ofString(b64))
            .build();

        HttpResponse<String> response = client.send(request,
            HttpResponse.BodyHandlers.ofString());

        System.out.println("Status: " + response.statusCode());
        System.out.println("Body: " + response.body());
        // Status: 202
        // Body: {"status":"delivered","endpoint":"Demo HTTP Endpoint","protocol":"HTTP","file":"java-file.txt"}
    }
}
```

### Node.js (axios)

```javascript
const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

const BASE_URL = 'http://localhost:8087';
const ENDPOINT_ID = 'a0000000-0000-0000-0000-000000000001';

async function demo() {
    // --- Method 1: Multipart upload ---
    const form = new FormData();
    form.append('file', fs.createReadStream('report.csv'));

    const multipartResult = await axios.post(
        `${BASE_URL}/api/forward/deliver/${ENDPOINT_ID}?trackId=TRZ-NODE-001`,
        form,
        { headers: form.getHeaders() }
    );
    console.log('Multipart:', multipartResult.data);
    // { status: 'delivered', endpoint: 'Demo HTTP Endpoint', protocol: 'HTTP', file: 'report.csv' }

    // --- Method 2: Base64 upload ---
    const fileContent = Buffer.from('Hello from Node.js');
    const b64 = fileContent.toString('base64');

    const base64Result = await axios.post(
        `${BASE_URL}/api/forward/deliver/${ENDPOINT_ID}/base64?filename=node-file.txt&trackId=TRZ-NODE-002`,
        b64,
        { headers: { 'Content-Type': 'text/plain' } }
    );
    console.log('Base64:', base64Result.data);
    // { status: 'delivered', endpoint: 'Demo HTTP Endpoint', protocol: 'HTTP', file: 'node-file.txt' }
}

demo().catch(console.error);
```

Install: `npm install axios form-data`

---

## Use Cases

1. **Partner file delivery** — a bank receives trade files via SFTP and needs to forward them to a clearing house over FTPS and a regulator over AS2. One forwarder service handles both.

2. **Webhook-style HTTP delivery** — forward files to a partner's REST API with Bearer token authentication, custom headers, and retry on transient failures.

3. **Event streaming** — forward incoming files to a Kafka topic for real-time processing by downstream consumers (fraud detection, analytics pipelines).

4. **Legacy system integration via FTP** — forward modern uploads to an older system that only accepts FTP, with automatic retries if the legacy server is intermittently available.

5. **B2B EDI exchange via AS2** — send business documents to trading partners using AS2 with MDN (Message Disposition Notification) receipts for non-repudiation, as required by many supply chain and healthcare partners.

6. **Multi-destination fan-out** — a single incoming file triggers multiple forward operations: one copy to SFTP archival, one to HTTP webhook, one to Kafka. Orchestrated by the flow engine calling the forwarder for each destination.

7. **DMZ-routed delivery** — in regulated environments, forward files through a DMZ proxy so that the application server never makes direct outbound connections to external networks. The forwarder dynamically registers and cleans up proxy port mappings.

8. **Secure FTPS delivery with explicit TLS** — deliver sensitive files to partners requiring FTP over TLS (explicit mode), with automatic PBSZ/PROT negotiation handled by the FTPS forwarder.

---

## API Reference

All endpoints are served on port `8087` by default.

### Legacy Destination Endpoints

| Method | Path | Content Type | Description |
|--------|------|-------------|-------------|
| `POST` | `/api/forward/{destinationId}` | `multipart/form-data` | Forward a file to an `ExternalDestination` (SFTP/FTP/Kafka) |
| `POST` | `/api/forward/{destinationId}/base64` | `text/plain` | Forward a Base64-encoded file to an `ExternalDestination` |

**Parameters for `/api/forward/{destinationId}/base64`:**
- `filename` (query, required) — the filename to use at the destination
- Request body: raw Base64-encoded file content

### Delivery Endpoint Endpoints

| Method | Path | Content Type | Description |
|--------|------|-------------|-------------|
| `POST` | `/api/forward/deliver/{endpointId}` | `multipart/form-data` | Deliver a file to a `DeliveryEndpoint` (all 7 protocols) |
| `POST` | `/api/forward/deliver/{endpointId}/base64` | `text/plain` | Deliver a Base64-encoded file to a `DeliveryEndpoint` |

**Parameters for deliver endpoints:**
- `trackId` (query, optional) — platform tracking ID for correlation
- `filename` (query, required for base64) — the filename to use at the destination
- Request body (base64): raw Base64-encoded file content

**Response (all endpoints return HTTP 202 Accepted):**

```json
{
    "status": "delivered",
    "endpoint": "endpoint-name",
    "protocol": "SFTP",
    "file": "filename.txt"
}
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC connection URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ hostname |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `SERVER_PORT` | `8087` | HTTP API port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker addresses (for Kafka forwarding) |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | JWT signing secret (shared across services) |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key for DMZ proxy integration |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier |
| `CLUSTER_HOST` | `localhost` | This service's hostname within the cluster |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label (PROD, STAGING, DEV) |
| `PROXY_ENABLED` | `false` | Enable global outbound proxy |
| `PROXY_TYPE` | `HTTP` | Global proxy type (HTTP, SOCKS5) |
| `PROXY_HOST` | `dmz-proxy` | Global proxy hostname |
| `PROXY_PORT` | `8088` | Global proxy port |
| `PROXY_NO_PROXY_HOSTS` | `localhost,127.0.0.1,postgres,rabbitmq` | Hosts that bypass the proxy |

---

## Retry Behavior Reference

The forwarder uses smart retry with exponential backoff for delivery endpoints. Configuration is per-endpoint in the `delivery_endpoints` table:

| Column | Default | Description |
|--------|---------|-------------|
| `retry_count` | `3` | Maximum number of delivery attempts |
| `retry_delay_ms` | `5000` | Base delay between retries (milliseconds) |

**Backoff formula:** `min(base * 2^(attempt-1) * random(0.75, 1.25), 120000)`

Example with defaults (`retry_count=3`, `retry_delay_ms=5000`):

| Attempt | Base Delay | Exponential | With Jitter (range) | Capped |
|---------|-----------|------------|---------------------|--------|
| 1 | 5000ms | 5000ms | 3750-6250ms | 3750-6250ms |
| 2 | 5000ms | 10000ms | 7500-12500ms | 7500-12500ms |
| 3 | 5000ms | 20000ms | 15000-25000ms | 15000-25000ms |

**Non-retryable errors** (detected by message content, retry is skipped immediately):

- Permission denied / auth failures
- HTTP 401 / 403
- File not found / HTTP 404
- Key expired / certificate errors

---

## Cleanup

### Docker Compose

```bash
docker compose -f docker-compose-forwarder.yml down -v
```

### Individual Docker Containers

```bash
docker stop mft-forwarder-service && docker rm mft-forwarder-service
# Also stop dependencies if you started them separately:
docker stop mft-postgres mft-rabbitmq && docker rm mft-postgres mft-rabbitmq
```

### From Source

Press `Ctrl+C` in the terminal where the service is running.

### Clean Up Test Data

If you want to remove only the demo data from the database without stopping services:

```bash
docker exec mft-fwd-postgres psql -U postgres -d filetransfer -c "
DELETE FROM delivery_endpoints WHERE name = 'Demo HTTP Endpoint';
DELETE FROM external_destinations WHERE name = 'Demo SFTP Target';
"
```

---

## Troubleshooting

### "External destination not found or inactive" (HTTP 404)

The destination or endpoint UUID does not exist in the database, or its `active` column is `false`.

```bash
# Check if the destination exists
docker exec mft-fwd-postgres psql -U postgres -d filetransfer -c "
SELECT id, name, active FROM external_destinations;
SELECT id, name, protocol, active FROM delivery_endpoints;
"
```

### "Delivery failed after N attempt(s)" (HTTP 500)

The forwarder tried all retries and failed. Check the logs for the specific error:

```bash
docker logs mft-fwd-forwarder 2>&1 | grep -i "delivery"
```

Common causes:
- Target server is down or unreachable
- Wrong credentials in the destination/endpoint configuration
- Network connectivity issues between the forwarder container and the target

### SFTP forwarding fails with "Auth fail"

The `encrypted_password` in the `external_destinations` table is expected to be encrypted by the encryption-service. If you inserted a plaintext password for demo purposes, the `CredentialCryptoClient.decrypt()` call may fail.

**Fix for demo:** Ensure the encryption-service is running, or check if there is a passthrough/no-op mode configured.

### HTTP forwarding fails with "Connection refused"

**Linux:** If the target HTTP server is on the host machine, the forwarder container cannot reach `localhost`. Use the Docker network service name (e.g., `target-http`) or `host.docker.internal`.

**macOS/Windows:** `host.docker.internal` should work. If not, check that Docker Desktop is up to date.

### Kafka forwarding fails with "Connection to node -1 could not be established"

The `KAFKA_BOOTSTRAP_SERVERS` environment variable is not pointing to a reachable Kafka broker. Kafka is optional and not included in the demo Docker Compose. To demo Kafka forwarding, add a Kafka broker to your compose file:

```yaml
  kafka:
    image: confluentinc/cp-kafka:7.6.0
    container_name: mft-fwd-kafka
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
    ports:
      - "9092:9092"
```

And set `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` on the forwarder service.

### PostgreSQL: "relation does not exist"

Flyway migrations have not run. Check the forwarder logs:

```bash
docker logs mft-fwd-forwarder 2>&1 | grep -i flyway
```

The forwarder shares migration scripts from the `shared` module. Tables like `external_destinations`, `delivery_endpoints`, and `as2_partnerships` are created automatically by Flyway on first startup.

### Windows: curl multipart upload syntax

PowerShell's `curl` is an alias for `Invoke-WebRequest`, which has different syntax. Use `curl.exe` explicitly or use the PowerShell examples shown in the demos above:

```powershell
# Use curl.exe (not the PowerShell alias)
curl.exe -X POST "http://localhost:8087/api/forward/deliver/..." -F "file=@C:\temp\demo.txt"
```

---

## What's Next

- **[Gateway Service](GATEWAY-SERVICE.md)** — the protocol gateway that routes incoming connections to backend servers; files arriving through the gateway can trigger forwarding
- **[Config Service](CONFIG-SERVICE.md)** — manage delivery endpoints, external destinations, and file flows through an API instead of direct SQL
- **[Onboarding API](ONBOARDING-API.md)** — create transfer accounts and manage the platform centrally
- **[AS2 Service](AS2-SERVICE.md)** — deeper dive into AS2/AS4 B2B messaging with partnership management and MDN receipts
- **[Encryption Service](ENCRYPTION-SERVICE.md)** — encrypt files before forwarding, and understand how `CredentialCryptoClient` protects stored passwords
- **[DMZ Proxy](DMZ-PROXY.md)** — understand the DMZ proxy that the forwarder integrates with for secure outbound delivery

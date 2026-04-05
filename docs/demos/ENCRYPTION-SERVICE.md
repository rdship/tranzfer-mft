# Encryption Service -- Demo & Quick Start Guide

> Encrypt and decrypt files and data using AES-256-GCM or PGP, with a dedicated credential encryption API protected by a master key.

---

## What This Service Does

- **File encryption/decryption** -- Upload a file via multipart form and get back the encrypted (or decrypted) bytes, using AES-256-GCM or PGP.
- **Base64 API** -- Send and receive data as Base64 strings instead of raw bytes, ideal for service-to-service calls where binary transport is inconvenient.
- **Credential encryption** -- Encrypt and decrypt sensitive values (passwords, API keys, tokens) using a platform master key, without needing to create or manage key records.
- **PGP support** -- Encrypts with ASCII-armored PGP public keys and decrypts with PGP private keys, powered by Bouncy Castle.
- **Key-per-account model** -- Each encryption operation references a `keyId` (UUID) from the `encryption_keys` table, so every transfer account can have its own keys.

## What You Need (Prerequisites Checklist)

- [ ] **Operating System:** Linux, macOS, or Windows
- [ ] **Docker** (recommended) OR **Java 21 + Maven** -- [Install guide](PREREQUISITES.md)
- [ ] **PostgreSQL 16** -- [Install guide](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it)
- [ ] **curl** -- pre-installed on Linux/macOS; Windows: `winget install cURL.cURL`
- [ ] **Ports available:** `8086` (Encryption Service), `5432` (PostgreSQL)

## Install & Start

### Method 1: Docker (Any OS -- 30 Seconds)

**Step 1 -- Start PostgreSQL (if you don't already have one running):**

```bash
docker run -d \
  --name mft-postgres \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine
```

Wait for it to be ready:

```bash
# Linux / macOS
docker exec mft-postgres pg_isready -U postgres

# Expected output:
# /var/run/postgresql:5432 - accepting connections
```

On Windows (PowerShell):

```powershell
docker exec mft-postgres pg_isready -U postgres
```

**Step 2 -- Build the Encryption Service image:**

```bash
cd /path/to/file-transfer-platform
mvn -pl shared,encryption-service -am clean package -DskipTests
docker build -t mft-encryption-service ./encryption-service
```

**Step 3 -- Run the Encryption Service:**

```bash
docker run -d \
  --name mft-encryption-service \
  -p 8086:8086 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e ENCRYPTION_MASTER_KEY=a]1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2 \
  -e JWT_SECRET=change_me_in_production_256bit_secret_key!! \
  mft-encryption-service
```

> **Note:** On Linux, replace `host.docker.internal` with `172.17.0.1` (the default Docker bridge gateway) or use `--network host`.

> **ENCRYPTION_MASTER_KEY** must be exactly 64 hex characters (representing 32 bytes / 256 bits). The example above is for demo only -- generate a real one for production with: `openssl rand -hex 32`

### Method 2: Docker Compose

Create a file called `docker-compose-encryption.yml` (or use the snippet below):

```yaml
version: "3.9"
services:
  postgres:
    image: postgres:16-alpine
    container_name: mft-enc-postgres
    environment:
      POSTGRES_DB: filetransfer
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 3s
      retries: 10

  encryption-service:
    build: ./encryption-service
    container_name: mft-encryption-service
    ports:
      - "8086:8086"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      ENCRYPTION_MASTER_KEY: "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
      JWT_SECRET: "change_me_in_production_256bit_secret_key!!"
      SERVER_PORT: "8086"
    depends_on:
      postgres:
        condition: service_healthy
```

Run from the `file-transfer-platform` root:

```bash
# Build shared module first (required dependency)
mvn -pl shared clean install -DskipTests

# Start everything
docker compose -f docker-compose-encryption.yml up --build -d
```

### Method 3: From Source

**Step 1 -- Start PostgreSQL** (via Docker, as shown above, or use a local installation).

**Step 2 -- Build:**

```bash
cd /path/to/file-transfer-platform

# Build the shared module first (encryption-service depends on it)
mvn -pl shared clean install -DskipTests

# Build the encryption service
mvn -pl encryption-service clean package -DskipTests
```

**Step 3 -- Run:**

Linux / macOS:

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
export ENCRYPTION_MASTER_KEY=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2
export JWT_SECRET=change_me_in_production_256bit_secret_key!!

java -jar encryption-service/target/encryption-service-1.0.0-SNAPSHOT.jar
```

Windows (PowerShell):

```powershell
$env:DATABASE_URL = "jdbc:postgresql://localhost:5432/filetransfer"
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "postgres"
$env:ENCRYPTION_MASTER_KEY = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
$env:JWT_SECRET = "change_me_in_production_256bit_secret_key!!"

java -jar encryption-service\target\encryption-service-1.0.0-SNAPSHOT.jar
```

Windows (Command Prompt):

```cmd
set DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer
set DB_USERNAME=postgres
set DB_PASSWORD=postgres
set ENCRYPTION_MASTER_KEY=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2
set JWT_SECRET=change_me_in_production_256bit_secret_key!!

java -jar encryption-service\target\encryption-service-1.0.0-SNAPSHOT.jar
```

## Verify It's Running

```bash
curl -s http://localhost:8086/actuator/health
```

Expected output:

```json
{"status":"UP"}
```

If you see `connection refused`, the service is still starting -- wait a few seconds and try again. If you see a database error, confirm PostgreSQL is running and the `filetransfer` database exists.

---

## API Reference (All Verified Endpoints)

The controller is mounted at `/api/encrypt`. Here is every endpoint:

| Method | Path | Content-Type | Params | Description |
|--------|------|-------------|--------|-------------|
| `POST` | `/api/encrypt/encrypt` | `multipart/form-data` | `keyId` (UUID, query), `file` (form part) | Encrypt an uploaded file; returns encrypted bytes |
| `POST` | `/api/encrypt/decrypt` | `multipart/form-data` | `keyId` (UUID, query), `file` (form part) | Decrypt an uploaded file; returns plain bytes |
| `POST` | `/api/encrypt/encrypt/base64` | `text/plain` or `application/json` | `keyId` (UUID, query), body = Base64 string | Encrypt Base64 payload; returns Base64 |
| `POST` | `/api/encrypt/decrypt/base64` | `text/plain` or `application/json` | `keyId` (UUID, query), body = Base64 string | Decrypt Base64 payload; returns Base64 |
| `POST` | `/api/encrypt/credential/encrypt` | `application/json` | body: `{"value": "..."}` | Encrypt a credential with the master key |
| `POST` | `/api/encrypt/credential/decrypt` | `application/json` | body: `{"encrypted": "..."}` | Decrypt a credential with the master key |

> **Note:** The file-based and base64 endpoints require a `keyId` that references an active row in the `encryption_keys` table. The credential endpoints use the platform master key directly and do not need a `keyId`.

---

## Demo 1: Credential Encryption Round-Trip (Simplest -- No Key Setup Needed)

The credential endpoints use the `ENCRYPTION_MASTER_KEY` directly, so you can test immediately without creating any key records.

**Step 1 -- Encrypt a password:**

```bash
curl -s -X POST http://localhost:8086/api/encrypt/credential/encrypt \
  -H "Content-Type: application/json" \
  -d '{"value": "MyS3cretP@ssword!"}' | python3 -m json.tool
```

On Windows (PowerShell), use:

```powershell
Invoke-RestMethod -Method POST -Uri "http://localhost:8086/api/encrypt/credential/encrypt" `
  -ContentType "application/json" `
  -Body '{"value": "MyS3cretP@ssword!"}' | ConvertTo-Json
```

Expected output:

```json
{
    "encrypted": "dGhpcyBpcyBhIGJhc2U2NCBlbmNyeXB0ZWQgdmFsdWU..."
}
```

The `encrypted` value is a Base64 string containing a 12-byte IV followed by AES-256-GCM ciphertext with a 16-byte authentication tag.

**Step 2 -- Decrypt it back:**

Copy the `encrypted` value from the previous response and paste it in:

```bash
curl -s -X POST http://localhost:8086/api/encrypt/credential/decrypt \
  -H "Content-Type: application/json" \
  -d '{"encrypted": "PASTE_THE_BASE64_STRING_HERE"}' | python3 -m json.tool
```

Expected output:

```json
{
    "value": "MyS3cretP@ssword!"
}
```

The original plaintext is recovered exactly.

**Step 3 -- Verify empty input handling:**

```bash
curl -s -X POST http://localhost:8086/api/encrypt/credential/encrypt \
  -H "Content-Type: application/json" \
  -d '{"value": ""}' | python3 -m json.tool
```

Expected output:

```json
{
    "encrypted": ""
}
```

Empty values pass through without error.

---

## Demo 2: File Encrypt/Decrypt Round-Trip (Real-World Scenario)

This demo encrypts an actual file using an AES-256-GCM key stored in the database, then decrypts it. This requires a key record in the `encryption_keys` table.

### Prerequisites: Create a Key Record

The encryption service reads keys from the `encryption_keys` table (managed via the Config Service on port 8084). If you have the Config Service running, create a key through its API. Otherwise, insert one directly into PostgreSQL:

```bash
# Generate an AES-256 key (Base64-encoded, 32 random bytes)
AES_KEY=$(openssl rand -base64 32)
echo "Generated AES key: $AES_KEY"

# Insert the key into the database
docker exec mft-postgres psql -U postgres -d filetransfer -c "
INSERT INTO encryption_keys (id, key_name, algorithm, encrypted_symmetric_key, active, created_at)
VALUES (
  'a0000000-0000-0000-0000-000000000001',
  'demo-aes-key',
  'AES_256_GCM',
  '$AES_KEY',
  true,
  now()
);
"
```

On Windows (PowerShell):

```powershell
$AES_KEY = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }) -as [byte[]])
Write-Host "Generated AES key: $AES_KEY"

docker exec mft-postgres psql -U postgres -d filetransfer -c @"
INSERT INTO encryption_keys (id, key_name, algorithm, encrypted_symmetric_key, active, created_at)
VALUES (
  'a0000000-0000-0000-0000-000000000001',
  'demo-aes-key',
  'AES_256_GCM',
  '$AES_KEY',
  true,
  now()
);
"@
```

**Step 1 -- Create a test file:**

```bash
echo "This is a confidential financial report. Account: 9876543210. Balance: $1,234,567.89" > /tmp/report.txt
```

Windows:

```powershell
"This is a confidential financial report. Account: 9876543210. Balance: $1,234,567.89" | Out-File -Encoding utf8 $env:TEMP\report.txt
```

**Step 2 -- Encrypt the file:**

```bash
curl -s -X POST "http://localhost:8086/api/encrypt/encrypt?keyId=a0000000-0000-0000-0000-000000000001" \
  -F "file=@/tmp/report.txt" \
  --output /tmp/report.txt.enc

echo "Original size:  $(wc -c < /tmp/report.txt) bytes"
echo "Encrypted size: $(wc -c < /tmp/report.txt.enc) bytes"
```

Expected output:

```
Original size:  85 bytes
Encrypted size: 113 bytes
```

The encrypted file is slightly larger due to the 12-byte IV and 16-byte GCM authentication tag.

**Step 3 -- Verify the encrypted file is not readable:**

```bash
# This will print binary gibberish
cat /tmp/report.txt.enc
echo ""
echo "---"
# Prove it is binary
file /tmp/report.txt.enc
```

Expected output:

```
(binary gibberish)
---
/tmp/report.txt.enc: data
```

**Step 4 -- Decrypt the file:**

```bash
curl -s -X POST "http://localhost:8086/api/encrypt/decrypt?keyId=a0000000-0000-0000-0000-000000000001" \
  -F "file=@/tmp/report.txt.enc" \
  --output /tmp/report-decrypted.txt

cat /tmp/report-decrypted.txt
```

Expected output:

```
This is a confidential financial report. Account: 9876543210. Balance: $1,234,567.89
```

**Step 5 -- Verify integrity (files match):**

Linux / macOS:

```bash
diff /tmp/report.txt /tmp/report-decrypted.txt && echo "Files are identical!"
```

Windows (PowerShell):

```powershell
if ((Get-FileHash $env:TEMP\report.txt).Hash -eq (Get-FileHash $env:TEMP\report-decrypted.txt).Hash) {
    Write-Host "Files are identical!"
}
```

Expected output:

```
Files are identical!
```

### Using the Base64 API (Alternative)

If you prefer not to use multipart uploads, you can encrypt/decrypt via Base64 strings:

```bash
# Encode the file to Base64
PAYLOAD=$(base64 /tmp/report.txt)

# Encrypt
ENCRYPTED=$(curl -s -X POST \
  "http://localhost:8086/api/encrypt/encrypt/base64?keyId=a0000000-0000-0000-0000-000000000001" \
  -H "Content-Type: text/plain" \
  -d "$PAYLOAD")

echo "Encrypted (Base64): ${ENCRYPTED:0:60}..."

# Decrypt
DECRYPTED=$(curl -s -X POST \
  "http://localhost:8086/api/encrypt/decrypt/base64?keyId=a0000000-0000-0000-0000-000000000001" \
  -H "Content-Type: text/plain" \
  -d "$ENCRYPTED")

# Decode and verify
echo "$DECRYPTED" | base64 -d
```

Expected output:

```
Encrypted (Base64): c29tZUJhc2U2NEVuY3J5cHRlZERhdGFIZXJlV2l0aEl2QW5kQ...
This is a confidential financial report. Account: 9876543210. Balance: $1,234,567.89
```

---

## Demo 3: Integration Patterns

### Python

```python
import requests
import base64

BASE_URL = "http://localhost:8086/api/encrypt"

# --- Credential Encryption ---
# Encrypt a database password before storing it in config
resp = requests.post(f"{BASE_URL}/credential/encrypt",
                     json={"value": "db_password_123"})
encrypted = resp.json()["encrypted"]
print(f"Encrypted credential: {encrypted[:40]}...")

# Decrypt it when you need the real password
resp = requests.post(f"{BASE_URL}/credential/decrypt",
                     json={"encrypted": encrypted})
print(f"Decrypted credential: {resp.json()['value']}")

# --- File Encryption (requires a keyId in the database) ---
KEY_ID = "a0000000-0000-0000-0000-000000000001"

# Encrypt a file
with open("/tmp/report.txt", "rb") as f:
    resp = requests.post(f"{BASE_URL}/encrypt",
                         params={"keyId": KEY_ID},
                         files={"file": ("report.txt", f)})
encrypted_bytes = resp.content
print(f"Encrypted {len(encrypted_bytes)} bytes")

# Decrypt it back
resp = requests.post(f"{BASE_URL}/decrypt",
                     params={"keyId": KEY_ID},
                     files={"file": ("report.txt.enc", encrypted_bytes)})
print(f"Decrypted: {resp.text}")

# --- Base64 API ---
plaintext = b"Sensitive data for inter-service transfer"
b64_input = base64.b64encode(plaintext).decode()

resp = requests.post(f"{BASE_URL}/encrypt/base64",
                     params={"keyId": KEY_ID},
                     data=b64_input,
                     headers={"Content-Type": "text/plain"})
encrypted_b64 = resp.text

resp = requests.post(f"{BASE_URL}/decrypt/base64",
                     params={"keyId": KEY_ID},
                     data=encrypted_b64,
                     headers={"Content-Type": "text/plain"})
decrypted = base64.b64decode(resp.text)
print(f"Round-trip result: {decrypted.decode()}")
```

### Java (HttpClient -- Java 21)

```java
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.Path;
import java.util.UUID;

public class EncryptionClient {
    private static final String BASE = "http://localhost:8086/api/encrypt";
    private static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        // Credential encryption (no keyId needed)
        var encReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/credential/encrypt"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"value\":\"MySecret123\"}"))
                .build();
        var encResp = client.send(encReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Encrypted: " + encResp.body());

        // File encryption (requires keyId)
        String keyId = "a0000000-0000-0000-0000-000000000001";
        String boundary = UUID.randomUUID().toString();
        byte[] fileBytes = "Confidential content".getBytes();

        String multipartBody = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"data.txt\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + new String(fileBytes) + "\r\n"
                + "--" + boundary + "--\r\n";

        var fileReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/encrypt?keyId=" + keyId))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(BodyPublishers.ofString(multipartBody))
                .build();
        var fileResp = client.send(fileReq, HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("Encrypted file size: " + fileResp.body().length + " bytes");
    }
}
```

### Node.js (fetch API)

```javascript
const BASE_URL = "http://localhost:8086/api/encrypt";
const KEY_ID = "a0000000-0000-0000-0000-000000000001";

// --- Credential Encryption ---
async function encryptCredential(value) {
    const resp = await fetch(`${BASE_URL}/credential/encrypt`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ value }),
    });
    return (await resp.json()).encrypted;
}

async function decryptCredential(encrypted) {
    const resp = await fetch(`${BASE_URL}/credential/decrypt`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ encrypted }),
    });
    return (await resp.json()).value;
}

// --- Base64 API ---
async function encryptBase64(plaintext) {
    const b64 = Buffer.from(plaintext).toString("base64");
    const resp = await fetch(
        `${BASE_URL}/encrypt/base64?keyId=${KEY_ID}`,
        {
            method: "POST",
            headers: { "Content-Type": "text/plain" },
            body: b64,
        }
    );
    return await resp.text();
}

async function decryptBase64(encryptedB64) {
    const resp = await fetch(
        `${BASE_URL}/decrypt/base64?keyId=${KEY_ID}`,
        {
            method: "POST",
            headers: { "Content-Type": "text/plain" },
            body: encryptedB64,
        }
    );
    const b64 = await resp.text();
    return Buffer.from(b64, "base64").toString("utf-8");
}

// Usage
(async () => {
    // Credential round-trip
    const enc = await encryptCredential("api_key_abc123");
    console.log("Encrypted:", enc.substring(0, 40) + "...");
    const dec = await decryptCredential(enc);
    console.log("Decrypted:", dec);

    // Data round-trip via Base64
    const cipherB64 = await encryptBase64("Wire transfer: $50,000 to IBAN DE89...");
    console.log("Cipher (Base64):", cipherB64.substring(0, 40) + "...");
    const plain = await decryptBase64(cipherB64);
    console.log("Plaintext:", plain);
})();
```

---

## Use Cases

1. **Encrypt files before SFTP delivery** -- A folder mapping triggers encryption of outbound files using a partner's PGP public key before the SFTP service sends them.
2. **Decrypt inbound PGP files** -- When a partner uploads a PGP-encrypted file, the flow engine calls the decrypt endpoint to unwrap it before processing.
3. **Credential vault** -- Store database passwords, API keys, and SFTP passphrases encrypted at rest using the credential API. Decrypt them only at runtime.
4. **Service-to-service data protection** -- Use the Base64 API to encrypt sensitive payloads (account numbers, SSNs) in transit between microservices via RabbitMQ or HTTP.
5. **Compliance with encryption mandates** -- Financial regulations (PCI-DSS, SOX) require encryption of data at rest and in transit. This service provides both file-level and field-level encryption.
6. **Key-per-partner isolation** -- Each transfer partner gets its own encryption key (PGP or AES), so compromising one key does not affect others.
7. **Encrypt audit log payloads** -- Sensitive fields in audit records can be encrypted before being stored in the analytics database.

---

## Environment Variables

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `ENCRYPTION_MASTER_KEY` | `000...000` (64 zeros) | **Yes (change in prod)** | 64-character hex string (32 bytes). Used for credential encryption. Generate with `openssl rand -hex 32`. |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | Yes | JDBC connection string for PostgreSQL |
| `DB_USERNAME` | `postgres` | Yes | PostgreSQL username |
| `DB_PASSWORD` | `postgres` | Yes | PostgreSQL password |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | Yes (change in prod) | JWT signing secret for inter-service auth |
| `SERVER_PORT` | `8086` | No | HTTP port (set in `application.yml`) |
| `CLUSTER_ID` | `default-cluster` | No | Cluster identifier for multi-node deployments |
| `CLUSTER_HOST` | `localhost` | No | Hostname of this instance within the cluster |
| `PLATFORM_ENVIRONMENT` | `PROD` | No | Environment label (PROD, STAGING, DEV) |
| `CONTROL_API_KEY` | `internal_control_secret` | No | API key for internal control plane calls |

---

## Cleanup

**Stop and remove containers:**

```bash
docker stop mft-encryption-service mft-postgres
docker rm mft-encryption-service mft-postgres
```

**If you used Docker Compose:**

```bash
docker compose -f docker-compose-encryption.yml down -v
```

**Remove test files:**

Linux / macOS:

```bash
rm -f /tmp/report.txt /tmp/report.txt.enc /tmp/report-decrypted.txt
```

Windows (PowerShell):

```powershell
Remove-Item $env:TEMP\report.txt, $env:TEMP\report.txt.enc, $env:TEMP\report-decrypted.txt -ErrorAction SilentlyContinue
```

**Remove Docker images (optional):**

```bash
docker rmi mft-encryption-service postgres:16-alpine
```

---

## Troubleshooting

### Linux

| Problem | Solution |
|---------|----------|
| `connection refused` on port 5432 | Confirm PostgreSQL is running: `docker ps \| grep postgres`. Check if another process uses port 5432: `sudo ss -tlnp \| grep 5432`. |
| `host.docker.internal` not resolving | On Linux, use `172.17.0.1` or `--network host` instead of `host.docker.internal`. |
| Flyway migration error | The shared module's `V1__baseline.sql` creates the `encryption_keys` table. Ensure you built and included the `shared` module. |
| `ENCRYPTION_MASTER_KEY` too short | Must be exactly 64 hex characters. Check with: `echo -n "$ENCRYPTION_MASTER_KEY" \| wc -c` (should output `64`). |

### macOS

| Problem | Solution |
|---------|----------|
| Port 5432 already in use | Stop the local Postgres: `brew services stop postgresql@16`, or change the Docker port mapping to `-p 5433:5432` and update `DATABASE_URL`. |
| Docker Desktop not running | Open Docker Desktop from Applications or run `open -a Docker`. Wait for the whale icon to appear in the menu bar. |
| Java 21 not found | Install with Homebrew: `brew install openjdk@21` and add to PATH: `export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"`. |

### Windows

| Problem | Solution |
|---------|----------|
| `curl` not found | Install via `winget install cURL.cURL`, or use PowerShell's `Invoke-RestMethod` instead. |
| Port conflict with local PostgreSQL | Stop the Windows service: `net stop postgresql-x64-16`, or change the Docker port mapping. |
| Line ending issues in test files | Use `Out-File -Encoding utf8` in PowerShell, not `>` redirect (which uses UTF-16). |
| Docker Desktop WSL2 error | Ensure WSL2 is enabled: `wsl --install`. Restart Docker Desktop after WSL2 installation. |
| `host.docker.internal` not working | This should work on Docker Desktop for Windows. If not, find the WSL2 gateway IP with `ipconfig` and use that. |

---

## What's Next

- **[Keystore Manager](KEYSTORE-MANAGER.md)** -- Generate and manage the SSH, TLS, AES, and HMAC keys that this service uses for encryption.
- **[Config Service](CONFIG-SERVICE.md)** -- Create encryption key records (`encryption_keys` table) and associate them with transfer accounts and folder mappings.
- **[SFTP Service](SFTP-SERVICE.md)** -- See how encrypted files flow through the SFTP transfer pipeline.
- **[Gateway Service](GATEWAY-SERVICE.md)** -- The DMZ gateway that routes external connections to internal protocol services.
- **[Prerequisites](PREREQUISITES.md)** -- Full installation guide for Docker, Java 21, Maven, and PostgreSQL.

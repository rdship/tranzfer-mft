# Keystore Manager -- Demo & Quick Start Guide

> Centralized key and certificate lifecycle management -- generate, store, retrieve, rotate, and monitor SSH keys, TLS certificates, AES symmetric keys, and HMAC secrets from a single REST API.

---

## What This Service Does

- **Generate cryptographic keys on demand** -- SSH host keys (EC-P256), SSH user keys (RSA up to 4096-bit), AES-256 symmetric keys, TLS certificates (RSA-2048, self-signed), and HMAC-SHA256 secrets.
- **Centralized key storage** -- All keys are stored in the `managed_keys` PostgreSQL table with metadata (alias, type, owner service, partner account, fingerprint, expiry).
- **Key rotation** -- Rotate any AES, SSH host, or HMAC key with one API call. The old key is deactivated and linked to its replacement.
- **Public key distribution** -- A dedicated `/public` endpoint exposes only the public portion of a keypair, safe to share with external partners.
- **Expiry monitoring** -- A daily scheduled job (8:00 AM) checks for keys expiring within 30 days and logs warnings.

## What You Need (Prerequisites Checklist)

- [ ] **Operating System:** Linux, macOS, or Windows
- [ ] **Docker** (recommended) OR **Java 21 + Maven** -- [Install guide](PREREQUISITES.md)
- [ ] **PostgreSQL 16** -- [Install guide](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it)
- [ ] **curl** -- pre-installed on Linux/macOS; Windows: `winget install cURL.cURL`
- [ ] **Ports available:** `8093` (Keystore Manager), `5432` (PostgreSQL)

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
docker exec mft-postgres pg_isready -U postgres

# Expected output:
# /var/run/postgresql:5432 - accepting connections
```

**Step 2 -- Build the Keystore Manager image:**

```bash
cd /path/to/file-transfer-platform
mvn -pl shared,keystore-manager -am clean package -DskipTests
docker build -t mft-keystore-manager ./keystore-manager
```

**Step 3 -- Run the Keystore Manager:**

```bash
docker run -d \
  --name mft-keystore-manager \
  -p 8093:8093 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e KEYSTORE_MASTER_PASSWORD=MyStr0ng!Mast3rP@ssword \
  mft-keystore-manager
```

> **Note:** On Linux, replace `host.docker.internal` with `172.17.0.1` (the default Docker bridge gateway) or use `--network host`.

> **KEYSTORE_MASTER_PASSWORD** protects the keystore. Use a strong, unique password in production.

### Method 2: Docker Compose

Create a file called `docker-compose-keystore.yml` (or use the snippet below):

```yaml
version: "3.9"
services:
  postgres:
    image: postgres:16-alpine
    container_name: mft-ks-postgres
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

  keystore-manager:
    build: ./keystore-manager
    container_name: mft-keystore-manager
    ports:
      - "8093:8093"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      KEYSTORE_MASTER_PASSWORD: "MyStr0ng!Mast3rP@ssword"
      SERVER_PORT: "8093"
    depends_on:
      postgres:
        condition: service_healthy
```

Run from the `file-transfer-platform` root:

```bash
# Build shared module first (required dependency)
mvn -pl shared clean install -DskipTests

# Start everything
docker compose -f docker-compose-keystore.yml up --build -d
```

### Method 3: From Source

**Step 1 -- Start PostgreSQL** (via Docker, as shown above, or use a local installation).

**Step 2 -- Build:**

```bash
cd /path/to/file-transfer-platform

# Build the shared module first (keystore-manager depends on it)
mvn -pl shared clean install -DskipTests

# Build the keystore manager
mvn -pl keystore-manager clean package -DskipTests
```

**Step 3 -- Run:**

Linux / macOS:

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
export KEYSTORE_MASTER_PASSWORD=MyStr0ng!Mast3rP@ssword

java -jar keystore-manager/target/keystore-manager-1.0.0-SNAPSHOT.jar
```

Windows (PowerShell):

```powershell
$env:DATABASE_URL = "jdbc:postgresql://localhost:5432/filetransfer"
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "postgres"
$env:KEYSTORE_MASTER_PASSWORD = "MyStr0ng!Mast3rP@ssword"

java -jar keystore-manager\target\keystore-manager-1.0.0-SNAPSHOT.jar
```

Windows (Command Prompt):

```cmd
set DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer
set DB_USERNAME=postgres
set DB_PASSWORD=postgres
set KEYSTORE_MASTER_PASSWORD=MyStr0ng!Mast3rP@ssword

java -jar keystore-manager\target\keystore-manager-1.0.0-SNAPSHOT.jar
```

## Verify It's Running

**Health endpoint (service-specific):**

```bash
curl -s http://localhost:8093/api/v1/keys/health | python3 -m json.tool
```

Expected output:

```json
{
    "status": "UP",
    "service": "keystore-manager",
    "totalKeys": 0,
    "keysByType": {}
}
```

**Spring Actuator health:**

```bash
curl -s http://localhost:8093/actuator/health
```

Expected output:

```json
{"status":"UP"}
```

**List all supported key types:**

```bash
curl -s http://localhost:8093/api/v1/keys/types | python3 -m json.tool
```

Expected output:

```json
[
    {"type": "SSH_HOST_KEY", "description": "SFTP/SSH server host key"},
    {"type": "SSH_USER_KEY", "description": "SFTP user authentication key"},
    {"type": "PGP_PUBLIC",   "description": "PGP public key for encryption"},
    {"type": "PGP_PRIVATE",  "description": "PGP private key for decryption"},
    {"type": "AES_SYMMETRIC","description": "AES-256 symmetric encryption key"},
    {"type": "TLS_CERTIFICATE","description": "X.509 TLS certificate + private key"},
    {"type": "TLS_KEYSTORE", "description": "Java keystore (JKS/PKCS12)"},
    {"type": "HMAC_SECRET",  "description": "HMAC-SHA256 signing secret"},
    {"type": "API_KEY",      "description": "Inter-service API key"}
]
```

---

## API Reference (All Verified Endpoints)

The controller is mounted at `/api/v1/keys`. Here is every endpoint:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/keys` | List all active keys. Optional filters: `?type=`, `?service=`, `?partner=` |
| `GET` | `/api/v1/keys/{alias}` | Get a key by its unique alias |
| `GET` | `/api/v1/keys/{alias}/public` | Get only the public key material (safe for external sharing) |
| `POST` | `/api/v1/keys/generate/ssh-host` | Generate an EC-P256 SSH host key |
| `POST` | `/api/v1/keys/generate/ssh-user` | Generate an RSA SSH user key (default 4096-bit) |
| `POST` | `/api/v1/keys/generate/aes` | Generate an AES-256 symmetric key |
| `POST` | `/api/v1/keys/generate/tls` | Generate an RSA-2048 self-signed TLS certificate |
| `POST` | `/api/v1/keys/generate/hmac` | Generate an HMAC-SHA256 signing secret |
| `POST` | `/api/v1/keys/import` | Import an existing key or certificate |
| `POST` | `/api/v1/keys/{alias}/rotate` | Rotate a key (deactivates old, generates new) |
| `GET` | `/api/v1/keys/health` | Health check with key inventory summary |
| `GET` | `/api/v1/keys/types` | List all supported key types with descriptions |

### Request Body Formats

**Generate SSH Host Key:**
```json
{"alias": "sftp-host-key", "ownerService": "sftp-service"}
```

**Generate SSH User Key:**
```json
{"alias": "partner-acme-ssh", "partnerAccount": "acme-corp", "keySize": "4096"}
```

**Generate AES Key:**
```json
{"alias": "flow-encryption-key", "ownerService": "encryption-service"}
```

**Generate TLS Certificate:**
```json
{"alias": "api-gateway-tls", "cn": "gateway.example.com", "validDays": "365"}
```

**Generate HMAC Key:**
```json
{"alias": "webhook-signing-key", "ownerService": "forwarder-service"}
```

**Import Key:**
```json
{
    "alias": "partner-pgp-pub",
    "keyType": "PGP_PUBLIC",
    "keyMaterial": "-----BEGIN PGP PUBLIC KEY BLOCK-----\n...\n-----END PGP PUBLIC KEY BLOCK-----",
    "description": "ACME Corp PGP public key",
    "ownerService": "encryption-service",
    "partnerAccount": "acme-corp"
}
```

**Rotate Key:**
```json
{"newAlias": "sftp-host-key-2024-v2"}
```

> If `newAlias` is omitted, the system generates one: `{oldAlias}-{timestamp}`.

---

## Demo 1: Generate Every Key Type (Simplest)

Start with an empty keystore and generate one of each key type.

**Step 1 -- Generate an SSH host key (EC-P256):**

```bash
curl -s -X POST http://localhost:8093/api/v1/keys/generate/ssh-host \
  -H "Content-Type: application/json" \
  -d '{"alias": "demo-sftp-host", "ownerService": "sftp-service"}' | python3 -m json.tool
```

Expected output:

```json
{
    "id": "b1c2d3e4-...",
    "alias": "demo-sftp-host",
    "keyType": "SSH_HOST_KEY",
    "algorithm": "EC-P256",
    "keyMaterial": "-----BEGIN EC PRIVATE KEY-----\nMIGH...\n-----END EC PRIVATE KEY-----",
    "publicKeyMaterial": "-----BEGIN PUBLIC KEY-----\nMFkw...\n-----END PUBLIC KEY-----",
    "fingerprint": "a3f2b1c4d5e6f7...",
    "ownerService": "sftp-service",
    "description": "SSH host key for sftp-service",
    "keySizeBits": 256,
    "active": true,
    "createdAt": "2026-04-05T..."
}
```

**Step 2 -- Generate an SSH user key (RSA-4096):**

```bash
curl -s -X POST http://localhost:8093/api/v1/keys/generate/ssh-user \
  -H "Content-Type: application/json" \
  -d '{"alias": "partner-acme-ssh", "partnerAccount": "acme-corp", "keySize": "4096"}' | python3 -m json.tool
```

Expected output:

```json
{
    "id": "c2d3e4f5-...",
    "alias": "partner-acme-ssh",
    "keyType": "SSH_USER_KEY",
    "algorithm": "RSA-4096",
    "keyMaterial": "-----BEGIN RSA PRIVATE KEY-----\nMIIJ...\n-----END RSA PRIVATE KEY-----",
    "publicKeyMaterial": "-----BEGIN PUBLIC KEY-----\nMIIC...\n-----END PUBLIC KEY-----",
    "fingerprint": "e4f5a6b7c8d9e0...",
    "partnerAccount": "acme-corp",
    "description": "SSH user key for acme-corp",
    "keySizeBits": 4096,
    "active": true,
    "createdAt": "2026-04-05T..."
}
```

**Step 3 -- Generate an AES-256 symmetric key:**

```bash
curl -s -X POST http://localhost:8093/api/v1/keys/generate/aes \
  -H "Content-Type: application/json" \
  -d '{"alias": "flow-aes-key", "ownerService": "encryption-service"}' | python3 -m json.tool
```

Expected output:

```json
{
    "id": "d3e4f5a6-...",
    "alias": "flow-aes-key",
    "keyType": "AES_SYMMETRIC",
    "algorithm": "AES-256",
    "keyMaterial": "4a8b2f1c9d3e7a6b...",
    "fingerprint": "f5a6b7c8d9e0f1...",
    "ownerService": "encryption-service",
    "description": "AES-256 symmetric key",
    "keySizeBits": 256,
    "active": true,
    "createdAt": "2026-04-05T..."
}
```

> **Note:** The AES key material is stored as a hex string (64 hex characters = 32 bytes = 256 bits).

**Step 4 -- Generate a TLS certificate (RSA-2048, valid 365 days):**

```bash
curl -s -X POST http://localhost:8093/api/v1/keys/generate/tls \
  -H "Content-Type: application/json" \
  -d '{"alias": "gateway-tls-cert", "cn": "gateway.tranzfer.local", "validDays": "365"}' | python3 -m json.tool
```

Expected output:

```json
{
    "id": "e4f5a6b7-...",
    "alias": "gateway-tls-cert",
    "keyType": "TLS_CERTIFICATE",
    "algorithm": "RSA-2048",
    "keyMaterial": "-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----",
    "publicKeyMaterial": "-----BEGIN PUBLIC KEY-----\nMIIB...\n-----END PUBLIC KEY-----",
    "fingerprint": "a6b7c8d9e0f1a2...",
    "subjectDn": "CN=gateway.tranzfer.local",
    "issuerDn": "CN=TranzFer CA",
    "validFrom": "2026-04-05T...",
    "expiresAt": "2027-04-05T...",
    "keySizeBits": 2048,
    "description": "TLS certificate for gateway.tranzfer.local",
    "active": true,
    "createdAt": "2026-04-05T..."
}
```

**Step 5 -- Generate an HMAC-SHA256 secret:**

```bash
curl -s -X POST http://localhost:8093/api/v1/keys/generate/hmac \
  -H "Content-Type: application/json" \
  -d '{"alias": "webhook-hmac", "ownerService": "forwarder-service"}' | python3 -m json.tool
```

Expected output:

```json
{
    "id": "f5a6b7c8-...",
    "alias": "webhook-hmac",
    "keyType": "HMAC_SECRET",
    "algorithm": "HmacSHA256",
    "keyMaterial": "9a8b7c6d5e4f3a2b...",
    "fingerprint": "b7c8d9e0f1a2b3...",
    "ownerService": "forwarder-service",
    "description": "HMAC-SHA256 secret",
    "keySizeBits": 256,
    "active": true,
    "createdAt": "2026-04-05T..."
}
```

**Step 6 -- Check the health endpoint to see the inventory:**

```bash
curl -s http://localhost:8093/api/v1/keys/health | python3 -m json.tool
```

Expected output:

```json
{
    "status": "UP",
    "service": "keystore-manager",
    "totalKeys": 5,
    "keysByType": {
        "SSH_HOST_KEY": 1,
        "SSH_USER_KEY": 1,
        "AES_SYMMETRIC": 1,
        "TLS_CERTIFICATE": 1,
        "HMAC_SECRET": 1
    }
}
```

---

## Demo 2: Key Rotation and Lifecycle (Real-World Scenario)

This demo walks through a production-like key rotation workflow: generate a key, use it, rotate it, and verify the old key is deactivated.

**Step 1 -- Generate an AES key for a production flow:**

```bash
curl -s -X POST http://localhost:8093/api/v1/keys/generate/aes \
  -H "Content-Type: application/json" \
  -d '{"alias": "prod-payment-encryption", "ownerService": "encryption-service"}' | python3 -m json.tool
```

Note the alias: `prod-payment-encryption`.

**Step 2 -- Retrieve the key by alias:**

```bash
curl -s http://localhost:8093/api/v1/keys/prod-payment-encryption | python3 -m json.tool
```

Expected: The full key record with `"active": true`.

**Step 3 -- Share only the public key with a partner (for asymmetric keys):**

First, let's create an SSH key to demonstrate this:

```bash
curl -s -X POST http://localhost:8093/api/v1/keys/generate/ssh-user \
  -H "Content-Type: application/json" \
  -d '{"alias": "partner-globex-ssh", "partnerAccount": "globex-corp", "keySize": "2048"}'

# Now get only the public part (safe to send to the partner)
curl -s http://localhost:8093/api/v1/keys/partner-globex-ssh/public
```

Expected output (PEM-encoded public key only):

```
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
-----END PUBLIC KEY-----
```

This endpoint returns `text/plain` -- you can pipe it directly to a file:

```bash
curl -s http://localhost:8093/api/v1/keys/partner-globex-ssh/public > /tmp/globex-public.pem
```

**Step 4 -- Rotate the AES key:**

```bash
curl -s -X POST http://localhost:8093/api/v1/keys/prod-payment-encryption/rotate \
  -H "Content-Type: application/json" \
  -d '{"newAlias": "prod-payment-encryption-v2"}' | python3 -m json.tool
```

Expected output (the NEW key):

```json
{
    "id": "new-uuid-...",
    "alias": "prod-payment-encryption-v2",
    "keyType": "AES_SYMMETRIC",
    "algorithm": "AES-256",
    "keyMaterial": "7f8e9d0c1b2a3f4e...",
    "fingerprint": "new-fingerprint...",
    "ownerService": "encryption-service",
    "description": "AES-256 symmetric key",
    "keySizeBits": 256,
    "active": true,
    "createdAt": "2026-04-05T..."
}
```

**Step 5 -- Verify the old key is now inactive:**

```bash
# Try to retrieve the old key -- should return 404 (only active keys are returned)
curl -s -o /dev/null -w "HTTP %{http_code}" http://localhost:8093/api/v1/keys/prod-payment-encryption
```

Expected output:

```
HTTP 404
```

The old key still exists in the database (for audit purposes) but is marked `active=false` with `rotatedToAlias=prod-payment-encryption-v2`.

**Step 6 -- Verify the new key is active:**

```bash
curl -s http://localhost:8093/api/v1/keys/prod-payment-encryption-v2 | python3 -m json.tool
```

Expected: Full key record with `"active": true`.

**Step 7 -- Filter keys by type or service:**

```bash
# All AES keys
curl -s "http://localhost:8093/api/v1/keys?type=AES_SYMMETRIC" | python3 -m json.tool

# All keys owned by encryption-service
curl -s "http://localhost:8093/api/v1/keys?service=encryption-service" | python3 -m json.tool

# All keys for a specific partner
curl -s "http://localhost:8093/api/v1/keys?partner=globex-corp" | python3 -m json.tool
```

**Step 8 -- Import an external key:**

```bash
curl -s -X POST http://localhost:8093/api/v1/keys/import \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "partner-acme-pgp-pub",
    "keyType": "PGP_PUBLIC",
    "keyMaterial": "-----BEGIN PGP PUBLIC KEY BLOCK-----\nVersion: GnuPG v2\n\nmQENBF...(partner provided key)...\n-----END PGP PUBLIC KEY BLOCK-----",
    "description": "ACME Corp PGP public key for outbound encryption",
    "ownerService": "encryption-service",
    "partnerAccount": "acme-corp"
  }' | python3 -m json.tool
```

Expected output:

```json
{
    "id": "imported-uuid-...",
    "alias": "partner-acme-pgp-pub",
    "keyType": "PGP_PUBLIC",
    "keyMaterial": "-----BEGIN PGP PUBLIC KEY BLOCK-----\n...",
    "fingerprint": "sha256-of-material...",
    "ownerService": "encryption-service",
    "partnerAccount": "acme-corp",
    "description": "ACME Corp PGP public key for outbound encryption",
    "active": true,
    "createdAt": "2026-04-05T..."
}
```

---

## Demo 3: Integration Patterns

### Python

```python
import requests
import json

BASE_URL = "http://localhost:8093/api/v1/keys"

# --- Generate keys for a new partner onboarding ---

def generate_ssh_key(alias, partner):
    """Generate an SSH user key for a partner."""
    resp = requests.post(f"{BASE_URL}/generate/ssh-user", json={
        "alias": alias,
        "partnerAccount": partner,
        "keySize": "4096"
    })
    resp.raise_for_status()
    key = resp.json()
    print(f"Generated SSH key: {key['alias']} ({key['algorithm']})")
    return key

def generate_aes_key(alias, service):
    """Generate an AES-256 key for a service."""
    resp = requests.post(f"{BASE_URL}/generate/aes", json={
        "alias": alias,
        "ownerService": service
    })
    resp.raise_for_status()
    return resp.json()

def get_public_key(alias):
    """Get only the public portion (safe to share)."""
    resp = requests.get(f"{BASE_URL}/{alias}/public")
    resp.raise_for_status()
    return resp.text

def rotate_key(alias, new_alias):
    """Rotate a key, deactivating the old one."""
    resp = requests.post(f"{BASE_URL}/{alias}/rotate", json={
        "newAlias": new_alias
    })
    resp.raise_for_status()
    return resp.json()

def get_health():
    """Get keystore inventory."""
    return requests.get(f"{BASE_URL}/health").json()

# --- Example: Partner onboarding workflow ---
print("=== Partner Onboarding: Acme Corp ===")

# 1. Generate SSH key for SFTP access
ssh_key = generate_ssh_key("py-acme-sftp", "acme-corp")

# 2. Generate AES key for file encryption
aes_key = generate_aes_key("py-acme-aes", "encryption-service")

# 3. Get the public key to send to the partner
pub_key = get_public_key("py-acme-sftp")
print(f"Public key to send to partner:\n{pub_key[:80]}...")

# 4. Check inventory
health = get_health()
print(f"Total keys in keystore: {health['totalKeys']}")
print(f"Keys by type: {json.dumps(health['keysByType'], indent=2)}")

# 5. Rotate the AES key (e.g., quarterly rotation)
new_key = rotate_key("py-acme-aes", "py-acme-aes-q2-2026")
print(f"Rotated to: {new_key['alias']}")
```

### Java (HttpClient -- Java 21)

```java
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;

public class KeystoreClient {
    private static final String BASE = "http://localhost:8093/api/v1/keys";
    private static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        // Generate an SSH host key
        String sshBody = """
                {"alias": "java-sftp-host", "ownerService": "sftp-service"}
                """;
        var sshResp = post("/generate/ssh-host", sshBody);
        System.out.println("SSH Host Key: " + sshResp.statusCode());
        System.out.println(sshResp.body().substring(0, 200) + "...");

        // Generate a TLS certificate
        String tlsBody = """
                {"alias": "java-api-tls", "cn": "api.tranzfer.local", "validDays": "90"}
                """;
        var tlsResp = post("/generate/tls", tlsBody);
        System.out.println("TLS Cert: " + tlsResp.statusCode());

        // Retrieve only the public key
        var pubResp = get("/java-sftp-host/public");
        System.out.println("Public key:\n" + pubResp.body());

        // Generate an HMAC key and then rotate it
        post("/generate/hmac", """
                {"alias": "java-hmac-key", "ownerService": "webhook-service"}
                """);
        var rotated = post("/java-hmac-key/rotate", """
                {"newAlias": "java-hmac-key-v2"}
                """);
        System.out.println("Rotated HMAC key: " + rotated.body().substring(0, 100) + "...");

        // Check health
        var health = get("/health");
        System.out.println("Health: " + health.body());
    }

    static HttpResponse<String> post(String path, String body) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
```

### Node.js (fetch API)

```javascript
const BASE_URL = "http://localhost:8093/api/v1/keys";

async function generateKey(type, body) {
    const resp = await fetch(`${BASE_URL}/generate/${type}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
    if (!resp.ok) throw new Error(`${resp.status}: ${await resp.text()}`);
    return resp.json();
}

async function getKey(alias) {
    const resp = await fetch(`${BASE_URL}/${alias}`);
    if (!resp.ok) throw new Error(`${resp.status}: ${await resp.text()}`);
    return resp.json();
}

async function getPublicKey(alias) {
    const resp = await fetch(`${BASE_URL}/${alias}/public`);
    if (!resp.ok) throw new Error(`${resp.status}: ${await resp.text()}`);
    return resp.text();
}

async function rotateKey(alias, newAlias) {
    const resp = await fetch(`${BASE_URL}/${alias}/rotate`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ newAlias }),
    });
    if (!resp.ok) throw new Error(`${resp.status}: ${await resp.text()}`);
    return resp.json();
}

async function listKeys(filter = {}) {
    const params = new URLSearchParams(filter);
    const resp = await fetch(`${BASE_URL}?${params}`);
    return resp.json();
}

async function getHealth() {
    const resp = await fetch(`${BASE_URL}/health`);
    return resp.json();
}

// --- Example: Automated key provisioning for a new environment ---
(async () => {
    console.log("=== Provisioning keys for staging environment ===\n");

    // 1. SSH host key for SFTP server
    const sshHost = await generateKey("ssh-host", {
        alias: "node-staging-sftp-host",
        ownerService: "sftp-service",
    });
    console.log(`SSH Host: ${sshHost.alias} (${sshHost.algorithm})`);

    // 2. SSH user key for a partner
    const sshUser = await generateKey("ssh-user", {
        alias: "node-staging-partner-ssh",
        partnerAccount: "staging-partner",
        keySize: "2048",
    });
    console.log(`SSH User: ${sshUser.alias} (${sshUser.algorithm})`);

    // 3. AES key for encryption
    const aes = await generateKey("aes", {
        alias: "node-staging-aes",
        ownerService: "encryption-service",
    });
    console.log(`AES:      ${aes.alias} (${aes.algorithm})`);

    // 4. TLS cert for the API gateway
    const tls = await generateKey("tls", {
        alias: "node-staging-tls",
        cn: "staging.tranzfer.local",
        validDays: "90",
    });
    console.log(`TLS:      ${tls.alias} (${tls.algorithm}), expires ${tls.expiresAt}`);

    // 5. HMAC secret for webhook signing
    const hmac = await generateKey("hmac", {
        alias: "node-staging-hmac",
        ownerService: "forwarder-service",
    });
    console.log(`HMAC:     ${hmac.alias} (${hmac.algorithm})`);

    // 6. Get the public key to share with partners
    const pubKey = await getPublicKey("node-staging-partner-ssh");
    console.log(`\nPublic key for partner:\n${pubKey.substring(0, 80)}...\n`);

    // 7. List all keys by service
    const encKeys = await listKeys({ service: "encryption-service" });
    console.log(`Encryption service keys: ${encKeys.length}`);

    // 8. Rotate the HMAC key
    const newHmac = await rotateKey("node-staging-hmac", "node-staging-hmac-v2");
    console.log(`\nRotated HMAC: ${newHmac.alias}`);

    // 9. Final health check
    const health = await getHealth();
    console.log(`\nKeystore health: ${health.totalKeys} total keys`);
    console.log("By type:", JSON.stringify(health.keysByType, null, 2));
})();
```

---

## Use Cases

1. **SFTP server host key provisioning** -- When deploying a new SFTP instance, generate an EC-P256 host key and configure it via the SFTP service's startup.
2. **Partner SSH key exchange** -- Generate an RSA-4096 SSH user key for a new partner, share the public key via the `/public` endpoint, and keep the private key secure in the keystore.
3. **TLS certificate management** -- Generate self-signed TLS certificates for internal services (API gateway, DMZ proxy) with configurable validity periods and CN values.
4. **Encryption key lifecycle** -- Generate AES-256 keys for the Encryption Service, rotate them quarterly, and track the rotation chain via `rotatedToAlias`.
5. **Webhook signature secrets** -- Generate HMAC-SHA256 secrets for signing outbound webhook payloads, ensuring message integrity for external integrations.
6. **PGP key import** -- When a trading partner sends their PGP public key, import it into the keystore with the `/import` endpoint, associating it with their partner account.
7. **Compliance key inventory** -- Use the health endpoint and type/service filters to generate an audit report of all active cryptographic material in the platform.
8. **Automated expiry alerting** -- The built-in daily scheduler checks for keys expiring within 30 days and logs warnings, which can be routed to monitoring systems.

---

## Environment Variables

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `KEYSTORE_MASTER_PASSWORD` | `change-this-master-password` | **Yes (change in prod)** | Master password that protects the keystore. Use a strong, unique passphrase. |
| `KEYSTORE_STORAGE_PATH` | `/data/keystores` | No | Filesystem path for keystore file storage (JKS/PKCS12 files). |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | Yes | JDBC connection string for PostgreSQL |
| `DB_USERNAME` | `postgres` | Yes | PostgreSQL username |
| `DB_PASSWORD` | `postgres` | Yes | PostgreSQL password |
| `SERVER_PORT` | `8093` | No | HTTP port (set in `application.yml`) |
| `CLUSTER_ID` | `default-cluster` | No | Cluster identifier for multi-node deployments |
| `CLUSTER_HOST` | `keystore-manager` | No | Hostname of this instance within the cluster |
| `PLATFORM_ENVIRONMENT` | `PROD` | No | Environment label (PROD, STAGING, DEV) |
| `PROXY_ENABLED` | `false` | No | Enable HTTP proxy for outbound connections |
| `PROXY_TYPE` | `HTTP` | No | Proxy type (HTTP or SOCKS5) |
| `PROXY_HOST` | `dmz-proxy` | No | Proxy hostname |
| `PROXY_PORT` | `8088` | No | Proxy port |

---

## Cleanup

**Stop and remove containers:**

```bash
docker stop mft-keystore-manager mft-postgres
docker rm mft-keystore-manager mft-postgres
```

**If you used Docker Compose:**

```bash
docker compose -f docker-compose-keystore.yml down -v
```

**Remove temp files created during demos:**

Linux / macOS:

```bash
rm -f /tmp/globex-public.pem
```

Windows (PowerShell):

```powershell
Remove-Item $env:TEMP\globex-public.pem -ErrorAction SilentlyContinue
```

**Remove Docker images (optional):**

```bash
docker rmi mft-keystore-manager postgres:16-alpine
```

**Clean up demo keys from the database (if keeping the database):**

```bash
docker exec mft-postgres psql -U postgres -d filetransfer -c "
  DELETE FROM managed_keys WHERE alias LIKE 'demo-%'
     OR alias LIKE 'partner-%'
     OR alias LIKE 'prod-payment-%'
     OR alias LIKE 'py-%'
     OR alias LIKE 'java-%'
     OR alias LIKE 'node-%'
     OR alias LIKE 'flow-%'
     OR alias LIKE 'gateway-%'
     OR alias LIKE 'webhook-%';
"
```

---

## Troubleshooting

### Linux

| Problem | Solution |
|---------|----------|
| `connection refused` on port 5432 | Confirm PostgreSQL is running: `docker ps \| grep postgres`. Check if another process uses 5432: `sudo ss -tlnp \| grep 5432`. |
| `host.docker.internal` not resolving | On Linux, use `172.17.0.1` or `--network host` instead of `host.docker.internal`. |
| Flyway migration error on startup | The `managed_keys` table is created by `V1__baseline.sql` in the shared module. Ensure you built `shared` first with `mvn -pl shared clean install`. |
| `Key alias already exists` error | Each alias must be unique. Either use a different alias or delete the existing key from the database. |
| Rotation fails with `Auto-rotation not supported` | Only `AES_SYMMETRIC`, `SSH_HOST_KEY`, and `HMAC_SECRET` key types support automatic rotation. For other types, generate a new key manually and deactivate the old one. |

### macOS

| Problem | Solution |
|---------|----------|
| Port 5432 already in use | Stop the local Postgres: `brew services stop postgresql@16`, or change the Docker port mapping to `-p 5433:5432` and update `DATABASE_URL`. |
| Docker Desktop not running | Open Docker Desktop from Applications or run `open -a Docker`. Wait for the whale icon in the menu bar. |
| Java 21 not found | Install with Homebrew: `brew install openjdk@21` and add to PATH: `export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"`. |
| `python3 -m json.tool` not found | macOS ships with Python3. If removed, install with `brew install python3` or use `jq` instead: `brew install jq` then replace `python3 -m json.tool` with `jq .` |

### Windows

| Problem | Solution |
|---------|----------|
| `curl` not found | Install via `winget install cURL.cURL`, or use PowerShell's `Invoke-RestMethod` instead. |
| Port conflict with local PostgreSQL | Stop the Windows service: `net stop postgresql-x64-16`, or change the Docker port. |
| `python3` not found for JSON formatting | Install with `winget install Python.Python.3` or use PowerShell: `curl ... \| ConvertFrom-Json \| ConvertTo-Json -Depth 10`. |
| Docker Desktop WSL2 error | Ensure WSL2 is enabled: `wsl --install`. Restart Docker Desktop after WSL2 installation. |
| Long PEM keys break in Command Prompt | Use PowerShell instead of `cmd.exe`. PowerShell handles multi-line strings and JSON better. |

---

## What's Next

- **[Encryption Service](ENCRYPTION-SERVICE.md)** -- Uses keys from this keystore to encrypt and decrypt files and credentials.
- **[SFTP Service](SFTP-SERVICE.md)** -- Consumes SSH host keys and user keys generated here for SFTP server authentication.
- **[Gateway Service](GATEWAY-SERVICE.md)** -- Uses TLS certificates from this keystore for the DMZ proxy.
- **[Config Service](CONFIG-SERVICE.md)** -- Manages the `encryption_keys` table that maps keys to transfer accounts and folder mappings.
- **[Prerequisites](PREREQUISITES.md)** -- Full installation guide for Docker, Java 21, Maven, and PostgreSQL.

# Encryption Service — Standalone Product Guide

> **File encryption as a service.** Encrypt and decrypt files using AES-256-GCM or PGP via a simple REST API. Support for file uploads, Base64 data, and credential encryption.

**Port:** 8086 | **Dependencies:** PostgreSQL | **Auth:** None (key-based)

---

## Why Use This

- **Dual algorithm support** — AES-256-GCM (symmetric) and PGP (asymmetric) via single API
- **File, Base64, and credential modes** — Three interfaces for different use cases
- **Key management** — Keys stored in database, referenced by UUID
- **Master key encryption** — Separate credential encryption using platform master key
- **512 MB uploads** — Handle large file encryption

---

## Quick Start

### Prerequisites
```bash
# Start PostgreSQL
docker run -d --name postgres -p 5432:5432 \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_PASSWORD=postgres \
  postgres:16
```

### Run
```bash
java -jar encryption-service/target/encryption-service-*.jar \
  --ENCRYPTION_MASTER_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

### Docker Compose
```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: filetransfer
      POSTGRES_PASSWORD: postgres
    ports: ["5432:5432"]

  encryption-service:
    build: ./encryption-service
    ports: ["8086:8086"]
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      ENCRYPTION_MASTER_KEY: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    depends_on: [postgres]
```

---

## API Reference

### 1. Encrypt File

**POST** `/api/encrypt?keyId={uuid}`

Upload a file and get back encrypted binary.

```bash
# First, create an encryption key via keystore-manager or database
# Then encrypt a file using that key's UUID
curl -X POST "http://localhost:8086/api/encrypt?keyId=550e8400-e29b-41d4-a716-446655440000" \
  -F "file=@/path/to/sensitive-data.csv" \
  --output encrypted-data.csv.enc
```

**Response:** Binary encrypted file with header `Content-Disposition: attachment; filename="sensitive-data.csv.enc"`

### 2. Decrypt File

**POST** `/api/decrypt?keyId={uuid}`

```bash
curl -X POST "http://localhost:8086/api/decrypt?keyId=550e8400-e29b-41d4-a716-446655440000" \
  -F "file=@encrypted-data.csv.enc" \
  --output decrypted-data.csv
```

**Response:** Binary decrypted file with header `Content-Disposition: attachment; filename="sensitive-data.csv"`

### 3. Encrypt Base64 Data

**POST** `/api/encrypt/base64?keyId={uuid}`

For API-to-API communication where binary isn't practical.

```bash
# Encode your data as Base64 first
DATA=$(echo -n "SSN: 123-45-6789" | base64)

curl -X POST "http://localhost:8086/api/encrypt/base64?keyId=550e8400-e29b-41d4-a716-446655440000" \
  -H "Content-Type: application/json" \
  -d "\"$DATA\""
```

**Response:** Base64-encoded encrypted data (string)

### 4. Decrypt Base64 Data

**POST** `/api/decrypt/base64?keyId={uuid}`

```bash
curl -X POST "http://localhost:8086/api/decrypt/base64?keyId=550e8400-e29b-41d4-a716-446655440000" \
  -H "Content-Type: application/json" \
  -d '"ENCRYPTED_BASE64_STRING"'
```

**Response:** Base64-encoded plaintext (string)

### 5. Encrypt Credential (Master Key)

**POST** `/api/encrypt/credential`

Encrypt sensitive credentials using the platform master key. No keyId needed.

```bash
curl -X POST http://localhost:8086/api/encrypt/credential \
  -H "Content-Type: application/json" \
  -d '{"value": "my-database-password-123"}'
```

**Response:**
```json
{
  "encrypted": "dGhpcyBpcyBhbiBlbmNyeXB0ZWQgdmFsdWU="
}
```

### 6. Decrypt Credential (Master Key)

**POST** `/api/decrypt/credential`

```bash
curl -X POST http://localhost:8086/api/decrypt/credential \
  -H "Content-Type: application/json" \
  -d '{"encrypted": "dGhpcyBpcyBhbiBlbmNyeXB0ZWQgdmFsdWU="}'
```

**Response:**
```json
{
  "value": "my-database-password-123"
}
```

---

## Encryption Algorithms

### AES-256-GCM
- **Key size:** 256-bit
- **IV size:** 12 bytes (96 bits), randomly generated per operation
- **Auth tag:** 128-bit (GCM provides authenticated encryption)
- **Output format:** `[IV (12 bytes)] + [Ciphertext] + [Auth Tag (16 bytes)]`
- **Key format:** Base64-encoded

### PGP (Bouncy Castle)
- **Encryption:** AES-256 with PGP envelope
- **Compression:** ZIP
- **Integrity:** MDC (Modification Detection Code) enabled
- **Key format:** ASCII-armored PGP public/private keys
- **Output:** ASCII-armored PGP message

---

## Integration Examples

### Python
```python
import requests
import base64

KEY_ID = "550e8400-e29b-41d4-a716-446655440000"
BASE_URL = "http://localhost:8086"

# Encrypt a file
with open("report.pdf", "rb") as f:
    resp = requests.post(
        f"{BASE_URL}/api/encrypt?keyId={KEY_ID}",
        files={"file": ("report.pdf", f)}
    )
    with open("report.pdf.enc", "wb") as out:
        out.write(resp.content)

# Encrypt a credential
resp = requests.post(f"{BASE_URL}/api/encrypt/credential",
    json={"value": "secret-api-key-12345"})
encrypted = resp.json()["encrypted"]
```

### Java
```java
RestTemplate rest = new RestTemplate();

// Encrypt file
MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
body.add("file", new FileSystemResource("report.pdf"));

byte[] encrypted = rest.postForObject(
    "http://localhost:8086/api/encrypt?keyId=" + keyId,
    new HttpEntity<>(body), byte[].class
);

// Encrypt credential
Map<String, String> credReq = Map.of("value", "secret-password");
Map<String, String> credResp = rest.postForObject(
    "http://localhost:8086/api/encrypt/credential",
    credReq, Map.class
);
String encryptedCred = credResp.get("encrypted");
```

### Node.js
```javascript
const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

const KEY_ID = '550e8400-e29b-41d4-a716-446655440000';

// Encrypt file
const form = new FormData();
form.append('file', fs.createReadStream('report.pdf'));
const { data } = await axios.post(
  `http://localhost:8086/api/encrypt?keyId=${KEY_ID}`,
  form,
  { headers: form.getHeaders(), responseType: 'arraybuffer' }
);
fs.writeFileSync('report.pdf.enc', data);

// Encrypt credential
const { data: cred } = await axios.post(
  'http://localhost:8086/api/encrypt/credential',
  { value: 'my-secret' }
);
console.log('Encrypted:', cred.encrypted);
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `ENCRYPTION_MASTER_KEY` | (insecure default) | 64-char hex string (256-bit AES key) |
| `server.port` | `8086` | HTTP port |
| `spring.servlet.multipart.max-file-size` | `512MB` | Max upload |

---

## All Endpoints Summary

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/encrypt` | keyId (query) | Encrypt file (binary) |
| POST | `/api/decrypt` | keyId (query) | Decrypt file (binary) |
| POST | `/api/encrypt/base64` | keyId (query) | Encrypt Base64 data |
| POST | `/api/decrypt/base64` | keyId (query) | Decrypt Base64 data |
| POST | `/api/encrypt/credential` | None (master key) | Encrypt credential |
| POST | `/api/decrypt/credential` | None (master key) | Decrypt credential |

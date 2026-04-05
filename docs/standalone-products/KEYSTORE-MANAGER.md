# Keystore Manager — Standalone Product Guide

> **Key and certificate lifecycle management.** Generate, import, rotate, and track SSH, TLS, PGP, AES, and HMAC keys via REST API. Fully independent — no shared module dependency.

**Port:** 8093 | **Dependencies:** PostgreSQL | **Auth:** None

---

## Why Use This

- **9 key types** — SSH host, SSH user, PGP public/private, AES-256, TLS certificate, TLS keystore, HMAC, API key
- **Key generation** — Generate SSH (EC P-256, RSA), AES-256, TLS (self-signed X.509), HMAC-SHA256
- **Key rotation** — One-click rotation with automatic archival of old keys
- **Import support** — Import PEM, Base64, or armored keys from external systems
- **Expiry monitoring** — Daily scheduled check for expiring certificates
- **Fully independent** — Does NOT depend on the shared module

---

## Quick Start

```bash
docker compose up -d postgres keystore-manager
curl http://localhost:8093/api/v1/keys/health
```

```json
{
  "status": "UP",
  "service": "keystore-manager",
  "totalKeys": 0,
  "keysByType": {}
}
```

---

## API Reference

### 1. Generate SSH Host Key (EC P-256)

**POST** `/api/v1/keys/generate/ssh-host`

```bash
curl -X POST http://localhost:8093/api/v1/keys/generate/ssh-host \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "sftp-prod-host-key",
    "ownerService": "sftp-service"
  }'
```

**Response:**
```json
{
  "id": "a1b2c3d4-...",
  "alias": "sftp-prod-host-key",
  "keyType": "SSH_HOST_KEY",
  "algorithm": "EC-P256",
  "fingerprint": "SHA256:abc123...",
  "ownerService": "sftp-service",
  "active": true,
  "keySizeBits": 256,
  "createdAt": "2026-04-05T14:00:00Z"
}
```

### 2. Generate SSH User Key (RSA)

**POST** `/api/v1/keys/generate/ssh-user`

```bash
curl -X POST http://localhost:8093/api/v1/keys/generate/ssh-user \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "partner-acme-ssh",
    "partnerAccount": "acme_corp",
    "keySize": 4096
  }'
```

### 3. Generate AES-256 Symmetric Key

**POST** `/api/v1/keys/generate/aes`

```bash
curl -X POST http://localhost:8093/api/v1/keys/generate/aes \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "file-encryption-key-01",
    "ownerService": "encryption-service"
  }'
```

### 4. Generate TLS Certificate (Self-Signed)

**POST** `/api/v1/keys/generate/tls`

```bash
curl -X POST http://localhost:8093/api/v1/keys/generate/tls \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "api-gateway-tls",
    "cn": "mft.example.com",
    "validDays": 365
  }'
```

**Response:**
```json
{
  "id": "e5f6g7h8-...",
  "alias": "api-gateway-tls",
  "keyType": "TLS_CERTIFICATE",
  "algorithm": "RSA-2048",
  "fingerprint": "SHA256:xyz789...",
  "subjectDn": "CN=mft.example.com",
  "issuerDn": "CN=mft.example.com",
  "validFrom": "2026-04-05T14:00:00Z",
  "expiresAt": "2027-04-05T14:00:00Z",
  "active": true,
  "createdAt": "2026-04-05T14:00:00Z"
}
```

### 5. Generate HMAC Secret

**POST** `/api/v1/keys/generate/hmac`

```bash
curl -X POST http://localhost:8093/api/v1/keys/generate/hmac \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "webhook-signing-secret",
    "ownerService": "config-service"
  }'
```

### 6. Import External Key

**POST** `/api/v1/keys/import`

Import a PGP public key, PEM certificate, or any key material.

```bash
curl -X POST http://localhost:8093/api/v1/keys/import \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "partner-pgp-public",
    "keyType": "PGP_PUBLIC",
    "keyMaterial": "-----BEGIN PGP PUBLIC KEY BLOCK-----\nmQENBF...\n-----END PGP PUBLIC KEY BLOCK-----",
    "description": "Acme Corp PGP public key for file encryption",
    "partnerAccount": "acme_corp"
  }'
```

### 7. Rotate Key

**POST** `/api/v1/keys/{alias}/rotate`

Generates a new key, marks the old one inactive, links them.

```bash
curl -X POST http://localhost:8093/api/v1/keys/sftp-prod-host-key/rotate \
  -H "Content-Type: application/json" \
  -d '{"newAlias": "sftp-prod-host-key-2026-04"}'
```

**Response:** New `ManagedKey` with the old key marked `active: false` and `rotatedToAlias` pointing to the new key.

Supports rotation for: `AES_SYMMETRIC`, `SSH_HOST_KEY`, `HMAC_SECRET`

### 8. List Keys

**GET** `/api/v1/keys`

```bash
# List all keys
curl http://localhost:8093/api/v1/keys

# Filter by type
curl "http://localhost:8093/api/v1/keys?type=SSH_HOST_KEY"

# Filter by service
curl "http://localhost:8093/api/v1/keys?service=sftp-service"

# Filter by partner
curl "http://localhost:8093/api/v1/keys?partner=acme_corp"
```

### 9. Get Key by Alias

**GET** `/api/v1/keys/{alias}`

```bash
curl http://localhost:8093/api/v1/keys/sftp-prod-host-key
```

### 10. Get Public Key (PEM)

**GET** `/api/v1/keys/{alias}/public`

Returns just the public key in PEM format (text/plain).

```bash
curl http://localhost:8093/api/v1/keys/partner-acme-ssh/public
```

**Response:**
```
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
-----END PUBLIC KEY-----
```

### 11. List Key Types

**GET** `/api/v1/keys/types`

```bash
curl http://localhost:8093/api/v1/keys/types
```

**Response:**
```json
[
  {"type": "SSH_HOST_KEY", "description": "SFTP/SSH server host key"},
  {"type": "SSH_USER_KEY", "description": "SFTP user authentication key"},
  {"type": "PGP_PUBLIC", "description": "PGP public key for encryption"},
  {"type": "PGP_PRIVATE", "description": "PGP private key for decryption"},
  {"type": "AES_SYMMETRIC", "description": "AES-256 symmetric encryption key"},
  {"type": "TLS_CERTIFICATE", "description": "X.509 TLS certificate + private key"},
  {"type": "TLS_KEYSTORE", "description": "Java keystore (JKS/PKCS12)"},
  {"type": "HMAC_SECRET", "description": "HMAC-SHA256 signing secret"},
  {"type": "API_KEY", "description": "Inter-service API key"}
]
```

---

## Integration Examples

### Python
```python
import requests

BASE = "http://localhost:8093/api/v1/keys"

# Generate an AES key for your application
key = requests.post(f"{BASE}/generate/aes", json={
    "alias": "my-app-encryption-key",
    "ownerService": "my-app"
}).json()

print(f"Key ID: {key['id']}, Alias: {key['alias']}")

# Later: rotate the key
new_key = requests.post(f"{BASE}/my-app-encryption-key/rotate", json={
    "newAlias": "my-app-encryption-key-v2"
}).json()

# Import a partner's PGP key
requests.post(f"{BASE}/import", json={
    "alias": "vendor-pgp",
    "keyType": "PGP_PUBLIC",
    "keyMaterial": open("vendor-public.asc").read(),
    "partnerAccount": "vendor_inc"
})
```

### Java
```java
RestTemplate rest = new RestTemplate();

// Generate TLS certificate
Map<String, Object> tlsRequest = Map.of(
    "alias", "my-service-tls",
    "cn", "my-service.internal",
    "validDays", 365
);
Map<String, Object> cert = rest.postForObject(
    "http://localhost:8093/api/v1/keys/generate/tls",
    tlsRequest, Map.class
);

// Get public key as PEM
String publicKeyPem = rest.getForObject(
    "http://localhost:8093/api/v1/keys/my-service-tls/public",
    String.class
);
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `keystore.storage-path` | `/data/keystores` | Key storage directory |
| `keystore.master-password` | (insecure default) | Master password for keystores |
| `spring.servlet.multipart.max-file-size` | `10MB` | Max upload (for key import) |
| `server.port` | `8093` | HTTP port |

---

## All Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/keys` | List keys (filter by type/service/partner) |
| GET | `/api/v1/keys/{alias}` | Get key by alias |
| GET | `/api/v1/keys/{alias}/public` | Get public key (PEM) |
| POST | `/api/v1/keys/generate/ssh-host` | Generate SSH host key (EC P-256) |
| POST | `/api/v1/keys/generate/ssh-user` | Generate SSH user key (RSA) |
| POST | `/api/v1/keys/generate/aes` | Generate AES-256 key |
| POST | `/api/v1/keys/generate/tls` | Generate TLS certificate (X.509) |
| POST | `/api/v1/keys/generate/hmac` | Generate HMAC-SHA256 secret |
| POST | `/api/v1/keys/import` | Import external key |
| POST | `/api/v1/keys/{alias}/rotate` | Rotate key |
| GET | `/api/v1/keys/types` | List supported key types |
| GET | `/api/v1/keys/health` | Health check |

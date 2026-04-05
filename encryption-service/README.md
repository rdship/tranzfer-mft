# Encryption Service

> AES-256-GCM and PGP file encryption/decryption with credential encryption support.

**Port:** 8086 | **Database:** PostgreSQL | **Required:** Optional

---

## Overview

The encryption service provides cryptographic operations for the platform:

- **AES-256-GCM encryption** — Symmetric encryption with authenticated encryption
- **PGP encryption** — Asymmetric encryption using Bouncy Castle
- **Credential encryption** — Encrypt/decrypt sensitive values with master key
- **File encryption** — Upload a file, get it back encrypted (or decrypted)
- **Base64 API** — Encrypt/decrypt Base64-encoded payloads

---

## Quick Start

```bash
docker compose up -d postgres encryption-service

curl http://localhost:8086/actuator/health
```

---

## API Endpoints

### File Encryption/Decryption

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/encrypt` | Encrypt uploaded file |
| POST | `/api/decrypt` | Decrypt uploaded file |

**Encrypt a file:**
```bash
curl -X POST "http://localhost:8086/api/encrypt?keyId=uuid-of-key" \
  -F "file=@sensitive-data.csv"
# Returns: encrypted bytes with .enc extension
```

**Decrypt a file:**
```bash
curl -X POST "http://localhost:8086/api/decrypt?keyId=uuid-of-key" \
  -F "file=@sensitive-data.csv.enc"
# Returns: original decrypted bytes
```

### Base64 Encryption/Decryption

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/encrypt/base64` | Encrypt Base64 payload |
| POST | `/api/decrypt/base64` | Decrypt Base64 payload |

```bash
curl -X POST "http://localhost:8086/api/encrypt/base64?keyId=uuid-of-key" \
  -H "Content-Type: text/plain" \
  -d "SGVsbG8gV29ybGQ="
# Returns: Base64-encoded encrypted data
```

### Credential Encryption (Master Key)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/encrypt/credential` | Encrypt a credential value |
| POST | `/api/decrypt/credential` | Decrypt a credential value |

```bash
# Encrypt
curl -X POST http://localhost:8086/api/encrypt/credential \
  -H "Content-Type: application/json" \
  -d '{"value": "my-secret-password"}'
# Returns: {"encrypted": "base64_encrypted_string"}

# Decrypt
curl -X POST http://localhost:8086/api/decrypt/credential \
  -H "Content-Type: application/json" \
  -d '{"encrypted": "base64_encrypted_string"}'
# Returns: {"value": "my-secret-password"}
```

---

## Encryption Details

### AES-256-GCM (Symmetric)
- Algorithm: AES with 256-bit key
- Mode: GCM (Galois/Counter Mode) — authenticated encryption
- IV: 12 bytes, randomly generated per encryption
- Authentication tag: 128 bits
- Output format: `IV[12 bytes] || CipherText || AuthTag[16 bytes]`

### PGP (Asymmetric)
- Library: Bouncy Castle
- Encryption: AES-256 with integrity packet
- Compression: ZIP
- Supports ASCII-armored public/private keys

### Algorithm Selection
- If the encryption key has a PGP public key configured → PGP encryption
- Otherwise → AES-256-GCM symmetric encryption

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8086` | API port |
| `ENCRYPTION_MASTER_KEY` | (insecure default) | Master key for credential encryption (**must change**) |
| `ENCRYPTION_MAX_FILE_SIZE` | `536870912` | Max file size for encryption (512 MB) |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `512MB` | Max upload size |

---

## Dependencies

- **PostgreSQL** — Required. Encryption key lookup.
- **shared** module — Entities, repositories.

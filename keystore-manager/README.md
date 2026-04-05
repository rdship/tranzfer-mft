# Keystore Manager

> Cryptographic key lifecycle management — generate, import, rotate, and expire SSH keys, TLS certificates, PGP keys, and AES secrets.

**Port:** 8093 | **Database:** PostgreSQL | **Required:** Optional

---

## Overview

The keystore manager provides centralized key and certificate management:

- **Key generation** — SSH host/user keys, AES-256 secrets, TLS certificates, HMAC secrets
- **Key import** — Import existing keys from external systems
- **Key rotation** — Rotate to new version with automatic archival
- **Expiry monitoring** — Daily check for certificates expiring within 30 days
- **Public key export** — Safe sharing of public key material
- **Fingerprinting** — SHA-256 fingerprint for all keys

---

## Quick Start

```bash
docker compose up -d postgres keystore-manager

# Health check
curl http://localhost:8093/api/v1/keys/health

# Generate an SSH host key
curl -X POST http://localhost:8093/api/v1/keys/generate/ssh-host \
  -H "Content-Type: application/json" \
  -d '{"alias": "sftp-host-key", "ownerService": "sftp-service"}'
```

---

## API Endpoints

### Key Retrieval

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/keys` | List all active keys (optional: `?type=`, `?service=`, `?partner=`) |
| GET | `/api/v1/keys/{alias}` | Get key by alias |
| GET | `/api/v1/keys/{alias}/public` | Get public key only (PEM-encoded, safe to share) |

**List keys:**
```bash
curl http://localhost:8093/api/v1/keys?type=SSH_HOST_KEY
```

**Get public key:**
```bash
curl http://localhost:8093/api/v1/keys/sftp-host-key/public
# Returns PEM-encoded public key
```

### Key Generation

| Method | Endpoint | Algorithm | Description |
|--------|----------|-----------|-------------|
| POST | `/api/v1/keys/generate/ssh-host` | EC P-256 | Generate SSH host key |
| POST | `/api/v1/keys/generate/ssh-user` | RSA (default 4096-bit) | Generate SSH user key |
| POST | `/api/v1/keys/generate/aes` | AES-256 | Generate symmetric key |
| POST | `/api/v1/keys/generate/tls` | RSA-2048 self-signed | Generate TLS certificate |
| POST | `/api/v1/keys/generate/hmac` | HMAC-SHA256 (256-bit) | Generate HMAC signing key |

**Generate SSH host key:**
```bash
curl -X POST http://localhost:8093/api/v1/keys/generate/ssh-host \
  -H "Content-Type: application/json" \
  -d '{"alias": "sftp-host-key", "ownerService": "sftp-service"}'
```

**Generate TLS certificate:**
```bash
curl -X POST http://localhost:8093/api/v1/keys/generate/tls \
  -H "Content-Type: application/json" \
  -d '{"alias": "ftp-tls-cert", "cn": "ftp.company.com", "validDays": 365}'
```

**Generate SSH user key:**
```bash
curl -X POST http://localhost:8093/api/v1/keys/generate/ssh-user \
  -H "Content-Type: application/json" \
  -d '{"alias": "partner-acme-key", "partnerAccount": "partner-acme", "keySize": 4096}'
```

### Key Import

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/keys/import` | Import external key |

```bash
curl -X POST http://localhost:8093/api/v1/keys/import \
  -H "Content-Type: application/json" \
  -d '{
    "alias": "partner-pgp-key",
    "keyType": "PGP_PUBLIC",
    "keyMaterial": "-----BEGIN PGP PUBLIC KEY BLOCK-----\n...",
    "description": "Partner ACME PGP public key",
    "partnerAccount": "partner-acme"
  }'
```

### Key Rotation

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/keys/{alias}/rotate` | Rotate key to new version |

```bash
curl -X POST http://localhost:8093/api/v1/keys/sftp-host-key/rotate \
  -H "Content-Type: application/json" \
  -d '{"newAlias": "sftp-host-key-v2"}'
```

Old key is marked inactive and points to the new key via `rotatedToAlias`.

### Utility

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/keys/health` | Service health (UP, total keys, keys by type) |
| GET | `/api/v1/keys/types` | Supported key types with descriptions |

---

## Supported Key Types

| Type | Description |
|------|-------------|
| `SSH_HOST_KEY` | SFTP/SSH server host key |
| `SSH_USER_KEY` | SFTP user authentication key |
| `PGP_PUBLIC` | PGP public key for encryption |
| `PGP_PRIVATE` | PGP private key for decryption |
| `AES_SYMMETRIC` | AES-256 symmetric encryption key |
| `TLS_CERTIFICATE` | X.509 TLS certificate + private key |
| `TLS_KEYSTORE` | Java keystore (JKS/PKCS12) |
| `HMAC_SECRET` | HMAC-SHA256 signing secret |
| `API_KEY` | Inter-service API key |

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8093` | API port |
| `KEYSTORE_MASTER_PASSWORD` | (insecure default) | Master password for keystore (**must change**) |
| `KEYSTORE_STORAGE_PATH` | `/data/keystores` | Keystore file storage path |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |

---

## Scheduled Tasks

| Task | Schedule | Purpose |
|------|----------|---------|
| Expiry check | Daily at 8:00 AM | Alerts for certificates expiring within 30 days |

---

## Dependencies

- **PostgreSQL** — Required. Key metadata and material storage.
- **shared** module — Entities, repositories.

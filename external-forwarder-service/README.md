# External Forwarder Service

> Multi-protocol file forwarding to external partners via SFTP, FTP, FTPS, HTTP, Kafka, AS2, and AS4.

**Port:** 8087 | **Database:** PostgreSQL | **Required:** Optional

---

## Overview

The external forwarder service delivers files to external destinations:

- **SFTP forwarding** — Apache MINA SSHD client, password auth, auto-mkdir
- **FTP forwarding** — Apache Commons Net, binary mode, passive mode
- **FTPS forwarding** — FTP over TLS
- **HTTP/HTTPS forwarding** — POST/PUT with auth (Basic, Bearer, API Key, OAuth2)
- **Kafka forwarding** — Publish to Kafka topics
- **AS2 forwarding** — RFC 4130 B2B protocol
- **AS4 forwarding** — ebMS3/SOAP B2B protocol
- **DMZ proxy integration** — Hot-adds temporary port mappings for delivery traffic
- **Smart retry** — Exponential backoff with jitter, retryable error classification

---

## Quick Start

```bash
docker compose up -d postgres external-forwarder-service

curl http://localhost:8087/actuator/health
```

---

## API Endpoints

### Legacy Forwarding (by Destination ID)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/forward/{destinationId}` | Forward file (multipart) |
| POST | `/api/forward/{destinationId}/base64` | Forward Base64-encoded payload |

**Forward a file:**
```bash
curl -X POST http://localhost:8087/api/forward/uuid-of-destination \
  -F "file=@report.csv" \
  -F "trackId=TRZA3X5T3LUY"
```

### Modern Forwarding (by Delivery Endpoint ID)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/forward/deliver/{endpointId}` | Forward file (multipart) |
| POST | `/api/forward/deliver/{endpointId}/base64` | Forward Base64 payload |

**Forward to delivery endpoint:**
```bash
curl -X POST http://localhost:8087/api/forward/deliver/uuid-of-endpoint \
  -F "file=@report.csv" \
  -F "trackId=TRZA3X5T3LUY"
```

---

## Protocol Details

### SFTP Forwarding
- Apache MINA SSHD client
- Password authentication
- Auto-creates remote directories if they don't exist
- Configurable timeout

### FTP Forwarding
- Apache Commons Net FTPClient
- Binary transfer mode
- Passive mode by default
- Auto-creates remote directories

### FTPS Forwarding
- FTP over TLS
- Same as FTP with TLS layer

### HTTP/HTTPS Forwarding
- Methods: POST or PUT
- **Auth types:**
  - BASIC — Username/password
  - BEARER_TOKEN — Bearer token header
  - API_KEY — Custom header with key
  - OAUTH2 — Token endpoint support
- Custom headers and timeouts
- Proxy support (HTTP, SOCKS5)

### Kafka Forwarding
- Key: filename
- Value: file bytes
- Configurable bootstrap servers and topic

### AS2/AS4 Forwarding
- RFC 4130 (AS2) and ebMS3 (AS4) protocols
- Partnership resolution via config-service
- MDN receipt handling

---

## DMZ Proxy Integration

When the external forwarder needs to reach an external server through the DMZ:
1. Hot-adds a temporary port mapping on the DMZ proxy (ports 40000-49999)
2. Routes delivery traffic through the DMZ proxy
3. Removes the mapping after delivery completes

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8087` | API port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key |

---

## Dependencies

- **PostgreSQL** — Required. External destinations and delivery endpoint configs.
- **config-service** — AS2/AS4 partnership lookup.
- **dmz-proxy** — Optional. For routing through DMZ.
- **shared** module — Entities, repositories.

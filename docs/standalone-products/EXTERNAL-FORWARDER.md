# External Forwarder — Standalone Product Guide

> **Multi-protocol file forwarding.** Forward files to external systems via SFTP, FTP, FTPS, HTTP/HTTPS, AS2, AS4, or Kafka with smart retry and DMZ proxy integration.

**Port:** 8087 | **Dependencies:** PostgreSQL | **Auth:** None

---

## Why Use This

- **7 protocols** — SFTP, FTP, FTPS, HTTP/HTTPS, AS2, AS4, Kafka
- **Smart retry** — Exponential backoff with failure classification
- **DMZ integration** — Dynamic port mapping through security proxy
- **Multiple auth types** — Basic, Bearer, API key, OAuth2 for HTTP
- **File + Base64** — Upload binary or send Base64-encoded content

---

## Quick Start

```bash
docker compose up -d postgres external-forwarder-service
```

---

## API Reference

### 1. Forward File to External Destination

**POST** `/api/forward/{destinationId}`

Forward a file to a pre-configured external destination (SFTP server, HTTP endpoint, etc.)

```bash
curl -X POST http://localhost:8087/api/forward/550e8400-e29b-41d4-a716-446655440000 \
  -F "file=@/path/to/invoice.csv"
```

**Response:**
```json
{
  "status": "forwarded",
  "destination": "partner-sftp-server",
  "file": "invoice.csv"
}
```

### 2. Forward Base64 Content

**POST** `/api/forward/{destinationId}/base64`

```bash
curl -X POST "http://localhost:8087/api/forward/550e8400.../base64?filename=report.csv" \
  -H "Content-Type: application/json" \
  -d '"aGVsbG8gd29ybGQ="'
```

### 3. Deliver to Endpoint

**POST** `/api/forward/deliver/{endpointId}`

Forward to a delivery endpoint (configured with protocol, host, credentials).

```bash
curl -X POST "http://localhost:8087/api/forward/deliver/660e9500...?trackId=TRZ-2026-001" \
  -F "file=@/path/to/batch.csv"
```

**Response:**
```json
{
  "status": "delivered",
  "endpoint": "acme-sftp-inbox",
  "protocol": "SFTP",
  "file": "batch.csv"
}
```

### 4. Deliver Base64 to Endpoint

**POST** `/api/forward/deliver/{endpointId}/base64`

```bash
curl -X POST "http://localhost:8087/api/forward/deliver/660e9500.../base64?filename=data.xml&trackId=TRZ-2026-002" \
  -H "Content-Type: application/json" \
  -d '"PD94bWwgdmVyc2lvbj0iMS4wIj8+..."'
```

---

## Supported Protocols

| Protocol | Default Port | Auth | Features |
|----------|-------------|------|----------|
| **SFTP** | 22 | Username + password | Binary upload, directory creation |
| **FTP** | 21 | Username + password | Passive mode, binary type |
| **FTPS** | 990 | Username + password + TLS | Explicit TLS, protected data channel |
| **HTTP/HTTPS** | 80/443 | Basic, Bearer, API key, OAuth2 | POST/PUT, custom headers |
| **AS2** | 80/443 | Certificate-based | MDN receipts, MIC integrity |
| **AS4** | 80/443 | Certificate-based | SOAP envelope, ebMS3 |
| **Kafka** | 9092 | N/A | Topic-based, filename as key |

---

## Retry Logic

| Failure Type | Action | Examples |
|-------------|--------|---------|
| **Retryable** | Exponential backoff | Connection reset, timeout, 500/502/503 |
| **Non-retryable** | Fail immediately | 401/403, 404, auth failure, cert error |

Backoff formula: `base_delay × 2^attempt × jitter(0.75-1.25)`, capped at 2 minutes.

---

## Integration Examples

### Python — Forward to SFTP
```python
import requests

# Forward a file to a pre-configured SFTP destination
DESTINATION_ID = "550e8400-e29b-41d4-a716-446655440000"

with open("daily-report.csv", "rb") as f:
    result = requests.post(
        f"http://localhost:8087/api/forward/{DESTINATION_ID}",
        files={"file": f}
    ).json()

print(f"Forwarded to: {result['destination']}")
```

### Java — Forward to HTTP API
```java
RestTemplate rest = new RestTemplate();

MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
body.add("file", new FileSystemResource("report.csv"));

Map<String, Object> result = rest.postForObject(
    "http://localhost:8087/api/forward/deliver/" + endpointId + "?trackId=TRZ-001",
    new HttpEntity<>(body), Map.class
);
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers (for Kafka protocol) |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key |
| `server.port` | `8087` | HTTP port |

---

## All Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/forward/{destinationId}` | Forward file (multipart) |
| POST | `/api/forward/{destinationId}/base64` | Forward Base64 content |
| POST | `/api/forward/deliver/{endpointId}` | Deliver file to endpoint |
| POST | `/api/forward/deliver/{endpointId}/base64` | Deliver Base64 to endpoint |

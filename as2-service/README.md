# AS2 Service

> AS2/AS4 B2B protocol handler for EDI message exchange with trading partners.

**Port:** 8094 | **Database:** PostgreSQL | **Required:** Optional

---

## Overview

The AS2 service implements RFC 4130 (AS2) and ebMS3 (AS4) protocols for B2B electronic data interchange:

- **AS2 inbound** — Receive AS2 messages from trading partners
- **AS4 inbound** — Receive AS4/ebMS3 SOAP messages
- **MDN generation** — Synchronous and asynchronous Message Disposition Notifications
- **Duplicate detection** — Message-ID based deduplication
- **MIC (Message Integrity Check)** — SHA-1/SHA-256/SHA-384/SHA-512 digest
- **Auto-provisioning** — Automatically creates transfer accounts for new partners
- **File routing** — Routes received files through the standard flow engine
- **Partnership validation** — Verifies AS2-From/AS2-To against configured partnerships

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq onboarding-api config-service as2-service

# Health check
curl http://localhost:8094/internal/health
```

---

## API Endpoints

### AS2 Inbound

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/as2/receive` | Receive AS2 message from trading partner |

**How partners send AS2 messages:**
```bash
curl -X POST http://your-server:8094/as2/receive \
  -H "AS2-From: PARTNER_AS2_ID" \
  -H "AS2-To: YOUR_AS2_ID" \
  -H "Message-ID: <unique-id@partner.com>" \
  -H "Subject: Invoice batch" \
  -H "Content-Type: application/octet-stream" \
  -H "Disposition-Notification-To: https://partner.com/mdn" \
  --data-binary @invoice-batch.edi
```

**Processing flow:**
1. Validate headers (AS2-From, AS2-To, Message-ID)
2. Check for duplicate Message-ID (AS2 spec requirement)
3. Look up partnership by AS2-From
4. Extract file from HTTP body
5. Compute MIC (Message Integrity Check)
6. Auto-provision transfer account if needed
7. Save file to partner's inbox
8. Route through platform's file flow engine
9. Return MDN (synchronous) or send async MDN

**MDN response (synchronous):**
```
Content-Type: multipart/report; report-type=disposition-notification

--boundary
Content-Type: text/plain
The AS2 message has been received and processed.

--boundary
Content-Type: message/disposition-notification
Disposition: automatic-action/MDN-sent-automatically; processed
Received-Content-MIC: abc123==, sha-256
Original-Message-ID: <unique-id@partner.com>
--boundary--
```

### AS4 Inbound

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/as4/receive` | Receive AS4/ebMS3 SOAP message |

### MDN Callback

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/as2/mdn` | Receive asynchronous MDN callbacks |

### Internal API

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/internal/health` | None | Service health |
| POST | `/internal/files/receive` | X-Internal-Key | Receive forwarded files |

**Health response:**
```json
{
  "status": "UP",
  "activePartnerships": 12
}
```

---

## Partnership Configuration

Partnerships are configured via the config-service (AS2 Partnerships API):

```bash
curl -X POST http://localhost:8084/api/as2-partnerships \
  -H "Content-Type: application/json" \
  -d '{
    "partnerName": "ACME Corp",
    "partnerAs2Id": "ACME_AS2_ID",
    "ourAs2Id": "TRANZFER_AS2_ID",
    "endpointUrl": "https://partner.acme.com/as2/receive",
    "protocol": "AS2",
    "encryptionAlgorithm": "AES256",
    "signingAlgorithm": "SHA256",
    "requestMdn": true,
    "mdnAsync": false,
    "compression": true,
    "active": true
  }'
```

---

## Auto-Provisioning

When a new AS2 partner sends their first message:
1. AS2 service creates a `TransferAccount` (protocol: AS2)
2. Home directory created: `/data/as2/{partner-as2-id}/inbox`, `/outbox`, `/archive`
3. AS2 ID mapped to platform username
4. Subsequent messages use the provisioned account

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8094` | API port |
| `AS2_HOME_BASE` | `/data/as2` | AS2 message storage directory |
| `AS2_MDN_URL` | `http://localhost:8094/as2/mdn` | Async MDN callback URL |
| `AS2_MAX_MESSAGE_SIZE` | `524288000` | Max message size (500 MB) |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key |

---

## MIC (Message Integrity Check)

The MIC provides non-repudiation:
- Computed as a digest of the message content
- Included in the MDN response
- Supported algorithms: SHA-1, SHA-256, SHA-384, SHA-512
- Both sender and receiver can verify message integrity

---

## Dependencies

- **PostgreSQL** — Required. Partnership data, message records.
- **config-service** — Required. AS2 partnership configurations.
- **onboarding-api** — For auto-provisioning transfer accounts.
- **shared** module — Entities, routing engine, repositories.

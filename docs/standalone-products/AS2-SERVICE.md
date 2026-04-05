# AS2 Service — Standalone Product Guide

> **B2B AS2/AS4 protocol handler.** Receive and send EDI files via AS2 (RFC 4130) and AS4 (OASIS ebMS3) with MDN receipts, MIC integrity checks, and partnership management.

**Port:** 8094 | **Dependencies:** PostgreSQL | **Auth:** AS2 headers / partnership-based

---

## Why Use This

- **RFC 4130 compliant** — Full AS2 protocol with MDN (Message Disposition Notification)
- **AS4 support** — Modern OASIS ebMS3 SOAP-based protocol
- **MIC integrity** — SHA-1/256/384/512 Message Integrity Check
- **Duplicate detection** — Message-ID based deduplication
- **Sync & async MDN** — Both synchronous and asynchronous MDN delivery
- **Partnership management** — Configure per-partner settings (signing, encryption, compression)

---

## Quick Start

```bash
docker compose up -d postgres as2-service
curl http://localhost:8094/internal/health
```

```json
{
  "status": "UP",
  "service": "as2-service",
  "activePartnerships": 0,
  "as2Partnerships": 0,
  "as4Partnerships": 0
}
```

---

## API Reference — AS2 Protocol

### 1. Receive AS2 Message (Inbound)

**POST** `/as2/receive`

This is the endpoint your trading partners send AS2 messages to.

```bash
# Simulate an AS2 message from a trading partner
curl -X POST http://localhost:8094/as2/receive \
  -H "AS2-From: PARTNER_AS2_ID" \
  -H "AS2-To: OUR_AS2_ID" \
  -H "Message-ID: <msg-20260405-001@partner.com>" \
  -H "Subject: Daily Invoice Batch" \
  -H "Content-Type: application/octet-stream" \
  -H "Content-Disposition: attachment; filename=\"invoice-batch.edi\"" \
  -H "Disposition-Notification-To: partner@example.com" \
  -H "Disposition-Notification-Options: signed-receipt-protocol=optional,pkcs7-signature;signed-receipt-micalg=optional,sha-256" \
  --data-binary @invoice-batch.edi
```

**Response (Synchronous MDN):**
```
Content-Type: multipart/report; report-type=disposition-notification
AS2-From: OUR_AS2_ID
AS2-To: PARTNER_AS2_ID
AS2-Version: 1.2

--BOUNDARY
Content-Type: text/plain

The AS2 message has been received and processed successfully.
Original Message-ID: <msg-20260405-001@partner.com>

--BOUNDARY
Content-Type: message/disposition-notification

Reporting-UA: TranzFer-MFT/AS2-Service
Original-Recipient: rfc822; OUR_AS2_ID
Final-Recipient: rfc822; OUR_AS2_ID
Original-Message-ID: <msg-20260405-001@partner.com>
Disposition: automatic-action/MDN-sent-automatically; processed
Received-Content-MIC: abc123==, sha-256
--BOUNDARY--
```

### 2. Receive Async MDN Callback

**POST** `/as2/mdn`

Endpoint for partners to send async MDN responses.

```bash
curl -X POST http://localhost:8094/as2/mdn \
  -H "Message-ID: <mdn-001@partner.com>" \
  -H "Original-Message-ID: <msg-outbound-001@tranzfer.com>" \
  -H "Content-Type: multipart/report" \
  --data-binary @mdn-response.txt
```

---

## API Reference — AS4 Protocol

### 3. Receive AS4 Message (SOAP/ebMS3)

**POST** `/as4/receive`

```bash
curl -X POST http://localhost:8094/as4/receive \
  -H "Content-Type: application/soap+xml" \
  -d '<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
  <soap:Header>
    <eb:Messaging>
      <eb:UserMessage>
        <eb:MessageInfo>
          <eb:MessageId>msg-as4-001@partner.com</eb:MessageId>
        </eb:MessageInfo>
        <eb:PartyInfo>
          <eb:From><eb:PartyId>PARTNER_ID</eb:PartyId></eb:From>
          <eb:To><eb:PartyId>OUR_ID</eb:PartyId></eb:To>
        </eb:PartyInfo>
      </eb:UserMessage>
    </eb:Messaging>
  </soap:Header>
  <soap:Body>
    <Payload>BASE64_ENCODED_FILE_CONTENT_HERE</Payload>
  </soap:Body>
</soap:Envelope>'
```

**Response (AS4 Receipt Signal):**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
  <soap:Header>
    <eb:Messaging>
      <eb:SignalMessage>
        <eb:MessageInfo>
          <eb:Timestamp>2026-04-05T14:30:00Z</eb:Timestamp>
          <eb:MessageId>receipt-001@tranzfer.com</eb:MessageId>
          <eb:RefToMessageId>msg-as4-001@partner.com</eb:RefToMessageId>
        </eb:MessageInfo>
        <eb:Receipt>
          <NonRepudiationInformation>
            <MessagePartNRInformation>
              <Reference URI="cid:payload"/>
            </MessagePartNRInformation>
          </NonRepudiationInformation>
        </eb:Receipt>
      </eb:SignalMessage>
    </eb:Messaging>
  </soap:Header>
  <soap:Body/>
</soap:Envelope>
```

---

## API Reference — Internal

### 4. Receive Internal File Forward

**POST** `/internal/files/receive`

Used by other platform services to forward files to AS2 partners.

```bash
curl -X POST http://localhost:8094/internal/files/receive \
  -H "X-Internal-Key: internal_control_secret" \
  -H "Content-Type: application/json" \
  -d '{
    "recordId": "a1b2c3d4-...",
    "destinationUsername": "partner_acme",
    "destinationAbsolutePath": "/inbox/invoice.edi",
    "fileContentBase64": "SVNBKjAwKi...",
    "originalFilename": "invoice.edi"
  }'
```

### 5. Health Check

**GET** `/internal/health`

```bash
curl http://localhost:8094/internal/health
```

---

## Partnership Configuration

AS2/AS4 partnerships are managed via the config-service (`/api/as2-partnerships`).

### Partnership Entity Fields

| Field | Type | Description |
|-------|------|-------------|
| `partnerName` | String | Human-readable partner name |
| `partnerAs2Id` | String | Partner's AS2 identifier (unique) |
| `ourAs2Id` | String | Our AS2 identifier |
| `endpointUrl` | String | Partner's receiving URL |
| `partnerCertificate` | String | PEM certificate for encryption/signing |
| `signingAlgorithm` | String | SHA1, SHA256, SHA384, SHA512 |
| `encryptionAlgorithm` | String | 3DES, AES128, AES192, AES256 |
| `mdnRequired` | boolean | Require MDN receipts |
| `mdnAsync` | boolean | Use async MDN delivery |
| `mdnUrl` | String | Async MDN callback URL |
| `compressionEnabled` | boolean | ZLIB compression |
| `protocol` | String | AS2 or AS4 |

---

## Integration Examples

### Python — Send AS2 Message
```python
import requests
import base64
import uuid
from datetime import datetime

def send_as2_message(partner_url, our_as2_id, partner_as2_id, filepath):
    """Send a file via AS2 protocol."""
    with open(filepath, "rb") as f:
        payload = f.read()

    message_id = f"<{uuid.uuid4()}@tranzfer.com>"

    headers = {
        "AS2-From": our_as2_id,
        "AS2-To": partner_as2_id,
        "Message-ID": message_id,
        "Subject": f"File delivery - {filepath}",
        "Content-Type": "application/octet-stream",
        "Content-Disposition": f'attachment; filename="{filepath}"',
        "Disposition-Notification-To": "mdn@tranzfer.com",
        "Disposition-Notification-Options":
            "signed-receipt-protocol=optional,pkcs7-signature;"
            "signed-receipt-micalg=optional,sha-256",
        "AS2-Version": "1.2",
        "Date": datetime.utcnow().strftime("%a, %d %b %Y %H:%M:%S GMT"),
    }

    response = requests.post(partner_url, headers=headers, data=payload)
    return response.status_code == 200

# Send to partner
send_as2_message(
    "http://partner.example.com:8094/as2/receive",
    "OUR_AS2_ID",
    "PARTNER_AS2_ID",
    "daily-invoices.edi"
)
```

### Java — Receive AS2 and Process
```java
// Your partner sends to: http://your-server:8094/as2/receive
// The AS2 service:
// 1. Validates AS2 headers
// 2. Checks for duplicate Message-ID
// 3. Looks up partnership by partner AS2 ID
// 4. Computes MIC for integrity
// 5. Routes file through the platform
// 6. Returns MDN receipt

// To configure a partnership via config-service:
RestTemplate rest = new RestTemplate();
Map<String, Object> partnership = Map.of(
    "partnerName", "Acme Corp",
    "partnerAs2Id", "ACME_AS2",
    "ourAs2Id", "TRANZFER_AS2",
    "endpointUrl", "https://acme.com/as2/receive",
    "signingAlgorithm", "SHA256",
    "encryptionAlgorithm", "AES256",
    "mdnRequired", true,
    "protocol", "AS2"
);
rest.postForObject(
    "http://localhost:8084/api/as2-partnerships",
    partnership, Map.class
);
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `as2.home-base` | `/data/as2` | AS2 file storage |
| `as2.max-message-size` | `524288000` | Max message (500 MB) |
| `as2.mdn.our-url` | `http://localhost:8094/as2/mdn` | Our MDN callback URL |
| `as2.mdn.async-timeout-seconds` | `300` | Async MDN timeout |
| `server.port` | `8094` | HTTP port |

---

## All Endpoints Summary

| Method | Path | Protocol | Description |
|--------|------|----------|-------------|
| POST | `/as2/receive` | AS2 | Receive AS2 message (inbound) |
| POST | `/as2/mdn` | AS2 | Receive async MDN callback |
| POST | `/as4/receive` | AS4 | Receive AS4/ebMS3 message |
| POST | `/internal/files/receive` | Internal | Receive forwarded file |
| GET | `/internal/health` | HTTP | Health check |

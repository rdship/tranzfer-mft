# AS2 Service -- Demo & Quick Start Guide

> RFC 4130 AS2 and OASIS ebMS3/AS4 B2B message exchange with synchronous/asynchronous MDN receipts, MIC integrity verification, and automatic file routing into the TranzFer platform.

---

## What This Service Does

- **AS2 message receiving (RFC 4130)** -- Trading partners POST files to `/as2/receive` with AS2-From, AS2-To, and Message-ID headers. The service validates the partnership, computes a Message Integrity Check (MIC), writes the file to the partner's inbox, and returns an MDN receipt.
- **AS4/ebMS3 message receiving (OASIS)** -- Partners send SOAP/XML envelopes to `/as4/receive` with ebMS3 PartyInfo and Base64-encoded payloads. The service parses the SOAP, validates partnership, and returns an ebMS3 Receipt signal.
- **MDN receipts (sync and async)** -- Synchronous MDN is returned inline with the HTTP response. Asynchronous MDN is sent later via HTTP POST to the partner's callback URL (specified in `Receipt-Delivery-Option` header).
- **Duplicate detection** -- Every AS2 Message-ID and AS4 MessageId is checked against the database. Duplicate messages are rejected per the AS2 specification.
- **Platform integration** -- Received files are routed through the same RoutingEngine as SFTP/FTP uploads, getting the same flow processing, AI classification, audit logging, and delivery evaluation.

---

## What You Need (Prerequisites Checklist)

Before starting, complete these items from [PREREQUISITES.md](PREREQUISITES.md):

- [ ] **Docker** installed and running ([Step 1, Option A](PREREQUISITES.md#option-a-docker-recommended-for-all-os))
      OR **Java 21 + Maven** installed ([Step 1, Option B](PREREQUISITES.md#option-b-java-21--maven-build-from-source))
- [ ] **PostgreSQL 16** running on port 5432 with database `filetransfer` ([Step 2](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it))
- [ ] **curl** installed (ships with macOS/Linux; Windows: `winget install cURL.cURL`)
- [ ] **Port 8094** is free (`lsof -i :8094` on Linux/macOS, `netstat -ano | findstr :8094` on Windows)

**Important:** Both the Storage Manager and AS2 Service default to port 8094. If you want to run both simultaneously, change one of them by setting `SERVER_PORT` (e.g., `SERVER_PORT=8096`).

---

## Install & Start

### Method 1: Docker (Any OS)

```bash
# 1. Start PostgreSQL (skip if already running)
docker run -d \
  --name mft-postgres \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine

# Wait for PostgreSQL to be ready
docker exec mft-postgres pg_isready -U postgres

# 2. Build the AS2 Service image
cd file-transfer-platform
docker build -t mft-as2-service ./as2-service

# 3. Run
docker run -d \
  --name mft-as2-service \
  -p 8094:8094 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e AS2_HOME_BASE=/data/as2 \
  -e AS2_MDN_OUR_URL=http://localhost:8094/as2/mdn \
  -v as2_data:/data/as2 \
  mft-as2-service
```

**Linux note:** Replace `host.docker.internal` with `172.17.0.1` or use `--network host`.

### Method 2: Docker Compose (with PostgreSQL)

Create a file called `docker-compose-as2.yml`:

```yaml
version: '3.9'
services:
  postgres:
    image: postgres:16-alpine
    container_name: mft-postgres
    environment:
      POSTGRES_DB: filetransfer
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5

  as2-service:
    build: ./as2-service
    container_name: mft-as2-service
    ports:
      - "8094:8094"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      AS2_HOME_BASE: /data/as2
      AS2_MDN_OUR_URL: http://localhost:8094/as2/mdn
      AS2_MAX_MESSAGE_SIZE: 524288000
      AS2_MDN_ASYNC_TIMEOUT: 300
      CONTROL_API_KEY: internal_control_secret
    volumes:
      - as2_data:/data/as2
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8094/internal/health || exit 1"]
      interval: 30s
      timeout: 3s
      start_period: 15s
      retries: 3

volumes:
  postgres_data:
  as2_data:
```

```bash
# Start both services
docker compose -f docker-compose-as2.yml up -d

# Check status
docker compose -f docker-compose-as2.yml ps
```

### Method 3: From Source

```bash
# 1. Start PostgreSQL (Docker method -- skip if you have native PostgreSQL)
docker run -d \
  --name mft-postgres \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine

# 2. Create AS2 home directory
mkdir -p /tmp/mft-as2

# 3. Build the service (from the repository root)
cd file-transfer-platform
mvn clean package -DskipTests -pl as2-service -am

# 4. Run
AS2_HOME_BASE=/tmp/mft-as2 \
AS2_MDN_OUR_URL=http://localhost:8094/as2/mdn \
java -jar as2-service/target/as2-service-*.jar
```

**Windows (PowerShell):**

```powershell
# Create AS2 home directory
New-Item -ItemType Directory -Force -Path C:\mft-as2

# Set environment variables and run
$env:AS2_HOME_BASE = "C:\mft-as2"
$env:AS2_MDN_OUR_URL = "http://localhost:8094/as2/mdn"
java -jar as2-service\target\as2-service-*.jar
```

---

## Verify It's Running

```bash
curl -s http://localhost:8094/internal/health | python3 -m json.tool
```

Expected output:

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

## Demo 1: Create a Partnership and Send an AS2 Message

AS2 requires a trading partnership to be configured before messages can be exchanged. Partnerships are managed through the Config Service (port 8084). For this standalone demo, we will insert the partnership directly into PostgreSQL and then send a message.

### Step 1: Create a trading partnership in the database

```bash
# Connect to PostgreSQL and insert a partnership
# This simulates what the Config Service's POST /api/as2-partnerships would do
docker exec -i mft-postgres psql -U postgres -d filetransfer <<'SQL'
INSERT INTO as2_partnerships (
  id, partner_name, partner_as2_id, our_as2_id,
  endpoint_url, signing_algorithm, encryption_algorithm,
  mdn_required, mdn_async, compression_enabled,
  protocol, active, created_at, updated_at
) VALUES (
  gen_random_uuid(),
  'Meridian Supply Co.',
  'MERIDIAN-AS2',
  'TRANZFER-MFT',
  'http://localhost:8094/as2/receive',
  'SHA256', 'AES256',
  true, false, false,
  'AS2', true, NOW(), NOW()
) ON CONFLICT (partner_as2_id) DO NOTHING;
SQL
```

**Without Docker (native psql):**

```bash
psql -h localhost -U postgres -d filetransfer <<'SQL'
INSERT INTO as2_partnerships (
  id, partner_name, partner_as2_id, our_as2_id,
  endpoint_url, signing_algorithm, encryption_algorithm,
  mdn_required, mdn_async, compression_enabled,
  protocol, active, created_at, updated_at
) VALUES (
  gen_random_uuid(),
  'Meridian Supply Co.',
  'MERIDIAN-AS2',
  'TRANZFER-MFT',
  'http://localhost:8094/as2/receive',
  'SHA256', 'AES256',
  true, false, false,
  'AS2', true, NOW(), NOW()
) ON CONFLICT (partner_as2_id) DO NOTHING;
SQL
```

### Step 2: Verify the partnership was created

```bash
curl -s http://localhost:8094/internal/health | python3 -m json.tool
```

Expected output:

```json
{
    "status": "UP",
    "service": "as2-service",
    "activePartnerships": 1,
    "as2Partnerships": 1,
    "as4Partnerships": 0
}
```

### Step 3: Send an AS2 message with proper RFC 4130 headers

```bash
curl -s -D - -X POST http://localhost:8094/as2/receive \
  -H "AS2-From: MERIDIAN-AS2" \
  -H "AS2-To: TRANZFER-MFT" \
  -H "Message-ID: <invoice-20260405-001@meridian-supply.com>" \
  -H "Subject: invoice-2026-04-001.edi" \
  -H "Content-Type: application/edi-x12" \
  -H "Disposition-Notification-To: as2@meridian-supply.com" \
  -H "Disposition-Notification-Options: signed-receipt-protocol=optional,pkcs7-signature;signed-receipt-micalg=optional,sha-256" \
  -H "Content-Disposition: attachment; filename=\"invoice-2026-04-001.edi\"" \
  -d "ISA*00*          *00*          *ZZ*MERIDIAN      *ZZ*TRANZFER      *260405*1030*U*00401*000000001*0*P*>~GS*IN*MERIDIAN*TRANZFER*20260405*1030*1*X*004010~ST*810*0001~BIG*20260405*INV-2026-04-001*20260401*PO-88471~N1*ST*Tranzfer MFT Inc~ITD*01*3*2**30~TDS*1425000~SE*6*0001~GE*1*1~IEA*1*000000001~"
```

Expected response headers:

```
HTTP/1.1 200
AS2-From: TRANZFER-MFT
AS2-To: MERIDIAN-AS2
Message-ID: <a1b2c3d4-...-@TRANZFER-MFT>
AS2-Version: 1.2
Content-Type: multipart/report; report-type=disposition-notification; boundary="----=_mdn_..."
```

Expected response body (MDN receipt):

```
------=_mdn_abcdef1234567890
Content-Type: text/plain

The AS2 message has been received and processed successfully.
Original Message-ID: <invoice-20260405-001@meridian-supply.com>
Date: Sat, 05 Apr 2026 10:30:00 +0000

------=_mdn_abcdef1234567890
Content-Type: message/disposition-notification

Reporting-UA: TranzFer-MFT/AS2-Service
Original-Recipient: rfc822; TRANZFER-MFT
Final-Recipient: rfc822; TRANZFER-MFT
Original-Message-ID: <invoice-20260405-001@meridian-supply.com>
Disposition: automatic-action/MDN-sent-automatically; processed
Received-Content-MIC: abc123base64hash==, SHA-256

------=_mdn_abcdef1234567890--
```

Key observations:
- The response contains a **multipart/report** MDN per RFC 3798 and RFC 4130 Section 7.
- `Disposition: automatic-action/MDN-sent-automatically; processed` confirms successful receipt.
- `Received-Content-MIC` contains the SHA-256 hash of the payload for non-repudiation. The sender can compare this against their own hash to verify the correct file was received.
- The `AS2-From` and `AS2-To` in the response are **swapped** from the request (we are now the sender of the MDN).

### Step 4: Verify duplicate detection

```bash
# Send the exact same Message-ID again
curl -s -D - -X POST http://localhost:8094/as2/receive \
  -H "AS2-From: MERIDIAN-AS2" \
  -H "AS2-To: TRANZFER-MFT" \
  -H "Message-ID: <invoice-20260405-001@meridian-supply.com>" \
  -H "Content-Type: application/edi-x12" \
  -d "ISA*00*duplicate*test~"
```

Expected response:

```
HTTP/1.1 400
```

The body will contain an error MDN with:

```
Disposition: automatic-action/MDN-sent-automatically; failed/failure: Duplicate Message-ID: invoice-20260405-001@meridian-supply.com
```

---

## Demo 2: Asynchronous MDN Receipt

In production, many trading partners request asynchronous MDN delivery. The sender includes a `Receipt-Delivery-Option` header with a callback URL. The receiver returns HTTP 200 immediately and sends the MDN later via HTTP POST.

### Step 1: Send a message with async MDN request

```bash
curl -s -D - -X POST http://localhost:8094/as2/receive \
  -H "AS2-From: MERIDIAN-AS2" \
  -H "AS2-To: TRANZFER-MFT" \
  -H "Message-ID: <po-20260405-002@meridian-supply.com>" \
  -H "Subject: purchase-order-88472.edi" \
  -H "Content-Type: application/edi-x12" \
  -H "Disposition-Notification-To: as2@meridian-supply.com" \
  -H "Receipt-Delivery-Option: http://localhost:9999/mdn-callback" \
  -d "ISA*00*          *00*          *ZZ*MERIDIAN      *ZZ*TRANZFER      *260405*1045*U*00401*000000002*0*P*>~GS*PO*MERIDIAN*TRANZFER*20260405*1045*2*X*004010~ST*850*0001~BEG*00*NE*PO-88472**20260405~N1*BY*Tranzfer MFT Inc~PO1*1*500*EA*28.50**VP*WIDGET-A~PO1*2*200*EA*42.00**VP*WIDGET-B~CTT*2~SE*7*0001~GE*1*2~IEA*1*000000002~"
```

Expected response:

```
HTTP/1.1 200
```

The response body will be empty (HTTP 200 OK with no content). The MDN will be sent asynchronously to `http://localhost:9999/mdn-callback` by the scheduled task (runs every 30 seconds by default).

Note: Since `http://localhost:9999/mdn-callback` does not exist in this demo, the async MDN delivery will fail silently in the logs. In production, this would be the trading partner's real MDN endpoint.

### Step 2: Receive an async MDN (simulating the partner's callback)

When YOUR service sends an outbound AS2 message and the partner responds with an async MDN, the partner POSTs to `/as2/mdn`:

```bash
curl -s -X POST http://localhost:8094/as2/mdn \
  -H "Message-ID: <mdn-resp-001@meridian-supply.com>" \
  -H "Original-Message-ID: <outbound-msg-001@tranzfer-mft.com>" \
  -H "Content-Type: message/disposition-notification" \
  -d "Reporting-UA: Meridian-AS2-Gateway/1.0
Original-Message-ID: <outbound-msg-001@tranzfer-mft.com>
Disposition: automatic-action/MDN-sent-automatically; processed"
```

Expected response:

```
HTTP/1.1 200
```

This endpoint receives async MDN callbacks from partners and updates the corresponding outbound message record to `ACKNOWLEDGED`.

---

## Demo 3: AS4/ebMS3 SOAP Message Exchange

AS4 (OASIS ebMS3) is the modern successor to AS2. Messages are wrapped in SOAP/XML envelopes with structured PartyInfo headers and Base64-encoded payloads.

### Step 1: Create an AS4 partnership

```bash
docker exec -i mft-postgres psql -U postgres -d filetransfer <<'SQL'
INSERT INTO as2_partnerships (
  id, partner_name, partner_as2_id, our_as2_id,
  endpoint_url, signing_algorithm, encryption_algorithm,
  mdn_required, protocol, active, created_at, updated_at
) VALUES (
  gen_random_uuid(),
  'Cascade Logistics GmbH',
  'CASCADE-EBMS',
  'TRANZFER-EBMS',
  'http://localhost:8094/as4/receive',
  'SHA256', 'AES256',
  true, 'AS4', true, NOW(), NOW()
) ON CONFLICT (partner_as2_id) DO NOTHING;
SQL
```

### Step 2: Send an AS4 SOAP envelope

The payload must be Base64-encoded inside the SOAP body. First, encode a sample EDI document:

```bash
# Base64-encode a sample payload
PAYLOAD_B64=$(echo -n "ISA*00*          *00*          *ZZ*CASCADE       *ZZ*TRANZFER      *260405*1100*U*00401*000000003*0*P*>~GS*FA*CASCADE*TRANZFER*20260405*1100*3*X*004010~ST*997*0001~AK1*IN*1~AK9*A*1*1*1~SE*4*0001~GE*1*3~IEA*1*000000003~" | base64)

# Send the SOAP envelope
curl -s -D - -X POST http://localhost:8094/as4/receive \
  -H "Content-Type: application/soap+xml" \
  -d "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"
               xmlns:eb=\"http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/\">
    <soap:Header>
        <eb:Messaging>
            <eb:UserMessage>
                <eb:MessageInfo>
                    <eb:Timestamp>2026-04-05T11:00:00Z</eb:Timestamp>
                    <eb:MessageId>fa-20260405-003@cascade-logistics.de</eb:MessageId>
                </eb:MessageInfo>
                <eb:PartyInfo>
                    <eb:From>
                        <eb:PartyId type=\"urn:oasis:names:tc:ebcore:partyid-type:iso6523:0088\">CASCADE-EBMS</eb:PartyId>
                    </eb:From>
                    <eb:To>
                        <eb:PartyId type=\"urn:oasis:names:tc:ebcore:partyid-type:iso6523:0088\">TRANZFER-EBMS</eb:PartyId>
                    </eb:To>
                </eb:PartyInfo>
                <eb:CollaborationInfo>
                    <eb:Service>urn:tranzfer:services:fileTransfer</eb:Service>
                    <eb:Action>functional-ack-997.edi</eb:Action>
                    <eb:ConversationId>conv-cascade-20260405</eb:ConversationId>
                </eb:CollaborationInfo>
                <eb:PayloadInfo>
                    <eb:PartInfo href=\"cid:payload\"/>
                </eb:PayloadInfo>
            </eb:UserMessage>
        </eb:Messaging>
    </soap:Header>
    <soap:Body>
        <Payload>$PAYLOAD_B64</Payload>
    </soap:Body>
</soap:Envelope>"
```

Expected response headers:

```
HTTP/1.1 200
Content-Type: text/xml
```

Expected response body (ebMS3 Receipt signal):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
    <soap:Header>
        <eb:Messaging>
            <eb:SignalMessage>
                <eb:MessageInfo>
                    <eb:Timestamp>2026-04-05T11:00:01Z</eb:Timestamp>
                    <eb:MessageId>a1b2c3d4-e5f6-...</eb:MessageId>
                    <eb:RefToMessageId>fa-20260405-003@cascade-logistics.de</eb:RefToMessageId>
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

Key observations:
- `eb:RefToMessageId` references the original message, proving receipt.
- `eb:Receipt` with `NonRepudiationInformation` provides cryptographic proof of delivery.
- The response is a valid SOAP envelope that the sender can parse and archive.

### Step 3: Verify AS4 error handling

```bash
# Send with unknown partner
curl -s -D - -X POST http://localhost:8094/as4/receive \
  -H "Content-Type: application/soap+xml" \
  -d "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"
               xmlns:eb=\"http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/\">
    <soap:Header>
        <eb:Messaging>
            <eb:UserMessage>
                <eb:MessageInfo>
                    <eb:MessageId>test-unknown-001</eb:MessageId>
                </eb:MessageInfo>
                <eb:PartyInfo>
                    <eb:From><eb:PartyId>UNKNOWN-PARTNER</eb:PartyId></eb:From>
                    <eb:To><eb:PartyId>TRANZFER-EBMS</eb:PartyId></eb:To>
                </eb:PartyInfo>
            </eb:UserMessage>
        </eb:Messaging>
    </soap:Header>
    <soap:Body><Payload>dGVzdA==</Payload></soap:Body>
</soap:Envelope>"
```

Expected response:

```
HTTP/1.1 400
Content-Type: text/xml
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
    <soap:Header>
        <eb:Messaging>
            <eb:SignalMessage>
                <eb:MessageInfo>
                    <eb:Timestamp>2026-04-05T11:05:00Z</eb:Timestamp>
                    <eb:MessageId>...</eb:MessageId>
                    <eb:RefToMessageId>unknown</eb:RefToMessageId>
                </eb:MessageInfo>
                <eb:Error errorCode="EBMS:0004" severity="failure"
                          shortDescription="Unknown trading partner: UNKNOWN-PARTNER">
                    <eb:Description xml:lang="en">Unknown trading partner: UNKNOWN-PARTNER</eb:Description>
                </eb:Error>
            </eb:SignalMessage>
        </eb:Messaging>
    </soap:Header>
    <soap:Body/>
</soap:Envelope>
```

---

## Demo 4: Integration Pattern -- Python, Java, Node.js

### Python

```python
import requests
import base64
import uuid

BASE = "http://localhost:8094"

# --- AS2 Message ---
message_id = f"<{uuid.uuid4()}@demo-partner.com>"
headers = {
    "AS2-From": "MERIDIAN-AS2",
    "AS2-To": "TRANZFER-MFT",
    "Message-ID": message_id,
    "Subject": "shipment-notice-SN-20260405.edi",
    "Content-Type": "application/edi-x12",
    "Disposition-Notification-To": "as2@meridian-supply.com",
    "Content-Disposition": 'attachment; filename="shipment-notice-SN-20260405.edi"',
}
payload = b"ISA*00*...*ZZ*MERIDIAN*ZZ*TRANZFER*260405*1200*U*00401~"

resp = requests.post(f"{BASE}/as2/receive", headers=headers, data=payload)
print(f"Status: {resp.status_code}")
print(f"AS2-From (response): {resp.headers.get('AS2-From')}")
print(f"MDN Message-ID: {resp.headers.get('Message-ID')}")

# Check for successful disposition
if "processed" in resp.text:
    print("MDN: Message processed successfully")
else:
    print(f"MDN body: {resp.text[:200]}")

# --- Health Check ---
health = requests.get(f"{BASE}/internal/health").json()
print(f"Active partnerships: {health['activePartnerships']}")
```

### Java (HttpClient -- Java 21+)

```java
import java.net.URI;
import java.net.http.*;
import java.util.UUID;

public class As2Demo {
    public static void main(String[] args) throws Exception {
        var client = HttpClient.newHttpClient();
        var base = "http://localhost:8094";
        var messageId = "<" + UUID.randomUUID() + "@demo-partner.com>";

        // Send AS2 message
        var payload = "ISA*00*...*ZZ*MERIDIAN*ZZ*TRANZFER*260405*1300*U*00401~";
        var request = HttpRequest.newBuilder()
            .uri(URI.create(base + "/as2/receive"))
            .header("AS2-From", "MERIDIAN-AS2")
            .header("AS2-To", "TRANZFER-MFT")
            .header("Message-ID", messageId)
            .header("Subject", "remittance-advice.edi")
            .header("Content-Type", "application/edi-x12")
            .header("Disposition-Notification-To", "as2@meridian-supply.com")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Status: " + response.statusCode());
        System.out.println("AS2-From: " + response.headers().firstValue("AS2-From").orElse("N/A"));
        System.out.println("MDN contains 'processed': " + response.body().contains("processed"));

        // Health check
        var healthReq = HttpRequest.newBuilder()
            .uri(URI.create(base + "/internal/health")).GET().build();
        var healthResp = client.send(healthReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Health: " + healthResp.body());
    }
}
```

### Node.js (fetch -- Node 18+)

```javascript
const crypto = require("crypto");

const BASE = "http://localhost:8094";

async function demo() {
  const messageId = `<${crypto.randomUUID()}@demo-partner.com>`;

  // Send AS2 message
  const resp = await fetch(`${BASE}/as2/receive`, {
    method: "POST",
    headers: {
      "AS2-From": "MERIDIAN-AS2",
      "AS2-To": "TRANZFER-MFT",
      "Message-ID": messageId,
      "Subject": "advance-ship-notice.edi",
      "Content-Type": "application/edi-x12",
      "Disposition-Notification-To": "as2@meridian-supply.com",
    },
    body: "ISA*00*...*ZZ*MERIDIAN*ZZ*TRANZFER*260405*1400*U*00401~",
  });

  console.log("Status:", resp.status);
  console.log("AS2-From (response):", resp.headers.get("AS2-From"));
  const mdnBody = await resp.text();
  console.log("MDN processed:", mdnBody.includes("processed"));

  // Health check
  const health = await (await fetch(`${BASE}/internal/health`)).json();
  console.log("Partnerships:", health.activePartnerships);
}

demo().catch(console.error);
```

---

## Use Cases

1. **EDI document exchange** -- Receive X12 810 (Invoice), 850 (Purchase Order), 856 (Advance Ship Notice), and 997 (Functional Acknowledgment) from trading partners over AS2 with guaranteed delivery via MDN receipts.
2. **Supply chain integration** -- Retailers and suppliers exchange inventory, order, and shipping data. AS2 provides non-repudiation (MIC proves what was received) and guaranteed delivery (MDN proves it arrived).
3. **Healthcare claims (HIPAA)** -- Health insurance claims (X12 837) and remittance advice (X12 835) exchanged between providers and payers over AS2 with encryption and signing.
4. **European e-invoicing (AS4/Peppol)** -- AS4/ebMS3 is the transport protocol for Peppol e-invoicing across the EU. The AS4 endpoint receives SOAP envelopes with UBL Invoice payloads.
5. **Customs and trade compliance** -- Customs declarations and trade documents exchanged between importers, exporters, and government agencies over AS2/AS4.
6. **Multi-partner hub** -- Configure dozens of trading partners with different security profiles (some SHA-1 legacy, some SHA-256, some with async MDN, some sync). Each partner gets auto-provisioned accounts and isolated home directories.
7. **Hybrid protocol deployment** -- Some partners use AS2, others use AS4. Both protocols share the same partnership table and routing infrastructure. A single service handles both.

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC connection string |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `AS2_HOME_BASE` | `/data/as2` | Root directory for partner inbox/outbox/archive |
| `AS2_INSTANCE_ID` | `null` | Unique instance identifier (for multi-instance deployments) |
| `AS2_MAX_MESSAGE_SIZE` | `524288000` | Maximum AS2 message size in bytes (default: 500 MB) |
| `AS2_MDN_ASYNC_TIMEOUT` | `300` | Seconds to wait for async MDN before marking as timed out |
| `AS2_MDN_OUR_URL` | `http://localhost:8094/as2/mdn` | Our URL for receiving async MDN callbacks from partners |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | JWT signing secret for internal authentication |
| `CONTROL_API_KEY` | `internal_control_secret` | API key for internal endpoints (`/internal/files/receive`) |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier for distributed deployments |
| `CLUSTER_HOST` | `localhost` | Hostname of this instance within the cluster |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label (PROD, STAGING, DEV) |

---

## API Reference -- All Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/as2/receive` | Receive an inbound AS2 message (RFC 4130). Returns synchronous MDN. |
| `POST` | `/as2/mdn` | Receive an asynchronous MDN callback from a trading partner |
| `POST` | `/as4/receive` | Receive an inbound AS4/ebMS3 SOAP message. Returns ebMS3 Receipt. |
| `GET` | `/internal/health` | Health check with partnership counts |
| `POST` | `/internal/files/receive` | Internal: receive forwarded files from other cluster instances (requires `X-Internal-Key` header) |

**Partnership management** is handled by the Config Service (port 8084):

| Method | Endpoint (Config Service :8084) | Description |
|--------|----------|-------------|
| `GET` | `/api/as2-partnerships` | List all active partnerships (optional `?protocol=AS2` or `?protocol=AS4`) |
| `GET` | `/api/as2-partnerships/{id}` | Get a specific partnership by UUID |
| `POST` | `/api/as2-partnerships` | Create a new partnership |
| `PUT` | `/api/as2-partnerships/{id}` | Update an existing partnership |
| `PATCH` | `/api/as2-partnerships/{id}/toggle` | Toggle a partnership active/inactive |
| `DELETE` | `/api/as2-partnerships/{id}` | Deactivate a partnership (soft delete) |

---

## Cleanup

```bash
# Docker method
docker stop mft-as2-service && docker rm mft-as2-service
docker stop mft-postgres && docker rm mft-postgres

# Docker Compose method
docker compose -f docker-compose-as2.yml down -v

# Source method: Ctrl+C to stop the Java process, then:
rm -rf /tmp/mft-as2

# Windows (PowerShell)
Remove-Item -Recurse -Force C:\mft-as2
```

---

## Troubleshooting

### All Platforms

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connection refused` on port 8094 | Service not started or still starting | Wait 15 seconds. Check logs: `docker logs mft-as2-service` |
| `Missing AS2-From header` error MDN | curl command missing `-H "AS2-From: ..."` | Include all required headers: AS2-From, AS2-To, Message-ID |
| `Unknown trading partner: XYZ` | No active partnership with that `partnerAs2Id` | Insert a partnership into `as2_partnerships` table (see Demo 1, Step 1) |
| `AS2-To does not match our AS2 ID` | `AS2-To` header does not match partnership's `ourAs2Id` | Verify: `SELECT our_as2_id FROM as2_partnerships WHERE partner_as2_id='...'` |
| `Duplicate Message-ID` | Same Message-ID sent twice | Use a unique Message-ID per message (e.g., UUID-based) |
| `Empty message body` | POST request has no body | Include file content as the request body with `-d` or `--data-binary` |
| `Relation "as2_partnerships" does not exist` | Flyway migration did not run | Ensure database name is `filetransfer` and Flyway is enabled |
| AS4 returns `Missing eb:MessageId` | SOAP XML malformed or missing required elements | Validate your SOAP envelope has `eb:MessageInfo > eb:MessageId` |

### Linux

| Symptom | Cause | Fix |
|---------|-------|-----|
| `host.docker.internal` not resolving | Linux Docker limitation | Use `--network host` or `--add-host=host.docker.internal:host-gateway` |
| Permission denied on `/data/as2` | Container running as non-root | Use a Docker volume or `chmod 777 /tmp/mft-as2` for local testing |

### macOS

| Symptom | Cause | Fix |
|---------|-------|-----|
| Port 8094 already in use | Storage Manager or another service on same port | Use `SERVER_PORT=8096` for one of the services |
| Slow startup in Docker | Docker Desktop resource limits | Allocate at least 2 GB RAM in Docker Desktop settings |

### Windows

| Symptom | Cause | Fix |
|---------|-------|-----|
| curl quoting issues with XML | Windows shell escaping | Use Git Bash instead of PowerShell, or save the XML to a file and use `--data-binary @file.xml` |
| `mvn` not recognized | Maven not in PATH | Run `winget install Apache.Maven`, restart terminal |
| Path errors with `\data\as2` | Backslash in paths | Use forward slashes: `AS2_HOME_BASE=C:/mft-as2` |

---

## What's Next

- **Config Service** ([CONFIG-SERVICE.md](CONFIG-SERVICE.md)) -- Manage AS2/AS4 partnerships through the REST API instead of direct SQL inserts. Create file flows that process AS2-received files.
- **Storage Manager** ([STORAGE-MANAGER.md](STORAGE-MANAGER.md)) -- Route AS2-received files into tiered storage for long-term retention with deduplication.
- **Encryption Service** ([ENCRYPTION-SERVICE.md](ENCRYPTION-SERVICE.md)) -- Add encryption to AS2 payloads. The platform can encrypt files before forwarding to downstream systems.
- **External Forwarder** ([EXTERNAL-FORWARDER.md](EXTERNAL-FORWARDER.md)) -- Forward received AS2 files to other destinations via SFTP, HTTP, or Kafka.
- **EDI Converter** ([EDI-CONVERTER.md](EDI-CONVERTER.md)) -- Parse and convert the EDI documents received via AS2 into JSON, CSV, or XML for downstream consumption.

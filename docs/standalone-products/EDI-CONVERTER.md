# EDI Converter — Standalone Product Guide

> **Universal EDI conversion engine.** Convert any EDI format to JSON, XML, CSV, or YAML with a single API call. No database, no configuration — just HTTP.

**Port:** 8095 | **Dependencies:** None | **Database:** Not required | **Auth:** None

---

## Why Use This

- **66 conversion paths** — 11 input formats × 6 output formats
- **Zero setup** — `java -jar edi-converter.jar` and you're running
- **Auto-detection** — Send any EDI content, the engine detects the format
- **Self-healing** — Auto-fixes 25+ common EDI errors
- **No vendor lock-in** — Stateless REST API, embed anywhere

---

## Quick Start

### Option 1: Java JAR
```bash
mvn clean package -pl edi-converter -DskipTests
java -jar edi-converter/target/edi-converter-*.jar
# Running on http://localhost:8095
```

### Option 2: Docker
```bash
docker compose up -d edi-converter
# Running on http://localhost:8095
```

### Option 3: Docker Standalone
```dockerfile
FROM eclipse-temurin:21-jre
COPY edi-converter-*.jar app.jar
EXPOSE 8095
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Verify
```bash
curl http://localhost:8095/api/v1/convert/health
```
```json
{
  "status": "UP",
  "service": "edi-converter",
  "version": "3.0",
  "inputFormats": 11,
  "outputFormats": 6,
  "totalConversionPaths": 66
}
```

---

## Supported Formats

### Input Formats (11)

| Format | Detection Pattern | Common Use |
|--------|------------------|-----------|
| **X12** | `ISA*` or `ST*` | US healthcare (837), POs (850), invoices (810) |
| **EDIFACT** | `UNB+`, `UNA`, `UNH+` | International trade, logistics |
| **TRADACOMS** | `STX=` | UK retail |
| **SWIFT MT** | `{1:` or `:20:` + `:32A:` | Banking wire transfers (MT103) |
| **HL7** | `MSH\|` | Healthcare patient data (ADT), lab results (ORU) |
| **NACHA/ACH** | Fixed 94-char records | US ACH payment batches |
| **BAI2** | `01,` or `02,` | Bank statement reporting |
| **ISO 20022** | `urn:iso:std:iso:20022` | Modern banking (pain, camt, pacs) |
| **FIX** | `8=FIX` | Financial trading messages |
| **PEPPOL/UBL** | `urn:oasis:names` | European e-invoicing |
| **AUTO** | (auto-detect) | Let the engine figure it out |

### Output Formats (6)

| Format | Content-Type | Use Case |
|--------|-------------|----------|
| **JSON** | `application/json` | APIs, databases, modern systems |
| **XML** | `application/xml` | Legacy systems, SOAP |
| **CSV** | `text/csv` | Spreadsheets, data warehouses |
| **YAML** | `application/yaml` | Configuration, human-readable |
| **FLAT** | `text/plain` | Fixed-width mainframe systems |
| **TIF** | `text/plain` | TranzFer Internal Format |

---

## API Reference

### 1. Convert EDI Content

**POST** `/api/v1/convert/convert`

Convert any EDI format to any output format.

```bash
curl -X POST http://localhost:8095/api/v1/convert/convert \
  -H "Content-Type: application/json" \
  -d '{
    "content": "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *210101*1200*U*00501*000000001*0*P*:~GS*HP*SENDER*RECEIVER*20210101*1200*1*X*005010~ST*837*0001*005010X222A1~BHT*0019*00*CLM20210101*20210101*1200*CH~NM1*41*2*CLINIC*****46*1234567890~NM1*40*2*PAYER*****46*9876543210~NM1*IL*1*DOE*JOHN****MI*PATIENT001~CLM*CLM20210101*1500***11:B:1*Y*A*Y*Y~SE*7*0001~GE*1*1~IEA*1*000000001~",
    "target": "JSON"
  }'
```

**Response:**
```json
{
  "sourceFormat": "X12",
  "documentType": "837",
  "envelope": {
    "senderId": "SENDER",
    "receiverId": "RECEIVER",
    "date": "210101",
    "controlNumber": "000000001"
  },
  "segments": [
    {"id": "ST", "elements": ["837", "0001", "005010X222A1"]},
    {"id": "BHT", "elements": ["0019", "00", "CLM20210101", "20210101", "1200", "CH"]},
    {"id": "NM1", "elements": ["41", "2", "CLINIC", "", "", "", "", "46", "1234567890"]},
    {"id": "CLM", "elements": ["CLM20210101", "1500", "", "", "11:B:1", "Y", "A", "Y", "Y"]}
  ]
}
```

### 2. Convert EDI File (Upload)

**POST** `/api/v1/convert/convert/file`

```bash
curl -X POST http://localhost:8095/api/v1/convert/convert/file \
  -F "file=@/path/to/invoice.edi" \
  -F "target=JSON"
```

### 3. Auto-Detect Format

**POST** `/api/v1/convert/detect`

```bash
curl -X POST http://localhost:8095/api/v1/convert/detect \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*          *00*          *ZZ*SENDER*ZZ*RECEIVER*210101*1200*U*00501*1*0*P*:~"}'
```

**Response:**
```json
{
  "format": "X12"
}
```

### 4. Parse EDI to Structured Document

**POST** `/api/v1/convert/parse`

```bash
curl -X POST http://localhost:8095/api/v1/convert/parse \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *210101*1200*U*00501*000000001*0*P*:~GS*HP*SENDER*RECEIVER*20210101*1200*1*X*005010~ST*837*0001~BHT*0019*00*CLM001*20210101*1200*CH~SE*3*0001~GE*1*1~IEA*1*000000001~"}'
```

**Response:**
```json
{
  "sourceFormat": "X12",
  "documentType": "837",
  "documentName": "Health Care Claim",
  "version": "005010",
  "senderId": "SENDER",
  "receiverId": "RECEIVER",
  "documentDate": "210101",
  "controlNumber": "000000001",
  "segments": [...],
  "businessData": {
    "transactionType": "837",
    "segmentCount": 6
  }
}
```

### 5. Explain EDI in Plain English

**POST** `/api/v1/convert/explain`

```bash
curl -X POST http://localhost:8095/api/v1/convert/explain \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*          *00*          *ZZ*CLINIC01       *ZZ*BCBSFL         *210115*0800*U*00501*000000001*0*P*:~GS*HP*CLINIC01*BCBSFL*20210115*0800*1*X*005010~ST*837*0001*005010X222A1~BHT*0019*00*CLM20210101*20210115*0800*CH~SE*3*0001~GE*1*1~IEA*1*000000001~"}'
```

**Response:**
```json
{
  "sourceFormat": "X12",
  "documentType": "837",
  "documentName": "Health Care Claim",
  "summary": "This is an ANSI X12 837 — Health Care Claim...",
  "totalSegments": 6,
  "segments": [
    {
      "segmentNumber": 1,
      "segmentId": "ISA",
      "explanation": "📨 **Message envelope** — Sent by **CLINIC01** to **BCBSFL** on Jan 15, 2021"
    }
  ],
  "tip": "💡 **Tip:** X12 uses * as field separator and ~ as segment terminator..."
}
```

### 6. Validate EDI

**POST** `/api/v1/convert/validate`

```bash
curl -X POST http://localhost:8095/api/v1/convert/validate \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*          *00*          *ZZ*SENDER*ZZ*RECEIVER*210101*1200*U*00501*1*0*P*:~"}'
```

**Response:**
```json
{
  "valid": false,
  "format": "X12",
  "errors": 1,
  "warnings": 2,
  "totalSegments": 1,
  "issues": [
    {
      "severity": "ERROR",
      "segment": "ISA",
      "lineNumber": 1,
      "problem": "Missing GS/GE functional group envelope",
      "fix": "Add GS and GE segments",
      "example": "GS*HP*SENDER*RECEIVER*20210101*1200*1*X*005010~"
    }
  ],
  "verdict": "❌ Invalid — errors must be fixed"
}
```

### 7. Self-Heal EDI (Auto-Fix Errors)

**POST** `/api/v1/convert/heal`

```bash
curl -X POST http://localhost:8095/api/v1/convert/heal \
  -H "Content-Type: application/json" \
  -d '{
    "content": "ISA*00*          *00*          *ZZ*SENDER*ZZ*RECEIVER*210101*1200*U*00501*1*0*P*:\nGS*HP*SENDER*RECEIVER*20210101*1200*1*X*005010\nST*837*0001\nSE*2*0001\nGE*1*1\nIEA*1*000000001",
    "format": "X12"
  }'
```

**Response:**
```json
{
  "wasHealed": true,
  "issuesFound": 3,
  "issuesFixed": 3,
  "format": "X12",
  "repairs": [
    {
      "issue": "Missing segment terminators (~)",
      "severity": "CRITICAL",
      "fix": "Added ~ at end of each segment",
      "autoFixed": true
    }
  ],
  "healedContent": "ISA*00*          *00*          *ZZ*SENDER*ZZ*RECEIVER*210101*1200*U*00501*1*0*P*:~\nGS*HP*SENDER*RECEIVER*20210101*1200*1*X*005010~\nST*837*0001~\nSE*2*0001~\nGE*1*1~\nIEA*1*000000001~",
  "verdict": "All 3 issues auto-fixed"
}
```

### 8. Semantic Diff (Compare Two EDI Documents)

**POST** `/api/v1/convert/diff`

```bash
curl -X POST http://localhost:8095/api/v1/convert/diff \
  -H "Content-Type: application/json" \
  -d '{
    "left": "ISA*00*...*ZZ*SENDER*ZZ*RECEIVER*210101*1200*U*00501*1*0*P*:~ST*850*0001~BEG*00*NE*PO-12345**20210115~SE*2*0001~IEA*1*1~",
    "right": "ISA*00*...*ZZ*SENDER*ZZ*RECEIVER*210201*1200*U*00501*2*0*P*:~ST*850*0001~BEG*00*NE*PO-12345**20210215~CTT*5~SE*3*0001~IEA*1*2~"
  }'
```

**Response:**
```json
{
  "totalChanges": 3,
  "segmentsAdded": 1,
  "segmentsModified": 2,
  "segmentsRemoved": 0,
  "changes": [
    {
      "type": "MODIFIED",
      "segmentId": "BEG",
      "description": "Segment BEG modified — 1 element changed"
    },
    {
      "type": "ADDED",
      "segmentId": "CTT",
      "description": "Segment CTT added"
    }
  ],
  "verdict": "Minor differences (3 changes)"
}
```

### 9. Compliance Scoring (0-100)

**POST** `/api/v1/convert/compliance`

```bash
curl -X POST http://localhost:8095/api/v1/convert/compliance \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *210101*1200*U*00501*000000001*0*P*:~GS*HP*SENDER*RECEIVER*20210101*1200*1*X*005010~ST*837*0001*005010X222A1~BHT*0019*00*CLM001*20210101*1200*CH~SE*3*0001~GE*1*1~IEA*1*000000001~"}'
```

**Response:**
```json
{
  "overallScore": 92,
  "grade": "A",
  "structureScore": 95,
  "elementScore": 90,
  "businessRuleScore": 88,
  "bestPracticeScore": 90,
  "verdict": "Excellent — production ready"
}
```

### 10. Generate EDI from Template

**GET** `/api/v1/convert/templates` — List available templates

```bash
curl http://localhost:8095/api/v1/convert/templates
```

**Response:**
```json
[
  {"id": "x12-837-claim", "name": "Healthcare Claim (837)", "format": "X12"},
  {"id": "x12-850-po", "name": "Purchase Order (850)", "format": "X12"},
  {"id": "x12-810-invoice", "name": "Invoice (810)", "format": "X12"},
  {"id": "edifact-orders", "name": "EDIFACT Purchase Order", "format": "EDIFACT"},
  {"id": "hl7-adt-a01", "name": "HL7 Patient Admission", "format": "HL7"},
  {"id": "swift-mt103", "name": "SWIFT MT103 Payment", "format": "SWIFT_MT"}
]
```

**POST** `/api/v1/convert/templates/{templateId}/generate`

```bash
curl -X POST http://localhost:8095/api/v1/convert/templates/x12-850-po/generate \
  -H "Content-Type: application/json" \
  -d '{
    "senderName": "ACME_CORP",
    "senderId": "1234567890",
    "receiverName": "SUPPLIER_INC",
    "receiverId": "9876543210",
    "poNumber": "PO-2026-0042",
    "orderDate": "20260405",
    "itemDescription": "Industrial Widget",
    "quantity": "500",
    "unitPrice": "12.50"
  }'
```

**Response:** Raw X12 EDI content (text/plain)

### 11. Natural Language → EDI Creation

**POST** `/api/v1/convert/create`

```bash
curl -X POST http://localhost:8095/api/v1/convert/create \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Create a purchase order for 500 widgets at $12.50 each from Acme Corp to RetailBuyer, PO#12345, due 2026-05-01"
  }'
```

**Response:**
```json
{
  "intent": "Purchase Order (X12 850)",
  "documentType": "X12_850",
  "generatedEdi": "ISA*00*          *00*          *ZZ*ACME_CORP      *ZZ*RETAILBUYER    *...",
  "extractedFields": {
    "quantity": "500",
    "unitPrice": "12.50",
    "buyerName": "Acme Corp",
    "sellerName": "RetailBuyer",
    "poNumber": "12345",
    "date": "2026-05-01"
  },
  "confidence": 87
}
```

### 12. Canonical Data Model

**POST** `/api/v1/convert/canonical`

Convert any EDI to a universal business-level JSON schema.

```bash
curl -X POST http://localhost:8095/api/v1/convert/canonical \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*...*ZZ*BUYER001*ZZ*SELLER001*...*~GS*PO*...*~ST*850*0001~BEG*00*NE*PO-12345**20260405~PO1*1*500*EA*12.50**VP*WIDGET-001~CTT*1~SE*4*0001~GE*1*1~IEA*1*1~"}'
```

**Response:**
```json
{
  "documentId": "a1b2c3d4-...",
  "type": "PURCHASE_ORDER",
  "sourceFormat": "X12",
  "header": {
    "documentNumber": "PO-12345",
    "documentDate": "2026-04-05",
    "currency": "USD"
  },
  "lineItems": [
    {
      "lineNumber": 1,
      "quantity": 500,
      "unitOfMeasure": "EA",
      "unitPrice": 12.50,
      "lineTotal": 6250.00,
      "productCode": "WIDGET-001"
    }
  ],
  "parties": [
    {"role": "BUYER", "id": "BUYER001"},
    {"role": "SELLER", "id": "SELLER001"}
  ],
  "totals": {
    "totalAmount": 6250.00,
    "currency": "USD"
  }
}
```

### 13. Stream Parse Large Files

**POST** `/api/v1/convert/stream/file`

For files too large to fit in memory (100GB+). O(1) memory usage.

```bash
curl -X POST http://localhost:8095/api/v1/convert/stream/file \
  -F "file=@/path/to/huge-batch.edi" \
  -F "format=AUTO"
```

**Response:**
```json
{
  "format": "X12",
  "totalSegments": 1500000,
  "totalBytes": 450000000,
  "durationMs": 12345,
  "errors": [],
  "metadata": {
    "senderId": "BIGBANK_001",
    "documentType": "835"
  }
}
```

### 14. AI Mapping Generator

**POST** `/api/v1/convert/mapping/generate`

Auto-generate mapping rules from source EDI + target JSON sample.

```bash
curl -X POST http://localhost:8095/api/v1/convert/mapping/generate \
  -H "Content-Type: application/json" \
  -d '{
    "source": "ISA*00*          *00*          *ZZ*ACME           *ZZ*PARTNER        *210101*1200*U*00501*1*0*P*:~",
    "target": "{\"header\": {\"sender\": \"ACME\", \"receiver\": \"PARTNER\", \"date\": \"2021-01-01\"}}"
  }'
```

**Response:**
```json
{
  "mappingId": "abc12345",
  "confidence": 82,
  "fieldsMatched": 3,
  "rules": [
    {
      "sourceField": "ISA*06",
      "targetField": "header.sender",
      "transform": "TRIM",
      "confidence": 95,
      "reasoning": "Exact value match: 'ACME'"
    }
  ]
}
```

### 15. Partner Profiles

Manage per-partner EDI formatting rules.

```bash
# Create a partner profile
curl -X POST http://localhost:8095/api/v1/convert/partners \
  -H "Content-Type: application/json" \
  -d '{
    "partnerId": "ACME_001",
    "partnerName": "Acme Corp",
    "preferredFormat": "X12",
    "preferredVersion": "005010",
    "senderQualifier": "ZZ",
    "senderId": "ACME_SENDER"
  }'

# Auto-generate profile from sample EDI
curl -X POST http://localhost:8095/api/v1/convert/partners/NEWPARTNER_001/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "partnerName": "New Partner Inc",
    "content": "ISA*00*...*~"
  }'

# Apply partner rules to outgoing EDI
curl -X POST http://localhost:8095/api/v1/convert/partners/ACME_001/apply \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*...*~"}'
```

### 16. Trained Map Conversion

**POST** `/api/v1/convert/trained`

Convert EDI using a trained partner-specific map (fetched from AI Engine).

```bash
curl -X POST http://localhost:8095/api/v1/convert/trained \
  -H "Content-Type: application/json" \
  -H "X-Internal-Key: internal_control_secret" \
  -d '{
    "content": "ISA*00*...",
    "targetFormat": "JSON",
    "partnerId": "acme"
  }'
```

### 17. Test Custom Mappings

**POST** `/api/v1/convert/test-mappings`

Test field mappings against sample EDI without persisting anything.

```bash
curl -X POST http://localhost:8095/api/v1/convert/test-mappings \
  -H "Content-Type: application/json" \
  -d '{
    "sourceContent": "ISA*00*          *00*          *ZZ*ACME*ZZ*PARTNER*...",
    "targetFormat": "JSON",
    "fieldMappings": [
      {"sourceField": "BEG*03", "targetField": "poNumber", "transform": "DIRECT", "confidence": 100},
      {"sourceField": "NM1*03", "targetField": "buyerName", "transform": "TRIM", "confidence": 95}
    ]
  }'
```

**Response:**
```json
{
  "output": "{\"poNumber\":\"PO-123\",\"buyerName\":\"Acme Corp\"}",
  "mapKey": "custom-test",
  "mapVersion": 0,
  "mapConfidence": 0,
  "fieldsApplied": 2,
  "fieldsSkipped": 0,
  "totalMappings": 2
}
```

### 18. List Supported Formats

**GET** `/api/v1/convert/formats`

```bash
curl http://localhost:8095/api/v1/convert/formats
```

---

## Integration Examples

### Python
```python
import requests

# Convert X12 to JSON
response = requests.post("http://localhost:8095/api/v1/convert/convert", json={
    "content": open("invoice.edi").read(),
    "target": "JSON"
})
invoice_json = response.json()

# Upload and convert file
with open("claims.edi", "rb") as f:
    response = requests.post(
        "http://localhost:8095/api/v1/convert/convert/file",
        files={"file": f},
        params={"target": "CSV"}
    )
    csv_output = response.text
```

### Node.js
```javascript
const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

// Convert EDI string
const { data } = await axios.post('http://localhost:8095/api/v1/convert/convert', {
  content: fs.readFileSync('invoice.edi', 'utf8'),
  target: 'JSON'
});

// Upload file
const form = new FormData();
form.append('file', fs.createReadStream('claims.edi'));
const result = await axios.post(
  'http://localhost:8095/api/v1/convert/convert/file?target=XML',
  form,
  { headers: form.getHeaders() }
);
```

### Java
```java
RestTemplate rest = new RestTemplate();

// Convert EDI string
Map<String, String> request = Map.of(
    "content", ediContent,
    "target", "JSON"
);
String json = rest.postForObject(
    "http://localhost:8095/api/v1/convert/convert",
    request, String.class
);

// Upload file
MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
body.add("file", new FileSystemResource(Path.of("claims.edi")));
body.add("target", "CSV");
String csv = rest.postForObject(
    "http://localhost:8095/api/v1/convert/convert/file",
    new HttpEntity<>(body), String.class
);
```

### cURL Pipeline
```bash
# Detect → Validate → Heal → Convert pipeline
FORMAT=$(curl -s -X POST http://localhost:8095/api/v1/convert/detect \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat input.edi)\"}" | jq -r '.format')

echo "Detected: $FORMAT"

# Heal any issues
HEALED=$(curl -s -X POST http://localhost:8095/api/v1/convert/heal \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat input.edi)\", \"format\": \"$FORMAT\"}" | jq -r '.healedContent')

# Convert to JSON
echo "$HEALED" | curl -s -X POST http://localhost:8095/api/v1/convert/convert \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat -)\", \"target\": \"JSON\"}" > output.json
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `server.port` | `8095` | HTTP port |
| `spring.servlet.multipart.max-file-size` | `100MB` | Max upload size |
| `edi.internal-format-version` | `1.0` | TIF format version |

No database, no message queue, no external services required.

---

## All Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/convert/detect` | Auto-detect EDI format |
| POST | `/api/v1/convert/parse` | Parse to structured document |
| POST | `/api/v1/convert/convert` | Convert to target format |
| POST | `/api/v1/convert/convert/file` | Upload and convert file |
| POST | `/api/v1/convert/explain` | Explain in plain English |
| POST | `/api/v1/convert/validate` | Validate structure |
| POST | `/api/v1/convert/heal` | Auto-fix errors |
| POST | `/api/v1/convert/diff` | Compare two documents |
| POST | `/api/v1/convert/compliance` | Score 0-100 compliance |
| POST | `/api/v1/convert/canonical` | Convert to canonical model |
| POST | `/api/v1/convert/stream` | Stream-parse large content |
| POST | `/api/v1/convert/stream/file` | Stream-parse large file |
| POST | `/api/v1/convert/create` | Natural language → EDI |
| GET | `/api/v1/convert/templates` | List templates |
| POST | `/api/v1/convert/templates/{id}/generate` | Generate from template |
| POST | `/api/v1/convert/mapping/generate` | Auto-generate mappings |
| POST | `/api/v1/convert/mapping/schema` | Map from schema description |
| GET | `/api/v1/convert/partners` | List partner profiles |
| GET | `/api/v1/convert/partners/{id}` | Get partner profile |
| POST | `/api/v1/convert/partners` | Create partner profile |
| PUT | `/api/v1/convert/partners/{id}` | Update partner profile |
| DELETE | `/api/v1/convert/partners/{id}` | Delete partner profile |
| POST | `/api/v1/convert/partners/{id}/analyze` | Auto-generate from sample |
| POST | `/api/v1/convert/partners/{id}/apply` | Apply partner rules |
| POST | `/api/v1/convert/trained` | Convert using trained/partner-specific map |
| POST | `/api/v1/convert/test-mappings` | Test custom field mappings |
| POST | `/api/v1/convert/trained/invalidate-cache` | Invalidate trained map cache |
| GET | `/api/v1/convert/formats` | List supported formats |
| GET | `/api/v1/convert/health` | Health check |

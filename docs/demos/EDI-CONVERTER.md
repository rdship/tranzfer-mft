# EDI Converter -- Demo & Quick Start Guide

> Convert, validate, explain, and generate EDI documents across 11 formats and 66 conversion paths -- zero dependencies, single JAR.

---

## What This Service Does

- **Auto-detects and converts** EDI documents between 11 input formats (X12, EDIFACT, TRADACOMS, SWIFT MT, HL7, NACHA, BAI2, ISO 20022, FIX, PEPPOL) and 6 output formats (JSON, XML, CSV, YAML, FLAT, TIF)
- **Explains EDI in plain English** -- turns cryptic segment codes into human-readable field descriptions
- **Validates with auto-fix** -- scores compliance 0-100 with A-F grades, and the self-healing engine auto-repairs 25+ common error patterns
- **Generates EDI from templates or natural language** -- describe what you need ("create a purchase order for 500 widgets to Acme Corp") and get valid EDI back
- **Compares documents semantically** -- field-level diff that shows business-level changes, not character-level noise

## What You Need (Prerequisites Checklist)

- [ ] **Operating System:** Linux, macOS, or Windows
- [ ] **Docker** (recommended) OR **Java 21 + Maven** -- [Install guide](PREREQUISITES.md)
- [ ] **curl** (for testing) -- pre-installed on Linux/macOS; Windows: `winget install cURL.cURL`
- [ ] **Port available:** `8095`

No database. No message broker. No external services. Just the JAR.

## Install & Start

### Method 1: Docker (Any OS -- 30 Seconds)

```bash
docker run -d \
  --name edi-converter \
  -p 8095:8095 \
  ghcr.io/rdship/tranzfer-mft/edi-converter:latest
```

### Method 2: From Source (Any OS)

```bash
# Clone (if not done)
git clone https://github.com/rdship/tranzfer-mft.git
cd tranzfer-mft

# Build just the EDI Converter
mvn clean package -DskipTests -pl edi-converter -am

# Run
java -jar edi-converter/target/edi-converter-1.0.0-SNAPSHOT.jar
```

You should see output ending with:

```
Started EdiConverterApplication in X.XXX seconds
```

## Verify It's Running

```bash
curl http://localhost:8095/api/v1/convert/health
```

Expected output:

```json
{
  "status": "UP",
  "service": "edi-converter",
  "version": "3.0",
  "inputFormats": 11,
  "outputFormats": 6,
  "totalConversionPaths": 66,
  "templates": 6,
  "partnerProfiles": 0,
  "features": [
    "convert", "explain", "validate", "templates", "generate",
    "canonical", "streaming", "self-healing", "semantic-diff",
    "compliance", "partner-profiles", "ai-mapping", "nl-create", "peppol"
  ]
}
```

---

## Demo 1: Detect, Parse, and Convert an X12 Purchase Order

This demo takes a real X12 850 Purchase Order and walks through detection, parsing, and conversion to JSON.

### Step 1: Create a sample X12 850 file

Linux / macOS:

```bash
cat > /tmp/sample-850.edi << 'EDIEOF'
ISA*00*          *00*          *ZZ*ACME-SUPPLY    *ZZ*GLOBALRETAIL   *240315*1200*^*00501*000000001*0*P*:~
GS*PO*ACME-SUPPLY*GLOBALRETAIL*20240315*1200*1*X*005010~
ST*850*0001~
BEG*00*NE*PO-2024-03-1587**20240315~
NM1*BY*2*Global Retail Inc~
NM1*SE*2*Acme Supply Co~
PO1*1*500*EA*12.50*PE*VP*WIDGET-A100~
PO1*2*200*EA*8.75*PE*VP*GASKET-B200~
CTT*2~
SE*9*0001~
GE*1*1~
IEA*1*000000001~
EDIEOF
```

Windows (PowerShell):

```powershell
@"
ISA*00*          *00*          *ZZ*ACME-SUPPLY    *ZZ*GLOBALRETAIL   *240315*1200*^*00501*000000001*0*P*:~
GS*PO*ACME-SUPPLY*GLOBALRETAIL*20240315*1200*1*X*005010~
ST*850*0001~
BEG*00*NE*PO-2024-03-1587**20240315~
NM1*BY*2*Global Retail Inc~
NM1*SE*2*Acme Supply Co~
PO1*1*500*EA*12.50*PE*VP*WIDGET-A100~
PO1*2*200*EA*8.75*PE*VP*GASKET-B200~
CTT*2~
SE*9*0001~
GE*1*1~
IEA*1*000000001~
"@ | Out-File -Encoding UTF8 C:\temp\sample-850.edi
```

### Step 2: Auto-detect the format

```bash
curl -s http://localhost:8095/api/v1/convert/detect \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat /tmp/sample-850.edi)\"}"
```

Windows (PowerShell):

```powershell
$body = @{ content = (Get-Content C:\temp\sample-850.edi -Raw) } | ConvertTo-Json
Invoke-RestMethod -Uri http://localhost:8095/api/v1/convert/detect -Method Post -ContentType "application/json" -Body $body
```

Expected output:

```json
{
  "format": "X12"
}
```

### Step 3: Parse into structured data

```bash
curl -s http://localhost:8095/api/v1/convert/parse \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat /tmp/sample-850.edi)\"}" | python3 -m json.tool
```

Expected output (abbreviated):

```json
{
  "format": "X12",
  "transactionType": "850",
  "segments": [
    { "id": "ISA", "elements": ["00", "          ", "00", "          ", "ZZ", "ACME-SUPPLY    ", ...] },
    { "id": "GS",  "elements": ["PO", "ACME-SUPPLY", "GLOBALRETAIL", ...] },
    { "id": "ST",  "elements": ["850", "0001"] },
    { "id": "BEG", "elements": ["00", "NE", "PO-2024-03-1587", "", "20240315"] },
    { "id": "PO1", "elements": ["1", "500", "EA", "12.50", "PE", "VP", "WIDGET-A100"] },
    { "id": "PO1", "elements": ["2", "200", "EA", "8.75", "PE", "VP", "GASKET-B200"] },
    ...
  ]
}
```

### Step 4: Convert to JSON

```bash
curl -s http://localhost:8095/api/v1/convert/convert \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat /tmp/sample-850.edi)\", \"target\": \"JSON\"}"
```

### Step 5: Convert to XML

```bash
curl -s http://localhost:8095/api/v1/convert/convert \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat /tmp/sample-850.edi)\", \"target\": \"XML\"}"
```

### Step 6: Convert to CSV

```bash
curl -s http://localhost:8095/api/v1/convert/convert \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat /tmp/sample-850.edi)\", \"target\": \"CSV\"}"
```

You can also convert by uploading a file directly:

```bash
curl -s http://localhost:8095/api/v1/convert/convert/file \
  -F "file=@/tmp/sample-850.edi" \
  -F "target=JSON"
```

---

## Demo 2: Full Production Workflow -- Validate, Heal, Explain, and Score

This simulates what happens in production: an EDI document arrives, you validate it, auto-fix any issues, explain it to a non-technical stakeholder, and score it for compliance.

### Step 1: Create a deliberately flawed EDI document

This X12 837 Healthcare Claim has common errors -- a wrong segment count and missing terminators:

```bash
cat > /tmp/broken-claim.edi << 'EDIEOF'
ISA*00*          *00*          *ZZ*CITYMEDICAL    *ZZ*BLUECROSS      *240315*1200*^*00501*000000001*0*P*:~
GS*HP*CITYMEDICAL*BLUECROSS*20240315*1200*1*X*005010X222A1~
ST*837*0001*005010X222A1~
BHT*0019*00*CLM20240315001*20240315*1200*CH~
NM1*85*2*City Medical Center****XX*1234567890~
NM1*40*2*Blue Cross Insurance****46*9876543210~
NM1*IL*1*Martinez*Sofia~
CLM*CLM20240315001*1500.00*11:B:1~
DTP*472*D8*20240315~
HI*ABK:J06.9~
SE*99*0001~
GE*1*1~
IEA*1*000000001~
EDIEOF
```

Note: `SE*99*0001` has an intentionally wrong count (99 instead of the real segment count).

### Step 2: Validate it

```bash
curl -s http://localhost:8095/api/v1/convert/validate \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat /tmp/broken-claim.edi)\"}" | python3 -m json.tool
```

Expected output (abbreviated):

```json
{
  "valid": false,
  "format": "X12",
  "errors": [...],
  "warnings": [...],
  "suggestions": [...]
}
```

### Step 3: Auto-heal the errors

```bash
curl -s http://localhost:8095/api/v1/convert/heal \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat /tmp/broken-claim.edi)\"}" | python3 -m json.tool
```

Expected output (abbreviated):

```json
{
  "wasHealed": true,
  "issuesFound": 1,
  "issuesFixed": 1,
  "repairs": [
    {
      "issue": "SE segment count mismatch",
      "severity": "CRITICAL",
      "fix": "Corrected segment count",
      "before": "SE*99*0001",
      "after": "SE*10*0001",
      "autoFixed": true
    }
  ],
  "format": "X12",
  "healedContent": "ISA*00*  ...(corrected document)...",
  "verdict": "Healed 1 of 1 issues"
}
```

### Step 4: Score for compliance

```bash
curl -s http://localhost:8095/api/v1/convert/compliance \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat /tmp/broken-claim.edi)\"}" | python3 -m json.tool
```

Expected output (abbreviated):

```json
{
  "overallScore": 72,
  "grade": "C",
  "structureScore": 85,
  "elementScore": 70,
  "businessRuleScore": 65,
  "bestPracticeScore": 70,
  "format": "X12",
  "documentType": "837",
  "totalChecks": 15,
  "passed": 10,
  "warnings": 3,
  "failures": 2,
  "recommendations": [
    "Fix SE segment count to match actual number of segments",
    "Consider adding NM1*87 (pay-to provider) for faster processing"
  ]
}
```

### Step 5: Explain it in plain English

```bash
curl -s http://localhost:8095/api/v1/convert/explain \
  -H "Content-Type: application/json" \
  -d "{\"content\": \"$(cat /tmp/broken-claim.edi)\"}" | python3 -m json.tool
```

Expected output (abbreviated):

```json
{
  "format": "X12",
  "transactionType": "837",
  "humanTitle": "Healthcare Claim",
  "segments": [
    {
      "id": "ISA",
      "humanName": "Interchange Control Header",
      "fields": [
        { "position": 1, "rawValue": "00", "meaning": "No authorization" },
        ...
      ]
    },
    {
      "id": "NM1",
      "humanName": "Name",
      "fields": [
        { "position": 1, "rawValue": "85", "meaning": "Billing Provider" },
        { "position": 3, "rawValue": "City Medical Center", "meaning": "Organization name" }
      ]
    },
    {
      "id": "CLM",
      "humanName": "Claim",
      "fields": [
        { "position": 1, "rawValue": "CLM20240315001", "meaning": "Claim ID" },
        { "position": 2, "rawValue": "1500.00", "meaning": "Total claim amount" }
      ]
    }
  ]
}
```

### Step 6: Compare two versions of a document (Semantic Diff)

```bash
cat > /tmp/claim-v2.edi << 'EDIEOF'
ISA*00*          *00*          *ZZ*CITYMEDICAL    *ZZ*BLUECROSS      *240315*1200*^*00501*000000001*0*P*:~
GS*HP*CITYMEDICAL*BLUECROSS*20240315*1200*1*X*005010X222A1~
ST*837*0001*005010X222A1~
BHT*0019*00*CLM20240315001*20240315*1200*CH~
NM1*85*2*City Medical Center****XX*1234567890~
NM1*40*2*Blue Cross Insurance****46*9876543210~
NM1*IL*1*Martinez*Sofia~
CLM*CLM20240315001*2250.00*11:B:1~
DTP*472*D8*20240315~
HI*ABK:M54.5~
SE*10*0001~
GE*1*1~
IEA*1*000000001~
EDIEOF
```

```bash
curl -s http://localhost:8095/api/v1/convert/diff \
  -H "Content-Type: application/json" \
  -d "{
    \"left\": \"$(cat /tmp/broken-claim.edi)\",
    \"right\": \"$(cat /tmp/claim-v2.edi)\"
  }" | python3 -m json.tool
```

Expected output (abbreviated):

```json
{
  "leftFormat": "X12",
  "rightFormat": "X12",
  "totalChanges": 3,
  "segmentsModified": 3,
  "segmentsAdded": 0,
  "segmentsRemoved": 0,
  "changes": [
    {
      "type": "MODIFIED",
      "segmentId": "CLM",
      "leftValue": "CLM*CLM20240315001*1500.00*11:B:1",
      "rightValue": "CLM*CLM20240315001*2250.00*11:B:1"
    },
    {
      "type": "MODIFIED",
      "segmentId": "HI",
      "leftValue": "HI*ABK:J06.9",
      "rightValue": "HI*ABK:M54.5"
    }
  ],
  "verdict": "2 business-level changes detected"
}
```

---

## Demo 3: Natural Language EDI Creation and Templates

### Option A: Create EDI from plain English

No EDI knowledge required. Describe what you need and get valid EDI back.

```bash
curl -s http://localhost:8095/api/v1/convert/create \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Send a purchase order for 500 widgets at $12.50 each to Acme Corp"
  }' | python3 -m json.tool
```

Expected output:

```json
{
  "intent": "Purchase Order (X12 850)",
  "documentType": "X12_850",
  "generatedEdi": "ISA*00*          *00*          *ZZ*BUYER001       ...",
  "extractedFields": {
    "quantity": "500",
    "unitPrice": "12.50",
    "sellerName": "Acme Corp",
    "buyerName": "DEFAULT_BUYER",
    "poNumber": "PO260405",
    "itemNumber": "ITEM001"
  },
  "confidence": 75,
  "warnings": ["Some fields used default values -- review and adjust as needed"],
  "explanation": "Generated a Purchase Order from DEFAULT_BUYER to Acme Corp for 500 items at $12.50 each (PO# PO260405)"
}
```

Try other natural language commands:

```bash
# Generate an invoice
curl -s http://localhost:8095/api/v1/convert/create \
  -H "Content-Type: application/json" \
  -d '{"text": "Create an invoice for $15000 from GlobalSupplier to RetailBuyer"}'

# Generate a healthcare claim
curl -s http://localhost:8095/api/v1/convert/create \
  -H "Content-Type: application/json" \
  -d '{"text": "Generate a healthcare claim for patient John Doe, $1500, diagnosis J06.9"}'

# Generate a SWIFT wire transfer
curl -s http://localhost:8095/api/v1/convert/create \
  -H "Content-Type: application/json" \
  -d '{"text": "Create a wire transfer for $50000 from SenderBank to ReceiverBank"}'
```

### Option B: Generate EDI from templates

#### List all available templates

```bash
curl -s http://localhost:8095/api/v1/convert/templates | python3 -m json.tool
```

Expected output (abbreviated):

```json
[
  {
    "id": "x12-837-claim",
    "name": "Healthcare Claim (837)",
    "format": "X12",
    "description": "Submit a medical claim to insurance",
    "fields": [
      { "id": "senderName", "label": "Your company name", "required": true },
      { "id": "senderId", "label": "Your EDI ID", "required": true },
      { "id": "receiverName", "label": "Insurance company name", "required": true },
      ...
    ]
  },
  { "id": "x12-850-po", "name": "Purchase Order (850)", ... },
  { "id": "x12-810-invoice", "name": "Invoice (810)", ... },
  { "id": "edifact-orders", "name": "EDIFACT Purchase Order (ORDERS)", ... },
  { "id": "hl7-adt-a01", "name": "HL7 Patient Admission (ADT^A01)", ... },
  { "id": "swift-mt103", "name": "SWIFT MT103 Payment", ... }
]
```

#### Generate a Purchase Order from a template

```bash
curl -s http://localhost:8095/api/v1/convert/templates/x12-850-po/generate \
  -H "Content-Type: application/json" \
  -d '{
    "buyerName": "Global Retail Inc",
    "buyerId": "GLOBALRETAIL",
    "sellerName": "Acme Supply Co",
    "sellerId": "ACMESUPPLY",
    "poNumber": "PO-2024-03-1587",
    "poDate": "20240315",
    "itemNumber": "WIDGET-A100",
    "quantity": "500",
    "unitPrice": "12.50"
  }'
```

Expected output:

```
ISA*00*          *00*          *ZZ*GLOBALRETAIL   *ZZ*ACMESUPPLY     *240315*1200*^*00501*000000001*0*P*:~
GS*PO*GLOBALRETAIL*ACMESUPPLY*20240315*1200*1*X*005010~
ST*850*0001~
BEG*00*NE*PO-2024-03-1587**20240315~
NM1*BY*2*Global Retail Inc~
NM1*SE*2*Acme Supply Co~
PO1*1*500*EA*12.50*PE*VP*WIDGET-A100~
CTT*1~
SE*8*0001~
GE*1*1~
IEA*1*000000001~
```

#### Generate a SWIFT MT103 payment

```bash
curl -s http://localhost:8095/api/v1/convert/templates/swift-mt103/generate \
  -H "Content-Type: application/json" \
  -d '{
    "senderBic": "CHASUS33XXX",
    "receiverBic": "BARCGB22XXX",
    "reference": "TXN-2024-0412",
    "amount": "USD50000,00",
    "orderingName": "Acme Corp",
    "orderingAccount": "1234567890",
    "beneficiaryName": "London Manufacturing Ltd",
    "beneficiaryAccount": "GB29NWBK60161331926819"
  }'
```

#### Generate an HL7 Patient Admission

```bash
curl -s http://localhost:8095/api/v1/convert/templates/hl7-adt-a01/generate \
  -H "Content-Type: application/json" \
  -d '{
    "sendingApp": "EPIC",
    "sendingFacility": "MERCY_GENERAL",
    "patientId": "MRN-98765",
    "patientLastName": "Johnson",
    "patientFirstName": "Emily",
    "dateOfBirth": "19850614",
    "gender": "F",
    "ward": "ICU"
  }'
```

---

## Demo 4: Integration Patterns

### Python

```python
import requests

EDI_URL = "http://localhost:8095/api/v1/convert"

# Convert X12 to JSON
edi_content = open("incoming-850.edi").read()
response = requests.post(f"{EDI_URL}/convert", json={
    "content": edi_content,
    "target": "JSON"
})
order_data = response.json()
print(f"Received purchase order: {order_data}")

# Validate before processing
validation = requests.post(f"{EDI_URL}/validate", json={
    "content": edi_content
}).json()
if not validation.get("valid"):
    # Auto-heal and retry
    healed = requests.post(f"{EDI_URL}/heal", json={
        "content": edi_content
    }).json()
    if healed["wasHealed"]:
        edi_content = healed["healedContent"]
        print(f"Auto-fixed {healed['issuesFixed']} issues")

# Score compliance
report = requests.post(f"{EDI_URL}/compliance", json={
    "content": edi_content
}).json()
print(f"Compliance: {report['grade']} ({report['overallScore']}/100)")
```

### Java

```java
import java.net.http.*;
import java.net.URI;

HttpClient client = HttpClient.newHttpClient();

// Convert EDI to JSON
String ediContent = Files.readString(Path.of("incoming-850.edi"));
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:8095/api/v1/convert/convert"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(
        "{\"content\": \"" + ediContent.replace("\"", "\\\"") + "\", \"target\": \"JSON\"}"
    ))
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
System.out.println("Converted: " + response.body());
```

### Node.js

```javascript
const EDI_URL = "http://localhost:8095/api/v1/convert";

// Create EDI from natural language
const response = await fetch(`${EDI_URL}/create`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    text: "Send a purchase order for 1000 units at $25 each to Acme Widgets"
  })
});

const result = await response.json();
console.log(`Generated ${result.documentType} with ${result.confidence}% confidence`);
console.log(result.generatedEdi);
```

---

## Demo 5: Test Custom Field Mappings

The EDI Converter can apply custom field mappings to EDI content -- used by the AI Engine's mapping correction workflow to test partner corrections before persisting them.

### Step 1: Test a mapping

```bash
curl -s -X POST http://localhost:8095/api/v1/convert/test-mappings \
  -H "Content-Type: application/json" \
  -d '{
    "sourceContent": "ISA*00*          *00*          *ZZ*ACME-SUPPLY    *ZZ*GLOBALRETAIL   *240315*1200*^*00501*000000001*0*P*:~GS*PO*ACME-SUPPLY*GLOBALRETAIL*20240315*1200*1*X*005010~ST*850*0001~BEG*00*NE*PO-2024-03-1587**20240315~NM1*BY*2*Global Retail Inc~NM1*SE*2*Acme Supply Co~PO1*1*500*EA*12.50*PE*VP*WIDGET-A100~CTT*2~SE*9*0001~GE*1*1~IEA*1*000000001~",
    "targetFormat": "JSON",
    "fieldMappings": [
      {"sourceField": "BEG*03", "targetField": "poNumber", "transform": "DIRECT", "confidence": 100},
      {"sourceField": "NM1*03", "targetField": "buyerName", "transform": "UPPERCASE", "confidence": 95},
      {"sourceField": "BEG*05", "targetField": "orderDate", "transform": "DATE_REFORMAT", "transformParam": "yyyyMMdd\u2192yyyy-MM-dd", "confidence": 90}
    ]
  }' | python3 -m json.tool
```

Expected response:

```json
{
    "output": "{\"poNumber\":\"PO-2024-03-1587\",\"buyerName\":\"GLOBAL RETAIL INC\",\"orderDate\":\"2024-03-15\"}",
    "mapKey": "custom-test",
    "mapVersion": 0,
    "mapConfidence": 0,
    "fieldsApplied": 3,
    "fieldsSkipped": 0,
    "totalMappings": 3
}
```

### Step 2: Try with nested target fields

```bash
curl -s -X POST http://localhost:8095/api/v1/convert/test-mappings \
  -H "Content-Type: application/json" \
  -d '{
    "sourceContent": "ISA*00*          *00*          *ZZ*ACME-SUPPLY    *ZZ*GLOBALRETAIL   *240315*1200*^*00501*000000001*0*P*:~GS*PO*ACME-SUPPLY*GLOBALRETAIL*20240315*1200*1*X*005010~ST*850*0001~BEG*00*NE*PO-2024-03-1587**20240315~NM1*BY*2*Global Retail Inc~PO1*1*500*EA*12.50*PE*VP*WIDGET-A100~SE*6*0001~GE*1*1~IEA*1*000000001~",
    "targetFormat": "JSON",
    "fieldMappings": [
      {"sourceField": "BEG*03", "targetField": "header.poNumber", "transform": "DIRECT", "confidence": 100},
      {"sourceField": "NM1*03", "targetField": "buyer.name", "transform": "TRIM", "confidence": 95},
      {"sourceField": "PO1*04", "targetField": "lineItems.unitPrice", "transform": "DIRECT", "confidence": 90}
    ]
  }' | python3 -m json.tool
```

The output JSON will have nested objects (`header.poNumber`, `buyer.name`).

**Supported transforms:** `DIRECT`, `TRIM`, `UPPERCASE`, `LOWERCASE`, `ZERO_PAD`, `DATE_REFORMAT`

---

## Demo 6: Cross-Format EDI Conversion (X12 → EDIFACT)

Convert an ANSI X12 850 Purchase Order into a UN/EDIFACT ORDERS message using the Canonical Data Model bridge.

### Step 6a: Convert X12 850 to EDIFACT ORDERS

```bash
curl -X POST http://localhost:8095/api/v1/convert/convert \
  -H "Content-Type: application/json" \
  -d '{
    "content": "ISA*00*          *00*          *ZZ*ACME           *ZZ*WIDGETCO       *210101*1200*U*00401*000000001*0*P*~\nGS*PO*ACME*WIDGETCO*20210101*1200*1*X*004010~\nST*850*0001~\nBEG*00*NE*PO-12345**20210101~\nNM1*BY*1*Smith*John~\nPO1*1*100*EA*5.00**VP*WIDGET-A~\nPO1*2*50*EA*12.50**VP*GADGET-B~\nSE*6*0001~\nGE*1*1~\nIEA*1*000000001~",
    "target": "EDIFACT"
  }'
```

**Expected response (EDIFACT ORDERS):**
```
UNA:+.? '
UNB+UNOA:4+ACME:ZZ+WIDGETCO:ZZ+260406:1200+123456'
UNH+1+ORDERS:D:01B:UN'
BGM+220+PO-12345+9'
DTM+137:20210101:102'
NAD+BY+++Smith John'
LIN+1++WIDGET-A:SA'
QTY+21:100'
PRI+AAA:5'
LIN+2++GADGET-B:SA'
QTY+21:50'
PRI+AAA:12.50'
UNS+S'
CNT+2:2'
UNT+12+1'
UNZ+1+123456'
```

### Step 6b: Convert EDIFACT back to X12

```bash
curl -X POST http://localhost:8095/api/v1/convert/convert \
  -H "Content-Type: application/json" \
  -d '{
    "content": "UNA:+.? '"'"'\nUNB+UNOA:4+SUPPLIER:ZZ+BUYER:ZZ+260406:0900+REF001'"'"'\nUNH+1+ORDERS:D:01B:UN'"'"'\nBGM+220+ORDER-789+9'"'"'\nDTM+137:20260406:102'"'"'\nNAD+BY+BUYER-ID::91++Acme Corp'"'"'\nLIN+1++PART-001:SA'"'"'\nQTY+21:200'"'"'\nPRI+AAA:7.50'"'"'\nUNS+S'"'"'\nMOA+86:1500'"'"'\nCNT+2:1'"'"'\nUNT+11+1'"'"'\nUNZ+1+REF001'"'"'",
    "target": "X12"
  }'
```

**Supported cross-format conversions via Canonical bridge:**
- X12 ↔ EDIFACT (Purchase Orders, Invoices, Ship Notices, Payments)
- X12/EDIFACT → HL7 (with clinical data)
- X12/EDIFACT → SWIFT MT (with financial data)
- Any input format → Any output format

---

## Demo 7: Compare Suite — Batch Conversion Output Comparison

Compare conversion outputs between two systems (e.g., migrating from old converter to TranzFer). The engine scans directories, matches files, runs field-level semantic diffs, and generates a summary report with fix recommendations.

### Step 6a: Prepare comparison (directory scan)

```bash
curl -X POST http://localhost:8095/api/v1/convert/compare/prepare \
  -H "Content-Type: application/json" \
  -d '{
    "sourceInputPath": "/data/old-system/input",
    "sourceOutputPath": "/data/old-system/output",
    "targetInputPath": "/data/new-system/input",
    "targetOutputPath": "/data/new-system/output",
    "reportOutputPath": "/data/reports"
  }'
```

**Expected response:**
```json
{
  "sessionId": "a1b2c3d4-...",
  "totalSourceFiles": 15,
  "totalTargetFiles": 14,
  "matchedPairs": 14,
  "filePairs": [
    {
      "sourceOutputFile": "/data/old-system/output/850_order1.json",
      "targetOutputFile": "/data/new-system/output/850_order1.json",
      "detectedFormat": "JSON",
      "sourceOutputSize": 1234,
      "targetOutputSize": 1240,
      "mapLabel": "JSON:850"
    }
  ],
  "unmatchedSource": ["850_order15.json"],
  "unmatchedTarget": [],
  "status": "READY",
  "message": "Found 14 matched file pairs. 1 source-only, 0 target-only. Ready to compare."
}
```

### Step 6b: Or prepare from CSV mapping file

```bash
# CSV format: sourceInputFile,sourceOutputFile,targetInputFile,targetOutputFile,mapLabel
curl -X POST http://localhost:8095/api/v1/convert/compare/prepare/upload \
  -F "mappingFile=@file-pairs.csv" \
  -F "reportOutputPath=/data/reports"
```

### Step 6c: Execute comparison (after reviewing preview)

```bash
curl -X POST http://localhost:8095/api/v1/convert/compare/execute/a1b2c3d4-...
```

**Expected response (abbreviated):**
```json
{
  "reportId": "a1b2c3d4-...",
  "generatedAt": "2026-04-06T12:00:00Z",
  "durationMs": 450,
  "totalPairsCompared": 14,
  "identicalPairs": 10,
  "differentPairs": 4,
  "errorPairs": 0,
  "mapSummaries": [
    {
      "mapLabel": "JSON:850",
      "totalFiles": 8,
      "identicalFiles": 6,
      "differentFiles": 2,
      "commonIssues": ["header.date (in 2 files)"],
      "patternInsights": ["All different files have the SAME differing fields — a single mapping fix will resolve all."],
      "verdict": "MOSTLY_GOOD"
    }
  ],
  "recommendations": [
    {
      "category": "TRANSFORM_DATE",
      "severity": "HIGH",
      "field": "header.date",
      "issue": "Field 'header.date' has date format difference: '20260406' vs '2026-04-06'",
      "fix": "Add DATE_REFORMAT transform. Use NL correction: \"Format header.date as yyyyMMdd\"",
      "affectedFiles": 4
    },
    {
      "category": "TRANSFORM_CASE",
      "severity": "MEDIUM",
      "field": "buyer.name",
      "issue": "Field 'buyer.name' has case difference: 'ACME CORP' vs 'acme corp'",
      "fix": "Add UPPERCASE or LOWERCASE transform. Use NL correction: \"Make buyer.name uppercase\"",
      "affectedFiles": 2
    }
  ],
  "overallVerdict": "NEEDS_WORK — 10/14 pairs identical (71%), 4 different, 0 errors. See recommendations."
}
```

### Step 6d: Get human-readable summary

```bash
curl http://localhost:8095/api/v1/convert/compare/reports/a1b2c3d4-.../summary
```

Returns a formatted text report with verdict, per-map breakdown, prioritized recommendations, and file-level details.

---

## Use Cases

- **ERP integration** -- Receive X12 850 purchase orders from trading partners, convert to JSON for your order management system
- **Healthcare claims processing** -- Parse X12 837 claims, validate against HIPAA rules, auto-fix common errors before submission
- **International trade** -- Convert between EDIFACT ORDERS and X12 850 for cross-border supply chain partners
- **Banking and payments** -- Parse SWIFT MT103 wire transfers, convert to ISO 20022 for modern payment rails
- **Hospital interoperability** -- Parse HL7 ADT/ORM messages, convert to JSON for FHIR-based systems
- **Supplier onboarding** -- New partner sends a sample EDI, use partner profiles to auto-configure their formatting rules
- **Compliance auditing** -- Score every outbound document before transmission, reject anything below a configurable threshold
- **Regression testing** -- Use semantic diff to compare EDI output before and after mapping changes
- **Non-technical users** -- Business analysts create EDI test data from natural language descriptions without learning the spec
- **ACH / NACHA processing** -- Parse batch payment files for payroll and vendor disbursements

## Supported Formats

### Input Formats (11)

| Format | Standard | Typical Use |
|--------|----------|-------------|
| X12 | ANSI ASC X12 | US B2B (purchase orders, invoices, claims) |
| EDIFACT | UN/EDIFACT | International trade |
| TRADACOMS | UK retail EDI | UK grocery/retail supply chain |
| SWIFT_MT | SWIFT FIN | International banking messages |
| HL7 | Health Level 7 v2.x | Healthcare system interoperability |
| NACHA | ACH file format | US bank-to-bank payments |
| BAI2 | Bank Admin Institute | Cash management reporting |
| ISO20022 | ISO 20022 XML | Modern payment messaging |
| FIX | Financial Information eXchange | Securities trading |
| PEPPOL | Pan-European Public Procurement | EU e-invoicing |
| AUTO | (auto-detect) | Let the service figure it out |

### Output Formats (6)

| Format | Content Type | Use |
|--------|-------------|-----|
| JSON | application/json | APIs, web apps, databases |
| XML | application/xml | Legacy systems, SOAP services |
| CSV | text/csv | Spreadsheets, data warehouses |
| YAML | application/yaml | Config files, human review |
| FLAT | text/plain | Fixed-width flat files |
| TIF | application/json | TranzFer Internal Format |

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8095` | HTTP port for the REST API |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `100MB` | Maximum upload file size |

The EDI Converter has no database, no message broker, and no external service dependencies. All configuration is minimal by design.

## API Reference (All Endpoints)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/convert/health` | Health check with feature list |
| `GET` | `/api/v1/convert/formats` | List all supported input/output formats |
| `GET` | `/api/v1/convert/templates` | List available EDI templates |
| `POST` | `/api/v1/convert/detect` | Auto-detect EDI format from content |
| `POST` | `/api/v1/convert/parse` | Parse EDI into structured segments |
| `POST` | `/api/v1/convert/convert` | Convert EDI to target format (JSON, XML, CSV, YAML, FLAT, TIF) |
| `POST` | `/api/v1/convert/convert/file` | Convert uploaded EDI file (multipart) |
| `POST` | `/api/v1/convert/explain` | Explain EDI document in plain English |
| `POST` | `/api/v1/convert/validate` | Validate EDI against standards |
| `POST` | `/api/v1/convert/heal` | Auto-detect and fix common EDI errors |
| `POST` | `/api/v1/convert/diff` | Semantic diff between two EDI documents |
| `POST` | `/api/v1/convert/compliance` | Score EDI compliance 0-100 with grade |
| `POST` | `/api/v1/convert/canonical` | Convert to universal Canonical Data Model |
| `POST` | `/api/v1/convert/stream` | Stream-parse large files (stats only) |
| `POST` | `/api/v1/convert/stream/file` | Stream-parse uploaded large file |
| `POST` | `/api/v1/convert/create` | Create EDI from natural language |
| `POST` | `/api/v1/convert/templates/{templateId}/generate` | Generate EDI from template + values |
| `POST` | `/api/v1/convert/mapping/generate` | Generate field mapping from source + target samples |
| `POST` | `/api/v1/convert/mapping/schema` | Generate field mapping from source + schema description |
| `GET` | `/api/v1/convert/partners` | List all partner profiles |
| `GET` | `/api/v1/convert/partners/{partnerId}` | Get a specific partner profile |
| `POST` | `/api/v1/convert/partners` | Create a new partner profile |
| `PUT` | `/api/v1/convert/partners/{partnerId}` | Update a partner profile |
| `DELETE` | `/api/v1/convert/partners/{partnerId}` | Delete a partner profile |
| `POST` | `/api/v1/convert/partners/{partnerId}/analyze` | Auto-generate profile from sample EDI |
| `POST` | `/api/v1/convert/partners/{partnerId}/apply` | Apply partner profile rules to a document |

## Cleanup

### Docker

```bash
docker stop edi-converter && docker rm edi-converter
```

### From source

Press `Ctrl+C` in the terminal where the JAR is running.

### Remove sample files

Linux / macOS:

```bash
rm -f /tmp/sample-850.edi /tmp/broken-claim.edi /tmp/claim-v2.edi
```

Windows (PowerShell):

```powershell
Remove-Item C:\temp\sample-850.edi, C:\temp\broken-claim.edi, C:\temp\claim-v2.edi -ErrorAction SilentlyContinue
```

## Troubleshooting

### Linux

**Port 8095 already in use:**

```bash
sudo lsof -i :8095
# Kill the process using that port, then retry
kill -9 <PID>
```

**"Permission denied" on Docker:**

```bash
sudo usermod -aG docker $USER
# Log out and back in, then retry
```

### macOS

**Port 8095 already in use:**

```bash
lsof -i :8095
kill -9 <PID>
```

**Java 21 not found (installed via Homebrew):**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
java -version  # should show 21.x
```

### Windows

**Port 8095 already in use:**

```powershell
netstat -ano | findstr :8095
taskkill /PID <PID> /F
```

**"java is not recognized":**

```powershell
# Ensure JAVA_HOME is set and on your PATH
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
```

**curl not found (older Windows):**

```powershell
winget install cURL.cURL
# Or use Invoke-RestMethod (PowerShell native):
Invoke-RestMethod -Uri http://localhost:8095/api/v1/convert/health
```

### All Platforms

**Large file uploads fail (413 error):**

Increase the max file size:

```bash
# Docker
docker run -e SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE=500MB ...

# From source
java -jar edi-converter-1.0.0-SNAPSHOT.jar --spring.servlet.multipart.max-file-size=500MB
```

**Conversion returns empty or unexpected output:**

1. Check the format was auto-detected correctly using `/detect`
2. Verify the EDI content is not truncated
3. Try `/validate` to see if there are structural issues
4. Use `/heal` to auto-fix common problems

## What's Next

- **[DMZ Proxy](DMZ-PROXY.md)** -- Secure your EDI endpoints behind an AI-powered security proxy
- **[Prerequisites](PREREQUISITES.md)** -- Full install guide for Docker and Java 21
- **[Architecture](../ARCHITECTURE.md)** -- How EDI Converter fits into the TranzFer MFT platform
- **[API Reference](../API-REFERENCE.md)** -- Complete API documentation for all services

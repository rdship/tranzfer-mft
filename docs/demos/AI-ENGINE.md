# AI Engine -- Demo & Quick Start Guide

> Real-time data classification, threat scoring, NLP command translation, anomaly detection, smart retry, file format detection, EDI translation, proxy intelligence, and self-driving infrastructure -- all in one service on port 8091.

---

## What This Service Does

- **Data Classification** -- Scans files for PCI (credit cards), PII (SSN, email, phone), PHI (ICD-10 codes, MRN), and FINANCIAL (SWIFT, IBAN) data using regex pattern matching. No LLM required.
- **Threat & Risk Scoring** -- Computes 0-100 risk scores per operation based on IP novelty, unusual hours, file size, rapid-fire activity, failed operations, and suspicious filenames.
- **NLP Command Translation** -- Converts natural language ("show me recent transfers") into structured CLI commands. Uses Claude API when available; falls back to keyword matching.
- **Anomaly Detection** -- Statistical z-score analysis on transfer volume, file sizes, timing, and missing expected transfers. Runs every 5 minutes.
- **Smart Retry & Auto-Remediation** -- Classifies failures (network, auth, storage, integrity, encryption, schema) and recommends RETRY, ALERT, QUARANTINE, or RE_REQUEST.
- **File Format Detection** -- Uses magic bytes to identify actual file format regardless of extension. Detects CSV, JSON, XML, GZIP, ZIP, PGP, PDF, PNG, TSV, and TEXT.
- **EDI/X12 Translation** -- Detects, validates, and translates X12 (837, 835, 850, 856, etc.) and SWIFT messages to JSON or CSV.
- **Proxy Intelligence** -- Real-time connection verdicts, IP reputation tracking, blocklist/allowlist management, geo-anomaly detection, and a full security dashboard.
- **Partner Profiles & SLA Forecasting** -- Learns per-partner behavior (active hours, file sizes, frequency) and predicts SLA breaches before they happen.
- **Self-Driving Infrastructure & Intelligence Network** -- Pre-scales services before traffic spikes, generates cost savings reports, and shares anonymized threat signals.

---

## What You Need (Prerequisites Checklist)

| Prerequisite | Required? | Notes |
|---|---|---|
| **Java 21** | Yes (source builds) | [Adoptium Temurin](https://adoptium.net/) recommended |
| **Docker** | Yes (container method) | Docker Desktop 4.x on macOS/Windows; `docker.io` on Linux |
| **PostgreSQL 16** | Yes | The AI engine uses Flyway migrations against the `filetransfer` database |
| **Maven 3.9+** | Yes (source builds) | For `mvn clean package` |
| **CLAUDE_API_KEY** | Optional | Enables full NLP with Claude. Without it, NLP uses keyword-based fallback. |
| **curl** | Yes | For all demo commands. Pre-installed on macOS/Linux. Windows: use Git Bash or WSL. |

---

## Install & Start

### Method 1: Docker Compose (Recommended -- with PostgreSQL)

This starts PostgreSQL and the AI engine together. Run from the repository root:

```bash
# Start only PostgreSQL + AI engine
docker compose up -d postgres ai-engine
```

Wait for both containers to become healthy:

```bash
docker compose ps
```

Expected output shows both `healthy`:

```
NAME                 STATUS
mft-postgres         running (healthy)
mft-ai-engine        running (healthy)
```

### Method 2: Docker (Standalone -- bring your own PostgreSQL)

If you already have PostgreSQL running on `localhost:5432`:

```bash
# Build the JAR first
cd ai-engine
mvn clean package -DskipTests

# Build and run the Docker image
docker build -t tranzfer-ai-engine .
docker run -d \
  --name ai-engine \
  -p 8091:8091 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  tranzfer-ai-engine
```

On Linux, replace `host.docker.internal` with your host IP or use `--network host`.

### Method 3: From Source (Any OS)

```bash
# 1. Ensure PostgreSQL is running with a 'filetransfer' database
psql -U postgres -c "CREATE DATABASE filetransfer;" 2>/dev/null || true

# 2. Build the entire project (shared module must be installed first)
cd /path/to/file-transfer-platform
mvn clean install -DskipTests

# 3. Run the AI engine
cd ai-engine
mvn spring-boot:run
```

To enable Claude NLP features:

```bash
CLAUDE_API_KEY=sk-ant-... mvn spring-boot:run
```

---

## Verify It's Running

```bash
curl -s http://localhost:8091/api/v1/ai/health | python3 -m json.tool
```

Expected response:

```json
{
    "status": "UP",
    "service": "ai-engine",
    "version": "3.0.0-phase3",
    "features": {
        "classification": true,
        "anomalyDetection": true,
        "nlp": true,
        "riskScoring": true,
        "smartRetry": true,
        "contentValidation": true,
        "threatScoring": true,
        "partnerProfiles": true,
        "predictiveSla": true,
        "autoRemediation": true,
        "nlMonitoring": true,
        "formatDetection": true
    }
}
```

Also verify proxy intelligence health:

```bash
curl -s http://localhost:8091/api/v1/proxy/health | python3 -m json.tool
```

Expected response:

```json
{
    "status": "UP",
    "service": "proxy-intelligence",
    "trackedIps": 0,
    "blockedIps": 0,
    "features": [
        "ip_reputation",
        "protocol_threat_detection",
        "connection_pattern_analysis",
        "geo_anomaly_detection",
        "adaptive_rate_limiting",
        "auto_blocklist",
        "brute_force_detection",
        "ddos_detection",
        "port_scan_detection",
        "slow_loris_detection"
    ]
}
```

---

## Demo 1: Data Classification -- Scan a File for Sensitive Data

The classification engine uses regex pattern matching to detect PCI, PII, PHI, and FINANCIAL data. No LLM needed.

### 1a. Classify inline text containing credit card and SSN

```bash
curl -s -X POST http://localhost:8091/api/v1/ai/classify/text \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Customer: John Doe\nSSN: 123-45-6789\nCard: 4111111111111111\nEmail: john@example.com",
    "filename": "customer-data.txt"
  }' | python3 -m json.tool
```

Expected response:

```json
{
    "filename": "classify__customer-data.txt",
    "scanned": true,
    "riskLevel": "CRITICAL",
    "riskScore": 75,
    "detections": [
        {
            "category": "PCI",
            "type": "CREDIT_CARD_VISA",
            "count": 1,
            "sample": "41**********11"
        },
        {
            "category": "PCI",
            "type": "CREDIT_CARD_GENERIC",
            "count": 1,
            "sample": "41**********11"
        },
        {
            "category": "PII",
            "type": "SSN",
            "count": 1,
            "sample": "12******89"
        },
        {
            "category": "PII",
            "type": "EMAIL",
            "count": 1,
            "sample": "jo************om"
        }
    ],
    "categoryCounts": {
        "PCI": 2,
        "PII": 2
    },
    "requiresEncryption": true,
    "blocked": true,
    "blockReason": "PCI data detected \u2014 encryption required before transfer",
    "note": null
}
```

Key things to observe:
- `riskLevel` is `CRITICAL` because PCI data was found.
- `blocked` is `true` because `ai.classification.block-unencrypted-pci` defaults to `true`.
- Sensitive values are masked in the `sample` field (e.g., `41**********11`).

### 1b. Classify a file upload (multipart)

```bash
# Create a test CSV with PHI data
echo 'patient_id,name,diagnosis_code,mrn
1001,Jane Smith,J45.0,MRN: 1234567
1002,Bob Jones,E11.9,MRN: 7654321' > /tmp/patient-data.csv

curl -s -X POST http://localhost:8091/api/v1/ai/classify \
  -F "file=@/tmp/patient-data.csv" | python3 -m json.tool
```

Expected response:

```json
{
    "filename": "classify__patient-data.csv",
    "scanned": true,
    "riskLevel": "HIGH",
    "riskScore": 70,
    "detections": [
        {
            "category": "PHI",
            "type": "ICD10_CODE",
            "count": 2,
            "sample": "J4**0"
        },
        {
            "category": "PHI",
            "type": "MRN",
            "count": 2,
            "sample": "MR*******67"
        }
    ],
    "categoryCounts": {
        "PHI": 4
    },
    "requiresEncryption": true,
    "blocked": false,
    "blockReason": null,
    "note": null
}
```

---

## Demo 2: Threat Scoring -- Evaluate Transfer Risk

The threat scoring endpoint combines multiple signals (IP, time, file size, filenames, recent activity) into a 0-100 score.

### 2a. Threat score for a suspicious operation

```bash
curl -s -X POST http://localhost:8091/api/v1/ai/threat-score \
  -H "Content-Type: application/json" \
  -d '{
    "username": "partner-acme",
    "ipAddress": "198.51.100.42",
    "action": "UPLOAD",
    "filename": "../../etc/passwd",
    "fileSizeBytes": 524288000
  }' | python3 -m json.tool
```

Expected response (first call -- IP is new):

```json
{
    "score": 65,
    "level": "HIGH",
    "recommendedAction": "REVIEW",
    "factors": [
        "First-time IP address: 198.51.100.42 (+25)",
        "Large file: 500.0 MB (+10)",
        "Suspicious filename pattern: path traversal attempt (+30)"
    ],
    "username": "partner-acme",
    "timestamp": "2026-04-05T12:00:00.000Z"
}
```

### 2b. Simple risk score (rule-based, no DB)

```bash
curl -s -X POST http://localhost:8091/api/v1/ai/risk-score \
  -H "Content-Type: application/json" \
  -d '{
    "newIp": true,
    "unusualHour": true,
    "fileSizeMb": 250,
    "containsPci": true,
    "containsPii": false
  }' | python3 -m json.tool
```

Expected response:

```json
{
    "score": 90,
    "level": "CRITICAL",
    "action": "BLOCK",
    "factors": [
        "Login from new IP (+30)",
        "Activity at unusual hour (+20)",
        "Large file >100MB (+15)",
        "Contains PCI data (+25)"
    ]
}
```

---

## Demo 3: NLP Command Translation

The NLP endpoint translates natural language into TranzFer CLI commands. Without `CLAUDE_API_KEY`, it uses keyword-based fallback.

### 3a. Translate a natural language command

```bash
curl -s -X POST http://localhost:8091/api/v1/ai/nlp/command \
  -H "Content-Type: application/json" \
  -d '{
    "query": "show me all accounts",
    "context": "admin dashboard"
  }' | python3 -m json.tool
```

Expected response (fallback mode -- no API key):

```json
{
    "understood": true,
    "isExplanation": false,
    "command": "accounts list",
    "response": "\u2192 accounts list"
}
```

### 3b. Suggest a flow from description

```bash
curl -s -X POST http://localhost:8091/api/v1/ai/nlp/suggest-flow \
  -H "Content-Type: application/json" \
  -d '{
    "description": "Decrypt PGP files, compress with gzip, then route to destination"
  }' | python3 -m json.tool
```

Expected response (fallback mode):

```json
{
    "success": true,
    "flowDefinition": {
        "name": "auto-flow-1234",
        "description": "Decrypt PGP files, compress with gzip, then route to destination",
        "steps": [
            { "type": "DECRYPT_PGP", "config": {}, "order": 0 },
            { "type": "COMPRESS_GZIP", "config": {}, "order": 1 },
            { "type": "ROUTE", "config": {}, "order": 2 }
        ]
    },
    "explanation": "Generated from keyword matching. Set CLAUDE_API_KEY for intelligent flow design."
}
```

### 3c. Ask a natural language monitoring question

```bash
curl -s -X POST http://localhost:8091/api/v1/ai/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "How many files transferred today?"}' | python3 -m json.tool
```

Expected response:

```json
{
    "answer": "Today: 0 file transfers."
}
```

The answer reflects real database state. Try other questions:

```bash
# "Are there any stuck transfers?"
curl -s -X POST http://localhost:8091/api/v1/ai/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "Are there any stuck transfers?"}'

# "Any SLA risks?"
curl -s -X POST http://localhost:8091/api/v1/ai/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "Any SLA risks?"}'
```

---

## Demo 4: Smart Retry -- Failure Classification

The smart retry engine classifies failure messages and recommends an action instead of blind retrying.

```bash
# Transient network error -> RETRY with backoff
curl -s -X POST http://localhost:8091/api/v1/ai/smart-retry \
  -H "Content-Type: application/json" \
  -d '{
    "errorMessage": "Connection timed out after 30000ms",
    "filename": "daily-report.csv",
    "retryCount": 1
  }' | python3 -m json.tool
```

Expected response:

```json
{
    "action": "RETRY",
    "delaySeconds": 60,
    "reason": "Transient network error \u2014 retry with backoff",
    "failureCategory": "NETWORK_TRANSIENT"
}
```

Try different error types:

```bash
# Auth error -> ALERT_NO_RETRY
curl -s -X POST http://localhost:8091/api/v1/ai/smart-retry \
  -H "Content-Type: application/json" \
  -d '{"errorMessage": "Permission denied (publickey)", "filename": "data.csv", "retryCount": 0}'
```

```json
{
    "action": "ALERT_NO_RETRY",
    "delaySeconds": 0,
    "reason": "Authentication/permission error \u2014 retrying won't help. Alert admin.",
    "failureCategory": "AUTH_FAILURE"
}
```

```bash
# Disk full -> RETRY_DELAYED (10 min wait)
curl -s -X POST http://localhost:8091/api/v1/ai/smart-retry \
  -H "Content-Type: application/json" \
  -d '{"errorMessage": "No space left on device", "filename": "backup.tar.gz", "retryCount": 2}'
```

```json
{
    "action": "RETRY_DELAYED",
    "delaySeconds": 600,
    "reason": "Storage full \u2014 wait 10 min for cleanup, then retry",
    "failureCategory": "STORAGE_FULL"
}
```

```bash
# Corrupt file -> RE_REQUEST
curl -s -X POST http://localhost:8091/api/v1/ai/smart-retry \
  -H "Content-Type: application/json" \
  -d '{"errorMessage": "Checksum mismatch: expected abc123, got def456", "filename": "payment.dat", "retryCount": 0}'
```

```json
{
    "action": "RE_REQUEST",
    "delaySeconds": 0,
    "reason": "File integrity failure \u2014 request re-send from source",
    "failureCategory": "INTEGRITY_FAILURE"
}
```

```bash
# Schema error -> QUARANTINE
curl -s -X POST http://localhost:8091/api/v1/ai/smart-retry \
  -H "Content-Type: application/json" \
  -d '{"errorMessage": "CSV parse error: unexpected column count at row 15", "filename": "invoices.csv", "retryCount": 0}'
```

```json
{
    "action": "QUARANTINE",
    "delaySeconds": 0,
    "reason": "File format/schema error \u2014 quarantine for manual review",
    "failureCategory": "FORMAT_ERROR"
}
```

---

## Demo 5: File Format Detection

Upload a file and the AI engine identifies its true format using magic bytes, regardless of the file extension.

### 5a. Detect a CSV file

```bash
echo 'id,name,amount
1,Alice,100.50
2,Bob,200.75' > /tmp/test-data.csv

curl -s -X POST http://localhost:8091/api/v1/ai/detect-format \
  -F "file=@/tmp/test-data.csv" | python3 -m json.tool
```

Expected response:

```json
{
    "filename": "detect__test-data.csv",
    "detectedFormat": "CSV",
    "declaredExtension": ".csv",
    "fileSizeBytes": 42,
    "extensionMismatch": false,
    "mismatchMessage": null,
    "suggestedAction": "Content validation available (VALIDATE step)"
}
```

### 5b. Detect a mislabeled file (JSON content with .csv extension)

```bash
echo '{"name": "test", "value": 42}' > /tmp/fake.csv

curl -s -X POST http://localhost:8091/api/v1/ai/detect-format \
  -F "file=@/tmp/fake.csv" | python3 -m json.tool
```

Expected response:

```json
{
    "filename": "detect__fake.csv",
    "detectedFormat": "JSON_OBJECT",
    "declaredExtension": ".csv",
    "fileSizeBytes": 31,
    "extensionMismatch": true,
    "mismatchMessage": "Extension says CSV but content is JSON_OBJECT",
    "suggestedAction": "WARNING: File content doesn't match extension. Verify with sender."
}
```

---

## Demo 6: Content Validation

Validate CSV structure, detect schema drift, and check column consistency.

```bash
# Create a CSV with issues: missing column, inconsistent row
echo 'id,name,amount,region
1,Alice,100.50,US
2,Bob,200.75
3,Carol,300.00,EU' > /tmp/bad-data.csv

curl -s -X POST http://localhost:8091/api/v1/ai/validate \
  -F "file=@/tmp/bad-data.csv" \
  -F "expectedColumns=id,name,amount,region,status" | python3 -m json.tool
```

Expected response:

```json
{
    "filename": "validate__bad-data.csv",
    "valid": false,
    "issues": [
        "Missing expected column: 'status'",
        "1 rows have inconsistent column count (expected 4)"
    ],
    "warnings": []
}
```

---

## Demo 7: EDI/X12 Detection and Translation

### 7a. Detect EDI type

```bash
curl -s -X POST http://localhost:8091/api/v1/edi/detect \
  -H "Content-Type: application/json" \
  -d '{
    "content": "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*U*00401*000000001*0*P*:~GS*HP*SENDER*RECEIVER*20230101*1200*1*X*004010X098A1~ST*837*0001~BHT*0019*00*12345*20230101*1200*CH~SE*4*0001~GE*1*1~IEA*1*000000001~"
  }' | python3 -m json.tool
```

Expected response:

```json
{
    "detected": true,
    "format": "X12",
    "transactionType": "837",
    "transactionName": "Health Care Claim"
}
```

### 7b. Validate EDI structure

```bash
curl -s -X POST http://localhost:8091/api/v1/edi/validate \
  -H "Content-Type: application/json" \
  -d '{
    "content": "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*U*00401*000000001*0*P*:~GS*HP*SENDER*RECEIVER*20230101*1200*1*X*004010X098A1~ST*837*0001~SE*2*0001~GE*1*1~IEA*1*000000001~"
  }' | python3 -m json.tool
```

Expected response:

```json
{
    "valid": true,
    "format": "X12",
    "transactionType": "837",
    "errors": [],
    "warnings": []
}
```

### 7c. Translate EDI to JSON

```bash
curl -s -X POST http://localhost:8091/api/v1/edi/translate/json \
  -H "Content-Type: application/json" \
  -d '{
    "content": "ISA*00*TEST*00*TEST~GS*HP*SENDER*RECEIVER~ST*837*0001~SE*2*0001~GE*1*1~IEA*1*000000001~"
  }' | python3 -m json.tool
```

Expected response:

```json
{
    "format": "X12",
    "transactionType": "837",
    "transactionName": "Health Care Claim",
    "segments": [
        { "segmentId": "ISA", "elements": ["00", "TEST", "00", "TEST"] },
        { "segmentId": "GS", "elements": ["HP", "SENDER", "RECEIVER"] },
        { "segmentId": "ST", "elements": ["837", "0001"] },
        { "segmentId": "SE", "elements": ["2", "0001"] },
        { "segmentId": "GE", "elements": ["1", "1"] },
        { "segmentId": "IEA", "elements": ["1", "000000001"] }
    ],
    "segmentCount": 6
}
```

### 7d. List supported EDI types

```bash
curl -s http://localhost:8091/api/v1/edi/types | python3 -m json.tool
```

Expected response:

```json
{
    "837": "Health Care Claim",
    "835": "Health Care Payment",
    "850": "Purchase Order",
    "856": "Ship Notice",
    "270": "Eligibility Inquiry",
    "271": "Eligibility Response",
    "997": "Functional Ack",
    "810": "Invoice"
}
```

---

## Demo 8: Proxy Intelligence -- Connection Verdict & IP Management

### 8a. Get a real-time connection verdict

```bash
curl -s -X POST http://localhost:8091/api/v1/proxy/verdict \
  -H "Content-Type: application/json" \
  -d '{
    "sourceIp": "203.0.113.50",
    "targetPort": 2222,
    "detectedProtocol": "SFTP"
  }' | python3 -m json.tool
```

Expected response:

```json
{
    "action": "ALLOW",
    "riskScore": 10,
    "reason": "New IP - monitoring",
    "ttlSeconds": 60,
    "signals": [],
    "rateLimit": {
        "maxConnectionsPerMinute": 60,
        "maxConcurrentConnections": 10,
        "maxBytesPerMinute": 1073741824
    },
    "metadata": {}
}
```

### 8b. Block an IP address

```bash
curl -s -X POST http://localhost:8091/api/v1/proxy/blocklist \
  -H "Content-Type: application/json" \
  -d '{"ip": "198.51.100.99", "reason": "known attacker"}' | python3 -m json.tool
```

Expected response:

```json
{
    "status": "blocked",
    "ip": "198.51.100.99"
}
```

### 8c. View the blocklist

```bash
curl -s http://localhost:8091/api/v1/proxy/blocklist | python3 -m json.tool
```

Expected response:

```json
{
    "count": 1,
    "ips": ["198.51.100.99"]
}
```

### 8d. Get IP intelligence

```bash
curl -s http://localhost:8091/api/v1/proxy/ip/203.0.113.50 | python3 -m json.tool
```

### 8e. View the security dashboard

```bash
curl -s http://localhost:8091/api/v1/proxy/dashboard | python3 -m json.tool
```

### 8f. View recent verdicts (audit trail)

```bash
curl -s "http://localhost:8091/api/v1/proxy/verdicts?limit=10" | python3 -m json.tool
```

---

## Demo 9: Observability, Partners, SLA, Self-Driving Infrastructure

### 9a. Get observability recommendations

```bash
curl -s http://localhost:8091/api/v1/ai/recommendations | python3 -m json.tool
```

Filter by category:

```bash
curl -s "http://localhost:8091/api/v1/ai/recommendations?category=RELIABILITY" | python3 -m json.tool
```

### 9b. Get health summary

```bash
curl -s http://localhost:8091/api/v1/ai/recommendations/summary | python3 -m json.tool
```

Expected response (fresh instance):

```json
{
    "recommendations": 0,
    "lastAnalysis": "pending"
}
```

### 9c. Get partner profiles

```bash
curl -s http://localhost:8091/api/v1/ai/partners | python3 -m json.tool
```

Returns an empty list `[]` on a fresh instance. After transfers have been recorded, returns learned profiles per partner.

### 9d. Get SLA forecasts

```bash
curl -s http://localhost:8091/api/v1/ai/sla/forecasts | python3 -m json.tool
```

### 9e. Get anomalies

```bash
curl -s http://localhost:8091/api/v1/ai/anomalies | python3 -m json.tool
```

### 9f. Get auto-remediation actions

```bash
curl -s http://localhost:8091/api/v1/ai/remediation/actions | python3 -m json.tool
```

### 9g. Self-driving infrastructure status

```bash
curl -s http://localhost:8091/api/v1/ai/self-driving/status | python3 -m json.tool
```

Expected response:

```json
{
    "totalActionsLogged": 0,
    "scaleUpActions": 0,
    "scaleDownActions": 0,
    "costSavingsTotal": 0.0,
    "status": "AUTONOMOUS"
}
```

### 9h. Intelligence network status

```bash
curl -s http://localhost:8091/api/v1/ai/intelligence/status | python3 -m json.tool
```

Expected response:

```json
{
    "localSignals": 0,
    "networkSignals": 0,
    "totalActive": 0,
    "status": "CONNECTED",
    "installationId": "a1b2c3d4e5f6",
    "capabilities": [
        "threat-sharing",
        "anomaly-broadcast",
        "sanctions-update",
        "benchmark-aggregation"
    ]
}
```

---

## Demo 10: Integration Pattern -- Python, Java, Node.js

### Python

```python
import requests

BASE = "http://localhost:8091/api/v1"

# Classify text for sensitive data
resp = requests.post(f"{BASE}/ai/classify/text", json={
    "content": "SSN: 123-45-6789, Card: 4111111111111111",
    "filename": "test.txt"
})
result = resp.json()
print(f"Risk: {result['riskLevel']} (score {result['riskScore']})")
print(f"Blocked: {result['blocked']}")
for d in result.get("detections", []):
    print(f"  {d['category']}/{d['type']}: {d['count']} matches")

# Smart retry decision
resp = requests.post(f"{BASE}/ai/smart-retry", json={
    "errorMessage": "Connection timed out",
    "filename": "report.csv",
    "retryCount": 2
})
decision = resp.json()
print(f"\nRetry decision: {decision['action']} (wait {decision['delaySeconds']}s)")
print(f"Reason: {decision['reason']}")
```

### Java

```java
import java.net.http.*;
import java.net.URI;

public class AiEngineDemo {
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final String BASE = "http://localhost:8091/api/v1";

    public static void main(String[] args) throws Exception {
        // Classify text
        var classifyReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/ai/classify/text"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""
                {"content": "Card: 4111111111111111", "filename": "test.txt"}
                """))
            .build();
        var resp = client.send(classifyReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Classification: " + resp.body());

        // Threat score
        var threatReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/ai/threat-score"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""
                {"username":"demo","ipAddress":"10.0.0.1","action":"UPLOAD",
                 "filename":"data.csv","fileSizeBytes":1048576}
                """))
            .build();
        resp = client.send(threatReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Threat score: " + resp.body());
    }
}
```

### Node.js

```javascript
const BASE = "http://localhost:8091/api/v1";

async function demo() {
  // Classify text
  const classifyResp = await fetch(`${BASE}/ai/classify/text`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      content: "SSN: 123-45-6789\nEmail: test@example.com",
      filename: "pii-file.txt"
    })
  });
  const classification = await classifyResp.json();
  console.log("Risk level:", classification.riskLevel);
  console.log("Detections:", classification.detections.length);

  // NLP command translation
  const nlpResp = await fetch(`${BASE}/ai/nlp/command`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ query: "show me recent transfers", context: null })
  });
  const nlp = await nlpResp.json();
  console.log("Translated command:", nlp.command);

  // Proxy verdict
  const verdictResp = await fetch(`${BASE}/proxy/verdict`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sourceIp: "192.168.1.100", targetPort: 2222 })
  });
  const verdict = await verdictResp.json();
  console.log("Verdict:", verdict.action, "Risk:", verdict.riskScore);
}

demo();
```

---

## Complete Endpoint Reference

### AiController -- `/api/v1/ai`

| Method | Path | Description |
|--------|------|-------------|
| POST | `/classify` | Classify uploaded file (multipart `file`) |
| POST | `/classify/text` | Classify inline text (`content`, `filename`) |
| GET | `/anomalies` | Get active anomalies (z-score based) |
| POST | `/nlp/command` | Translate natural language to CLI command (`query`, `context`) |
| POST | `/nlp/suggest-flow` | Generate flow definition from description (`description`) |
| POST | `/nlp/explain` | Explain a system event (`event`) |
| POST | `/risk-score` | Compute rule-based risk score (`newIp`, `unusualHour`, `fileSizeMb`, `containsPci`, `containsPii`) |
| POST | `/smart-retry` | Classify failure and recommend action (`errorMessage`, `filename`, `retryCount`) |
| POST | `/validate` | Validate file structure (multipart `file`, optional `expectedColumns`) |
| POST | `/threat-score` | Score operation threat level (`username`, `ipAddress`, `action`, `filename`, `fileSizeBytes`) |
| GET | `/partners` | List all learned partner profiles |
| GET | `/partners/{username}` | Get specific partner profile |
| POST | `/partners/{username}/check` | Check transfer deviations (`filename`, `fileSize`) |
| GET | `/sla/forecasts` | Get SLA breach predictions |
| GET | `/remediation/actions` | Get recent auto-remediation actions |
| POST | `/ask` | Ask natural language monitoring question (`question`) |
| POST | `/detect-format` | Detect true file format (multipart `file`) |
| GET | `/recommendations` | Get observability recommendations (optional `?category=`) |
| GET | `/recommendations/summary` | Get platform health summary |
| GET | `/self-driving/actions` | Get self-driving infrastructure actions |
| GET | `/self-driving/status` | Get autonomy status |
| GET | `/intelligence/signals` | Get active threat signals |
| GET | `/intelligence/status` | Get intelligence network status |
| GET | `/health` | Service health check |

### EdiController -- `/api/v1/edi`

| Method | Path | Description |
|--------|------|-------------|
| POST | `/detect` | Detect EDI type from content (`content`) |
| POST | `/validate` | Validate EDI structure (`content`) |
| POST | `/translate/json` | Translate EDI to JSON (`content`) |
| POST | `/translate/csv` | Translate EDI to CSV (`content`) |
| GET | `/types` | List supported EDI transaction types |

### ProxyIntelligenceController -- `/api/v1/proxy`

| Method | Path | Description |
|--------|------|-------------|
| POST | `/verdict` | Get connection verdict (`sourceIp`, `targetPort`, `detectedProtocol`) |
| POST | `/event` | Report a single threat event |
| POST | `/events` | Report batch of threat events |
| GET | `/blocklist` | View blocked IPs |
| POST | `/blocklist` | Add IP to blocklist (`ip`, `reason`) |
| DELETE | `/blocklist/{ip}` | Remove IP from blocklist |
| GET | `/allowlist` | View allowed IPs |
| POST | `/allowlist` | Add IP to allowlist (`ip`) |
| GET | `/ip/{ip}` | Get full IP intelligence |
| GET | `/dashboard` | Security dashboard |
| GET | `/verdicts` | Recent verdict audit trail (optional `?limit=`) |
| POST | `/geo/high-risk-countries` | Set high-risk countries (`countries` array) |
| POST | `/geo/tor-nodes` | Update Tor exit node list (`nodes` array) |
| POST | `/geo/vpn-prefixes` | Update VPN prefix list (`prefixes` array) |
| GET | `/geo/stats` | Get geo threat statistics |
| GET | `/health` | Proxy intelligence health check |

---

## Use Cases

1. **Compliance Gateway** -- Scan every file before transfer. Block PCI data unless encrypted. Flag PHI for healthcare compliance (HIPAA). Audit trail via classification results.
2. **Intelligent Retry Orchestration** -- Replace blind retry loops with smart retry. Route auth failures to alert queues. Quarantine malformed files. Auto-retry transient network errors with backoff.
3. **Partner SLA Monitoring** -- Learn each partner's normal delivery schedule. Get early warnings when partners drift late. Detect missing expected transfers.
4. **Security Operations Center** -- Use the proxy intelligence dashboard for real-time connection monitoring. Block suspicious IPs automatically. Track brute-force and DDoS patterns. Manage geo-risk.
5. **Natural Language Admin** -- Enable CLAUDE_API_KEY for operators to query the platform in English: "Which partner has the most failures?" or "Show transfers from last hour."
6. **EDI Integration Hub** -- Detect incoming EDI format (X12, SWIFT, EDIFACT) automatically. Validate structure. Translate to JSON or CSV for downstream systems.
7. **Self-Driving Operations** -- Let the AI engine pre-scale SFTP replicas before predicted traffic spikes. Automatically remediate stuck transfers. Generate cost optimization reports.

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `CLAUDE_API_KEY` | _(empty)_ | Anthropic API key for full NLP. Without it, NLP uses keyword fallback. |
| `CLAUDE_MODEL` | `claude-sonnet-4-20250514` | Claude model to use |
| `CLAUDE_BASE_URL` | `https://api.anthropic.com` | Claude API base URL |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier |
| `CLUSTER_HOST` | `ai-engine` | Host identifier for this instance |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | JWT signing secret |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key for control plane |
| `PROXY_ENABLED` | `false` | Enable outbound proxy |
| `PROXY_TYPE` | `HTTP` | Proxy type (HTTP/SOCKS) |
| `PROXY_HOST` | `dmz-proxy` | Proxy hostname |
| `PROXY_PORT` | `8088` | Proxy port |
| `TRACK_ID_PREFIX` | `TRZ` | Prefix for transfer tracking IDs |

---

## Cleanup

```bash
# Docker Compose
docker compose down -v          # Stops containers, removes volumes

# Standalone Docker
docker stop ai-engine && docker rm ai-engine

# Remove test files
rm -f /tmp/patient-data.csv /tmp/test-data.csv /tmp/fake.csv /tmp/bad-data.csv
```

---

## Troubleshooting

### All Platforms

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connection refused` on 8091 | Service not started or still starting | Wait 15-20s for Spring Boot startup. Check `docker logs mft-ai-engine`. |
| `relation "..." does not exist` | Flyway migrations not applied | Ensure the database exists: `psql -U postgres -c "CREATE DATABASE filetransfer;"`. Then restart the service. |
| `HikariPool` connection errors | PostgreSQL not reachable | Verify PostgreSQL is running: `pg_isready -h localhost -p 5432`. Check `DATABASE_URL`. |
| NLP returns `"understood": false` | No Claude API key and unrecognized keywords | Set `CLAUDE_API_KEY` for full NLP, or use recognized keywords (status, accounts, flows, recent, services, logs). |
| Classification returns `"scanned": false` | File too large or binary | Default max scan size is 100 MB (set via `ai.classification.max-scan-size-mb`). Binary files scan first 10 KB only. |

### Linux

```bash
# If port 8091 is already in use
sudo ss -tlnp | grep 8091
# Kill the process or change the port:
SERVER_PORT=9091 mvn spring-boot:run
```

### macOS

```bash
# If Docker Desktop is not running
open -a Docker
# Wait for Docker to start, then retry docker compose up
```

### Windows (PowerShell / Git Bash)

```powershell
# Use Git Bash for curl commands, or PowerShell equivalent:
Invoke-RestMethod -Uri http://localhost:8091/api/v1/ai/health | ConvertTo-Json

# If port conflict:
netstat -ano | findstr :8091
```

---

## What's Next

- **Enable Claude NLP** -- Set `CLAUDE_API_KEY` to unlock full natural language support for command translation, flow design, and event explanation.
- **Connect to the full platform** -- Run `docker compose up -d` to start all TranzFer services. The AI engine reads transfer records, audit logs, and partner data from the shared PostgreSQL database.
- **Set up the proxy pipeline** -- Deploy the DMZ proxy (`dmz-proxy` service) and configure it to call `/api/v1/proxy/verdict` for every inbound connection.
- **Explore the Analytics Service** -- See `docs/demos/ANALYTICS-SERVICE.md` for the dashboard, predictions, time series, and alert rules that complement the AI engine.
- **Build custom integrations** -- Use the Python/Java/Node.js examples above as starting points to integrate classification, threat scoring, or NLP into your own workflows.

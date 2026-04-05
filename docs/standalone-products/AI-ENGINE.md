# AI Engine — Standalone Product Guide

> **Intelligence-as-a-service.** Data classification, anomaly detection, threat scoring, NLP command processing, partner profiling, and predictive SLA — all via REST API.

**Port:** 8091 | **Dependencies:** PostgreSQL | **Auth:** None | **Optional:** Claude API key for enhanced NLP

---

## Why Use This

- **Data classification** — Detect PCI, PII, PHI, and financial data in any file
- **Anomaly detection** — Statistical z-score analysis on transfer patterns
- **Threat scoring** — Risk-score any user action (IP, time, file, behavior)
- **NLP commands** — Natural language → platform commands
- **Partner profiling** — Learn partner behavior and detect deviations
- **Proxy intelligence** — Real-time connection verdicts (ALLOW/BLOCK/THROTTLE)
- **Predictive SLA** — Forecast SLA breaches before they happen

---

## Quick Start

```bash
docker compose up -d postgres ai-engine
curl http://localhost:8091/api/v1/ai/health
```

```json
{
  "status": "UP",
  "service": "ai-engine",
  "features": {
    "classification": true,
    "anomalyDetection": true,
    "nlp": true,
    "proxyIntelligence": true,
    "partnerProfiles": true,
    "predictiveSla": true,
    "autoRemediation": true,
    "selfDrivingInfra": true
  }
}
```

---

## API Reference — Data Classification

### Classify File Content

**POST** `/api/v1/ai/classify`

Upload a file and detect sensitive data (PCI card numbers, PII, PHI, financial data).

```bash
curl -X POST http://localhost:8091/api/v1/ai/classify \
  -F "file=@/path/to/customer-data.csv"
```

**Response:**
```json
{
  "filename": "customer-data.csv",
  "scanned": true,
  "riskLevel": "HIGH",
  "riskScore": 85,
  "detections": [
    {"category": "PCI", "type": "CREDIT_CARD", "count": 23, "sample": "4111-XXXX-XXXX-1111"},
    {"category": "PII", "type": "SSN", "count": 150, "sample": "XXX-XX-6789"},
    {"category": "PII", "type": "EMAIL", "count": 150, "sample": "j***@example.com"}
  ],
  "categoryCounts": {"PCI": 23, "PII": 300},
  "requiresEncryption": true,
  "blocked": false,
  "note": "File contains PCI data — encryption recommended before transfer"
}
```

### Classify Text Content

**POST** `/api/v1/ai/classify/text`

```bash
curl -X POST http://localhost:8091/api/v1/ai/classify/text \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Patient: John Doe, DOB: 1985-03-15, SSN: 123-45-6789, Card: 4111111111111111",
    "filename": "patient-record.txt"
  }'
```

---

## API Reference — Threat Scoring

### Score a User Action

**POST** `/api/v1/ai/threat-score`

```bash
curl -X POST http://localhost:8091/api/v1/ai/threat-score \
  -H "Content-Type: application/json" \
  -d '{
    "username": "partner_acme",
    "ipAddress": "203.0.113.42",
    "action": "FILE_UPLOAD",
    "filename": "large-batch-export.zip",
    "fileSizeBytes": 524288000
  }'
```

**Response:**
```json
{
  "score": 72,
  "level": "HIGH",
  "recommendedAction": "CHALLENGE",
  "factors": [
    "New IP address not seen before (+15)",
    "Unusual hour for this account (+10)",
    "File size exceeds account average by 5x (+20)",
    "Compressed archive format (+5)"
  ],
  "username": "partner_acme",
  "timestamp": "2026-04-05T14:30:00Z"
}
```

### Calculate Risk Score

**POST** `/api/v1/ai/risk-score`

```bash
curl -X POST http://localhost:8091/api/v1/ai/risk-score \
  -H "Content-Type: application/json" \
  -d '{
    "newIp": true,
    "unusualHour": true,
    "fileSizeMb": 500,
    "containsPci": true,
    "containsPii": false
  }'
```

**Response:**
```json
{
  "score": 78,
  "level": "HIGH",
  "action": "CHALLENGE",
  "factors": ["New IP (+15)", "Unusual hour (+10)", "Large file (+20)", "PCI data (+25)"]
}
```

---

## API Reference — NLP Commands

### Natural Language Command

**POST** `/api/v1/ai/nlp/command`

```bash
curl -X POST http://localhost:8091/api/v1/ai/nlp/command \
  -H "Content-Type: application/json" \
  -d '{"query": "show me all failed transfers from last week"}'
```

**Response:**
```json
{
  "understood": true,
  "isExplanation": false,
  "command": "search transfers --status=FAILED --since=7d",
  "response": "Found 12 failed transfers in the last 7 days..."
}
```

### Suggest a File Flow

**POST** `/api/v1/ai/nlp/suggest-flow`

```bash
curl -X POST http://localhost:8091/api/v1/ai/nlp/suggest-flow \
  -H "Content-Type: application/json" \
  -d '{"description": "Encrypt all incoming CSV files, scan for PCI data, and forward to the SFTP archive server"}'
```

**Response:**
```json
{
  "success": true,
  "flowDefinition": {
    "name": "PCI CSV Processing",
    "steps": [
      {"type": "CLASSIFY", "config": {"categories": ["PCI"]}},
      {"type": "ENCRYPT", "config": {"algorithm": "AES_256_GCM"}},
      {"type": "FORWARD", "config": {"protocol": "SFTP", "destination": "archive-server"}}
    ]
  },
  "explanation": "This flow will classify incoming CSVs for PCI data, encrypt with AES-256, then forward via SFTP"
}
```

### Ask a Question

**POST** `/api/v1/ai/ask`

```bash
curl -X POST http://localhost:8091/api/v1/ai/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "Which partner has the highest error rate?"}'
```

---

## API Reference — Smart Retry

### Get Retry Decision

**POST** `/api/v1/ai/smart-retry`

```bash
curl -X POST http://localhost:8091/api/v1/ai/smart-retry \
  -H "Content-Type: application/json" \
  -d '{
    "errorMessage": "Connection reset by peer",
    "filename": "daily-batch.csv",
    "retryCount": 2
  }'
```

**Response:**
```json
{
  "action": "RETRY_DELAYED",
  "delaySeconds": 30,
  "reason": "Network transient error — connection reset. Retrying after backoff.",
  "failureCategory": "NETWORK_TRANSIENT"
}
```

**Possible actions:** `RETRY`, `RETRY_DELAYED`, `ALERT_NO_RETRY`, `RE_REQUEST`, `QUARANTINE`

---

## API Reference — Content Validation

### Validate File Structure

**POST** `/api/v1/ai/validate`

```bash
curl -X POST http://localhost:8091/api/v1/ai/validate \
  -F "file=@/path/to/data.csv" \
  -F "expectedColumns=name,email,amount"
```

**Response:**
```json
{
  "filename": "data.csv",
  "valid": false,
  "issues": ["Missing expected column: 'amount'", "Found 2 empty rows"],
  "warnings": ["Column 'email' has 5 null values"]
}
```

---

## API Reference — File Format Detection

### Detect Format Mismatches

**POST** `/api/v1/ai/detect-format`

```bash
curl -X POST http://localhost:8091/api/v1/ai/detect-format \
  -F "file=@/path/to/data.csv.xlsx"
```

**Response:**
```json
{
  "filename": "data.csv.xlsx",
  "detectedFormat": "XLSX",
  "declaredExtension": "xlsx",
  "fileSizeBytes": 45000,
  "extensionMismatch": false,
  "suggestedAction": "NONE"
}
```

---

## API Reference — Partner Profiles

### List All Partner Profiles

**GET** `/api/v1/ai/partners`

```bash
curl http://localhost:8091/api/v1/ai/partners
```

### Get Partner Profile

**GET** `/api/v1/ai/partners/{username}`

```bash
curl http://localhost:8091/api/v1/ai/partners/acme_corp
```

**Response:**
```json
{
  "username": "acme_corp",
  "totalTransfers": 4523,
  "avgTransfersPerDay": 45.2,
  "avgFileSizeBytes": 2048000,
  "maxFileSizeBytes": 52428800,
  "activeHoursUtc": [8, 9, 10, 11, 12, 13, 14, 15, 16],
  "activeDays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
  "commonFileExtensions": {"csv": 80, "xml": 15, "txt": 5},
  "errorRate": 0.02,
  "lastTransfer": "2026-04-05T14:00:00Z",
  "predictedNextDelivery": "2026-04-05T15:00:00Z",
  "healthScore": 95,
  "profileBuiltAt": "2026-04-05T10:00:00Z"
}
```

### Check for Deviations

**POST** `/api/v1/ai/partners/{username}/check`

```bash
curl -X POST http://localhost:8091/api/v1/ai/partners/acme_corp/check \
  -H "Content-Type: application/json" \
  -d '{"filename": "urgent-export.zip", "fileSize": 524288000}'
```

**Response:**
```json
["File size 500MB exceeds partner average by 250x", "ZIP format not typical for this partner"]
```

---

## API Reference — Proxy Intelligence

### Get Connection Verdict

**POST** `/api/v1/proxy/verdict`

Real-time verdict for incoming connections.

```bash
curl -X POST http://localhost:8091/api/v1/proxy/verdict \
  -H "Content-Type: application/json" \
  -d '{
    "sourceIp": "203.0.113.42",
    "targetPort": 2222,
    "detectedProtocol": "SFTP"
  }'
```

**Response:**
```json
{
  "action": "ALLOW",
  "riskScore": 15,
  "reason": "Known IP with good reputation",
  "ttlSeconds": 300,
  "signals": [],
  "rateLimit": null,
  "metadata": {"country": "US", "reputationScore": 85}
}
```

**Verdict actions:** `ALLOW`, `THROTTLE`, `CHALLENGE`, `BLOCK`, `BLACKHOLE`

### Manage Blocklist

```bash
# View blocklist
curl http://localhost:8091/api/v1/proxy/blocklist

# Block an IP
curl -X POST http://localhost:8091/api/v1/proxy/blocklist \
  -H "Content-Type: application/json" \
  -d '{"ip": "198.51.100.50", "reason": "Brute force attempt"}'

# Unblock
curl -X DELETE http://localhost:8091/api/v1/proxy/blocklist/198.51.100.50
```

### Security Dashboard

**GET** `/api/v1/proxy/dashboard`

```bash
curl http://localhost:8091/api/v1/proxy/dashboard
```

### IP Intelligence

**GET** `/api/v1/proxy/ip/{ip}`

```bash
curl http://localhost:8091/api/v1/proxy/ip/203.0.113.42
```

---

## API Reference — Predictive SLA

### Get SLA Forecasts

**GET** `/api/v1/ai/sla/forecasts`

```bash
curl http://localhost:8091/api/v1/ai/sla/forecasts
```

**Response:**
```json
[
  {
    "username": "acme_corp",
    "healthScore": 42,
    "overdue": true,
    "overdueMessage": "Last delivery 6 hours ago, expected every 2 hours",
    "deliveryDrift": 14400,
    "daysToSlaBreach": 2,
    "riskLevel": "HIGH"
  }
]
```

---

## API Reference — EDI Intelligence

### Detect EDI Format

**POST** `/api/v1/edi/detect`

```bash
curl -X POST http://localhost:8091/api/v1/edi/detect \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*          *00*          *ZZ*SENDER*ZZ*RECEIVER*..."}'
```

### Translate EDI to JSON

**POST** `/api/v1/edi/translate/json`

```bash
curl -X POST http://localhost:8091/api/v1/edi/translate/json \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*...*~"}'
```

### List EDI Types

**GET** `/api/v1/edi/types`

```bash
curl http://localhost:8091/api/v1/edi/types
```

---

## Integration Examples

### Python — Data Classification Pipeline
```python
import requests

def classify_and_route(filepath):
    """Classify file and decide routing based on sensitivity."""
    with open(filepath, "rb") as f:
        result = requests.post(
            "http://localhost:8091/api/v1/ai/classify",
            files={"file": f}
        ).json()

    if result["requiresEncryption"]:
        print(f"⚠️ {result['riskLevel']} risk — encrypting before transfer")
        # Route to encryption service first
        return "ENCRYPT_THEN_TRANSFER"
    elif result["blocked"]:
        print(f"🚫 BLOCKED: {result['blockReason']}")
        return "BLOCKED"
    else:
        return "DIRECT_TRANSFER"
```

### Java — Threat Scoring
```java
RestTemplate rest = new RestTemplate();

Map<String, Object> request = Map.of(
    "username", username,
    "ipAddress", clientIp,
    "action", "FILE_UPLOAD",
    "filename", filename,
    "fileSizeBytes", fileSize
);

Map<String, Object> score = rest.postForObject(
    "http://localhost:8091/api/v1/ai/threat-score",
    request, Map.class
);

int riskScore = (int) score.get("score");
if (riskScore > 70) {
    throw new SecurityException("High risk action blocked: score " + riskScore);
}
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `CLAUDE_API_KEY` | (empty) | Optional: Claude API key for enhanced NLP |
| `CLAUDE_MODEL` | `claude-sonnet-4-20250514` | Claude model for NLP |
| `ai.classification.max-scan-size-mb` | `100` | Max file size for classification |
| `ai.classification.block-unencrypted-pci` | `true` | Block unencrypted PCI data |
| `ai.anomaly.threshold-sigma` | `3.0` | Z-score threshold for anomalies |
| `ai.proxy.max-connections-per-ip-per-minute` | `60` | Per-IP rate limit |
| `server.port` | `8091` | HTTP port |

---

## All Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/ai/classify` | Classify file (upload) |
| POST | `/api/v1/ai/classify/text` | Classify text content |
| GET | `/api/v1/ai/anomalies` | Get detected anomalies |
| POST | `/api/v1/ai/nlp/command` | NLP command processing |
| POST | `/api/v1/ai/nlp/suggest-flow` | Suggest file flow |
| POST | `/api/v1/ai/nlp/explain` | Explain an event |
| POST | `/api/v1/ai/risk-score` | Calculate risk score |
| POST | `/api/v1/ai/smart-retry` | Get retry decision |
| POST | `/api/v1/ai/validate` | Validate file structure |
| POST | `/api/v1/ai/threat-score` | Score user action |
| POST | `/api/v1/ai/detect-format` | Detect file format |
| GET | `/api/v1/ai/partners` | List partner profiles |
| GET | `/api/v1/ai/partners/{username}` | Get partner profile |
| POST | `/api/v1/ai/partners/{username}/check` | Check for deviations |
| GET | `/api/v1/ai/sla/forecasts` | SLA breach forecasts |
| GET | `/api/v1/ai/remediation/actions` | Auto-remediation actions |
| POST | `/api/v1/ai/ask` | Ask a question (NLP) |
| GET | `/api/v1/ai/recommendations` | Get recommendations |
| GET | `/api/v1/ai/recommendations/summary` | Health summary |
| GET | `/api/v1/ai/self-driving/actions` | Self-driving actions |
| GET | `/api/v1/ai/self-driving/status` | Autonomy status |
| GET | `/api/v1/ai/intelligence/signals` | Threat signals |
| GET | `/api/v1/ai/health` | Health check |
| POST | `/api/v1/edi/detect` | Detect EDI format |
| POST | `/api/v1/edi/validate` | Validate EDI |
| POST | `/api/v1/edi/translate/json` | EDI → JSON |
| POST | `/api/v1/edi/translate/csv` | EDI → CSV |
| GET | `/api/v1/edi/types` | List EDI types |
| POST | `/api/v1/proxy/verdict` | Connection verdict |
| POST | `/api/v1/proxy/event` | Report threat event |
| POST | `/api/v1/proxy/events` | Report batch events |
| GET | `/api/v1/proxy/blocklist` | View blocklist |
| POST | `/api/v1/proxy/blocklist` | Block IP |
| DELETE | `/api/v1/proxy/blocklist/{ip}` | Unblock IP |
| GET | `/api/v1/proxy/allowlist` | View allowlist |
| POST | `/api/v1/proxy/allowlist` | Allow IP |
| GET | `/api/v1/proxy/ip/{ip}` | IP intelligence |
| GET | `/api/v1/proxy/dashboard` | Security dashboard |
| GET | `/api/v1/proxy/verdicts` | Recent verdicts |
| GET | `/api/v1/proxy/health` | Proxy health |

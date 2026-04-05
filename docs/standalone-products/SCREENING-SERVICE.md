# Screening Service — Standalone Product Guide

> **Sanctions screening as a service.** Screen files, names, and transactions against OFAC, EU, and UN sanctions lists using Jaro-Winkler fuzzy matching. Upload a CSV and get instant compliance results.

**Port:** 8092 | **Dependencies:** PostgreSQL | **Auth:** None

---

## Why Use This

- **Real-time OFAC/EU/UN screening** — Live download from US Treasury, built-in EU and UN lists
- **Fuzzy name matching** — Jaro-Winkler similarity catches misspellings and transliterations
- **CSV/TSV file scanning** — Upload a file, screen specific columns, get results in milliseconds
- **Configurable threshold** — Default 82% match sensitivity, adjust per your compliance needs
- **Auto-refresh** — Sanctions lists refresh every 6 hours automatically

---

## Quick Start

```bash
# Start PostgreSQL + Screening Service
docker compose up -d postgres screening-service

# Verify (sanctions lists load on startup)
curl http://localhost:8092/api/v1/screening/health
```

```json
{
  "status": "UP",
  "service": "screening-service",
  "sanctionsLists": {
    "OFAC_SDN": 1247,
    "EU_SANCTIONS": 4,
    "UN_SANCTIONS": 3
  },
  "lastRefresh": "2026-04-05T10:00:00Z"
}
```

---

## API Reference

### 1. Screen a File (CSV/TSV Upload)

**POST** `/api/v1/screening/scan`

Upload a CSV or TSV file and screen all or specific columns against sanctions lists.

```bash
curl -X POST http://localhost:8092/api/v1/screening/scan \
  -F "file=@/path/to/wire-transfers.csv" \
  -F "trackId=TRZ-WIRE-2026-001" \
  -F "account=compliance-dept" \
  -F "columns=sender_name" \
  -F "columns=beneficiary_name"
```

**Response:**
```json
{
  "id": "a1b2c3d4-...",
  "trackId": "TRZ-WIRE-2026-001",
  "filename": "wire-transfers.csv",
  "accountUsername": "compliance-dept",
  "outcome": "POSSIBLE_HIT",
  "recordsScanned": 150,
  "hitsFound": 2,
  "durationMs": 345,
  "actionTaken": "BLOCKED",
  "screenedAt": "2026-04-05T14:30:00Z",
  "hits": [
    {
      "matchedName": "ISLAMIC REVOLUTIONARY GUARD CORPS",
      "sanctionsListName": "IRAN",
      "sanctionsListSource": "OFAC_SDN",
      "matchScore": 0.89,
      "fileField": "beneficiary_name",
      "fileValue": "Islamic Revolutionary Guard",
      "lineNumber": 47
    },
    {
      "matchedName": "WAGNER GROUP",
      "sanctionsListName": "WAGNER",
      "sanctionsListSource": "EU_SANCTIONS",
      "matchScore": 0.95,
      "fileField": "sender_name",
      "fileValue": "Wagner Grp LLC",
      "lineNumber": 112
    }
  ]
}
```

### 2. Screen Inline Text/CSV

**POST** `/api/v1/screening/scan/text`

Screen CSV content directly without file upload.

```bash
curl -X POST http://localhost:8092/api/v1/screening/scan/text \
  -H "Content-Type: application/json" \
  -d '{
    "content": "sender,beneficiary,amount\nAcme Corp,Normal Company,5000\nShell LLC,Hezbollah Trading Co,15000\nTech Inc,Al Qaida Foundation,25000",
    "filename": "quick-check.csv",
    "trackId": "MANUAL-CHECK-001",
    "columns": ["beneficiary"]
  }'
```

**Response:**
```json
{
  "outcome": "HIT",
  "recordsScanned": 3,
  "hitsFound": 2,
  "actionTaken": "BLOCKED",
  "hits": [
    {
      "matchedName": "HIZBALLAH",
      "sanctionsListSource": "OFAC_SDN",
      "matchScore": 0.86,
      "fileField": "beneficiary",
      "fileValue": "Hezbollah Trading Co",
      "lineNumber": 2
    },
    {
      "matchedName": "AL QAIDA",
      "sanctionsListSource": "OFAC_SDN",
      "matchScore": 0.92,
      "fileField": "beneficiary",
      "fileValue": "Al Qaida Foundation",
      "lineNumber": 3
    }
  ]
}
```

### 3. Get Screening Result by Track ID

**GET** `/api/v1/screening/results/{trackId}`

```bash
curl http://localhost:8092/api/v1/screening/results/TRZ-WIRE-2026-001
```

### 4. Get Recent Results

**GET** `/api/v1/screening/results`

Returns the 50 most recent screening results.

```bash
curl http://localhost:8092/api/v1/screening/results
```

### 5. Get All Hits

**GET** `/api/v1/screening/hits`

Returns all results with outcome `HIT` or `POSSIBLE_HIT`.

```bash
curl http://localhost:8092/api/v1/screening/hits
```

### 6. Force Refresh Sanctions Lists

**POST** `/api/v1/screening/lists/refresh`

```bash
curl -X POST http://localhost:8092/api/v1/screening/lists/refresh
```

**Response:**
```json
{
  "status": "refreshed",
  "lists": {
    "OFAC_SDN": 1247,
    "EU_SANCTIONS": 4,
    "UN_SANCTIONS": 3
  },
  "lastRefresh": "2026-04-05T15:00:00Z"
}
```

### 7. Get Sanctions List Status

**GET** `/api/v1/screening/lists`

```bash
curl http://localhost:8092/api/v1/screening/lists
```

**Response:**
```json
{
  "lists": {
    "OFAC_SDN": 1247,
    "EU_SANCTIONS": 4,
    "UN_SANCTIONS": 3
  },
  "lastRefresh": "2026-04-05T10:00:00Z",
  "autoRefresh": "every 6 hours"
}
```

---

## Screening Logic

### Outcome Classification

| Outcome | Match Score | Meaning | Default Action |
|---------|------------|---------|----------------|
| **CLEAR** | < 0.82 | No matches found | PASSED |
| **POSSIBLE_HIT** | 0.82 – 0.94 | Fuzzy match, needs review | BLOCKED |
| **HIT** | ≥ 0.95 | Confirmed match | BLOCKED |
| **ERROR** | N/A | Processing failure | PASSED |

### Jaro-Winkler Algorithm

The service uses the **Jaro-Winkler similarity** algorithm, the industry standard for sanctions name matching:
- Handles misspellings: "Hezbollah" ↔ "Hizballah" (0.86 match)
- Handles transliterations: "Al Qaeda" ↔ "Al Qaida" (0.92 match)
- Boosts common prefixes (up to 4 characters)
- Returns 0.0 (no match) to 1.0 (exact match)

### Sanctions Sources

| Source | Entries | Auto-Refresh | URL |
|--------|---------|-------------|-----|
| **OFAC SDN** | ~1,200+ | Every 6 hours | `treasury.gov/ofac/downloads/sdn.csv` |
| **EU Sanctions** | Built-in | On startup | (configurable) |
| **UN Sanctions** | Built-in | On startup | (configurable) |

---

## Integration Examples

### Python
```python
import requests

# Screen a CSV file
with open("payments.csv", "rb") as f:
    result = requests.post(
        "http://localhost:8092/api/v1/screening/scan",
        files={"file": f},
        data={
            "trackId": "BATCH-2026-04-05",
            "columns": ["sender_name", "beneficiary_name"]
        }
    ).json()

if result["outcome"] in ("HIT", "POSSIBLE_HIT"):
    print(f"⚠️ BLOCKED: {result['hitsFound']} matches found")
    for hit in result["hits"]:
        print(f"  - {hit['fileValue']} matched {hit['matchedName']} "
              f"({hit['sanctionsListSource']}, score: {hit['matchScore']:.0%})")
else:
    print("✅ CLEAR: No sanctions matches")
```

### Java
```java
RestTemplate rest = new RestTemplate();

// Screen inline content
Map<String, Object> request = Map.of(
    "content", csvContent,
    "filename", "transactions.csv",
    "columns", List.of("beneficiary_name", "sender_name")
);

Map<String, Object> result = rest.postForObject(
    "http://localhost:8092/api/v1/screening/scan/text",
    request, Map.class
);

if ("HIT".equals(result.get("outcome"))) {
    throw new ComplianceException("Sanctions match detected");
}
```

### Node.js
```javascript
const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

// Screen a file
const form = new FormData();
form.append('file', fs.createReadStream('payments.csv'));
form.append('columns', 'beneficiary_name');

const { data } = await axios.post(
  'http://localhost:8092/api/v1/screening/scan',
  form,
  { headers: form.getHeaders() }
);

if (data.outcome === 'HIT' || data.outcome === 'POSSIBLE_HIT') {
  console.log(`Blocked: ${data.hitsFound} matches`);
  data.hits.forEach(h => console.log(`  ${h.fileValue} → ${h.matchedName}`));
}
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `SCREENING_THRESHOLD` | `0.82` | Jaro-Winkler match threshold (0.0–1.0) |
| `SCREENING_DEFAULT_ACTION` | `BLOCK` | Action on match: BLOCK or FLAG |
| `server.port` | `8092` | HTTP port |

---

## All Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/screening/scan` | Screen uploaded file |
| POST | `/api/v1/screening/scan/text` | Screen inline text/CSV |
| GET | `/api/v1/screening/results/{trackId}` | Get result by track ID |
| GET | `/api/v1/screening/results` | List recent results (top 50) |
| GET | `/api/v1/screening/hits` | List all hits |
| POST | `/api/v1/screening/lists/refresh` | Force refresh sanctions lists |
| GET | `/api/v1/screening/lists` | Get sanctions list status |
| GET | `/api/v1/screening/health` | Health check |

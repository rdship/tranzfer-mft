# Screening Service

> OFAC, EU, and UN sanctions screening with Jaro-Winkler fuzzy matching against live sanctions lists.

**Port:** 8092 | **Database:** PostgreSQL | **Required:** Optional

---

## Overview

The screening service scans file contents against international sanctions lists to ensure compliance:

- **OFAC SDN list** — Live download from US Treasury
- **EU Sanctions** — European Union sanctions list
- **UN Sanctions** — United Nations consolidated list
- **Fuzzy matching** — Jaro-Winkler similarity algorithm (configurable threshold)
- **CSV/TSV parsing** — Intelligent column detection for structured files
- **Auto-refresh** — Sanctions lists update every 6 hours
- **Configurable action** — BLOCK or FLAG on match

---

## Quick Start

```bash
docker compose up -d postgres screening-service

# Health check (includes sanctions list status)
curl http://localhost:8092/api/v1/screening/health

# Screen a file
curl -X POST http://localhost:8092/api/v1/screening/scan \
  -F "file=@customer-list.csv"
```

---

## API Endpoints

### Screening Operations

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/screening/scan` | Screen uploaded file |
| POST | `/api/v1/screening/scan/text` | Screen inline text/CSV |
| GET | `/api/v1/screening/results/{trackId}` | Get result by track ID |
| GET | `/api/v1/screening/results` | Get recent results (top 50) |
| GET | `/api/v1/screening/hits` | Get all matches (blocked/flagged) |

**Screen a file:**
```bash
curl -X POST http://localhost:8092/api/v1/screening/scan \
  -F "file=@partners.csv" \
  -F "trackId=TRZA3X5T3LUY" \
  -F "account=partner-acme" \
  -F "columns=company_name,contact_name"
```

**Response:**
```json
{
  "trackId": "TRZA3X5T3LUY",
  "filename": "partners.csv",
  "accountUsername": "partner-acme",
  "outcome": "HIT",
  "recordsScanned": 150,
  "hitsFound": 2,
  "durationMs": 45,
  "actionTaken": "BLOCKED",
  "hits": [
    {
      "matchedName": "AL QAIDA",
      "sanctionsListName": "Specially Designated Nationals",
      "sanctionsListSource": "OFAC_SDN",
      "matchScore": 0.97,
      "fileField": "company_name",
      "fileValue": "Al Qaeda Trading Co",
      "lineNumber": 47
    }
  ]
}
```

**Screen inline text:**
```bash
curl -X POST http://localhost:8092/api/v1/screening/scan/text \
  -H "Content-Type: application/json" \
  -d '{
    "content": "John Smith,ACME Corp\nTaliban Group,Kabul",
    "filename": "partners.csv",
    "trackId": "TRZ123456",
    "columns": ["name", "company"]
  }'
```

### Sanctions List Management

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/screening/lists/refresh` | Force refresh all lists |
| GET | `/api/v1/screening/lists` | List status and counts |

**Force refresh:**
```bash
curl -X POST http://localhost:8092/api/v1/screening/lists/refresh
```

**Response:**
```json
{
  "status": "refreshed",
  "lists": {"OFAC_SDN": 1247, "EU_SANCTIONS": 89, "UN_SANCTIONS": 156},
  "lastRefresh": "2026-04-05T10:00:00Z"
}
```

### Health

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/screening/health` | Service health with list status |

---

## Matching Algorithm

The service uses **Jaro-Winkler similarity** for fuzzy name matching:

1. Names are normalized (lowercase, remove special characters, collapse whitespace)
2. Each value is compared against all sanctions entries + known aliases
3. Jaro-Winkler boosts common prefixes (up to 4 characters)
4. Scoring: `>= 0.95` → **HIT**, `>= 0.82` → **POSSIBLE_HIT**, `< 0.82` → **CLEAR**

**Outcomes:**
| Outcome | Condition | Default Action |
|---------|-----------|----------------|
| CLEAR | No matches above threshold | PASSED |
| HIT | Match score >= 0.95 | BLOCKED |
| POSSIBLE_HIT | Match score >= threshold but < 0.95 | BLOCKED or FLAGGED |
| ERROR | Processing failure | — |

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8092` | API port |
| `SCREENING_MATCH_THRESHOLD` | `0.82` | Minimum similarity score (0.0-1.0) |
| `SCREENING_DEFAULT_ACTION` | `BLOCK` | Action on match: `BLOCK` or `FLAG` |
| `SCREENING_REFRESH_HOURS` | `24` | Hours between auto-refresh |
| `SCREENING_OFAC_ENABLED` | `true` | Enable OFAC SDN list |
| `SCREENING_EU_ENABLED` | `true` | Enable EU sanctions list |
| `SCREENING_UN_ENABLED` | `true` | Enable UN sanctions list |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |

---

## Sanctions List Sources

| List | Source | Entries |
|------|--------|---------|
| OFAC SDN | US Treasury (`treasury.gov/ofac/downloads/sdn.csv`) | ~1,200+ |
| EU Sanctions | European Union (built-in + external feed) | Variable |
| UN Sanctions | United Nations consolidated list | Variable |

Lists are loaded on startup and refreshed automatically. ShedLock prevents duplicate refreshes in clustered deployments.

---

## Dependencies

- **PostgreSQL** — Required. Screening results and sanctions entries.
- **shared** module — Entities, repositories.

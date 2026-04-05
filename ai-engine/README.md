# AI Engine

> The intelligence brain of the platform — data classification, threat scoring, proxy verdicts, anomaly detection, NLP, and autonomous operations.

**Port:** 8091 | **Database:** PostgreSQL | **Required:** Recommended

---

## Overview

The AI engine provides intelligence across three capability tiers:

**Tier 1 — Foundational Intelligence:**
- Sensitive data classification (PCI, PII, PHI, Financial)
- Anomaly detection (volume spikes, unusual patterns)
- Smart retry (failure classification and recovery)
- Content validation (CSV/TSV structure)
- Threat scoring (per-operation risk assessment)

**Tier 2 — Proxy Intelligence (DMZ Security Brain):**
- Real-time connection verdicts (ALLOW/THROTTLE/CHALLENGE/BLOCK/BLACKHOLE)
- IP reputation scoring (0-100 with decay)
- Protocol threat detection (SSH brute force, FTP bounce, HTTP probing)
- Connection pattern analysis (flood, slow-loris, DDoS)
- Geo-anomaly detection (Tor, VPN, impossible travel)

**Tier 3 — Advanced Intelligence:**
- Partner behavioral profiling
- Predictive SLA forecasting
- Auto-remediation of failed transfers
- Natural language Q&A about platform state
- Self-driving infrastructure scaling
- Privacy-preserving threat intelligence sharing
- EDI format detection and translation

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq ai-engine

# Health check
curl http://localhost:8091/api/v1/ai/health

# Classify a file
curl -X POST http://localhost:8091/api/v1/ai/classify -F "file=@data.csv"

# Ask a question
curl -X POST http://localhost:8091/api/v1/ai/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "how many files failed today?"}'
```

---

## API Endpoints

### Data Classification

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ai/classify` | Classify uploaded file for sensitive data |
| POST | `/api/v1/ai/classify/text` | Classify text content |

**Classify a file:**
```bash
curl -X POST http://localhost:8091/api/v1/ai/classify \
  -F "file=@invoice.csv"
```

**Response:**
```json
{
  "riskLevel": "HIGH",
  "riskScore": 75,
  "detections": [
    {"type": "PCI", "pattern": "CREDIT_CARD", "count": 3, "masked": "****-****-****-1234"},
    {"type": "PII", "pattern": "SSN", "count": 1, "masked": "***-**-6789"}
  ],
  "categoryCounts": {"PCI": 3, "PII": 1},
  "requiresEncryption": true,
  "blocked": true
}
```

**Detectable data types:**
- **PCI:** Credit card numbers (Visa, MC, Amex, Discover)
- **PII:** SSN, email addresses, phone numbers, passport numbers
- **PHI:** Medical record numbers, ICD-10 codes, NDC drug codes
- **Financial:** Bank routing numbers, SWIFT/BIC codes, IBAN numbers

---

### Anomaly Detection

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/anomalies` | Get active anomalies |

**Response:**
```json
[
  {
    "type": "VOLUME_SPIKE",
    "severity": "HIGH",
    "description": "Transfer volume 340% above normal for this hour",
    "zScore": 4.2,
    "detectedAt": "2026-04-05T10:15:00Z"
  }
]
```

Anomalies detected: volume spikes/drops, file size anomalies, unusual transfer times, missing expected transfers.

---

### Smart Retry

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ai/smart-retry` | Classify failure and recommend retry strategy |

**Request:**
```json
{"errorMessage": "Connection refused", "filename": "data.csv", "retryCount": 2}
```

**Response:**
```json
{
  "action": "RETRY",
  "delaySeconds": 60,
  "reason": "Transient network error - exponential backoff",
  "failureCategory": "NETWORK_TRANSIENT"
}
```

**Actions:** `RETRY` (30-60s backoff), `RETRY_DELAYED` (10 min), `ALERT_NO_RETRY` (auth errors), `RE_REQUEST` (integrity failure), `QUARANTINE` (format errors)

---

### Risk & Threat Scoring

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ai/risk-score` | Compute risk score for a transfer |
| POST | `/api/v1/ai/threat-score` | Compute per-operation threat score |

**Risk score request:**
```json
{"newIp": true, "unusualHour": false, "fileSizeMb": 50, "containsPci": true, "containsPii": false}
```

**Response:**
```json
{"score": 65, "level": "HIGH", "action": "REVIEW", "factors": ["New IP (+25)", "PCI data (+25)", "Large file (+10)"]}
```

**Threat signals (9 total):** New IP (+25), unusual hour (+15), large file (+10), PCI data (+25), PII data (+15), rapid operations (+20), failed operations (+15), suspicious filenames (+30), executables (+10).

---

### Content Validation

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ai/validate` | Validate file structure (CSV/TSV) |
| POST | `/api/v1/ai/detect-format` | Auto-detect file format |

**Validate CSV:**
```bash
curl -X POST http://localhost:8091/api/v1/ai/validate \
  -F "file=@data.csv" -F "expectedColumns=5"
```

**Detect format:**
```bash
curl -X POST http://localhost:8091/api/v1/ai/detect-format -F "file=@unknown.bin"
```

**Response:**
```json
{"detectedFormat": "GZIP", "extensionMismatch": true, "suggestedAction": "Auto-decompress GZIP before processing"}
```

**Detected formats:** GZIP, ZIP, PDF, PNG, PGP (message/key), XML, JSON, CSV, TSV, EDI (X12/EDIFACT)

---

### NLP (Natural Language Processing)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ai/nlp/command` | Translate English → CLI command |
| POST | `/api/v1/ai/nlp/suggest-flow` | Generate flow from English description |
| POST | `/api/v1/ai/nlp/explain` | Explain a transfer failure |
| POST | `/api/v1/ai/ask` | Ask a question about system state |

**Ask a question (no LLM required — pattern-based):**
```bash
curl -X POST http://localhost:8091/api/v1/ai/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "how many files failed today?"}'
```

**Supported questions:**
- "How many files today?" → Transfer count
- "Any failures/problems?" → Failure stats with worst account
- "Are there stuck/pending transfers?" → Count pending >30 min
- "How is partner-x doing?" → Health, transfer rate, error rate
- "Any SLA/overdue/breach issues?" → SLA forecasts
- "What is the average transfer time?" → Upload-to-route latency
- "What remediation actions were taken?" → Auto-fix log

**Translate to CLI command (requires Claude API key):**
```bash
curl -X POST http://localhost:8091/api/v1/ai/nlp/command \
  -H "Content-Type: application/json" \
  -d '{"query": "show me all failed transfers today"}'
```

---

### Partner Profiles

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/partners` | List all partner profiles |
| GET | `/api/v1/ai/partners/{username}` | Get specific partner profile |
| POST | `/api/v1/ai/partners/{username}/check` | Check for behavioral deviations |

**Check for deviations:**
```bash
curl -X POST http://localhost:8091/api/v1/ai/partners/partner-acme/check \
  -H "Content-Type: application/json" \
  -d '{"filename": "data.exe", "fileSize": 104857600}'
```

**Response:**
```json
["Unusual file extension: .exe (expected: .csv, .txt)", "File size 100MB exceeds partner average of 2MB"]
```

**Profile includes:** Active hours, active days, average file sizes, error rate, common extensions, health score, predicted next delivery.

---

### SLA Forecasting

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/sla/forecasts` | Predictive SLA forecasts |

**Response:**
```json
[
  {
    "username": "partner-acme",
    "healthScore": 72,
    "overdue": false,
    "deliveryDriftHours": 1.5,
    "daysUntilBreach": 12,
    "riskLevel": "MEDIUM"
  }
]
```

---

### Auto-Remediation

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/remediation/actions` | Recent auto-remediation actions |

Auto-remediation runs every 2 minutes:
- Reclassifies FAILED transfers with smart retry logic
- Sends alerts for non-retryable failures
- Clears stuck PENDING records >1 hour
- Max 10 retries per transfer

---

### Recommendations

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/recommendations` | System health recommendations |
| GET | `/api/v1/ai/recommendations/summary` | Health summary |

**Categories:** PERFORMANCE, SCALING, RELIABILITY, SECURITY, COMPLIANCE, COST

---

### Self-Driving Infrastructure

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/self-driving/actions` | Recent infrastructure actions |
| GET | `/api/v1/ai/self-driving/status` | Autonomy status |

Analyzes hourly traffic patterns and pre-scales services before predicted spikes.

---

### Intelligence Network

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/intelligence/signals` | Threat signals (last 24h) |
| GET | `/api/v1/ai/intelligence/status` | Network status |

Privacy-preserving threat intelligence sharing (SHA-256 anonymized metadata).

---

### Proxy Intelligence (DMZ Security Brain)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/proxy/verdict` | Get connection verdict |
| POST | `/api/v1/proxy/event` | Report single threat event |
| POST | `/api/v1/proxy/events` | Batch report threat events |
| GET | `/api/v1/proxy/blocklist` | Get blocked IPs |
| POST | `/api/v1/proxy/blocklist` | Block an IP |
| DELETE | `/api/v1/proxy/blocklist/{ip}` | Unblock an IP |
| GET | `/api/v1/proxy/allowlist` | Get allowed IPs |
| POST | `/api/v1/proxy/allowlist` | Allow an IP |
| GET | `/api/v1/proxy/ip/{ip}` | Full IP intelligence |
| GET | `/api/v1/proxy/dashboard` | Security dashboard |
| GET | `/api/v1/proxy/verdicts?limit=50` | Recent verdict audit trail |
| POST | `/api/v1/proxy/geo/high-risk-countries` | Set high-risk countries |
| POST | `/api/v1/proxy/geo/tor-nodes` | Update Tor exit nodes |
| POST | `/api/v1/proxy/geo/vpn-prefixes` | Update VPN prefixes |
| GET | `/api/v1/proxy/geo/stats` | Geo detection stats |
| GET | `/api/v1/proxy/health` | Proxy intelligence health |

**Get verdict:**
```bash
curl -X POST http://localhost:8091/api/v1/proxy/verdict \
  -H "Content-Type: application/json" \
  -d '{"sourceIp":"203.0.113.5","targetPort":2222,"detectedProtocol":"SSH"}'
```

**Response:**
```json
{
  "action": "ALLOW",
  "riskScore": 25,
  "reason": "Known IP, normal behavior",
  "ttlSeconds": 300,
  "signals": [],
  "rateLimit": {"maxConnectionsPerMinute": 60, "maxConcurrent": 20, "maxBytesPerMinute": 500000000}
}
```

**Verdict actions:**
| Action | Risk Score | Behavior |
|--------|-----------|----------|
| ALLOW | <40 | Pass through normally |
| THROTTLE | 40-59 | Apply restrictive rate limits |
| CHALLENGE | 60-84 | Allow but monitor closely |
| BLOCK | 85-94 | Send RST, close connection |
| BLACKHOLE | 95+ | Silent drop, auto-blocklist |

**Risk score formula:**
- IP Reputation (inverted): **35%**
- Connection Pattern Risk: **30%**
- Geo Risk: **15%**
- New IP Penalty: **10%**
- Protocol Baseline Risk: **10%**

**Block an IP:**
```bash
curl -X POST http://localhost:8091/api/v1/proxy/blocklist \
  -H "Content-Type: application/json" \
  -d '{"ip":"203.0.113.5","reason":"Brute force detected"}'
```

---

### EDI Translation

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/edi/detect` | Detect EDI format |
| POST | `/api/v1/edi/validate` | Validate EDI structure |
| POST | `/api/v1/edi/translate/json` | Convert EDI → JSON |
| POST | `/api/v1/edi/translate/csv` | Convert EDI → CSV |
| GET | `/api/v1/edi/types` | List supported transaction types |

**Supported formats:** X12 (837, 835, 850, 856, 270, 271, 997, 810, 820, 834), SWIFT, EDIFACT

---

### Health

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/health` | Service health with feature flags |

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8091` | API port |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `CLAUDE_API_KEY` | (none) | Anthropic API key (optional, for NLP features) |
| `CLAUDE_MODEL` | `claude-sonnet-4-20250514` | Claude model for NLP |
| `AI_CLASSIFICATION_MAX_SCAN_BYTES` | `104857600` | Max file scan size (100 MB) |
| `AI_BLOCK_UNENCRYPTED_PCI` | `true` | Block files with unencrypted credit card numbers |
| `ai.anomaly.threshold-sigma` | `3.0` | Z-score threshold for anomalies |
| `ai.anomaly.lookback-days` | `30` | Lookback window for anomaly baseline |
| `ai.proxy.max-tracked-ips` | `100000` | Max IPs in reputation tracker |
| `ai.proxy.decay-interval-ms` | `300000` | Reputation decay interval (5 min) |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key |

---

## Scheduled Tasks

| Task | Interval | Purpose |
|------|----------|---------|
| Anomaly detection | 5 min | Detect volume/size/timing anomalies |
| Partner profiling | 10 min | Update partner behavioral profiles |
| SLA forecasting | 30 min | Predict delivery drift and breaches |
| Auto-remediation | 2 min | Retry failed transfers, clear stuck jobs |
| Observability analysis | 5 min | Collect metrics, generate recommendations |
| Self-driving infra | 5 min | Pre-scale based on predicted traffic |
| IP reputation decay | 5 min | Decay scores toward neutral (50) |
| Stale profile cleanup | 1 hour | Evict inactive IP profiles |

All scheduled tasks use ShedLock for distributed coordination.

---

## Dependencies

- **PostgreSQL** — Required. Transfer records, audit logs for analysis.
- **shared** module — Entities, repositories.
- **Claude API** — Optional. Only needed for NLP command translation; all other features work without it.

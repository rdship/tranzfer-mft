# TranzFer MFT — API Reference

All REST endpoints across all services. Organized by service.

> **Auth:** Most endpoints require a JWT token via `Authorization: Bearer <token>` header. Internal APIs require `X-Internal-Key` header. Exceptions are noted.

---

## Table of Contents

- [Onboarding API (:8080)](#onboarding-api-8080)
- [Config Service (:8084)](#config-service-8084)
- [SFTP Service (:8081)](#sftp-service-8081)
- [FTP Service (:8082)](#ftp-service-8082)
- [FTP-Web Service (:8083)](#ftp-web-service-8083)
- [Gateway Service (:8085)](#gateway-service-8085)
- [DMZ Proxy (:8088)](#dmz-proxy-8088)
- [Encryption Service (:8086)](#encryption-service-8086)
- [External Forwarder (:8087)](#external-forwarder-8087)
- [License Service (:8089)](#license-service-8089)
- [Analytics Service (:8090)](#analytics-service-8090)
- [AI Engine (:8091)](#ai-engine-8091)
- [Screening Service (:8092)](#screening-service-8092)
- [Keystore Manager (:8093)](#keystore-manager-8093)
- [Storage Manager (:8094)](#storage-manager-8094)
- [EDI Converter (:8095)](#edi-converter-8095)

---

## Onboarding API (:8080)

The main platform API. Handles authentication, accounts, and file management.

### Authentication

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| POST | `/api/auth/register` | Register new user | None |
| POST | `/api/auth/login` | Login, returns JWT token | None |
| POST | `/api/auth/refresh` | Refresh JWT token | Bearer |

**Example — Register:**
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@company.com","password":"MyPassword123!"}'
```

**Example — Login:**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@company.com","password":"MyPassword123!"}'
# Returns: {"token": "eyJ..."}
```

### Transfer Accounts

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/accounts` | List all accounts | Bearer |
| POST | `/api/accounts` | Create account | Bearer |
| GET | `/api/accounts/{id}` | Get account details | Bearer |
| PUT | `/api/accounts/{id}` | Update account | Bearer |
| DELETE | `/api/accounts/{id}` | Delete account | Bearer |
| POST | `/api/accounts/{id}/enable` | Enable account | Bearer |
| POST | `/api/accounts/{id}/disable` | Disable account | Bearer |

### Folder Mappings

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/folder-mappings` | List folder mappings | Bearer |
| POST | `/api/folder-mappings` | Create folder mapping | Bearer |
| PUT | `/api/folder-mappings/{id}` | Update mapping | Bearer |
| DELETE | `/api/folder-mappings/{id}` | Delete mapping | Bearer |

### SSH Keys

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/accounts/{id}/ssh-keys` | List SSH keys for account | Bearer |
| POST | `/api/accounts/{id}/ssh-keys` | Add SSH key | Bearer |
| DELETE | `/api/accounts/{id}/ssh-keys/{keyId}` | Remove SSH key | Bearer |

### Transfer Journey

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/transfers/journey/{trackId}` | Full journey of a tracked transfer | Bearer |
| GET | `/api/transfers/recent` | Recent transfers | Bearer |

### P2P Transfers

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| POST | `/api/p2p/send` | Initiate P2P transfer | Bearer |
| GET | `/api/p2p/inbox` | P2P inbox | Bearer |
| GET | `/api/p2p/status/{id}` | P2P transfer status | Bearer |

### Health

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/actuator/health` | Service health | None |

---

## Config Service (:8084)

### File Flows

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/flows` | List all active file flows |
| POST | `/api/flows` | Create file flow |
| GET | `/api/flows/{id}` | Get flow details |
| PUT | `/api/flows/{id}` | Update flow (requires full object) |
| DELETE | `/api/flows/{id}` | Deactivate flow (soft-delete) |
| PATCH | `/api/flows/{id}/toggle` | Enable/disable flow (no body needed) |
| GET | `/api/flows/step-types` | List available step types |
| GET | `/api/flows/functions/catalog` | Function catalog with IO modes |
| GET | `/api/flows/match-fields` | Available match criteria fields |
| POST | `/api/flows/test-match` | Test match criteria against file context |
| GET | `/api/flows/executions` | Search executions (filter by trackId, status, filename) |

**Example — Create a flow:**
```bash
curl -X POST http://localhost:8084/api/flows \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Compress and Screen",
    "filenamePattern": ".*\\.csv",
    "direction": "INBOUND",
    "steps": [
      {"type": "COMPRESS_GZIP", "order": 1},
      {"type": "SCREEN", "order": 2}
    ]
  }'
```

**Example — Toggle flow on/off:**
```bash
curl -X PATCH http://localhost:8084/api/flows/{id}/toggle
```

### Connectors

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/connectors` | List connectors (Slack, PagerDuty, etc.) |
| POST | `/api/connectors` | Create connector |
| PUT | `/api/connectors/{id}` | Update connector |
| DELETE | `/api/connectors/{id}` | Delete connector (soft-delete) |
| POST | `/api/connectors/{id}/test` | Test connector webhook |
| GET | `/api/connectors/types` | List connector types (SLACK, PAGERDUTY, TEAMS, etc.) |

### Security Profiles

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/security-profiles` | List security profiles |
| POST | `/api/security-profiles` | Create profile |
| PUT | `/api/security-profiles/{id}` | Update profile |
| DELETE | `/api/security-profiles/{id}` | Delete profile |

### Scheduler

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/scheduler` | List scheduled jobs |
| POST | `/api/scheduler` | Create scheduled job |
| PUT | `/api/scheduler/{id}` | Update job |
| DELETE | `/api/scheduler/{id}` | Delete job |

### SLA Agreements

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/sla` | List SLA agreements |
| POST | `/api/sla` | Create SLA |
| PUT | `/api/sla/{id}` | Update SLA |
| DELETE | `/api/sla/{id}` | Delete SLA |

### External Destinations

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/external-destinations` | List external destinations |
| POST | `/api/external-destinations` | Create destination |
| PUT | `/api/external-destinations/{id}` | Update destination |
| DELETE | `/api/external-destinations/{id}` | Delete destination |

### Legacy Servers

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/legacy-servers` | List legacy servers |
| POST | `/api/legacy-servers` | Add legacy server |

### Server Configuration

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/server-config` | Get server configuration |
| PUT | `/api/server-config` | Update server configuration |

### Platform Settings

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/platform-settings` | Get platform settings |
| PUT | `/api/platform-settings` | Update platform settings |

---

## FTP-Web Service (:8083)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/files/list` | List files in directory | Bearer |
| POST | `/api/files/upload` | Upload file (multipart, max 512 MB) | Bearer |
| GET | `/api/files/download` | Download file | Bearer |
| DELETE | `/api/files/delete` | Delete file or directory | Bearer |
| POST | `/api/files/mkdir` | Create directory | Bearer |
| POST | `/api/files/rename` | Rename or move file | Bearer |
| POST | `/internal/files/receive` | Internal file routing | X-Internal-Key |

---

## Gateway Service (:8085)

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/internal/gateway/status` | Gateway status (SFTP/FTP ports, running state) | X-Internal-Key |
| GET | `/internal/gateway/legacy-servers` | List configured legacy servers | X-Internal-Key |

---

## DMZ Proxy (:8088)

> **Auth:** All endpoints except `/health` require `X-Internal-Key` header.

### Port Mapping Management

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/proxy/mappings` | List all port mappings with live stats |
| POST | `/api/proxy/mappings` | Hot-add a new port mapping |
| DELETE | `/api/proxy/mappings/{name}` | Remove a port mapping |

**Example — Add mapping:**
```bash
curl -X POST http://localhost:8088/api/proxy/mappings \
  -H "X-Internal-Key: internal_control_secret" \
  -H "Content-Type: application/json" \
  -d '{"name":"new-sftp","listenPort":2223,"targetHost":"sftp2","targetPort":2222}'
```

### Security Intelligence

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/proxy/security/stats` | Full security metrics dump |
| GET | `/api/proxy/security/connections` | Connection tracker stats |
| GET | `/api/proxy/security/ip/{ip}` | Per-IP security intelligence |
| GET | `/api/proxy/security/rate-limits` | Rate limiter state |
| GET | `/api/proxy/security/summary` | Quick security summary |

### Health

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/proxy/health` | Service health + features | None |

---

## AI Engine (:8091)

### Data Classification

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ai/classify` | Classify uploaded file for sensitive data (PCI/PII/PHI) |
| POST | `/api/v1/ai/classify/text` | Classify text content |

**Example:**
```bash
curl -X POST http://localhost:8091/api/v1/ai/classify \
  -F "file=@invoice.csv"
```

### Anomaly Detection

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/anomalies` | Get active anomalies |

### Natural Language Processing

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ai/nlp/command` | Translate natural language → CLI command |
| POST | `/api/v1/ai/nlp/suggest-flow` | Generate flow from English description |
| POST | `/api/v1/ai/nlp/explain` | Explain a transfer failure |
| POST | `/api/v1/ai/ask` | Ask a question about system state |

**Example:**
```bash
curl -X POST http://localhost:8091/api/v1/ai/nlp/command \
  -H "Content-Type: application/json" \
  -d '{"text": "show me all failed transfers today"}'
```

### Risk & Threat Scoring

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ai/risk-score` | Compute risk score for a transfer |
| POST | `/api/v1/ai/threat-score` | Compute threat score per operation |

### Smart Retry

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ai/smart-retry` | Classify failure and recommend retry strategy |

### Content Validation

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ai/validate` | Validate file structure (CSV/TSV) |
| POST | `/api/v1/ai/detect-format` | Auto-detect file type |

### Partner Profiles

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/partners` | Get all partner profiles |
| GET | `/api/v1/ai/partners/{username}` | Get specific partner profile |
| POST | `/api/v1/ai/partners/{username}/check` | Check for behavioral deviations |

### Recommendations

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/recommendations` | System health recommendations |
| GET | `/api/v1/ai/recommendations/summary` | Health summary |

### SLA Forecasts

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/sla/forecasts` | Predictive SLA forecasts |

### Auto-Remediation

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/remediation/actions` | Recent auto-remediation actions |

### Proxy Intelligence

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/proxy/verdict` | Get connection verdict (ALLOW/BLOCK/etc.) |
| POST | `/api/v1/proxy/event` | Report single threat event |
| POST | `/api/v1/proxy/events` | Batch report threat events |
| GET | `/api/v1/proxy/blocklist` | Get blocked IPs |
| POST | `/api/v1/proxy/blocklist` | Block an IP |
| DELETE | `/api/v1/proxy/blocklist/{ip}` | Unblock an IP |
| GET | `/api/v1/proxy/allowlist` | Get allowed IPs |
| POST | `/api/v1/proxy/allowlist` | Allow an IP |
| GET | `/api/v1/proxy/ip/{ip}` | Full IP intelligence |
| GET | `/api/v1/proxy/dashboard` | Security dashboard |
| GET | `/api/v1/proxy/verdicts` | Recent verdict audit trail |
| POST | `/api/v1/proxy/geo/high-risk-countries` | Set high-risk countries |
| POST | `/api/v1/proxy/geo/tor-nodes` | Update Tor exit node list |
| POST | `/api/v1/proxy/geo/vpn-prefixes` | Update VPN IP prefixes |
| GET | `/api/v1/proxy/geo/stats` | Geo detection stats |
| GET | `/api/v1/proxy/health` | Proxy intelligence health |

**Example — Get verdict:**
```bash
curl -X POST http://localhost:8091/api/v1/proxy/verdict \
  -H "Content-Type: application/json" \
  -d '{"sourceIp":"203.0.113.5","targetPort":2222,"detectedProtocol":"SSH"}'
```

**Example — Block IP:**
```bash
curl -X POST http://localhost:8091/api/v1/proxy/blocklist \
  -H "Content-Type: application/json" \
  -d '{"ip":"203.0.113.5","reason":"Brute force detected"}'
```

### Natural Language Mapping Correction

Partners can iteratively correct EDI field mappings through plain English instructions.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/edi/correction/sessions` | Start a correction session with sample EDI input |
| GET | `/api/v1/edi/correction/sessions/{id}` | Get session details |
| GET | `/api/v1/edi/correction/sessions?partnerId=X` | List partner's sessions |
| POST | `/api/v1/edi/correction/sessions/{id}/correct` | Submit NL correction instruction |
| POST | `/api/v1/edi/correction/sessions/{id}/test` | Re-run test with current mappings |
| POST | `/api/v1/edi/correction/sessions/{id}/approve` | Approve & persist corrected map |
| POST | `/api/v1/edi/correction/sessions/{id}/reject` | Reject corrections |
| GET | `/api/v1/edi/correction/sessions/{id}/history` | Correction history |
| GET | `/api/v1/edi/correction/health` | Correction service health |

**Example — Start session:**
```bash
curl -X POST http://localhost:8091/api/v1/edi/correction/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "partnerId": "acme",
    "sourceFormat": "X12",
    "targetFormat": "JSON",
    "sampleInputContent": "ISA*00*          *00*          *ZZ*ACME*ZZ*PARTNER*..."
  }'
```

**Example — Submit correction:**
```bash
curl -X POST http://localhost:8091/api/v1/edi/correction/sessions/{id}/correct \
  -H "Content-Type: application/json" \
  -d '{"partnerId": "acme", "instruction": "Company name should come from NM1*03, not NM1*02"}'
```

**Example — Approve:**
```bash
curl -X POST "http://localhost:8091/api/v1/edi/correction/sessions/{id}/approve?partnerId=acme"
```

### Health

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/ai/health` | AI service health + features |

---

## Screening Service (:8092)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/screening/check` | Screen a name against sanctions lists |
| GET | `/api/v1/screening/results` | Get screening results |
| GET | `/api/v1/screening/stats` | Screening statistics |

**Example:**
```bash
curl -X POST http://localhost:8092/api/v1/screening/check \
  -H "Content-Type: application/json" \
  -d '{"name":"John Smith","country":"US"}'
```

---

## EDI Converter (:8095)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/edi/detect` | Auto-detect EDI format |
| POST | `/api/v1/edi/validate` | Validate EDI structure |
| POST | `/api/v1/edi/translate/json` | Convert EDI → JSON |
| POST | `/api/v1/edi/translate/csv` | Convert EDI → CSV |
| GET | `/api/v1/edi/types` | List supported transaction types |
| POST | `/api/v1/convert/trained` | Convert using trained/partner-specific map |
| POST | `/api/v1/convert/test-mappings` | Test custom field mappings against sample EDI |
| POST | `/api/v1/convert/trained/invalidate-cache` | Invalidate trained map cache |
| POST | `/api/v1/convert/compare/prepare` | Scan dirs, match files, return preview for confirmation |
| POST | `/api/v1/convert/compare/prepare/upload` | Upload CSV with explicit file pair mappings |
| POST | `/api/v1/convert/compare/execute/{id}` | Execute comparison after user confirmation |
| GET | `/api/v1/convert/compare/reports/{id}` | Retrieve comparison report |
| GET | `/api/v1/convert/compare/reports/{id}/summary` | Human-readable text summary |
| GET | `/api/v1/convert/compare/sessions/{id}` | Get session preview |

**Output formats:** JSON, XML, CSV, YAML, FLAT, TIF, **X12**, **EDIFACT**, **HL7**, **SWIFT_MT** (110 total paths)

**Example — Cross-format: X12 850 → EDIFACT ORDERS:**
```bash
curl -X POST http://localhost:8095/api/v1/convert/convert \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*...", "target": "EDIFACT"}'
```

**Example — Convert EDI:**
```bash
curl -X POST http://localhost:8095/api/v1/edi/translate/json \
  -H "Content-Type: text/plain" \
  -d 'ISA*00*          *00*          *ZZ*SENDER    ...'
```

**Example — Test custom mappings:**
```bash
curl -X POST http://localhost:8095/api/v1/convert/test-mappings \
  -H "Content-Type: application/json" \
  -d '{
    "sourceContent": "ISA*00*...",
    "targetFormat": "JSON",
    "fieldMappings": [
      {"sourceField": "BEG*03", "targetField": "poNumber", "transform": "DIRECT"},
      {"sourceField": "NM1*03", "targetField": "buyerName", "transform": "TRIM"}
    ]
  }'
```

**Example — Compare conversion outputs:**
```bash
# Step 1: Prepare (scan directories, get preview)
curl -X POST http://localhost:8095/api/v1/convert/compare/prepare \
  -H "Content-Type: application/json" \
  -d '{
    "sourceInputPath": "/data/old-system/input",
    "sourceOutputPath": "/data/old-system/output",
    "targetInputPath": "/data/new-system/input",
    "targetOutputPath": "/data/new-system/output",
    "reportOutputPath": "/data/reports"
  }'
# Returns: { sessionId, matchedPairs, filePairs[], status: "READY" }

# Step 2: Execute (after user reviews preview)
curl -X POST http://localhost:8095/api/v1/convert/compare/execute/{sessionId}
# Returns: full report with per-file diffs, map summaries, recommendations
```

---

## Common Patterns

### Health Check (All Services)

Every Spring Boot service exposes:
```bash
curl http://localhost:{port}/actuator/health
```

### Pagination

List endpoints typically support:
```
?page=0&size=20&sort=createdAt,desc
```

### Error Response Format

```json
{
  "timestamp": "2026-04-05T12:00:00.000Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed: email is required",
  "path": "/api/auth/register"
}
```

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
| GET | `/api/v1/flows` | List all file flows |
| POST | `/api/v1/flows` | Create file flow |
| GET | `/api/v1/flows/{id}` | Get flow details |
| PUT | `/api/v1/flows/{id}` | Update flow |
| DELETE | `/api/v1/flows/{id}` | Delete flow |

**Example — Create a flow:**
```bash
curl -X POST http://localhost:8084/api/v1/flows \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Compress and Screen",
    "triggerPattern": "*.csv",
    "steps": [
      {"type": "COMPRESS_GZIP", "order": 1},
      {"type": "SCREEN", "order": 2}
    ]
  }'
```

### Connectors

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/connectors` | List connectors (Slack, PagerDuty, etc.) |
| POST | `/api/v1/connectors` | Create connector |
| PUT | `/api/v1/connectors/{id}` | Update connector |
| DELETE | `/api/v1/connectors/{id}` | Delete connector |

### Security Profiles

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/security-profiles` | List security profiles |
| POST | `/api/v1/security-profiles` | Create profile |
| PUT | `/api/v1/security-profiles/{id}` | Update profile |
| DELETE | `/api/v1/security-profiles/{id}` | Delete profile |

### Scheduler

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/scheduler/jobs` | List scheduled jobs |
| POST | `/api/v1/scheduler/jobs` | Create scheduled job |
| PUT | `/api/v1/scheduler/jobs/{id}` | Update job |
| DELETE | `/api/v1/scheduler/jobs/{id}` | Delete job |

### SLA Agreements

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/sla` | List SLA agreements |
| POST | `/api/v1/sla` | Create SLA |
| PUT | `/api/v1/sla/{id}` | Update SLA |
| DELETE | `/api/v1/sla/{id}` | Delete SLA |

### External Destinations

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/external-destinations` | List external destinations |
| POST | `/api/v1/external-destinations` | Create destination |
| PUT | `/api/v1/external-destinations/{id}` | Update destination |
| DELETE | `/api/v1/external-destinations/{id}` | Delete destination |

### Legacy Servers

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/legacy-servers` | List legacy servers |
| POST | `/api/v1/legacy-servers` | Add legacy server |

### Server Configuration

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/server-config` | Get server configuration |
| PUT | `/api/v1/server-config` | Update server configuration |

### Platform Settings

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/platform-settings` | Get platform settings |
| PUT | `/api/v1/platform-settings` | Update platform settings |

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

**Example:**
```bash
curl -X POST http://localhost:8095/api/v1/edi/translate/json \
  -H "Content-Type: text/plain" \
  -d 'ISA*00*          *00*          *ZZ*SENDER    ...'
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

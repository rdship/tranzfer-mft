# TranzFer MFT — Service Catalog

Every service in the platform, explained in plain language. For each service you'll find: what it does, when you need it, how to run it, and how to configure it.

---

## Table of Contents

- [Quick Reference Table](#quick-reference-table)
- [Infrastructure Services](#infrastructure-services)
  - [PostgreSQL](#postgresql)
  - [RabbitMQ](#rabbitmq)
- [Core Services (Required)](#core-services)
  - [onboarding-api](#onboarding-api)
  - [config-service](#config-service)
- [Transfer Services (Pick Your Protocols)](#transfer-services)
  - [sftp-service](#sftp-service)
  - [ftp-service](#ftp-service)
  - [ftp-web-service](#ftp-web-service)
  - [as2-service](#as2-service)
  - [gateway-service](#gateway-service)
- [Security & Network Services](#security--network-services)
  - [dmz-proxy](#dmz-proxy)
  - [api-gateway](#api-gateway)
  - [encryption-service](#encryption-service)
  - [keystore-manager](#keystore-manager)
  - [screening-service](#screening-service)
- [Intelligence Services](#intelligence-services)
  - [ai-engine](#ai-engine)
  - [analytics-service](#analytics-service)
- [Storage & Delivery Services](#storage--delivery-services)
  - [storage-manager](#storage-manager)
  - [external-forwarder-service](#external-forwarder-service)
  - [edi-converter](#edi-converter)
- [Management Services](#management-services)
  - [license-service](#license-service)
- [Frontend Applications](#frontend-applications)
  - [admin-ui](#admin-ui)
  - [ftp-web-ui](#ftp-web-ui)
  - [partner-portal](#partner-portal)
- [Client Tools](#client-tools)
  - [mft-client](#mft-client)
  - [cli](#cli)

---

## Quick Reference Table

| Service | Port(s) | Needs DB? | Needs RabbitMQ? | Required? |
|---------|---------|-----------|-----------------|-----------|
| PostgreSQL | 5432 | — | No | Yes |
| RabbitMQ | 5672, 15672 | No | — | Yes |
| onboarding-api | 8080 | Yes | Yes | Yes |
| config-service | 8084 | Yes | Yes | Yes |
| sftp-service | 2222, 8081 | Yes | Yes | If you need SFTP |
| ftp-service | 21, 21000-21010, 8082 | Yes | Yes | If you need FTP |
| ftp-web-service | 8083 | Yes | Yes | If you need web upload |
| as2-service | 8094 | Yes | No | If you need AS2 |
| gateway-service | 2220, 2122, 8085 | Yes | Yes | If running multiple instances |
| dmz-proxy | 2222, 21, 443, 8088 | **No** | No | If exposed to internet |
| api-gateway | 80, 443 | No | No | If you need single HTTP entry point |
| encryption-service | 8086 | Yes | No | If you need PGP/AES encryption |
| keystore-manager | 8093 | Yes | No | If you manage certificates centrally |
| screening-service | 8092 | Yes | No | If you need OFAC/sanctions screening |
| ai-engine | 8091 | Yes | No | If you want AI features or DMZ security |
| analytics-service | 8090 | Yes | Yes | If you want dashboards/metrics |
| storage-manager | 8094 | Yes | No | If you need tiered storage |
| external-forwarder | 8087 | Yes | No | If you send files to external systems |
| edi-converter | 8095 | **No** | No | If you process EDI files |
| license-service | 8089 | Yes | No | If you need license enforcement |
| admin-ui | 3000 | No | No | If you want the web dashboard |
| ftp-web-ui | 3001 | No | No | If you want the file browser |
| partner-portal | 3002 | No | No | If partners need self-service |

---

## Infrastructure Services

### PostgreSQL

**What it does:** Stores all platform data — users, accounts, transfer records, configurations, audit logs.

**When you need it:** Always. This is required.

**Run it standalone:**
```bash
docker compose up -d postgres
```

**Default configuration:**
| Setting | Value |
|---------|-------|
| Port | 5432 |
| Database | filetransfer |
| Username | postgres |
| Password | postgres (change in production!) |
| Max connections | 150 |
| Shared buffers | 256 MB |

**Verify it's working:**
```bash
docker compose exec postgres pg_isready -U postgres
# Should print: accepting connections
```

**Production notes:**
- Change the default password via `DB_PASSWORD` env var
- Consider a managed database (AWS RDS, Azure Database, GCP Cloud SQL)
- See [CAPACITY-PLANNING.md](CAPACITY-PLANNING.md) for sizing

---

### RabbitMQ

**What it does:** Event bus. Services publish events (file received, account created) and other services react to them.

**When you need it:** Always. Required for inter-service communication.

**Run it standalone:**
```bash
docker compose up -d rabbitmq
```

**Default configuration:**
| Setting | Value |
|---------|-------|
| AMQP Port | 5672 |
| Management UI | 15672 |
| Username | guest |
| Password | guest (change in production!) |
| Exchange | file-transfer.events |

**Verify it's working:**
```bash
# Open management UI
open http://localhost:15672
# Login: guest / guest
```

---

## Core Services

### onboarding-api

**What it does:** The main API server. Handles user registration, login (JWT), transfer accounts, folder mappings, SSH key management, P2P transfers, and the transfer journey tracker.

**When you need it:** Always. Every other service depends on the account data managed here.

**Run it:**
```bash
docker compose up -d postgres rabbitmq onboarding-api
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8080 | REST API |

**Key environment variables:**
| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://postgres:5432/filetransfer` | PostgreSQL connection |
| `DB_USERNAME` | `postgres` | Database user |
| `DB_PASSWORD` | `postgres` | Database password |
| `JWT_SECRET` | (insecure default) | JWT signing key — **must change in production** |
| `CONTROL_API_KEY` | `internal_control_secret` | Key for internal API calls |
| `SERVER_PORT` | `8080` | HTTP port |

**Verify it's working:**
```bash
curl http://localhost:8080/actuator/health
# Should return {"status":"UP"}
```

**What it provides to other services:**
- User authentication (JWT tokens)
- Transfer account management
- Folder mapping configuration
- SSH public key storage
- Audit trail / transfer journey

---

### config-service

**What it does:** Manages all platform configuration — file processing flows, connectors, security profiles, delivery endpoints, schedulers, SLA agreements, legacy server mappings.

**When you need it:** If you want automated file processing, scheduled transfers, or SLA monitoring.

**Run it:**
```bash
docker compose up -d postgres rabbitmq config-service
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8084 | REST API |

**Key features:**
- **File Flows** — Define processing pipelines: compress → encrypt → screen → deliver
- **Connectors** — Integrate with Slack, PagerDuty, email, webhooks
- **Security Profiles** — Define per-account security rules
- **Scheduler** — Cron-based scheduled transfers
- **SLA Agreements** — Define and monitor delivery SLAs
- **Legacy Servers** — Map external servers for gateway routing

---

## Transfer Services

### sftp-service

**What it does:** A full SFTP server built on Apache MINA SSHD. Clients connect via any SFTP client (WinSCP, FileZilla, `sftp` command, etc.) to upload and download files.

**When you need it:** If any of your users or partners use SFTP.

**Run it (minimal SFTP setup):**
```bash
docker compose up -d postgres rabbitmq onboarding-api sftp-service
```

**Ports:**
| Port | Purpose |
|------|---------|
| 2222 | SFTP (SSH protocol) |
| 8081 | Management REST API |

**Key environment variables:**
| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_HOME_BASE` | `/data/sftp` | Base directory for user home folders |
| `SFTP_HOST_KEY_PATH` | `./sftp_host_key` | SSH host key file |
| `SERVER_PORT` | `8081` | Management API port |

**Test it:**
```bash
# 1. Create an account first
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"Test123!"}'

# 2. Connect via SFTP
sftp -P 2222 test@localhost
```

**Storage:** Files are stored at `$SFTP_HOME_BASE/<account-name>/`. Each account gets its own directory with inbox/outbox subfolders.

**Clustering:** Multiple SFTP instances can run behind the gateway service. Each gets a unique `INSTANCE_ID`.

---

### ftp-service

**What it does:** A full FTP/FTPS server built on Apache FTPServer. Supports active and passive mode FTP with optional TLS.

**When you need it:** If any of your users or partners use FTP or FTPS.

**Run it:**
```bash
docker compose up -d postgres rabbitmq onboarding-api ftp-service
```

**Ports:**
| Port | Purpose |
|------|---------|
| 21 | FTP control |
| 21000-21010 | FTP passive data ports |
| 8082 | Management REST API |

**Key environment variables:**
| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_HOME_BASE` | `/data/ftp` | Base directory for FTP homes |
| `FTP_PUBLIC_HOST` | `127.0.0.1` | Public IP for passive mode (set to your external IP!) |
| `FTP_PASSIVE_PORT_START` | `21000` | Start of passive port range |
| `FTP_PASSIVE_PORT_END` | `21010` | End of passive port range |

**Important:** For FTP passive mode to work through firewalls, set `FTP_PUBLIC_HOST` to your server's external IP and open ports 21000-21010 in your firewall.

---

### ftp-web-service

**What it does:** A REST API for file upload/download over HTTP. Powers the web-based file browser UI.

**When you need it:** If users want to upload/download files through a web browser or REST API.

**Run it:**
```bash
docker compose up -d postgres rabbitmq onboarding-api ftp-web-service
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8083 | HTTP REST API |

**Key APIs:**
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/files/list` | List files in a directory |
| POST | `/api/files/upload` | Upload a file (multipart) |
| GET | `/api/files/download` | Download a file |
| DELETE | `/api/files/delete` | Delete a file or directory |
| POST | `/api/files/mkdir` | Create a directory |
| POST | `/api/files/rename` | Rename or move a file |

**Max file size:** 512 MB (configurable via `spring.servlet.multipart.max-file-size`).

---

### as2-service

**What it does:** Implements the AS2 (Applicability Statement 2) protocol for B2B file exchange. Used in EDI and supply chain integrations.

**When you need it:** If you exchange files with partners who require AS2 protocol (common in healthcare, retail, supply chain).

**Run it:**
```bash
docker compose up -d postgres onboarding-api as2-service
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8094 | AS2 HTTP endpoint + REST API |

**Key configuration:**
| Variable | Default | Description |
|----------|---------|-------------|
| `AS2_HOME_BASE` | `/data/as2` | Storage for AS2 messages |
| `AS2_MDN_URL` | `http://localhost:8094/as2/mdn` | MDN callback URL |
| `AS2_MAX_MESSAGE_SIZE` | `524288000` | Max message size (500 MB) |
| `AS2_MDN_ASYNC_TIMEOUT` | `300` | Async MDN timeout (seconds) |

---

### gateway-service

**What it does:** A protocol-level proxy that routes incoming SFTP/FTP connections to the correct backend service instance. It looks up the connecting user in the database and routes them to their assigned server.

**When you need it:** If you run multiple instances of SFTP or FTP services (high availability / load balancing).

**Not needed if:** You run a single instance of each protocol service — just point DMZ proxy directly at the service.

**Run it:**
```bash
docker compose up -d postgres rabbitmq onboarding-api sftp-service ftp-service gateway-service
```

**Ports:**
| Port | Purpose |
|------|---------|
| 2220 | SFTP gateway (proxies to SFTP services) |
| 2122 | FTP gateway (proxies to FTP services) |
| 8085 | Management REST API |

**Routing logic:**
1. User has assigned server → route to that server
2. User exists but no server → route to default internal service
3. Unknown user → route to legacy server (if configured) or reject

---

## Security & Network Services

### dmz-proxy

**What it does:** An AI-powered TCP reverse proxy designed to sit in the DMZ (demilitarized zone). It accepts external connections and relays them to internal services while performing real-time security analysis.

**When you need it:** If your platform is accessible from the internet. This is your security boundary.

**Key design:** The DMZ proxy has **no database access** by design. Even if compromised, it cannot access internal data.

**Run it:**
```bash
docker compose up -d postgres rabbitmq onboarding-api sftp-service \
  gateway-service ai-engine dmz-proxy
```

**Ports:**
| Port | Purpose |
|------|---------|
| 2222 | SFTP relay (→ gateway:2220) |
| 21 | FTP relay (→ gateway:2121) |
| 443 | HTTPS relay (→ ftp-web-service:8083) |
| 8088 | Management REST API (internal only!) |

**Key environment variables:**
| Variable | Default | Description |
|----------|---------|-------------|
| `DMZ_SECURITY_ENABLED` | `true` | Enable AI security layer |
| `AI_ENGINE_URL` | `http://ai-engine:8091` | AI engine endpoint |
| `VERDICT_TIMEOUT_MS` | `200` | Max wait for AI verdict (ms) |
| `DEFAULT_RATE_PER_MINUTE` | `60` | Connections per IP per minute |
| `DEFAULT_MAX_CONCURRENT` | `20` | Max concurrent connections per IP |
| `DEFAULT_MAX_BYTES_PER_MINUTE` | `500000000` | Max bytes per IP per minute (500 MB) |
| `GLOBAL_RATE_PER_MINUTE` | `10000` | Global DDoS threshold |
| `CONTROL_API_KEY` | `internal_control_secret` | Management API key |

**Security features:**
- Rate limiting (per-IP and global)
- AI-powered connection verdicts (ALLOW/THROTTLE/BLOCK/BLACKHOLE)
- Protocol detection (SSH, FTP, HTTP, TLS — from first bytes)
- Connection tracking (per-IP state, bandwidth, ports used)
- Batched async threat event reporting to AI engine
- Graceful degradation if AI engine is unavailable

**Verify it's working:**
```bash
# Check health
curl http://localhost:8088/api/proxy/health

# Check security stats (requires API key)
curl -H "X-Internal-Key: internal_control_secret" \
  http://localhost:8088/api/proxy/security/summary
```

For full details, see [SECURITY-ARCHITECTURE.md](SECURITY-ARCHITECTURE.md).

---

### api-gateway

**What it does:** An Nginx-based reverse proxy that routes all HTTP/REST traffic to the correct backend service. Provides a single entry point for web browsers and API consumers.

**When you need it:** If you want a single URL (e.g., `https://mft.company.com`) for all HTTP traffic.

**Run it:**
```bash
docker compose up -d api-gateway
```

**Ports:**
| Port | Purpose |
|------|---------|
| 80 | HTTP |
| 443 | HTTPS |

**Routes configured in nginx.conf:**
| Path | Backend |
|------|---------|
| `/api/` | onboarding-api:8080 |
| `/config/` | config-service:8084 |
| `/analytics/` | analytics-service:8090 |
| `/license/` | license-service:8089 |
| `/` | admin-ui:3000 |

---

### encryption-service

**What it does:** Encrypts and decrypts files using AES-256 or PGP. Used by file processing flows to encrypt files before delivery or decrypt files on receipt.

**When you need it:** If you need file-level encryption (not just transport-level TLS).

**Run it:**
```bash
docker compose up -d postgres encryption-service
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8086 | REST API |

**Key environment variables:**
| Variable | Default | Description |
|----------|---------|-------------|
| `ENCRYPTION_MASTER_KEY` | (default in config) | Master encryption key — **must change in production!** |
| `ENCRYPTION_MAX_FILE_SIZE` | `536870912` | Max file size (512 MB) |

**Supported algorithms:** AES-256-GCM, PGP (via Bouncy Castle 1.77)

---

### keystore-manager

**What it does:** Centralized management of cryptographic keys, TLS certificates, and SSH key pairs. Provides a REST API for generating, importing, and distributing keys.

**When you need it:** If you manage multiple certificates/keys and want a central store.

**Run it:**
```bash
docker compose up -d postgres keystore-manager
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8093 | REST API |

**Key environment variables:**
| Variable | Default | Description |
|----------|---------|-------------|
| `KEYSTORE_MASTER_PASSWORD` | (insecure default) | Master password for keystore — **must change!** |
| `KEYSTORE_STORAGE_PATH` | `/data/keystores` | Where keystores are stored on disk |

---

### screening-service

**What it does:** Screens names and entities against sanctions lists (US OFAC, EU, UN). Blocks or flags transfers involving sanctioned parties.

**When you need it:** If you have compliance requirements (financial services, defense, healthcare).

**Run it:**
```bash
docker compose up -d postgres screening-service
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8092 | REST API |

**Key environment variables:**
| Variable | Default | Description |
|----------|---------|-------------|
| `SCREENING_MATCH_THRESHOLD` | `0.82` | Similarity threshold (0.0-1.0). Lower = more matches. |
| `SCREENING_DEFAULT_ACTION` | `BLOCK` | What to do on match: `BLOCK` or `FLAG` |

**Sanctions lists loaded:**
- US Treasury OFAC SDN list (18,698 entries, auto-refreshed every 24h)
- EU sanctions list (auto-refreshed)
- UN sanctions list

---

## Intelligence Services

### ai-engine

**What it does:** The AI brain of the platform. Provides data classification (PCI/PII/PHI detection), anomaly detection, NLP command processing, risk scoring, partner profiling, and real-time proxy threat intelligence.

**When you need it:**
- If you want automatic sensitive data detection
- If you want anomaly detection
- If you want the NLP admin terminal
- If you use the DMZ proxy security layer

**Run it:**
```bash
docker compose up -d postgres ai-engine
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8091 | REST API |

**Key environment variables:**
| Variable | Default | Description |
|----------|---------|-------------|
| `CLAUDE_API_KEY` | (none) | Anthropic Claude API key. Optional — NLP features fall back to keyword matching. |
| `CLAUDE_MODEL` | `claude-sonnet-4-20250514` | Claude model to use |

**AI features that work without an LLM:**
- PCI/PII/PHI data classification (regex-based)
- Anomaly detection (statistical z-score)
- Risk scoring (rule-based)
- Smart retry logic
- Partner behavior profiling
- Proxy threat intelligence
- File format detection
- Content validation

**Features that benefit from an LLM (optional):**
- NLP admin terminal (natural language → commands)
- Flow builder (describe in English → processing flow)
- Transfer failure explanation

**Scheduled tasks:**
| Task | Interval | What it does |
|------|----------|-------------|
| Anomaly detection | Every 5 min | Scans transfer patterns for statistical outliers |
| Partner profile rebuild | Every 10 min | Updates behavioral profiles per account |
| IP reputation decay | Every 5 min | Drifts reputation scores toward neutral |
| Alert expiration | Every 1 min | Cleans up expired alerts |
| Connection pattern cleanup | Every 1 hour | Evicts stale connection tracking data |

---

### analytics-service

**What it does:** Aggregates transfer metrics, provides real-time dashboards, and generates predictive forecasts (e.g., "SFTP service will reach capacity in 48 hours").

**When you need it:** If you want metrics, dashboards, or alerting.

**Run it:**
```bash
docker compose up -d postgres rabbitmq analytics-service
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8090 | REST API |

**Key configuration:**
| Setting | Default | Description |
|---------|---------|-------------|
| Aggregation interval | 60 min | How often metrics are aggregated |
| Prediction window | 48 hours | How far ahead to forecast |
| Error rate alert threshold | 5% | Alert when error rate exceeds this |

---

## Storage & Delivery Services

### storage-manager

**What it does:** Enterprise-grade file storage with tiered lifecycle management. Files automatically move from hot (SSD) to warm to cold storage based on age.

**When you need it:** If you process large volumes of files and need cost-effective storage tiering.

**Run it:**
```bash
docker compose up -d postgres storage-manager
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8094 | REST API |

**Storage tiers:**
| Tier | Path | Default Size | Retention |
|------|------|-------------|-----------|
| Hot | `/data/storage/hot` | 100 GB | 7 days, then moves to warm |
| Warm | `/data/storage/warm` | 500 GB | 30 days, then moves to cold |
| Cold | `/data/storage/cold` | 2 TB | 365 days, then purged |
| Backup | `/data/storage/backup` | — | Permanent |

---

### external-forwarder-service

**What it does:** Delivers files to external destinations — other SFTP servers, FTP servers, or Kafka topics.

**When you need it:** If you need to send files to partners or external systems automatically.

**Run it:**
```bash
docker compose up -d postgres external-forwarder-service
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8087 | REST API |

**Supported destinations:**
- External SFTP servers (via Apache SSHD)
- External FTP servers (via Apache Commons Net)
- Kafka topics (via Spring Kafka)

---

### edi-converter

**What it does:** Converts between EDI formats. Translates X12, EDIFACT, HL7, SWIFT, and other standards to/from JSON, CSV, XML.

**When you need it:** If you process EDI files (healthcare, supply chain, finance).

**Run it:**
```bash
docker compose up -d edi-converter
```

**No database required.** This is a stateless converter.

**Ports:**
| Port | Purpose |
|------|---------|
| 8095 | REST API |

**Supported formats:**
X12, EDIFACT, TRADACOMS, SWIFT, HL7, NACHA, BAI2, ISO20022, FIX

**Supported X12 transaction types:**
| Code | Name |
|------|------|
| 837 | Health Care Claim |
| 835 | Health Care Payment |
| 850 | Purchase Order |
| 856 | Ship Notice |
| 270/271 | Eligibility Inquiry/Response |
| 997 | Functional Acknowledgment |
| 810 | Invoice |
| 820 | Payment Order |
| 834 | Benefit Enrollment |

---

## Management Services

### license-service

**What it does:** Manages platform licenses. Includes a built-in 30-day free trial.

**When you need it:** If you need license enforcement.

**Run it:**
```bash
docker compose up -d postgres license-service
```

**Ports:**
| Port | Purpose |
|------|---------|
| 8089 | REST API |

**Key environment variables:**
| Variable | Default | Description |
|----------|---------|-------------|
| `LICENSE_ADMIN_KEY` | `license_admin_secret_key` | Admin key for license management |
| `LICENSE_TRIAL_DAYS` | `30` | Free trial duration |

---

## Frontend Applications

All frontends are React 18 + Vite + TailwindCSS, served by Nginx in Docker.

### admin-ui

**What it does:** The admin dashboard. 34 pages covering accounts, flows, security, analytics, compliance, and more.

**Run it:**
```bash
docker compose up -d admin-ui
```

**Port:** 3000 → Open http://localhost:3000

**Smart feature detection:** The UI only shows pages for services that are actually running. If you didn't start the screening service, the OFAC page won't appear.

---

### ftp-web-ui

**What it does:** A simple file browser for end users. Upload, download, rename, delete files through the browser.

**Run it:**
```bash
docker compose up -d ftp-web-service ftp-web-ui
```

**Port:** 3001 → Open http://localhost:3001

---

### partner-portal

**What it does:** A self-service portal for trading partners. Partners can manage their own files, view transfer history, and check SLAs.

**Run it:**
```bash
docker compose up -d onboarding-api partner-portal
```

**Port:** 3002 → Open http://localhost:3002

---

## Client Tools

### mft-client

**What it does:** A desktop MFT client with built-in JRE. Runs on any OS. Supports SFTP/FTP with features like auto-sync, P2P transfer, inbox monitoring, and file tracking.

**How to use it:**
```bash
chmod +x installer/mft
./installer/mft setup          # Configure connection
./installer/mft send file.csv  # Send a file
./installer/mft inbox          # Check inbox
./installer/mft status         # Connection status
```

See the [mft-client README](../mft-client/README.md) for full documentation.

---

### cli

**What it does:** A Java-based CLI tool for administrators. Provides programmatic access to all platform APIs.

**Note:** This is a library module (no Docker container). Build and run directly:
```bash
cd cli
mvn package -DskipTests
java -jar target/cli-*.jar
```

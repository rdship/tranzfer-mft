# TranzFer MFT — Complete Installation & Configuration Guide

> This document covers everything needed to install TranzFer MFT from scratch:
> hardware, OS, database, message broker, networking, firewall rules, DNS, TLS,
> storage, and service configuration.

---

## Table of Contents

1. [Platform Architecture](#platform-architecture)
2. [Hardware Requirements](#hardware-requirements)
3. [Operating System](#operating-system)
4. [Database Configuration (PostgreSQL)](#database-configuration)
5. [Message Broker (RabbitMQ)](#message-broker)
6. [Network Architecture](#network-architecture)
7. [Firewall Configuration](#firewall-configuration)
8. [DNS & TLS Certificates](#dns--tls)
9. [Storage Requirements](#storage-requirements)
10. [Environment Variables](#environment-variables)
11. [Installation Recipes](#installation-recipes)
12. [Post-Install Verification](#post-install-verification)
13. [Kubernetes Deployment](#kubernetes-deployment)
14. [Troubleshooting](#troubleshooting)

---

## Platform Architecture

TranzFer has **17 services** in 5 tiers. Install only what you need.

```
┌─────────────────────────────────────────────────────────────────────┐
│  TIER 1: INFRASTRUCTURE (Required)                                   │
│  PostgreSQL :5432          RabbitMQ :5672                            │
├─────────────────────────────────────────────────────────────────────┤
│  TIER 2: CORE (Required)                                             │
│  onboarding-api :8080      config-service :8084                     │
├─────────────────────────────────────────────────────────────────────┤
│  TIER 3: TRANSFER (Per protocol)                                     │
│  sftp :2222   ftp :21   ftp-web :8083   gateway :2220   dmz :8088  │
├─────────────────────────────────────────────────────────────────────┤
│  TIER 4: INTELLIGENCE (Optional)                                     │
│  encryption :8086   forwarder :8087   ai-engine :8091               │
│  screening :8092    analytics :8090                                  │
├─────────────────────────────────────────────────────────────────────┤
│  TIER 5: FRONTENDS (Optional)                                        │
│  admin-ui :3000   file-portal :3001   license :8089                 │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Hardware Requirements

### Minimum (dev/test — up to 10K transfers/day)

| Resource | Requirement |
|----------|------------|
| CPU | 4 cores |
| RAM | 8 GB |
| Disk (OS + app) | 20 GB SSD |
| Disk (file storage) | 50 GB (depends on file sizes) |
| Network | 100 Mbps |

### Recommended (production — up to 1M transfers/day)

| Resource | Requirement |
|----------|------------|
| CPU | 8+ cores (16 for full platform) |
| RAM | 32 GB |
| Disk (OS + app) | 50 GB SSD |
| Disk (file storage) | 500 GB+ SSD/NVMe |
| Disk (database) | 100 GB SSD (separate from app) |
| Network | 1 Gbps+ |

### High Volume (500M+ transfers/day — multi-node)

| Resource | Per Node |
|----------|---------|
| CPU | 16+ cores |
| RAM | 64 GB |
| Storage | NVMe, separate mounts for /data and /db |
| Network | 10 Gbps |
| Nodes | 3+ (behind load balancer) |

---

## Operating System

### Supported

| OS | Version | Status |
|----|---------|--------|
| Ubuntu | 22.04 LTS, 24.04 LTS | Recommended |
| RHEL / Rocky Linux | 8.x, 9.x | Supported |
| Amazon Linux | 2023 | Supported |
| Debian | 11, 12 | Supported |
| macOS | 13+ (Ventura) | Dev/test only |
| Windows Server | 2019, 2022 | Via Docker Desktop |

### Required Packages

```bash
# Ubuntu/Debian
sudo apt update && sudo apt install -y \
  openjdk-21-jdk maven docker.io docker-compose-plugin \
  curl git postgresql-client netcat-openbsd

# RHEL/Rocky
sudo dnf install -y \
  java-21-openjdk-devel maven docker docker-compose \
  curl git postgresql nc

# macOS
brew install openjdk@21 maven docker
```

### System Tuning (Production)

```bash
# /etc/sysctl.conf — add these for high-volume file transfer
net.core.somaxconn = 65535
net.ipv4.tcp_max_syn_backlog = 65535
net.core.netdev_max_backlog = 65535
fs.file-max = 2097152
vm.max_map_count = 262144

# /etc/security/limits.conf
* soft nofile 65535
* hard nofile 65535
* soft nproc 65535
* hard nproc 65535

# Apply
sudo sysctl -p
```

---

## Database Configuration

### PostgreSQL Requirements

| Parameter | Dev | Production |
|-----------|-----|-----------|
| Version | 14+ | **16** (recommended) |
| Database name | `filetransfer` | `filetransfer` |
| Default user | `postgres` | Dedicated `mft_user` |
| Max connections | 100 | **300+** |
| Shared buffers | 128MB | **4GB** (25% of RAM) |
| Work mem | 4MB | **64MB** |
| Effective cache size | 1GB | **12GB** (75% of RAM) |
| Storage | Any | **SSD/NVMe required** |

### Option A: Docker (dev/small deployments)

Included in `docker-compose.yml`. No extra setup needed.

```yaml
# Already configured in docker-compose.yml
postgres:
  image: postgres:16-alpine
  environment:
    POSTGRES_DB: filetransfer
    POSTGRES_USER: postgres
    POSTGRES_PASSWORD: postgres  # CHANGE IN PRODUCTION
  volumes:
    - postgres_data:/var/lib/postgresql/data
```

### Option B: Dedicated PostgreSQL Server (production)

```bash
# Install
sudo apt install postgresql-16

# Create database and user
sudo -u postgres psql << 'SQL'
CREATE USER mft_user WITH PASSWORD 'your-strong-password-here';
CREATE DATABASE filetransfer OWNER mft_user;
GRANT ALL PRIVILEGES ON DATABASE filetransfer TO mft_user;

-- Required extensions
\c filetransfer
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
SQL
```

**Production postgresql.conf tuning:**

```ini
# /etc/postgresql/16/main/postgresql.conf

# Connections
max_connections = 300
superuser_reserved_connections = 5

# Memory (for 16GB RAM server)
shared_buffers = 4GB
effective_cache_size = 12GB
work_mem = 64MB
maintenance_work_mem = 512MB

# Write-Ahead Log
wal_level = replica
max_wal_size = 2GB
min_wal_size = 512MB
checkpoint_completion_target = 0.9

# Query Planner
random_page_cost = 1.1          # SSD
effective_io_concurrency = 200   # SSD

# Logging (PCI DSS 10.x)
logging_collector = on
log_directory = 'pg_log'
log_filename = 'postgresql-%Y-%m-%d.log'
log_statement = 'ddl'
log_connections = on
log_disconnections = on
log_duration = on
log_min_duration_statement = 1000  # Log queries > 1 second

# SSL (PCI DSS 4.1)
ssl = on
ssl_cert_file = '/etc/ssl/certs/pg-server.crt'
ssl_key_file = '/etc/ssl/private/pg-server.key'
```

**pg_hba.conf (restrict access):**

```
# TYPE  DATABASE        USER            ADDRESS                 METHOD
local   all             postgres                                peer
host    filetransfer    mft_user        10.0.0.0/8              scram-sha-256
host    filetransfer    mft_user        172.16.0.0/12           scram-sha-256
hostssl filetransfer    mft_user        0.0.0.0/0               scram-sha-256
```

### Option C: Managed Database (AWS RDS, Azure, GCP)

| Cloud | Service | Recommendation |
|-------|---------|---------------|
| AWS | RDS PostgreSQL 16 | `db.r6g.large` or higher |
| Azure | Azure Database for PostgreSQL Flexible Server | 4 vCores, 16GB |
| GCP | Cloud SQL for PostgreSQL | `db-custom-4-16384` |

```bash
# Connection string for managed DB
DATABASE_URL=jdbc:postgresql://your-rds-endpoint.region.rds.amazonaws.com:5432/filetransfer
DB_USERNAME=mft_user
DB_PASSWORD=your-rds-password
```

### Database Tables (auto-created)

TranzFer creates **20+ tables** automatically on first startup via Hibernate DDL.
No manual schema creation needed.

| Table | Service | Purpose |
|-------|---------|---------|
| `users` | onboarding-api | System users (admin, operators) |
| `transfer_accounts` | onboarding-api | SFTP/FTP transfer accounts |
| `folder_mappings` | onboarding-api | File routing rules |
| `file_transfer_records` | shared | Every file transfer with track ID + checksums |
| `audit_logs` | shared | PCI-compliant immutable audit trail |
| `file_flows` | config-service | Processing pipelines |
| `flow_executions` | config-service | Pipeline execution history |
| `security_profiles` | config-service | SSH cipher/MAC/KEX configs |
| `service_registrations` | shared | Service discovery registry |
| `license_records` | license-service | License keys |
| `webhook_connectors` | shared | ServiceNow/Slack/PagerDuty configs |
| `sanctions_entries` | screening-service | OFAC/EU/UN sanctions lists |
| `screening_results` | screening-service | Screening audit trail |
| `metric_snapshots` | analytics-service | Hourly metrics |
| `alert_rules` | analytics-service | Configurable alerts |
| `transfer_tickets` | onboarding-api | P2P transfer authorization |
| `client_presence` | onboarding-api | Online MFT clients |

### Backup Strategy

```bash
# Daily backup (run via cron)
pg_dump -U mft_user -h localhost -Fc filetransfer > /backups/mft-$(date +%F).dump

# Restore
pg_restore -U mft_user -h localhost -d filetransfer /backups/mft-2025-01-15.dump

# Point-in-time recovery (requires WAL archiving)
# Configure in postgresql.conf:
archive_mode = on
archive_command = 'cp %p /wal-archive/%f'
```

---

## Message Broker

### RabbitMQ Requirements

| Parameter | Dev | Production |
|-----------|-----|-----------|
| Version | 3.12+ | **3.13** |
| Erlang | 26+ | 26+ |
| Memory | 256MB | **2GB+** |
| Disk | 1GB | 10GB |

### Docker (included)

```yaml
# Already in docker-compose.yml
rabbitmq:
  image: rabbitmq:3.13-management-alpine
  environment:
    RABBITMQ_DEFAULT_USER: guest      # CHANGE IN PRODUCTION
    RABBITMQ_DEFAULT_PASS: guest      # CHANGE IN PRODUCTION
```

### Dedicated RabbitMQ (production)

```bash
# Install
sudo apt install rabbitmq-server

# Create MFT vhost and user
sudo rabbitmqctl add_vhost mft
sudo rabbitmqctl add_user mft_user 'strong-password'
sudo rabbitmqctl set_permissions -p mft mft_user ".*" ".*" ".*"
sudo rabbitmqctl set_user_tags mft_user monitoring

# Enable management UI
sudo rabbitmq-plugins enable rabbitmq_management
```

**Production config (/etc/rabbitmq/rabbitmq.conf):**

```ini
# Connections
channel_max = 2048
heartbeat = 60

# Memory
vm_memory_high_watermark.relative = 0.7
disk_free_limit.absolute = 2GB

# Logging
log.file.level = warning
```

### Managed RabbitMQ

| Cloud | Service |
|-------|---------|
| AWS | Amazon MQ for RabbitMQ |
| Azure | Azure Service Bus (AMQP) |
| CloudAMQP | Managed RabbitMQ (any cloud) |

### Exchanges and Queues (auto-created)

| Exchange | Type | Queues |
|----------|------|--------|
| `file-transfer.events` | Topic | `sftp.account.events`, `ftp.account.events`, `ftpweb.account.events` |

---

## Network Architecture

### Single-Server Deployment

```
                Internet
                    │
            ┌───────▼───────┐
            │  Firewall/LB  │
            │  (nginx/HAProxy)
            └───┬───┬───┬───┘
                │   │   │
    Port 2222 ──┘   │   └── Port 443 (HTTPS)
    (SFTP)          │
              Port 3000 (Admin UI)
                    │
            ┌───────▼───────┐
            │  TranzFer     │
            │  Server       │
            │  (Docker)     │
            └───────────────┘
```

### DMZ Deployment (recommended for production)

```
    Internet                    DMZ                         Internal Network
   ──────────           ─────────────────              ─────────────────────
                    ┌─────────────────┐            ┌───────────────────────┐
  Partners ──────→  │  DMZ Proxy      │ ────────→  │  Gateway Service      │
  (SFTP/FTP)        │  :8088          │            │  :2220/:2121          │
                    │  (stateless,    │            │         │              │
                    │   no DB access) │            │  SFTP Service :2222   │
                    └─────────────────┘            │  FTP Service :21      │
                                                   │  Config :8084         │
  Admins ─────────────────────────────────────→    │  Admin UI :3000       │
  (HTTPS only)                                     │  AI Engine :8091      │
                                                   │  Screening :8092      │
                                                   │         │              │
                                                   │  PostgreSQL :5432     │
                                                   │  RabbitMQ :5672       │
                                                   └───────────────────────┘
```

### Multi-Region Deployment

```
   US-EAST                    EU-WEST                    APAC
┌────────────┐           ┌────────────┐           ┌────────────┐
│ SFTP x3    │           │ SFTP x2    │           │ SFTP x5    │
│ Gateway x2 │           │ Gateway x2 │           │ Gateway x3 │
│ Admin UI   │           │ Admin UI   │           │ Admin UI   │
│ PostgreSQL │           │ PostgreSQL │           │ PostgreSQL │
│ RabbitMQ   │           │ RabbitMQ   │           │ RabbitMQ   │
└────────────┘           └────────────┘           └────────────┘
  cluster-id:              cluster-id:              cluster-id:
  us-east-1                eu-west-1                apac-1
```

Each cluster is independent. Set `CLUSTER_ID` environment variable per region.

---

## Firewall Configuration

### Server-Side Firewall Rules

#### Inbound (Internet → Server)

| Port | Protocol | Source | Service | Required? |
|------|----------|--------|---------|:---------:|
| **2222** | TCP | Partners | SFTP Server | If using SFTP |
| **21** | TCP | Partners | FTP Command | If using FTP |
| **21000-21010** | TCP | Partners | FTP Passive Data | If using FTP |
| **443** | TCP | Users/Admins | HTTPS (Admin UI, File Portal) | If using web UIs |
| **3000** | TCP | Admin network | Admin Dashboard | Dev only (use 443 in prod) |
| **3001** | TCP | Internal users | File Portal | Dev only (use 443 in prod) |

#### Inbound (DMZ → Internal)

| Port | Protocol | Source | Service | Note |
|------|----------|--------|---------|------|
| 2220 | TCP | DMZ Proxy | Gateway SFTP | Internal only |
| 2121 | TCP | DMZ Proxy | Gateway FTP | Internal only |
| 8080-8092 | TCP | Internal LAN | API services | Never expose to internet |

#### Outbound (Server → Internet)

| Port | Protocol | Destination | Purpose | Required? |
|------|----------|-------------|---------|:---------:|
| **443** | TCP | treasury.gov | OFAC sanctions list download | If using screening |
| **443** | TCP | api.anthropic.com | Claude AI API | If using NLP features |
| **443** | TCP | hooks.slack.com | Slack notifications | If using Slack connector |
| **443** | TCP | *.service-now.com | ServiceNow incidents | If using ServiceNow |
| **443** | TCP | events.pagerduty.com | PagerDuty alerts | If using PagerDuty |
| **5432** | TCP | Managed DB | PostgreSQL (if external) | If using RDS/Cloud SQL |

#### Internal (Service ↔ Service)

| Port | Protocol | Purpose |
|------|----------|---------|
| 5432 | TCP | All services → PostgreSQL |
| 5672 | TCP | Services → RabbitMQ |
| 8080-8092 | TCP | Inter-service REST calls |

### Linux Firewall (iptables/nftables)

```bash
# UFW (Ubuntu)
sudo ufw allow 2222/tcp comment "SFTP"
sudo ufw allow 443/tcp comment "HTTPS"
sudo ufw allow from 10.0.0.0/8 to any port 5432 comment "PostgreSQL internal"
sudo ufw allow from 10.0.0.0/8 to any port 5672 comment "RabbitMQ internal"
sudo ufw allow from 10.0.0.0/8 to any port 8080:8092 proto tcp comment "MFT services internal"
sudo ufw enable

# firewalld (RHEL)
sudo firewall-cmd --permanent --add-port=2222/tcp
sudo firewall-cmd --permanent --add-port=443/tcp
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="10.0.0.0/8" port port="5432" protocol="tcp" accept'
sudo firewall-cmd --reload
```

### AWS Security Groups

```
# SG: mft-public (for SFTP/FTP servers)
Inbound:  TCP 2222 from 0.0.0.0/0 (SFTP)
Inbound:  TCP 443  from 0.0.0.0/0 (HTTPS)

# SG: mft-internal (for all other services)
Inbound:  TCP 5432 from sg-mft-internal (PostgreSQL)
Inbound:  TCP 5672 from sg-mft-internal (RabbitMQ)
Inbound:  TCP 8080-8092 from sg-mft-internal (APIs)

# SG: mft-db (for PostgreSQL)
Inbound:  TCP 5432 from sg-mft-internal only
```

### Client-Side Firewall (for MFT Client users)

| Direction | Port | Destination | Purpose |
|-----------|------|-------------|---------|
| **Outbound** | **2222** | MFT Server | SFTP (only port needed) |
| Outbound | 21+21000-21010 | MFT Server | FTP (if using FTP) |
| Outbound | 443 | MFT Server | HTTPS File Portal |
| Inbound | 9900 | Other clients | P2P receiver (only if using P2P) |

> **Tell partners: "Open outbound TCP 2222 to our server IP. That's it."**

---

## DNS & TLS

### DNS Records (production)

| Record | Type | Value | Purpose |
|--------|------|-------|---------|
| `mft.yourcompany.com` | A | Server IP | SFTP/FTP endpoint |
| `admin.mft.yourcompany.com` | A/CNAME | Server IP / LB | Admin dashboard |
| `files.mft.yourcompany.com` | A/CNAME | Server IP / LB | File portal |
| `api.mft.yourcompany.com` | A/CNAME | Server IP / LB | REST APIs |

### TLS Certificates

```bash
# Option 1: Let's Encrypt (free)
sudo certbot certonly --standalone -d mft.yourcompany.com \
  -d admin.mft.yourcompany.com -d files.mft.yourcompany.com

# Option 2: Commercial cert
# Place at:
#   /etc/ssl/certs/mft.crt
#   /etc/ssl/private/mft.key

# Option 3: Self-signed (dev only)
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout /etc/ssl/private/mft.key -out /etc/ssl/certs/mft.crt \
  -subj "/CN=mft.yourcompany.com"
```

### Reverse Proxy (nginx)

For production, put nginx in front:

```nginx
# /etc/nginx/sites-available/mft

# Admin UI
server {
    listen 443 ssl;
    server_name admin.mft.yourcompany.com;
    ssl_certificate /etc/ssl/certs/mft.crt;
    ssl_certificate_key /etc/ssl/private/mft.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
    }
}

# File Portal
server {
    listen 443 ssl;
    server_name files.mft.yourcompany.com;
    ssl_certificate /etc/ssl/certs/mft.crt;
    ssl_certificate_key /etc/ssl/private/mft.key;
    client_max_body_size 512M;

    location / {
        proxy_pass http://127.0.0.1:3001;
    }
    location /api/ {
        proxy_pass http://127.0.0.1:8083;
    }
}
```

---

## Storage Requirements

### File Storage Mounts

| Mount Point | Service | Purpose | Recommended Size |
|-------------|---------|---------|-----------------|
| `/data/sftp` | sftp-service | SFTP user home directories | 100GB+ |
| `/data/ftp` | ftp-service | FTP user home directories | 100GB+ |
| `/data/ftpweb` | ftp-web-service | Web upload storage | 50GB+ |
| `/data/quarantine` | guaranteed-delivery | Quarantined failed files (never deleted) | 20GB |
| `/tmp/mft-flow-work` | flow-engine | Temporary flow processing files | 10GB (auto-cleaned) |

```bash
# Create storage directories
sudo mkdir -p /data/{sftp,ftp,ftpweb,quarantine}
sudo chown -R 1000:1000 /data/

# For Docker, add volume mounts in docker-compose.yml:
volumes:
  - /data/sftp:/data/sftp
  - /data/ftp:/data/ftp
```

### Storage Monitoring

Monitor these — if `/data` fills up, transfers fail:

```bash
# Add to cron (check every 5 min)
*/5 * * * * df -h /data | awk 'NR==2 {if ($5+0 > 85) print "ALERT: /data at " $5}'
```

---

## Environment Variables

### Complete Reference

All services read from the same set of environment variables.
Set them in `.env`, `docker-compose.yml`, or Kubernetes ConfigMap.

#### Required (all deployments)

| Variable | Example | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://postgres:5432/filetransfer` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `mft_user` | Database username |
| `DB_PASSWORD` | `s3cur3_pa55w0rd!` | Database password |
| `RABBITMQ_HOST` | `rabbitmq` | RabbitMQ hostname |
| `JWT_SECRET` | `openssl rand -base64 32` | **Must be ≥ 256 bits** |
| `CONTROL_API_KEY` | `openssl rand -hex 16` | Inter-service auth |

#### Required (production)

| Variable | Example | Description |
|----------|---------|-------------|
| `CLUSTER_ID` | `prod-us-east-1` | Unique cluster identifier |
| `ENCRYPTION_MASTER_KEY` | `openssl rand -hex 32` | 256-bit AES master key |
| `LICENSE_ADMIN_KEY` | `openssl rand -hex 16` | License management key |

#### Optional

| Variable | Default | Description |
|----------|---------|-------------|
| `TRACK_ID_PREFIX` | `TRZ` | First 3 chars of tracking IDs |
| `CLAUDE_API_KEY` | (empty) | Anthropic API key for NLP features |
| `SCREENING_THRESHOLD` | `0.82` | Sanctions fuzzy match threshold |
| `SCREENING_DEFAULT_ACTION` | `BLOCK` | Action on sanctions hit |
| `FTP_PUBLIC_HOST` | `127.0.0.1` | FTP passive mode host |

#### Generate all secrets at once

```bash
cat > .env << ENV
DATABASE_URL=jdbc:postgresql://postgres:5432/filetransfer
DB_USERNAME=mft_user
DB_PASSWORD=$(openssl rand -base64 24)
RABBITMQ_HOST=rabbitmq
JWT_SECRET=$(openssl rand -base64 32)
CONTROL_API_KEY=$(openssl rand -hex 16)
ENCRYPTION_MASTER_KEY=$(openssl rand -hex 32)
LICENSE_ADMIN_KEY=$(openssl rand -hex 16)
CLUSTER_ID=prod-$(hostname)-1
TRACK_ID_PREFIX=TRZ
ENV

echo "Generated .env with unique secrets"
chmod 600 .env  # Restrict permissions
```

---

## Installation Recipes

### Recipe 1: SFTP Only (5 services)

```bash
docker compose up -d postgres rabbitmq onboarding-api sftp-service config-service
```

### Recipe 2: SFTP + FTP (6 services)

```bash
docker compose up -d postgres rabbitmq onboarding-api sftp-service ftp-service config-service
```

### Recipe 3: Full Transfer, No AI (12 services)

```bash
docker compose up -d postgres rabbitmq \
  onboarding-api sftp-service ftp-service ftp-web-service \
  config-service gateway-service encryption-service \
  external-forwarder-service dmz-proxy admin-ui
```

### Recipe 4: Everything (17 services)

```bash
docker compose up --build -d
```

### Recipe 5: SFTP + Screening (7 services)

```bash
docker compose up -d postgres rabbitmq \
  onboarding-api sftp-service config-service screening-service admin-ui
```

### Recipe 6: AI-Powered (8 services)

```bash
docker compose up -d postgres rabbitmq \
  onboarding-api sftp-service config-service \
  ai-engine analytics-service admin-ui
```

---

## Post-Install Verification

### Quick Check

```bash
# All containers running?
docker compose ps | grep "Up"

# Create admin user
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@yourcompany.com","password":"Str0ng!Pass"}'
docker exec mft-postgres psql -U postgres -d filetransfer \
  -c "UPDATE users SET role='ADMIN' WHERE email='admin@yourcompany.com';"
```

### Full Test Suite

```bash
./tests/run-tests.sh --html
open tests/reports/test-report-*.html
```

### Connectivity Tests

```bash
# Test SFTP
echo "ls" | sftp -P 2222 -o StrictHostKeyChecking=no testuser@localhost

# Test database
docker exec mft-postgres psql -U postgres -d filetransfer -c "SELECT count(*) FROM users"

# Test screening
curl -s http://localhost:8092/api/v1/screening/health | python3 -m json.tool

# Test AI
curl -s http://localhost:8091/api/v1/ai/health | python3 -m json.tool
```

---

## Kubernetes Deployment

See [INSTALL-KUBERNETES.md](INSTALL-KUBERNETES.md) for detailed Helm chart instructions.

```bash
helm install mft ./helm/mft-platform --namespace mft --create-namespace \
  --set global.imageRegistry="your-registry.com/" \
  --set global.jwtSecret="$(openssl rand -base64 32)" \
  --set sftpService.enabled=true \
  --set screeningService.enabled=true
```

---

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| Service exits immediately | Schema validation fail | Run `onboarding-api` first (ddl-auto: update), then restart others |
| `too many clients already` | PostgreSQL max_connections | Increase to 300: `ALTER SYSTEM SET max_connections = 300; SELECT pg_reload_conf();` |
| SFTP "Connection refused" | Port not exposed | Check `docker compose ps` for port mapping |
| SFTP "Permission denied" | Wrong password or account not synced | Check RabbitMQ is running; restart sftp-service |
| Admin UI blank | CORS or backend not ready | Wait 30s after startup; check onboarding-api logs |
| Screening empty | OFAC download failed | Check outbound HTTPS to treasury.gov; call POST /api/v1/screening/lists/refresh |
| AI NLP returns generic response | No Claude API key | Set `CLAUDE_API_KEY` env var; keyword fallback still works |
| File stuck in PENDING | Routing engine or dest service down | GuaranteedDeliveryService auto-retries every 60s |
| ServiceNow not creating incidents | Wrong URL or auth | Test: POST /api/connectors/{id}/test |

### Viewing Logs

```bash
# All services
docker compose logs -f

# Specific service
docker compose logs -f sftp-service

# Filter for errors
docker compose logs sftp-service 2>&1 | grep -i "error\|fail\|exception"
```

---

## Service Reference

| Service | Port(s) | DB | MQ | Tier |
|---------|---------|:--:|:--:|------|
| postgres | 5432 | — | — | Infrastructure |
| rabbitmq | 5672/15672 | — | — | Infrastructure |
| onboarding-api | 8080 | Yes | Yes | Core |
| config-service | 8084 | Yes | Yes | Core |
| sftp-service | 2222/8081 | Yes | Yes | Transfer |
| ftp-service | 21/8082 | Yes | Yes | Transfer |
| ftp-web-service | 8083 | Yes | Yes | Transfer |
| gateway-service | 2220/2121/8085 | Yes | Yes | Transfer |
| dmz-proxy | 8088 | **No** | No | Transfer |
| encryption-service | 8086 | Yes | No | Intelligence |
| forwarder-service | 8087 | Yes | Yes | Intelligence |
| ai-engine | 8091 | Yes | No | Intelligence |
| screening-service | 8092 | Yes | No | Intelligence |
| analytics-service | 8090 | Yes | Yes | Intelligence |
| license-service | 8089 | Yes | No | Frontend |
| admin-ui | 3000 | No | No | Frontend |
| ftp-web-ui | 3001 | No | No | Frontend |

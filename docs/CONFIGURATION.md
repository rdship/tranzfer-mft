# TranzFer MFT — Configuration Reference

Every environment variable, port, and configuration option in one place.

---

## Table of Contents

1. [Port Map](#port-map)
2. [Shared Environment Variables](#shared-environment-variables)
3. [Per-Service Configuration](#per-service-configuration)
4. [Security-Sensitive Variables](#security-sensitive-variables)
5. [Docker Compose Overrides](#docker-compose-overrides)

---

## Port Map

### External (expose to internet via firewall)

| Port | Service | Protocol | Purpose |
|------|---------|----------|---------|
| 32222 | dmz-proxy | TCP | SFTP access (maps to internal 2222) |
| 32121 | dmz-proxy | TCP | FTP access (maps to internal 21) |
| 4443 | dmz-proxy | TCP | HTTPS access (maps to internal 443) |
| 80 | api-gateway | HTTP | REST API |
| 443 | api-gateway | HTTPS | REST API (TLS) |

### Internal (do NOT expose to internet)

| Port | Service | Purpose |
|------|---------|---------|
| 2222 | sftp-service | SFTP server |
| 2223 | sftp-service-2 | SFTP server (replica) |
| 21 | ftp-service | FTP control |
| 21000-21010 | ftp-service | FTP passive data |
| 2121 | ftp-service-2 | FTP control (replica) |
| 21100-21110 | ftp-service-2 | FTP passive data (replica) |
| 2220 | gateway-service | SFTP gateway proxy |
| 2122 | gateway-service | FTP gateway proxy |
| 8080 | onboarding-api | Main REST API |
| 8081 | sftp-service | Management API |
| 8082 | ftp-service | Management API |
| 8083 | ftp-web-service | HTTP file API |
| 8084 | config-service | Configuration API |
| 8085 | gateway-service | Management API |
| 8086 | encryption-service | Encryption API |
| 8087 | external-forwarder | Forwarder API |
| 8088 | dmz-proxy | Management API |
| 8089 | license-service | License API |
| 8090 | analytics-service | Analytics API |
| 8091 | ai-engine | AI / Proxy Intelligence API |
| 8092 | screening-service | Screening API |
| 8093 | keystore-manager | Keystore API |
| 8094 | as2-service / storage-manager | AS2 / Storage API |
| 8095 | edi-converter | EDI API |
| 8096 | sftp-service-2 | Management API (replica) |
| 8097 | ftp-service-2 | Management API (replica) |
| 8098 | ftp-web-service-2 | Management API (replica) |
| 3000 | admin-ui | Admin dashboard |
| 3001 | ftp-web-ui | File browser |
| 3002 | partner-portal | Partner portal |
| 5432 | postgresql | Database |
| 5672 | rabbitmq | AMQP messaging |
| 15672 | rabbitmq | Management UI |

---

## Shared Environment Variables

These variables are used by multiple services. Set them once in your `.env` file or docker-compose override.

### Database

| Variable | Default | Used By | Description |
|----------|---------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://postgres:5432/filetransfer` | All Java services with DB | JDBC connection URL |
| `DB_USERNAME` | `postgres` | All Java services with DB | Database username |
| `DB_PASSWORD` | `postgres` | All Java services with DB | Database password |

### Security

| Variable | Default | Used By | Description |
|----------|---------|---------|-------------|
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | onboarding-api, shared | JWT signing key. **Must change in production.** |
| `CONTROL_API_KEY` | `internal_control_secret` | All services | API key for internal endpoints |

### Cluster

| Variable | Default | Used By | Description |
|----------|---------|---------|-------------|
| `CLUSTER_ID` | `cluster-1` | All services | Cluster identifier |
| `CLUSTER_COMM_MODE` | `WITHIN_CLUSTER` | All services | Communication mode |
| `CLUSTER_HOST` | (service name) | All services | Hostname for service discovery |

### Platform

| Variable | Default | Used By | Description |
|----------|---------|---------|-------------|
| `PLATFORM_ENVIRONMENT` | `PROD` | All services | Environment name |
| `TRACK_ID_PREFIX` | `TRZ` | onboarding-api | Prefix for transfer tracking IDs |

### Messaging

| Variable | Default | Used By | Description |
|----------|---------|---------|-------------|
| `RABBITMQ_HOST` | `rabbitmq` | Services with AMQP | RabbitMQ hostname |
| `RABBITMQ_PORT` | `5672` | Services with AMQP | RabbitMQ AMQP port |
| `RABBITMQ_USERNAME` | `guest` | Services with AMQP | RabbitMQ username |
| `RABBITMQ_PASSWORD` | `guest` | Services with AMQP | RabbitMQ password |

---

## Per-Service Configuration

### onboarding-api

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8080` | HTTP port |
| `JWT_EXPIRATION_MS` | `900000` | Token expiry (15 min) |
| `SFTP_HOME_BASE` | `/data/sftp` | SFTP home directory base |
| `FTP_HOME_BASE` | `/data/ftp` | FTP home directory base |
| `FTPWEB_HOME_BASE` | `/data/ftpweb` | FTP-Web home directory base |

### sftp-service

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8081` | Management API port |
| `SFTP_HOME_BASE` | `/data/sftp` | User home directories |
| `SFTP_HOST_KEY_PATH` | `./sftp_host_key` | SSH host key file |
| `SFTP_PORT` | `2222` | SFTP listen port |
| `INSTANCE_ID` | `sftp-1` | Instance identifier for clustering |

### ftp-service

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8082` | Management API port |
| `FTP_HOME_BASE` | `/data/ftp` | User home directories |
| `FTP_PUBLIC_HOST` | `127.0.0.1` | Public IP for passive mode |
| `FTP_PORT` | `21` | FTP listen port |
| `FTP_PASSIVE_PORT_START` | `21000` | Passive port range start |
| `FTP_PASSIVE_PORT_END` | `21010` | Passive port range end |

### ftp-web-service

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8083` | HTTP API port |
| `FTPWEB_HOME_BASE` | `/data/ftpweb` | User home directories |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `512MB` | Max upload size |

### gateway-service

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8085` | Management API port |
| `GATEWAY_SFTP_PORT` | `2220` | SFTP gateway listen port |
| `GATEWAY_FTP_PORT` | `2122` | FTP gateway listen port |
| `GATEWAY_HOST_KEY_PATH` | `./gateway_host_key` | SSH host key for SFTP proxy |
| `INTERNAL_SFTP_HOST` | `sftp-service` | Default SFTP backend |
| `INTERNAL_SFTP_PORT` | `2222` | Default SFTP backend port |
| `INTERNAL_FTP_HOST` | `ftp-service` | Default FTP backend |
| `INTERNAL_FTP_PORT` | `21` | Default FTP backend port |
| `INTERNAL_FTPWEB_HOST` | `ftp-web-service` | Default FTP-Web backend |
| `INTERNAL_FTPWEB_PORT` | `8083` | Default FTP-Web backend port |

### dmz-proxy

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8088` | Management API port |
| `DMZ_SECURITY_ENABLED` | `true` | Enable/disable AI security layer |
| `AI_ENGINE_URL` | `http://ai-engine:8091` | AI engine base URL |
| `VERDICT_TIMEOUT_MS` | `200` | Max wait for AI verdict (ms) |
| `DEFAULT_RATE_PER_MINUTE` | `60` | Per-IP connection rate limit |
| `DEFAULT_MAX_CONCURRENT` | `20` | Per-IP concurrent connection limit |
| `DEFAULT_MAX_BYTES_PER_MINUTE` | `500000000` | Per-IP bandwidth limit (500 MB) |
| `GLOBAL_RATE_PER_MINUTE` | `10000` | Global DDoS threshold |
| `GATEWAY_HOST` | `gateway-service` | Default SFTP/FTP target host |
| `FTPWEB_HOST` | `ftp-web-service` | Default HTTPS target host |

### ai-engine

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8091` | API port |
| `CLAUDE_API_KEY` | (none) | Anthropic API key. Optional. |
| `CLAUDE_MODEL` | `claude-sonnet-4-20250514` | Claude model for NLP features |
| `AI_CLASSIFICATION_MAX_SCAN_BYTES` | `104857600` | Max file scan size (100 MB) |
| `AI_BLOCK_UNENCRYPTED_PCI` | `true` | Block files with unencrypted credit card numbers |

### encryption-service

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8086` | API port |
| `ENCRYPTION_MASTER_KEY` | (insecure default) | Master encryption key. **Must change!** |
| `ENCRYPTION_MAX_FILE_SIZE` | `536870912` | Max file size (512 MB) |

### screening-service

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8092` | API port |
| `SCREENING_MATCH_THRESHOLD` | `0.82` | Sanctions match threshold (0-1) |
| `SCREENING_DEFAULT_ACTION` | `BLOCK` | Action on match: BLOCK or FLAG |

### license-service

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8089` | API port |
| `LICENSE_ADMIN_KEY` | `license_admin_secret_key` | Admin key for license management |
| `LICENSE_TRIAL_DAYS` | `30` | Free trial duration |

### keystore-manager

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8093` | API port |
| `KEYSTORE_MASTER_PASSWORD` | (insecure default) | Keystore master password. **Must change!** |
| `KEYSTORE_STORAGE_PATH` | `/data/keystores` | Keystore file location |

### storage-manager

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8094` | API port |
| `STORAGE_HOT_PATH` | `/data/storage/hot` | Hot tier path |
| `STORAGE_HOT_CAPACITY` | `107374182400` | Hot tier capacity (100 GB) |
| `STORAGE_HOT_RETENTION_HOURS` | `168` | Hours before moving to warm (7 days) |
| `STORAGE_WARM_PATH` | `/data/storage/warm` | Warm tier path |
| `STORAGE_COLD_PATH` | `/data/storage/cold` | Cold tier path |

### as2-service

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8094` | API port |
| `AS2_HOME_BASE` | `/data/as2` | AS2 message storage |
| `AS2_MDN_URL` | `http://localhost:8094/as2/mdn` | MDN callback URL |
| `AS2_MAX_MESSAGE_SIZE` | `524288000` | Max message (500 MB) |

### analytics-service

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8090` | API port |
| `ANALYTICS_AGGREGATION_INTERVAL` | `60` | Minutes between aggregation runs |
| `ANALYTICS_PREDICTION_WINDOW` | `48` | Hours to forecast ahead |
| `ANALYTICS_ERROR_RATE_THRESHOLD` | `5` | Error rate % to trigger alert |

### edi-converter

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8095` | API port |

### external-forwarder-service

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8087` | API port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers (if using Kafka) |

---

## Security-Sensitive Variables

These **must** be changed before production deployment:

| Variable | Default | Risk if unchanged |
|----------|---------|-------------------|
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | Anyone can forge authentication tokens |
| `DB_PASSWORD` | `postgres` | Full database access |
| `CONTROL_API_KEY` | `internal_control_secret` | Access to all internal APIs |
| `ENCRYPTION_MASTER_KEY` | (hardcoded in config) | Can decrypt all encrypted files |
| `KEYSTORE_MASTER_PASSWORD` | (hardcoded in config) | Can extract all private keys |
| `LICENSE_ADMIN_KEY` | `license_admin_secret_key` | Can manipulate licenses |
| `RABBITMQ_PASSWORD` | `guest` | Full message bus access |

**Recommended approach:**
```bash
# Create a .env file (never commit this!)
cat > .env << 'EOF'
JWT_SECRET=$(openssl rand -hex 32)
DB_PASSWORD=$(openssl rand -hex 16)
CONTROL_API_KEY=$(openssl rand -hex 16)
ENCRYPTION_MASTER_KEY=$(openssl rand -hex 32)
KEYSTORE_MASTER_PASSWORD=$(openssl rand -base64 32)
LICENSE_ADMIN_KEY=$(openssl rand -hex 16)
RABBITMQ_PASSWORD=$(openssl rand -hex 16)
EOF
```

---

## Docker Compose Overrides

To customize configuration without editing `docker-compose.yml`, create a `docker-compose.override.yml`:

```yaml
# docker-compose.override.yml
services:
  sftp-service:
    environment:
      SFTP_HOME_BASE: /custom/path/sftp
    ports:
      - "2222:2222"  # Expose directly (skip DMZ proxy for development)

  dmz-proxy:
    environment:
      DEFAULT_RATE_PER_MINUTE: "120"      # Double the rate limit
      DEFAULT_MAX_CONCURRENT: "50"        # More concurrent connections
      VERDICT_TIMEOUT_MS: "500"           # More time for AI verdicts
```

Docker Compose automatically merges `docker-compose.override.yml` with `docker-compose.yml`.

For production, use a named override:
```bash
docker compose -f docker-compose.yml -f docker-compose.production.yml up -d
```

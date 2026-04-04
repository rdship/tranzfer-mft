# On-Premise Installation Guide

See the main [README.md](../README.md) for full architecture details.

## Prerequisites

| Requirement | Minimum | Recommended |
|-------------|---------|-------------|
| OS | Linux x86_64/arm64 | Ubuntu 22.04 / RHEL 9 |
| Java | 21 LTS | Eclipse Temurin 21 |
| PostgreSQL | 14 | 16 |
| RabbitMQ | 3.12 | 3.13 |
| RAM | 8 GB | 16 GB+ |
| Docker | 24.0+ | Latest stable |

## Docker Compose (Recommended)

```bash
git clone https://github.com/rdship/tranzfer-mft.git && cd tranzfer-mft
cp .env.production .env   # Edit with your real secrets
mvn clean package -DskipTests
docker compose up -d
docker compose ps          # Verify all 15 containers are UP
```

## Bare Metal (systemd)

For each service, create `/etc/systemd/system/mft-<service>.service`:

```ini
[Unit]
Description=TranzFer <ServiceName>
After=network.target postgresql.service

[Service]
User=mft
Environment=DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer
Environment=DB_PASSWORD=changeme
Environment=JWT_SECRET=your-256-bit-secret
ExecStart=/usr/bin/java -Xmx1g -jar /opt/mft/<service>/app.jar
Restart=always

[Install]
WantedBy=multi-user.target
```

## Minimum Services by Use Case

| Use Case | Services |
|----------|----------|
| SFTP only | postgres, rabbitmq, onboarding-api, sftp-service |
| FTP only | postgres, rabbitmq, onboarding-api, ftp-service |
| Web uploads | postgres, rabbitmq, onboarding-api, ftp-web-service, ftp-web-ui |
| Full platform | All 15 containers |

## Firewall

| Port | Open To |
|------|---------|
| 2222 (SFTP) | External partners |
| 21 (FTP) | External partners |
| 3000 (Admin) | Internal admins only |
| 3001 (Portal) | Internal users |
| 8080-8090 | Internal network only |

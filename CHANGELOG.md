# Changelog

## v2.0.0 (2026-04-04)

### New Services
- **License Service** — RSA-2048 signed license keys, 30-day free trial, per-service enforcement
- **Analytics Service** — Hourly metric aggregation, linear regression predictions, configurable alert rules

### New UIs
- **Admin Dashboard v2** — 16-page React dashboard with analytics charts, scaling predictions, security profile management, license management, white-labeling support
- **FTP-Web UI** — User-facing file portal with drag-and-drop upload, directory navigation, file download

### New Features
- **Security Profiles** — Configurable SSH ciphers, MACs, KEX algorithms, TLS settings, mTLS support
- **Scaling Predictions** — Traffic trend analysis with replica count recommendations per service
- **Alert Rules** — Configurable thresholds for error rate, latency, and transfer volume
- **White-Labeling** — Custom logo, company name, and brand colors via admin settings
- **Helm Charts** — Full Kubernetes deployment with HPA, Ingress, Secrets, and ServiceMonitor
- **OTEL Support** — OpenTelemetry collector configuration for metrics and traces
- **Multi-Cluster** — Each cluster operates independently with unique CLUSTER_ID

### Infrastructure
- Upgraded to Java 21 LTS
- PostgreSQL max_connections configurable
- All services externalized via environment variables (fully stateless)
- Production deployment templates (.env.production, values-production.yaml)

## v1.0.0 (2026-04-03)

### Initial Release
- 11 microservices: Onboarding API, SFTP, FTP, FTP-Web, Config, Gateway, Encryption, External Forwarder, DMZ Proxy, CLI
- Shared library with JPA entities and routing engine
- Docker Compose orchestration
- Basic admin UI
- Multi-protocol support (SFTP, FTP, HTTP)
- Folder-based file routing with regex matching
- AES-256 and PGP encryption
- DMZ isolation proxy
- RabbitMQ event-driven architecture

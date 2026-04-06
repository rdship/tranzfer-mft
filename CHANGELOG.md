# Changelog

## v2.1.0 (2026-04-06)

### New Features
- **Natural Language Mapping Correction** — Partners can fix EDI field mappings through plain English instructions. The AI interprets corrections ("Company name should come from NM1*03"), applies changes, runs sample tests, and iterates until the partner approves. On approval, a new partner-specific ConversionMap is persisted and the file flow is updated automatically.
- **Cross-Format EDI Conversion** — Convert between any EDI formats via the Canonical Data Model bridge. X12 850 → EDIFACT ORDERS, EDIFACT INVOIC → X12 810, HL7 → X12, etc. 4 new output formats (X12, EDIFACT, HL7, SWIFT_MT) bringing total to 110 conversion paths.
- **Compare Suite** — Batch comparison of conversion outputs between two systems. Provide 4 directory paths (or CSV mapping file), engine scans and matches files, runs field-level semantic diffs (EDI/JSON/text), aggregates per-map, and generates a summary report with prioritized fix recommendations including NL correction hints. Supports user confirmation flow before execution.
- **CONVERT_EDI Flow Step** — New file flow step type that applies trained/partner-specific EDI conversion maps during file processing
- **Test Custom Mappings Endpoint** — EDI Converter can now test arbitrary field mappings against sample EDI content without persisting anything (`POST /api/v1/convert/test-mappings`)

### New Endpoints (AI Engine)
- `POST /api/v1/edi/correction/sessions` — Start correction session
- `POST /api/v1/edi/correction/sessions/{id}/correct` — Submit NL correction
- `POST /api/v1/edi/correction/sessions/{id}/approve` — Approve & persist map
- `POST /api/v1/edi/correction/sessions/{id}/reject` — Reject corrections
- `GET /api/v1/edi/correction/sessions/{id}/history` — Correction history

### New Endpoints (EDI Converter)
- `POST /api/v1/convert/test-mappings` — Test custom field mappings
- `POST /api/v1/convert/trained` — Convert using trained partner-specific map
- `POST /api/v1/convert/compare/prepare` — Prepare batch comparison (scan + match)
- `POST /api/v1/convert/compare/prepare/upload` — Prepare from CSV mapping file
- `POST /api/v1/convert/compare/execute/{id}` — Execute comparison
- `GET /api/v1/convert/compare/reports/{id}` — Get comparison report
- `GET /api/v1/convert/compare/reports/{id}/summary` — Human-readable summary

### Database
- V16 migration: `edi_mapping_correction_sessions` table for correction session state

### Tests
- 112 new tests (811 total across 19 services, 0 failures)

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

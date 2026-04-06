# Changelog

## v2.3.0 (2026-04-06)

### Per-Listener Security Profiles
- **3-Tier Security**: Each server listener (SFTP, FTP, AS2, etc.) gets its own security profile — RULES, AI, or AI+LLM
- **RULES tier** (<1ms): IP whitelist/blacklist with CIDR support, geo-blocking/allowing by country, rate limiting per source IP, concurrent connection limits, bandwidth throttling, file extension filters, file size limits, transfer windows (day/time restrictions), idle timeout, max auth attempts, require encryption, connection logging
- **AI tier** (~5ms avg): Everything in RULES + internal AI engine verdict (IP reputation, geo-anomaly detection, protocol threat detection, connection pattern analysis, threat intelligence). 90%+ cache hit rate for sub-ms typical latency
- **AI+LLM tier** (~50ms avg): Everything in AI + Claude LLM escalation for borderline cases (risk score 30-70). LLM only fires for ~5-10% of connections. Requires `ai.llm.enabled=true`
- **Same proxy, different security**: Different listeners on different ports can have different security tiers through the same DMZ proxy
- **Outbound = RULES only**: External destinations get proxy routing + security rules (no AI overhead for outbound connections you initiate)
- **Dynamic proxy detection**: UI only shows running proxy services in dropdown, auto-fills DMZ proxy config when detected
- **Overhead estimates**: UI displays real-time latency estimates per security tier

### New Endpoints
- `GET /api/listener-security-policies` — List all security policies
- `POST /api/listener-security-policies` — Create policy (auto-pushes to DMZ proxy)
- `PUT /api/listener-security-policies/{id}` — Update policy (hot-reconfigures proxy)
- `GET /api/listener-security-policies/server/{id}` — Policy for a server instance
- `GET /api/listener-security-policies/destination/{id}` — Policy for an external destination
- `PUT /api/proxy/mappings/{name}/security-policy` — Hot-update proxy security policy
- `GET /api/v1/proxy/overhead-estimates` — Security tier overhead estimates

### Database
- V18 migration: `listener_security_policies` table with JSONB rules, FK to server_instances and external_destinations

### Tests
- 52 new tests (ManualSecurityFilter: 40, LlmSecurityEscalation: 12)

## v2.2.0 (2026-04-06)

### EDI Converter — Production Hardening (4 Phases)
- **Phase 1: Real Parsing** — ISA-driven delimiter detection, composite/repeating element support, UNA-aware EDIFACT parsing with release character handling, hierarchical loop detection (10 X12 transaction types), per-segment error recovery
- **Phase 2: Real Validation** — 35 X12 + 21 EDIFACT segment definition registries, element-level validation (type, length, code set), 19 business rules (X12/EDIFACT/HL7), version-aware validation (005010/004010)
- **Phase 3: Real Conversion** — Full canonical model (references, dates, contacts, notes), 40+ new segment mappings (forward + reverse), spec-compliant ISA generation (106 chars, AtomicLong control numbers), round-trip fidelity
- **Phase 4: Real AI** — Claude API integration for mapping inference + NL entity extraction, persistent trained maps (file-based, survive restarts), cache statistics and management endpoints

### LLM Opt-In
- External LLM usage is now explicitly opt-in via `ai.llm.enabled` platform setting (default: off)
- Settings > AI tab: toggle, API key, model selector (Sonnet/Haiku/Opus), endpoint URL (http/https), connection test
- All AI features continue to work without LLM using built-in pattern matching
- V17 migration seeds AI platform settings

### External Endpoint Enhancements
- Full protocol selection: SFTP, FTP, FTPS, HTTP, HTTPS, API
- Proxy routing: DMZ, HTTP, SOCKS5 proxy configuration per destination
- Connection test: `POST /api/forward/test-connection` with proxy support
- Auto-detection of DMZ Proxy service with setup guidance

### New Endpoints
- `POST /api/forward/test-connection` — Test external endpoint connectivity (with optional proxy)
- `GET /api/v1/convert/trained/maps` — List persisted trained maps
- `GET /api/v1/convert/trained/cache-stats` — Cache statistics
- `POST /api/v1/convert/trained/invalidate-all` — Full cache invalidation (memory + disk)

### Database
- V17 migration: `ai.llm.enabled`, `ai.llm.api-key`, `ai.llm.model`, `ai.llm.base-url` platform settings

### Tests
- 324 new tests (1135 total across 19 services, 0 failures)

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

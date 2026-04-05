# TranzFer MFT — Gap Analysis

> Last updated: April 2026

This document identifies gaps in documentation, configuration, security, testing, and operational readiness across the TranzFer MFT platform.

---

## 1. Documentation Gaps

| # | Gap | Impact | Recommendation |
|---|-----|--------|----------------|
| D1 | **No per-module README files** — 19 of 20 modules have no README. Only `mft-client` has one. GitHub users navigating to a module directory see nothing. | High — Contributors and adopters can't understand a module without reading source code. | Created `docs/SERVICES.md` as a comprehensive service catalog. Consider adding individual READMEs for the 5 most complex modules. |
| D2 | **No API reference** — All REST endpoints are only discoverable by reading Java controller source code. No OpenAPI/Swagger spec. | High — External integrators have no way to discover APIs without source access. | Created `docs/API-REFERENCE.md` with all endpoints. Add Springdoc OpenAPI (`springdoc-openapi-starter-webmvc-ui`) to auto-generate Swagger UI. |
| D3 | **No centralized configuration reference** — Env vars are scattered across 18 `application.yml` files. No single source of truth. | Medium — Operators mis-configure deployments because they don't know what's available. | Created `docs/CONFIGURATION.md` with every env var, port, and default. |
| D4 | **No security architecture document** — The DMZ proxy + AI engine security layer (2000+ lines of new code) is undocumented. | High — Security auditors and operators can't assess the threat model. | Created `docs/SECURITY-ARCHITECTURE.md` with full threat model, data flows, and configuration. |
| D5 | **No developer/contributor guide** — No instructions for building individual modules, running tests, debugging, or IDE setup. | Medium — New contributors have high onboarding friction. | Created `docs/DEVELOPER-GUIDE.md`. |
| D6 | **No architecture deep-dive** — Root README has a basic ASCII diagram but no detailed document on service communication, data flows, or deployment topology. | Medium — Hard to understand how the 20 services interact. | Created `docs/ARCHITECTURE.md`. |
| D7 | **docs/INSTALLATION.md references "17 services"** — Platform now has 20 microservices (AS2, EDI converter, storage manager added). | Low — Inaccurate count causes confusion. | Update INSTALLATION.md service count and tier diagram. |
| D8 | **No documentation index** — The `docs/` folder has 7 files with no README or table of contents. | Medium — Users don't know which doc to read for their use case. | Created `docs/README.md` as a documentation hub. |

---

## 2. Security Gaps

| # | Gap | Impact | Recommendation |
|---|-----|--------|----------------|
| S1 | **Default secrets in source code** — JWT secret (`change_me_in_production_256bit_secret_key!!`), control API key (`internal_control_secret`), database password (`postgres`), encryption master key are hardcoded defaults in `application.yml` files. | Critical — If deployed without changing, all secrets are publicly known from the GitHub repo. | ✅ **RESOLVED**: `scripts/preflight-check.sh` detects 9 secret categories in env vars or compose files. Fails deployment in PROD mode. Defaults remain in dev/test YAML for local development convenience. |
| S2 | **DMZ proxy management API (port 8088) has no TLS** — Only protected by `X-Internal-Key` header over plaintext HTTP. | High — If DMZ management port is exposed, the API key is sent in cleartext. | Add TLS support to management API or document that port 8088 must never be exposed externally. |
| S3 | **No mutual TLS between DMZ proxy and AI engine** — Communication is plaintext HTTP on the internal network. | Medium — If internal network is compromised, verdict responses can be tampered with. | Add mTLS or at minimum a shared HMAC signature on verdict responses. |
| S4 | **AI engine has no authentication on proxy endpoints** — Anyone on the internal network can call `/api/v1/proxy/verdict`, manipulate blocklists, or inject fake events. Now also applies to 30+ new `/api/v1/threats/*` endpoints from `ThreatIntelligenceController`. | Medium — Internal lateral movement could bypass all security. | Add API key or mTLS authentication to AI engine proxy and threat intelligence endpoints. Note: `ThreatIntelligenceController` added with 30+ endpoints but auth is still pending (via shared module's JWT filter). |
| S5 | **No TLS between services** — All inter-service HTTP is plaintext. Only external-facing connections use TLS. | Medium — Acceptable in a private Docker network but not in multi-tenant cloud deployments. | Document as a known limitation. Add service mesh (Istio/Linkerd) or Spring Boot TLS for production. |
| S6 | **notification-service is a stub** — Empty module with no implementation. | Low — Security alerts, rate limit notifications, and brute force alerts have no delivery mechanism. | Implement email/Slack/webhook notification service or document as Phase 2. |

---

## 3. Testing Gaps

| # | Gap | Impact | Recommendation |
|---|-----|--------|----------------|
| T1 | **No integration tests for DMZ proxy ↔ AI engine communication** — Unit tests mock each side independently. No test verifies the full verdict flow over HTTP. | High — Protocol mismatch between proxy and AI engine would not be caught until deployment. | ✅ **RESOLVED**: 15 WireMock-based tests in `AiVerdictClientIntegrationTest` (DMZ side) + 12 SpringBootTest tests in `ProxyIntelligenceControllerIntegrationTest` (AI engine side). Covers verdict flow, caching, fallback, event reporting, blocklist/allowlist. |
| T2 | **No load/stress tests** — No benchmarks for verdict latency under load, cache hit rates, or rate limiter accuracy at scale. | Medium — Performance claims (sub-millisecond, 10K connections) are untested. | Add JMH benchmarks for hot path and Gatling/k6 load tests for AI engine endpoints. |
| T3 | **No end-to-end test for file transfer through DMZ** — No automated test verifying: external SFTP client → DMZ proxy → gateway → SFTP service → file lands on disk. | High — Critical path is untested end-to-end. | Add Docker Compose-based E2E test with an SFTP client container. |
| T4 | **Frontend tests missing** — No unit tests, component tests, or E2E tests for admin-ui, ftp-web-ui, or partner-portal. | Medium — 34 admin pages with zero test coverage. | Add Vitest for unit tests and Playwright for E2E tests. |
| T5 | **Screening service test coverage unknown** — OFAC matching with 18K entries needs fuzz testing for false positive/negative rates. | Medium — Compliance feature has unclear accuracy. | Add benchmark test with known OFAC matches and near-misses. |

---

## 4. Operational Gaps

| # | Gap | Impact | Recommendation |
|---|-----|--------|----------------|
| O1 | **No centralized logging** — Each service logs to stdout. No ELK/Loki stack configured. | Medium — Debugging production issues across 20 services requires checking each container individually. | Add Loki + Grafana or ELK stack to docker-compose. Document log aggregation setup. |
| O2 | **No alerting pipeline** — Prometheus metrics are collected but no AlertManager rules are defined. | Medium — Anomalies detected by AI engine have no automated notification path. | Add AlertManager with rules for: service down, high error rate, DDoS detected, disk full. |
| O3 | **No backup/restore procedure** — PostgreSQL data is in a Docker volume with no documented backup strategy. | High — Data loss risk in production. | ✅ **RESOLVED**: `scripts/backup.sh` (automated pg_dump with retention), `scripts/restore.sh` (safe restore with pre-restore backup + validation), `scripts/backup-cron-setup.sh` (daily cron + K8s CronJob YAML). Supports Docker and direct connection modes. |
| O4 | **No health check for DMZ proxy security layer** — Docker health check only hits Spring Boot actuator, not the Netty listeners or AI engine connectivity. | Low — Security layer could be degraded while health check reports healthy. | Add `/api/proxy/security/summary` to health check chain. |
| O5 | **Single PostgreSQL instance** — All 15 services share one database. No read replicas, no connection pooling (PgBouncer). | Medium — Database becomes bottleneck at scale. One slow query affects all services. | Document PgBouncer setup. Consider per-service schemas or separate databases for high-volume services. |
| O6 | **No graceful shutdown orchestration** — `docker compose down` stops all services simultaneously. DMZ proxy may drop active connections. | Low — Acceptable for dev. Not for production with SLA requirements. | Document rolling restart procedure. Add `stop_grace_period` to docker-compose. |

---

## 5. Feature Gaps

| # | Gap | Impact | Recommendation |
|---|-----|--------|----------------|
| F1 | **No FTPS listener in DMZ proxy** — Proxy relays raw TCP but doesn't terminate or detect FTPS (implicit TLS on port 990) as a first-class listener. | Low — FTPS works through port mapping but isn't exposed by default in docker-compose. | Add a default FTPS mapping (port 990 → gateway) if FTPS support is needed. |
| F2 | **No WebSocket support for real-time dashboard** — Admin UI polls REST endpoints. No live-updating security dashboard. | Low — Acceptable for v1. | Add WebSocket/SSE endpoint for real-time security event streaming. |
| F3 | **AI engine proxy intelligence has no persistence** — IP reputation, connection patterns, and verdicts are in-memory only. Service restart loses all learned data. | Medium — After restart, all IPs are "new" again, losing reputation history. | ✅ **RESOLVED**: V15 Flyway migration adds `verdict_records` and `security_events` tables. `ThreatIntelligenceStore` provides DB persistence with in-memory cache. All verdicts, threat indicators, and security events are persisted to PostgreSQL and restored on startup. |
| F4 | **No rate limit for REST management APIs** — The proxy management API and AI engine API have no rate limiting on their own endpoints. | Low — Internal APIs, but could be abused if exposed. | Add Spring Security rate limiting or a simple token bucket on management endpoints. |
| F5 | **GeoIP lookup not integrated** — `GeoAnomalyDetector` accepts country codes but has no MaxMind GeoIP2 or IP-API integration to resolve IP → country automatically. | Medium — Country-based threat detection requires manual country input or external feed. | ✅ **RESOLVED**: `GeoIpResolver` service uses ip-api.com with a 50K IP cache, automatic country/city/ISP resolution, and impossible travel detection. Accessible via REST at `/api/v1/threats/geo/resolve/{ip}`. |
| F6 | **No AS2 MDN verification** — AS2 service receives MDNs but verification logic is placeholder. | Medium — B2B compliance requires signed MDN verification. | Implement RFC 4130 MDN signature verification. |

---

## 6. Priority Matrix

### Must Fix (Before Production)

| ID | Description | Status |
|----|-------------|--------|
| S1 | Remove default secrets from source code | ✅ Pre-flight script (`scripts/preflight-check.sh`) detects 9 secret categories, fails deployment if defaults found in PROD |
| O3 | Document backup/restore procedures | ✅ `scripts/backup.sh`, `scripts/restore.sh`, `scripts/backup-cron-setup.sh` with Docker/direct/K8s modes |
| T1 | Add integration tests for DMZ ↔ AI engine | ✅ 15 WireMock tests (AiVerdictClientIntegrationTest) + 12 SpringBootTest tests (ProxyIntelligenceControllerIntegrationTest) |
| D2 | Add API reference documentation | ✅ `docs/API-REFERENCE.md` |
| D4 | Document security architecture | ✅ `docs/SECURITY-ARCHITECTURE.md` |

### Should Fix (First Production Release)

| ID | Description | Status |
|----|-------------|--------|
| S4 | Add authentication to AI engine proxy + threat intel endpoints | Open — 30+ new threat intel endpoints also lack auth |
| S2 | Add TLS to DMZ management API | Open |
| F3 | Add persistence for AI reputation data | ✅ V15 migration + ThreatIntelligenceStore with DB persistence |
| F5 | Integrate GeoIP database | ✅ GeoIpResolver with ip-api.com + 50K cache |
| T3 | Add E2E file transfer test | Open |
| O1 | Set up centralized logging | Open |

### Nice to Have (Future Releases)

| ID | Description |
|----|-------------|
| S3 | mTLS between DMZ proxy and AI engine |
| F2 | WebSocket real-time dashboard |
| T2 | Load testing suite |
| T4 | Frontend test coverage |
| O2 | AlertManager rules |

---

## 7. Summary

| Category | Critical | High | Medium | Low | Total | Resolved |
|----------|----------|------|--------|-----|-------|----------|
| Documentation | 0 | 3 | 4 | 1 | **8** | 6 |
| Security | 1 | 1 | 3 | 1 | **6** | 1 |
| Testing | 0 | 2 | 3 | 0 | **5** | 1 |
| Operations | 0 | 1 | 3 | 2 | **6** | 1 |
| Features | 0 | 0 | 3 | 3 | **6** | 2 |
| **Total** | **1** | **7** | **16** | **7** | **31** | **11** |

The platform is architecturally strong with comprehensive feature coverage. All **Must Fix** items are now resolved: S1 (pre-flight secret detection), O3 (backup/restore scripts), T1 (DMZ ↔ AI integration tests), D2 (API reference), D4 (security architecture). Two additional **Should Fix** items are now resolved: F3 (AI engine persistence via V15 migration + ThreatIntelligenceStore) and F5 (GeoIP integration via GeoIpResolver with ip-api.com). Remaining work focuses on TLS hardening (S2/S3/S5), AI engine authentication (S4 -- now more urgent with 30+ new threat intel endpoints), E2E testing (T3), and operational tooling (O1/O2).

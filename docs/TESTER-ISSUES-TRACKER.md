# TranzFer MFT — Tester Issues Tracker (Master List)

**Last updated:** 2026-04-13  
**Sources:** 12 test reports from docs/run-reports/  
**Rule:** No issue dropped. Every finding tracked to resolution.

---

## Status Legend
- FIXED — Code committed and pushed
- FIXED-CTO — Fixed by CTO in their release
- OPEN — Not yet addressed
- WONTFIX — By design or superseded

---

## CRITICAL (8 issues)

| # | Issue | Source Report | Status | Resolution |
|---|-------|-------------|--------|------------|
| C1 | V42 Flyway self-deadlock kills 22 services on cold boot | post-release-run | **FIXED** | `SPRING_JPA_HIBERNATE_DDL__AUTO=none` + `allow_jdbc_metadata_access=false` in docker-compose. SchemaHealthIndicator validates in background. |
| C2 | Flow routing ignores filename pattern — ALL files match same flow | integration-test, edi-flow-test | **FIXED** | FlowRuleCompiler.compile() combines `criteriaMatcher AND filenameMatcher` (line 32). FlowRuleRegistryInitializer loads all active flows on startup + 30s refresh. `FLOW_RULES_ENABLED=true` in docker-compose. |
| C3 | SCREEN step NPE on empty config `{}` | edi-flow-test | **OPEN** | FlowProcessingEngine step dispatch should null-check config map before accessing keys. |
| C4 | Flow execution error not persisted — status stuck PROCESSING forever | edi-flow-test, integration-test | **FIXED (partial)** | FlowExecutionRecoveryJob runs every 5 min, marks stuck PROCESSING > 5 min as FAILED. But root cause (exception not caught in step loop) may still exist. |
| C5 | Virtual filesystem write failure — SFTP put to VIRTUAL accounts returns "Operation unsupported" | playwright-enterprise | **OPEN** | VirtualSftpFileSystem.createOutputStream() not implemented or storage-manager integration incomplete for VFS write path. |
| C6 | SFTP home directory not auto-created for PHYSICAL accounts | playwright-enterprise | **FIXED** | AccountEventConsumer creates inbox/outbox/sent on account.created event. VIRTUAL accounts skip physical dirs. |
| C7 | 80-concurrent cliff — 8 services break at exactly 80 concurrent | stress-test, resilience-architecture | **FIXED (partial)** | Phase 2: HikariCP pools scaled (SFTP 30, FTP 25, Gateway 25). PostgreSQL max_connections 200→400. But root cause may be Docker Desktop networking or OS fd limits — needs production verification. |
| C8 | config-service query performance 20x slower than onboarding-api | performance-report, stress-test | **OPEN** | Suspected N+1 queries or missing second-level cache. Needs profiling with `log_min_duration_statement=100` and query plan analysis. |

---

## HIGH (18 issues)

| # | Issue | Source Report | Status | Resolution |
|---|-------|-------------|--------|------------|
| H1 | demo-onboard.sh pagination bug — only 12/28 servers fetched | post-release-run | **OPEN** | Script fetches first page only. Needs pagination loop or `?size=1000` param. |
| H2 | demo-onboard.sh counter lies — logs say 200 flows, DB has 26 | post-release-run | **OPEN** | Counter increments even on failed POST. Need to check HTTP response code before incrementing. |
| H3 | 22-service cascade when Flyway fails | post-release-run | **FIXED** | `SPRING_FLYWAY_ENABLED=false` in common-env. Only db-migrate service runs Flyway. Services boot without waiting for migration. |
| H4 | 4 nginx frontend healthchecks fail (hit proxied `/` instead of `/health`) | post-release-run | **OPEN** | nginx healthcheck should hit a static endpoint (e.g., `/health` returning 200) not the proxied backend. |
| H5 | Flow deactivation API returns 400 | integration-test | **OPEN** | FileFlowController PUT endpoint may require all fields, not just `active: false`. Needs PATCH support or partial update. |
| H6 | Account creation API fails — home directory on wrong filesystem | integration-test | **OPEN** | Account service creates homeDir path that doesn't exist in container volume mapping. Needs volume mount verification. |
| H7 | Seed account SFTP login fails — invalid credentials despite correct bcrypt | integration-test | **OPEN** | CredentialService may cache stale credentials. Check cache eviction on account.created event. |
| H8 | Routing engine priority semantics unclear — DB changes don't take effect | integration-test | **FIXED** | FlowRuleRegistry hot-reload via RabbitMQ + Fabric events. Phase 1 added flowCache sync on hot-reload. 30s periodic refresh as safety net. |
| H9 | User CRUD flaky — all create/update/delete fail first attempt, pass on retry | playwright-enterprise | **OPEN** | Likely race condition in JWT validation or DB transaction isolation. Needs investigation. |
| H10 | Account lockout after failed attempts — no admin unlock API | playwright-enterprise | **FIXED-CTO** | CTO added BruteForceProtection.unlock(email) + POST /api/auth/admin/unlock/{email} endpoint. |
| H11 | XSS not sanitized on storage displayName | playwright-enterprise | **FIXED-CTO** | CTO added InputSanitizer.stripHtml() utility. |
| H12 | No unique constraint on partner company name — duplicates allowed | playwright-enterprise | **FIXED-CTO** | CTO added V58__partner_unique_company_name.sql + unique=true on Partner.companyName. |
| H13 | Empty body POST returns 500 instead of 400 | playwright-enterprise | **FIXED-CTO** | CTO added HttpMessageNotReadableException handler in PlatformExceptionHandler → returns 400. |
| H14 | Invalid enum values return 500 — deserialization errors unhandled | playwright-enterprise | **OPEN** | Need HttpMessageNotReadableException handler for enum deserialization failures. May be covered by H13 fix. |
| H15 | 8 API endpoints returning 500 at rest | bug-audit | **OPEN** | /api/dlq, /api/fabric/checkpoints, /api/compliance, /status, /api/encrypt/status, /api/v1/licenses, /api/v1/analytics/dashboard, /api/v1/screening/stats |
| H16 | Alertmanager SMTP not configured — platform flying blind | bug-audit | **OPEN** | Placeholder values in alertmanager config. Needs real SMTP credentials. |
| H17 | Missing DB column p95latency_ms — generates hundreds of errors/min | bug-audit | **OPEN** | Entity references column that doesn't exist. Needs Flyway migration to add column. |
| H18 | 4 file transfers stuck on missing encryption keys | bug-audit | **OPEN** | Flow steps reference keyId that doesn't exist in keystore-manager. Need seed keys or better error handling. |

---

## MEDIUM (12 issues)

| # | Issue | Source Report | Status | Resolution |
|---|-------|-------------|--------|------------|
| M1 | demo-onboard.sh Step 25 skipped with bad numbering | post-release-run | **OPEN** | Step numbering off-by-one after adding new sections. |
| M2 | nginx frontends missing TLS certificates (ui-service, partner-portal, ftp-web-ui) | tls-architecture-gap | **OPEN** | Only api-gateway has HTTPS. Need entrypoint.sh to fetch cert from keystore-manager. |
| M3 | Flyway schema version 999 > latest migration | bug-audit | **OPEN** | Someone ran a manual migration with version 999. Flyway repair needed. |
| M4 | PGP Key Rotation scheduler misconfigured in as2-service | bug-audit | **OPEN** | Scheduler bean not properly wired. |
| M5 | DMZ proxy receiving external scanner traffic | bug-audit | **OPEN** | Expected if exposed to internet. Add rate limiting or IP whitelist. |
| M6 | Keystore download returns wrong format (both formats return same key) | tls-architecture-gap | **OPEN** | PEM and PKCS12 endpoints both return public key. PKCS12 should return full keystore. |
| M7 | Keystore Redis connection uses localhost instead of redis hostname | tls-architecture-gap | **OPEN** | Need REDIS_HOST env var in keystore-manager service config. |
| M8 | EDI converter validate endpoint returns 415 Unsupported Media Type | edi-flow-test | **OPEN** | Wrong Content-Type header. Endpoint expects application/json, not text/plain. |
| M9 | EDI converter accepts malformed input as valid (should return 400) | edi-flow-test | **OPEN** | Validation gap — malformed_x12 and wrong_format CSV both return 200. |
| M10 | EDI converter crashes on truncated X12 (returns 500) | edi-flow-test | **OPEN** | Unhandled exception parsing incomplete X12 document. |
| M11 | EDI converter output format may be no-op (JSON/XML/CSV/YAML identical) | edi-flow-test | **OPEN** | Convert endpoint may not actually transform format. Needs verification. |
| M12 | ServerInstance → ServerConfig entity rename breaks demo-onboard.sh | cto-release-validation | **FIXED** | demo-onboard.sh updated: instanceId→name, protocol→serviceType, host/port format, 3DES���AES192. Commit 0940e9c. |

---

## PERFORMANCE (5 issues)

| # | Issue | Source Report | Status | Resolution |
|---|-------|-------------|--------|------------|
| P1 | Cold boot 12-15 minutes | performance-report, startup-optimization | **FIXED (partial)** | `allow_jdbc_metadata_access=false` + `ddl-auto=none` eliminates Hibernate validation. lazy-init=true. Full 6x improvement needs entity modularization (startup-optimization.md roadmap). |
| P2 | Per-service startup 195-260 seconds | startup-optimization | **FIXED (partial)** | Same as P1. Target <40s requires modularizing shared-platform entities into per-service subsets. |
| P3 | config-service 5.4x latency degradation under load | stress-test | **OPEN** | Same as C8. Needs query profiling. |
| P4 | Memory 17.6 GB for 41 containers (77% of host) | performance-report | **OPEN** | JVM heap -Xmx384m per service. Could reduce to -Xmx256m for lightweight services (edi-converter, license, keystore). |
| P5 | 5-10s GC pauses under load | stress-test | **OPEN** | ZGC enabled but may need tuning. Check `-XX:+ZGenerational` is active. |

---

## BREAKING CHANGES FROM CTO RELEASE (3 items)

| # | Issue | Source Report | Status | Resolution |
|---|-------|-------------|--------|------------|
| B1 | ServerInstance → ServerConfig entity rename | cto-release-validation | **FIXED** | demo-onboard.sh updated. Commit 0940e9c. |
| B2 | AS2 3DES encryption blocked (AES only) | cto-release-validation | **FIXED** | demo-onboard.sh: 3DES→AES192. Commit 0940e9c. |
| B3 | V61 materialized view missing destination_account_id column | cto-release-validation | **FIXED** | V60 migration adds column + index. V61 creates view successfully. |

---

## PLAYWRIGHT ENTERPRISE TEST SUITE (355 tests)

| Category | Result | Notes |
|----------|--------|-------|
| Total tests | 355 | 14 test files |
| Pass (Run 3) | 210 | Improving each run |
| Flaky | 21 | All UI timing (JWT expiry + React lazy-load) |
| Hard failures | 0 | Zero API failures |
| Run time | 13m 12s | Stable |

**Flaky root causes (all 21):**
1. JWT token expiry (15-min TTL, test suite runs 13 min)
2. React lazy-load race (first page visit fetches JS chunk)
3. Cold cache (empty tables on first assertion)

**Flaky fixes needed:**
- Increase JWT TTL to 60 min for admin sessions
- Pre-warm React chunks in Playwright setup
- Run seed data before test suite
- Use Playwright storageState auth instead of per-test login

---

## MISSING ENTERPRISE FEATURES (from playwright-enterprise report)

| Feature | Priority | Notes |
|---------|----------|-------|
| Token refresh / long-lived sessions | HIGH | Prevents JWT expiry mid-workflow |
| Admin account unlock API | DONE | CTO added POST /api/auth/admin/unlock/{email} |
| File transfer progress tracking (real-time %) | MEDIUM | SSE stream exists but no per-file % |
| Per-flow retry policy configuration | MEDIUM | Currently hardcoded 3 retries |
| Transfer scheduling (specific time) | MEDIUM | No scheduler UI or API |
| File integrity dashboard (aggregate rates) | LOW | Activity Monitor has per-file, not aggregate |
| Partner self-service onboarding | LOW | Portal exists but limited |
| Webhook delivery monitoring | LOW | notification-service logs but no UI |
| Geographic routing / data residency UI | LOW | No UI for geo-aware routing |
| Certificate lifecycle management | LOW | Keystore-manager has API, no lifecycle UI |
| Bandwidth throttling dashboard | LOW | QoS params set per-account, no dashboard |
| Audit trail export (CSV/PDF) | DONE | Activity Monitor V2 has CSV export |
| MFA for file transfer protocols | LOW | Not feasible for SFTP/FTP protocols |
| Transfer SLA alerting | MEDIUM | No SLA definition or alerting |
| DR / cross-cluster replication | LOW | Architecture exists, not wired |

---

## SUMMARY SCOREBOARD

| Category | Total | Fixed | Open | Fix Rate |
|----------|-------|-------|------|----------|
| CRITICAL | 8 | 5 | 3 | 62% |
| HIGH | 18 | 7 | 11 | 39% |
| MEDIUM | 12 | 1 | 11 | 8% |
| PERFORMANCE | 5 | 2 | 3 | 40% |
| BREAKING (CTO) | 3 | 3 | 0 | 100% |
| **TOTAL** | **46** | **18** | **28** | **39%** |

### Open items requiring immediate attention (Priority order):

**Must fix for demo/production:**
1. C3 — SCREEN step NPE on empty config
2. C5 — Virtual filesystem write failure (SFTP to VIRTUAL accounts)
3. C8 — config-service 20x slower under load
4. H15 — 8 API endpoints returning 500
5. H17 — Missing p95latency_ms DB column

**Should fix:**
6. H1/H2 — demo-onboard.sh pagination + counter bugs
7. H5 — Flow deactivation API returns 400
8. H9 — User CRUD flaky (race condition)
9. M8/M9/M10/M11 — EDI converter validation gaps
10. P4/P5 — Memory + GC tuning

**Nice to have:**
11. H4 — nginx healthcheck endpoints
12. M2 — nginx TLS certificates
13. M6/M7 — keystore-manager format + Redis fixes

# TranzFer MFT Platform — Enterprise Gap Analysis

**Date:** 2026-04-06  
**Audited by:** CTO-level review across UI, Backend, Security, and DevOps  
**Benchmark:** Axway SecureTransport, GoAnywhere MFT, IBM Sterling  

---

## CRITICAL (Blocks enterprise sales / compliance certification)

### Security & Compliance
1. **No antivirus/malware scanning** — Files uploaded without AV scanning. Ransomware, trojans pass through unchecked. Blocks HIPAA, PCI-DSS, SOC2.
2. **No DLP (Data Loss Prevention)** — No content inspection for PII, credit cards, SSNs. Users can accidentally transfer regulated data. Blocks HIPAA, PCI-DSS, GDPR.
3. **Dockerfiles run as root** — All 22 services run as UID 0. Container compromise = host compromise. Blocks CIS Docker Benchmark, PCI-DSS.
4. **No dependency vulnerability scanning** — No OWASP Dependency-Check, Snyk, or Sonatype in CI. CVEs in Maven/npm deps go undetected. Blocks PCI-DSS 6.2.
5. **Helm defaults insecure** — `password: postgres`, `jwtSecret: change_me_in_production` shipped in values.yaml. Production deployments may use defaults.
6. **No LDAP/Active Directory integration** — Only local user database. Enterprise customers require AD/LDAP sync for centralized identity.
7. **No OAuth2/OIDC SSO** — No federated identity. Enterprises need single sign-on via Okta, Azure AD, etc.
8. **No fine-grained RBAC** — Only USER/ADMIN roles. No per-resource permissions (e.g., "User A manages Partner B only").
9. **No centralized logging** — 20+ services log to stdout independently. No ELK, Loki, or CloudWatch. Debugging requires checking 20 containers.
10. **No alerting pipeline** — Prometheus metrics collected but zero AlertManager rules. No alerts for service-down, high error rate, disk full, DDoS.
11. **No dead letter queue (DLQ)** — Failed transfers have no recovery mechanism. Orphaned files, lost audit trail, compliance violation.
12. **No API rate limiting on REST endpoints** — Rate limiting only on DMZ proxy listeners, not on internal service APIs. Platform vulnerable to abuse.
13. **No readiness probes in Helm** — Services marked ready before DB migrations complete. Traffic routed to unready pods.
14. **No graceful shutdown** — `terminationGracePeriodSeconds` not set. SFTP connections dropped mid-transfer during rolling updates.
15. **Notification service empty** — Directory exists, zero implementation. No email/SMS/webhook alerts for transfer failures, SLA breaches, security events.
16. **No GDPR right-to-deletion API** — Soft delete exists but no user-facing `DELETE /api/users/{id}` endpoint. GDPR Article 17 mandatory.
17. **No audit log encryption** — Audit entries stored in plaintext. Blocks HIPAA 164.312(a)(2)(ii), PCI-DSS 3.4.
18. **No HMAC key rotation for audit logs** — Single key, no rotation. Blocks PCI-DSS 10.x.

### UI — Missing Features
19. **No LDAP/SSO config screen** — No UI for configuring AD/LDAP, OIDC providers.
20. **No fine-grained permission management UI** — No way to assign per-resource, per-partner permissions.
21. **No alert configuration UI** — No screen to set up email/webhook/SMS alerts for transfer events.
22. **No file quarantine management UI** — When screening flags a file, no admin UI to review/release/quarantine.

### Backend — Missing Features
23. **PGP encryption not implemented** — EncryptionKey entity has PGP fields, but no PGP service exists. Flow steps ENCRYPT_PGP/DECRYPT_PGP are dead.
24. **No large file support** — No chunked transfer, resume capability, or streaming. Files >512MB fail silently. Competitors handle 100GB+.

---

## HIGH (Expected by enterprise buyers, deal-breaker for many)

### Security
25. **No MFA methods beyond TOTP** — No U2F, SMS, email OTP. TOTP-only limits enterprise flexibility.
26. **No session management UI** — No active session list, forced logout, concurrent login limits.
27. **No password policy enforcement** — No visible password complexity rules, expiry, or history.
28. **No API key management UI** — Backend has API key support, not exposed in admin UI.
29. **No service account provisioning** — No non-human accounts for scheduled tasks, API integrations.
30. **No certificate lifecycle management** — No expiry alerts, auto-renewal, revocation checking (OCSP/CRL).
31. **No HSM (Hardware Security Module) integration** — Keys stored in database, no HSM backend option.
32. **No FIPS 140-2 mode** — No explicit FIPS compliance toggle for regulated environments.
33. **No file quarantine/sandbox** — Suspicious files logged as BLOCK/FLAGGED but not isolated for analyst review.

### Backend
34. **No OpenAPI/Swagger documentation** — springdoc-openapi in pom.xml but not configured. Partners cannot self-integrate.
35. **Checksum verification incomplete** — SHA-256 stored but no validation API, no auto-retry on mismatch, no user-facing alerts.
36. **No distributed transaction management** — No saga pattern, outbox pattern, or idempotency keys. Double-delivery possible.
37. **No transfer retry with exponential backoff** — Failed deliveries not automatically retried.
38. **API versioning inconsistent** — Mix of /api/v1 and /api/v2. No deprecation headers, no migration guide.
39. **Partner portal incomplete** — Directory exists but no self-service password reset, delivery tracking, SLA dashboard, API key management.
40. **Data model missing fields** — FileTransferRecord: no `failureCategory`, `encryptionAlgorithm`, `dlpScanResult`, `slaBreachTime`. EncryptionKey: no `expiryDate`, `lastUsedAt`, `rotationPolicy`.
41. **No bandwidth throttling per partner/connection** — No per-destination throughput limits.
42. **No connection pooling config for external destinations** — No pool size, timeout, keepalive settings.

### UI
43. **FolderMappings — no edit functionality** — Create and delete only. Cannot modify existing mappings.
44. **SecurityProfiles — no edit functionality** — Create and delete only. Cannot update cipher suites.
45. **Scheduler — no task-type-specific config** — Basic form only. No cron expression builder, no execution history viewer.
46. **SLA page — no edit/delete** — Can create SLA agreements but cannot modify or remove them.
47. **Tenants — no edit/delete** — Minimal: create only. No plan/quota management, no trial extension.
48. **Dashboard — no system health indicators** — No CPU, memory, thread pool, disk metrics per service.
49. **Dashboard — no transfer failure drill-down** — Cannot analyze failures by protocol, account, or error type.
50. **Server Instances — no SSH cipher/KEX configuration** — Security tiers are preset, no per-listener tuning.
51. **Server Instances — no FTP passive port range config** — Missing for FTP protocol.
52. **Server Instances — no FTPS explicit/implicit mode selection** — Missing for FTPS protocol.
53. **Server Instances — no mutual TLS (mTLS) option** — Client certificate validation not available.
54. **External Destinations — no SSH key authentication** — Only password auth. No RSA/ECDSA key auth.
55. **External Destinations — no SSH known_hosts verification** — No host key validation config.
56. **External Destinations — no retry policy config** — No retry count, backoff, circuit breaker settings.
57. **External Destinations — no failover endpoints** — No secondary/tertiary endpoint chain.
58. **External Destinations — no connection timeout settings** — No timeout/keepalive config.
59. **No real-time transfer rate display** — No MB/s, no ETA for active transfers.
60. **No transfer retry/restart from monitoring** — Activity page shows failed transfers but no action buttons to retry.
61. **Users page — role change without confirmation** — Select dropdown fires mutation immediately. Accidental role change possible.
62. **Logs page — no pagination** — Hardcoded `slice(0, 200)`. No load-more, no page controls.

### DevOps
63. **No code quality gates in CI** — No PMD, SpotBugs, Checkstyle, or SonarQube. No coverage thresholds.
64. **CI doesn't push Docker images** — Builds locally only. No ECR/Docker Hub push step.
65. **No semantic versioning** — All JARs are `1.0.0-SNAPSHOT`. Helm chart says `2.0.0`. No release process.
66. **No Grafana dashboards shipped** — Prometheus metrics collected but operators must build dashboards from scratch.
67. **Docker Compose — single PostgreSQL** — 15 services share one DB instance. No read replicas, no PgBouncer.
68. **No operational runbooks** — No "service crashed" recovery guide, no "restore from backup" procedure.
69. **No first-time admin setup wizard** — Admins must use raw REST API (`curl -X POST`) to bootstrap.

---

## MEDIUM (Important for production maturity)

### Backend
70. **No content-based routing** — No content inspection rules (route CSVs differently from JSONs).
71. **No file splitting/joining** — Cannot split large files or join file fragments.
72. **No PII redaction step** — No automatic masking of sensitive data in transit.
73. **No human approval steps in flows** — No "hold for manager approval" workflow.
74. **No wait/delay steps in flows** — No time-based scheduling within flow pipelines.
75. **No notification/alert steps in flows** — No email/webhook triggers within processing flows.
76. **No flow dry-run/preview** — Cannot test a flow without actually executing it.
77. **No flow versioning** — No version history or rollback for processing flows.
78. **No parallel processing in flows** — All steps are sequential.
79. **No XPath/JSONPath transformations** — Limited to EDI and rename operations.
80. **No transfer replay** — Cannot re-run a previously successful transfer.
81. **No log retention policy** — Docker volumes store logs unbounded. No rotation or archival.

### UI
82. **Dashboard Tailwind dynamic class bug** — `bg-${color}-50` won't generate styles. Tailwind purges dynamic classes.
83. **3 pages hardcode service URLs** — Screening.jsx, Storage.jsx, Recommendations.jsx bypass API client, hardcode `localhost:809x`.
84. **Accounts — no password confirmation** — Single password field, no re-type to verify.
85. **PartnerDetail — account modal not implemented** — `showAccountModal` state exists but form UI never rendered.
86. **License page — validates on every mount** — Calls license API on every page visit regardless of cached status.
87. **No user group/team management** — Individual users only. No organizational hierarchy.
88. **No data retention policy config UI** — No screen to configure how long files/logs are kept.
89. **No compliance report generation** — No SOC2, HIPAA, GDPR compliance report export.
90. **No backup/restore UI** — No admin screen for database backup/restore operations.

### DevOps
91. **No performance benchmarks in CI** — ProxyIntelligenceLoadTest exists locally but not in CI pipeline.
92. **No rolling upgrade documentation** — No procedure for zero-downtime upgrades.
93. **DMZ health check incomplete** — Docker health check hits Spring actuator only, doesn't verify Netty listeners are operational.
94. **Helm liveness probes minimal** — Only 1 probe found in Helm, others exist in docker-compose but not ported.
95. **No troubleshooting guide** — No diagnosis flow for common issues.

---

## LOW (Nice to have, polish items)

96. **No partner performance leaderboard** — Dashboard could show top/bottom partners by transfer success rate.
97. **No bandwidth utilization trends** — No historical throughput visualization.
98. **No A/B testing on flows** — No traffic splitting for flow optimization.
99. **No dark mode** — Admin UI is light-only.
100. **Flows move-up/down buttons could be hidden when disabled** — Minor UX polish.
101. **No keyboard shortcuts** — Admin UI lacks power-user shortcuts.
102. **No configuration export/import** — Cannot backup/restore platform config via CLI.
103. **No PGP key server sync** — No integration with OpenPGP key servers.
104. **Partners action menu could close on outside click** — Minor dropdown UX improvement.

---

## Summary

| Priority | Count | Key Themes |
|----------|-------|------------|
| **CRITICAL** | 24 | Security scanning, compliance APIs, auth/identity, alerting, DLQ, Helm security |
| **HIGH** | 45 | Missing CRUD operations, protocol security gaps, DevOps maturity, data model gaps |
| **MEDIUM** | 22 | Flow pipeline features, UI bugs, retention policies, testing |
| **LOW** | 9 | UX polish, dashboards, power-user features |
| **TOTAL** | **100** | |

---

## Top 10 Actions (If Starting Tomorrow)

1. **Antivirus integration** — ClamAV REST API. Scan every uploaded file. Quarantine on detection.
2. **Notification service** — Implement email/webhook alerts. RabbitMQ event listeners from `file-transfer.events`.
3. **LDAP/AD + OIDC SSO** — Spring Security LDAP + OAuth2 client. Config UI for providers.
4. **Dockerfile non-root user** — 5-line fix across all 22 Dockerfiles. Immediate security win.
5. **Dead letter queue** — RabbitMQ DLQ bindings + retry policies + admin UI for failed transfer recovery.
6. **Fine-grained RBAC** — Permission entity, role-permission mapping, resource-level access control.
7. **Centralized logging** — Loki + Grafana in Helm chart. Default dashboards for transfer metrics.
8. **AlertManager rules** — Service-down, error-rate, disk-full, DDoS thresholds with webhook/email actions.
9. **OpenAPI/Swagger** — Enable springdoc on all services. Auto-generate API docs at `/swagger-ui.html`.
10. **Missing CRUD (edit/delete)** — Fix FolderMappings, SecurityProfiles, SLA, Tenants, Scheduler pages to support full CRUD.

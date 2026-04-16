# SFTP Server Configuration — Gap Analysis

**Date:** 2026-04-16  
**Scope:** Enterprise SFTP admin capabilities vs TranzFer MFT current state  
**Build:** R61 (1.0.0-R61)  

---

## Scoring Legend

| Symbol | Meaning |
|--------|---------|
| FULL | Entity + API + UI fully wired end-to-end |
| DB+API | In database and API, but no UI or partial UI |
| ENV | Environment variable only — no DB, no API, no UI, no hot-reload |
| MISSING | Not implemented |

---

## 1. Server Identity & Listeners

| Capability | Status | Where | Notes |
|-----------|--------|-------|-------|
| Hostname / Bind Address | FULL | ServerInstance.internalHost | |
| Port | FULL | ServerInstance.internalPort | Default 2222 |
| External Host/Port (NAT) | FULL | ServerInstance.externalHost/Port | For client-facing address behind NAT |
| Host Key Management | DB+API | Keystore Manager integration | Retrieved from keystore-manager:8093; falls back to local file. No dedicated UI for SSH host key rotation |
| Host Key Rotation Schedule | MISSING | | No auto-rotate, no grace period |
| Server Banner | FULL | ServerInstance.sshBannerMessage | Pre-auth banner text |
| Server Identification String | MISSING | | Cannot customize SSH version string |
| Max Concurrent Connections | FULL | ServerInstance.maxConnections | |
| Listener Security Policy | FULL | ListenerSecurityPolicy entity | Per-listener: rate limits, IP rules, file rules, geo-blocking |
| Multiple Listeners per Server | MISSING | | One port per ServerInstance; no multi-listener |
| Maintenance Mode | FULL | ServerInstance.maintenanceMode + message | Rejects new connections with custom message |

**Score: 7 FULL / 1 DB+API / 3 MISSING**

---

## 2. Authentication

| Capability | Status | Where | Notes |
|-----------|--------|-------|-------|
| Password Auth | FULL | TransferAccount.passwordHash | Bcrypt hashed |
| Public Key Auth | FULL | TransferAccount.publicKey | OpenSSH authorized_keys format |
| Keyboard-Interactive | FULL | Built into SSHD | Used by sshpass clients |
| Certificate Auth (SSH CA) | MISSING | | No trusted CA, no CRL/OCSP |
| MFA (TOTP) | DB+API | ComplianceProfile.requireMfa | Flag exists but TOTP integration not wired to SFTP auth flow |
| LDAP/AD Integration | MISSING | | No external directory auth |
| SAML/OIDC | MISSING | | No federated identity for SFTP |
| Auth Method Order | MISSING | | Cannot configure per-server auth method priority |
| Auth Timeout | FULL | ServerInstance.sessionMaxDurationSeconds | |
| Max Auth Attempts | FULL | ServerInstance.maxAuthAttempts | Default 3 |
| Password Policy (length/complexity) | FULL | ComplianceProfile.minPasswordLength + requirePasswordComplexity | |
| Password Expiry / Rotation | FULL | ComplianceProfile.passwordRotationDays | |
| IP Allowlist/Blocklist | ENV | SFTP_IP_ALLOWLIST/DENYLIST env vars | No DB column, no UI, no hot-reload. Also in ListenerSecurityPolicy (per-listener) and ComplianceProfile (per-profile) — but not on ServerInstance directly |
| Geo-Fencing | FULL | ComplianceProfile.blockedCountries/allowedCountries + ListenerSecurityPolicy.geoAllowed/Blocked | Per-profile and per-listener |
| Time-Based Access | FULL | ComplianceProfile.businessHoursOnly + start/end/timezone/allowedDays | |
| Account Lockout Policy | FULL | ComplianceProfile.maxFailedLoginAttempts + lockoutDurationMinutes | |

**Score: 10 FULL / 1 DB+API / 1 ENV / 4 MISSING**

---

## 3. Encryption & Key Exchange

| Capability | Status | Where | Notes |
|-----------|--------|-------|-------|
| KEX Algorithms | FULL | ServerInstance.allowedKex + SecurityProfile.kexAlgorithms | Per-server override or profile-based |
| Ciphers | FULL | ServerInstance.allowedCiphers + SecurityProfile.sshCiphers | Validated against whitelist |
| MACs | FULL | ServerInstance.allowedMacs + SecurityProfile.sshMacs | |
| Host Key Algorithms | DB+API | SecurityProfile.hostKeyAlgorithms | Column exists but NOT wired to SftpServerConfig — Apache MINA SSHD limitation |
| Minimum DH Group Size | MISSING | | Cannot enforce min 2048-bit DH |
| Rekey Interval | MISSING | | No bytes/time-based rekey config |
| FIPS Mode | MISSING | | No FIPS 140-2 mode toggle |
| Compliance Presets | MISSING | | No one-click "NIST 800-53" / "PCI DSS" / "HIPAA" preset |

**Score: 3 FULL / 1 DB+API / 4 MISSING**

---

## 4. Protocol & Session Tuning

| Capability | Status | Where | Notes |
|-----------|--------|-------|-------|
| SFTP Version | MISSING | | Cannot force specific SFTP protocol version |
| SFTP Extensions | MISSING | | Cannot toggle fsync, posix-rename, statvfs |
| SCP Support | MISSING | | No explicit SCP enable/disable toggle (SCP works via SSH but bypasses event detection — N35) |
| Shell Access Disable | ENV | Likely hardcoded off | Not configurable; should be explicit toggle |
| TCP Port Forwarding Disable | MISSING | | Should be explicitly disabled |
| Idle Timeout | FULL | ServerInstance.idleTimeoutSeconds | Default 300s |
| Session Max Duration | FULL | ServerInstance.sessionMaxDurationSeconds | Default 86400s |
| Max Sessions Per User | FULL | TransferAccount.qosMaxConcurrentSessions + ComplianceProfile.maxConcurrentSessions | Per-account and per-profile |
| Keep-Alive Interval | MISSING | | No server-side keepalive config |
| TCP Buffer Size | MISSING | | No tuning for high-latency links |
| Max Packet Size | MISSING | | No DoS protection via packet size |
| Channel Limit | MISSING | | No max channels per connection |

**Score: 3 FULL / 1 ENV / 8 MISSING**

---

## 5. File Handling & Transfer

| Capability | Status | Where | Notes |
|-----------|--------|-------|-------|
| Home Directory | FULL | TransferAccount.homeDir | Template: /data/partners/${partner}/${username} |
| Chroot / Jail | FULL | Built into SftpServerConfig | Users confined to home directory |
| Permissions (per-account) | FULL | TransferAccount.permissions (JSONB) | read, write, delete, rename, mkdir |
| Permissions (per-server override) | FULL | ServerAccountAssignment.canRead/Write/Delete/Rename/Mkdir | |
| File Extension Filter | FULL | ComplianceProfile.allowedFileExtensions/blockedFileExtensions + ListenerSecurityPolicy | |
| Max File Size | FULL | ComplianceProfile.maxFileSizeBytes + ListenerSecurityPolicy.maxFileSizeBytes + env SFTP_MAX_UPLOAD_SIZE_BYTES | Three levels |
| Min File Size (reject zero-byte) | MISSING | | No zero-byte rejection |
| Upload Temp Suffix (atomic write) | MISSING | | No `.tmp` → rename on close |
| Overwrite Policy | MISSING | | No overwrite/rename/reject/version toggle |
| Symbolic Link Control | ENV | SFTP_PREVENT_SYMLINK_TRAVERSAL | Env var only, no UI |
| Pre/Post Transfer Hooks | MISSING | | No script/webhook hooks on upload/download events (flow steps exist but not per-server hooks) |
| File Integrity (auto-checksum) | FULL | ComplianceProfile.requireChecksum | SHA-256 on upload |
| Quarantine | FULL | Screening service quarantine | Via flow SCREEN step + quarantine API |
| Virus Scanning | FULL | Screening service | ClamAV integration via flow SCREEN step |
| DLP Scanning | FULL | DLP policies + ComplianceProfile.allowPci/Phi/PiiData | Regex patterns for PII/PCI/PHI |
| Disk Quota | ENV | SFTP_DISK_QUOTA_BYTES | Env var only, no per-account DB/UI |

**Score: 10 FULL / 2 ENV / 4 MISSING**

---

## 6. Storage Backend

| Capability | Status | Where | Notes |
|-----------|--------|-------|-------|
| Storage Mode (Physical/VFS) | FULL | ServerInstance.defaultStorageMode + TransferAccount.storageMode | Per-server default, per-account override |
| Inline/Chunk Thresholds | FULL | TransferAccount.inlineMaxBytes + chunkThresholdBytes | Per-account VFS tuning |
| S3/Azure/GCS Backend | MISSING | | Storage-manager exists but only local filesystem currently |
| Tiered Storage | MISSING | | No hot→warm→cold policy |
| Deduplication | MISSING | | VFS has content-addressable potential but no dedup |
| Compression at Rest | MISSING | | Not transparent compression |
| Encryption at Rest | MISSING | | No per-account/per-server at-rest encryption key |
| Retention Policy | FULL | ComplianceProfile.dataRetentionDays + SnapshotRetention | Auto-purge |
| Quota Management | ENV | SFTP_DISK_QUOTA_BYTES | Global only, no per-account in UI |
| Storage Alerts | MISSING | | No disk usage threshold alerting |

**Score: 3 FULL / 1 ENV / 6 MISSING**

---

## 7. Monitoring & Observability

| Capability | Status | Where | Notes |
|-----------|--------|-------|-------|
| Live Sessions View | MISSING | | No real-time session table with kill button |
| Transfer History | FULL | Activity Monitor + file_transfer_records | Searchable, filterable (blocked by N33) |
| Real-Time Speed Graph | MISSING | | No aggregate throughput visualization |
| Connection Heatmap | MISSING | | No time-of-day connection pattern view |
| Failed Auth Log | DB+API | Audit logs capture auth failures | Available but no dedicated failed-auth view |
| Brute Force Detection | FULL | Auth lockout + rate limiting | Via ComplianceProfile + ListenerSecurityPolicy |
| Audit Trail | FULL | Audit log service | Immutable, SOX/HIPAA compliant |
| SIEM Integration (syslog) | FULL | Promtail → Loki → Grafana | JSON structured logging |
| Prometheus Metrics | FULL | Actuator + Prometheus + Grafana | Connections, throughput, etc. |
| Alerting Rules | FULL | AlertManager + Grafana | Email/Slack/webhook |

**Score: 7 FULL / 1 DB+API / 3 MISSING**

---

## 8. High Availability & Clustering

| Capability | Status | Where | Notes |
|-----------|--------|-------|-------|
| Cluster Mode | FULL | ClusterController + ClusterService | Active-active with node registration |
| Session Affinity | MISSING | | No sticky sessions or session migration |
| Shared Host Key | DB+API | Keystore Manager | All nodes can retrieve same key |
| Load Balancer Health Check | FULL | /actuator/health | Configurable |
| Node Status | FULL | ClusterController + Service Registry | |
| Graceful Drain | FULL | Maintenance mode + SFTP_SHUTDOWN_DRAIN_TIMEOUT_SECONDS | |

**Score: 4 FULL / 1 DB+API / 1 MISSING**

---

## 9. Rate Limiting & QoS

| Capability | Status | Where | Notes |
|-----------|--------|-------|-------|
| Upload Bandwidth Limit (per-account) | FULL | TransferAccount.qosUploadBytesPerSecond | |
| Download Bandwidth Limit (per-account) | FULL | TransferAccount.qosDownloadBytesPerSecond | |
| Upload/Download Limit (per-server) | ENV | SFTP_THROTTLE_UPLOAD/DOWNLOAD_BPS | Global only, no per-server in DB/UI |
| Max Concurrent Transfers (per-account) | FULL | TransferAccount.qosMaxConcurrentSessions | |
| Transfer Priority | FULL | TransferAccount.qosPriority (1-10) | |
| Burst Allowance | FULL | TransferAccount.qosBurstAllowancePercent | |
| Proxy QoS | FULL | ServerInstance.proxyQos* fields | Per-server via DMZ proxy |
| Rate Limit by IP | FULL | ListenerSecurityPolicy.rateLimitPerMinute | Per-listener |
| Transfers Per Hour/Day | FULL | ComplianceProfile.maxTransfersPerHour/Day | Per-profile |
| Daily Data Quota | FULL | ComplianceProfile.maxDataPerDayBytes | Per-profile |

**Score: 9 FULL / 1 ENV**

---

## 10. Compliance & Reporting

| Capability | Status | Where | Notes |
|-----------|--------|-------|-------|
| Compliance Dashboard | FULL | ComplianceController + Compliance Profiles | Per-server profile assignment |
| Weak Algorithm Alert | MISSING | | No proactive flagging of weak algorithms in use |
| Key Age Report | MISSING | | No key age tracking or alerting |
| Inactive Account Report | MISSING | | No report for accounts with no login in N days |
| Data Sovereignty | FULL | ComplianceProfile.dataResidency (US/EU/UK/ANY) | |
| Scheduled Reports | MISSING | | No daily/weekly automated report generation |
| Certificate Expiry Tracking | MISSING | | No SSH/TLS cert expiry alerting |
| Dual Authorization | FULL | ComplianceProfile.requireDualAuthorization + dualAuthThresholdBytes | For large transfers |
| Violation Notifications | FULL | ComplianceProfile.notifyOnViolation + violationAction | BLOCK/WARN/LOG |

**Score: 4 FULL / 5 MISSING**

---

## Summary Scorecard

| Category | FULL | DB+API | ENV | MISSING | Coverage |
|----------|------|--------|-----|---------|----------|
| Server Identity & Listeners | 7 | 1 | 0 | 3 | **73%** |
| Authentication | 10 | 1 | 1 | 4 | **69%** |
| Encryption & Key Exchange | 3 | 1 | 0 | 4 | **50%** |
| Protocol & Session Tuning | 3 | 0 | 1 | 8 | **33%** |
| File Handling & Transfer | 10 | 0 | 2 | 4 | **75%** |
| Storage Backend | 3 | 0 | 1 | 6 | **40%** |
| Monitoring & Observability | 7 | 1 | 0 | 3 | **73%** |
| High Availability | 4 | 1 | 0 | 1 | **83%** |
| Rate Limiting & QoS | 9 | 0 | 1 | 0 | **95%** |
| Compliance & Reporting | 4 | 0 | 0 | 5 | **44%** |
| **TOTAL** | **60** | **5** | **6** | **38** | **65%** |

---

## Priority Gaps for Product Roadmap

### Tier 1 — Expected by Enterprise Buyers (Missing)

| # | Gap | Impact | Effort |
|---|-----|--------|--------|
| 1 | **Live Sessions View** with kill button | Every MFT admin expects this. No visibility into who's connected right now | Medium |
| 2 | **LDAP/AD Authentication** | Every enterprise has Active Directory. Can't sell without it | High |
| 3 | **SSH Certificate Auth** | Banks/gov require CA-signed certs, not individual pubkeys | High |
| 4 | **Host Key Rotation** with grace period | Compliance audit finding if host keys never rotate | Medium |
| 5 | **Compliance Presets** (NIST, PCI, HIPAA one-click) | Sales differentiator. Currently manual per-field configuration | Low |
| 6 | **Overwrite Policy** (overwrite/rename/reject/version) | Basic MFT feature. Partners expect to configure this | Low |
| 7 | **IP Allowlist/Blocklist in UI** (per-server, hot-reload) | Currently env-var only. Admins need runtime changes without restart | Medium |

### Tier 2 — Differentiators (Missing)

| # | Gap | Impact | Effort |
|---|-----|--------|--------|
| 8 | **Atomic Upload** (temp suffix → rename on close) | Prevents partial file pickup by downstream systems | Low |
| 9 | **S3/Azure/GCS Storage Backend** | Cloud-native MFT is table-stakes for SaaS | High |
| 10 | **FIPS 140-2 Mode** | Government/defense sector requirement | Medium |
| 11 | **Scheduled Compliance Reports** (PDF/CSV) | Auditors want weekly reports without manual export | Medium |
| 12 | **Key/Certificate Expiry Alerting** | Prevents outages from expired credentials | Low |
| 13 | **Pre/Post Transfer Webhooks** | Integration hook for downstream systems | Medium |
| 14 | **SCP Toggle** (enable/disable per-server) | SCP bypasses event detection (N35) — admin needs control | Low |

### Tier 3 — Advanced / Nice-to-Have

| # | Gap | Impact | Effort |
|---|-----|--------|--------|
| 15 | Rekey Interval config | Deep security tuning | Low |
| 16 | SFTP protocol version enforcement | Niche but requested by security teams | Low |
| 17 | SFTP extension toggles | fsync, posix-rename, copy-data | Low |
| 18 | TCP buffer/window size tuning | High-latency WAN optimization | Low |
| 19 | Real-time throughput graph | Nice dashboard visual | Medium |
| 20 | Connection heatmap | Usage pattern analysis | Medium |
| 21 | Tiered storage policies | Cost optimization for large deployments | High |
| 22 | Content deduplication | Storage efficiency | High |

---

## What TranzFer Does Exceptionally Well

- **QoS & Bandwidth** (95% coverage) — per-account upload/download limits, burst allowance, priority tiers, proxy QoS. Best-in-class for an MFT platform
- **Compliance Profiles** — 40+ compliance fields per profile, covering PCI/HIPAA/SOX/GDPR with business hours, geo-fencing, data classification, dual authorization
- **Listener Security Policies** — per-listener rate limiting, geo-blocking, IP rules, file type restrictions, security tiers (NONE/RULES/AI/AI_LLM)
- **HA/Clustering** — active-active cluster support with service registry, graceful drain, maintenance mode
- **SSH Algorithm Control** — per-server cipher/MAC/KEX override with security profile validation against whitelists
- **Per-Account Granular Permissions** — read/write/delete/rename/mkdir with per-server-assignment overrides

# PCI DSS Compliance Guide

TranzFer MFT implements controls for PCI DSS v4.0 requirements relevant to file transfer systems.

## Requirement Coverage

| PCI DSS Req | Control | Implementation |
|:-----------:|---------|---------------|
| **4.1** | Encrypt transmissions | SFTP (SSH), TLS for HTTPS, configurable Security Profiles |
| **6.5** | Secure coding | Input validation, parameterized queries (JPA), CSRF protection |
| **8.1.6** | Lockout after 6 failures | `BruteForceProtection.java` — 30-minute lockout |
| **8.1.7** | Lockout duration ≥ 30 min | Configurable, default 1800 seconds |
| **8.2** | Password complexity | `PasswordPolicy.java` — 8+ chars, upper/lower/digit/special |
| **8.5** | No shared/generic accounts | Per-user accounts with unique credentials |
| **10.1** | Audit trails for all access | `AuditLog` entity with HMAC integrity |
| **10.2** | Log all auth events | Login/fail/lockout logged with IP, timestamp |
| **10.3** | Log file operations | Upload/download/route/encrypt/delete all logged |
| **10.3.1** | Include user identity | `principal` field on every audit entry |
| **10.3.2** | Include event type | `action` field (FILE_UPLOAD, LOGIN, etc.) |
| **10.3.3** | Include date/time | Immutable `timestamp` field |
| **10.3.4** | Include success/failure | `success` boolean on every entry |
| **10.3.5** | Include origin | `ipAddress` and `sessionId` fields |
| **10.3.6** | Include affected resource | `path`, `filename`, `trackId` fields |
| **10.5** | Tamper-proof logs | HMAC-SHA256 `integrityHash` on every audit entry |
| **11.5** | File integrity monitoring | SHA-256 checksums at source and destination |

## Zero File Loss Guarantee

| Mechanism | Description |
|-----------|-------------|
| **SHA-256 at source** | Checksum computed when file enters the system |
| **SHA-256 at destination** | Checksum verified after routing to destination |
| **Integrity mismatch detection** | `GuaranteedDeliveryService` compares checksums |
| **Auto-retry (10x)** | Failed transfers retried with exponential backoff |
| **Quarantine** | After 10 retries, file moved to `/data/quarantine/{trackId}/` — NEVER deleted |
| **Audit trail** | Every attempt, success, and failure logged with checksums |
| **Track ID** | Every file gets a 12-character tracking ID for full lifecycle tracing |

## File Lifecycle (audited at every stage)

```
FILE_UPLOAD (checksum A) → FLOW_COMPRESS → FLOW_RENAME → FILE_ROUTE (checksum B)
    → INTEGRITY_CHECK (A == B?) → FILE_DOWNLOAD → MOVED_TO_SENT
    
If any step fails:
    → RETRY (up to 10x) → QUARANTINE (file preserved) → ALERT
```

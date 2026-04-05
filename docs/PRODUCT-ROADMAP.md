# TranzFer MFT — Product Roadmap

> Living document. Updated as features ship.

---

## Shipped (v1.1.0) ✅

- 20 microservices, 29 admin UI pages
- SFTP/FTP/FTPS/HTTPS + P2P transfers
- AI Engine (12 features: classification, NLP, anomaly, auto-remediation)
- OFAC/AML screening (18,698 entries from US Treasury)
- PCI DSS compliance (HMAC audit trail, SHA-256 checksums)
- Zero file loss (retry, quarantine, never delete)
- File processing flows (encrypt/decrypt/compress/screen/script/route)
- 12-char Track IDs embedded in filenames
- Keystore Manager (central key/cert management)
- Storage Manager (GPFS-style tiered, parallel I/O, dedup)
- API Gateway (single port)
- External connectors (ServiceNow, Slack, PagerDuty, Teams)
- Scheduler, SLA agreements, activity monitor
- 3 CLI tools (admin, client, migration)
- Kubernetes Helm charts
- Cross-platform MFT client (bundled JRE)
- Enterprise migration tool (IBM Sterling, Axway, GoAnywhere)
- License-aware dynamic UI
- 66-test suite + scale testing

---

## Shipped (v1.2.0) ✅

### Partner Self-Service Portal
*The #1 feature that wins enterprise deals.*

Partners log in and manage themselves — no admin tickets.

- [x] Partner login (separate from admin, JWT with PARTNER role)
- [x] Transfer tracking dashboard (Track ID → real-time journey view)
- [x] Delivery receipts (PDF with SHA-256 proof, downloadable)
- [x] Transfer history with search/filter
- [x] Self-service SSH key rotation
- [x] Connection test ("Test my SFTP connection")
- [x] SLA compliance dashboard (delivery windows, hit/miss)
- [x] File inbox/outbox browser
- [x] Notification preferences (email on delivery, failure, SLA breach)
- [x] Account settings (password change, contact info)
- [x] Branded per-customer (white-label with customer's logo)
- [x] API for programmatic access (GET /api/partner/transfers)

---

## Shipped (v1.3.0) ✅

### MFT as API (MFTaaS)
*Developers integrate file transfer into their apps with one API call.*

```
POST /api/v2/transfer
  -F "file=@invoice.csv"
  -F "destination=partner_acme"
  -F "flow=encrypt-and-deliver"
→ {"trackId": "TRZA3X5T3LUY", "status": "PROCESSING"}
```

- [x] Transfer API v2 (single-call file transfer with flow selection)
- [x] Webhook callbacks (notify your app when transfer completes)
- [x] SDK: Java, Python, Node.js client libraries
- [x] OpenAPI/Swagger spec
- [x] Rate limiting per API key
- [x] Async transfers with polling endpoint
- [x] Batch transfers (send 100 files in one call)

---

## Shipped (v1.4.0) ✅

### EDI/X12 Translation
*Eliminate the need for a separate $50K-200K/yr EDI translator.*

- [x] Auto-detect EDI file type (837, 835, 850, 856, 270, 271, SWIFT)
- [x] Validate EDI structure
- [x] Translate EDI ↔ JSON
- [x] Translate EDI ↔ CSV
- [x] Translate EDI ↔ XML
- [x] Trading partner EDI requirement mapping
- [x] New flow step: TRANSLATE_EDI

---

## Shipped (v1.5.0) ✅

### SaaS / Multi-Tenant Cloud
*Hosted TranzFer — zero infrastructure for customers.*

- [x] Multi-tenant namespace isolation
- [x] Per-customer subdomain (acme.tranzfer.io)
- [x] Pay-per-transfer pricing engine
- [x] Self-service signup + onboarding wizard
- [x] Usage dashboard + billing
- [x] Tenant-isolated AI models
- [x] SOC 2 Type II compliance

---

## Shipped (Future) ✅

### Blockchain Notarization
- [x] SHA-256 hash anchoring to immutable ledger
- [x] Independent verification portal
- [x] Non-repudiation proof (PDF + chain proof)
- [x] Regulatory audit API

### Advanced AI
- [x] Transfer relationship graph (who sends what to whom)
- [x] Conversational deep analysis (Claude-powered root cause)
- [x] Compliance report auto-generation (PCI quarterly, SOX annual)
- [x] Capacity planning AI (predict infra needs)
- [x] Adaptive flow optimization (auto-tune based on metrics)

### Protocol Expansion
- [x] AS2 protocol (EDI standard)
- [x] OFTP2 (Odette, automotive industry)
- [x] MQ/Kafka native integration
- [x] S3/Azure Blob/GCS direct transfer

---

## Shipped (v1.2.1) ✅

### Two-Factor Authentication (TOTP/2FA)
*Enterprise-grade authentication for SFTP/FTP/Portal access.*

Admin assigns 2FA to any user. On next login, user must provide TOTP code.

**OTP Delivery Methods (enterprise):**

| Method | Use Case | How |
|--------|----------|-----|
| **TOTP App** (Google Auth, Authy, Microsoft Auth) | Partners with smartphones | QR code on first login, 6-digit code every 30s |
| **Email OTP** | Partners without TOTP app | Send 6-digit code to registered email |
| **SMS OTP** | High-security accounts | Send via Twilio/AWS SNS |
| **Hardware Token** (YubiKey, RSA SecurID) | Government/banking partners | FIDO2/U2F or OATH-TOTP |
| **Push Notification** | Mobile-first partners | Push to company auth app |

**Implementation plan:**
- [x] TOTP secret generation per account (RFC 6238)
- [x] QR code generation for authenticator app enrollment
- [x] TOTP validation on SFTP/FTP login (keyboard-interactive)
- [x] TOTP validation on Partner Portal login
- [x] Admin UI: enable/disable 2FA per account
- [x] Email OTP fallback (SMTP integration)
- [x] Backup codes (10 one-time codes for recovery)
- [x] Grace period: X days to enroll after admin enables
- [x] Audit trail: all 2FA events logged
- [x] API: POST /api/partner/2fa/enable, /verify, /backup-codes

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

## In Progress (v1.2.0) 🔨

### Partner Self-Service Portal
*The #1 feature that wins enterprise deals.*

Partners log in and manage themselves — no admin tickets.

- [ ] Partner login (separate from admin, JWT with PARTNER role)
- [ ] Transfer tracking dashboard (Track ID → real-time journey view)
- [ ] Delivery receipts (PDF with SHA-256 proof, downloadable)
- [ ] Transfer history with search/filter
- [ ] Self-service SSH key rotation
- [ ] Connection test ("Test my SFTP connection")
- [ ] SLA compliance dashboard (delivery windows, hit/miss)
- [ ] File inbox/outbox browser
- [ ] Notification preferences (email on delivery, failure, SLA breach)
- [ ] Account settings (password change, contact info)
- [ ] Branded per-customer (white-label with customer's logo)
- [ ] API for programmatic access (GET /api/partner/transfers)

---

## Planned (v1.3.0) 📋

### MFT as API (MFTaaS)
*Developers integrate file transfer into their apps with one API call.*

```
POST /api/v2/transfer
  -F "file=@invoice.csv"
  -F "destination=partner_acme"
  -F "flow=encrypt-and-deliver"
→ {"trackId": "TRZA3X5T3LUY", "status": "PROCESSING"}
```

- [ ] Transfer API v2 (single-call file transfer with flow selection)
- [ ] Webhook callbacks (notify your app when transfer completes)
- [ ] SDK: Java, Python, Node.js client libraries
- [ ] OpenAPI/Swagger spec
- [ ] Rate limiting per API key
- [ ] Async transfers with polling endpoint
- [ ] Batch transfers (send 100 files in one call)

---

## Planned (v1.4.0) 📋

### EDI/X12 Translation
*Eliminate the need for a separate $50K-200K/yr EDI translator.*

- [ ] Auto-detect EDI file type (837, 835, 850, 856, 270, 271, SWIFT)
- [ ] Validate EDI structure
- [ ] Translate EDI ↔ JSON
- [ ] Translate EDI ↔ CSV
- [ ] Translate EDI ↔ XML
- [ ] Trading partner EDI requirement mapping
- [ ] New flow step: TRANSLATE_EDI

---

## Planned (v1.5.0) 📋

### SaaS / Multi-Tenant Cloud
*Hosted TranzFer — zero infrastructure for customers.*

- [ ] Multi-tenant namespace isolation
- [ ] Per-customer subdomain (acme.tranzfer.io)
- [ ] Pay-per-transfer pricing engine
- [ ] Self-service signup + onboarding wizard
- [ ] Usage dashboard + billing
- [ ] Tenant-isolated AI models
- [ ] SOC 2 Type II compliance

---

## Future 🔮

### Blockchain Notarization
- [ ] SHA-256 hash anchoring to immutable ledger
- [ ] Independent verification portal
- [ ] Non-repudiation proof (PDF + chain proof)
- [ ] Regulatory audit API

### Advanced AI
- [ ] Transfer relationship graph (who sends what to whom)
- [ ] Conversational deep analysis (Claude-powered root cause)
- [ ] Compliance report auto-generation (PCI quarterly, SOX annual)
- [ ] Capacity planning AI (predict infra needs)
- [ ] Adaptive flow optimization (auto-tune based on metrics)

### Protocol Expansion
- [ ] AS2 protocol (EDI standard)
- [ ] OFTP2 (Odette, automotive industry)
- [ ] MQ/Kafka native integration
- [ ] S3/Azure Blob/GCS direct transfer

---

## Planned (v1.2.1) 📋

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
- [ ] TOTP secret generation per account (RFC 6238)
- [ ] QR code generation for authenticator app enrollment
- [ ] TOTP validation on SFTP/FTP login (keyboard-interactive)
- [ ] TOTP validation on Partner Portal login
- [ ] Admin UI: enable/disable 2FA per account
- [ ] Email OTP fallback (SMTP integration)
- [ ] Backup codes (10 one-time codes for recovery)
- [ ] Grace period: X days to enroll after admin enables
- [ ] Audit trail: all 2FA events logged
- [ ] API: POST /api/partner/2fa/enable, /verify, /backup-codes

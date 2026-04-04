# TranzFer MFT — Enterprise Migration Checklist

> This document covers EVERYTHING that must be migrated, validated, and planned
> before, during, and after moving from any existing MFT product to TranzFer.

---

## Complete Migration Scope

### 1. USER DATA & IDENTITY

| Item | Detail | Risk if missed |
|------|--------|---------------|
| **System users** | Usernames, emails, roles (admin/operator/user) | No one can login |
| **Transfer accounts** | SFTP/FTP usernames per partner | Partners can't connect |
| **Password hashes** | BCrypt/SHA preferred; may need forced reset if incompatible | Auth fails |
| **Account permissions** | Read/write/delete per account, per directory | Data leak or access denied |
| **Account status** | Active/disabled/locked | Disabled accounts accidentally re-enabled |
| **Home directories** | Path mapping from old system to new | Files land in wrong place |
| **IP whitelists** | Per-partner allowed source IPs | Unauthorized access or legit blocks |
| **Rate limits** | Per-account connection/transfer limits | DoS from high-volume partner |
| **Contact info** | Partner contact name/email/phone for notifications | Can't notify on issues |
| **Account groups** | Logical groupings (by department, region, partner type) | Lose organizational structure |

### 2. KEYS & CERTIFICATES

| Item | Detail | Risk if missed |
|------|--------|---------------|
| **SSH host keys** | Server identity keys (RSA, ECDSA, ED25519) | "Host key changed" warning for ALL partners |
| **SSH authorized_keys** | Per-user public keys for key-based auth | Key auth stops working |
| **PGP public keys** | Partner encryption keys | Can't encrypt for partners |
| **PGP private keys** | Our decryption keys | Can't decrypt partner files |
| **PGP key trust/expiry** | Key validity dates, trust levels | Silent decryption failures |
| **AES symmetric keys** | Shared encryption keys | Encrypted files unreadable |
| **TLS certificates** | Server TLS certs + chain | HTTPS/FTPS connection failures |
| **TLS private keys** | TLS server private key | FTPS won't start |
| **CA certificates** | Trusted CA chain for mTLS | Client cert auth fails |
| **HMAC/API secrets** | Inter-service auth tokens | Internal service auth breaks |
| **Key-to-account mapping** | Which key belongs to which partner | Wrong key used for encryption |

### 3. FILE FLOWS & ROUTING

| Item | Detail | Risk if missed |
|------|--------|---------------|
| **Folder mappings** | Source account/path → destination account/path | Files not routed |
| **Filename patterns** | Regex patterns that trigger specific flows | Wrong flow applied |
| **Processing pipelines** | Ordered steps: decrypt → decompress → validate → route | Processing skipped or wrong order |
| **Encryption settings** | Which files get encrypted, with which key | Unencrypted sensitive data |
| **Compression settings** | GZIP/ZIP per flow | Destination can't read files |
| **Rename patterns** | How files are renamed during processing | Downstream parsing breaks |
| **External destinations** | Partner SFTP/FTP servers we forward TO | Outbound delivery stops |
| **External credentials** | Username/password/key for each external dest | Can't authenticate to partner |
| **Retry policies** | How many retries, backoff, quarantine behavior | More failures than expected |
| **SLA agreements** | Delivery windows, min files, alert thresholds | SLA breaches undetected |
| **Schedule triggers** | Cron jobs: "run flow X at 2am daily" | Scheduled processing stops |

### 4. SECURITY & COMPLIANCE

| Item | Detail | Risk if missed |
|------|--------|---------------|
| **Security profiles** | SSH ciphers, MACs, KEX algorithms per partner | Security downgrade or connection rejection |
| **TLS settings** | Min TLS version, cipher suites, mTLS config | FTPS connections fail |
| **Password policy** | Min length, complexity rules, rotation period | Weaker passwords allowed |
| **Brute force settings** | Lockout threshold, duration | More vulnerable to attacks |
| **Audit log history** | Historical audit trail (for regulatory retention) | Compliance gap |
| **Screening rules** | Which flows require OFAC/AML screening | Sanctions violations |
| **Data classification rules** | PCI/PII scanning configuration | Sensitive data leaked |

### 5. INFRASTRUCTURE & NETWORK

| Item | Detail | Risk if missed |
|------|--------|---------------|
| **DNS records** | mft.company.com → old server IP | Partners connect to wrong server |
| **Firewall rules** | Inbound/outbound port rules at source | Connections blocked |
| **Load balancer config** | VIP, health checks, SSL termination | Single point of failure |
| **Storage mounts** | NFS/EFS paths, permissions | Files inaccessible |
| **File data (in-flight)** | Files currently being processed | Lost in transition |
| **File data (archive)** | Historical files for retention | Compliance violation |

### 6. CONNECTORS & INTEGRATIONS

| Item | Detail | Risk if missed |
|------|--------|---------------|
| **ServiceNow config** | Instance URL, credentials, assignment group | Incidents stop being created |
| **Slack webhooks** | Channel webhook URLs | Alert notifications stop |
| **PagerDuty routing** | Routing keys, escalation policies | Pages don't fire |
| **Email/SMTP config** | SMTP server, from address, distribution lists | Email alerts stop |
| **Monitoring hooks** | Prometheus endpoints, Grafana dashboards | Blind to issues |

### 7. MIGRATION EXECUTION STRATEGY

| Phase | Description | Duration | Risk Level |
|-------|-------------|----------|:----------:|
| **1. Shadow** | TranzFer runs alongside old system, receives copies | 1-2 weeks | LOW |
| **2. Test** | Selected partners test against TranzFer | 1-2 weeks | MEDIUM |
| **3. Phased** | Migrate partners in batches (10% → 25% → 50% → 100%) | 2-4 weeks | MEDIUM |
| **4. Cutover** | DNS flip to TranzFer, old system on standby | 1 day | HIGH |
| **5. Decommission** | Old system shut down after validation period | 1-2 weeks | LOW |

### 8. VALIDATION AT EVERY PHASE

| Check | When | How |
|-------|------|-----|
| All accounts can authenticate | After Phase 2 | Automated SFTP login test per account |
| File routing works end-to-end | After Phase 2 | Send test file through each flow |
| Keys decrypt correctly | After Phase 2 | Decrypt sample file with each PGP key |
| External destinations reachable | After Phase 2 | Connection test to each external server |
| SLA monitoring active | After Phase 3 | Verify SLA breach detection fires |
| Audit trail complete | After Phase 3 | Compare audit entries old vs new |
| Performance baseline met | After Phase 3 | Transfer latency within 20% of old system |
| Screening functional | After Phase 3 | Send test sanctions hit, verify blocked |
| Connectors fire | After Phase 3 | Trigger test alert to ServiceNow/Slack |
| DNS cutover clean | After Phase 4 | All partners resolving to new IP |

---

## What the Migration Tool Must Handle

```
1. DISCOVER    → SSH into source, extract users/keys/configs/flows
2. ANALYZE     → AI reviews, identifies risks, suggests plan
3. PLAN        → Choose strategy: shadow/test/phased/cutover
4. IMPORT      → Create accounts, import keys, create flows in TranzFer
5. VALIDATE    → Test each account, flow, key, external destination
6. SHADOW      → Run parallel (optional), compare results
7. MIGRATE     → Execute per-batch migration with rollback
8. VERIFY      → End-to-end test per migrated partner
9. CUTOVER     → DNS flip + monitoring
10. REPORT     → Full migration report with status per item
```

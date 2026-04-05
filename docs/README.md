# TranzFer MFT — Documentation

> **New here?** Start with the [Product Guide](PRODUCT-GUIDE.md) or the [Getting Started](../README.md#-getting-started-pick-your-path) guide in the root README.

---

## Quick Links

| I want to... | Read this |
|--------------|-----------|
| **Understand the whole product** | [PRODUCT-GUIDE.md](PRODUCT-GUIDE.md) |
| **Understand the architecture** | [ARCHITECTURE.md](ARCHITECTURE.md) |
| **Set up a specific service** | [SERVICES.md](SERVICES.md) or the service's own README |
| **Understand the security layer** | [SECURITY-ARCHITECTURE.md](SECURITY-ARCHITECTURE.md) |
| **Find an API endpoint** | [API-REFERENCE.md](API-REFERENCE.md) or the service's own README |
| **Configure environment variables** | [CONFIGURATION.md](CONFIGURATION.md) |
| **Build and test the code** | [DEVELOPER-GUIDE.md](DEVELOPER-GUIDE.md) |
| **Install in production** | [INSTALLATION.md](INSTALLATION.md) |
| **Deploy to Kubernetes** | [INSTALL-KUBERNETES.md](INSTALL-KUBERNETES.md) |
| **Deploy on bare metal** | [INSTALL-ON-PREMISE.md](INSTALL-ON-PREMISE.md) |
| **Plan capacity** | [CAPACITY-PLANNING.md](CAPACITY-PLANNING.md) |
| **Migrate from another MFT** | [MIGRATION-CHECKLIST.md](MIGRATION-CHECKLIST.md) |
| **Check PCI compliance** | [PCI-DSS-COMPLIANCE.md](PCI-DSS-COMPLIANCE.md) |
| **See what's planned** | [PRODUCT-ROADMAP.md](PRODUCT-ROADMAP.md) |
| **Review gaps & known issues** | [GAP-ANALYSIS.md](GAP-ANALYSIS.md) |

---

## Documentation Map

### Centralized Docs (this directory)

```
docs/
├── README.md                    ← You are here
├── PRODUCT-GUIDE.md             Complete product reference
│
├── ARCHITECTURE.md              Architecture, service communication, data flows
├── SERVICES.md                  Every service: what it does, ports, config, setup
├── SECURITY-ARCHITECTURE.md     DMZ proxy + AI engine security deep-dive
├── API-REFERENCE.md             All REST endpoints across all services
├── CONFIGURATION.md             Every environment variable, port, and default
├── DEVELOPER-GUIDE.md           Build, test, debug, contribute
├── GAP-ANALYSIS.md              Known gaps in docs, security, testing, ops
│
├── INSTALLATION.md              Full production installation guide
├── INSTALL-KUBERNETES.md        Kubernetes + Helm deployment
├── INSTALL-ON-PREMISE.md        Bare metal / VM deployment
├── CAPACITY-PLANNING.md         Scaling guide (10K to 500M transfers/day)
├── MIGRATION-CHECKLIST.md       130-item migration checklist
├── PCI-DSS-COMPLIANCE.md        PCI DSS compliance mapping
└── PRODUCT-ROADMAP.md           Feature roadmap
```

### Per-Service READMEs (in each module directory)

Every microservice has its own `README.md` with detailed API endpoints, configuration, architecture, and setup instructions. Click any service below to navigate to its documentation:

**Core Services:**
- [onboarding-api/](../onboarding-api/) — Central API: accounts, auth, transfers, clustering, partner onboarding
- [config-service/](../config-service/) — Flows, connectors, security profiles, scheduling, platform settings
- [shared/](../shared/) — Shared library: entities, routing engine, security, migrations

**Protocol Services:**
- [sftp-service/](../sftp-service/) — SFTP server (SSH-based)
- [ftp-service/](../ftp-service/) — FTP/FTPS server
- [ftp-web-service/](../ftp-web-service/) — HTTP file upload/download API
- [as2-service/](../as2-service/) — AS2/AS4 B2B protocol handler

**Network & Security:**
- [gateway-service/](../gateway-service/) — Protocol gateway (user-based routing)
- [dmz-proxy/](../dmz-proxy/) — AI-powered security reverse proxy
- [ai-engine/](../ai-engine/) — Intelligence brain: classification, verdicts, NLP, anomaly detection

**Business Logic:**
- [encryption-service/](../encryption-service/) — AES-256/PGP encryption
- [keystore-manager/](../keystore-manager/) — Key and certificate lifecycle
- [screening-service/](../screening-service/) — OFAC/EU/UN sanctions screening
- [analytics-service/](../analytics-service/) — Metrics, dashboards, predictive scaling
- [storage-manager/](../storage-manager/) — Tiered storage with deduplication
- [edi-converter/](../edi-converter/) — EDI format detection, conversion, translation
- [external-forwarder-service/](../external-forwarder-service/) — Multi-protocol file forwarding
- [license-service/](../license-service/) — License validation and entitlements

**Frontends:**
- [admin-ui/](../admin-ui/) — Admin dashboard (React, 34+ pages)
- [ftp-web-ui/](../ftp-web-ui/) — File browser for end users (React)
- [partner-portal/](../partner-portal/) — Partner self-service portal (React)

**Tools & Infrastructure:**
- [cli/](../cli/) — Admin CLI tool
- [mft-client/](../mft-client/) — Desktop sync client
- [api-gateway/](../api-gateway/) — Nginx reverse proxy

---

## For Different Audiences

### I'm an Operator / SysAdmin
1. [PRODUCT-GUIDE.md](PRODUCT-GUIDE.md) — Understand the platform
2. [INSTALLATION.md](INSTALLATION.md) — Hardware, OS, network requirements
3. [CONFIGURATION.md](CONFIGURATION.md) — All environment variables
4. [SERVICES.md](SERVICES.md) — What each service does and needs
5. [SECURITY-ARCHITECTURE.md](SECURITY-ARCHITECTURE.md) — Firewall rules, DMZ setup

### I'm a Developer / Integrator
1. [DEVELOPER-GUIDE.md](DEVELOPER-GUIDE.md) — Build, test, debug
2. [API-REFERENCE.md](API-REFERENCE.md) — REST endpoints overview
3. Per-service READMEs — Detailed endpoints for each service
4. [ARCHITECTURE.md](ARCHITECTURE.md) — How services communicate

### I'm a Security Auditor
1. [SECURITY-ARCHITECTURE.md](SECURITY-ARCHITECTURE.md) — Threat model, data flows
2. [GAP-ANALYSIS.md](GAP-ANALYSIS.md) — Known security gaps
3. [PCI-DSS-COMPLIANCE.md](PCI-DSS-COMPLIANCE.md) — Compliance mapping
4. [dmz-proxy README](../dmz-proxy/) — Security layer details
5. [ai-engine README](../ai-engine/) — Threat intelligence details

### I'm Evaluating TranzFer
1. [PRODUCT-GUIDE.md](PRODUCT-GUIDE.md) — Complete product overview
2. [Root README](../README.md) — Quick start
3. [ARCHITECTURE.md](ARCHITECTURE.md) — What you're getting
4. [PRODUCT-ROADMAP.md](PRODUCT-ROADMAP.md) — Where it's going

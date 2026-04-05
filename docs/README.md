# TranzFer MFT — Documentation

> **New here?** Start with the [Getting Started](../README.md#-getting-started-pick-your-path) guide in the root README.

---

## Quick Links

| I want to... | Read this |
|--------------|-----------|
| **Understand the architecture** | [ARCHITECTURE.md](ARCHITECTURE.md) |
| **Set up a specific service** | [SERVICES.md](SERVICES.md) |
| **Understand the security layer** | [SECURITY-ARCHITECTURE.md](SECURITY-ARCHITECTURE.md) |
| **Find an API endpoint** | [API-REFERENCE.md](API-REFERENCE.md) |
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

```
docs/
├── README.md                    ← You are here
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

---

## For Different Audiences

### I'm an Operator / SysAdmin
1. [INSTALLATION.md](INSTALLATION.md) — Hardware, OS, network requirements
2. [CONFIGURATION.md](CONFIGURATION.md) — All environment variables
3. [SERVICES.md](SERVICES.md) — What each service does and needs
4. [SECURITY-ARCHITECTURE.md](SECURITY-ARCHITECTURE.md) — Firewall rules, DMZ setup

### I'm a Developer / Integrator
1. [DEVELOPER-GUIDE.md](DEVELOPER-GUIDE.md) — Build, test, debug
2. [API-REFERENCE.md](API-REFERENCE.md) — REST endpoints
3. [ARCHITECTURE.md](ARCHITECTURE.md) — How services communicate
4. [SERVICES.md](SERVICES.md) — Pick the services you need

### I'm a Security Auditor
1. [SECURITY-ARCHITECTURE.md](SECURITY-ARCHITECTURE.md) — Threat model, data flows
2. [GAP-ANALYSIS.md](GAP-ANALYSIS.md) — Known security gaps
3. [PCI-DSS-COMPLIANCE.md](PCI-DSS-COMPLIANCE.md) — Compliance mapping
4. [CONFIGURATION.md](CONFIGURATION.md) — Security-related config

### I'm Evaluating TranzFer
1. [Root README](../README.md) — Overview and quick start
2. [ARCHITECTURE.md](ARCHITECTURE.md) — What you're getting
3. [SERVICES.md](SERVICES.md) — Feature inventory
4. [PRODUCT-ROADMAP.md](PRODUCT-ROADMAP.md) — Where it's going

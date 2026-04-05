# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.x.x   | Yes       |
| < 1.0   | No        |

## Reporting a Vulnerability

We take security seriously at TranzFer MFT.

**Do NOT open a public GitHub issue for security vulnerabilities.**

Instead, please report vulnerabilities via email:

- Email: security@tranzfer.io
- Subject: [SECURITY] Brief description
- Include: steps to reproduce, impact assessment, affected versions

### What to expect

- Acknowledgment within 48 hours
- Status update within 5 business days
- Target resolution within 30 days for critical issues
- Credit in release notes (unless you prefer anonymity)

### Scope

The following are in scope:
- Authentication and authorization bypasses
- Data exposure or leakage
- Remote code execution
- SQL injection, XSS, CSRF
- Cryptographic weaknesses
- SFTP/FTP protocol vulnerabilities
- API security issues

### Out of Scope

- Denial of service attacks
- Social engineering
- Issues in third-party dependencies (report upstream)
- Issues requiring physical access

## Security Practices

- All data encrypted at rest (AES-256) and in transit (TLS 1.2+)
- OFAC/AML screening on all transfers
- PCI DSS compliant audit trail with HMAC integrity
- SHA-256 file checksums for integrity verification
- JWT-based authentication with configurable expiry
- Role-based access control (RBAC)
- Rate limiting on all API endpoints
- SSH key management via Keystore Manager

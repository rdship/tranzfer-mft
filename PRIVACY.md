# Privacy Policy

**Effective Date:** April 2026
**Last Updated:** April 2026

## Overview

TranzFer MFT ("the Software") is a self-hosted managed file transfer
platform. This privacy policy describes how data is handled within
the Software.

## Data Controller

When you deploy TranzFer MFT, YOUR ORGANIZATION is the data controller.
The software runs entirely within your infrastructure. The developers
of TranzFer MFT do not have access to your data.

## What Data the Software Processes

### User Account Data
- Usernames, email addresses, roles
- Authentication credentials (stored as bcrypt hashes, never plaintext)
- SSH public keys (managed via Keystore Manager)
- TOTP secrets (for two-factor authentication)

### File Transfer Data
- File metadata: filenames, sizes, timestamps, checksums (SHA-256)
- Transfer records: source, destination, protocol, status, Track IDs
- Journey tracking: end-to-end transfer lifecycle events
- Audit logs: all operations with HMAC integrity verification

### Screening Data
- Partner names checked against OFAC/SDN lists
- Screening results and match scores
- File content scanned for sanctions matches

### AI Engine Data
- File classification metadata
- Anomaly detection patterns
- Transfer behavior analytics
- All AI processing is local (no external API calls)

## Data Storage

All data is stored within your deployment environment:
- PostgreSQL database (user accounts, transfer records, audit logs)
- File system (transferred files, encryption keys)
- No data is transmitted to external servers by default

## Data Retention

Data retention is configurable by the system administrator:
- Transfer records: configurable (default: 365 days)
- Audit logs: configurable (default: 7 years for compliance)
- File data: per policy (configurable auto-deletion)
- User accounts: until manually removed

## External Connections

The Software may make outbound connections for:
- OFAC/SDN list updates (U.S. Treasury public data)
- External notification delivery (if configured): Slack, Teams,
  PagerDuty, ServiceNow, email (SMTP)
- NTP time synchronization
- Docker image pulls (during updates)

No telemetry, analytics, or usage data is collected by the developers.

## Data Subject Rights (GDPR)

If you process EU personal data, the Software supports:
- Data export (via API and admin UI)
- Data deletion (account removal, transfer record purging)
- Access logging (audit trail of all data access)
- Encryption at rest and in transit

Implementation of GDPR data subject requests is the responsibility
of the deploying organization.

## Encryption

- Data at rest: AES-256 encryption
- Data in transit: TLS 1.2+ (HTTPS, FTPS), SSH (SFTP)
- File integrity: SHA-256 checksums
- Audit integrity: HMAC verification
- Key management: Keystore Manager service

## Children's Privacy

This Software is intended for enterprise use and is not directed at
individuals under the age of 16.

## Changes to This Policy

Updates to this privacy policy will be reflected in the repository
and noted in the CHANGELOG.

## Contact

Privacy inquiries: privacy@tranzfer.io

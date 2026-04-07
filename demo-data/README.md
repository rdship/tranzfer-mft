# Demo Database Backups

These backups contain pre-populated demo data for TranzFer MFT demonstrations.
Use ONLY for demos — not for development or testing.

## Restore

```bash
# Drop and recreate the database
docker exec mft-postgres psql -U postgres -c "DROP DATABASE IF EXISTS filetransfer; CREATE DATABASE filetransfer;"

# Restore the backup
docker exec -i mft-postgres pg_restore -U postgres -d filetransfer < demo-data/demo-db-YYYYMMDD-HHMM.dump

# Restart services to pick up the restored data
docker compose restart
```

## Contents (~1000 objects)

| Entity | Count |
|--------|-------|
| Transfer Accounts | 225 (100 SFTP + 100 FTP + 25 Web) |
| File Flows | 178 |
| Partners | 48 |
| AS2/AS4 Partnerships | 42 |
| Encryption Keys | 80 |
| Delivery Endpoints | 30 |
| External Destinations | 30 |
| Server Instances | 28 |
| Legacy Servers | 28 |
| Tenants | 26 |
| Security Profiles | 26 |
| Folder Templates | 26 |
| Scheduled Tasks | 26 |
| DLP Policies | 26 |
| Webhook Connectors | 26 |
| Alert Rules | 26 |
| SLA Agreements | 27 |
| Platform Settings | 31 |
| Folder Mappings | 30 |
| Listener Security Policies | 16 |

## Login

- URL: http://localhost:3000
- Email: admin@filetransfer.local
- Password: Tr@nzFer2026!

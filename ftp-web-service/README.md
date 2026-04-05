# FTP-Web Service

> HTTP-based file management API for web browser uploads, downloads, and directory operations.

**Port:** 8083 | **Database:** PostgreSQL | **Messaging:** RabbitMQ | **Required:** Optional

---

## Overview

The FTP-Web service provides a REST API for browser-based file operations. It powers the ftp-web-ui (file browser) and supports:

- **File upload** via multipart form data (up to 512 MB)
- **File download** with proper content disposition
- **Directory listing** with file metadata
- **Directory creation, rename, and delete**
- **JWT authentication** (Bearer token)
- **Path traversal protection** — users are sandboxed in their home directory
- **File routing integration** — uploads trigger the flow engine

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq onboarding-api ftp-web-service ftp-web-ui

# API health check
curl http://localhost:8083/internal/health

# Open file browser
open http://localhost:3001
```

---

## API Endpoints

### File Operations (JWT Required)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/files/list?path=/inbox` | List directory contents |
| POST | `/api/files/upload?path=/inbox` | Upload file (multipart) |
| GET | `/api/files/download?path=/inbox/file.csv` | Download file |
| DELETE | `/api/files/delete?path=/inbox/old.csv` | Delete file or directory |
| POST | `/api/files/mkdir?path=/inbox/reports` | Create directory |
| POST | `/api/files/rename?from=/old&to=/new` | Rename or move file |

**List directory:**
```bash
curl "http://localhost:8083/api/files/list?path=/inbox" \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
[
  {"name": "invoice.csv", "path": "/inbox/invoice.csv", "directory": false, "size": 245760, "lastModified": "2026-04-05T10:30:00Z"},
  {"name": "reports", "path": "/inbox/reports", "directory": true, "size": 0, "lastModified": "2026-04-04T08:00:00Z"}
]
```

**Upload file:**
```bash
curl -X POST "http://localhost:8083/api/files/upload?path=/inbox" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@invoice.csv"
```

**Download file:**
```bash
curl "http://localhost:8083/api/files/download?path=/inbox/invoice.csv" \
  -H "Authorization: Bearer $TOKEN" \
  -o invoice.csv
```

### Internal API

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| GET | `/internal/health` | None | Service health status |
| POST | `/internal/files/receive` | X-Internal-Key | Receive forwarded files |

---

## Security

- **JWT authentication** — Bearer token required on all `/api/files/*` endpoints
- **Path traversal protection** — All paths are normalized and validated to stay within the user's home directory
- **CORS** — Configurable allowed origins (default: `localhost:3000,3001,3002`)
- **File size limit** — 512 MB max upload

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8083` | HTTP port |
| `FTPWEB_HOME_BASE` | `/data/ftpweb` | Root directory for user homes |
| `INSTANCE_ID` | — | Instance ID for clustering |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `JWT_SECRET` | (shared) | JWT signing key |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `512MB` | Max upload size |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,3001,3002` | CORS origins |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key |

---

## Dependencies

- **PostgreSQL** — Required. Account lookup and transfer records.
- **RabbitMQ** — Required. Listens on `ftpweb.account.events`.
- **shared** module — Entities, routing engine, JWT utilities.

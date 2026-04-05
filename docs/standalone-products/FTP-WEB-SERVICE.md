# FTP Web Service — Standalone Product Guide

> **HTTP file operations API.** Upload, download, list, delete, mkdir, and rename files via REST. JWT-authenticated with path traversal protection.

**Port:** 8083 | **Dependencies:** PostgreSQL, RabbitMQ | **Auth:** JWT Bearer token

---

## Why Use This

- **Pure HTTP file API** — No FTP/SFTP client needed, just REST calls
- **Full file operations** — Upload, download, list, delete, mkdir, rename
- **JWT authentication** — Secure token-based access
- **Path traversal protection** — Sandboxed to user home directory
- **512 MB uploads** — Handle large file transfers
- **CORS support** — Ready for browser-based file managers

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq onboarding-api ftp-web-service

# Login to get JWT token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@example.com", "password": "password123"}' | jq -r '.accessToken')
```

---

## API Reference

### 1. List Files

**GET** `/api/files/list`

```bash
curl http://localhost:8083/api/files/list?path=/inbox \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
[
  {"name": "report.csv", "path": "/inbox/report.csv", "directory": false, "size": 1048576, "lastModified": "2026-04-05T14:00:00Z"},
  {"name": "archive", "path": "/inbox/archive", "directory": true, "size": 0, "lastModified": "2026-04-05T10:00:00Z"}
]
```

### 2. Upload File

**POST** `/api/files/upload`

```bash
curl -X POST http://localhost:8083/api/files/upload?path=/inbox \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/report.csv"
```

### 3. Download File

**GET** `/api/files/download`

```bash
curl http://localhost:8083/api/files/download?path=/inbox/report.csv \
  -H "Authorization: Bearer $TOKEN" \
  --output report.csv
```

### 4. Delete File

**DELETE** `/api/files/delete`

```bash
curl -X DELETE "http://localhost:8083/api/files/delete?path=/inbox/old-file.csv" \
  -H "Authorization: Bearer $TOKEN"
```

### 5. Create Directory

**POST** `/api/files/mkdir`

```bash
curl -X POST "http://localhost:8083/api/files/mkdir?path=/inbox/batch-2026-04" \
  -H "Authorization: Bearer $TOKEN"
```

### 6. Rename File

**POST** `/api/files/rename`

```bash
curl -X POST "http://localhost:8083/api/files/rename?from=/inbox/old.csv&to=/inbox/new.csv" \
  -H "Authorization: Bearer $TOKEN"
```

---

## Integration Examples

### Python
```python
import requests

BASE = "http://localhost:8083/api/files"
HEADERS = {"Authorization": f"Bearer {token}"}

# Upload
with open("data.csv", "rb") as f:
    requests.post(f"{BASE}/upload?path=/inbox", headers=HEADERS, files={"file": f})

# List
files = requests.get(f"{BASE}/list?path=/inbox", headers=HEADERS).json()
for f in files:
    print(f"{f['name']} ({f['size']} bytes)")

# Download
resp = requests.get(f"{BASE}/download?path=/inbox/data.csv", headers=HEADERS)
with open("downloaded.csv", "wb") as f:
    f.write(resp.content)
```

### JavaScript (Browser)
```javascript
// Upload with drag-and-drop
async function uploadFile(file) {
  const form = new FormData();
  form.append('file', file);

  await fetch('/api/files/upload?path=/inbox', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` },
    body: form
  });
}

// List files
const files = await fetch('/api/files/list?path=/inbox', {
  headers: { 'Authorization': `Bearer ${token}` }
}).then(r => r.json());
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `FTPWEB_HOME_BASE` | `/data/ftpweb` | User home base directory |
| `JWT_SECRET` | (insecure default) | JWT signing secret |
| `spring.servlet.multipart.max-file-size` | `512MB` | Max upload size |
| `server.port` | `8083` | HTTP port |

---

## All Endpoints Summary

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/files/list` | JWT | List files/directories |
| POST | `/api/files/upload` | JWT | Upload file (multipart) |
| GET | `/api/files/download` | JWT | Download file |
| DELETE | `/api/files/delete` | JWT | Delete file |
| POST | `/api/files/mkdir` | JWT | Create directory |
| POST | `/api/files/rename` | JWT | Rename file |
| POST | `/internal/files/receive` | X-Internal-Key | Receive forwarded file |
| GET | `/internal/health` | None | Health check |

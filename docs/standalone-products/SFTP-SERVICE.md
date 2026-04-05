# SFTP Service — Standalone Product Guide

> **Production SFTP server.** Apache MINA SSHD-based SFTP server with password + public key authentication, user sandboxing, automatic directory provisioning, and file routing integration.

**Port:** 2222 (SFTP) + 8081 (HTTP) | **Dependencies:** PostgreSQL, RabbitMQ | **Auth:** Username/password or SSH public key

---

## Why Use This

- **Production-grade SFTP** — Built on Apache MINA SSHD (battle-tested SSH library)
- **Dual auth** — Password and public key authentication
- **User sandboxing** — Each user sees only their home directory
- **Auto-provisioning** — Creates inbox/outbox/archive/sent directories per user
- **File routing** — Uploaded files automatically routed through platform flows
- **Event streaming** — Account events published to RabbitMQ

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq sftp-service

# Connect with any SFTP client
sftp -P 2222 username@localhost
```

### Verify
```bash
curl http://localhost:8081/internal/health
```

```json
{
  "status": "UP",
  "sftpServerRunning": true,
  "sftpPort": 2222
}
```

---

## Connecting as a Client

### Command Line (sftp)
```bash
# Password auth
sftp -P 2222 partner_acme@mft.example.com

# Public key auth
sftp -P 2222 -i ~/.ssh/partner_key partner_acme@mft.example.com
```

### Python (paramiko)
```python
import paramiko

# Password auth
transport = paramiko.Transport(("localhost", 2222))
transport.connect(username="partner_acme", password="secure_password")
sftp = paramiko.SFTPClient.from_transport(transport)

# Upload file
sftp.put("local-report.csv", "/inbox/report.csv")

# Download file
sftp.get("/outbox/results.csv", "local-results.csv")

# List files
for entry in sftp.listdir("/inbox"):
    print(entry)

sftp.close()
transport.close()
```

### Java (JSch)
```java
JSch jsch = new JSch();
Session session = jsch.getSession("partner_acme", "localhost", 2222);
session.setPassword("secure_password");
session.setConfig("StrictHostKeyChecking", "no");
session.connect();

ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
sftp.connect();

// Upload
sftp.put("local-file.csv", "/inbox/file.csv");

// Download
sftp.get("/outbox/result.csv", "local-result.csv");

sftp.disconnect();
session.disconnect();
```

### Node.js (ssh2-sftp-client)
```javascript
const SftpClient = require('ssh2-sftp-client');

const sftp = new SftpClient();
await sftp.connect({
  host: 'localhost',
  port: 2222,
  username: 'partner_acme',
  password: 'secure_password'
});

// Upload
await sftp.put('local-file.csv', '/inbox/file.csv');

// Download
await sftp.get('/outbox/result.csv', 'local-result.csv');

// List
const list = await sftp.list('/inbox');
list.forEach(f => console.log(f.name, f.size));

await sftp.end();
```

---

## Directory Structure (Per User)

```
/home/partner_acme/
├── inbox/      ← Upload files here (triggers routing)
├── outbox/     ← Files delivered to this user appear here
├── archive/    ← Processed files moved here
└── sent/       ← Copies of sent files
```

---

## HTTP API

### Receive Internal File Forward

**POST** `/internal/files/receive`

```bash
curl -X POST http://localhost:8081/internal/files/receive \
  -H "X-Internal-Key: internal_control_secret" \
  -H "Content-Type: application/json" \
  -d '{
    "recordId": "a1b2c3d4-...",
    "destinationUsername": "partner_acme",
    "destinationAbsolutePath": "/outbox/report.csv",
    "fileContentBase64": "aGVsbG8gd29ybGQ=",
    "originalFilename": "report.csv"
  }'
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `SFTP_PORT` | `2222` | SFTP server port |
| `SFTP_HOME_BASE` | `/data/sftp` | Base directory for user homes |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key |
| `server.port` | `8081` | HTTP management port |

---

## All Endpoints Summary

| Protocol | Port | Path | Description |
|----------|------|------|-------------|
| SFTP | 2222 | N/A | SFTP protocol (SSH-based file transfer) |
| HTTP | 8081 | POST `/internal/files/receive` | Receive forwarded file |
| HTTP | 8081 | GET `/internal/health` | Health check |

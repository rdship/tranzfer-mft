# FTP Service — Standalone Product Guide

> **Production FTP/FTPS server.** Apache FtpServer-based with passive mode, optional TLS encryption, connection limits, and automatic directory provisioning.

**Port:** 21 (FTP) + 8082 (HTTP) | **Dependencies:** PostgreSQL, RabbitMQ | **Auth:** Username/password

---

## Why Use This

- **Production FTP server** — Built on Apache FtpServer
- **FTPS support** — Optional TLS encryption (explicit mode)
- **Passive mode** — Configurable passive port range (21000-21010)
- **Connection limits** — 10 concurrent connections, 5 per user
- **Auto-provisioning** — Creates user home directories automatically
- **File routing** — Uploaded files routed through platform flows

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq ftp-service

# Connect with any FTP client
ftp localhost 21
```

---

## Connecting as a Client

### Command Line (ftp)
```bash
ftp localhost 21
# Username: partner_acme
# Password: secure_password
ftp> cd inbox
ftp> put local-file.csv
ftp> ls
ftp> get outbox/result.csv
ftp> bye
```

### Python (ftplib)
```python
from ftplib import FTP

ftp = FTP()
ftp.connect("localhost", 21)
ftp.login("partner_acme", "secure_password")

# Upload
with open("report.csv", "rb") as f:
    ftp.storbinary("STOR /inbox/report.csv", f)

# Download
with open("result.csv", "wb") as f:
    ftp.retrbinary("RETR /outbox/result.csv", f.write)

# List
ftp.retrlines("LIST /inbox")

ftp.quit()
```

### Python (FTPS — TLS)
```python
from ftplib import FTP_TLS

ftps = FTP_TLS()
ftps.connect("localhost", 21)
ftps.auth()              # Upgrade to TLS
ftps.prot_p()            # Protect data channel
ftps.login("partner_acme", "secure_password")

with open("sensitive.csv", "rb") as f:
    ftps.storbinary("STOR /inbox/sensitive.csv", f)

ftps.quit()
```

### Java (Apache Commons Net)
```java
FTPClient ftp = new FTPClient();
ftp.connect("localhost", 21);
ftp.login("partner_acme", "secure_password");
ftp.enterLocalPassiveMode();
ftp.setFileType(FTP.BINARY_FILE_TYPE);

// Upload
try (InputStream is = new FileInputStream("report.csv")) {
    ftp.storeFile("/inbox/report.csv", is);
}

// Download
try (OutputStream os = new FileOutputStream("result.csv")) {
    ftp.retrieveFile("/outbox/result.csv", os);
}

ftp.logout();
ftp.disconnect();
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `FTP_PORT` | `21` | FTP server port |
| `FTP_PASSIVE_PORTS` | `21000-21010` | Passive mode port range |
| `FTP_HOME_BASE` | `/data/ftp` | User home base directory |
| `ftp.ftps.enabled` | `false` | Enable FTPS (TLS) |
| `ftp.ftps.protocol` | `TLSv1.2` | TLS protocol version |
| `server.port` | `8082` | HTTP management port |

---

## All Endpoints Summary

| Protocol | Port | Path | Description |
|----------|------|------|-------------|
| FTP | 21 | N/A | FTP protocol (file transfer) |
| FTP | 21000-21010 | N/A | Passive mode data channels |
| HTTP | 8082 | POST `/internal/files/receive` | Receive forwarded file |
| HTTP | 8082 | GET `/internal/health` | Health check |

# FTP Service -- Demo & Quick Start Guide

> Production-grade FTP/FTPS server built on Apache FtpServer with passive mode support, explicit TLS (AUTH TLS), database-backed user management, per-user home directories, and real-time upload/download event routing via RabbitMQ.

---

## What This Service Does

- **FTP protocol server** on port 21 (configurable) using Apache FtpServer, with passive data connections on ports 21000-21010 (configurable).
- **FTPS (FTP over TLS)** support via explicit TLS -- clients connect on port 21 and upgrade to TLS using the `AUTH TLS` command. When enabled, the server auto-generates a self-signed keystore if none is provided.
- **Database-backed user management** through `FtpUserManager` -- all user lookups, authentication, and permission checks query the shared PostgreSQL database (same `transfer_accounts` table used by all services). User creation/deletion is only possible through the Onboarding API.
- **Per-user home directories** with configurable permissions -- each user is chroot-jailed to their home directory. Write permission is granted only if the account's permissions map includes `"write": true`. Concurrent login limits are enforced (10 concurrent sessions, 5 per IP).
- **Real-time file event routing** via `FtpletRoutingAdapter` -- hooks into Apache FtpServer's Ftplet interface to detect `onUploadEnd` and `onDownloadEnd` events and delegates to the shared RoutingEngine for automated processing.
- **Admin HTTP API** on port 8082 with health check at `GET /internal/health` and internal file-receive at `POST /internal/files/receive`.

---

## What You Need (Prerequisites Checklist)

| Requirement | Why | Install Guide |
|-------------|-----|---------------|
| **Docker** OR **Java 21 + Maven** | Run or build the service | [PREREQUISITES.md -- Step 1](PREREQUISITES.md#step-1--choose-your-installation-method) |
| **PostgreSQL 16** | Stores transfer accounts, audit logs, flow definitions | [PREREQUISITES.md -- Step 2](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it) |
| **RabbitMQ 3.13** | Receives account-change events from Onboarding API | [PREREQUISITES.md -- Step 3](PREREQUISITES.md#step-3--install-rabbitmq-if-your-service-needs-it) |
| **Onboarding API** (port 8080) | Creates FTP transfer accounts before you can connect | Included in Docker Compose below |
| **An FTP client** | Connect to the server | See OS-specific options in Demos below |

**Port requirements:** 21 (FTP control), 21000-21010 (FTP passive data), 8082 (Admin HTTP), 5432 (PostgreSQL), 5672 + 15672 (RabbitMQ), 8080 (Onboarding API).

**Note on port 21:** On Linux, binding to port 21 requires root privileges or the `NET_BIND_SERVICE` capability. The Docker container handles this automatically. When running from source, either use `sudo`, grant the capability, or change the port to 2121 (`FTP_PORT=2121`).

---

## Install & Start

### Method 1: Docker Compose (Recommended -- Starts Everything)

Create a file named `docker-compose-ftp-demo.yml`:

```yaml
version: '3.9'

services:
  postgres:
    image: postgres:16-alpine
    container_name: mft-postgres
    environment:
      POSTGRES_DB: filetransfer
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5

  rabbitmq:
    image: rabbitmq:3.13-management-alpine
    container_name: mft-rabbitmq
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest
    ports:
      - "5672:5672"
      - "15672:15672"
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  onboarding-api:
    build: ./onboarding-api
    container_name: mft-onboarding-api
    ports:
      - "8080:8080"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      RABBITMQ_HOST: rabbitmq
      JWT_SECRET: change_me_in_production_256bit_secret_key!!
      CONTROL_API_KEY: internal_control_secret
      FTP_HOME_BASE: /data/ftp
      CLUSTER_HOST: onboarding-api
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

  ftp-service:
    build: ./ftp-service
    container_name: mft-ftp-service
    ports:
      - "21:21"
      - "21000-21010:21000-21010"
      - "8082:8082"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      RABBITMQ_HOST: rabbitmq
      JWT_SECRET: change_me_in_production_256bit_secret_key!!
      CONTROL_API_KEY: internal_control_secret
      FTP_PORT: 21
      FTP_PASSIVE_PORTS: 21000-21010
      FTP_PUBLIC_HOST: 127.0.0.1
      FTP_HOME_BASE: /data/ftp
      FTP_INSTANCE_ID: ftp-1
      CLUSTER_HOST: ftp-service
    volumes:
      - ftp_data:/data/ftp
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

volumes:
  ftp_data:
```

Start it from the repository root:

```bash
cd /path/to/file-transfer-platform
docker compose -f docker-compose-ftp-demo.yml up -d
```

Wait for services to become healthy:

```bash
docker compose -f docker-compose-ftp-demo.yml ps
```

Expected output:

```
NAME                 STATUS              PORTS
mft-postgres         running (healthy)   0.0.0.0:5432->5432/tcp
mft-rabbitmq         running (healthy)   0.0.0.0:5672->5672/tcp, 0.0.0.0:15672->15672/tcp
mft-onboarding-api   running (healthy)   0.0.0.0:8080->8080/tcp
mft-ftp-service      running (healthy)   0.0.0.0:21->21/tcp, 0.0.0.0:21000-21010->21000-21010/tcp, 0.0.0.0:8082->8082/tcp
```

### Method 2: Docker (Standalone Container)

If you already have PostgreSQL and RabbitMQ running:

```bash
# Build the image
cd /path/to/file-transfer-platform
mvn clean package -DskipTests -pl ftp-service -am
docker build -t mft-ftp-service ./ftp-service

# Run
docker run -d \
  --name mft-ftp-service \
  -p 21:21 \
  -p 21000-21010:21000-21010 \
  -p 8082:8082 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e RABBITMQ_HOST=host.docker.internal \
  -e FTP_PORT=21 \
  -e FTP_PASSIVE_PORTS=21000-21010 \
  -e FTP_PUBLIC_HOST=127.0.0.1 \
  -e FTP_HOME_BASE=/data/ftp \
  -e JWT_SECRET=change_me_in_production_256bit_secret_key!! \
  -e CONTROL_API_KEY=internal_control_secret \
  -v ftp_data:/data/ftp \
  mft-ftp-service
```

### Method 3: From Source

```bash
cd /path/to/file-transfer-platform

# Build (from repository root)
mvn clean package -DskipTests -pl ftp-service -am

# Run (use port 2121 to avoid needing root on Linux/macOS)
java -jar ftp-service/target/*.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/filetransfer \
  --spring.datasource.username=postgres \
  --spring.datasource.password=postgres \
  --spring.rabbitmq.host=localhost \
  --ftp.port=2121 \
  --ftp.passive-ports=21000-21010 \
  --ftp.public-host=127.0.0.1 \
  --ftp.home-base=/data/ftp
```

---

## Verify It's Running

```bash
# Check the admin health endpoint
curl -s http://localhost:8082/internal/health | python3 -m json.tool
```

Expected output:

```json
{
    "status": "UP",
    "ftpServerStopped": false,
    "instanceId": "ftp-1"
}
```

Note: `"ftpServerStopped": false` means the server IS running (the field name comes from Apache FtpServer's `isStopped()` method).

---

## Demo 1: Create an Account and Connect via FTP

### Step 1 -- Register an admin user and get a JWT token

```bash
# Register (skip if you already have a token from the SFTP demo)
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@demo.local", "password": "DemoPass123!"}' | python3 -m json.tool
```

Expected output:

```json
{
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 900000
}
```

Save the token:

```bash
TOKEN="eyJhbGciOiJIUzI1NiJ9..."   # paste the actual accessToken value here
```

### Step 2 -- Create an FTP transfer account

```bash
curl -s -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "protocol": "FTP",
    "username": "ftpuser1",
    "password": "FtpSecure88!",
    "permissions": {"read": true, "write": true, "delete": false}
  }' | python3 -m json.tool
```

Expected output:

```json
{
    "id": "b2c3d4e5-...",
    "protocol": "FTP",
    "username": "ftpuser1",
    "homeDir": "/data/ftp/ftpuser1",
    "permissions": {
        "read": true,
        "write": true,
        "delete": false
    },
    "active": true,
    "serverInstance": null,
    "createdAt": "2026-04-05T10:00:00Z",
    "connectionInstructions": "..."
}
```

### Step 3 -- Connect via FTP

#### Linux / macOS -- command-line `ftp`

```bash
ftp localhost 21
```

When prompted:

```
Connected to localhost.
220 Service ready for new user.
Name (localhost:youruser): ftpuser1
331 User name okay, need password.
Password: FtpSecure88!
230 User logged in, proceed.
Remote system type is UNIX.
ftp>
```

**Important:** The built-in `ftp` command on macOS and some Linux distributions may not support passive mode well. If you get "connection refused" on data commands, switch to `lftp`:

```bash
# Install lftp
# Linux: sudo apt-get install -y lftp
# macOS: brew install lftp

lftp -u ftpuser1,FtpSecure88! localhost -p 21
```

Once connected:

```
lftp ftpuser1@localhost:~> ls
drwxr-xr-x   2 ftpuser1 ftpuser1     4096 Apr 05 10:00 .
lftp ftpuser1@localhost:~> set ftp:passive-mode on
```

#### Windows -- Built-in FTP client

```powershell
ftp localhost
```

```
Connected to localhost.
220 Service ready for new user.
User (localhost:(none)): ftpuser1
331 User name okay, need password.
Password: FtpSecure88!
230 User logged in, proceed.
ftp> passive
Passive mode On.
ftp> dir
```

#### Windows -- FileZilla (Recommended)

1. Download FileZilla from https://filezilla-project.org/
2. Open FileZilla and enter:
   - **Host:** localhost
   - **Username:** ftpuser1
   - **Password:** FtpSecure88!
   - **Port:** 21
3. Click **Quickconnect**
4. If prompted about insecure FTP, click OK (or use FTPS -- see Demo 2)

### Step 4 -- Upload and download a file

Using `lftp`:

```bash
lftp -u ftpuser1,FtpSecure88! localhost -p 21 -e "
  set ftp:passive-mode on;
  put /etc/hostname -o upload-test.txt;
  ls;
  get upload-test.txt -o /tmp/downloaded.txt;
  bye
"
```

Expected output:

```
12 bytes transferred
-rw-r--r--    1 ftpuser1 ftpuser1       12 Apr 05 10:01 upload-test.txt
12 bytes transferred
```

The FTP service logs show the upload and download detection:

```bash
docker logs mft-ftp-service 2>&1 | tail -5
```

```
INFO  FTP server started on port 21
INFO  FTP upload detected: user=ftpuser1 path=/upload-test.txt
INFO  FTP download detected: user=ftpuser1 path=/data/ftp/ftpuser1/upload-test.txt
```

---

## Demo 2: FTPS (FTP over TLS) Connection

The FTP service supports explicit FTPS -- clients connect on port 21 and upgrade to TLS using the `AUTH TLS` command. This encrypts both the control channel and data transfers.

### Step 1 -- Enable FTPS

Add these environment variables when starting the service:

```bash
# Docker Compose: add to the ftp-service environment section
FTP_FTPS_ENABLED: "true"
FTP_FTPS_KEYSTORE_PATH: /app/ftp-keystore.jks
FTP_FTPS_KEYSTORE_PASSWORD: changeit
FTP_FTPS_PROTOCOL: TLSv1.2
FTP_FTPS_CLIENT_AUTH: "false"
```

Or from source:

```bash
java -jar ftp-service/target/*.jar \
  --ftp.ftps.enabled=true \
  --ftp.ftps.keystore-path=./ftp-keystore.jks \
  --ftp.ftps.keystore-password=changeit \
  --ftp.ftps.protocol=TLSv1.2 \
  --ftp.ftps.client-auth=false \
  # ... other flags
```

If the keystore file does not exist, the service auto-generates a self-signed certificate using `keytool`:

```
INFO  FTPS enabled but keystore not found at /app/ftp-keystore.jks. Generating self-signed...
INFO  Generated self-signed FTPS keystore at /app/ftp-keystore.jks
INFO  FTPS configured: protocol=TLSv1.2, clientAuth=false, keystore=/app/ftp-keystore.jks
INFO  FTPS enabled: explicit TLS on port 21
```

### Step 2 -- Connect with FTPS using `lftp`

```bash
# Connect with explicit TLS (AUTH TLS on port 21)
lftp -u ftpuser1,FtpSecure88! -p 21 -e "
  set ftp:ssl-allow yes;
  set ftp:ssl-force yes;
  set ftp:ssl-protect-data yes;
  set ssl:verify-certificate no;
  set ftp:passive-mode on;
  ls;
  bye
" localhost
```

The `set ftp:ssl-force yes` command ensures the connection uses TLS. The `set ssl:verify-certificate no` is needed because of the self-signed certificate (do not use this in production).

### Step 3 -- Connect with FTPS using FileZilla

1. Open FileZilla and go to **File > Site Manager > New Site**
2. Configure:
   - **Protocol:** FTP - File Transfer Protocol
   - **Host:** localhost
   - **Port:** 21
   - **Encryption:** Require explicit FTP over TLS
   - **Logon Type:** Normal
   - **User:** ftpuser1
   - **Password:** FtpSecure88!
3. Click **Connect**
4. Accept the self-signed certificate when prompted
5. The status bar will show: `TLS connection established`

### Step 4 -- Connect with FTPS using `curl`

```bash
# Upload a file via FTPS
echo "Hello FTPS" > /tmp/ftps-test.txt
curl --ftp-ssl --insecure \
  -T /tmp/ftps-test.txt \
  ftp://ftpuser1:FtpSecure88!@localhost:21/ftps-test.txt

# Download a file via FTPS
curl --ftp-ssl --insecure \
  -o /tmp/ftps-downloaded.txt \
  ftp://ftpuser1:FtpSecure88!@localhost:21/ftps-test.txt

# List files via FTPS
curl --ftp-ssl --insecure \
  ftp://ftpuser1:FtpSecure88!@localhost:21/
```

---

## Demo 3: Integration Pattern -- Python, Java, Node.js

### Python (ftplib)

```python
from ftplib import FTP, FTP_TLS
import io

# --- Plain FTP ---
ftp = FTP()
ftp.connect("localhost", 21)
ftp.login("ftpuser1", "FtpSecure88!")
ftp.set_pasv(True)  # Enable passive mode

# List files
print("Files:", ftp.nlst())

# Upload a file
data = b"id,amount,currency\n1,2500.00,EUR\n"
ftp.storbinary("STOR invoice.csv", io.BytesIO(data))
print("Uploaded: invoice.csv")

# Download a file
buffer = io.BytesIO()
ftp.retrbinary("RETR invoice.csv", buffer.write)
print("Downloaded content:", buffer.getvalue().decode())

# List files again
print("Files after upload:", ftp.nlst())

ftp.quit()
```

### Python (ftplib) -- FTPS

```python
from ftplib import FTP_TLS
import io

# --- FTPS (Explicit TLS) ---
ftps = FTP_TLS()
ftps.connect("localhost", 21)
ftps.auth()            # Upgrade to TLS (AUTH TLS command)
ftps.prot_p()          # Encrypt data channel too
ftps.login("ftpuser1", "FtpSecure88!")
ftps.set_pasv(True)

# List files
print("FTPS files:", ftps.nlst())

# Upload
data = b"secure,data\n1,encrypted\n"
ftps.storbinary("STOR secure-file.csv", io.BytesIO(data))
print("Uploaded via FTPS: secure-file.csv")

ftps.quit()
```

```bash
# No extra dependencies needed -- ftplib is in the Python standard library
python ftp_demo.py
```

Expected output:

```
Files: []
Uploaded: invoice.csv
Downloaded content: id,amount,currency
1,2500.00,EUR

Files after upload: ['invoice.csv']
```

### Java (Apache Commons Net)

```java
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.ftp.FTPReply;

import java.io.*;

public class FtpDemo {
    public static void main(String[] args) throws Exception {
        // --- Plain FTP ---
        FTPClient ftp = new FTPClient();
        ftp.connect("localhost", 21);
        ftp.login("ftpuser1", "FtpSecure88!");
        ftp.enterLocalPassiveMode();

        // Check connection
        int reply = ftp.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            throw new IOException("FTP connection failed: " + reply);
        }

        // Upload
        String content = "id,amount,currency\n1,3000.00,GBP\n";
        ftp.storeFile("payment.csv",
            new ByteArrayInputStream(content.getBytes()));
        System.out.println("Uploaded: payment.csv");

        // List
        for (String name : ftp.listNames()) {
            System.out.println("  File: " + name);
        }

        // Download
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ftp.retrieveFile("payment.csv", out);
        System.out.println("Downloaded: " + out.toString());

        ftp.logout();
        ftp.disconnect();

        // --- FTPS ---
        FTPSClient ftps = new FTPSClient("TLS", false); // explicit TLS
        ftps.connect("localhost", 21);
        ftps.login("ftpuser1", "FtpSecure88!");
        ftps.execPBSZ(0);
        ftps.execPROT("P");
        ftps.enterLocalPassiveMode();

        System.out.println("FTPS files: " +
            java.util.Arrays.toString(ftps.listNames()));

        ftps.logout();
        ftps.disconnect();
    }
}
```

Add to `pom.xml`:

```xml
<dependency>
    <groupId>commons-net</groupId>
    <artifactId>commons-net</artifactId>
    <version>3.10.0</version>
</dependency>
```

### Node.js (basic-ftp)

```javascript
const ftp = require('basic-ftp');
const { Readable } = require('stream');

async function main() {
  const client = new ftp.Client();
  client.ftp.verbose = true;

  // --- Plain FTP ---
  await client.access({
    host: 'localhost',
    port: 21,
    user: 'ftpuser1',
    password: 'FtpSecure88!',
    secure: false
  });

  // List files
  const list = await client.list();
  console.log('Files:', list.map(f => f.name));

  // Upload a file
  const data = Buffer.from('id,amount,currency\n1,4000.00,JPY\n');
  await client.uploadFrom(Readable.from(data), 'trade.csv');
  console.log('Uploaded: trade.csv');

  // Download a file
  const chunks = [];
  const writable = new (require('stream').Writable)({
    write(chunk, enc, cb) { chunks.push(chunk); cb(); }
  });
  await client.downloadTo(writable, 'trade.csv');
  console.log('Downloaded:', Buffer.concat(chunks).toString());

  client.close();

  // --- FTPS ---
  const ftpsClient = new ftp.Client();
  await ftpsClient.access({
    host: 'localhost',
    port: 21,
    user: 'ftpuser1',
    password: 'FtpSecure88!',
    secure: true,            // Enable TLS
    secureOptions: {
      rejectUnauthorized: false  // Self-signed cert
    }
  });

  const ftpsList = await ftpsClient.list();
  console.log('FTPS files:', ftpsList.map(f => f.name));

  ftpsClient.close();
}

main().catch(console.error);
```

```bash
# Install and run
npm install basic-ftp
node ftp_demo.js
```

Expected output:

```
Files: []
Uploaded: trade.csv
Downloaded: id,amount,currency
1,4000.00,JPY

FTPS files: [ 'trade.csv' ]
```

---

## Use Cases

1. **Legacy system integration** -- Connect to partners who only support FTP (no SFTP). Many mainframe systems, ERP platforms, and older B2B gateways require FTP. The FTP service bridges legacy FTP partners into the modern TranzFer platform.

2. **FTPS for compliance** -- Enable explicit TLS to encrypt FTP transfers in transit. This satisfies PCI-DSS requirement 4.1 (encrypt cardholder data across open networks) while maintaining compatibility with partners who cannot migrate to SFTP.

3. **Passive mode for NAT/firewall traversal** -- The configurable passive port range (21000-21010) and public host address (`FTP_PUBLIC_HOST`) allow the FTP service to operate behind NAT gateways and firewalls, which is essential for cloud and DMZ deployments.

4. **Automated batch file pickup** -- Partners upload daily settlement files, payment batches, or inventory updates. The `FtpletRoutingAdapter` detects each upload and triggers processing flows (validation, format conversion, encryption) before routing to the destination.

5. **Concurrent session control** -- The FTP service enforces limits of 10 concurrent sessions per user and 5 per IP address (configured in `FtpUserManager.toFtpUser()`), preventing resource exhaustion from runaway scripts or credential abuse.

6. **Multi-instance deployment** -- Run multiple FTP instances (`ftp-1` on port 21, `ftp-2` on port 2121) and assign specific partners to dedicated instances for isolation and performance guarantees.

7. **Write-protected accounts** -- Create accounts with `"permissions": {"read": true, "write": false}` for partners who should only download files (e.g., report distribution). The `WritePermission` authority is only added when `write=true`.

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `FTP_PORT` | `21` | Port the FTP control channel listens on |
| `FTP_PASSIVE_PORTS` | `21000-21010` | Port range for passive mode data connections |
| `FTP_PUBLIC_HOST` | `127.0.0.1` | Public IP/hostname sent to clients for passive connections (must be reachable by clients) |
| `FTP_HOME_BASE` | `/data/ftp` | Base directory for user home directories |
| `FTP_INSTANCE_ID` | `null` | Instance identifier for multi-instance deployments (e.g., `ftp-1`) |
| `FTP_FTPS_ENABLED` | `false` | Enable FTPS (FTP over TLS) |
| `FTP_FTPS_KEYSTORE_PATH` | `./ftp-keystore.jks` | Path to the Java keystore containing the TLS certificate |
| `FTP_FTPS_KEYSTORE_PASSWORD` | `changeit` | Keystore password |
| `FTP_FTPS_PROTOCOL` | `TLSv1.2` | TLS protocol version |
| `FTP_FTPS_CLIENT_AUTH` | `false` | Require client TLS certificates (`true` = mutual TLS) |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC connection string |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ server hostname |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `RABBITMQ_USERNAME` | `guest` | RabbitMQ username |
| `RABBITMQ_PASSWORD` | `guest` | RabbitMQ password |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | JWT signing secret (must match Onboarding API) |
| `CONTROL_API_KEY` | `internal_control_secret` | Key for internal service-to-service API calls |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier |
| `CLUSTER_HOST` | `localhost` | Hostname of this service instance |
| `CLUSTER_COMM_MODE` | `WITHIN_CLUSTER` | Communication mode: `WITHIN_CLUSTER` or `CROSS_CLUSTER` |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label |
| `TRACK_ID_PREFIX` | `TRZ` | Prefix for transfer tracking IDs |
| `FLOW_MAX_CONCURRENT` | `50` | Maximum concurrent flow executions |
| `FLOW_WORK_DIR` | `/tmp/mft-flow-work` | Temporary directory for flow step processing |
| `PROXY_ENABLED` | `false` | Enable outbound proxy for cross-cluster forwarding |
| `PROXY_HOST` | `dmz-proxy` | Proxy hostname (when enabled) |
| `PROXY_PORT` | `8088` | Proxy port (when enabled) |

The admin HTTP server port is configured via `server.port` (Spring Boot standard), default `8082`.

---

## Cleanup

### Docker Compose

```bash
# Stop and remove containers
docker compose -f docker-compose-ftp-demo.yml down

# Also remove data volumes
docker compose -f docker-compose-ftp-demo.yml down -v
```

### Standalone Docker

```bash
docker stop mft-ftp-service && docker rm mft-ftp-service
docker volume rm ftp_data
```

### From Source

Press `Ctrl+C` in the terminal where the service is running.

```bash
# Remove generated files
rm -f ftp-keystore.jks
rm -rf /data/ftp
```

---

## Troubleshooting

### All Platforms

**"Connection refused" on port 21**

```bash
# Check if the FTP service is running
docker logs mft-ftp-service 2>&1 | tail -20

# Common causes:
# - Port 21 is already in use (check with: lsof -i :21 or netstat -ano | findstr :21)
# - PostgreSQL is not running
# - The container has not finished starting
```

**"Login failed" after entering correct password**

Verify the account exists with `protocol: FTP`:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/accounts/{id} | python3 -m json.tool
```

The account must have `"protocol": "FTP"` (not `"SFTP"` or `"FTP_WEB"`).

**"Passive mode refused" / "Connection timed out" on data commands**

Passive mode requires the passive port range (21000-21010) to be reachable by the client:

```bash
# Verify passive ports are exposed
docker port mft-ftp-service

# Verify FTP_PUBLIC_HOST is set correctly
# For Docker: use 127.0.0.1 (localhost) or host IP
# For remote access: set to the server's external IP
```

If using Docker Compose, ensure the port range is mapped: `"21000-21010:21000-21010"`.

**"User management must go through the Onboarding API"**

This error appears if you try to create users directly through the FTP protocol. The `FtpUserManager.save()` method intentionally rejects direct user creation -- all accounts must be created via the Onboarding API's `POST /api/accounts` endpoint.

### Linux

**"Permission denied" binding to port 21**

Port 21 is a privileged port (below 1024). When running from source:

```bash
# Option 1: Use a non-privileged port
java -jar ftp-service/target/*.jar --ftp.port=2121

# Option 2: Grant capability (avoids running as root)
sudo setcap 'cap_net_bind_service=+ep' $(which java)

# Option 3: Use sudo (not recommended for production)
sudo java -jar ftp-service/target/*.jar
```

**SELinux blocking FTP data connections**

```bash
# Check for denials
sudo ausearch -m avc -ts recent
# If SELinux is blocking, allow FTP passive connections
sudo setsebool -P ftpd_connect_all_unreserved 1
```

### macOS

**macOS built-in `ftp` removed in recent versions**

Apple removed the `ftp` command in macOS Ventura and later. Use `lftp` instead:

```bash
brew install lftp
lftp -u ftpuser1,FtpSecure88! localhost -p 21
```

**Passive mode fails with Docker Desktop**

Docker Desktop on macOS may not forward port ranges correctly. If passive mode fails:

```bash
# Use active mode in lftp
lftp -e "set ftp:passive-mode off" -u ftpuser1,FtpSecure88! localhost -p 21
```

Or run the FTP service directly from source instead of Docker.

### Windows

**Windows Defender Firewall blocks FTP ports**

```powershell
# Allow FTP control port
New-NetFirewallRule -DisplayName "TranzFer FTP Control" -Direction Inbound -LocalPort 21 -Protocol TCP -Action Allow

# Allow FTP passive port range
New-NetFirewallRule -DisplayName "TranzFer FTP Passive" -Direction Inbound -LocalPort 21000-21010 -Protocol TCP -Action Allow
```

**FileZilla shows "TLS connection not available"**

This means FTPS is not enabled on the server. Either:
1. Enable FTPS (see Demo 2) and reconnect with "Require explicit FTP over TLS"
2. Change FileZilla's encryption setting to "Only use plain FTP (insecure)"

**Windows built-in FTP client does not support passive mode**

The `ftp.exe` command in Windows does have a `passive` command, but it may not work correctly with all servers. Use FileZilla or WinSCP instead.

---

## What's Next

- **Enable FTPS in production** -- Generate a proper TLS certificate (from the [Keystore Manager](KEYSTORE-MANAGER.md)) and configure `FTP_FTPS_KEYSTORE_PATH` to point to it.
- **Set up the Gateway** -- Use the [Gateway Service](GATEWAY-SERVICE.md) as a single entry point that routes FTP users to different backend instances.
- **Configure file flows** -- Use the [Config Service](CONFIG-SERVICE.md) to create rules that process uploaded files (validate, encrypt, convert, screen) before routing.
- **Add encryption** -- Use the [Encryption Service](ENCRYPTION-SERVICE.md) to PGP-encrypt files in transit.
- **Monitor and audit** -- Use the [Analytics Service](ANALYTICS-SERVICE.md) to track FTP transfer volumes and the built-in audit logs for compliance.
- **Scale out** -- Add a second FTP instance (`ftp-2`) on port 2121 as shown in the full `docker-compose.yml` for high-availability deployments.

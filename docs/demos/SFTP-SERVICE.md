# SFTP Service -- Demo & Quick Start Guide

> Production-grade SFTP server built on Apache MINA SSHD with per-user home directories, password and SSH public-key authentication, automatic directory provisioning, and real-time file routing via RabbitMQ.

---

## What This Service Does

- **SFTP protocol server** on port 2222 (configurable) using Apache MINA SSHD, supporting both password and SSH public-key authentication.
- **Per-user isolated home directories** with auto-created `inbox/`, `outbox/`, `archive/`, and `sent/` subdirectories on first login (rooted filesystem -- users cannot escape their home directory).
- **Real-time upload/download detection** via `SftpRoutingEventListener` that intercepts file open/close events and delegates to the shared RoutingEngine for automated file processing and forwarding.
- **Credential caching with RabbitMQ-driven invalidation** -- account lookups are cached in-memory and evicted when the Onboarding API publishes account change events.
- **Multi-instance support** -- each SFTP server can be assigned an instance ID (e.g., `sftp-1`, `sftp-2`), and accounts can be pinned to specific instances or left unassigned for any-instance access.
- **Admin HTTP API** on a separate port (default 8081) with health check endpoint at `GET /internal/health` and internal file-receive endpoint at `POST /internal/files/receive`.

---

## What You Need (Prerequisites Checklist)

| Requirement | Why | Install Guide |
|-------------|-----|---------------|
| **Docker** OR **Java 21 + Maven** | Run or build the service | [PREREQUISITES.md -- Step 1](PREREQUISITES.md#step-1--choose-your-installation-method) |
| **PostgreSQL 16** | Stores transfer accounts, audit logs, flow definitions | [PREREQUISITES.md -- Step 2](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it) |
| **RabbitMQ 3.13** | Receives account-change events from Onboarding API | [PREREQUISITES.md -- Step 3](PREREQUISITES.md#step-3--install-rabbitmq-if-your-service-needs-it) |
| **Onboarding API** (port 8080) | Creates SFTP transfer accounts before you can connect | Included in Docker Compose below |
| **An SFTP client** | Connect to the server | See OS-specific options in Demos below |

**Port requirements:** 2222 (SFTP), 8081 (Admin HTTP), 5432 (PostgreSQL), 5672 + 15672 (RabbitMQ), 8080 (Onboarding API).

---

## Install & Start

### Method 1: Docker Compose (Recommended -- Starts Everything)

This is the fastest path. It starts PostgreSQL, RabbitMQ, the Onboarding API, and the SFTP service together.

Create a file named `docker-compose-sftp-demo.yml`:

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
      SFTP_HOME_BASE: /data/sftp
      CLUSTER_HOST: onboarding-api
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

  sftp-service:
    build: ./sftp-service
    container_name: mft-sftp-service
    ports:
      - "2222:2222"
      - "8081:8081"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      RABBITMQ_HOST: rabbitmq
      JWT_SECRET: change_me_in_production_256bit_secret_key!!
      CONTROL_API_KEY: internal_control_secret
      SFTP_PORT: 2222
      SFTP_HOME_BASE: /data/sftp
      SFTP_INSTANCE_ID: sftp-1
      CLUSTER_HOST: sftp-service
    volumes:
      - sftp_data:/data/sftp
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

volumes:
  sftp_data:
```

Start it from the repository root:

```bash
cd /path/to/file-transfer-platform
docker compose -f docker-compose-sftp-demo.yml up -d
```

Wait for services to become healthy (30-60 seconds):

```bash
docker compose -f docker-compose-sftp-demo.yml ps
```

Expected output:

```
NAME                 STATUS              PORTS
mft-postgres         running (healthy)   0.0.0.0:5432->5432/tcp
mft-rabbitmq         running (healthy)   0.0.0.0:5672->5672/tcp, 0.0.0.0:15672->15672/tcp
mft-onboarding-api   running (healthy)   0.0.0.0:8080->8080/tcp
mft-sftp-service     running (healthy)   0.0.0.0:2222->2222/tcp, 0.0.0.0:8081->8081/tcp
```

### Method 2: Docker (Standalone Container)

If you already have PostgreSQL and RabbitMQ running:

```bash
# Build the image
cd /path/to/file-transfer-platform
mvn clean package -DskipTests -pl sftp-service -am
docker build -t mft-sftp-service ./sftp-service

# Run (adjust DATABASE_URL and RABBITMQ_HOST for your environment)
docker run -d \
  --name mft-sftp-service \
  -p 2222:2222 \
  -p 8081:8081 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e RABBITMQ_HOST=host.docker.internal \
  -e SFTP_PORT=2222 \
  -e SFTP_HOME_BASE=/data/sftp \
  -e JWT_SECRET=change_me_in_production_256bit_secret_key!! \
  -e CONTROL_API_KEY=internal_control_secret \
  -v sftp_data:/data/sftp \
  mft-sftp-service
```

### Method 3: From Source

```bash
cd /path/to/file-transfer-platform

# Build (from repository root -- shared module is a dependency)
mvn clean package -DskipTests -pl sftp-service -am

# Run
java -jar sftp-service/target/*.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/filetransfer \
  --spring.datasource.username=postgres \
  --spring.datasource.password=postgres \
  --spring.rabbitmq.host=localhost \
  --sftp.port=2222 \
  --sftp.home-base=/data/sftp
```

---

## Verify It's Running

```bash
# Check the admin health endpoint
curl -s http://localhost:8081/internal/health | python3 -m json.tool
```

Expected output:

```json
{
    "status": "UP",
    "sftpServerRunning": true,
    "sftpPort": 2222,
    "instanceId": "sftp-1"
}
```

```bash
# Check that port 2222 is listening
# Linux / macOS
lsof -i :2222
# Windows (PowerShell)
Get-NetTCPConnection -LocalPort 2222
```

---

## Demo 1: Create an Account and Connect via SFTP

SFTP accounts are managed through the Onboarding API. You must create an account before connecting.

### Step 1 -- Register an admin user and get a JWT token

```bash
# Register
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

### Step 2 -- Create an SFTP transfer account

```bash
curl -s -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "protocol": "SFTP",
    "username": "partner1",
    "password": "SecurePass99!",
    "permissions": {"read": true, "write": true, "delete": false}
  }' | python3 -m json.tool
```

Expected output:

```json
{
    "id": "a1b2c3d4-...",
    "protocol": "SFTP",
    "username": "partner1",
    "homeDir": "/data/sftp/partner1",
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

### Step 3 -- Connect via SFTP

#### Linux / macOS -- command-line `sftp`

```bash
sftp -P 2222 partner1@localhost
```

When prompted, accept the host key fingerprint (first connection only):

```
The authenticity of host '[localhost]:2222' can't be established.
RSA key fingerprint is SHA256:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.
Are you sure you want to continue connecting (yes/no/[fingerprint])? yes
```

Enter the password when prompted:

```
partner1@localhost's password: SecurePass99!
```

You should see:

```
Connected to localhost.
sftp>
```

Explore the auto-created directory structure:

```
sftp> ls
archive   inbox     outbox    sent
sftp> pwd
Remote working directory: /
```

#### Windows -- WinSCP

1. Download WinSCP from https://winscp.net/eng/download.php
2. Open WinSCP and create a new site:
   - **File protocol:** SFTP
   - **Host name:** localhost
   - **Port number:** 2222
   - **User name:** partner1
   - **Password:** SecurePass99!
3. Click **Login** and accept the host key
4. You will see the four directories: `inbox/`, `outbox/`, `archive/`, `sent/`

#### Windows -- PuTTY PSFTP

```powershell
# Download psftp.exe from https://www.chiark.greenend.org.uk/~sgtatham/putty/latest.html
psftp -P 2222 partner1@localhost
```

Enter the password when prompted. The same `ls` and `put`/`get` commands work as on Linux.

### Step 4 -- Upload a file

```
sftp> cd inbox
sftp> put /etc/hostname test-upload.txt
Uploading /etc/hostname to /inbox/test-upload.txt
/etc/hostname                                 100%   12     0.0KB/s   00:00
sftp> ls
test-upload.txt
sftp> exit
```

On Windows with WinSCP, drag and drop a file into the `inbox` folder.

The SFTP service logs will show the upload detection:

```bash
docker logs mft-sftp-service 2>&1 | tail -5
```

```
INFO  SFTP filesystem ready for user=partner1 at /data/sftp/partner1
INFO  SFTP upload detected: user=partner1 relative=/inbox/test-upload.txt absolute=/data/sftp/partner1/inbox/test-upload.txt
```

---

## Demo 2: SSH Key Authentication

SSH public-key authentication is more secure than passwords and is standard in production MFT deployments.

### Step 1 -- Generate an SSH key pair

```bash
# Linux / macOS
ssh-keygen -t rsa -b 4096 -f ~/.ssh/mft_demo_key -N ""

# Windows (PowerShell)
ssh-keygen -t rsa -b 4096 -f $env:USERPROFILE\.ssh\mft_demo_key -N '""'
```

### Step 2 -- Create an account with the public key

```bash
# Read the public key
PUBKEY=$(cat ~/.ssh/mft_demo_key.pub)

# Create account with public key attached
curl -s -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{
    \"protocol\": \"SFTP\",
    \"username\": \"partner2\",
    \"password\": \"FallbackPass1!\",
    \"publicKey\": \"$PUBKEY\",
    \"permissions\": {\"read\": true, \"write\": true, \"delete\": true}
  }" | python3 -m json.tool
```

### Step 3 -- Connect using the SSH key (no password prompt)

```bash
sftp -P 2222 -i ~/.ssh/mft_demo_key partner2@localhost
```

Expected output (no password prompt):

```
Connected to localhost.
sftp> ls
archive   inbox     outbox    sent
```

#### How it works (from source code)

The `SftpPublicKeyAuthenticator` class retrieves the stored public key from the database (OpenSSH `authorized_keys` format), parses it using `AuthorizedKeyEntry.readAuthorizedKeys()`, resolves each entry to a `PublicKey` object, and compares it against the key presented by the client. If any stored key matches, authentication succeeds.

---

## Demo 3: Integration Pattern -- Python, Java, Node.js

### Python (paramiko)

```python
import paramiko

# Connect with password
transport = paramiko.Transport(("localhost", 2222))
transport.connect(username="partner1", password="SecurePass99!")
sftp = paramiko.SFTPClient.from_transport(transport)

# List root directory
print("Directories:", sftp.listdir("/"))
# Output: ['archive', 'inbox', 'outbox', 'sent']

# Upload a file
with open("/tmp/invoice.csv", "w") as f:
    f.write("id,amount,currency\n1,1500.00,USD\n")

sftp.put("/tmp/invoice.csv", "/inbox/invoice.csv")
print("Uploaded: /inbox/invoice.csv")

# Download a file
sftp.get("/inbox/invoice.csv", "/tmp/downloaded_invoice.csv")
print("Downloaded to /tmp/downloaded_invoice.csv")

# List inbox
print("Inbox files:", sftp.listdir("/inbox"))

sftp.close()
transport.close()
```

```bash
# Install and run
pip install paramiko
python sftp_demo.py
```

Expected output:

```
Directories: ['archive', 'inbox', 'outbox', 'sent']
Uploaded: /inbox/invoice.csv
Downloaded to /tmp/downloaded_invoice.csv
Inbox files: ['invoice.csv']
```

### Python (paramiko) -- SSH key authentication

```python
import paramiko

key = paramiko.RSAKey.from_private_key_file("/home/user/.ssh/mft_demo_key")
transport = paramiko.Transport(("localhost", 2222))
transport.connect(username="partner2", pkey=key)
sftp = paramiko.SFTPClient.from_transport(transport)

print("Connected with SSH key. Dirs:", sftp.listdir("/"))

sftp.close()
transport.close()
```

### Java (JSch)

```java
import com.jcraft.jsch.*;
import java.util.Vector;

public class SftpDemo {
    public static void main(String[] args) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession("partner1", "localhost", 2222);
        session.setPassword("SecurePass99!");
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(10000);

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(5000);

        // List root directory
        Vector<ChannelSftp.LsEntry> entries = channel.ls("/");
        for (ChannelSftp.LsEntry entry : entries) {
            System.out.println("  " + entry.getFilename());
        }
        // Output: archive, inbox, outbox, sent

        // Upload
        channel.put("/tmp/invoice.csv", "/inbox/invoice.csv");
        System.out.println("Uploaded: /inbox/invoice.csv");

        // Download
        channel.get("/inbox/invoice.csv", "/tmp/downloaded_invoice.csv");
        System.out.println("Downloaded to /tmp/downloaded_invoice.csv");

        channel.disconnect();
        session.disconnect();
    }
}
```

Add to `pom.xml`:

```xml
<dependency>
    <groupId>com.jcraft</groupId>
    <artifactId>jsch</artifactId>
    <version>0.1.55</version>
</dependency>
```

### Node.js (ssh2-sftp-client)

```javascript
const SftpClient = require('ssh2-sftp-client');
const fs = require('fs');

async function main() {
  const sftp = new SftpClient();

  await sftp.connect({
    host: 'localhost',
    port: 2222,
    username: 'partner1',
    password: 'SecurePass99!'
  });

  // List root directory
  const rootList = await sftp.list('/');
  console.log('Directories:', rootList.map(f => f.name));
  // Output: ['archive', 'inbox', 'outbox', 'sent']

  // Upload a file
  fs.writeFileSync('/tmp/invoice.csv', 'id,amount,currency\n1,1500.00,USD\n');
  await sftp.put('/tmp/invoice.csv', '/inbox/invoice.csv');
  console.log('Uploaded: /inbox/invoice.csv');

  // Download a file
  await sftp.get('/inbox/invoice.csv', '/tmp/downloaded_invoice.csv');
  console.log('Downloaded to /tmp/downloaded_invoice.csv');

  // List inbox
  const inboxList = await sftp.list('/inbox');
  console.log('Inbox files:', inboxList.map(f => f.name));

  await sftp.end();
}

main().catch(console.error);
```

```bash
# Install and run
npm install ssh2-sftp-client
node sftp_demo.js
```

Expected output:

```
Directories: [ 'archive', 'inbox', 'outbox', 'sent' ]
Uploaded: /inbox/invoice.csv
Downloaded to /tmp/downloaded_invoice.csv
Inbox files: [ 'invoice.csv' ]
```

---

## Demo 4: Auth Hardening -- Failed Attempt Lockout

This demo shows how the server locks an account after repeated failed login attempts and how to verify the lockout via the health endpoint.

### Step 1 -- Configure lockout (default: 5 failures, 15-minute lockout)

The defaults are already production-ready, but you can customize them in the Docker Compose environment:

```yaml
# In docker-compose-sftp-demo.yml, under sftp-service environment:
SFTP_AUTH_MAX_FAILED_ATTEMPTS: 5
SFTP_AUTH_LOCKOUT_DURATION_SECONDS: 900
```

Restart if you changed values:

```bash
docker compose -f docker-compose-sftp-demo.yml up -d sftp-service
```

### Step 2 -- Trigger lockout with bad passwords

```bash
# Attempt 1-5: use wrong password (each will fail with "Permission denied")
for i in 1 2 3 4 5; do
  echo "Attempt $i..."
  sshpass -p 'WrongPassword!' sftp -P 2222 -oBatchMode=no -oStrictHostKeyChecking=no partner1@localhost <<< "exit" 2>&1 || true
done
```

If you do not have `sshpass`, simply run `sftp -P 2222 partner1@localhost` five times and enter the wrong password each time.

### Step 3 -- Verify the account is locked

```bash
# The 6th attempt will be rejected even with the correct password
sftp -P 2222 partner1@localhost
# Output: Permission denied (account locked)
```

### Step 4 -- Check locked accounts via the health endpoint

```bash
curl -s http://localhost:8081/internal/health | python3 -m json.tool
```

Expected output includes the locked account:

```json
{
    "status": "UP",
    "sftpServerRunning": true,
    "sftpPort": 2222,
    "instanceId": "sftp-1",
    "activeConnections": 0,
    "lockedAccounts": ["partner1"],
    "authStats": {
        "totalLogins": 2,
        "failedLogins": 5,
        "lockoutsTriggered": 1
    }
}
```

### Step 5 -- Wait for lockout to expire

After 15 minutes (or the configured `SFTP_AUTH_LOCKOUT_DURATION_SECONDS`), the account is automatically unlocked:

```bash
# After 15 minutes, connect with the correct password
sftp -P 2222 partner1@localhost
# Enter: SecurePass99!
# Output: Connected to localhost.
```

---

## Demo 5: SSH Algorithm Configuration -- Restrict to Strong Ciphers

This demo shows how to restrict the SSH server to only strong ciphers for compliance (e.g., FIPS 140-2, PCI-DSS).

### Step 1 -- Set algorithm restrictions

Update the Docker Compose environment for the SFTP service:

```yaml
# In docker-compose-sftp-demo.yml, under sftp-service environment:
SFTP_CIPHERS: "aes256-ctr"
SFTP_MACS: "hmac-sha2-256,hmac-sha2-512"
SFTP_KEX: "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521"
```

Restart the service:

```bash
docker compose -f docker-compose-sftp-demo.yml up -d sftp-service
```

### Step 2 -- Connect with a matching cipher (should succeed)

```bash
sftp -P 2222 -oCiphers=aes256-ctr partner1@localhost
```

Expected output:

```
partner1@localhost's password:
Connected to localhost.
sftp>
```

### Step 3 -- Connect with a disallowed cipher (should fail)

```bash
sftp -P 2222 -oCiphers=aes128-cbc partner1@localhost
```

Expected output:

```
Unable to negotiate with 127.0.0.1 port 2222: no matching cipher found.
Their offer: aes256-ctr
```

### Step 4 -- Verify offered algorithms

```bash
ssh -vv -p 2222 partner1@localhost 2>&1 | grep "kex_input_kexinit"
```

The debug output will show only the configured algorithms being offered by the server.

### Step 5 -- Revert to defaults

Remove the `SFTP_CIPHERS`, `SFTP_MACS`, and `SFTP_KEX` variables (or set them to empty strings) and restart. The server will revert to Apache MINA SSHD defaults, which include a broad set of algorithms.

---

## Demo 6: Bandwidth Throttling

This demo shows how to limit upload speed per user to prevent bandwidth saturation.

### Step 1 -- Set a 1 MB/s upload limit

```yaml
# In docker-compose-sftp-demo.yml, under sftp-service environment:
SFTP_THROTTLE_UPLOAD_BPS: 1048576   # 1 MB/s = 1,048,576 bytes/sec
SFTP_THROTTLE_DOWNLOAD_BPS: 1048576
```

Restart:

```bash
docker compose -f docker-compose-sftp-demo.yml up -d sftp-service
```

### Step 2 -- Create a 10 MB test file

```bash
# Linux / macOS
dd if=/dev/urandom of=/tmp/test-10mb.bin bs=1M count=10

# Windows (PowerShell)
$bytes = New-Object byte[] 10485760
(New-Object Random).NextBytes($bytes)
[System.IO.File]::WriteAllBytes("$env:TEMP\test-10mb.bin", $bytes)
```

### Step 3 -- Upload and observe the throttling

```bash
sftp -P 2222 partner1@localhost
```

```
sftp> cd inbox
sftp> put /tmp/test-10mb.bin
Uploading /tmp/test-10mb.bin to /inbox/test-10mb.bin
/tmp/test-10mb.bin                           100%   10MB   1.0MB/s   00:10
```

Without throttling the upload would complete in under a second on a local connection. With the 1 MB/s limit, it takes approximately 10 seconds.

### Step 4 -- Compare with unlimited speed

Remove `SFTP_THROTTLE_UPLOAD_BPS` and restart. The same upload will complete nearly instantly:

```
/tmp/test-10mb.bin                           100%   10MB  50.0MB/s   00:00
```

---

## Demo 7: File Operation Controls -- Size Limits and Extension Filtering

This demo shows how to enforce maximum file size and restrict allowed file extensions.

### Step 1 -- Configure file controls

```yaml
# In docker-compose-sftp-demo.yml, under sftp-service environment:
SFTP_MAX_UPLOAD_SIZE_BYTES: 5242880       # 5 MB max
SFTP_ALLOWED_EXTENSIONS: ".csv,.xml,.txt,.json"
SFTP_PREVENT_SYMLINK_TRAVERSAL: "true"
```

Restart:

```bash
docker compose -f docker-compose-sftp-demo.yml up -d sftp-service
```

### Step 2 -- Upload a file within limits (should succeed)

```bash
# Create a small CSV file
echo "id,amount,currency" > /tmp/small-file.csv

sftp -P 2222 partner1@localhost
```

```
sftp> cd inbox
sftp> put /tmp/small-file.csv
Uploading /tmp/small-file.csv to /inbox/small-file.csv
/tmp/small-file.csv                          100%   20     0.0KB/s   00:00
```

### Step 3 -- Upload a file that exceeds the size limit (should be rejected)

```bash
# Create a 10 MB file
dd if=/dev/urandom of=/tmp/large-file.csv bs=1M count=10
```

```
sftp> put /tmp/large-file.csv
remote open("/inbox/large-file.csv"): Failure — file exceeds maximum upload size (5242880 bytes)
```

### Step 4 -- Upload a disallowed extension (should be rejected)

```bash
echo "#!/bin/bash" > /tmp/script.sh
```

```
sftp> put /tmp/script.sh
remote open("/inbox/script.sh"): Failure — file extension .sh is not allowed
```

### Step 5 -- Verify allowed extensions work

```
sftp> put /tmp/small-file.csv
Uploading /tmp/small-file.csv to /inbox/small-file.csv
/tmp/small-file.csv                          100%   20     0.0KB/s   00:00
sftp> exit
```

---

## Demo 8: Audit Log Viewing -- Structured JSON Events

The SFTP service emits structured JSON audit events for every significant action. These are written to the container's standard output and can be viewed with Docker logs.

### Step 1 -- Perform some actions

Connect, upload a file, download it, and disconnect:

```bash
sftp -P 2222 partner1@localhost <<EOF
cd inbox
put /tmp/small-file.csv
get /inbox/small-file.csv /tmp/downloaded.csv
exit
EOF
```

### Step 2 -- View the audit events in Docker logs

```bash
docker logs mft-sftp-service 2>&1 | grep '"event"' | python3 -m json.tool
```

Expected output (one JSON object per event):

```json
{
    "timestamp": "2026-04-05T14:20:00.123Z",
    "event": "LOGIN",
    "username": "partner1",
    "sourceIp": "172.18.0.1",
    "instanceId": "sftp-1"
}
```

```json
{
    "timestamp": "2026-04-05T14:20:01.456Z",
    "event": "UPLOAD",
    "username": "partner1",
    "sourceIp": "172.18.0.1",
    "path": "/inbox/small-file.csv",
    "sizeBytes": 20,
    "instanceId": "sftp-1"
}
```

```json
{
    "timestamp": "2026-04-05T14:20:02.789Z",
    "event": "DOWNLOAD",
    "username": "partner1",
    "sourceIp": "172.18.0.1",
    "path": "/inbox/small-file.csv",
    "sizeBytes": 20,
    "instanceId": "sftp-1"
}
```

```json
{
    "timestamp": "2026-04-05T14:20:03.012Z",
    "event": "DISCONNECT",
    "username": "partner1",
    "sourceIp": "172.18.0.1",
    "sessionDurationSeconds": 3,
    "instanceId": "sftp-1"
}
```

### Step 3 -- View failed login events

```bash
# Trigger a failed login
sshpass -p 'WrongPassword!' sftp -P 2222 -oStrictHostKeyChecking=no partner1@localhost <<< "exit" 2>&1 || true

# View the event
docker logs mft-sftp-service 2>&1 | grep 'LOGIN_FAILED' | tail -1 | python3 -m json.tool
```

```json
{
    "timestamp": "2026-04-05T14:21:00.345Z",
    "event": "LOGIN_FAILED",
    "username": "partner1",
    "sourceIp": "172.18.0.1",
    "reason": "invalid_password",
    "failedAttempts": 1,
    "instanceId": "sftp-1"
}
```

### Supported event types

| Event | Trigger |
|-------|---------|
| `LOGIN` | Successful authentication |
| `LOGIN_FAILED` | Failed authentication attempt |
| `UPLOAD` | File uploaded |
| `DOWNLOAD` | File downloaded |
| `DELETE` | File deleted |
| `MKDIR` | Directory created |
| `RENAME` | File or directory renamed/moved |
| `DISCONNECT` | Session ended |

---

## Use Cases

1. **Partner file exchange** -- Give each trading partner (supplier, bank, logistics provider) their own SFTP account with isolated `inbox`/`outbox` directories. Files uploaded to `inbox` are automatically routed to the correct recipient's `outbox` via flow rules.

2. **Automated batch processing** -- Partners upload daily batch files (invoices, payment files, EDI documents) to their `inbox`. The RoutingEngine detects the upload and triggers processing flows (validation, encryption, format conversion, sanctions screening) before delivery.

3. **Compliance and audit trail** -- Every login attempt (success or failure), every file upload, and every download is logged to the `audit_logs` table with username, IP address, timestamp, and action. This satisfies SOX, PCI-DSS, and HIPAA audit requirements.

4. **Multi-instance high availability** -- Run multiple SFTP service instances (`sftp-1`, `sftp-2`) behind a load balancer. Pin specific high-volume partners to dedicated instances via the `serverInstance` field while allowing smaller partners to connect to any instance.

5. **SSH key rotation** -- Update a partner's public key via the Onboarding API (`PATCH /api/accounts/{id}`). The RabbitMQ event invalidates the credential cache on all SFTP instances, and the next login uses the new key with zero downtime.

6. **DMZ deployment with proxy** -- Deploy the SFTP service behind the DMZ Proxy for internet-facing scenarios. The proxy handles rate limiting and threat detection while the SFTP service stays in the internal network.

7. **Cross-protocol routing** -- A file uploaded via SFTP can be automatically routed to an FTP user's outbox, an HTTP endpoint, or an external AS2 partner, all configured through file flows in the Config Service.

---

## Environment Variables

### Core

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_PORT` | `2222` | Port the SFTP protocol server listens on |
| `SFTP_HOST_KEY_PATH` | `./sftp_host_key` | Path to the SSH host key file (auto-generated if missing) |
| `SFTP_HOME_BASE` | `/data/sftp` | Base directory for user home directories (e.g., `/data/sftp/partner1/`) |
| `SFTP_INSTANCE_ID` | `null` | Instance identifier for multi-instance deployments (e.g., `sftp-1`) |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC connection string |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ server hostname |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `RABBITMQ_USERNAME` | `guest` | RabbitMQ username |
| `RABBITMQ_PASSWORD` | `guest` | RabbitMQ password |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | JWT signing secret (must match Onboarding API) |
| `CONTROL_API_KEY` | `internal_control_secret` | Key for internal service-to-service API calls (`X-Internal-Key` header) |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier for multi-cluster deployments |
| `CLUSTER_HOST` | `localhost` | Hostname of this service instance (used for cross-service communication) |
| `CLUSTER_COMM_MODE` | `WITHIN_CLUSTER` | Communication mode: `WITHIN_CLUSTER` or `CROSS_CLUSTER` |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label |
| `TRACK_ID_PREFIX` | `TRZ` | Prefix for generated transfer tracking IDs |
| `FLOW_MAX_CONCURRENT` | `50` | Maximum concurrent flow executions |
| `FLOW_WORK_DIR` | `/tmp/mft-flow-work` | Temporary directory for flow step processing |
| `PROXY_ENABLED` | `false` | Enable outbound proxy for cross-cluster file forwarding |
| `PROXY_HOST` | `dmz-proxy` | Proxy hostname (when enabled) |
| `PROXY_PORT` | `8088` | Proxy port (when enabled) |

### Connection Management

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_MAX_CONNECTIONS` | `0` (unlimited) | Maximum total concurrent SFTP connections |
| `SFTP_MAX_CONNECTIONS_PER_USER` | `0` (unlimited) | Maximum concurrent connections per username |
| `SFTP_IDLE_TIMEOUT_SECONDS` | `0` (no timeout) | Disconnect sessions idle longer than this many seconds |
| `SFTP_MAX_SESSION_DURATION_SECONDS` | `0` (unlimited) | Maximum session lifetime in seconds |
| `SFTP_BANNER_MESSAGE` | (empty) | Pre-authentication banner displayed to connecting clients |

### Auth Hardening

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_AUTH_MAX_FAILED_ATTEMPTS` | `5` | Failed login attempts before account lockout (0 = disable) |
| `SFTP_AUTH_LOCKOUT_DURATION_SECONDS` | `900` (15 min) | Duration of account lockout after reaching max failures |
| `SFTP_IP_ALLOWLIST` | (empty = disabled) | Comma-separated IPs/CIDRs allowed to connect |
| `SFTP_IP_DENYLIST` | (empty = disabled) | Comma-separated IPs/CIDRs blocked from connecting |

### File Operation Controls

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_MAX_UPLOAD_SIZE_BYTES` | `0` (unlimited) | Maximum single-file upload size in bytes |
| `SFTP_DISK_QUOTA_BYTES` | `0` (unlimited) | Maximum total disk usage per user home directory |
| `SFTP_ALLOWED_EXTENSIONS` | (empty = all allowed) | Comma-separated permitted file extensions (e.g., `.csv,.xml,.txt`) |
| `SFTP_DENIED_EXTENSIONS` | (empty = none denied) | Comma-separated blocked file extensions (e.g., `.exe,.bat,.sh`) |
| `SFTP_PREVENT_SYMLINK_TRAVERSAL` | `true` | Block symlinks that escape the user's home directory |

### SSH Algorithm Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_CIPHERS` | (empty = MINA defaults) | Comma-separated cipher names (e.g., `aes256-ctr,aes128-ctr`) |
| `SFTP_MACS` | (empty = MINA defaults) | Comma-separated MAC algorithm names (e.g., `hmac-sha2-256`) |
| `SFTP_KEX` | (empty = MINA defaults) | Comma-separated key exchange algorithm names |

### Bandwidth Throttling

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_THROTTLE_UPLOAD_BPS` | `0` (unlimited) | Per-user upload speed limit in bytes per second |
| `SFTP_THROTTLE_DOWNLOAD_BPS` | `0` (unlimited) | Per-user download speed limit in bytes per second |

### Graceful Shutdown

| Variable | Default | Description |
|----------|---------|-------------|
| `SFTP_SHUTDOWN_DRAIN_TIMEOUT_SECONDS` | `30` | Seconds to wait for active connections to drain before forced disconnect |

The admin HTTP server port is configured via `server.port` (Spring Boot standard), default `8081`.

---

## Cleanup

### Docker Compose

```bash
# Stop and remove containers
docker compose -f docker-compose-sftp-demo.yml down

# Also remove data volumes (deletes all uploaded files and database)
docker compose -f docker-compose-sftp-demo.yml down -v
```

### Standalone Docker

```bash
docker stop mft-sftp-service && docker rm mft-sftp-service
docker volume rm sftp_data   # optional: removes uploaded files
```

### From Source

Press `Ctrl+C` in the terminal where the service is running.

```bash
# Remove the host key and data directory
rm -f sftp_host_key
rm -rf /data/sftp    # caution: deletes all user files
```

---

## Troubleshooting

### All Platforms

**"Connection refused" on port 2222**

The SFTP server has not started yet or failed to start. Check the logs:

```bash
docker logs mft-sftp-service 2>&1 | tail -20
# or (from source)
# look at the console output for errors
```

Common causes:
- PostgreSQL is not running or not reachable (check `DATABASE_URL`)
- Port 2222 is already in use by another process
- Flyway migration failed (first-time database schema setup)

**"Permission denied" after entering correct password**

The account does not exist or has the wrong protocol type. Verify:

```bash
# Check that the account was created with protocol=SFTP
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/accounts/{id} | python3 -m json.tool
```

The account must have `"protocol": "SFTP"` and `"active": true`.

**"Host key verification failed"**

The host key changed (common after recreating the container). Remove the old key:

```bash
# Linux / macOS
ssh-keygen -R "[localhost]:2222"

# Windows (PowerShell)
ssh-keygen -R "[localhost]:2222"
# Or delete the line from %USERPROFILE%\.ssh\known_hosts manually
```

### Linux

**"Address already in use: 2222"**

```bash
# Find and kill the process using port 2222
sudo lsof -i :2222
sudo kill $(lsof -t -i:2222)
```

**"Permission denied" creating `/data/sftp`**

```bash
sudo mkdir -p /data/sftp
sudo chown -R $(whoami):$(whoami) /data/sftp
```

### macOS

**Port 2222 conflicts with Multipass or other SSH services**

```bash
# Check what is using 2222
lsof -i :2222
# Change the SFTP port: set SFTP_PORT=2223
```

**Docker Desktop needs file sharing access to `/data/sftp`**

If running from source with Docker for dependencies, ensure the data directory is within a Docker-shared path, or use a path under your home directory:

```bash
java -jar sftp-service/target/*.jar --sftp.home-base=$HOME/mft-data/sftp
```

### Windows

**Port 2222 blocked by Windows Firewall**

```powershell
# Allow inbound connections on port 2222
New-NetFirewallRule -DisplayName "TranzFer SFTP" -Direction Inbound -LocalPort 2222 -Protocol TCP -Action Allow
```

**WinSCP cannot connect -- "Network error: Connection refused"**

Ensure Docker Desktop is running and the container is healthy:

```powershell
docker ps --filter "name=mft-sftp-service"
```

If using WSL 2, the service may be accessible at the WSL IP rather than `localhost`. Check with:

```powershell
wsl hostname -I
```

**Line ending differences in SSH public keys**

Windows may add `\r\n` line endings. Ensure the public key is a single line with no trailing carriage returns when passing it to the API.

---

## What's Next

- **Configure file flows** -- Use the [Config Service](CONFIG-SERVICE.md) to create routing rules that automatically process files uploaded to `inbox` and deliver them to the recipient's `outbox`.
- **Set up the Gateway** -- Deploy the [Gateway Service](GATEWAY-SERVICE.md) to expose a single SFTP entry point that routes users to different backend SFTP instances.
- **Add encryption** -- Use the [Encryption Service](ENCRYPTION-SERVICE.md) as a flow step to PGP-encrypt or AES-encrypt files before delivery.
- **Monitor transfers** -- Use the [Analytics Service](ANALYTICS-SERVICE.md) to track transfer volumes, latencies, and success rates across all SFTP accounts.
- **Scale horizontally** -- Add a second SFTP instance (`sftp-2`) as shown in the full `docker-compose.yml`, pin high-volume partners to dedicated instances, and load-balance the rest.

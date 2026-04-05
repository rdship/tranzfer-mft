# Gateway Service — Demo & Quick Start Guide

> Protocol-aware gateway that routes SFTP and FTP connections to the correct backend server based on user identity — known users go to their assigned instance, unknown users go to a legacy server, and unrecognized users are rejected.

---

## What This Service Does

- **Single entry point for all SFTP and FTP connections** — clients connect to gateway ports (SFTP on 2220, FTP on 2122) and are transparently proxied to the correct backend server. They never need to know which server they are actually hitting.
- **User-based routing with four-tier logic** — (1) known user with a server assignment goes to that specific instance, (2) known user without assignment goes to the default internal service, (3) unknown user goes to a configured legacy server, (4) no legacy server configured means connection is rejected.
- **SFTP gateway with full filesystem proxy** — the gateway authenticates against the backend via Apache MINA SSHD, then exposes the backend's SFTP filesystem to the client as if it were local. File operations (read, write, mkdir, ls) pass through transparently.
- **FTP gateway with Netty TCP bridge** — intercepts the FTP `USER` command to determine routing, connects to the backend, replays the command, and bridges all subsequent bytes bidirectionally. The client sees a normal FTP session.
- **Admin API for status and routing inspection** — HTTP endpoints on port 8085 expose gateway health, port configuration, and configured legacy server mappings.

---

## What You Need (Prerequisites Checklist)

- [ ] **Operating System:** Linux, macOS, or Windows
- [ ] **Docker** (recommended) OR **Java 21 + Maven** — [Install guide](PREREQUISITES.md)
- [ ] **PostgreSQL 16** — [Install guide](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it)
- [ ] **RabbitMQ 3.13** — [Install guide](PREREQUISITES.md#step-3--install-rabbitmq-if-your-service-needs-it)
- [ ] **curl** — pre-installed on Linux/macOS; Windows: `winget install cURL.cURL`
- [ ] **An SFTP client** — `sftp` (pre-installed on Linux/macOS), or PuTTY/WinSCP on Windows
- [ ] **Ports available:** `5432` (PostgreSQL), `5672` (RabbitMQ), `8085` (Admin HTTP), `2220` (SFTP gateway), `2122` (FTP gateway)

---

## Install & Start

### Step 0: Start PostgreSQL and RabbitMQ (Required)

If you do not already have PostgreSQL and RabbitMQ running, start them now. Every method below needs both.

```bash
# PostgreSQL
docker run -d \
  --name mft-postgres \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine

# RabbitMQ
docker run -d \
  --name mft-rabbitmq \
  -e RABBITMQ_DEFAULT_USER=guest \
  -e RABBITMQ_DEFAULT_PASS=guest \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3.13-management-alpine
```

Verify both are accepting connections:

```bash
docker exec mft-postgres pg_isready -U postgres
docker exec mft-rabbitmq rabbitmq-diagnostics ping
```

Expected output:

```
/var/run/postgresql:5432 - accepting connections
Ping succeeded
```

---

### Method 1: Docker (Any OS)

**Build the image** (from the repository root):

```bash
cd file-transfer-platform

# Build the shared library and gateway JAR (Docker COPY expects it in target/)
mvn clean package -DskipTests -pl gateway-service -am

# Build the Docker image
docker build -t mft-gateway-service ./gateway-service
```

**Run the container:**

```bash
docker run -d \
  --name mft-gateway-service \
  -p 8085:8085 \
  -p 2220:2220 \
  -p 2122:2122 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e RABBITMQ_HOST=host.docker.internal \
  -e RABBITMQ_PORT=5672 \
  -e GATEWAY_SFTP_PORT=2220 \
  -e GATEWAY_FTP_PORT=2122 \
  -e INTERNAL_SFTP_HOST=host.docker.internal \
  -e INTERNAL_SFTP_PORT=2222 \
  -e INTERNAL_FTP_HOST=host.docker.internal \
  -e INTERNAL_FTP_PORT=21 \
  mft-gateway-service
```

> **Linux note:** Replace `host.docker.internal` with `172.17.0.1` (the Docker bridge IP) or use `--network host` instead of the `-p` port mappings.

---

### Method 2: Docker Compose (with All Dependencies)

This is the recommended way to demo the gateway, because it includes backend SFTP and FTP servers to route to. Save this as `docker-compose-gateway.yml` in the repository root:

```yaml
version: '3.9'
services:
  postgres:
    image: postgres:16-alpine
    container_name: mft-gw-postgres
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
    container_name: mft-gw-rabbitmq
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

  # Backend SFTP server (what the gateway routes SFTP users to)
  sftp-service:
    build: ./sftp-service
    container_name: mft-gw-sftp
    ports:
      - "22222:2222"
      - "8081:8081"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
      SERVER_PORT: 8081
      SFTP_PORT: 2222
      SFTP_HOME_BASE: /data/sftp
      SFTP_INSTANCE_ID: sftp-1
      CLUSTER_HOST: sftp-service
      JWT_SECRET: change_me_in_production_256bit_secret_key!!
      CONTROL_API_KEY: internal_control_secret
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

  # Backend FTP server (what the gateway routes FTP users to)
  ftp-service:
    build: ./ftp-service
    container_name: mft-gw-ftp
    ports:
      - "21:21"
      - "21000-21010:21000-21010"
      - "8082:8082"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
      SERVER_PORT: 8082
      FTP_PORT: 21
      FTP_PASSIVE_PORTS: 21000-21010
      FTP_PUBLIC_HOST: 127.0.0.1
      FTP_HOME_BASE: /data/ftp
      FTP_INSTANCE_ID: ftp-1
      CLUSTER_HOST: ftp-service
      JWT_SECRET: change_me_in_production_256bit_secret_key!!
      CONTROL_API_KEY: internal_control_secret
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

  # The Gateway itself
  gateway-service:
    build: ./gateway-service
    container_name: mft-gw-gateway
    ports:
      - "2220:2220"
      - "2122:2122"
      - "8085:8085"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
      SERVER_PORT: 8085
      GATEWAY_SFTP_PORT: 2220
      GATEWAY_FTP_PORT: 2122
      INTERNAL_SFTP_HOST: sftp-service
      INTERNAL_SFTP_PORT: 2222
      INTERNAL_FTP_HOST: ftp-service
      INTERNAL_FTP_PORT: 21
      INTERNAL_FTPWEB_HOST: ftp-web-service
      INTERNAL_FTPWEB_PORT: 8083
      CLUSTER_HOST: gateway-service
      JWT_SECRET: change_me_in_production_256bit_secret_key!!
      CONTROL_API_KEY: internal_control_secret
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
```

**Start everything:**

```bash
docker compose -f docker-compose-gateway.yml up -d --build
```

**Watch startup progress:**

```bash
docker compose -f docker-compose-gateway.yml ps
```

Expected output (after 30-60 seconds):

```
NAME               SERVICE            STATUS
mft-gw-postgres    postgres           running (healthy)
mft-gw-rabbitmq    rabbitmq           running (healthy)
mft-gw-sftp        sftp-service       running
mft-gw-ftp         ftp-service        running
mft-gw-gateway     gateway-service    running
```

---

### Method 3: From Source

```bash
cd file-transfer-platform

# Build everything needed
mvn clean package -DskipTests -pl gateway-service -am

# Run (PostgreSQL and RabbitMQ must already be running on localhost)
java -jar gateway-service/target/*.jar \
  --server.port=8085 \
  --gateway.sftp.port=2220 \
  --gateway.ftp.port=2122
```

Expected startup log (last few lines):

```
INFO  SFTP gateway started on port 2220
INFO  FTP gateway started on port 2122
INFO  Started GatewayServiceApplication in X.XX seconds
```

---

## Verify It's Running

### Check the Admin Status Endpoint

```bash
curl -s http://localhost:8085/internal/gateway/status | python3 -m json.tool
```

Expected output:

```json
{
    "sftpGatewayPort": 2220,
    "ftpGatewayPort": 2122,
    "sftpGatewayRunning": true
}
```

### Check Legacy Server Configuration

```bash
curl -s http://localhost:8085/internal/gateway/legacy-servers | python3 -m json.tool
```

Expected output (empty list if no legacy servers are configured yet):

```json
[]
```

To filter by protocol:

```bash
curl -s "http://localhost:8085/internal/gateway/legacy-servers?protocol=SFTP" | python3 -m json.tool
```

### Check the SFTP Gateway Port Is Listening

```bash
# Linux / macOS
nc -zv localhost 2220

# Windows (PowerShell)
Test-NetConnection -ComputerName localhost -Port 2220
```

Expected output (Linux/macOS):

```
Connection to localhost port 2220 [tcp/*] succeeded!
```

### Check the FTP Gateway Port Is Listening

```bash
# Linux / macOS — raw TCP to see the FTP greeting
echo "" | nc localhost 2122
```

Expected output:

```
220 File Transfer Gateway Ready
```

---

## Demo 1: SFTP Connection Through the Gateway

This demo shows a user connecting through the gateway's SFTP port (2220) and being transparently routed to a backend SFTP server. You need a user account in the database first.

**Prerequisites:** Use the Docker Compose setup (Method 2) so backend servers are available, and create a test user via the onboarding API or directly in the database.

### Step 1: Create a Test Transfer Account

If the onboarding API is running (port 8080), create an account through it. Otherwise, insert directly into the database:

```bash
docker exec mft-gw-postgres psql -U postgres -d filetransfer -c "
INSERT INTO transfer_accounts (username, protocol, password_hash, home_dir, active)
VALUES ('demouser', 'SFTP', '\$2a\$10\$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012', '/home/demouser', true)
ON CONFLICT (username) DO NOTHING;
"
```

> **Note:** The actual password hash depends on how the backend SFTP service validates credentials. If using the onboarding API, create the user through `POST /api/accounts` instead.

### Step 2: Connect Through the Gateway

```bash
sftp -P 2220 demouser@localhost
```

What happens behind the scenes:

1. Your SFTP client connects to the gateway on port 2220
2. The gateway's `SftpGatewayConfig` authenticator fires
3. `UserRoutingService.routeSftp("demouser")` is called
4. The routing service looks up `demouser` in the `transfer_accounts` table
5. If found with a `server_instance` assignment, traffic goes to that instance
6. If found without assignment, traffic goes to the default SFTP host (`sftp-service:2222`)
7. If not found, the gateway checks for a configured legacy SFTP server
8. The gateway authenticates against the backend on behalf of the user
9. A proxy SFTP filesystem is created over the backend session
10. All subsequent file operations go through transparently

**Expected gateway log output:**

```
INFO  SFTP gateway: routing demouser -> default (sftp-service:2222)
INFO  SFTP gateway: demouser authenticated via sftp-service:2222 (legacy=false)
```

### Step 3: Perform File Operations Through the Gateway

Once connected (you will see the `sftp>` prompt), the session behaves exactly like a direct SFTP connection:

```
sftp> ls
sftp> mkdir test-dir
sftp> cd test-dir
sftp> put /etc/hostname
sftp> ls
hostname
sftp> get hostname /tmp/hostname-downloaded
sftp> exit
```

---

## Demo 2: Routing Decision Tree — Watch All Four Paths

This demo walks through the four possible routing outcomes by manipulating database state. It is the best way to understand the gateway's core value.

### Path 1: Known User with Server Assignment

```bash
# Assign demouser to a specific SFTP instance
docker exec mft-gw-postgres psql -U postgres -d filetransfer -c "
UPDATE transfer_accounts SET server_instance = 'sftp-1' WHERE username = 'demouser';
"
```

Connect through the gateway:

```bash
sftp -P 2220 demouser@localhost
```

Expected log:

```
INFO  SFTP gateway: routing demouser -> instance sftp-1 (sftp-service:2222)
```

### Path 2: Known User Without Assignment (Default Routing)

```bash
# Remove the server assignment
docker exec mft-gw-postgres psql -U postgres -d filetransfer -c "
UPDATE transfer_accounts SET server_instance = NULL WHERE username = 'demouser';
"
```

Connect again:

```bash
sftp -P 2220 demouser@localhost
```

Expected log:

```
INFO  SFTP gateway: routing demouser -> default (sftp-service:2222)
```

### Path 3: Unknown User Routed to Legacy Server

```bash
# Configure a legacy SFTP server in the database
docker exec mft-gw-postgres psql -U postgres -d filetransfer -c "
INSERT INTO legacy_server_configs (name, protocol, host, port, active)
VALUES ('Old SFTP Server', 'SFTP', 'legacy-sftp.example.com', 22, true)
ON CONFLICT DO NOTHING;
"
```

Connect with an unknown username:

```bash
sftp -P 2220 unknownuser@localhost
```

Expected log:

```
INFO  SFTP gateway: routing unknown user unknownuser -> legacy (legacy-sftp.example.com:22)
```

The connection will fail at the backend authentication step (since legacy-sftp.example.com does not exist in this demo), but the routing decision is logged.

### Path 4: Unknown User, No Legacy Server — Rejected

```bash
# Remove all legacy SFTP servers
docker exec mft-gw-postgres psql -U postgres -d filetransfer -c "
DELETE FROM legacy_server_configs WHERE protocol = 'SFTP';
"
```

Connect with an unknown username:

```bash
sftp -P 2220 nobodyuser@localhost
```

Expected log:

```
WARN  SFTP gateway: no route for user nobodyuser, rejecting
```

Expected client output:

```
nobodyuser@localhost: Permission denied (password).
```

---

## Demo 3: Integration Patterns — Python, Java, Node.js

### Python (paramiko)

```python
import paramiko

# Connect through the gateway — not directly to the backend
transport = paramiko.Transport(("localhost", 2220))
transport.connect(username="demouser", password="your-password")
sftp = paramiko.SFTPClient.from_transport(transport)

# List files (the gateway proxies this to the routed backend)
files = sftp.listdir("/home/demouser")
print("Files:", files)

# Upload a file through the gateway
sftp.put("local-report.csv", "/home/demouser/report.csv")
print("Upload complete")

# Download a file through the gateway
sftp.get("/home/demouser/report.csv", "downloaded-report.csv")
print("Download complete")

sftp.close()
transport.close()
```

Install paramiko: `pip install paramiko`

### Java (JSch)

```java
import com.jcraft.jsch.*;

public class GatewayDemo {
    public static void main(String[] args) throws Exception {
        JSch jsch = new JSch();

        // Connect through the gateway port, not the backend port
        Session session = jsch.getSession("demouser", "localhost", 2220);
        session.setPassword("your-password");
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(10000);

        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect();

        // List files — transparently routed to the correct backend
        java.util.Vector<?> files = sftp.ls("/home/demouser");
        for (Object entry : files) {
            System.out.println(((ChannelSftp.LsEntry) entry).getFilename());
        }

        // Upload through the gateway
        sftp.put("local-report.csv", "/home/demouser/report.csv");
        System.out.println("Upload complete");

        sftp.disconnect();
        session.disconnect();
    }
}
```

Add to your `pom.xml`:

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

async function demo() {
    const sftp = new SftpClient();

    // Connect through the gateway
    await sftp.connect({
        host: 'localhost',
        port: 2220,  // Gateway port — NOT the backend port
        username: 'demouser',
        password: 'your-password'
    });

    // List files — gateway routes to the correct backend
    const files = await sftp.list('/home/demouser');
    console.log('Files:', files.map(f => f.name));

    // Upload through the gateway
    await sftp.put(Buffer.from('Hello from Node.js\n'), '/home/demouser/hello.txt');
    console.log('Upload complete');

    // Download through the gateway
    const content = await sftp.get('/home/demouser/hello.txt');
    console.log('Downloaded:', content.toString());

    await sftp.end();
}

demo().catch(console.error);
```

Install: `npm install ssh2-sftp-client`

---

## Use Cases

1. **Centralized entry point for multi-server deployments** — organizations running multiple SFTP/FTP instances behind a single IP/port. External partners connect to one address and are routed internally.

2. **Zero-downtime server migrations** — move users from a legacy server to a new server one at a time by updating their `server_instance` assignment. No client-side changes needed.

3. **Legacy system integration** — new users land on the modern platform while existing users continue to be routed to the old server until they are migrated.

4. **Compliance-driven user segregation** — route users handling sensitive data to dedicated server instances with stricter auditing and encryption, while general users share a standard pool.

5. **Multi-tenant isolation** — each tenant's transfer accounts can be assigned to a dedicated server instance, ensuring physical separation of file storage.

6. **Load distribution** — spread users across multiple backend servers by assigning groups of accounts to different instances.

7. **Protocol translation entry point** — clients connect via SFTP on port 2220, but the gateway could route to FTP-Web (HTTP) backends for internal processing, enabling protocol flexibility.

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC connection URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ hostname |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `SERVER_PORT` | `8085` | Admin HTTP API port |
| `GATEWAY_SFTP_PORT` | `2220` | SFTP gateway listen port |
| `GATEWAY_FTP_PORT` | `2121` | FTP gateway listen port (set to `2122` in docker-compose) |
| `GATEWAY_HOST_KEY_PATH` | `./gateway_host_key` | Path to SSH host key (auto-generated if missing) |
| `INTERNAL_SFTP_HOST` | `sftp-service` | Default backend SFTP server hostname |
| `INTERNAL_SFTP_PORT` | `2222` | Default backend SFTP server port |
| `INTERNAL_FTP_HOST` | `ftp-service` | Default backend FTP server hostname |
| `INTERNAL_FTP_PORT` | `21` | Default backend FTP server port |
| `INTERNAL_FTPWEB_HOST` | `ftp-web-service` | Default backend FTP-Web server hostname |
| `INTERNAL_FTPWEB_PORT` | `8083` | Default backend FTP-Web server port |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | JWT signing secret (shared across services) |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key for service-to-service calls |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier for multi-cluster deployments |
| `CLUSTER_HOST` | `localhost` | This service's hostname within the cluster |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label (PROD, STAGING, DEV) |
| `PROXY_ENABLED` | `false` | Enable outbound proxy for backend connections |
| `PROXY_TYPE` | `HTTP` | Proxy type (HTTP, SOCKS5) |
| `PROXY_HOST` | `dmz-proxy` | Proxy hostname |
| `PROXY_PORT` | `8088` | Proxy port |

---

## Cleanup

### Docker Compose

```bash
docker compose -f docker-compose-gateway.yml down -v
```

### Individual Docker Containers

```bash
docker stop mft-gateway-service && docker rm mft-gateway-service
# Also stop dependencies if you started them separately:
docker stop mft-postgres mft-rabbitmq && docker rm mft-postgres mft-rabbitmq
```

### From Source

Press `Ctrl+C` in the terminal where the service is running.

---

## Troubleshooting

### "Connection refused" on port 2220

**All OS:** The gateway takes 10-20 seconds to start the SFTP listener after the Spring Boot application starts. Wait and retry.

```bash
# Check if the gateway process is running
docker logs mft-gateway-service 2>&1 | grep "SFTP gateway started"
# Expected: INFO  SFTP gateway started on port 2220
```

**Linux:** If using `--network host`, ensure no other process is already using port 2220:

```bash
ss -tlnp | grep 2220
```

**macOS:** Docker Desktop sometimes delays port forwarding. Restart Docker Desktop if the port is not reachable after 30 seconds.

**Windows:** Ensure Windows Firewall allows inbound connections on port 2220:

```powershell
New-NetFirewallRule -DisplayName "TranzFer SFTP Gateway" -Direction Inbound -LocalPort 2220 -Protocol TCP -Action Allow
```

### "Host key verification failed" when connecting via SFTP

The gateway generates a new SSH host key on first startup. If you have connected before and the key changed:

```bash
# Linux / macOS
ssh-keygen -R "[localhost]:2220"

# Windows (PowerShell)
ssh-keygen -R "[localhost]:2220"
# Or delete the line from %USERPROFILE%\.ssh\known_hosts manually
```

### "Permission denied" after successful routing

This means the gateway routed the connection correctly, but the backend server rejected the credentials. Check:

```bash
# Verify the user exists in the database
docker exec mft-gw-postgres psql -U postgres -d filetransfer -c "
SELECT username, protocol, active, server_instance FROM transfer_accounts WHERE username = 'demouser';
"
```

If the user exists but authentication fails, the password hash in the database may not match what you are entering. Create the user through the onboarding API (`POST /api/accounts`) instead of direct SQL inserts.

### FTP gateway shows "421 Backend connection failed"

The gateway resolved the routing but cannot reach the backend FTP server. Check:

```bash
# Is the FTP backend running?
docker logs mft-gw-ftp 2>&1 | tail -5

# Can the gateway container reach the FTP service?
docker exec mft-gw-gateway nc -zv ftp-service 21
```

### Gateway starts but no routes work (empty routing)

The `transfer_accounts` and `legacy_server_configs` tables may be empty. The gateway does not create test data on its own. Populate them via the onboarding API or direct SQL inserts (see Demo 2 above).

### PostgreSQL: "relation does not exist"

Flyway migrations have not run. Check the gateway logs for migration errors:

```bash
docker logs mft-gw-gateway 2>&1 | grep -i flyway
```

The gateway shares migration scripts from the `shared` module. All tables (including `transfer_accounts`, `server_instances`, and `legacy_server_configs`) are created by Flyway on first startup.

---

## What's Next

- **[SFTP Service](SFTP-SERVICE.md)** — understand the backend server that the gateway routes to
- **[FTP Service](FTP-SERVICE.md)** — the FTP backend for gateway FTP routing
- **[Config Service](CONFIG-SERVICE.md)** — manage server instances, legacy server configs, and routing rules through an API
- **[Onboarding API](ONBOARDING-API.md)** — create transfer accounts with server assignments
- **[External Forwarder](EXTERNAL-FORWARDER.md)** — once files arrive via the gateway, forward them to external partners

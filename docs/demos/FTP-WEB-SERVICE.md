# FTP-Web Service -- Demo & Quick Start Guide

> HTTP-based file transfer service providing REST endpoints for upload, download, list, delete, mkdir, and rename operations, secured with JWT authentication, with per-user isolated home directories and real-time file routing via RabbitMQ. Supports file uploads up to 512 MB.

---

## What This Service Does

- **Six HTTP file operation endpoints** under `/api/files/` -- `list`, `upload` (multipart), `download`, `delete`, `mkdir`, and `rename` -- all scoped to the authenticated user's home directory.
- **JWT-authenticated** -- every request to `/api/files/*` requires a `Bearer` token in the `Authorization` header. The `JwtAuthFilter` extracts the user's email and role from the token and sets the Spring Security context. Internal endpoints (`/internal/*`) are unauthenticated.
- **Per-user home directory isolation** -- each user's operations are restricted to their assigned home directory (e.g., `/data/ftpweb/user1/`). The `FileOperationService.resolveAndValidate()` method normalizes paths and prevents path traversal attacks.
- **Real-time upload/download routing** -- the `FileOperationService` calls `routingEngine.onFileUploaded()` after each upload and `routingEngine.onFileDownloaded()` on each download, enabling automated file processing flows.
- **512 MB upload limit** -- configured via Spring's `multipart.max-file-size` and `multipart.max-request-size` in `application.yml`.
- **CORS enabled** -- allows requests from `http://localhost:3000`, `http://localhost:3001`, and `http://localhost:3002` (the Admin UI, FTP-Web UI, and Partner Portal).
- **Admin HTTP API** with health check at `GET /internal/health` (returns service instance ID, cluster ID, and service type) and internal file-receive at `POST /internal/files/receive`.

---

## What You Need (Prerequisites Checklist)

| Requirement | Why | Install Guide |
|-------------|-----|---------------|
| **Docker** OR **Java 21 + Maven** | Run or build the service | [PREREQUISITES.md -- Step 1](PREREQUISITES.md#step-1--choose-your-installation-method) |
| **PostgreSQL 16** | Stores transfer accounts, audit logs, flow definitions | [PREREQUISITES.md -- Step 2](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it) |
| **RabbitMQ 3.13** | Receives account-change events from Onboarding API | [PREREQUISITES.md -- Step 3](PREREQUISITES.md#step-3--install-rabbitmq-if-your-service-needs-it) |
| **Onboarding API** (port 8080) | Creates FTP_WEB transfer accounts and issues JWT tokens | Included in Docker Compose below |
| **curl or any HTTP client** | Call the REST endpoints | Pre-installed on Linux/macOS; available on Windows |

**Port requirements:** 8083 (FTP-Web HTTP), 5432 (PostgreSQL), 5672 + 15672 (RabbitMQ), 8080 (Onboarding API).

---

## Install & Start

### Method 1: Docker Compose (Recommended -- Starts Everything)

Create a file named `docker-compose-ftpweb-demo.yml`:

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
      FTPWEB_HOME_BASE: /data/ftpweb
      CLUSTER_HOST: onboarding-api
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

  ftp-web-service:
    build: ./ftp-web-service
    container_name: mft-ftp-web-service
    ports:
      - "8083:8083"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      RABBITMQ_HOST: rabbitmq
      JWT_SECRET: change_me_in_production_256bit_secret_key!!
      CONTROL_API_KEY: internal_control_secret
      FTPWEB_HOME_BASE: /data/ftpweb
      FTPWEB_INSTANCE_ID: ftpweb-1
      CLUSTER_HOST: ftp-web-service
    volumes:
      - ftpweb_data:/data/ftpweb
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

volumes:
  ftpweb_data:
```

Start it from the repository root:

```bash
cd /path/to/file-transfer-platform
docker compose -f docker-compose-ftpweb-demo.yml up -d
```

Wait for services to become healthy:

```bash
docker compose -f docker-compose-ftpweb-demo.yml ps
```

Expected output:

```
NAME                  STATUS              PORTS
mft-postgres          running (healthy)   0.0.0.0:5432->5432/tcp
mft-rabbitmq          running (healthy)   0.0.0.0:5672->5672/tcp, 0.0.0.0:15672->15672/tcp
mft-onboarding-api    running (healthy)   0.0.0.0:8080->8080/tcp
mft-ftp-web-service   running (healthy)   0.0.0.0:8083->8083/tcp
```

### Method 2: Docker (Standalone Container)

If you already have PostgreSQL and RabbitMQ running:

```bash
# Build the image
cd /path/to/file-transfer-platform
mvn clean package -DskipTests -pl ftp-web-service -am
docker build -t mft-ftp-web-service ./ftp-web-service

# Run
docker run -d \
  --name mft-ftp-web-service \
  -p 8083:8083 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e RABBITMQ_HOST=host.docker.internal \
  -e FTPWEB_HOME_BASE=/data/ftpweb \
  -e JWT_SECRET=change_me_in_production_256bit_secret_key!! \
  -e CONTROL_API_KEY=internal_control_secret \
  -v ftpweb_data:/data/ftpweb \
  mft-ftp-web-service
```

### Method 3: From Source

```bash
cd /path/to/file-transfer-platform

# Build (from repository root)
mvn clean package -DskipTests -pl ftp-web-service -am

# Run
java -jar ftp-web-service/target/*.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/filetransfer \
  --spring.datasource.username=postgres \
  --spring.datasource.password=postgres \
  --spring.rabbitmq.host=localhost \
  --ftpweb.home-base=/data/ftpweb
```

---

## Verify It's Running

```bash
# Health check (unauthenticated -- /internal/* is public)
curl -s http://localhost:8083/internal/health | python3 -m json.tool
```

Expected output:

```json
{
    "status": "UP",
    "serviceInstanceId": "...",
    "clusterId": "default-cluster",
    "serviceType": "FTP_WEB",
    "instanceId": "ftpweb-1"
}
```

```bash
# Confirm that /api/files/* requires authentication
curl -s -o /dev/null -w "%{http_code}" http://localhost:8083/api/files/list
```

Expected output: `403` (Forbidden -- no JWT token provided).

---

## Demo 1: Complete JWT Auth Flow and File Operations

The FTP-Web service uses JWT tokens issued by the Onboarding API. Every API call to `/api/files/*` requires a valid `Authorization: Bearer <token>` header.

### Step 1 -- Register an admin user and get a JWT token

```bash
# Register a user (or login if already registered)
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

Save the token (used for both Onboarding API calls AND FTP-Web API calls):

```bash
TOKEN="eyJhbGciOiJIUzI1NiJ9..."   # paste the actual accessToken value here
```

### Step 2 -- Create an FTP_WEB transfer account

```bash
curl -s -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "protocol": "FTP_WEB",
    "username": "webuser1",
    "password": "WebPass99!",
    "permissions": {"read": true, "write": true, "delete": true}
  }' | python3 -m json.tool
```

Expected output:

```json
{
    "id": "c3d4e5f6-...",
    "protocol": "FTP_WEB",
    "username": "webuser1",
    "homeDir": "/data/ftpweb/webuser1",
    "permissions": {
        "read": true,
        "write": true,
        "delete": true
    },
    "active": true,
    "serverInstance": null,
    "createdAt": "2026-04-05T10:00:00Z",
    "connectionInstructions": "..."
}
```

### Step 3 -- List the root directory (empty at first)

```bash
curl -s http://localhost:8083/api/files/list?path=/ \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

Expected output:

```json
[]
```

### Step 4 -- Create directories

```bash
# Create an inbox directory
curl -s -X POST "http://localhost:8083/api/files/mkdir?path=/inbox" \
  -H "Authorization: Bearer $TOKEN" \
  -w "\nHTTP Status: %{http_code}\n"
```

Expected output:

```
HTTP Status: 201
```

```bash
# Create a reports directory
curl -s -X POST "http://localhost:8083/api/files/mkdir?path=/reports" \
  -H "Authorization: Bearer $TOKEN" \
  -w "\nHTTP Status: %{http_code}\n"
```

```bash
# Create a nested directory
curl -s -X POST "http://localhost:8083/api/files/mkdir?path=/reports/2026/Q1" \
  -H "Authorization: Bearer $TOKEN" \
  -w "\nHTTP Status: %{http_code}\n"
```

```bash
# List root again -- now shows directories
curl -s http://localhost:8083/api/files/list?path=/ \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

Expected output:

```json
[
    {
        "name": "inbox",
        "path": "/inbox",
        "directory": true,
        "size": 4096,
        "lastModified": "2026-04-05T10:01:00Z"
    },
    {
        "name": "reports",
        "path": "/reports",
        "directory": true,
        "size": 4096,
        "lastModified": "2026-04-05T10:01:00Z"
    }
]
```

### Step 5 -- Upload a file

```bash
# Create a sample file
echo "id,customer,amount,currency
1,Acme Corp,15000.00,USD
2,GlobalTech,8500.50,EUR
3,FreshFoods,22000.00,GBP" > /tmp/invoices.csv

# Upload to /inbox
curl -s -X POST "http://localhost:8083/api/files/upload?path=/inbox" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/invoices.csv" \
  -w "\nHTTP Status: %{http_code}\n"
```

Expected output:

```
HTTP Status: 201
```

```bash
# List the inbox directory
curl -s http://localhost:8083/api/files/list?path=/inbox \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

Expected output:

```json
[
    {
        "name": "invoices.csv",
        "path": "/invoices.csv",
        "directory": false,
        "size": 117,
        "lastModified": "2026-04-05T10:02:00Z"
    }
]
```

### Step 6 -- Download a file

```bash
curl -s http://localhost:8083/api/files/download?path=/inbox/invoices.csv \
  -H "Authorization: Bearer $TOKEN" \
  -o /tmp/downloaded_invoices.csv

cat /tmp/downloaded_invoices.csv
```

Expected output:

```
id,customer,amount,currency
1,Acme Corp,15000.00,USD
2,GlobalTech,8500.50,EUR
3,FreshFoods,22000.00,GBP
```

The response includes a `Content-Disposition: attachment; filename="invoices.csv"` header.

### Step 7 -- Rename/move a file

```bash
curl -s -X POST "http://localhost:8083/api/files/rename?from=/inbox/invoices.csv&to=/reports/2026/Q1/invoices.csv" \
  -H "Authorization: Bearer $TOKEN" \
  -w "\nHTTP Status: %{http_code}\n"
```

Expected output:

```
HTTP Status: 200
```

```bash
# Verify the file moved
curl -s http://localhost:8083/api/files/list?path=/reports/2026/Q1 \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

Expected output:

```json
[
    {
        "name": "invoices.csv",
        "path": "/invoices.csv",
        "directory": false,
        "size": 117,
        "lastModified": "2026-04-05T10:02:00Z"
    }
]
```

### Step 8 -- Delete a file

```bash
curl -s -X DELETE "http://localhost:8083/api/files/delete?path=/reports/2026/Q1/invoices.csv" \
  -H "Authorization: Bearer $TOKEN" \
  -w "\nHTTP Status: %{http_code}\n"
```

Expected output:

```
HTTP Status: 204
```

```bash
# Delete an entire directory tree (recursive)
curl -s -X DELETE "http://localhost:8083/api/files/delete?path=/reports" \
  -H "Authorization: Bearer $TOKEN" \
  -w "\nHTTP Status: %{http_code}\n"
```

Expected output:

```
HTTP Status: 204
```

The `delete` endpoint handles both files and directories. Directory deletion is recursive -- it walks the file tree and deletes all contents.

---

## Demo 2: Real-World Scenario -- Bulk Upload and Organize

This demo simulates a partner uploading multiple files, organizing them, and downloading a report.

```bash
# Create directory structure
for dir in inbox outbox archive processed; do
  curl -s -X POST "http://localhost:8083/api/files/mkdir?path=/$dir" \
    -H "Authorization: Bearer $TOKEN"
done

# Upload multiple files
for i in 1 2 3 4 5; do
  echo "batch_id,item,qty,price
B${i}001,Widget-A,100,12.50
B${i}002,Widget-B,250,8.75
B${i}003,Gadget-X,50,45.00" > /tmp/batch_${i}.csv

  curl -s -X POST "http://localhost:8083/api/files/upload?path=/inbox" \
    -H "Authorization: Bearer $TOKEN" \
    -F "file=@/tmp/batch_${i}.csv" \
    -w "Uploaded batch_${i}.csv: HTTP %{http_code}\n"
done

# List all uploaded files
echo ""
echo "=== Inbox contents ==="
curl -s http://localhost:8083/api/files/list?path=/inbox \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Move processed files to the archive
for i in 1 2 3; do
  curl -s -X POST "http://localhost:8083/api/files/rename?from=/inbox/batch_${i}.csv&to=/archive/batch_${i}.csv" \
    -H "Authorization: Bearer $TOKEN"
done

echo ""
echo "=== Inbox after archiving ==="
curl -s http://localhost:8083/api/files/list?path=/inbox \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

echo ""
echo "=== Archive contents ==="
curl -s http://localhost:8083/api/files/list?path=/archive \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

Expected output:

```
Uploaded batch_1.csv: HTTP 201
Uploaded batch_2.csv: HTTP 201
Uploaded batch_3.csv: HTTP 201
Uploaded batch_4.csv: HTTP 201
Uploaded batch_5.csv: HTTP 201

=== Inbox contents ===
[
    { "name": "batch_1.csv", "directory": false, "size": 98, ... },
    { "name": "batch_2.csv", "directory": false, "size": 98, ... },
    { "name": "batch_3.csv", "directory": false, "size": 98, ... },
    { "name": "batch_4.csv", "directory": false, "size": 98, ... },
    { "name": "batch_5.csv", "directory": false, "size": 98, ... }
]

=== Inbox after archiving ===
[
    { "name": "batch_4.csv", "directory": false, "size": 98, ... },
    { "name": "batch_5.csv", "directory": false, "size": 98, ... }
]

=== Archive contents ===
[
    { "name": "batch_1.csv", "directory": false, "size": 98, ... },
    { "name": "batch_2.csv", "directory": false, "size": 98, ... },
    { "name": "batch_3.csv", "directory": false, "size": 98, ... }
]
```

---

## Demo 3: Integration Pattern -- Python, Java, Node.js

### Python (requests)

```python
import requests
import os

BASE_URL = "http://localhost:8083/api/files"
ONBOARDING_URL = "http://localhost:8080"

# Step 1: Get a JWT token
auth_resp = requests.post(f"{ONBOARDING_URL}/api/auth/login", json={
    "email": "admin@demo.local",
    "password": "DemoPass123!"
})
token = auth_resp.json()["accessToken"]
headers = {"Authorization": f"Bearer {token}"}

print(f"Token obtained (expires in {auth_resp.json()['expiresIn']}ms)")

# Step 2: Create a directory
resp = requests.post(f"{BASE_URL}/mkdir", params={"path": "/data-drop"}, headers=headers)
print(f"mkdir /data-drop: {resp.status_code}")

# Step 3: Upload a file
with open("/tmp/report.csv", "w") as f:
    f.write("date,metric,value\n2026-04-01,revenue,125000\n2026-04-02,revenue,131000\n")

with open("/tmp/report.csv", "rb") as f:
    resp = requests.post(
        f"{BASE_URL}/upload",
        params={"path": "/data-drop"},
        headers=headers,
        files={"file": ("report.csv", f, "text/csv")}
    )
print(f"upload report.csv: {resp.status_code}")

# Step 4: List files
resp = requests.get(f"{BASE_URL}/list", params={"path": "/data-drop"}, headers=headers)
files = resp.json()
for entry in files:
    kind = "DIR " if entry["directory"] else "FILE"
    print(f"  {kind}  {entry['name']}  ({entry['size']} bytes)")

# Step 5: Download a file
resp = requests.get(
    f"{BASE_URL}/download",
    params={"path": "/data-drop/report.csv"},
    headers=headers
)
print(f"Downloaded content:\n{resp.text}")

# Step 6: Rename
resp = requests.post(
    f"{BASE_URL}/rename",
    params={"from": "/data-drop/report.csv", "to": "/data-drop/report-final.csv"},
    headers=headers
)
print(f"rename: {resp.status_code}")

# Step 7: Delete
resp = requests.delete(
    f"{BASE_URL}/delete",
    params={"path": "/data-drop/report-final.csv"},
    headers=headers
)
print(f"delete: {resp.status_code}")

# Step 8: Clean up directory
resp = requests.delete(f"{BASE_URL}/delete", params={"path": "/data-drop"}, headers=headers)
print(f"delete /data-drop: {resp.status_code}")
```

```bash
pip install requests
python ftpweb_demo.py
```

Expected output:

```
Token obtained (expires in 900000ms)
mkdir /data-drop: 201
upload report.csv: 201
  FILE  report.csv  (68 bytes)
Downloaded content:
date,metric,value
2026-04-01,revenue,125000
2026-04-02,revenue,131000

rename: 200
delete: 204
delete /data-drop: 204
```

### Java (HttpClient -- JDK 11+)

```java
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.*;

public class FtpWebDemo {
    static final String BASE = "http://localhost:8083/api/files";
    static final String ONBOARDING = "http://localhost:8080";

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // Step 1: Login and get token
        HttpRequest loginReq = HttpRequest.newBuilder()
            .uri(URI.create(ONBOARDING + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(
                "{\"email\":\"admin@demo.local\",\"password\":\"DemoPass123!\"}"))
            .build();
        String loginBody = client.send(loginReq,
            HttpResponse.BodyHandlers.ofString()).body();
        // Simple token extraction (use a JSON library in production)
        String token = loginBody.split("\"accessToken\":\"")[1].split("\"")[0];
        System.out.println("Token: " + token.substring(0, 20) + "...");

        // Step 2: mkdir
        HttpRequest mkdirReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/mkdir?path=/java-demo"))
            .header("Authorization", "Bearer " + token)
            .POST(BodyPublishers.noBody())
            .build();
        int mkdirStatus = client.send(mkdirReq,
            HttpResponse.BodyHandlers.ofString()).statusCode();
        System.out.println("mkdir: " + mkdirStatus);

        // Step 3: Upload (multipart)
        String boundary = "----JavaBoundary" + System.currentTimeMillis();
        String csvContent = "id,name,value\n1,alpha,100\n2,beta,200\n";
        String multipartBody =
            "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"data.csv\"\r\n" +
            "Content-Type: text/csv\r\n\r\n" +
            csvContent + "\r\n" +
            "--" + boundary + "--\r\n";

        HttpRequest uploadReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/upload?path=/java-demo"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(BodyPublishers.ofString(multipartBody))
            .build();
        int uploadStatus = client.send(uploadReq,
            HttpResponse.BodyHandlers.ofString()).statusCode();
        System.out.println("upload: " + uploadStatus);

        // Step 4: List
        HttpRequest listReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/list?path=/java-demo"))
            .header("Authorization", "Bearer " + token)
            .GET().build();
        String listBody = client.send(listReq,
            HttpResponse.BodyHandlers.ofString()).body();
        System.out.println("list: " + listBody);

        // Step 5: Download
        HttpRequest downloadReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/download?path=/java-demo/data.csv"))
            .header("Authorization", "Bearer " + token)
            .GET().build();
        String downloadBody = client.send(downloadReq,
            HttpResponse.BodyHandlers.ofString()).body();
        System.out.println("download:\n" + downloadBody);

        // Step 6: Cleanup
        HttpRequest deleteReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/delete?path=/java-demo"))
            .header("Authorization", "Bearer " + token)
            .DELETE().build();
        int deleteStatus = client.send(deleteReq,
            HttpResponse.BodyHandlers.ofString()).statusCode();
        System.out.println("delete: " + deleteStatus);
    }
}
```

### Node.js (axios + form-data)

```javascript
const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

const BASE_URL = 'http://localhost:8083/api/files';
const ONBOARDING_URL = 'http://localhost:8080';

async function main() {
  // Step 1: Login
  const authResp = await axios.post(`${ONBOARDING_URL}/api/auth/login`, {
    email: 'admin@demo.local',
    password: 'DemoPass123!'
  });
  const token = authResp.data.accessToken;
  const headers = { Authorization: `Bearer ${token}` };
  console.log('Token obtained');

  // Step 2: Create directory
  await axios.post(`${BASE_URL}/mkdir`, null, {
    params: { path: '/node-demo' },
    headers
  });
  console.log('mkdir /node-demo: 201');

  // Step 3: Upload a file
  fs.writeFileSync('/tmp/orders.csv',
    'order_id,product,qty\nORD001,Laptop,5\nORD002,Monitor,10\n');

  const form = new FormData();
  form.append('file', fs.createReadStream('/tmp/orders.csv'));

  await axios.post(`${BASE_URL}/upload`, form, {
    params: { path: '/node-demo' },
    headers: { ...headers, ...form.getHeaders() }
  });
  console.log('upload orders.csv: 201');

  // Step 4: List files
  const listResp = await axios.get(`${BASE_URL}/list`, {
    params: { path: '/node-demo' },
    headers
  });
  console.log('Files:', listResp.data.map(f =>
    `${f.name} (${f.size} bytes, dir=${f.directory})`));

  // Step 5: Download
  const dlResp = await axios.get(`${BASE_URL}/download`, {
    params: { path: '/node-demo/orders.csv' },
    headers,
    responseType: 'text'
  });
  console.log('Downloaded:\n' + dlResp.data);

  // Step 6: Rename
  await axios.post(`${BASE_URL}/rename`, null, {
    params: { from: '/node-demo/orders.csv', to: '/node-demo/orders-processed.csv' },
    headers
  });
  console.log('renamed to orders-processed.csv');

  // Step 7: Delete directory (recursive)
  await axios.delete(`${BASE_URL}/delete`, {
    params: { path: '/node-demo' },
    headers
  });
  console.log('deleted /node-demo');
}

main().catch(err =>
  console.error('Error:', err.response?.status, err.response?.data || err.message));
```

```bash
npm install axios form-data
node ftpweb_demo.js
```

Expected output:

```
Token obtained
mkdir /node-demo: 201
upload orders.csv: 201
Files: [ 'orders.csv (56 bytes, dir=false)' ]
Downloaded:
order_id,product,qty
ORD001,Laptop,5
ORD002,Monitor,10

renamed to orders-processed.csv
deleted /node-demo
```

---

## API Endpoint Reference

All endpoints require `Authorization: Bearer <jwt_token>` except `/internal/*`.

| Method | Path | Params | Description | Response |
|--------|------|--------|-------------|----------|
| `GET` | `/api/files/list` | `path` (default: `/`) | List directory contents | `200` JSON array of `FileEntry` |
| `POST` | `/api/files/upload` | `path` (default: `/inbox`), `file` (multipart) | Upload a file | `201` Created |
| `GET` | `/api/files/download` | `path` (required) | Download a file | `200` binary stream with `Content-Disposition` |
| `DELETE` | `/api/files/delete` | `path` (required) | Delete file or directory (recursive) | `204` No Content |
| `POST` | `/api/files/mkdir` | `path` (required) | Create directory (creates parents) | `201` Created |
| `POST` | `/api/files/rename` | `from` (required), `to` (required) | Rename or move a file/directory | `200` OK |
| `GET` | `/internal/health` | -- | Health check (unauthenticated) | `200` JSON status |
| `POST` | `/internal/files/receive` | `X-Internal-Key` header, JSON body | Receive forwarded file (service-to-service) | `204` No Content |

**FileEntry schema:**

```json
{
    "name": "invoices.csv",
    "path": "/invoices.csv",
    "directory": false,
    "size": 1024,
    "lastModified": "2026-04-05T10:00:00Z"
}
```

---

## Use Cases

1. **Web-based file browser** -- The FTP-Web UI (port 3001) uses these exact endpoints to provide a browser-based file manager. Partners who cannot install SFTP/FTP clients can upload and download files through their web browser.

2. **REST API integration** -- Applications that need to send or receive files can call the HTTP endpoints directly, without needing an SFTP/FTP library. This is simpler for web applications, serverless functions, and CI/CD pipelines.

3. **Mobile app file transfer** -- Mobile applications can use standard HTTP libraries to upload and download files, avoiding the complexity of SFTP libraries on iOS/Android.

4. **Automated file processing pipelines** -- Combine with CI/CD: a Jenkins/GitHub Actions job uploads build artifacts to `/inbox`, the RoutingEngine detects the upload, triggers validation and encryption flows, then routes the file to the partner's outbox.

5. **Partner portal self-service** -- Partners log into the Partner Portal (port 3002), which uses the FTP-Web API to let them browse their files, upload new deliveries, and download received files -- all through a web interface.

6. **Large file uploads** -- The 512 MB upload limit (configurable via `spring.servlet.multipart.max-file-size`) supports large batch files, reports, and data extracts common in MFT scenarios.

7. **Microservice file exchange** -- Other microservices in the platform (or external systems) can programmatically move files between users by calling the rename endpoint or uploading to specific paths.

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `FTPWEB_HOME_BASE` | `/data/ftpweb` | Base directory for user home directories |
| `FTPWEB_INSTANCE_ID` | `null` | Instance identifier for multi-instance deployments |
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

Spring Boot standard properties also apply:

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8083` | HTTP server port |
| `spring.servlet.multipart.max-file-size` | `512MB` | Maximum upload file size |
| `spring.servlet.multipart.max-request-size` | `512MB` | Maximum request size |
| `cors.allowed-origins` | `http://localhost:3000,http://localhost:3001,http://localhost:3002` | CORS allowed origins |

---

## Cleanup

### Docker Compose

```bash
# Stop and remove containers
docker compose -f docker-compose-ftpweb-demo.yml down

# Also remove data volumes
docker compose -f docker-compose-ftpweb-demo.yml down -v
```

### Standalone Docker

```bash
docker stop mft-ftp-web-service && docker rm mft-ftp-web-service
docker volume rm ftpweb_data
```

### From Source

Press `Ctrl+C` in the terminal where the service is running.

```bash
rm -rf /data/ftpweb
```

---

## Troubleshooting

### All Platforms

**`403 Forbidden` on all `/api/files/*` requests**

The JWT token is missing, expired, or signed with a different secret.

```bash
# Verify the token works against the Onboarding API first
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/accounts | python3 -m json.tool

# If the token is expired (default: 15 minutes / 900000ms), get a new one:
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "admin@demo.local", "password": "DemoPass123!"}' | python3 -m json.tool
```

Ensure the `JWT_SECRET` environment variable is identical on both the Onboarding API and the FTP-Web service.

**`404 Not Found` or `FTP_WEB account not found`**

The authenticated user (email from the JWT) does not have an FTP_WEB transfer account. Create one:

```bash
curl -s -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"protocol": "FTP_WEB", "username": "webuser1", "password": "WebPass99!"}'
```

The protocol must be exactly `FTP_WEB` (not `FTP` or `SFTP`).

**`413 Payload Too Large` on upload**

The file exceeds the 512 MB limit. Either:
- Split the file into smaller chunks
- Increase the limit: set `spring.servlet.multipart.max-file-size=1024MB` and `spring.servlet.multipart.max-request-size=1024MB`

**Path traversal blocked -- `SecurityException: Path traversal attempt detected`**

The service prevents any path that would resolve outside the user's home directory. Paths like `../../etc/passwd` or `/inbox/../../../etc/passwd` are blocked by `resolveAndValidate()` which normalizes the path and checks that it starts with the user's home directory.

**Upload succeeds but file not found in listing**

Check that the `path` parameter on upload matches the directory you are listing. The upload path is the directory, not the full file path. The filename comes from the multipart form's original filename.

### Linux

**`Permission denied` writing to `/data/ftpweb`**

```bash
sudo mkdir -p /data/ftpweb
sudo chown -R $(whoami):$(whoami) /data/ftpweb
```

### macOS

**`curl: (7) Failed to connect to localhost port 8083`**

Ensure Docker Desktop is running and the container is healthy:

```bash
docker ps --filter "name=mft-ftp-web-service"
```

If running from source, check for port conflicts:

```bash
lsof -i :8083
```

### Windows

**`curl` not available**

Windows 10/11 includes curl, but older versions may not. Alternatives:

```powershell
# PowerShell native (Invoke-RestMethod)
$headers = @{ "Authorization" = "Bearer $TOKEN" }
Invoke-RestMethod -Uri "http://localhost:8083/api/files/list?path=/" -Headers $headers

# Upload with PowerShell
$form = @{
    file = Get-Item -Path "C:\temp\invoices.csv"
}
Invoke-RestMethod -Uri "http://localhost:8083/api/files/upload?path=/inbox" `
    -Method Post -Headers $headers -Form $form
```

**CORS errors in browser**

If calling the API from a web app running on a different port, ensure the origin is in the `cors.allowed-origins` list. Add your origin:

```bash
# Set via environment variable
cors.allowed-origins=http://localhost:3000,http://localhost:3001,http://localhost:3002,http://localhost:4200
```

**File names with spaces or special characters**

URL-encode the path parameter:

```bash
# Upload to a path with spaces
curl -X POST "http://localhost:8083/api/files/mkdir?path=/My%20Documents" \
  -H "Authorization: Bearer $TOKEN"
```

---

## What's Next

- **Deploy the FTP-Web UI** -- The [FTP-Web UI](../../ftp-web-ui/) (port 3001) provides a browser-based file manager that uses these exact API endpoints. Start it with `docker compose up ftp-web-ui`.
- **Configure file flows** -- Use the [Config Service](CONFIG-SERVICE.md) to create routing rules that automatically process files uploaded through the web interface.
- **Add encryption** -- Use the [Encryption Service](ENCRYPTION-SERVICE.md) as a flow step to encrypt files before delivery.
- **Set up the Partner Portal** -- The [Partner Portal](../../partner-portal/) (port 3002) gives partners a self-service web interface for file operations.
- **Scale horizontally** -- Add a second FTP-Web instance (`ftpweb-2` on port 8098) as shown in the full `docker-compose.yml` for load balancing.
- **Connect to SFTP/FTP** -- Files uploaded via the web interface can be routed to SFTP or FTP users' outboxes, bridging web-based and protocol-based file transfer.

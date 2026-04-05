# Storage Manager -- Demo & Quick Start Guide

> Tiered storage engine with GPFS-style parallel I/O, SHA-256 deduplication, and AI-powered lifecycle management across HOT/WARM/COLD/BACKUP tiers.

---

## What This Service Does

- **4-tier storage** -- Files land in HOT (fast SSD), age into WARM, then COLD, with automatic BACKUP snapshots. Each tier has independent paths and size limits.
- **SHA-256 deduplication** -- Every file is checksummed on write. If an identical file already exists, the duplicate is discarded and the existing record's access count is incremented. Zero wasted storage.
- **Parallel I/O engine** -- Large files are striped across multiple threads (configurable stripe size and thread count). Small files use a single direct write. Both paths compute SHA-256 inline.
- **AI-powered lifecycle** -- Access patterns determine tiering decisions. Frequently accessed files stay HOT. Files with predictable partner schedules are pre-staged back from WARM to HOT before they are needed.
- **Incremental backup** -- Every 6 hours, files with `backupStatus=NONE` or `PENDING` are copied to the backup tier with SHA-256 integrity verification.

---

## What You Need (Prerequisites Checklist)

Before starting, complete these items from [PREREQUISITES.md](PREREQUISITES.md):

- [ ] **Docker** installed and running ([Step 1, Option A](PREREQUISITES.md#option-a-docker-recommended-for-all-os))
      OR **Java 21 + Maven** installed ([Step 1, Option B](PREREQUISITES.md#option-b-java-21--maven-build-from-source))
- [ ] **PostgreSQL 16** running on port 5432 with database `filetransfer` ([Step 2](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it))
- [ ] **curl** installed (ships with macOS/Linux; Windows: `winget install cURL.cURL`)
- [ ] **Port 8094** is free (`lsof -i :8094` on Linux/macOS, `netstat -ano | findstr :8094` on Windows)
- [ ] At least **1 GB free disk space** for the four storage tiers

---

## Install & Start

### Method 1: Docker (Any OS)

```bash
# 1. Start PostgreSQL (skip if already running)
docker run -d \
  --name mft-postgres \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine

# Wait for PostgreSQL to be ready
docker exec mft-postgres pg_isready -U postgres

# 2. Build the Storage Manager image
cd file-transfer-platform
docker build -t mft-storage-manager ./storage-manager

# 3. Run with custom tier paths mapped to local directories
docker run -d \
  --name mft-storage-manager \
  -p 8094:8094 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e STORAGE_HOT_PATH=/data/storage/hot \
  -e STORAGE_WARM_PATH=/data/storage/warm \
  -e STORAGE_COLD_PATH=/data/storage/cold \
  -e STORAGE_BACKUP_PATH=/data/storage/backup \
  -v storage_data:/data/storage \
  mft-storage-manager
```

**Linux note:** Replace `host.docker.internal` with `172.17.0.1` (the Docker bridge IP) or use `--network host`.

### Method 2: Docker Compose (with PostgreSQL)

Create a file called `docker-compose-storage.yml`:

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
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5

  storage-manager:
    build: ./storage-manager
    container_name: mft-storage-manager
    ports:
      - "8094:8094"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      STORAGE_HOT_PATH: /data/storage/hot
      STORAGE_WARM_PATH: /data/storage/warm
      STORAGE_COLD_PATH: /data/storage/cold
      STORAGE_BACKUP_PATH: /data/storage/backup
      STORAGE_IO_THREADS: 8
      STORAGE_STRIPE_SIZE_KB: 4096
      STORAGE_WRITE_BUFFER_MB: 64
    volumes:
      - storage_data:/data/storage
    depends_on:
      postgres:
        condition: service_healthy

volumes:
  postgres_data:
  storage_data:
```

```bash
# Start both services
docker compose -f docker-compose-storage.yml up -d

# Check status
docker compose -f docker-compose-storage.yml ps
```

### Method 3: From Source

```bash
# 1. Start PostgreSQL (Docker method -- skip if you have native PostgreSQL)
docker run -d \
  --name mft-postgres \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine

# 2. Create local storage directories
mkdir -p /tmp/mft-storage/{hot,warm,cold,backup}

# 3. Build the service (from the repository root)
cd file-transfer-platform
mvn clean package -DskipTests -pl storage-manager -am

# 4. Run with environment variables pointing to local directories
STORAGE_HOT_PATH=/tmp/mft-storage/hot \
STORAGE_WARM_PATH=/tmp/mft-storage/warm \
STORAGE_COLD_PATH=/tmp/mft-storage/cold \
STORAGE_BACKUP_PATH=/tmp/mft-storage/backup \
java -jar storage-manager/target/storage-manager-*.jar
```

**Windows (PowerShell):**

```powershell
# Create local storage directories
New-Item -ItemType Directory -Force -Path C:\mft-storage\hot, C:\mft-storage\warm, C:\mft-storage\cold, C:\mft-storage\backup

# Set environment variables and run
$env:STORAGE_HOT_PATH = "C:\mft-storage\hot"
$env:STORAGE_WARM_PATH = "C:\mft-storage\warm"
$env:STORAGE_COLD_PATH = "C:\mft-storage\cold"
$env:STORAGE_BACKUP_PATH = "C:\mft-storage\backup"
java -jar storage-manager\target\storage-manager-*.jar
```

---

## Verify It's Running

```bash
curl -s http://localhost:8094/api/v1/storage/health | python3 -m json.tool
```

Expected output:

```json
{
    "hotCount": 0,
    "hotSizeGb": 0,
    "warmCount": 0,
    "warmSizeGb": 0,
    "coldCount": 0,
    "coldSizeGb": 0,
    "totalObjects": 0,
    "recentActions": 0,
    "status": "UP",
    "service": "storage-manager",
    "features": [
        "parallel-io",
        "tiered-storage",
        "deduplication",
        "incremental-backup",
        "ai-lifecycle",
        "predictive-prestage"
    ]
}
```

---

## Demo 1: Store and Retrieve a File

This demo stores a business document, retrieves it by track ID, and inspects storage metadata.

### Step 1: Create a sample file

```bash
# Create a realistic business document
echo "INVOICE-2026-04-001
Date: 2026-04-05
Vendor: Meridian Supply Co.
PO Number: PO-88471
Amount: $14,250.00
Terms: Net 30
Ship To: 742 Evergreen Terrace, Springfield" > /tmp/invoice-2026-04-001.txt
```

**Windows:**

```powershell
@"
INVOICE-2026-04-001
Date: 2026-04-05
Vendor: Meridian Supply Co.
PO Number: PO-88471
Amount: $14,250.00
Terms: Net 30
Ship To: 742 Evergreen Terrace, Springfield
"@ | Out-File -FilePath C:\Temp\invoice-2026-04-001.txt -Encoding utf8
```

### Step 2: Store the file

```bash
curl -s -X POST http://localhost:8094/api/v1/storage/store \
  -F "file=@/tmp/invoice-2026-04-001.txt" \
  -F "trackId=TRZ-000001" \
  -F "account=meridian_supply" | python3 -m json.tool
```

Expected output:

```json
{
    "status": "STORED",
    "tier": "HOT",
    "trackId": "TRZ-000001",
    "sha256": "a3f7b2c1e8d94056ab12cd34ef56789012345678abcdef0123456789abcdef01",
    "sizeBytes": 178,
    "striped": false,
    "throughputMbps": 12.5,
    "durationMs": 2
}
```

Note: `striped` is `false` because the file is smaller than the stripe size (4096 KB default). The SHA-256 hash and throughput will vary on your system.

### Step 3: Retrieve the file by track ID

```bash
curl -s -D - http://localhost:8094/api/v1/storage/retrieve/TRZ-000001 -o /tmp/retrieved-invoice.txt
```

Expected headers:

```
HTTP/1.1 200
Content-Type: text/plain
Content-Disposition: attachment; filename="invoice-2026-04-001.txt"
X-Track-Id: TRZ-000001
X-Storage-Tier: HOT
X-SHA256: a3f7b2c1e8d94056ab12cd34ef56789012345678abcdef0123456789abcdef01
```

```bash
# Verify contents match
diff /tmp/invoice-2026-04-001.txt /tmp/retrieved-invoice.txt
# No output = files are identical
```

### Step 4: List stored objects

```bash
# List all objects for account "meridian_supply"
curl -s "http://localhost:8094/api/v1/storage/objects?account=meridian_supply" | python3 -m json.tool
```

Expected output:

```json
[
    {
        "id": "c7e4f2a1-...",
        "trackId": "TRZ-000001",
        "filename": "invoice-2026-04-001.txt",
        "physicalPath": "/data/storage/hot/meridian_supply/invoice-2026-04-001.txt",
        "logicalPath": "/meridian_supply/invoice-2026-04-001.txt",
        "tier": "HOT",
        "sizeBytes": 178,
        "sha256": "a3f7b2c1...",
        "contentType": "text/plain",
        "accountUsername": "meridian_supply",
        "accessCount": 1,
        "lastAccessedAt": "2026-04-05T10:30:00Z",
        "createdAt": "2026-04-05T10:29:55Z",
        "tierChangedAt": null,
        "backupStatus": "NONE",
        "lastBackupAt": null,
        "striped": false,
        "stripeCount": 1,
        "compressionRatio": null,
        "deleted": false
    }
]
```

---

## Demo 2: SHA-256 Deduplication in Action

This demo proves that uploading the same file twice does not waste storage. The second upload detects the duplicate by SHA-256 and returns `DEDUPLICATED`.

### Step 1: Upload the same file again with a different track ID

```bash
# Upload the identical invoice a second time
curl -s -X POST http://localhost:8094/api/v1/storage/store \
  -F "file=@/tmp/invoice-2026-04-001.txt" \
  -F "trackId=TRZ-000002" \
  -F "account=meridian_supply" | python3 -m json.tool
```

Expected output:

```json
{
    "status": "DEDUPLICATED",
    "existingTrackId": "TRZ-000001",
    "sha256": "a3f7b2c1e8d94056ab12cd34ef56789012345678abcdef0123456789abcdef01",
    "savedBytes": 178
}
```

Key observations:
- `status` is `DEDUPLICATED`, not `STORED` -- the file was not written to disk again.
- `existingTrackId` points to the original `TRZ-000001` -- you know exactly where the deduplicated original lives.
- `savedBytes` tells you how much disk space was saved (178 bytes in this case, but for real 50 MB EDI files, this adds up fast).

### Step 2: Verify the access count increased

```bash
curl -s "http://localhost:8094/api/v1/storage/objects?account=meridian_supply" | python3 -m json.tool
```

The `accessCount` for the original file should now be `2` (incremented by the dedup hit).

### Step 3: Upload a different file to confirm it stores normally

```bash
echo "PURCHASE-ORDER-88472
Date: 2026-04-05
Buyer: Cascade Logistics Inc.
Items: 500x Widget-A, 200x Widget-B
Total: $32,700.00" > /tmp/po-88472.txt

curl -s -X POST http://localhost:8094/api/v1/storage/store \
  -F "file=@/tmp/po-88472.txt" \
  -F "trackId=TRZ-000003" \
  -F "account=cascade_logistics" | python3 -m json.tool
```

Expected output:

```json
{
    "status": "STORED",
    "tier": "HOT",
    "trackId": "TRZ-000003",
    "sha256": "b8c9d3e2f1a04567bc23de45fg67890123456789bcdef0234567890bcdef0234",
    "sizeBytes": 135,
    "striped": false,
    "throughputMbps": 15.2,
    "durationMs": 1
}
```

Different content produces a different SHA-256, so it is stored normally.

---

## Demo 3: Tier Lifecycle, Backup, and Metrics

This demo exercises the full lifecycle -- tiering, backup, and metrics endpoints.

### Step 1: Check storage metrics

```bash
curl -s http://localhost:8094/api/v1/storage/metrics | python3 -m json.tool
```

Expected output:

```json
{
    "hotCount": 2,
    "hotSizeGb": 0.0,
    "warmCount": 0,
    "warmSizeGb": 0,
    "coldCount": 0,
    "coldSizeGb": 0,
    "totalObjects": 2,
    "recentActions": 0
}
```

### Step 2: Trigger a manual tiering cycle

The automatic tiering runs every 15 minutes. For the demo, trigger it manually:

```bash
curl -s -X POST http://localhost:8094/api/v1/storage/lifecycle/tier | python3 -m json.tool
```

Expected output:

```json
{
    "status": "completed"
}
```

Since the files were just created (less than 168 hours ago -- the default `hot-to-warm-hours`), nothing will move. In production, files older than 7 days with low access counts would migrate from HOT to WARM.

### Step 3: Trigger a manual backup

```bash
curl -s -X POST http://localhost:8094/api/v1/storage/lifecycle/backup | python3 -m json.tool
```

Expected output:

```json
{
    "status": "completed"
}
```

### Step 4: Check that backups completed

```bash
curl -s "http://localhost:8094/api/v1/storage/objects?account=meridian_supply" | python3 -m json.tool
```

The `backupStatus` field should now show `BACKED_UP` and `lastBackupAt` should have a timestamp.

### Step 5: View lifecycle actions

```bash
curl -s http://localhost:8094/api/v1/storage/lifecycle/actions | python3 -m json.tool
```

Expected output (after files have been tiered or backed up):

```json
[
    {
        "action": "TIER_HOT_TO_WARM",
        "filename": "invoice-2026-04-001.txt",
        "trackId": "TRZ-000001",
        "sizeBytes": 178,
        "timestamp": "2026-04-05T18:15:00Z"
    }
]
```

Note: If no tiering has occurred yet (files are too new), this will return an empty array `[]`.

### Step 6: List objects by tier

```bash
# List only HOT tier objects
curl -s "http://localhost:8094/api/v1/storage/objects?tier=HOT" | python3 -m json.tool

# List only WARM tier objects (empty until tiering moves files)
curl -s "http://localhost:8094/api/v1/storage/objects?tier=WARM" | python3 -m json.tool
```

---

## Demo 4: Integration Pattern -- Python, Java, Node.js

### Python

```python
import requests

BASE = "http://localhost:8094/api/v1/storage"

# Store a file
with open("/tmp/invoice-2026-04-001.txt", "rb") as f:
    resp = requests.post(f"{BASE}/store", files={"file": f},
                         data={"trackId": "TRZ-PY-001", "account": "meridian_supply"})
    result = resp.json()
    print(f"Status: {result['status']}, SHA-256: {result.get('sha256', 'N/A')}")

# Retrieve a file
resp = requests.get(f"{BASE}/retrieve/TRZ-PY-001")
print(f"Tier: {resp.headers['X-Storage-Tier']}")
print(f"SHA-256: {resp.headers['X-SHA256']}")
print(f"Content: {resp.text[:100]}")

# Get metrics
metrics = requests.get(f"{BASE}/metrics").json()
print(f"HOT files: {metrics['hotCount']}, Total: {metrics['totalObjects']}")
```

### Java (HttpClient -- Java 21+)

```java
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;

public class StorageDemo {
    public static void main(String[] args) throws Exception {
        var client = HttpClient.newHttpClient();
        var base = "http://localhost:8094/api/v1/storage";

        // Store a file using multipart form
        String boundary = "----FormBoundary" + System.currentTimeMillis();
        String body = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"report.txt\"\r\n"
            + "Content-Type: text/plain\r\n\r\n"
            + "Quarterly Report Q1 2026 - Revenue: $2.4M\r\n"
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"trackId\"\r\n\r\n"
            + "TRZ-JAVA-001\r\n"
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"account\"\r\n\r\n"
            + "cascade_logistics\r\n"
            + "--" + boundary + "--\r\n";

        var storeReq = HttpRequest.newBuilder()
            .uri(URI.create(base + "/store"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        var storeResp = client.send(storeReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Store: " + storeResp.body());

        // Retrieve
        var getReq = HttpRequest.newBuilder()
            .uri(URI.create(base + "/retrieve/TRZ-JAVA-001")).GET().build();
        var getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Tier: " + getResp.headers().firstValue("X-Storage-Tier").orElse("?"));

        // Metrics
        var metricsReq = HttpRequest.newBuilder()
            .uri(URI.create(base + "/metrics")).GET().build();
        var metricsResp = client.send(metricsReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Metrics: " + metricsResp.body());
    }
}
```

### Node.js (fetch -- Node 18+)

```javascript
const fs = require("fs");
const path = require("path");

const BASE = "http://localhost:8094/api/v1/storage";

async function demo() {
  // Store a file
  const formData = new FormData();
  const fileContent = new Blob(["SHIPPING-MANIFEST-2026-04-05\nContainer: MSKU-7284910\nWeight: 12,450 kg\n"]);
  formData.append("file", fileContent, "manifest-2026-04-05.txt");
  formData.append("trackId", "TRZ-NODE-001");
  formData.append("account", "pacific_freight");

  const storeResp = await fetch(`${BASE}/store`, { method: "POST", body: formData });
  const storeResult = await storeResp.json();
  console.log("Store result:", JSON.stringify(storeResult, null, 2));

  // Retrieve the file
  const getResp = await fetch(`${BASE}/retrieve/TRZ-NODE-001`);
  console.log("Tier:", getResp.headers.get("X-Storage-Tier"));
  console.log("SHA-256:", getResp.headers.get("X-SHA256"));
  console.log("Content:", await getResp.text());

  // Metrics
  const metrics = await (await fetch(`${BASE}/metrics`)).json();
  console.log("Metrics:", JSON.stringify(metrics, null, 2));
}

demo().catch(console.error);
```

---

## Use Cases

1. **Regulatory document retention** -- Store invoices and compliance documents in HOT tier for fast access during audits. After 30 days, they automatically migrate to COLD tier for long-term retention at lower cost.
2. **EDI file deduplication** -- Trading partners sometimes retransmit the same EDI file. Dedup catches identical files by SHA-256, avoiding double-processing and saving disk space.
3. **High-throughput batch ingestion** -- Nightly batch jobs upload thousands of files. Parallel I/O striping ensures files over 4 MB write at maximum disk throughput across 8 threads.
4. **Disaster recovery** -- Incremental backups every 6 hours with SHA-256 integrity verification. Every file in HOT, WARM, or COLD gets a copy in the BACKUP tier.
5. **Predictive pre-staging** -- A partner downloads their reports every Monday at 9 AM. The AI lifecycle manager detects this pattern and pre-stages their files from WARM back to HOT every Monday at 8:30 AM.
6. **Multi-tenant isolation** -- The `account` parameter segregates files by trading partner. Each account has its own namespace in every tier.
7. **Compliance audit trail** -- Every access increments `accessCount` and updates `lastAccessedAt`. Combined with `createdAt` and `tierChangedAt`, you have a complete lifecycle audit trail.

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC connection string |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `STORAGE_HOT_PATH` | `/data/storage/hot` | Filesystem path for HOT tier (fast SSD recommended) |
| `STORAGE_WARM_PATH` | `/data/storage/warm` | Filesystem path for WARM tier |
| `STORAGE_COLD_PATH` | `/data/storage/cold` | Filesystem path for COLD tier (large, slow disk OK) |
| `STORAGE_BACKUP_PATH` | `/data/storage/backup` | Filesystem path for backup copies |
| `STORAGE_HOT_MAX_GB` | `100` | Maximum size of HOT tier in GB. At 80%, aggressive eviction begins. |
| `STORAGE_WARM_MAX_GB` | `500` | Maximum size of WARM tier in GB |
| `STORAGE_COLD_MAX_GB` | `2000` | Maximum size of COLD tier in GB |
| `STORAGE_STRIPE_SIZE_KB` | `4096` | Files larger than this (4 MB) use parallel striped writes |
| `STORAGE_IO_THREADS` | `8` | Number of parallel I/O threads for striped reads/writes |
| `STORAGE_WRITE_BUFFER_MB` | `64` | Write buffer size in MB per I/O operation |
| `STORAGE_HOT_WARM_HOURS` | `168` | Hours before inactive files move from HOT to WARM (default: 7 days) |
| `STORAGE_WARM_COLD_DAYS` | `30` | Days before inactive files move from WARM to COLD |
| `STORAGE_COLD_RETENTION_DAYS` | `365` | Days to retain files in COLD tier |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier for distributed deployments |
| `CLUSTER_HOST` | `storage-manager` | Hostname of this instance within the cluster |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label (PROD, STAGING, DEV) |

---

## API Reference -- All Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/storage/store` | Store a file (multipart: `file`, optional `trackId`, `account`) |
| `GET` | `/api/v1/storage/retrieve/{trackId}` | Retrieve a file by track ID (returns file bytes) |
| `GET` | `/api/v1/storage/objects` | List objects (optional query: `?account=X` or `?tier=HOT`) |
| `GET` | `/api/v1/storage/metrics` | Storage metrics: file counts and sizes per tier |
| `GET` | `/api/v1/storage/lifecycle/actions` | Recent lifecycle actions (tier moves, etc.) |
| `POST` | `/api/v1/storage/lifecycle/tier` | Trigger a manual tiering cycle |
| `POST` | `/api/v1/storage/lifecycle/backup` | Trigger a manual backup of all unprotected files |
| `GET` | `/api/v1/storage/health` | Health check with feature list and tier metrics |

---

## Cleanup

```bash
# Docker method
docker stop mft-storage-manager && docker rm mft-storage-manager
docker stop mft-postgres && docker rm mft-postgres

# Docker Compose method
docker compose -f docker-compose-storage.yml down -v

# Source method: Ctrl+C to stop the Java process, then:
rm -rf /tmp/mft-storage
rm /tmp/invoice-2026-04-001.txt /tmp/po-88472.txt /tmp/retrieved-invoice.txt

# Windows (PowerShell)
Remove-Item -Recurse -Force C:\mft-storage
Remove-Item C:\Temp\invoice-2026-04-001.txt
```

---

## Troubleshooting

### All Platforms

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connection refused` on port 8094 | Service not started or still starting | Wait 10-15 seconds. Check logs: `docker logs mft-storage-manager` |
| `File not found: TRZ-XXXX` on retrieve | Track ID does not exist or file was soft-deleted | Use `GET /api/v1/storage/objects` to list valid track IDs |
| `Could not resolve dependencies` during Maven build | First build -- dependencies downloading | Run `mvn clean package -DskipTests -pl storage-manager -am` again |
| `Relation "storage_objects" does not exist` | Flyway migration did not run | Ensure `spring.flyway.enabled=true` (default). Check DB has `filetransfer` database. |
| Empty `metrics` response (all zeros) | No files stored yet | Store a file first using `POST /api/v1/storage/store` |

### Linux

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Permission denied` writing to `/data/storage/hot` | Docker volume permissions | Use `-v` with a local directory you own, or run with `--user $(id -u):$(id -g)` |
| `host.docker.internal` not resolving | Linux Docker does not support this by default | Use `--network host` or `--add-host=host.docker.internal:host-gateway` |

### macOS

| Symptom | Cause | Fix |
|---------|-------|-----|
| Slow file I/O in Docker | Docker Desktop file sharing overhead | Use named Docker volumes (`storage_data:/data/storage`) instead of bind mounts |
| Port 8094 already in use | Another service on the same port | Check: `lsof -i :8094` and stop the conflicting process |

### Windows

| Symptom | Cause | Fix |
|---------|-------|-----|
| `\data\storage\hot` path errors | Windows backslash path issues | Use forward slashes or set `STORAGE_HOT_PATH=C:/mft-storage/hot` |
| Docker cannot access file | WSL 2 filesystem boundary | Store files in WSL path (`/tmp/`) or Windows path (`C:\Temp\`), not across both |
| `mvn` not recognized | Maven not in PATH | Run `winget install Apache.Maven`, restart terminal |

---

## What's Next

- **Encryption Service** ([ENCRYPTION-SERVICE.md](ENCRYPTION-SERVICE.md)) -- Encrypt files before storing them. Combine with Storage Manager for encrypted-at-rest tiered storage.
- **AS2 Service** ([AS2-SERVICE.md](AS2-SERVICE.md)) -- Receive B2B files via AS2/AS4 protocol. Files can be routed into the Storage Manager for tiered retention.
- **Config Service** ([CONFIG-SERVICE.md](CONFIG-SERVICE.md)) -- Define file flows that automatically route uploaded files into the Storage Manager.
- **Analytics Service** ([ANALYTICS-SERVICE.md](ANALYTICS-SERVICE.md)) -- Monitor storage utilization, tier distribution trends, and deduplication savings over time.

# Storage Manager — Standalone Product Guide

> **Tiered storage with deduplication.** Store files across HOT/WARM/COLD tiers with SHA-256 deduplication, parallel I/O striping, and automated lifecycle management.

**Port:** 8094 | **Dependencies:** PostgreSQL | **Auth:** None

---

## Why Use This

- **Tiered storage** — HOT (SSD, 100 GB) → WARM (500 GB) → COLD (2 TB) with automatic migration
- **SHA-256 deduplication** — Never store the same file twice
- **Parallel I/O** — 8-thread striped writes with 4 KB stripes for throughput
- **Automated lifecycle** — Files tier down based on age (7 days → WARM, 30 days → COLD)
- **Incremental backups** — Automatic backup to dedicated tier every 6 hours
- **AI-powered pre-staging** — Predictive pre-staging of files based on access patterns

---

## Quick Start

```bash
docker compose up -d postgres storage-manager
curl http://localhost:8094/api/v1/storage/health
```

```json
{
  "status": "UP",
  "service": "storage-manager",
  "hotCount": 0, "hotSizeGb": 0.0,
  "warmCount": 0, "warmSizeGb": 0.0,
  "coldCount": 0, "coldSizeGb": 0.0,
  "features": ["parallel-io", "tiered-storage", "deduplication", "incremental-backup", "ai-lifecycle", "predictive-prestage"]
}
```

---

## API Reference

### 1. Store a File

**POST** `/api/v1/storage/store`

```bash
curl -X POST http://localhost:8094/api/v1/storage/store \
  -F "file=@/path/to/report.pdf" \
  -F "trackId=TRZ-2026-001" \
  -F "account=finance-dept"
```

**Response (first upload):**
```json
{
  "status": "STORED",
  "tier": "HOT",
  "trackId": "TRZ-2026-001",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "sizeBytes": 1048576,
  "striped": true,
  "throughputMbps": 245.3,
  "durationMs": 4
}
```

**Response (duplicate file — deduplication kicks in):**
```json
{
  "status": "DEDUPLICATED",
  "existingTrackId": "TRZ-2026-001",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "savedBytes": 1048576
}
```

### 2. Retrieve a File

**GET** `/api/v1/storage/retrieve/{trackId}`

```bash
curl http://localhost:8094/api/v1/storage/retrieve/TRZ-2026-001 \
  --output report.pdf
```

**Response headers:**
```
Content-Type: application/pdf
Content-Disposition: attachment; filename="report.pdf"
X-Track-Id: TRZ-2026-001
X-Storage-Tier: HOT
X-SHA256: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

### 3. List Stored Objects

**GET** `/api/v1/storage/objects`

```bash
# All objects
curl http://localhost:8094/api/v1/storage/objects

# Filter by account
curl "http://localhost:8094/api/v1/storage/objects?account=finance-dept"

# Filter by tier
curl "http://localhost:8094/api/v1/storage/objects?tier=HOT"
```

**Response:**
```json
[
  {
    "id": "a1b2c3d4-...",
    "trackId": "TRZ-2026-001",
    "filename": "report.pdf",
    "tier": "HOT",
    "sizeBytes": 1048576,
    "sha256": "e3b0c44...",
    "contentType": "application/pdf",
    "accountUsername": "finance-dept",
    "accessCount": 3,
    "lastAccessedAt": "2026-04-05T15:00:00Z",
    "createdAt": "2026-04-05T14:00:00Z",
    "backupStatus": "BACKED_UP",
    "striped": true
  }
]
```

### 4. Storage Metrics

**GET** `/api/v1/storage/metrics`

```bash
curl http://localhost:8094/api/v1/storage/metrics
```

**Response:**
```json
{
  "hotCount": 42,
  "hotSizeGb": 2.5,
  "warmCount": 156,
  "warmSizeGb": 45.3,
  "coldCount": 1200,
  "coldSizeGb": 180.7,
  "totalObjects": 1398,
  "recentActions": 15
}
```

### 5. View Lifecycle Actions

**GET** `/api/v1/storage/lifecycle/actions`

```bash
curl http://localhost:8094/api/v1/storage/lifecycle/actions
```

**Response:**
```json
[
  {
    "action": "TIER_HOT_TO_WARM",
    "filename": "old-report.csv",
    "trackId": "TRZ-2026-OLD",
    "sizeBytes": 2048000,
    "timestamp": "2026-04-05T03:00:00Z"
  }
]
```

### 6. Trigger Manual Tiering

**POST** `/api/v1/storage/lifecycle/tier`

```bash
curl -X POST http://localhost:8094/api/v1/storage/lifecycle/tier
```

### 7. Trigger Manual Backup

**POST** `/api/v1/storage/lifecycle/backup`

```bash
curl -X POST http://localhost:8094/api/v1/storage/lifecycle/backup
```

---

## Integration Examples

### Python
```python
import requests

BASE = "http://localhost:8094/api/v1/storage"

# Store a file
with open("data.csv", "rb") as f:
    result = requests.post(f"{BASE}/store",
        files={"file": f},
        data={"trackId": "MY-APP-001", "account": "my-app"}
    ).json()

if result["status"] == "DEDUPLICATED":
    print(f"File already exists (saved {result['savedBytes']} bytes)")
else:
    print(f"Stored in {result['tier']} tier, SHA256: {result['sha256']}")

# Retrieve later
resp = requests.get(f"{BASE}/retrieve/MY-APP-001")
with open("downloaded.csv", "wb") as f:
    f.write(resp.content)

# Check storage metrics
metrics = requests.get(f"{BASE}/metrics").json()
print(f"Total: {metrics['totalObjects']} objects, {metrics['hotSizeGb']:.1f} GB hot")
```

### Java
```java
RestTemplate rest = new RestTemplate();

// Store file
MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
body.add("file", new FileSystemResource("data.csv"));
body.add("trackId", "MY-APP-001");

Map<String, Object> result = rest.postForObject(
    "http://localhost:8094/api/v1/storage/store",
    new HttpEntity<>(body), Map.class
);

// Retrieve file
byte[] file = rest.getForObject(
    "http://localhost:8094/api/v1/storage/retrieve/MY-APP-001",
    byte[].class
);
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `storage.hot.path` | `/data/storage/hot` | HOT tier path |
| `storage.hot.max-size-gb` | `100` | HOT tier max size |
| `storage.warm.path` | `/data/storage/warm` | WARM tier path |
| `storage.warm.max-size-gb` | `500` | WARM tier max size |
| `storage.cold.path` | `/data/storage/cold` | COLD tier path |
| `storage.cold.max-size-gb` | `2000` | COLD tier max size |
| `storage.hot-to-warm-hours` | `168` | Hours before HOT → WARM (7 days) |
| `storage.warm-to-cold-days` | `30` | Days before WARM → COLD |
| `storage.io-threads` | `8` | Parallel I/O threads |
| `storage.stripe-size-kb` | `4096` | Stripe size for parallel writes |
| `server.port` | `8094` | HTTP port |

---

## All Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/storage/store` | Store a file (with dedup) |
| GET | `/api/v1/storage/retrieve/{trackId}` | Retrieve a file |
| GET | `/api/v1/storage/objects` | List objects (filter by account/tier) |
| GET | `/api/v1/storage/metrics` | Storage metrics |
| GET | `/api/v1/storage/lifecycle/actions` | View lifecycle actions |
| POST | `/api/v1/storage/lifecycle/tier` | Trigger tiering cycle |
| POST | `/api/v1/storage/lifecycle/backup` | Trigger backup |
| GET | `/api/v1/storage/health` | Health check |

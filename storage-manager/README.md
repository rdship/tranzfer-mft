# Storage Manager

> Tiered storage with parallel I/O, SHA-256 deduplication, AI-powered lifecycle management, and automatic backups.

**Port:** 8094 | **Database:** PostgreSQL | **Required:** Optional

---

## Overview

The storage manager provides enterprise file storage:

- **Tiered storage** — HOT (fast SSD) → WARM → COLD (archive) with automatic transitions
- **Parallel I/O** — GPFS-style striped writes across multiple threads
- **Deduplication** — SHA-256 content-hash prevents storing identical files twice
- **AI-powered tiering** — Monitors access patterns, pre-stages frequently used files
- **Automatic backups** — Incremental every 6 hours, full daily
- **Lifecycle management** — Configurable retention and capacity thresholds

---

## Quick Start

```bash
docker compose up -d postgres storage-manager

# Health check
curl http://localhost:8094/api/v1/storage/health

# Store a file
curl -X POST http://localhost:8094/api/v1/storage/store \
  -F "file=@large-report.csv" -F "trackId=TRZA3X5T3LUY" -F "account=partner-acme"
```

---

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/storage/store` | Store file (multipart, up to 512 MB) |
| GET | `/api/v1/storage/retrieve/{trackId}` | Download file (records access for tiering) |
| GET | `/api/v1/storage/objects?account=&tier=` | List stored files |
| GET | `/api/v1/storage/metrics` | Storage distribution by tier |
| GET | `/api/v1/storage/lifecycle/actions` | Recent lifecycle operations |
| POST | `/api/v1/storage/lifecycle/tier` | Trigger manual tiering cycle |
| POST | `/api/v1/storage/lifecycle/backup` | Trigger manual backup |
| GET | `/api/v1/storage/health` | Service health with features |

**Store a file:**
```bash
curl -X POST http://localhost:8094/api/v1/storage/store \
  -F "file=@report.csv" \
  -F "trackId=TRZA3X5T3LUY" \
  -F "account=partner-acme"
```

**Response:**
```json
{
  "trackId": "TRZA3X5T3LUY",
  "tier": "HOT",
  "sizeBytes": 245760,
  "sha256": "abc123...",
  "deduplicated": false,
  "striped": true,
  "stripeCount": 4
}
```

**Retrieve a file:**
```bash
curl http://localhost:8094/api/v1/storage/retrieve/TRZA3X5T3LUY -o report.csv
```

**Storage metrics:**
```bash
curl http://localhost:8094/api/v1/storage/metrics
```

**Response:**
```json
{
  "hot": {"usedBytes": 5368709120, "capacityBytes": 107374182400, "fileCount": 1200},
  "warm": {"usedBytes": 21474836480, "capacityBytes": 536870912000, "fileCount": 5600},
  "cold": {"usedBytes": 85899345920, "capacityBytes": 2147483648000, "fileCount": 23000}
}
```

---

## Storage Tiers

| Tier | Default Capacity | Retention | Purpose |
|------|-----------------|-----------|---------|
| **HOT** | 100 GB | 7 days | Fast access, recent files |
| **WARM** | 500 GB | 30 days | Medium access, older files |
| **COLD** | 2 TB | 365 days | Archive, rarely accessed |
| **BACKUP** | — | — | Backup copies of all tiers |

### Automatic Tiering

Files move automatically based on age and access patterns:
- HOT → WARM after 7 days (configurable)
- WARM → COLD after 30 days
- If HOT tier exceeds 80% capacity, oldest files are moved early
- Frequently accessed WARM files are pre-staged back to HOT

### Deduplication

Before storing, the service computes a SHA-256 hash:
- If an identical file already exists → reuse it (increment access count)
- Saves storage and speeds up writes for duplicate files

### Parallel I/O

Files are chunked and written across multiple threads:
- Default: 8 parallel threads, 4 KB stripe size, 64 MB write buffer
- Improves throughput for large files

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8094` | API port |
| `STORAGE_HOT_PATH` | `/data/storage/hot` | Hot tier directory |
| `STORAGE_HOT_CAPACITY` | `107374182400` | Hot tier capacity (100 GB) |
| `STORAGE_HOT_RETENTION_HOURS` | `168` | Hours before moving to warm (7 days) |
| `STORAGE_WARM_PATH` | `/data/storage/warm` | Warm tier directory |
| `STORAGE_COLD_PATH` | `/data/storage/cold` | Cold tier directory |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |

---

## Scheduled Tasks

| Task | Interval | Purpose |
|------|----------|---------|
| Tiering cycle | Configurable | Move files between tiers based on age/access |
| Incremental backup | Every 6 hours | Backup changed files |
| Full backup | Daily | Complete backup of all tiers |

---

## Dependencies

- **PostgreSQL** — Required. Storage metadata, access tracking.
- **shared** module — Entities, repositories.

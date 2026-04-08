# TranzFer MFT — Performance, Security & Resilience Testing Guide

**Run from: your laptop (not the dev machine)**
**Platform: 20 microservices, 3 protocols (SFTP/FTP/AS2), 1 shared PostgreSQL DB, RabbitMQ**
**Goal: Validate Speed · Security · Stability across millions of file transfers**

---

## Table of Contents

1. [Laptop Requirements](#1-laptop-requirements)
2. [Software Prerequisites](#2-software-prerequisites)
3. [Platform Startup](#3-platform-startup)
4. [Test Suite Overview](#4-test-suite-overview)
5. [Phase 1 — Smoke Test (5 min)](#5-phase-1--smoke-test-5-min)
6. [Phase 2 — Individual Service Tests](#6-phase-2--individual-service-tests)
7. [Phase 3 — Integration Pipeline Tests](#7-phase-3--integration-pipeline-tests)
8. [Phase 4 — Volume & Load Tests (Millions of Transfers)](#8-phase-4--volume--load-tests-millions-of-transfers)
9. [Phase 5 — Security Tests](#9-phase-5--security-tests)
10. [Phase 6 — Resilience & Chaos Tests](#10-phase-6--resilience--chaos-tests)
11. [Phase 7 — Endurance / Soak Test (Optional — 24h)](#11-phase-7--endurance--soak-test-optional--24h)
12. [Results Interpretation](#12-results-interpretation)
13. [Pass / Fail Thresholds](#13-pass--fail-thresholds)
14. [Findings Template](#14-findings-template)
15. [Quick Reference — All Commands](#15-quick-reference--all-commands)

---

## 1. Laptop Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| RAM | 16 GB | 32 GB |
| CPU | 8 cores | 12+ cores |
| Disk (free) | 40 GB | 80 GB (SSD) |
| OS | macOS / Linux | macOS 14+ / Ubuntu 22.04 |
| Docker Desktop RAM allocation | 12 GB | 20 GB |
| Docker Desktop CPU | 6 | 10 |

> **Docker Desktop → Settings → Resources**: Set RAM to at least 12 GB and CPU to 6 before starting.

---

## 2. Software Prerequisites

Run the setup script once, then verify:

```bash
cd /path/to/file-transfer-platform
./tests/perf/setup/prereqs.sh
./tests/perf/setup/prereqs.sh --verify
```

**What it installs:**

| Tool | Purpose | Install Method |
|------|---------|----------------|
| Docker Desktop | Run all 20 services | Manual — https://www.docker.com/products/docker-desktop |
| k6 | HTTP load testing | `brew install k6` / Linux binary |
| Python 3.11+ | SFTP/FTP benchmarks | `brew install python3` |
| paramiko | SFTP Python client | `pip3 install paramiko` |
| sshpass | Password-based SFTP | `brew install hudochenkov/sshpass/sshpass` |
| jq | JSON parsing | `brew install jq` |
| bc | Math in shell scripts | Built-in on macOS/Linux |
| gnuplot (optional) | Chart generation | `brew install gnuplot` |

---

## 3. Platform Startup

### 3a. Clone and build (first time only)

```bash
git clone <repo-url>
cd file-transfer-platform

# Build all Java services (takes ~3-5 min on first run)
mvn clean package -DskipTests -q

# Build Docker images
docker compose build
```

### 3b. Start the full platform

```bash
# Start infrastructure first (PostgreSQL + RabbitMQ)
docker compose up -d mft-postgres mft-rabbitmq
sleep 15  # Wait for DB to be ready

# Start core services
docker compose up -d \
  mft-config-service \
  mft-keystore-manager \
  mft-encryption-service \
  mft-license-service

sleep 20

# Start all remaining services
docker compose up -d
```

### 3c. Verify all 20 services are healthy

```bash
./tests/perf/setup/platform-check.sh
```

Expected output:
```
[✓] onboarding-api      8080  UP   (231ms)
[✓] sftp-service        8081  UP   (89ms)
[✓] ftp-service         8082  UP   (95ms)
[✓] ftp-web-service     8083  UP   (78ms)
[✓] config-service      8084  UP   (44ms)
[✓] gateway-service     8085  UP   (112ms)
[✓] encryption-service  8086  UP   (67ms)
[✓] forwarder-service   8087  UP   (91ms)
[✓] dmz-proxy           8088  UP   (55ms)
[✓] license-service     8089  UP   (48ms)
[✓] analytics-service   8090  UP   (134ms)
[✓] ai-engine           8091  UP   (198ms)
[✓] screening-service   8092  UP   (88ms)
[✓] keystore-manager    8093  UP   (72ms)
[✓] as2-service         8094  UP   (101ms)
[✓] edi-converter       8095  UP   (63ms)
[✓] storage-manager     8096  UP   (82ms)
[✓] notification-svc    8097  UP   (77ms)
[✓] platform-sentinel   8098  UP   (156ms)
[✓] PostgreSQL          5432  UP
[✓] RabbitMQ            5672  UP  (mgmt: 15672)

All 20 services healthy. Platform ready for testing.
```

> If any service shows DOWN, check: `docker compose logs mft-<service-name> --tail=50`

---

## 4. Test Suite Overview

```
tests/perf/
├── README.md                    ← You are here
├── run-all.sh                   ← Run everything (generates full report)
├── setup/
│   ├── prereqs.sh               ← Install all tools
│   └── platform-check.sh        ← Verify all 20 services healthy
├── k6/
│   ├── lib/auth.js              ← Shared auth (login + JWT management)
│   ├── 01-smoke.js              ← All 20 services alive? (5 min)
│   ├── 02-onboarding.js         ← Auth, account creation, JWT throughput
│   ├── 03-encryption.js         ← Encrypt/decrypt throughput by file size
│   ├── 04-screening.js          ← OFAC screening throughput
│   ├── 05-analytics.js          ← Dashboard queries, metric ingestion
│   ├── 06-sentinel.js           ← Health score, findings API
│   ├── 07-full-pipeline.js      ← End-to-end REST transfer pipeline
│   └── 08-security.js           ← Auth bypass, injection, rate limiting
├── python/
│   ├── requirements.txt
│   ├── sftp_benchmark.py        ← SFTP: concurrent uploads, large files, 10K files
│   ├── ftp_benchmark.py         ← FTP: passive mode, concurrent, bulk
│   └── million_transfers.py     ← Volume: 1M small files via async batching
└── resilience/
    ├── kill-service.sh          ← Kill one service and measure impact + recovery
    ├── chaos-rabbitmq.sh        ← Kill RabbitMQ mid-transfer, observe DLQ
    ├── chaos-database.sh        ← Kill DB, verify circuit breakers open
    └── recovery-time.sh         ← Measure MTTR for each service after restart
```

**Recommended execution order:**
```
Phase 1 (5 min)   → Smoke: confirm platform alive
Phase 2 (2 hrs)   → Individual service tests: find baseline per service
Phase 3 (1 hr)    → Integration: end-to-end pipelines
Phase 4 (4 hrs)   → Volume: 10K → 100K → 1M transfers
Phase 5 (1 hr)    → Security: boundary checks, auth, injection
Phase 6 (2 hrs)   → Resilience: kill services, chaos, recovery
Phase 7 (24 hrs)  → Endurance: soak test (optional, overnight)
```

---

## 5. Phase 1 — Smoke Test (5 min)

Quick confirmation that all services are alive and responding.

```bash
k6 run tests/perf/k6/01-smoke.js
```

**What it tests:**
- `/actuator/health` on all 20 services
- Login endpoint responds with JWT
- One basic authenticated request per service
- SFTP port 2222 accepts SSH connection
- FTP port 21 accepts connection

**Pass criteria:** All 20 services return HTTP 200 within 3 seconds. Zero failures.

---

## 6. Phase 2 — Individual Service Tests

Run each service in isolation. Each test uses its own load profile.

### 6.1 onboarding-api (Port 8080) — Auth & Account Management

```bash
# Light: baseline latency
k6 run --env PROFILE=light tests/perf/k6/02-onboarding.js

# Medium: 100 concurrent users
k6 run --env PROFILE=medium tests/perf/k6/02-onboarding.js

# Heavy: 500 concurrent users (stress test)
k6 run --env PROFILE=heavy tests/perf/k6/02-onboarding.js
```

**Scenarios tested:**
- Login (POST /api/v1/auth/login) — measures JWT issuance throughput
- Account creation (POST /api/v1/accounts) — measures write throughput
- Account listing (GET /api/v1/accounts) — measures DB read under load
- Partner creation (POST /api/v1/partners)
- Token refresh rate

**Targets:**
| Metric | Light (10 users) | Medium (100 users) | Heavy (500 users) |
|--------|------------------|--------------------|-------------------|
| Login p95 | < 100ms | < 300ms | < 800ms |
| Account create p95 | < 200ms | < 500ms | < 1.5s |
| Error rate | 0% | < 0.1% | < 1% |
| Throughput (logins/sec) | > 50 | > 200 | > 500 |

### 6.2 encryption-service (Port 8086) — AES Throughput

```bash
k6 run --env FILE_SIZE=1KB tests/perf/k6/03-encryption.js
k6 run --env FILE_SIZE=100KB tests/perf/k6/03-encryption.js
k6 run --env FILE_SIZE=1MB tests/perf/k6/03-encryption.js
k6 run --env FILE_SIZE=10MB tests/perf/k6/03-encryption.js
k6 run --env FILE_SIZE=100MB tests/perf/k6/03-encryption.js
```

**Targets:**
| File Size | Encrypt p95 | Decrypt p95 | Min Throughput |
|-----------|-------------|-------------|----------------|
| 1 KB | < 10ms | < 10ms | > 5,000 ops/s |
| 100 KB | < 30ms | < 30ms | > 500 ops/s |
| 1 MB | < 200ms | < 200ms | > 50 ops/s |
| 10 MB | < 1.5s | < 1.5s | > 5 ops/s |
| 100 MB | < 15s | < 15s | > 0.5 ops/s |

### 6.3 screening-service (Port 8092) — OFAC/Sanctions Throughput

```bash
k6 run tests/perf/k6/04-screening.js
```

**Scenarios:**
- Single file screening (POST /api/v1/screening/scan)
- Batch screening (burst of 100 files)
- Positive hit detection (verify BLOCKED status returned)
- Concurrent screening under load

**Target:** p95 < 300ms per scan. Zero false negatives on known hits.

### 6.4 analytics-service (Port 8090) — Query Latency

```bash
k6 run tests/perf/k6/05-analytics.js
```

**Scenarios:**
- Dashboard query (GET /api/v1/analytics/dashboard)
- Metrics ingestion (POST /api/v1/analytics/metrics)
- Historical query (date range queries)
- Concurrent dashboard reads (100 users)

**Target:** Dashboard p95 < 500ms at 100 concurrent users.

### 6.5 platform-sentinel (Port 8098) — Observer Under Load

```bash
k6 run tests/perf/k6/06-sentinel.js
```

**Scenarios:**
- Health score retrieval (GET /api/v1/sentinel/health-score)
- Findings list (GET /api/v1/sentinel/findings)
- Trigger manual analysis (POST /api/v1/sentinel/analyze)
- Verify findings generated during load

**Target:** Health score p95 < 200ms. Analysis cycle < 30s.

### 6.6 SFTP Service (Port 2222) — Protocol Benchmarks

```bash
# Install Python deps first (once)
pip3 install -r tests/perf/python/requirements.txt

# Run SFTP benchmarks
python3 tests/perf/python/sftp_benchmark.py --scenario small-files
python3 tests/perf/python/sftp_benchmark.py --scenario large-file --size 1GB
python3 tests/perf/python/sftp_benchmark.py --scenario concurrent --connections 50
python3 tests/perf/python/sftp_benchmark.py --scenario bulk --count 10000
```

**Scenarios:**
| Scenario | Description | Target |
|----------|-------------|--------|
| small-files | 1000 x 10KB uploads | > 200 files/sec |
| large-file | Single 1GB upload | > 50 MB/s throughput |
| concurrent | 50 simultaneous connections | All complete, 0 errors |
| bulk | 10,000 x 1KB files | > 500 files/sec |
| download | 1000 x 1MB downloads | > 100 files/sec |

### 6.7 FTP Service (Port 21) — Protocol Benchmarks

```bash
python3 tests/perf/python/ftp_benchmark.py --scenario passive-upload
python3 tests/perf/python/ftp_benchmark.py --scenario concurrent --connections 30
python3 tests/perf/python/ftp_benchmark.py --scenario bulk --count 5000
```

### 6.8 AS2 Service (Port 8094) — EDI/AS2 Exchange

```bash
# AS2 MDN round-trip time
curl -X POST http://localhost:8094/api/v1/as2/send \
  -H "Content-Type: application/edi-x12" \
  -d "ISA*00*..." \
  --write-out "Total: %{time_total}s\n"
```

**Target:** AS2 MDN round-trip < 2s, throughput > 50 messages/sec.

### 6.9 EDI Converter (Port 8095) — Conversion Throughput

```bash
k6 run tests/perf/k6/03-encryption.js --env SERVICE=edi
```

**Target:** X12 → JSON conversion p95 < 100ms. > 500 conversions/sec.

### 6.10 All Other Services — Health + Baseline

For services not benchmarked above (config, forwarder, dmz, license, keystore, storage, notification), check baseline with:

```bash
# Generates a baseline latency table for all services
./tests/perf/setup/platform-check.sh --baseline
```

---

## 7. Phase 3 — Integration Pipeline Tests

These tests exercise real data flows across multiple services — closest to production.

### Pipeline A: Full Transfer (REST path)

```
User → onboarding-api (auth)
     → file upload
     → screening-service (OFAC check)
     → encryption-service (AES encrypt)
     → storage-manager (store)
     → notification-service (alert)
     → analytics-service (record metrics)
     → platform-sentinel (observe)
```

```bash
# Run end-to-end REST pipeline
k6 run --env PIPELINE=full tests/perf/k6/07-full-pipeline.js

# Light (10 users, 5 min)
k6 run --env PIPELINE=full --env PROFILE=light tests/perf/k6/07-full-pipeline.js

# Medium (100 users, 15 min)  
k6 run --env PIPELINE=full --env PROFILE=medium tests/perf/k6/07-full-pipeline.js

# Heavy (500 users, 30 min)
k6 run --env PIPELINE=full --env PROFILE=heavy tests/perf/k6/07-full-pipeline.js
```

**Target:** End-to-end p95 < 3s at 100 concurrent users.

### Pipeline B: SFTP → Gateway → Storage

Real file transfer via SFTP protocol through the platform:

```bash
python3 tests/perf/python/sftp_benchmark.py --scenario full-pipeline --users 50
```

### Pipeline C: Screening-heavy (compliance)

Upload 1000 files that trigger screening rules, verify all properly flagged:

```bash
k6 run --env PIPELINE=screening tests/perf/k6/07-full-pipeline.js
```

### Pipeline D: EDI Bulk Processing

Convert + route 10,000 EDI files through the platform:

```bash
python3 tests/perf/python/million_transfers.py --scenario edi-bulk --count 10000
```

### Pipeline E: Simultaneous all protocols

SFTP + FTP + AS2 + REST all at the same time — closest to peak production load:

```bash
# Run all protocols concurrently (each in background)
python3 tests/perf/python/sftp_benchmark.py --concurrent --count 1000 &
python3 tests/perf/python/ftp_benchmark.py --concurrent --count 1000 &
k6 run --env PIPELINE=full --env PROFILE=medium tests/perf/k6/07-full-pipeline.js &
wait
echo "All protocols completed"
```

---

## 8. Phase 4 — Volume & Load Tests (Millions of Transfers)

> **Warning:** These tests generate real load. Ensure the platform is running on Docker with adequate RAM/CPU allocated.

### 8.1 Stepped Volume Test (10K → 100K → 1M)

```bash
# Step 1: 10,000 transfers (takes ~10 min)
python3 tests/perf/python/million_transfers.py --count 10000 --batch-size 100 --workers 20

# Step 2: 100,000 transfers (takes ~1 hr)
python3 tests/perf/python/million_transfers.py --count 100000 --batch-size 500 --workers 50

# Step 3: 1,000,000 transfers (takes ~8-12 hrs — run overnight)
python3 tests/perf/python/million_transfers.py --count 1000000 --batch-size 1000 --workers 100
```

**What it measures per run:**
- Transfers/second (throughput)
- p50, p95, p99 transfer latency
- Error rate (failed transfers)
- DB connection pool saturation
- RabbitMQ queue depth at peak
- Memory growth (watch for leaks)
- Sentinel health score trend

**Expected results table (fill in after each run):**

| Volume | Duration | Throughput | p95 Latency | Error Rate | Sentinel Score |
|--------|----------|------------|-------------|------------|----------------|
| 10,000 | | | | | |
| 100,000 | | | | | |
| 1,000,000 | | | | | |

### 8.2 Concurrent Connection Stress

Maximum concurrent open connections before the platform degrades:

```bash
python3 tests/perf/python/sftp_benchmark.py \
  --scenario ramp-connections \
  --start 10 \
  --end 500 \
  --step 10 \
  --hold-seconds 30
```

Record the connection count at which:
- [ ] First errors appear
- [ ] Error rate exceeds 1%
- [ ] Error rate exceeds 10%
- [ ] Service becomes unavailable

### 8.3 Large File Transfer

```bash
python3 tests/perf/python/sftp_benchmark.py --scenario large-file --size 1GB
python3 tests/perf/python/sftp_benchmark.py --scenario large-file --size 5GB
python3 tests/perf/python/sftp_benchmark.py --scenario large-file --size 10GB
```

**Record:** Time, throughput (MB/s), memory used during transfer.

### 8.4 Mixed File Size Flood

Simulate realistic production mix (80% small, 15% medium, 5% large):

```bash
python3 tests/perf/python/million_transfers.py \
  --scenario realistic-mix \
  --count 100000 \
  --small-pct 80 \
  --medium-pct 15 \
  --large-pct 5
```

---

## 9. Phase 5 — Security Tests

> These tests probe the security boundaries. All are legitimate pen-test style checks against your own running instance.

```bash
k6 run tests/perf/k6/08-security.js
```

**Tests performed:**

### 9.1 Authentication Boundary

| Test | Method | Expected Response |
|------|--------|-------------------|
| No JWT token | Any authenticated endpoint | 401 Unauthorized |
| Expired JWT (1970 exp) | Any endpoint | 401 Unauthorized |
| Tampered JWT signature | Any endpoint | 401 Unauthorized |
| JWT with escalated role (ADMIN) | ADMIN-only endpoint | 403 Forbidden |
| Valid JWT wrong service | X-Internal-Key endpoint | 403 Forbidden |
| Missing X-Internal-Key | Internal endpoint | 401 Unauthorized |

### 9.2 Brute Force Protection

```bash
# Fire 20 failed logins against same account — expect lockout after threshold
k6 run --env TEST=brute-force tests/perf/k6/08-security.js
```

- After N failures (check sentinel rule `login_failure_spike` threshold), account should lock
- Locked account returns 423 Locked or 401 with lockout message
- Sentinel should create a finding within 5 minutes

### 9.3 Input Validation

| Test | Input | Expected |
|------|-------|----------|
| Path traversal in filename | `../../etc/passwd` | 400 Bad Request or sanitized |
| SQL injection in search | `'; DROP TABLE accounts; --` | 400 or sanitized |
| Oversized filename | 10,000-char filename | 400 Bad Request |
| Null bytes in metadata | `\x00` in header values | 400 Bad Request |
| XSS in display fields | `<script>alert(1)</script>` | Sanitized / 400 |
| XXE in XML upload | XML with entity expansion | Rejected |
| EICAR test file | Standard AV test string | Quarantined (screening hit) |

### 9.4 Rate Limiting Verification

```bash
# Fire 1000 requests/sec against rate-limited endpoint
k6 run --env TEST=rate-limit tests/perf/k6/08-security.js
```

Expect: 429 Too Many Requests after threshold exceeded.

### 9.5 TLS/Protocol Security

```bash
# Test TLS version downgrade
openssl s_client -connect localhost:9443 -tls1    # Should FAIL (TLS 1.0 rejected)
openssl s_client -connect localhost:9443 -tls1_1  # Should FAIL (TLS 1.1 rejected)
openssl s_client -connect localhost:9443 -tls1_2  # Should SUCCEED
openssl s_client -connect localhost:9443 -tls1_3  # Should SUCCEED

# Check cipher suites
nmap --script ssl-enum-ciphers -p 9443 localhost
```

### 9.6 Audit Log Integrity

```bash
# Platform uses HMAC-signed audit logs — verify tamper detection
curl -s http://localhost:8080/api/v1/admin/audit-integrity | jq '.status'
# Expected: "VALID"
```

### 9.7 SFTP Security

```bash
# Attempt SFTP with wrong credentials (should fail, not crash)
sftp -P 2222 -o StrictHostKeyChecking=no wronguser@localhost

# Attempt directory traversal via SFTP
sftp -P 2222 admin@localhost
sftp> get ../../../etc/passwd  # Should be blocked

# Attempt to exceed quota
python3 tests/perf/python/sftp_benchmark.py --scenario quota-exceed
```

---

## 10. Phase 6 — Resilience & Chaos Tests

> These tests kill services or resources mid-operation to verify circuit breakers, graceful degradation, and recovery.

### 10.1 Single Service Kill

Kill each service one at a time while load is running. Observe what degrades, what keeps working.

```bash
# Start background load first
k6 run --env PROFILE=medium tests/perf/k6/07-full-pipeline.js &
K6_PID=$!

# Kill services one at a time (30 sec intervals)
./tests/perf/resilience/kill-service.sh --service ai-engine --duration 60
./tests/perf/resilience/kill-service.sh --service analytics-service --duration 60
./tests/perf/resilience/kill-service.sh --service notification-service --duration 60
./tests/perf/resilience/kill-service.sh --service keystore-manager --duration 60

wait $K6_PID
```

**Expected behavior per service:**

| Service Killed | Expected Behavior | Circuit Breaker? |
|---------------|-------------------|-----------------|
| ai-engine | Files allowed through (ALLOWED mode), no transfer failure | Yes — graceful |
| analytics | Empty/cached analytics, transfers continue | Yes — graceful |
| notification-service | Notifications queued in RabbitMQ, not lost | Yes — graceful |
| keystore-manager | Local key fallback activates | Yes — graceful |
| license-service | 24h cached license continues working | Yes — graceful |
| screening-service | **FAIL FAST** — transfers blocked (compliance service) | No — intentional |
| encryption-service | **FAIL FAST** — transfers blocked (security service) | No — intentional |
| config-service | **FAIL FAST** — services won't start without config | No — intentional |

### 10.2 RabbitMQ Chaos

```bash
./tests/perf/resilience/chaos-rabbitmq.sh
```

**Scenario:**
1. Start 500 file transfers via SFTP
2. Kill RabbitMQ after 10 seconds (mid-transfer)
3. Verify: in-flight transfers fail gracefully, messages go to DLQ
4. Restart RabbitMQ after 30 seconds
5. Verify: DLQ messages replayed, transfers complete

**Sentinel should:** Create a `dlq_growth` finding during the outage.

### 10.3 Database Chaos

```bash
./tests/perf/resilience/chaos-database.sh
```

**Scenario:**
1. Start steady load (100 users)
2. Kill PostgreSQL container
3. Measure: how quickly circuit breakers open (< 5s expected)
4. Verify: services return 503, not 500 or timeout hang
5. Restart PostgreSQL after 60 seconds
6. Measure: time to full recovery (< 30s expected)

### 10.4 Recovery Time Measurement

```bash
./tests/perf/resilience/recovery-time.sh
```

Measures **MTTR (Mean Time To Recovery)** for each service:

| Service | Kill → Circuit Open | Restart → First 200 | Full Throughput |
|---------|--------------------|--------------------|-----------------|
| onboarding-api | | | |
| sftp-service | | | |
| encryption-service | | | |
| analytics-service | | | |
| platform-sentinel | | | |

### 10.5 Memory Pressure Test

```bash
# Transfer 100 x 100MB files simultaneously — fills heap
python3 tests/perf/python/sftp_benchmark.py \
  --scenario memory-pressure \
  --concurrent 100 \
  --file-size 100MB

# Monitor JVM heap while this runs:
watch -n 2 'docker stats --no-stream --format "{{.Name}} {{.MemUsage}}" | grep mft'
```

Watch for: OOM errors, heap dumps, GC pause spikes, container restarts.

### 10.6 DLQ Flood + Sentinel Detection

```bash
# Flood the dead letter queue — Sentinel should detect and alert
python3 tests/perf/python/million_transfers.py --scenario fail-all --count 500
sleep 360  # Wait for Sentinel 5-min analysis cycle
curl -s http://localhost:8098/api/v1/sentinel/findings | jq '.[] | select(.ruleName=="dlq_growth")'
```

Expected: Sentinel creates a `dlq_growth` finding with `severity=MEDIUM`.

---

## 11. Phase 7 — Endurance / Soak Test (Optional — 24h)

> Run overnight. Detects memory leaks, connection pool exhaustion, log file growth, slow DB degradation.

```bash
# Steady 50 concurrent users for 24 hours
k6 run \
  --vus 50 \
  --duration 24h \
  --out json=tests/perf/results/soak-$(date +%Y%m%d).json \
  tests/perf/k6/07-full-pipeline.js
```

**Monitor every hour:**
```bash
# Check container memory growth
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}" | grep mft

# Check DB connection count
docker exec mft-postgres psql -U mftuser -d mftdb -c \
  "SELECT count(*) as connections, state FROM pg_stat_activity GROUP BY state;"

# Check Sentinel health score trend
curl -s "http://localhost:8098/api/v1/sentinel/health-score/history?hours=1" | \
  jq '.[-1].overallScore'
```

**Pass criteria:**
- Memory: no container grows > 20% from start to end
- Connections: DB connection count stays stable (no slow leak)
- Error rate: stays below 0.5% throughout
- Health score: stays above 70 throughout

---

## 12. Results Interpretation

### k6 Output Explained

```
✓ status is 200
✓ response time OK

checks.........................: 99.98%  ✓ 149970 ✗ 30
data_received..................: 1.2 GB  2.0 MB/s
data_sent......................: 450 MB  750 kB/s
http_req_blocked...............: avg=12µs   p(90)=21µs   p(95)=45µs
http_req_connecting............: avg=5µs    p(90)=0s     p(95)=0s
http_req_duration..............: avg=142ms  p(90)=380ms  p(95)=520ms  ← KEY METRIC
  { expected_response:true }...: avg=141ms  p(90)=379ms  p(95)=519ms
http_req_failed................: 0.02%   ✓ 149970 ✗ 30              ← ERROR RATE
http_req_receiving.............: avg=45µs
http_req_sending...............: avg=12µs
http_req_tls_handshaking.......: avg=0s
http_req_waiting...............: avg=142ms
http_reqs......................: 150000  250/s                        ← THROUGHPUT
iteration_duration.............: avg=1.14s
iterations.....................: 15000   25/s
vus............................: 100     min=100     max=100
vus_max........................: 100     min=100     max=100
```

**Focus on:**
- `http_req_duration p(95)` — 95th percentile latency (most important)
- `http_req_failed` — error rate (must be < 1% for production)
- `http_reqs/s` — throughput

### Platform Sentinel Score as Test Result

During and after any load test, check the Sentinel health score — it's a real-time composite view of platform health:

```bash
curl -s http://localhost:8098/api/v1/sentinel/health-score | \
  jq '{overall: .overallScore, infra: .infrastructureScore, data: .dataScore, security: .securityScore}'
```

| Score Range | Meaning | Action |
|-------------|---------|--------|
| 90–100 | Excellent | No issues |
| 75–89 | Good | Minor degradation, monitor |
| 50–74 | Degraded | Investigate findings |
| 25–49 | Poor | Multiple issues, immediate attention |
| 0–24 | Critical | Platform unhealthy |

---

## 13. Pass / Fail Thresholds

### HTTP Services

| Service | Metric | PASS | WARN | FAIL |
|---------|--------|------|------|------|
| All services | Availability | ≥ 99.9% | 99.0–99.9% | < 99.0% |
| onboarding-api | Login p95 | < 300ms | 300–800ms | > 800ms |
| onboarding-api | Throughput | > 500 logins/s | 200–500/s | < 200/s |
| encryption-service | 1MB p95 | < 200ms | 200–500ms | > 500ms |
| screening-service | Scan p95 | < 300ms | 300–700ms | > 700ms |
| analytics-service | Dashboard p95 | < 500ms | 500ms–1s | > 1s |
| platform-sentinel | Health score API p95 | < 200ms | 200–500ms | > 500ms |
| Full pipeline | End-to-end p95 | < 3s | 3–8s | > 8s |

### File Transfer (SFTP/FTP)

| Scenario | PASS | WARN | FAIL |
|----------|------|------|------|
| SFTP 10KB file | < 200ms | 200–500ms | > 500ms |
| SFTP 1MB file | < 2s | 2–5s | > 5s |
| SFTP 100MB file | < 30s | 30–60s | > 60s |
| SFTP 1GB file | < 5 min | 5–15 min | > 15 min |
| SFTP concurrent 50 connections | 0 errors | 1–5 errors | > 5 errors |
| SFTP 10K bulk files | > 500 files/s | 200–500/s | < 200/s |
| FTP passive 1MB | < 3s | 3–8s | > 8s |

### Resilience

| Scenario | PASS | WARN | FAIL |
|----------|------|------|------|
| Service kill → circuit open | < 5s | 5–15s | > 15s |
| Service restart → recovery | < 30s | 30–60s | > 60s |
| RabbitMQ kill → DLQ drains on restart | ✓ 100% | 95–99% | < 95% |
| DB kill → 503 (not 500/hang) | ✓ | Mix of 503/500 | Timeouts/hang |
| Memory growth (24h soak) | < 10% | 10–20% | > 20% |

### Security

| Check | PASS | FAIL |
|-------|------|------|
| No JWT → 401 | ✓ | Any other response |
| Tampered JWT → 401 | ✓ | Any other response |
| Role escalation → 403 | ✓ | Any other response |
| Path traversal → 400 | ✓ | File served |
| SQL injection → 400/sanitized | ✓ | SQL error in response |
| EICAR file → quarantined | ✓ | File accepted |
| Brute force → lockout | ✓ | Unlimited logins |
| Audit log tamper → detected | ✓ | Tamper undetected |

---

## 14. Findings Template

Copy this table into a Google Sheet or Notion. Fill in as you run each phase.

```
## TranzFer MFT Performance Test Run

Date:
Tester:
Platform version (git sha): git -C file-transfer-platform rev-parse --short HEAD
Docker Desktop RAM:
Docker Desktop CPU:
Laptop model + RAM:

### Service Health at Start
[paste output of: ./tests/perf/setup/platform-check.sh]

### Phase 1 — Smoke
Status: PASS / FAIL
Notes:

### Phase 2 — Individual Services
| Service | Profile | p95 Latency | Throughput | Error Rate | Pass/Fail | Notes |
|---------|---------|-------------|------------|------------|-----------|-------|
| onboarding-api | light | | | | | |
| onboarding-api | medium | | | | | |
| onboarding-api | heavy | | | | | |
| encryption-service | 1KB | | | | | |
| encryption-service | 1MB | | | | | |
| encryption-service | 100MB | | | | | |
| screening-service | baseline | | | | | |
| analytics-service | 100 users | | | | | |
| platform-sentinel | baseline | | | | | |
| sftp-service | small-files | | | | | |
| sftp-service | concurrent-50 | | | | | |
| sftp-service | 1GB | | | | | |
| ftp-service | baseline | | | | | |

### Phase 3 — Integration Pipelines
| Pipeline | Users | Duration | p95 E2E | Error Rate | Pass/Fail |
|----------|-------|----------|---------|------------|-----------|
| Full REST | 10 | 5m | | | |
| Full REST | 100 | 15m | | | |
| Full REST | 500 | 30m | | | |
| SFTP+Gateway | 50 | 10m | | | |
| All protocols concurrent | - | 10m | | | |

### Phase 4 — Volume
| Volume | Duration | Throughput (files/s) | p95 | Error Rate | Sentinel Score |
|--------|----------|---------------------|-----|------------|----------------|
| 10K | | | | | |
| 100K | | | | | |
| 1M | | | | | |
Max concurrent connections before degradation:
Large file (1GB) throughput:
Large file (5GB) throughput:

### Phase 5 — Security
| Test | Expected | Actual | Pass/Fail |
|------|----------|--------|-----------|
| No JWT | 401 | | |
| Tampered JWT | 401 | | |
| Role escalation | 403 | | |
| Brute force lockout | Locked after N attempts | | |
| Path traversal | 400 | | |
| SQL injection | 400 | | |
| EICAR file | Quarantined | | |
| Audit log tamper | Detected | | |
| TLS 1.0 rejected | Connection refused | | |

### Phase 6 — Resilience
| Scenario | Circuit Opens In | Recovery Time | Findings Created | Pass/Fail |
|----------|-----------------|---------------|-----------------|-----------|
| Kill ai-engine | | | | |
| Kill analytics | | | | |
| Kill keystore | | | | |
| Kill screening | (fail-fast expected) | | | |
| Kill RabbitMQ | | | DLQ finding? | |
| Kill PostgreSQL | | | | |

### Phase 7 — Endurance (if run)
Duration:
Start memory:
End memory:
Memory growth %:
Avg error rate:
DB connection count stable:
Sentinel health score at end:

### Overall Verdict
Speed:    PASS / WARN / FAIL
Security: PASS / WARN / FAIL
Stability: PASS / WARN / FAIL

### Top 5 Findings (ranked by severity)
1.
2.
3.
4.
5.
```

---

## 15. Quick Reference — All Commands

```bash
# ── SETUP ──────────────────────────────────────────────────────────────────
./tests/perf/setup/prereqs.sh                    # Install k6, Python deps
./tests/perf/setup/platform-check.sh             # Verify all services UP

# ── PLATFORM ───────────────────────────────────────────────────────────────
docker compose up -d                             # Start everything
docker compose logs mft-<name> --tail=50         # Check service logs
docker stats --no-stream | grep mft              # Resource usage snapshot

# ── SMOKE ──────────────────────────────────────────────────────────────────
k6 run tests/perf/k6/01-smoke.js

# ── INDIVIDUAL SERVICE TESTS ───────────────────────────────────────────────
k6 run --env PROFILE=light   tests/perf/k6/02-onboarding.js
k6 run --env PROFILE=medium  tests/perf/k6/02-onboarding.js
k6 run --env PROFILE=heavy   tests/perf/k6/02-onboarding.js
k6 run --env FILE_SIZE=1KB   tests/perf/k6/03-encryption.js
k6 run --env FILE_SIZE=1MB   tests/perf/k6/03-encryption.js
k6 run --env FILE_SIZE=100MB tests/perf/k6/03-encryption.js
k6 run tests/perf/k6/04-screening.js
k6 run tests/perf/k6/05-analytics.js
k6 run tests/perf/k6/06-sentinel.js
python3 tests/perf/python/sftp_benchmark.py --scenario small-files
python3 tests/perf/python/sftp_benchmark.py --scenario concurrent --connections 50
python3 tests/perf/python/sftp_benchmark.py --scenario large-file --size 1GB
python3 tests/perf/python/ftp_benchmark.py   --scenario concurrent --connections 30

# ── INTEGRATION PIPELINES ──────────────────────────────────────────────────
k6 run --env PROFILE=light  tests/perf/k6/07-full-pipeline.js
k6 run --env PROFILE=medium tests/perf/k6/07-full-pipeline.js
k6 run --env PROFILE=heavy  tests/perf/k6/07-full-pipeline.js

# ── VOLUME ─────────────────────────────────────────────────────────────────
python3 tests/perf/python/million_transfers.py --count 10000  --workers 20
python3 tests/perf/python/million_transfers.py --count 100000 --workers 50
python3 tests/perf/python/million_transfers.py --count 1000000 --workers 100

# ── SECURITY ───────────────────────────────────────────────────────────────
k6 run tests/perf/k6/08-security.js
openssl s_client -connect localhost:9443 -tls1    # Should FAIL
openssl s_client -connect localhost:9443 -tls1_2  # Should PASS

# ── RESILIENCE ─────────────────────────────────────────────────────────────
./tests/perf/resilience/kill-service.sh --service ai-engine --duration 60
./tests/perf/resilience/chaos-rabbitmq.sh
./tests/perf/resilience/chaos-database.sh
./tests/perf/resilience/recovery-time.sh

# ── SENTINEL MONITORING (during any test) ──────────────────────────────────
watch -n 10 'curl -s http://localhost:8098/api/v1/sentinel/health-score | jq .overallScore'
curl -s "http://localhost:8098/api/v1/sentinel/findings?status=OPEN" | jq 'length'
curl -s "http://localhost:8098/api/v1/sentinel/dashboard" | jq .

# ── ENDURANCE (24h) ────────────────────────────────────────────────────────
k6 run --vus 50 --duration 24h \
  --out json=tests/perf/results/soak-$(date +%Y%m%d).json \
  tests/perf/k6/07-full-pipeline.js

# ── RUN EVERYTHING (generates full report) ─────────────────────────────────
./tests/perf/run-all.sh
./tests/perf/run-all.sh --skip-volume    # Skip phase 4 (faster)
./tests/perf/run-all.sh --skip-endurance # Skip 24h soak
```

---

*Generated for TranzFer MFT Platform — 20 services, 3 protocols, production-grade validation*
*Speed · Security · Stability*

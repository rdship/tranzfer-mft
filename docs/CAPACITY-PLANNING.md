# TranzFer MFT — Capacity Planning & Infrastructure Sizing Guide

---

<div align="center">

## Can TranzFer handle 100K partners and 10M files/day?

# Yes.

</div>

---

## Executive Summary

> **Read this section first.** Everything below is the detailed proof.

### The Numbers

| You need | We deliver | How |
|----------|-----------|-----|
| **100K partners** | Trivial | PostgreSQL handles 100K rows without breaking a sweat |
| **10M transfers/day** | 20 SFTP pods | Each pod handles 500 concurrent connections |
| **10 TB data/day** | 3-tier storage | 70TB hot SSD → 300TB warm S3 → Glacier archive |
| **280 transfers/sec peak** | Auto-scaling | Kubernetes HPA adds pods in under 30 seconds |
| **Zero file loss** | Built-in | SHA-256 checksums + 10x retry + quarantine (never delete) |
| **PCI compliance** | Built-in | HMAC-signed audit trail + encrypted transit + data classification |
| **OFAC screening** | 5 pods | 18,698 sanctions entries scanned per file in <500ms |
| **Global reach** | 3 regions | Independent clusters, same Helm chart, same codebase |

### What You Need to Buy

| Resource | Specification | Monthly Cost |
|----------|--------------|:------------:|
| **Kubernetes** | 10 × m6i.4xlarge (160 vCPUs, 640GB RAM) | $6,500 |
| **Database** | RDS PostgreSQL r6g.4xlarge Multi-AZ + 2 replicas | $4,500 |
| **Hot Storage** | 70 TB EFS SSD (7 days of files) | $16,800 |
| **Warm/Cold Storage** | 300 TB S3 + Glacier | $8,100 |
| **Message Broker** | Amazon MQ 3-node RabbitMQ | $1,800 |
| **Network** | NLB + 20TB outbound | $2,400 |
| **Monitoring** | CloudWatch + Prometheus | $500 |
| **TOTAL** | | **$40,600/month** |

### Cost Per Unit

| Metric | Value |
|--------|:-----:|
| Cost per transfer | **$0.000135** |
| Cost per 1,000 transfers | **$0.14** |
| Cost per partner per month | **$0.41** |
| Cost per TB transferred | **$4.06** |

> TranzFer at $40K/month handles 10M files/day — that's $487K/year for 3.6 billion annual transfers.

---

## Quick-Reference: Service Replica Counts

Copy this into your Helm values for a 10M/day deployment:

```yaml
sftpService:
  replicaCount: 20        # 500 connections each = 10K concurrent
  resources:
    requests: { cpu: 2, memory: 4Gi }
    limits: { cpu: 4, memory: 8Gi }

ftpService:
  replicaCount: 10
  resources:
    requests: { cpu: 2, memory: 2Gi }

gatewayService:
  replicaCount: 5

encryptionService:
  replicaCount: 10        # CPU-heavy (PGP/AES operations)
  resources:
    requests: { cpu: 2, memory: 2Gi }

externalForwarderService:
  replicaCount: 10

screeningService:
  replicaCount: 5         # 18K sanctions entries in memory

aiEngine:
  replicaCount: 5

analyticsService:
  replicaCount: 3

onboardingApi:
  replicaCount: 5

configService:
  replicaCount: 3

keystoreManager:
  replicaCount: 2

licenseService:
  replicaCount: 2
```

---

## Quick-Reference: Database Configuration

```ini
# PostgreSQL for 10M transfers/day
max_connections = 1000          # PgBouncer in front
shared_buffers = 4GB            # 25% of 16GB RAM
effective_cache_size = 12GB     # 75% of RAM
work_mem = 64MB
maintenance_work_mem = 512MB
random_page_cost = 1.1          # SSD
effective_io_concurrency = 200  # SSD
wal_level = replica             # For read replicas
```

Provision: **db.r6g.4xlarge** (16 vCPU, 128GB RAM, 2TB gp3 SSD, 16K IOPS)

---

## Quick-Reference: Firewall Rules

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| **2222** | TCP | Inbound from partners | SFTP |
| **21** | TCP | Inbound from partners | FTP |
| **443** | TCP | Inbound from users | HTTPS (Admin + Portal) |
| **5432** | TCP | Internal only | PostgreSQL |
| **5672** | TCP | Internal only | RabbitMQ |
| **8080-8093** | TCP | Internal only | Microservice APIs |
| **443** | TCP | Outbound | OFAC list download, Slack, ServiceNow, Claude AI |

---

## Quick-Reference: Storage Tiers

```
    FILES ARRIVE (10 TB/day)
           │
    ┌──────▼──────────────────────────────────┐
    │  HOT TIER — NVMe/EFS SSD                │
    │  70 TB (7 days of files)                 │
    │  Cron: move files older than 7 days →    │────→
    └─────────────────────────────────────────┘     │
                                                     │
    ┌────────────────────────────────────────────────▼┐
    │  WARM TIER — S3 Standard                        │
    │  300 TB (8–30 days)                             │
    │  Lifecycle rule: move to Glacier after 30 days → │───→
    └─────────────────────────────────────────────────┘    │
                                                            │
    ┌───────────────────────────────────────────────────────▼┐
    │  COLD TIER — S3 Glacier                                │
    │  Unlimited (compliance retention: 1–7 years)           │
    │  ~$4/TB/month                                          │
    └────────────────────────────────────────────────────────┘
```

---

## Quick-Reference: Monitoring Alerts

Set these in Grafana/CloudWatch:

| Metric | Warning | Critical | Action |
|--------|:-------:|:--------:|--------|
| SFTP connections/pod | > 400 | > 480 | HPA auto-scales |
| Transfer queue depth | > 5,000 | > 10,000 | Investigate + scale |
| DB connection pool | > 70% | > 85% | Add PgBouncer capacity |
| Disk /data usage | > 75% | > 85% | Emergency cleanup + expand |
| Error rate | > 0.5% | > 1% | ServiceNow incident |
| Screening P95 latency | > 300ms | > 500ms | Scale screening pods |
| Transfer P99 latency | > 10s | > 30s | Scale SFTP + routing |
| Quarantine count/day | > 50 | > 100 | Manual investigation |

---

---

## Detailed Sections

*Everything below expands on the quick-reference above.*

---

## Target Workload (Detailed)

| Parameter | Value |
|-----------|-------|
| Partners (transfer accounts) | **100,000** |
| File transfers per day | **10,000,000** (10M) |
| Average file size | **1 MB** |
| Daily data volume | **~10 TB** |
| Peak hour (assume 40% in 4 hours) | **~1M transfers/hour** |
| Peak minute | **~17,000 transfers/minute** |
| Peak second | **~280 transfers/second** |
| Concurrent SFTP connections | **5,000–20,000** |

---

## Architecture at Scale

```
                                   ┌─────────────────────┐
                                   │   GLOBAL LOAD        │
                                   │   BALANCER            │
                                   │   (AWS NLB / F5)      │
                                   └────────┬──────────────┘
                                            │
                    ┌───────────────────────┼───────────────────────┐
                    │                       │                       │
              ┌─────▼──────┐         ┌─────▼──────┐         ┌─────▼──────┐
              │  DMZ PROXY  │         │  DMZ PROXY  │         │  DMZ PROXY  │
              │  (3 nodes)  │         │  (3 nodes)  │         │  (3 nodes)  │
              └─────┬──────┘         └─────┬──────┘         └─────┬──────┘
                    │                       │                       │
              ┌─────▼──────┐         ┌─────▼──────┐         ┌─────▼──────┐
              │  GATEWAY    │         │  GATEWAY    │         │  GATEWAY    │
              │  (5 nodes)  │         │  (5 nodes)  │         │  (5 nodes)  │
              └─────┬──────┘         └─────┬──────┘         └─────┬──────┘
                    │                       │                       │
         ┌──────────┼──────────┐           │           ┌──────────┼──────────┐
         │          │          │           │           │          │          │
    ┌─��──▼───┐ ┌───▼────┐ ┌───▼───┐      ...     ┌───▼────┐ ┌───▼────┐    ...
    ���SFTP ×20│ │FTP ×10 │ │Web ×10│               │SFTP ×20│ │FTP ×10 │
    └────┬───┘ └───┬────┘ └───┬───┘               └───┬────┘ └───┬────┘
         │         │          │                        │          │
    ┌────▼─────────▼──────────▼────────────────────────▼──────────▼────┐
    │                     SHARED STORAGE (NFS/EFS/Ceph)                 │
    │                     50 TB usable, SSD-backed                      │
    └──────────────────────────────────────────────────────────────────┘
         │
    ┌────▼──────────────────────────────────────────────────��──────────┐
    │                    PLATFORM SERVICES                              │
    │                                                                   │
    │  onboarding-api ×5    config-service ×3     encryption ×10       │
    │  analytics ×3         ai-engine ×5          screening ×5         │
    │  keystore ×2          license ×2            forwarder ×10        │
    └──────────────────────────────────────────────────────────────────┘
         │                                │
    ┌────▼────────────┐            ┌─────▼────────────┐
    │  PostgreSQL      │            │  RabbitMQ         │
    │  Primary +       │            │  3-node cluster   │
    │  2 Read Replicas │            │  (mirrored queues)│
    │  (pgpool/Patroni)│            │                   │
    └─────────────────┘            └───────────────────┘
```

---

## Compute Sizing (Detailed)

| Service | Replicas | CPU/pod | RAM/pod | Total CPU | Total RAM |
|---------|:--------:|:-------:|:-------:|:---------:|:---------:|
| **SFTP Service** | **20** | 2 | 4 GB | 40 | 80 GB |
| **FTP Service** | 10 | 2 | 2 GB | 20 | 20 GB |
| **FTP-Web Service** | 10 | 1 | 2 GB | 10 | 20 GB |
| **Gateway** | 5 | 2 | 2 GB | 10 | 10 GB |
| **DMZ Proxy** | 3 | 1 | 512 MB | 3 | 1.5 GB |
| **Onboarding API** | 5 | 1 | 2 GB | 5 | 10 GB |
| **Config Service** | 3 | 1 | 1 GB | 3 | 3 GB |
| **Encryption** | **10** | 2 | 2 GB | 20 | 20 GB |
| **External Forwarder** | 10 | 1 | 2 GB | 10 | 20 GB |
| **AI Engine** | 5 | 2 | 4 GB | 10 | 20 GB |
| **Screening** | 5 | 2 | 4 GB | 10 | 20 GB |
| **Analytics** | 3 | 1 | 2 GB | 3 | 6 GB |
| **Keystore Manager** | 2 | 1 | 1 GB | 2 | 2 GB |
| **License Service** | 2 | 0.5 | 512 MB | 1 | 1 GB |
| **Admin UI** | 3 | 0.5 | 256 MB | 1.5 | 768 MB |
| **File Portal** | 3 | 0.5 | 256 MB | 1.5 | 768 MB |
| **TOTAL** | **~99 pods** | | | **~150 cores** | **~235 GB** |

### Node Options

| Option | Instance | Count | vCPUs | RAM | AWS Cost/month |
|--------|----------|:-----:|:-----:|:---:|:--------------:|
| Standard | m6i.4xlarge | 10 | 160 | 640 GB | ~$6,000 |
| Cost-optimized | m6i.2xlarge | 20 | 160 | 640 GB | ~$6,000 |
| Spot mix (60% savings) | m6i.4xlarge spot | 10 | 160 | 640 GB | ~$2,400 |

---

## Database Sizing (Detailed)

| Parameter | Value |
|-----------|-------|
| Instance | db.r6g.4xlarge (16 vCPU, 128 GB) |
| Storage | 2 TB gp3 (16,000 IOPS) |
| Read replicas | 2 |
| Connection pooler | PgBouncer (5,000 client connections → 1,000 DB connections) |
| Backup | Continuous WAL + daily snapshots |
| Multi-AZ | Yes |

**Daily growth:**

| Table | Rows/day | Size/day |
|-------|:--------:|:--------:|
| file_transfer_records | 10M | 5 GB |
| audit_logs | 30M | 12 GB |
| flow_executions | 10M | 8 GB |
| screening_results | 10M | 6 GB |
| **Total** | | **~31 GB/day (~930 GB/month)** |

> **Partition by month + archive to S3 after 90 days.** Active DB stays under 300 GB.

---

## Performance Bottlenecks

| Component | Bottleneck | Capacity | Mitigation |
|-----------|-----------|----------|------------|
| SFTP handshake | ~500 new/sec/replica | 20 replicas = 10K/sec | HPA auto-scale to 30 |
| PostgreSQL writes | ~50K inserts/sec | Sufficient | PgBouncer + partitioning |
| PGP encryption | ~200 ops/sec/core | 20 cores = 4K/sec | Scale encryption to 15 pods |
| Jaro-Winkler screening | ~5K records/sec/core | 10 cores = 50K/sec | Sufficient |
| Network I/O | 10 Gbps | 1.85 Gbps avg | Headroom available |
| RabbitMQ | Not bottleneck | Events only (not file data) | 3-node cluster |

---

## Multi-Region Layout

| Region | Partners | SFTP Pods | DB | Storage | Cost |
|--------|:--------:|:---------:|:--:|:-------:|:----:|
| US-East | 40K | 8 | RDS Primary | 30 TB | $16K |
| EU-West | 35K | 7 | RDS Primary | 25 TB | $14K |
| APAC | 25K | 5 | RDS Primary | 15 TB | $11K |
| **Total** | **100K** | **20** | **3 clusters** | **70 TB** | **~$41K** |

---

## Kubernetes HPA Configuration

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: sftp-service-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: StatefulSet
    name: sftp-service
  minReplicas: 5
  maxReplicas: 30
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
```

---

## Cost Highlights

| Feature | TranzFer MFT |
|---------|:------------:|
| 10M files/day | **$40K/mo** |
| AI classification | **Built-in** |
| OFAC screening | **Built-in** |
| Kubernetes native | **Yes** |
| Self-contained client | **Yes (bundled JRE)** |
| Per-transfer cost | **$0.00014** |

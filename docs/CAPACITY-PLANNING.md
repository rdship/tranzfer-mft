# TranzFer MFT — Capacity Planning & Infrastructure Sizing Guide

## Target Workload

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
    ┌────▼───┐ ┌───▼────┐ ┌───▼───┐      ...     ┌───▼────┐ ┌───▼────┐    ...
    │SFTP ×20│ │FTP ×10 │ │Web ×10│               │SFTP ×20│ │FTP ×10 │
    └────┬───┘ └───┬────┘ └───┬───┘               └───┬────┘ └───┬────┘
         │         │          │                        │          │
    ┌────▼─────────▼──────────▼────────────────────────▼──────────▼────┐
    │                     SHARED STORAGE (NFS/EFS/Ceph)                 │
    │                     50 TB usable, SSD-backed                      │
    └──────────────────────────────────────────────────────────────────┘
         │
    ┌────▼─────────────────────────────────────────────────────────────┐
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

## Infrastructure Sizing

### Compute (Kubernetes)

| Service | Replicas | CPU/pod | RAM/pod | Total CPU | Total RAM | Why |
|---------|:--------:|:-------:|:-------:|:---------:|:---------:|-----|
| **SFTP Service** | **20** | 2 cores | 4 GB | 40 cores | 80 GB | Each handles ~500 concurrent SSH sessions |
| **FTP Service** | 10 | 2 cores | 2 GB | 20 cores | 20 GB | FTP is lighter than SFTP |
| **FTP-Web Service** | 10 | 1 core | 2 GB | 10 cores | 20 GB | HTTP file uploads |
| **Gateway** | 5 | 2 cores | 2 GB | 10 cores | 10 GB | Protocol routing (Netty, low memory) |
| **DMZ Proxy** | 3 | 1 core | 512 MB | 3 cores | 1.5 GB | Stateless TCP proxy |
| **Onboarding API** | 5 | 1 core | 2 GB | 5 cores | 10 GB | Auth, account CRUD |
| **Config Service** | 3 | 1 core | 1 GB | 3 cores | 3 GB | Flow + config management |
| **Encryption** | 10 | 2 cores | 2 GB | 20 cores | 20 GB | CPU-intensive (PGP/AES) |
| **External Forwarder** | 10 | 1 core | 2 GB | 10 cores | 20 GB | Outbound SFTP/FTP connections |
| **AI Engine** | 5 | 2 cores | 4 GB | 10 cores | 20 GB | Classification + anomaly |
| **Screening** | 5 | 2 cores | 4 GB | 10 cores | 20 GB | 18K+ sanctions entries in memory |
| **Analytics** | 3 | 1 core | 2 GB | 3 cores | 6 GB | Metric aggregation |
| **Keystore Manager** | 2 | 1 core | 1 GB | 2 cores | 2 GB | Key operations (low volume) |
| **License Service** | 2 | 0.5 core | 512 MB | 1 core | 1 GB | License validation |
| **Admin UI** | 3 | 0.5 core | 256 MB | 1.5 cores | 768 MB | Static React (nginx) |
| **File Portal** | 3 | 0.5 core | 256 MB | 1.5 cores | 768 MB | Static React (nginx) |

**Total Compute:**

| Resource | Amount |
|----------|--------|
| **Total pods** | ~99 |
| **Total CPU** | ~150 cores |
| **Total RAM** | ~235 GB |
| **Kubernetes nodes** | 8–12 nodes (m6i.4xlarge or equivalent) |

### Kubernetes Node Sizing

| Option | Node Type | Nodes | vCPUs | RAM | Cost (AWS, on-demand) |
|--------|-----------|:-----:|:-----:|:---:|----------------------|
| **Standard** | m6i.4xlarge | 10 | 160 | 640 GB | ~$6,000/month |
| **Cost-optimized** | m6i.2xlarge | 20 | 160 | 640 GB | ~$6,000/month |
| **High-memory** | r6i.2xlarge | 12 | 96 | 768 GB | ~$5,500/month |

> Use **Spot instances** for non-critical services (analytics, AI, screening) to save 60–70%.

---

### Database (PostgreSQL)

| Parameter | Recommendation |
|-----------|---------------|
| **Instance** | AWS RDS db.r6g.4xlarge (16 vCPU, 128 GB RAM) |
| **Storage** | 2 TB gp3 SSD (16,000 IOPS provisioned) |
| **Read replicas** | 2 (for analytics queries and read-heavy loads) |
| **Connection pooling** | PgBouncer in front (max 5,000 connections) |
| **Max connections** | 1,000 (per instance, PgBouncer multiplexes) |
| **Backup** | Continuous WAL archiving + daily snapshots |
| **Multi-AZ** | Yes (automatic failover) |

**Daily DB growth estimate:**

| Table | Rows/day | Row size | Daily growth |
|-------|:--------:|:--------:|:------------:|
| file_transfer_records | 10M | ~500 bytes | **5 GB** |
| audit_logs | 30M (3 per transfer) | ~400 bytes | **12 GB** |
| flow_executions | 10M | ~800 bytes | **8 GB** |
| screening_results | 10M | ~600 bytes | **6 GB** |
| **Total daily DB growth** | | | **~31 GB** |
| **Monthly DB growth** | | | **~930 GB** |

> **Retention strategy**: Partition tables by month. Archive to S3 after 90 days.
> Active DB stays under 300 GB with partitioning.

```sql
-- Example: partition file_transfer_records by month
CREATE TABLE file_transfer_records (
    ...
    uploaded_at TIMESTAMP NOT NULL
) PARTITION BY RANGE (uploaded_at);

CREATE TABLE ftr_2025_01 PARTITION OF file_transfer_records
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');
```

---

### Message Broker (RabbitMQ)

| Parameter | Recommendation |
|-----------|---------------|
| **Cluster** | 3-node mirrored cluster |
| **Instance** | 4 vCPU, 16 GB RAM per node |
| **Disk** | 100 GB SSD per node |
| **Peak messages/sec** | ~1,000 (account events, routing events) |
| **Queue depth** | < 10,000 (alert if higher) |

> RabbitMQ handles account sync events, NOT file data.
> Files transfer via SFTP/FTP directly — MQ load is manageable.

---

### Storage (File Data)

| Parameter | Value |
|-----------|-------|
| **Daily volume** | 10 TB (10M files × 1 MB) |
| **Retention** | 7 days hot, 30 days warm, archive after |
| **Hot storage** | 70 TB NVMe/SSD (7 days × 10 TB) |
| **Warm storage** | 300 TB HDD/S3 (30 days) |
| **Archive** | S3 Glacier / tape (unlimited) |

**Storage architecture:**

```
┌─────────────────────────────────────────────┐
│  HOT TIER (NFS/EFS with SSD)                 │
│  /data/sftp, /data/ftp                       │
│  70 TB — last 7 days                         │
│  Auto-cleanup: cron moves files > 7d to warm │
├─────────────────────────────────────────────┤
│  WARM TIER (S3 Standard / NFS HDD)           │
│  /archive — 8 to 30 days                     │
│  300 TB                                      │
├─────────────────────────────────────────────┤
│  COLD TIER (S3 Glacier / Tape)               │
│  30+ days — compliance retention             │
│  Unlimited, ~$4/TB/month                     │
└─────────────────────────────────────────────┘
```

| Cloud | Hot (70TB SSD) | Warm (300TB) | Cold | Total/month |
|-------|:--------------:|:------------:|:----:|:-----------:|
| AWS | EFS: $16,800 | S3: $6,900 | Glacier: $1,200 | ~$25,000 |
| Azure | Files Premium: $14,000 | Blob Hot: $6,000 | Blob Cool: $900 | ~$21,000 |
| GCP | Filestore: $15,000 | GCS: $6,000 | Nearline: $1,000 | ~$22,000 |

---

### Network

| Parameter | Requirement |
|-----------|------------|
| **Bandwidth** | 10 Gbps minimum (burst to 25 Gbps) |
| **Daily throughput** | 10 TB inbound + 10 TB outbound = 20 TB |
| **Sustained rate** | ~1.85 Gbps average (peaks to 5+ Gbps) |
| **Load balancer** | AWS NLB (Network Load Balancer) — handles millions of concurrent TCP connections |
| **DNS** | Route 53 with health checks + failover |
| **Inter-AZ traffic** | Keep SFTP service + storage in same AZ to minimize cross-AZ costs |

---

## Performance Bottleneck Analysis

| Component | Bottleneck at | Mitigation |
|-----------|:------------:|------------|
| **SFTP SSH handshake** | ~500 new connections/sec/replica | Scale to 20+ replicas |
| **PostgreSQL writes** | ~50K inserts/sec | PgBouncer + partitioned tables + read replicas |
| **File I/O** | Disk throughput | NVMe SSDs, separate mount for /data |
| **Encryption (PGP)** | ~200 ops/sec/core | 10 encryption replicas (20 cores) |
| **Screening (Jaro-Winkler)** | ~5K records/sec/core | 5 replicas with 18K entries in memory |
| **RabbitMQ** | Not a bottleneck (events only, not file data) | 3-node cluster is more than enough |
| **AI classification** | ~1K files/sec (regex scanning) | 5 replicas, skip binary files |

---

## Kubernetes HPA Configuration

```yaml
# SFTP Service — auto-scale based on CPU
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
    - type: Pods
      pods:
        metric:
          name: sftp_active_connections
        target:
          type: AverageValue
          averageValue: "400"

# Encryption — auto-scale on CPU (compute-heavy)
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: encryption-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: encryption-service
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

---

## Multi-Region for Global Scale

For 100K partners across regions:

| Region | Partners | SFTP Replicas | DB | Storage |
|--------|:--------:|:-------------:|:--:|:-------:|
| US-East | 40K | 8 | RDS Primary | EFS 30TB |
| EU-West | 35K | 7 | RDS Primary | EFS 25TB |
| APAC | 25K | 5 | RDS Primary | EFS 15TB |

Each region is **fully independent** (own DB, own storage, own MQ).
Set `CLUSTER_ID=us-east-1`, `eu-west-1`, `apac-1`.

---

## Cost Estimate (Full 10M/day Deployment)

### AWS (US-East)

| Component | Specification | Monthly Cost |
|-----------|--------------|:------------:|
| **Kubernetes (EKS)** | 10 × m6i.4xlarge + EKS fee | ~$6,500 |
| **Database (RDS)** | db.r6g.4xlarge Multi-AZ + 2 read replicas | ~$4,500 |
| **Storage (EFS)** | 70 TB provisioned throughput | ~$16,800 |
| **Storage (S3)** | 300 TB Standard + Glacier | ~$8,100 |
| **RabbitMQ (Amazon MQ)** | mq.m5.2xlarge 3-node | ~$1,800 |
| **Load Balancer (NLB)** | 20 TB/month throughput | ~$700 |
| **Data Transfer** | 20 TB outbound | ~$1,700 |
| **Monitoring** | CloudWatch, Prometheus | ~$500 |
| **Total** | | **~$40,600/month** |

### Cost per Transfer

| Metric | Value |
|--------|-------|
| Monthly transfers | 300M |
| Monthly cost | $40,600 |
| **Cost per transfer** | **$0.000135** |
| **Cost per 1,000 transfers** | **$0.14** |
| **Cost per partner/month** | **$0.41** |

---

## Monitoring at Scale

### Key Metrics to Watch

| Metric | Threshold | Action |
|--------|-----------|--------|
| SFTP active connections | > 400/pod | HPA scales up |
| Transfer queue depth | > 10,000 | Alert + investigate |
| DB connection pool usage | > 80% | Add PgBouncer capacity |
| Disk usage /data | > 80% | Trigger cleanup job |
| Error rate | > 1% | Alert to ServiceNow |
| Screening latency P95 | > 500ms | Scale screening pods |
| File transfer latency P99 | > 30s | Scale SFTP + routing |
| Quarantine count | > 100/day | Investigation needed |

### Grafana Dashboards

Deploy with the `monitoring.enabled=true` Helm flag:

```bash
helm install mft ./helm/mft-platform \
  --set monitoring.enabled=true \
  --set monitoring.grafana.enabled=true
```

All services expose `/actuator/prometheus` for Prometheus scraping.

---

## Summary: Can TranzFer Handle 10M Files/Day?

**Yes.** Here's what's needed:

| What | How |
|------|-----|
| **100K partners** | PostgreSQL handles 100K rows in transfer_accounts trivially |
| **10M transfers/day** | 20 SFTP replicas × 500 connections each = 10K concurrent |
| **10 TB data/day** | 70 TB NVMe hot storage + S3 tiering |
| **280 transfers/sec peak** | Horizontally scale all services via HPA |
| **PCI compliance** | Audit logs + checksums + HMAC (already built) |
| **OFAC screening** | 5 replicas screening in parallel (18K entries in memory) |
| **Zero file loss** | Guaranteed delivery + quarantine + retry (already built) |
| **Global deployment** | 3 regions, independent clusters, same Helm chart |
| **Total cost** | **~$40K/month** ($0.14 per 1,000 transfers) |

The architecture is already stateless and horizontally scalable.
The only infrastructure work is provisioning the right number of nodes
and configuring shared storage.

# TranzFer MFT — Kubernetes Deployment

Complete Kubernetes manifests for production deployment. All 22 microservices,
infrastructure (PostgreSQL, RabbitMQ, Redis), storage, ingress, auto-scaling,
network policies, and SPIFFE/SPIRE service identity.

---

## Directory structure

```
k8s/
├── namespace.yaml              Namespace: mft
├── configmap.yaml              Shared non-secret config (all services)
├── secrets.yaml                TEMPLATE — replace with real values or External Secrets
├── storage/
│   ├── storageclass.yaml       RWX StorageClass options (EFS/Filestore/Azure Files/NFS)
│   └── pvcs.yaml               All PVCs — storage-manager uses ReadWriteMany
├── infra/
│   ├── postgres.yaml           PostgreSQL StatefulSet
│   ├── rabbitmq.yaml           RabbitMQ StatefulSet
│   └── redis.yaml              Redis Deployment (AOF persistence)
├── services/
│   ├── storage-manager.yaml    *** RWX PVC — shares storage across all replicas ***
│   ├── onboarding-api.yaml     Representative stateless service (template for others)
│   ├── all-services.yaml       All other Spring Boot services
│   └── dmz-proxy.yaml          Two groups: internal (ClusterIP) + external (LoadBalancer)
├── ingress/
│   └── ingress.yaml            HTTPS ingress for UI + API + partner portal
├── autoscaling/
│   └── hpa.yaml                HorizontalPodAutoscalers for key services
├── network/
│   └── network-policy.yaml     Zero-trust network policies
└── spiffe/
    ├── spire-server.yaml       SPIRE Server StatefulSet
    └── spire-agent.yaml        SPIRE Agent DaemonSet (one per node)
```

---

## Prerequisites

```bash
# 1. Kubernetes cluster (EKS / GKE / AKS / on-prem)
kubectl version

# 2. Metrics server (for HPA)
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

# 3. cert-manager (for TLS)
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml

# 4. nginx-ingress-controller
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm install ingress-nginx ingress-nginx/ingress-nginx

# 5. StorageClass for ReadWriteMany (choose one):
#    AWS EKS:    EFS CSI driver → use efs-rwx storageClass
#    GKE:        Filestore CSI driver → use filestore-rwx storageClass
#    AKS:        Azure Files (built-in) → use azurefile-rwx storageClass
#    On-prem:    NFS provisioner → use nfs-rwx storageClass
```

---

## Deploy — step by step

### Step 1: Namespace + infrastructure

```bash
kubectl apply -f k8s/namespace.yaml

# Create secrets FIRST (fill in real values before applying)
# See secrets.yaml for required keys
kubectl apply -f k8s/secrets.yaml

kubectl apply -f k8s/configmap.yaml
```

### Step 2: StorageClass + PVCs

```bash
# Edit storageclass.yaml to uncomment/configure the right StorageClass for your cloud
kubectl apply -f k8s/storage/storageclass.yaml

# Edit pvcs.yaml: change storageClassName on storage-manager-pvc to match your cloud
# (efs-rwx / filestore-rwx / azurefile-rwx / nfs-rwx)
kubectl apply -f k8s/storage/pvcs.yaml

# Verify PVCs are Bound before continuing
kubectl get pvc -n mft
```

### Step 3: SPIFFE/SPIRE (service identity)

```bash
kubectl apply -f k8s/spiffe/spire-server.yaml
kubectl apply -f k8s/spiffe/spire-agent.yaml

# Wait for SPIRE agent to be Running on all nodes
kubectl rollout status daemonset/spire-agent -n mft
```

### Step 4: Infrastructure services

```bash
kubectl apply -f k8s/infra/postgres.yaml
kubectl apply -f k8s/infra/rabbitmq.yaml
kubectl apply -f k8s/infra/redis.yaml

# Wait for all to be ready
kubectl wait --for=condition=ready pod -l app=postgres  -n mft --timeout=120s
kubectl wait --for=condition=ready pod -l app=rabbitmq  -n mft --timeout=120s
kubectl wait --for=condition=ready pod -l app=redis     -n mft --timeout=60s
```

### Step 5: Platform services

```bash
# Storage manager first (other services depend on it for VFS)
kubectl apply -f k8s/services/storage-manager.yaml
kubectl rollout status deployment/storage-manager -n mft

# All other services
kubectl apply -f k8s/services/onboarding-api.yaml
kubectl apply -f k8s/services/all-services.yaml
kubectl apply -f k8s/services/dmz-proxy.yaml
```

### Step 6: Frontend + ingress

```bash
# Edit ingress.yaml: replace mft.example.com with your real domain
kubectl apply -f k8s/ingress/ingress.yaml
```

### Step 7: Auto-scaling + network policies

```bash
kubectl apply -f k8s/autoscaling/hpa.yaml
kubectl apply -f k8s/network/network-policy.yaml
```

---

## Storage: why ReadWriteMany matters

| Access mode  | Docker equivalent   | Multi-replica safe? |
|---|---|---|
| ReadWriteOnce (RWO) | Named volume on one node | NO — each pod isolated |
| **ReadWriteMany (RWX)** | **NFS/shared mount** | **YES — all pods share** |

The storage-manager PVC uses `ReadWriteMany`. Every replica mounts `/data/storage`
from the same EFS/Filestore/NFS share. Pod A writes `{sha256}.bin` → Pod B reads it
immediately. The Redis location registry becomes a no-op.

**Alternative: S3/MinIO backend**

If you prefer object storage over shared filesystem:

```bash
# Start MinIO (production: use MinIO Operator with proper replication)
kubectl apply -f k8s/infra/minio.yaml  # if you add this

# Switch storage-manager to S3 backend
kubectl set env deployment/storage-manager STORAGE_BACKEND=s3 -n mft
kubectl set env deployment/storage-manager STORAGE_S3_ENDPOINT=http://minio:9000 -n mft
```

---

## Network topology

```
Internet
  │
  └── LoadBalancer (dmz-proxy-external)
        ports: 22 (SFTP), 21 (FTP), 443 (HTTPS)
        TLS required, zone enforcement ON, egress filter ON
          │
          └── gateway-service (ClusterIP)
                │
                └── all internal services (ClusterIP only)
                      no direct external access

Corporate VPN / Private peering
  │
  └── ClusterIP (dmz-proxy-internal)
        ports: 2222 (SFTP), 21 (FTP), 443 (HTTPS)
        TLS optional, zone enforcement ON
```

All services are ClusterIP — **zero external exposure** except the DMZ proxy LoadBalancer.
NetworkPolicy enforces this at the Kubernetes network layer.

---

## Scaling individual services

```bash
# Scale storage-manager (safe with RWX PVC)
kubectl scale deployment/storage-manager --replicas=4 -n mft

# Scale onboarding-api
kubectl scale deployment/onboarding-api --replicas=4 -n mft

# Scale external proxy for high partner volume
kubectl scale deployment/dmz-proxy-external --replicas=6 -n mft

# HPA handles it automatically based on CPU thresholds (see autoscaling/hpa.yaml)
```

---

## Useful commands

```bash
# Watch all pods
kubectl get pods -n mft -w

# Check storage-manager shared mount
kubectl exec -it deployment/storage-manager -n mft -- ls /data/storage/hot

# Verify two storage-manager pods see the same files
kubectl exec deploy/storage-manager -n mft -c storage-manager -- touch /data/storage/test.txt
# On second pod: should see the same file
kubectl get pods -n mft -l app=storage-manager
kubectl exec <second-pod-name> -n mft -- ls /data/storage/test.txt

# Check Redis location registry
kubectl exec deploy/redis -n mft -- redis-cli KEYS 'platform:storage:location:*'

# Tail logs from all onboarding-api replicas
kubectl logs -l app=onboarding-api -n mft --follow

# Check SPIFFE SVIDs
kubectl exec -it daemonset/spire-agent -n mft -- spire-agent api fetch x509
```

---

## Production checklist

- [ ] Replace `<base64-encoded-*>` in secrets.yaml with real values (use External Secrets Operator)
- [ ] Replace `mft.example.com` with real domain in ingress.yaml
- [ ] Replace `fs-XXXXXXXXX` with real EFS File System ID in storageclass.yaml
- [ ] Set `storageClassName` in pvcs.yaml to match your cloud
- [ ] Configure `loadBalancerSourceRanges` on dmz-proxy-external to known partner IPs
- [ ] Enable `DMZ_ZONES_ENABLED=true` and configure real CIDR ranges
- [ ] Set up cert-manager ClusterIssuer for TLS
- [ ] Configure resource requests/limits per service based on load testing
- [ ] Set up Prometheus + Grafana for monitoring (use existing dashboard in docker-compose)
- [ ] Configure backup for PostgreSQL PVC (volume snapshots or pg_dump CronJob)

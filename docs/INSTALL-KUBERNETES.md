# Kubernetes Installation Guide

## Prerequisites

- Kubernetes 1.25+
- Helm 3.10+
- kubectl configured
- Container registry (ECR, GCR, ACR, Docker Hub, etc.)
- Ingress controller (nginx-ingress recommended)
- cert-manager (for TLS — optional)

## Step 1: Build & Push Images

```bash
# Set your registry
export REGISTRY=your-registry.example.com/mft

# Build and push all services
for svc in onboarding-api sftp-service ftp-service ftp-web-service \
  config-service gateway-service encryption-service \
  external-forwarder-service dmz-proxy license-service \
  analytics-service admin-ui ftp-web-ui; do

  docker build -t ${REGISTRY}/${svc}:2.0.0 ./${svc}
  docker push ${REGISTRY}/${svc}:2.0.0
done
```

## Step 2: Create Namespace

```bash
kubectl create namespace mft
```

## Step 3: Install with Helm

### Minimal Install (dev/staging)

```bash
helm install mft ./helm/mft-platform \
  --namespace mft \
  --set global.imageRegistry="${REGISTRY}/" \
  --set global.jwtSecret="$(openssl rand -base64 32)" \
  --set global.controlApiKey="$(openssl rand -hex 16)"
```

### Production Install (external managed DB)

```bash
helm install mft ./helm/mft-platform \
  --namespace mft \
  -f helm/values-production.yaml \
  --set global.imageRegistry="${REGISTRY}/" \
  --set global.postgresql.host="your-rds-endpoint.amazonaws.com" \
  --set global.postgresql.password="your-db-password" \
  --set global.jwtSecret="$(openssl rand -base64 32)"
```

## Step 4: Selective Installation

Disable services you don't need:

```bash
# SFTP-only deployment
helm install mft ./helm/mft-platform \
  --namespace mft \
  --set ftpService.enabled=false \
  --set ftpWebService.enabled=false \
  --set ftpWebUi.enabled=false \
  --set externalForwarderService.enabled=false \
  --set dmzProxy.enabled=false \
  --set encryptionService.enabled=false
```

## Step 5: Configure Ingress

```yaml
# In your values override:
ingress:
  enabled: true
  className: nginx
  hosts:
    admin: admin.mft.yourcompany.com
    portal: files.mft.yourcompany.com
    api: api.mft.yourcompany.com
  tls:
    enabled: true
    secretName: mft-tls
```

## Auto-Scaling

```yaml
sftpService:
  autoscaling:
    enabled: true
    minReplicas: 2
    maxReplicas: 20
    targetCPUUtilizationPercentage: 60
```

The analytics service provides scaling recommendations visible in the admin UI dashboard.

## Multi-Region Deployment

Deploy separate Helm releases per region:

```bash
# US East
helm install mft-us ./helm/mft-platform -n mft-us \
  --set global.clusterId="us-east-1" \
  --set sftpService.replicaCount=3

# Europe
helm install mft-eu ./helm/mft-platform -n mft-eu \
  --set global.clusterId="eu-west-1" \
  --set sftpService.replicaCount=2

# Asia Pacific
helm install mft-apac ./helm/mft-platform -n mft-apac \
  --set global.clusterId="apac-1" \
  --set sftpService.replicaCount=5 \
  --set sftpService.autoscaling.enabled=true
```

Each cluster has its own database. Optionally share a single license-service across clusters.

## Monitoring

Enable Prometheus + Grafana:

```bash
helm install mft ./helm/mft-platform \
  --set monitoring.enabled=true \
  --set monitoring.grafana.enabled=true
```

All services expose `/actuator/prometheus` for metrics scraping.

## Upgrade

```bash
helm upgrade mft ./helm/mft-platform \
  --namespace mft \
  --set global.imageTag="2.1.0"
```

## Uninstall

```bash
helm uninstall mft --namespace mft
kubectl delete namespace mft
```

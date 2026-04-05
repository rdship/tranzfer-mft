# Analytics Service — Standalone Product Guide

> **Transfer analytics and monitoring.** Real-time dashboards, linear regression predictions, time series metrics, and configurable alert rules.

**Port:** 8090 | **Dependencies:** PostgreSQL, RabbitMQ | **Auth:** None

---

## Why Use This

- **Real-time dashboard** — Transfers today, success rate, active connections, protocol breakdown
- **Predictive scaling** — Linear regression forecasts load 24-48 hours ahead
- **Time series** — Hourly snapshots with P95/P99 latency metrics
- **Alert rules** — Configurable thresholds for error rate, volume, latency
- **Protocol breakdown** — Metrics split by SFTP, FTP, HTTP, AS2

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq analytics-service
curl http://localhost:8090/api/v1/analytics/dashboard
```

---

## API Reference

### 1. Dashboard Summary

**GET** `/api/v1/analytics/dashboard`

```bash
curl http://localhost:8090/api/v1/analytics/dashboard
```

**Response:**
```json
{
  "totalTransfersToday": 4523,
  "totalTransfersLastHour": 187,
  "successRateToday": 0.987,
  "totalGbToday": 45.7,
  "activeConnections": 23,
  "topProtocol": "SFTP",
  "alerts": [
    {"ruleName": "High Error Rate", "metric": "ERROR_RATE", "currentValue": 0.06, "threshold": 0.05, "severity": "WARN"}
  ],
  "scalingRecommendations": [...],
  "transfersPerHour": [
    {"hour": "2026-04-05T08:00:00Z", "transfers": 312, "successRate": 0.99, "bytes": 5368709120}
  ],
  "transfersByProtocol": {"SFTP": 2500, "FTP": 1200, "HTTP": 700, "AS2": 123}
}
```

### 2. Scaling Predictions

**GET** `/api/v1/analytics/predictions`

```bash
curl http://localhost:8090/api/v1/analytics/predictions
```

**GET** `/api/v1/analytics/predictions/{serviceType}`

```bash
curl http://localhost:8090/api/v1/analytics/predictions/SFTP
```

**Response:**
```json
{
  "serviceType": "SFTP",
  "currentLoad": 0.65,
  "predictedLoad24h": 0.82,
  "recommendedReplicas": 3,
  "currentReplicas": 2,
  "confidence": 0.87,
  "reason": "Increasing trend detected, peak expected at 14:00 UTC",
  "trend": "INCREASING",
  "peakExpectedAt": "2026-04-05T14:00:00Z"
}
```

### 3. Time Series Metrics

**GET** `/api/v1/analytics/timeseries`

```bash
# All services, last 24 hours
curl http://localhost:8090/api/v1/analytics/timeseries

# Specific service, last 48 hours
curl "http://localhost:8090/api/v1/analytics/timeseries?service=SFTP&hours=48"
```

**Response:**
```json
[
  {
    "snapshotTime": "2026-04-05T14:00:00Z",
    "serviceType": "SFTP",
    "totalTransfers": 312,
    "successfulTransfers": 308,
    "failedTransfers": 4,
    "totalBytesTransferred": 5368709120,
    "avgLatencyMs": 145.3,
    "p95LatencyMs": 350.0,
    "p99LatencyMs": 890.0,
    "activeSessions": 23
  }
]
```

### 4. Alert Rules — CRUD

```bash
# List alerts
curl http://localhost:8090/api/v1/analytics/alerts

# Create alert
curl -X POST http://localhost:8090/api/v1/analytics/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "High Error Rate",
    "serviceType": "SFTP",
    "metric": "ERROR_RATE",
    "operator": ">",
    "threshold": 0.05,
    "windowMinutes": 60,
    "enabled": true
  }'

# Update alert
curl -X PUT http://localhost:8090/api/v1/analytics/alerts/{id} \
  -H "Content-Type: application/json" \
  -d '{"name": "Critical Error Rate", "threshold": 0.10}'

# Delete alert
curl -X DELETE http://localhost:8090/api/v1/analytics/alerts/{id}
```

---

## Integration Examples

### Python — Monitoring Dashboard
```python
import requests

dashboard = requests.get("http://localhost:8090/api/v1/analytics/dashboard").json()
print(f"Transfers today: {dashboard['totalTransfersToday']}")
print(f"Success rate: {dashboard['successRateToday']:.1%}")

for alert in dashboard["alerts"]:
    print(f"⚠️ {alert['ruleName']}: {alert['currentValue']} > {alert['threshold']}")
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL URL |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `analytics.aggregation-interval-minutes` | `60` | Aggregation window |
| `analytics.prediction-window-hours` | `48` | Prediction horizon |
| `analytics.alert.error-rate-threshold` | `0.05` | Default error threshold |
| `server.port` | `8090` | HTTP port |

---

## All Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/analytics/dashboard` | Full dashboard summary |
| GET | `/api/v1/analytics/predictions` | All scaling predictions |
| GET | `/api/v1/analytics/predictions/{serviceType}` | Prediction for service |
| GET | `/api/v1/analytics/timeseries` | Time series metrics |
| GET | `/api/v1/analytics/alerts` | List alert rules |
| POST | `/api/v1/analytics/alerts` | Create alert rule |
| PUT | `/api/v1/analytics/alerts/{id}` | Update alert rule |
| DELETE | `/api/v1/analytics/alerts/{id}` | Delete alert rule |

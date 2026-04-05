# Analytics Service

> Real-time metrics, dashboards, predictive scaling, and alerting for transfer operations.

**Port:** 8090 | **Database:** PostgreSQL | **Messaging:** RabbitMQ | **Required:** Optional

---

## Overview

The analytics service provides operational intelligence:

- **Real-time dashboard** — Transfer counts, success rates, data volumes, protocol breakdown
- **Predictive scaling** — Linear regression forecasting with replica recommendations
- **Time series** — Historical metrics by service and time window
- **Alert rules** — Configurable thresholds with evaluation on each aggregation
- **Hourly aggregation** — Scheduled metrics computation with ShedLock

---

## Quick Start

```bash
docker compose up -d postgres rabbitmq analytics-service

# Dashboard
curl http://localhost:8090/api/v1/analytics/dashboard

# Predictions
curl http://localhost:8090/api/v1/analytics/predictions
```

---

## API Endpoints

### Dashboard

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/analytics/dashboard` | Real-time dashboard summary |

**Response:**
```json
{
  "totalTransfers": 15420,
  "successfulTransfers": 14890,
  "failedTransfers": 530,
  "successRate": 96.6,
  "totalGbTransferred": 245.7,
  "protocolBreakdown": {"SFTP": 8500, "FTP": 4200, "HTTPS": 2720},
  "activeAlerts": [
    {"ruleName": "High Error Rate", "metric": "ERROR_RATE", "currentValue": 5.2, "threshold": 5.0}
  ]
}
```

### Predictions

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/analytics/predictions` | Scaling recommendations for all services |
| GET | `/api/v1/analytics/predictions/{serviceType}` | Prediction for specific service |

**Service types:** `SFTP`, `FTP`, `FTP_WEB`, `GATEWAY`, `ENCRYPTION`

**Response:**
```json
{
  "serviceType": "SFTP",
  "currentLoad": 850,
  "predicted24hLoad": 1200,
  "trend": "INCREASING",
  "recommendedReplicas": 3,
  "spikeDetected": false,
  "confidence": 0.85
}
```

**Trends:** `STABLE`, `INCREASING`, `SPIKE_DETECTED`, `DECREASING`

**Scaling logic:** 1 replica per 500 transfers/hour (configurable), min 1, max 20.

### Time Series

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/analytics/timeseries?service=SFTP&hours=24` | Historical metrics |

### Alert Rules

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/analytics/alerts` | List all alert rules |
| POST | `/api/v1/analytics/alerts` | Create alert rule |
| PUT | `/api/v1/analytics/alerts/{id}` | Update alert rule |
| DELETE | `/api/v1/analytics/alerts/{id}` | Delete alert rule |

**Create an alert:**
```bash
curl -X POST http://localhost:8090/api/v1/analytics/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "High Error Rate",
    "metric": "ERROR_RATE",
    "operator": "GT",
    "threshold": 5.0,
    "enabled": true
  }'
```

**Metrics:** `ERROR_RATE`, `TRANSFER_VOLUME`, `LATENCY_P95`
**Operators:** `GT`, `GTE`, `LT`, `LTE`

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8090` | API port |
| `ANALYTICS_AGGREGATION_INTERVAL` | `60` | Minutes between aggregation runs |
| `ANALYTICS_PREDICTION_WINDOW` | `48` | Hours to forecast ahead |
| `ANALYTICS_ERROR_RATE_THRESHOLD` | `5` | Default error rate alert (%) |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | JDBC URL |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |

---

## Scheduled Tasks

| Task | Interval | Purpose |
|------|----------|---------|
| Metrics aggregation | Hourly | Compute transfer stats, latency percentiles (P95, P99) |

Uses ShedLock for distributed coordination.

---

## Dependencies

- **PostgreSQL** — Required. Transfer records for analysis, alert rules.
- **RabbitMQ** — Required. Event-driven metric updates.
- **shared** module — Entities, repositories.

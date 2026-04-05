# Analytics Service -- Demo & Quick Start Guide

> Real-time dashboards, linear regression predictions, time series metrics, and configurable alert rules for the TranzFer MFT platform -- on port 8090.

---

## What This Service Does

- **Live Dashboard** -- Aggregates today's transfer volume, success rate, throughput in GB, hourly time series, protocol breakdown, active alerts, and scaling recommendations into a single API call.
- **Predictive Scaling** -- Uses linear regression on up to 48 hours of metric snapshots to predict load 24 hours ahead. Recommends replica counts per service type (SFTP, FTP, FTP_WEB, GATEWAY, ENCRYPTION). Detects trends: STABLE, INCREASING, DECREASING, SPIKE_DETECTED.
- **Time Series Metrics** -- Hourly snapshots of total transfers, successes, failures, bytes transferred, average/p95/p99 latency, and active sessions. Grouped by service type. Aggregated automatically every hour.
- **Configurable Alert Rules** -- CRUD for alert rules that evaluate against real-time metrics: ERROR_RATE, TRANSFER_VOLUME, LATENCY_P95. Operators: GT, GTE, LT, LTE. Triggered alerts appear on the dashboard.
- **Prometheus & Actuator Metrics** -- Exposes `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus` for external monitoring (Grafana, Datadog, etc.).

---

## What You Need (Prerequisites Checklist)

| Prerequisite | Required? | Notes |
|---|---|---|
| **Java 21** | Yes (source builds) | [Adoptium Temurin](https://adoptium.net/) recommended |
| **Docker** | Yes (container method) | Docker Desktop 4.x on macOS/Windows; `docker.io` on Linux |
| **PostgreSQL 16** | Yes | Stores metric snapshots and alert rules via Flyway migrations |
| **RabbitMQ 3.13** | Yes | The service connects to RabbitMQ for event-driven metric collection |
| **Maven 3.9+** | Yes (source builds) | For `mvn clean package` |
| **curl** | Yes | For all demo commands. Pre-installed on macOS/Linux. Windows: use Git Bash or WSL. |

---

## Install & Start

### Method 1: Docker Compose (Recommended -- with all dependencies)

This starts PostgreSQL, RabbitMQ, and the analytics service together. Run from the repository root:

```bash
# Start infrastructure + analytics
docker compose up -d postgres rabbitmq analytics-service
```

Wait for all containers to become healthy:

```bash
docker compose ps
```

Expected output:

```
NAME                     STATUS
mft-postgres             running (healthy)
mft-rabbitmq             running (healthy)
mft-analytics-service    running (healthy)
```

RabbitMQ management UI is available at http://localhost:15672 (guest/guest).

### Method 2: Docker (Standalone -- bring your own dependencies)

If you already have PostgreSQL on `localhost:5432` and RabbitMQ on `localhost:5672`:

```bash
# Build the JAR first
cd analytics-service
mvn clean package -DskipTests

# Build and run the Docker image
docker build -t tranzfer-analytics .
docker run -d \
  --name analytics-service \
  -p 8090:8090 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e RABBITMQ_HOST=host.docker.internal \
  -e RABBITMQ_PORT=5672 \
  tranzfer-analytics
```

On Linux, replace `host.docker.internal` with your host IP or use `--network host`.

### Method 3: From Source (Any OS)

```bash
# 1. Start PostgreSQL (if not running)
#    macOS:   brew services start postgresql@16
#    Linux:   sudo systemctl start postgresql
#    Windows: net start postgresql-x64-16

# 2. Start RabbitMQ (if not running)
#    macOS:   brew services start rabbitmq
#    Linux:   sudo systemctl start rabbitmq-server
#    Windows: net start RabbitMQ

# 3. Create the database
psql -U postgres -c "CREATE DATABASE filetransfer;" 2>/dev/null || true

# 4. Build the entire project (shared module must be installed first)
cd /path/to/file-transfer-platform
mvn clean install -DskipTests

# 5. Run the analytics service
cd analytics-service
mvn spring-boot:run
```

#### Starting RabbitMQ with Docker (if you don't have it installed)

```bash
docker run -d \
  --name rabbitmq \
  -p 5672:5672 \
  -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=guest \
  -e RABBITMQ_DEFAULT_PASS=guest \
  rabbitmq:3.13-management-alpine
```

---

## Verify It's Running

```bash
curl -s http://localhost:8090/actuator/health | python3 -m json.tool
```

Expected response:

```json
{
    "status": "UP"
}
```

Also verify the main API endpoint:

```bash
curl -s http://localhost:8090/api/v1/analytics/dashboard | python3 -m json.tool
```

Expected response (fresh instance with no transfer data):

```json
{
    "totalTransfersToday": 0,
    "totalTransfersLastHour": 0,
    "successRateToday": 1.0,
    "totalGbToday": 0.0,
    "activeConnections": 0,
    "topProtocol": "N/A",
    "alerts": [],
    "scalingRecommendations": [
        {
            "serviceType": "SFTP",
            "currentLoad": 0.0,
            "predictedLoad24h": 0.0,
            "recommendedReplicas": 1,
            "currentReplicas": 1,
            "confidence": 0.1,
            "reason": "Insufficient data for prediction (< 3 hourly snapshots)",
            "trend": "STABLE",
            "peakExpectedAt": null
        },
        {
            "serviceType": "FTP",
            "currentLoad": 0.0,
            "predictedLoad24h": 0.0,
            "recommendedReplicas": 1,
            "currentReplicas": 1,
            "confidence": 0.1,
            "reason": "Insufficient data for prediction (< 3 hourly snapshots)",
            "trend": "STABLE",
            "peakExpectedAt": null
        },
        {
            "serviceType": "FTP_WEB",
            "currentLoad": 0.0,
            "predictedLoad24h": 0.0,
            "recommendedReplicas": 1,
            "currentReplicas": 1,
            "confidence": 0.1,
            "reason": "Insufficient data for prediction (< 3 hourly snapshots)",
            "trend": "STABLE",
            "peakExpectedAt": null
        },
        {
            "serviceType": "GATEWAY",
            "currentLoad": 0.0,
            "predictedLoad24h": 0.0,
            "recommendedReplicas": 1,
            "currentReplicas": 1,
            "confidence": 0.1,
            "reason": "Insufficient data for prediction (< 3 hourly snapshots)",
            "trend": "STABLE",
            "peakExpectedAt": null
        },
        {
            "serviceType": "ENCRYPTION",
            "currentLoad": 0.0,
            "predictedLoad24h": 0.0,
            "recommendedReplicas": 1,
            "currentReplicas": 1,
            "confidence": 0.1,
            "reason": "Insufficient data for prediction (< 3 hourly snapshots)",
            "trend": "STABLE",
            "peakExpectedAt": null
        }
    ],
    "transfersPerHour": [],
    "transfersByProtocol": {}
}
```

---

## Demo 1: Dashboard -- Full Platform Overview

The dashboard endpoint is a single call that returns everything an operations team needs: volumes, success rates, throughput, time series, alerts, and scaling recommendations.

```bash
curl -s http://localhost:8090/api/v1/analytics/dashboard | python3 -m json.tool
```

### Understanding the Dashboard Response

| Field | Type | What It Tells You |
|-------|------|-------------------|
| `totalTransfersToday` | long | Total file transfers since midnight UTC |
| `totalTransfersLastHour` | long | Transfers in the last 60 minutes |
| `successRateToday` | double | Success ratio (1.0 = 100%) |
| `totalGbToday` | double | Total gigabytes transferred today |
| `activeConnections` | int | Currently active connections |
| `topProtocol` | string | Busiest protocol (SFTP, FTP, FTP_WEB, etc.) |
| `alerts` | array | Currently triggered alert rules (see Demo 4) |
| `scalingRecommendations` | array | Per-service scaling predictions (see Demo 2) |
| `transfersPerHour` | array | 24-hour time series for charting |
| `transfersByProtocol` | map | Transfer counts grouped by protocol |

The `alerts` array contains objects with this structure when triggered:

```json
{
    "ruleName": "High Error Rate",
    "serviceType": "SFTP",
    "metric": "ERROR_RATE",
    "currentValue": 0.12,
    "threshold": 0.05,
    "severity": "CRITICAL"
}
```

The `transfersPerHour` array (used for time series charts) has this structure:

```json
{
    "hour": "14:00",
    "transfers": 42,
    "successRate": 0.95,
    "bytes": 1073741824
}
```

---

## Demo 2: Predictions -- Linear Regression Scaling

The prediction engine uses linear regression on the last 48 hours of metric snapshots to project load 24 hours ahead. It determines trend direction, detects spikes, and recommends replica counts.

### 2a. Get all predictions (all service types)

```bash
curl -s http://localhost:8090/api/v1/analytics/predictions | python3 -m json.tool
```

Expected response:

```json
[
    {
        "serviceType": "SFTP",
        "currentLoad": 0.0,
        "predictedLoad24h": 0.0,
        "recommendedReplicas": 1,
        "currentReplicas": 1,
        "confidence": 0.1,
        "reason": "Insufficient data for prediction (< 3 hourly snapshots)",
        "trend": "STABLE",
        "peakExpectedAt": null
    },
    {
        "serviceType": "FTP",
        "currentLoad": 0.0,
        "predictedLoad24h": 0.0,
        "recommendedReplicas": 1,
        "currentReplicas": 1,
        "confidence": 0.1,
        "reason": "Insufficient data for prediction (< 3 hourly snapshots)",
        "trend": "STABLE",
        "peakExpectedAt": null
    }
]
```

With sufficient data (3+ hourly snapshots), predictions look like:

```json
{
    "serviceType": "SFTP",
    "currentLoad": 350.0,
    "predictedLoad24h": 520.0,
    "recommendedReplicas": 2,
    "currentReplicas": 1,
    "confidence": 0.87,
    "reason": "Traffic increasing at 7.1 transfers/hour. Predicted 520/hr in 24h (capacity 500/replica).",
    "trend": "INCREASING",
    "peakExpectedAt": "2026-04-06T12:00:00Z"
}
```

### 2b. Get prediction for a single service

```bash
curl -s http://localhost:8090/api/v1/analytics/predictions/SFTP | python3 -m json.tool
```

Valid service types: `SFTP`, `FTP`, `FTP_WEB`, `GATEWAY`, `ENCRYPTION`.

Capacity per replica (used by the prediction engine):

| Service Type | Transfers/Hour/Replica |
|---|---|
| SFTP | 500 |
| FTP | 1,000 |
| FTP_WEB | 2,000 |
| GATEWAY | 3,000 |
| ENCRYPTION | 5,000 |

### How the Prediction Works

1. Fetches the last 48 hourly `MetricSnapshot` records for the service type.
2. Runs linear regression (y = mx + b) on the time series.
3. Extrapolates 24 hours ahead: `predicted = slope * (n + 24) + intercept`.
4. Computes R-squared for confidence.
5. Spike detection: any value > 3x the average triggers `SPIKE_DETECTED`.
6. Recommends replicas: `ceil(predicted / capacity_per_replica)`, capped at 20.

---

## Demo 3: Time Series -- Metric Snapshots

Metric snapshots are recorded every hour by the `MetricsAggregationService`. Each snapshot captures transfers, successes, failures, bytes, and latency percentiles.

### 3a. Get last 24 hours of metrics (default)

```bash
curl -s "http://localhost:8090/api/v1/analytics/timeseries" | python3 -m json.tool
```

### 3b. Get last 6 hours for SFTP only

```bash
curl -s "http://localhost:8090/api/v1/analytics/timeseries?service=SFTP&hours=6" | python3 -m json.tool
```

### 3c. Get all service types for 48 hours

```bash
curl -s "http://localhost:8090/api/v1/analytics/timeseries?service=ALL&hours=48" | python3 -m json.tool
```

Expected response (with data):

```json
[
    {
        "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        "snapshotTime": "2026-04-05T10:00:00Z",
        "serviceType": "SFTP",
        "protocol": null,
        "totalTransfers": 142,
        "successfulTransfers": 135,
        "failedTransfers": 7,
        "totalBytesTransferred": 5368709120,
        "avgLatencyMs": 1250.5,
        "p95LatencyMs": 3200.0,
        "p99LatencyMs": 5100.0,
        "activeSessions": 0,
        "createdAt": "2026-04-05T11:00:01Z"
    },
    {
        "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
        "snapshotTime": "2026-04-05T11:00:00Z",
        "serviceType": "SFTP",
        "protocol": null,
        "totalTransfers": 198,
        "successfulTransfers": 190,
        "failedTransfers": 8,
        "totalBytesTransferred": 8589934592,
        "avgLatencyMs": 980.2,
        "p95LatencyMs": 2800.0,
        "p99LatencyMs": 4500.0,
        "activeSessions": 0,
        "createdAt": "2026-04-05T12:00:01Z"
    }
]
```

On a fresh instance with no transfers, this returns `[]`.

### Time Series Fields

| Field | Description |
|-------|-------------|
| `snapshotTime` | Start of the hour window |
| `serviceType` | Protocol/service: SFTP, FTP, FTP_WEB, UNKNOWN, ALL |
| `totalTransfers` | Total transfers in this hour |
| `successfulTransfers` | Transfers that completed (DOWNLOADED or MOVED_TO_SENT) |
| `failedTransfers` | Transfers with FAILED status |
| `totalBytesTransferred` | Sum of file sizes in bytes |
| `avgLatencyMs` | Average upload-to-download latency |
| `p95LatencyMs` | 95th percentile latency |
| `p99LatencyMs` | 99th percentile latency |
| `activeSessions` | Active sessions at snapshot time |

---

## Demo 4: Alert Rules -- Create, Read, Update, Delete

Alert rules define thresholds on metrics. When triggered, they appear in the dashboard's `alerts` array.

### 4a. Create an alert rule: High error rate

```bash
curl -s -X POST http://localhost:8090/api/v1/analytics/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "High Error Rate",
    "serviceType": "SFTP",
    "metric": "ERROR_RATE",
    "operator": "GT",
    "threshold": 0.05,
    "windowMinutes": 60,
    "enabled": true
  }' | python3 -m json.tool
```

Expected response (HTTP 201 Created):

```json
{
    "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "name": "High Error Rate",
    "serviceType": "SFTP",
    "metric": "ERROR_RATE",
    "operator": "GT",
    "threshold": 0.05,
    "windowMinutes": 60,
    "enabled": true,
    "lastTriggered": null,
    "createdAt": "2026-04-05T12:00:00Z"
}
```

### 4b. Create a latency alert

```bash
curl -s -X POST http://localhost:8090/api/v1/analytics/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "SFTP Latency P95 Spike",
    "serviceType": "SFTP",
    "metric": "LATENCY_P95",
    "operator": "GT",
    "threshold": 5000.0,
    "windowMinutes": 30,
    "enabled": true
  }' | python3 -m json.tool
```

### 4c. Create a volume alert (cross-service)

```bash
curl -s -X POST http://localhost:8090/api/v1/analytics/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Traffic Surge",
    "serviceType": null,
    "metric": "TRANSFER_VOLUME",
    "operator": "GT",
    "threshold": 10000.0,
    "windowMinutes": 60,
    "enabled": true
  }' | python3 -m json.tool
```

Setting `serviceType` to `null` evaluates the rule across all service types.

### 4d. List all alert rules

```bash
curl -s http://localhost:8090/api/v1/analytics/alerts | python3 -m json.tool
```

Expected response:

```json
[
    {
        "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        "name": "High Error Rate",
        "serviceType": "SFTP",
        "metric": "ERROR_RATE",
        "operator": "GT",
        "threshold": 0.05,
        "windowMinutes": 60,
        "enabled": true,
        "lastTriggered": null,
        "createdAt": "2026-04-05T12:00:00Z"
    }
]
```

### 4e. Update an alert rule (change threshold)

Use the `id` from the create response:

```bash
curl -s -X PUT http://localhost:8090/api/v1/analytics/alerts/f47ac10b-58cc-4372-a567-0e02b2c3d479 \
  -H "Content-Type: application/json" \
  -d '{
    "name": "High Error Rate",
    "serviceType": "SFTP",
    "metric": "ERROR_RATE",
    "operator": "GT",
    "threshold": 0.10,
    "windowMinutes": 60,
    "enabled": true
  }' | python3 -m json.tool
```

Expected response (HTTP 200):

```json
{
    "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "name": "High Error Rate",
    "serviceType": "SFTP",
    "metric": "ERROR_RATE",
    "operator": "GT",
    "threshold": 0.10,
    "windowMinutes": 60,
    "enabled": true,
    "lastTriggered": null,
    "createdAt": "2026-04-05T12:00:00Z"
}
```

### 4f. Delete an alert rule

```bash
curl -s -X DELETE http://localhost:8090/api/v1/analytics/alerts/f47ac10b-58cc-4372-a567-0e02b2c3d479 -w "\nHTTP Status: %{http_code}\n"
```

Expected output:

```
HTTP Status: 204
```

### Alert Rule Fields Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | Yes | Human-readable rule name |
| `serviceType` | string | No | Service type filter (SFTP, FTP, FTP_WEB, GATEWAY, ENCRYPTION). Null = all services. |
| `metric` | string | Yes | Metric to evaluate: `ERROR_RATE`, `TRANSFER_VOLUME`, `LATENCY_P95` |
| `operator` | string | Yes | Comparison: `GT` (>), `GTE` (>=), `LT` (<), `LTE` (<=) |
| `threshold` | double | Yes | Threshold value. ERROR_RATE is a ratio (0.05 = 5%). LATENCY_P95 is in milliseconds. TRANSFER_VOLUME is a count. |
| `windowMinutes` | int | No | Evaluation window (default: 60) |
| `enabled` | boolean | No | Whether the rule is active (default: true) |

---

## Demo 5: Integration Pattern -- Python, Java, Node.js

### Python

```python
import requests
import json

BASE = "http://localhost:8090/api/v1/analytics"

# 1. Get dashboard summary
dashboard = requests.get(f"{BASE}/dashboard").json()
print(f"Transfers today: {dashboard['totalTransfersToday']}")
print(f"Success rate: {dashboard['successRateToday']:.1%}")
print(f"Top protocol: {dashboard['topProtocol']}")
print(f"Active alerts: {len(dashboard['alerts'])}")

# 2. Get predictions for SFTP
pred = requests.get(f"{BASE}/predictions/SFTP").json()
print(f"\nSFTP Prediction:")
print(f"  Current load: {pred['currentLoad']}")
print(f"  Predicted (24h): {pred['predictedLoad24h']}")
print(f"  Trend: {pred['trend']}")
print(f"  Recommended replicas: {pred['recommendedReplicas']}")

# 3. Create an alert rule
rule = requests.post(f"{BASE}/alerts", json={
    "name": "Python Alert - High Errors",
    "serviceType": "SFTP",
    "metric": "ERROR_RATE",
    "operator": "GT",
    "threshold": 0.05,
    "windowMinutes": 60,
    "enabled": True
}).json()
print(f"\nCreated alert rule: {rule['id']}")

# 4. Get time series for charting
timeseries = requests.get(f"{BASE}/timeseries", params={
    "service": "ALL",
    "hours": 24
}).json()
print(f"\nTime series data points: {len(timeseries)}")
for point in timeseries[-3:]:  # last 3 hours
    print(f"  {point['snapshotTime']}: {point['totalTransfers']} transfers, "
          f"{point['avgLatencyMs']:.0f}ms avg latency")
```

### Java

```java
import java.net.http.*;
import java.net.URI;

public class AnalyticsDemo {
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final String BASE = "http://localhost:8090/api/v1/analytics";

    public static void main(String[] args) throws Exception {
        // Dashboard
        var dashReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/dashboard"))
            .GET().build();
        var resp = client.send(dashReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Dashboard: " + resp.body());

        // Create alert rule
        var alertReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/alerts"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""
                {
                  "name": "Java Alert",
                  "metric": "ERROR_RATE",
                  "operator": "GT",
                  "threshold": 0.05
                }
                """))
            .build();
        resp = client.send(alertReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("Alert created: " + resp.body());

        // Predictions
        var predReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/predictions/SFTP"))
            .GET().build();
        resp = client.send(predReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("SFTP prediction: " + resp.body());
    }
}
```

### Node.js

```javascript
const BASE = "http://localhost:8090/api/v1/analytics";

async function demo() {
  // Dashboard
  const dashboard = await fetch(`${BASE}/dashboard`).then(r => r.json());
  console.log(`Transfers today: ${dashboard.totalTransfersToday}`);
  console.log(`Success rate: ${(dashboard.successRateToday * 100).toFixed(1)}%`);
  console.log(`Alerts: ${dashboard.alerts.length}`);

  // Predictions
  const predictions = await fetch(`${BASE}/predictions`).then(r => r.json());
  for (const p of predictions) {
    console.log(`${p.serviceType}: ${p.trend} (${p.currentLoad} -> ${p.predictedLoad24h})`);
  }

  // Create alert
  const alert = await fetch(`${BASE}/alerts`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      name: "Node Alert - Latency",
      metric: "LATENCY_P95",
      operator: "GT",
      threshold: 5000,
      enabled: true
    })
  }).then(r => r.json());
  console.log(`Created alert: ${alert.id}`);

  // Time series
  const ts = await fetch(`${BASE}/timeseries?service=ALL&hours=12`).then(r => r.json());
  console.log(`Time series points: ${ts.length}`);
}

demo();
```

---

## Use Cases

1. **Operations Dashboard** -- Call `GET /api/v1/analytics/dashboard` every 30 seconds from a React or Grafana dashboard. Shows transfer volume, success rate, hourly trends, protocol breakdown, and active alerts in one call.
2. **Auto-Scaling Pipeline** -- Poll `GET /api/v1/analytics/predictions/SFTP` from a Kubernetes operator or CI/CD pipeline. When `recommendedReplicas > currentReplicas` and `confidence > 0.7`, trigger `kubectl scale`.
3. **SRE Alerting** -- Create alert rules for each SLA metric. ERROR_RATE > 0.05 triggers pager. LATENCY_P95 > 5000ms triggers Slack notification. TRANSFER_VOLUME < 100 triggers "low volume" investigation.
4. **Capacity Planning Reports** -- Query `GET /api/v1/analytics/timeseries?hours=168` (7 days) weekly. Feed the data into spreadsheets or BI tools to plan infrastructure investments.
5. **Prometheus/Grafana Integration** -- Scrape `http://localhost:8090/actuator/prometheus` for JVM metrics, HTTP request metrics, and custom counters. Build Grafana dashboards alongside the built-in analytics.
6. **Compliance Auditing** -- Use time series data to prove SLA compliance: "99.5% success rate over the last 30 days." Alert rules create an audit trail of threshold breaches.
7. **Multi-Service Correlation** -- Compare SFTP, FTP, and GATEWAY time series side by side. If FTP latency spikes while SFTP stays normal, the issue is FTP-specific.

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier |
| `CLUSTER_HOST` | `analytics-service` | Host identifier for this instance |
| `JWT_SECRET` | `change_me_in_production_256bit_secret_key!!` | JWT signing secret |
| `JWT_EXPIRATION` | `900000` | JWT token expiration in ms (15 min) |
| `CONTROL_API_KEY` | `internal_control_secret` | Internal API key for control plane |
| `PROXY_ENABLED` | `false` | Enable outbound proxy |
| `PROXY_TYPE` | `HTTP` | Proxy type (HTTP/SOCKS) |
| `PROXY_HOST` | `dmz-proxy` | Proxy hostname |
| `PROXY_PORT` | `8088` | Proxy port |
| `TRACK_ID_PREFIX` | `TRZ` | Prefix for transfer tracking IDs |

### Internal Configuration (application.yml)

These are set in `application.yml` and not typically overridden via environment variables:

| Setting | Default | Description |
|---------|---------|-------------|
| `analytics.aggregation-interval-minutes` | `60` | How often snapshots are aggregated |
| `analytics.prediction-window-hours` | `48` | How many hours of data the prediction engine uses |
| `analytics.alert-error-rate-threshold` | `0.05` | Default error rate threshold (5%) |

---

## Complete Endpoint Reference

### AnalyticsController -- `/api/v1/analytics`

| Method | Path | Parameters | Description |
|--------|------|------------|-------------|
| GET | `/dashboard` | -- | Full dashboard: volumes, rates, time series, alerts, predictions |
| GET | `/predictions` | -- | Scaling predictions for all service types |
| GET | `/predictions/{serviceType}` | Path: SFTP, FTP, FTP_WEB, GATEWAY, ENCRYPTION | Prediction for one service |
| GET | `/timeseries` | Query: `service` (default ALL), `hours` (default 24) | Metric snapshots |
| GET | `/alerts` | -- | List all alert rules |
| POST | `/alerts` | Body: AlertRule JSON | Create a new alert rule (returns 201) |
| PUT | `/alerts/{id}` | Path: UUID, Body: AlertRule JSON | Update an existing alert rule |
| DELETE | `/alerts/{id}` | Path: UUID | Delete an alert rule (returns 204) |

### Actuator Endpoints

| Path | Description |
|------|-------------|
| `/actuator/health` | Application health status |
| `/actuator/metrics` | Available metric names |
| `/actuator/metrics/{name}` | Specific metric value (e.g., `jvm.memory.used`) |
| `/actuator/prometheus` | Prometheus-format metrics for scraping |
| `/actuator/info` | Application info |

---

## Cleanup

```bash
# Docker Compose (removes analytics + infrastructure)
docker compose down -v

# Standalone Docker
docker stop analytics-service rabbitmq && docker rm analytics-service rabbitmq

# If you created alert rules and want to reset:
psql -U postgres -d filetransfer -c "DELETE FROM alert_rules;"
psql -U postgres -d filetransfer -c "DELETE FROM metric_snapshots;"
```

---

## Troubleshooting

### All Platforms

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connection refused` on 8090 | Service not started or still starting | Wait 15-20s for Spring Boot startup. Check `docker logs mft-analytics-service`. |
| `relation "alert_rules" does not exist` | Flyway migrations not applied | Ensure the database exists: `psql -U postgres -c "CREATE DATABASE filetransfer;"`. Restart the service. |
| `Cannot connect to RabbitMQ` | RabbitMQ not running or wrong host/port | Verify: `curl -s http://localhost:15672/api/overview -u guest:guest`. Check `RABBITMQ_HOST` and `RABBITMQ_PORT`. |
| Dashboard returns all zeros | No transfer data yet | The analytics service reads from the `file_transfer_records` table. Start other services (SFTP, FTP) and transfer some files. |
| Predictions say "Insufficient data" | Fewer than 3 hourly snapshots | The aggregation job runs every hour. Wait 3+ hours, or run the full platform with active transfers. |
| `HikariPool` connection errors | PostgreSQL not reachable | Verify: `pg_isready -h localhost -p 5432`. Check `DATABASE_URL`. |

### Linux

```bash
# If port 8090 is already in use
sudo ss -tlnp | grep 8090
# Kill the process or change the port:
SERVER_PORT=9090 mvn spring-boot:run

# If RabbitMQ won't start (Erlang cookie issue)
sudo rm -f /var/lib/rabbitmq/.erlang.cookie
sudo systemctl restart rabbitmq-server
```

### macOS

```bash
# If Docker Desktop is not running
open -a Docker
# Wait for Docker to start, then retry

# If RabbitMQ installed via Homebrew
brew services restart rabbitmq
# Management UI: http://localhost:15672 (guest/guest)
```

### Windows (PowerShell / Git Bash)

```powershell
# Use Git Bash for curl, or PowerShell:
Invoke-RestMethod -Uri http://localhost:8090/api/v1/analytics/dashboard | ConvertTo-Json -Depth 10

# Check if RabbitMQ service is running
Get-Service -Name RabbitMQ

# If port conflict:
netstat -ano | findstr :8090
```

---

## What's Next

- **Generate transfer data** -- Start the full platform with `docker compose up -d`. Upload files via SFTP (`ssh -p 22222 ...`) or FTP to generate real metrics. The analytics service aggregates data every hour.
- **Build a Grafana dashboard** -- Point Grafana at `http://localhost:8090/actuator/prometheus` as a Prometheus data source. Combine JVM metrics with the REST API for a complete view.
- **Set up production alerts** -- Create alert rules for your SLAs. Pair with the AI engine's auto-remediation to close the loop: detect, alert, fix.
- **Explore the AI Engine** -- See `docs/demos/AI-ENGINE.md` for data classification, threat scoring, NLP, anomaly detection, and proxy intelligence that complement the analytics service.
- **Automate scaling** -- Write a cron job or Kubernetes operator that polls `/api/v1/analytics/predictions` and adjusts replica counts when `confidence > 0.7` and `recommendedReplicas != currentReplicas`.

# Screening Service — Demo & Quick Start Guide

> OFAC, EU, and UN sanctions screening for file transfers — powered by Jaro-Winkler fuzzy name matching with configurable thresholds.

---

## What This Service Does

- **Scans files (CSV, TSV, plain text) against OFAC, EU, and UN sanctions lists** — every name in your file is checked against thousands of sanctioned entities.
- **Fuzzy name matching using Jaro-Winkler similarity** — catches misspellings, transliteration variations, and partial matches (e.g., "Sberbnk of Russia" still matches "SBERBANK OF RUSSIA" at 0.91 similarity).
- **Returns CLEAR, HIT, or POSSIBLE_HIT verdicts** — exact matches (score >= 0.95) produce a HIT; fuzzy matches above the threshold produce POSSIBLE_HIT; no matches produce CLEAR.
- **Configurable threshold and action** — set the match sensitivity (default 0.82 = 82%) and whether hits are blocked or flagged.
- **Auto-refreshes sanctions lists** — downloads the OFAC SDN list from the US Treasury on startup and every 6 hours. Falls back to built-in sample data if the download fails.

---

## What You Need (Prerequisites Checklist)

- [ ] **Operating System:** Linux, macOS, or Windows
- [ ] **Docker** (recommended) OR **Java 21 + Maven** — [Install guide](PREREQUISITES.md)
- [ ] **PostgreSQL 16** — [Install guide](PREREQUISITES.md#step-2--install-postgresql-if-your-service-needs-it)
- [ ] **curl** — pre-installed on Linux/macOS; Windows: `winget install cURL.cURL`
- [ ] **Ports available:** `5432` (PostgreSQL), `8092` (Screening Service)

---

## Install & Start

### Step 0: Start PostgreSQL (Required)

If you do not already have PostgreSQL running, start it now. Every method below needs it.

```bash
docker run -d \
  --name mft-postgres \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine
```

Verify it is accepting connections:

```bash
docker exec mft-postgres pg_isready -U postgres
```

Expected output:

```
/var/run/postgresql:5432 - accepting connections
```

---

### Method 1: Docker (Any OS)

**Build the image** (from the repository root):

```bash
cd file-transfer-platform

# Build the JAR first (Docker COPY expects it in target/)
mvn clean package -DskipTests -pl screening-service -am

# Build the Docker image
docker build -t mft-screening-service ./screening-service
```

**Run the container:**

```bash
docker run -d \
  --name mft-screening-service \
  -p 8092:8092 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e SCREENING_THRESHOLD=0.82 \
  -e SCREENING_DEFAULT_ACTION=BLOCK \
  mft-screening-service
```

> **Linux note:** Replace `host.docker.internal` with `172.17.0.1` (the Docker bridge IP) or use `--network host` instead of `-p 8092:8092`.

---

### Method 2: Docker Compose (with PostgreSQL)

Create a file called `docker-compose-screening.yml`:

```yaml
version: '3.9'
services:
  postgres:
    image: postgres:16-alpine
    container_name: mft-postgres
    environment:
      POSTGRES_DB: filetransfer
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5

  screening-service:
    build: ./screening-service
    container_name: mft-screening-service
    ports:
      - "8092:8092"
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      SERVER_PORT: 8092
      SCREENING_THRESHOLD: 0.82
      SCREENING_DEFAULT_ACTION: BLOCK
    depends_on:
      postgres:
        condition: service_healthy
```

Run from the repository root:

```bash
docker compose -f docker-compose-screening.yml up -d
```

---

### Method 3: From Source

```bash
cd file-transfer-platform

# Build (first time takes 5-15 minutes for dependency downloads)
mvn clean package -DskipTests -pl screening-service -am

# Run
java -jar screening-service/target/screening-service-1.0.0-SNAPSHOT.jar
```

The service reads its configuration from `screening-service/src/main/resources/application.yml`. Default settings connect to PostgreSQL on `localhost:5432` with database `filetransfer`, user `postgres`, password `postgres`.

To override settings:

```bash
java -jar screening-service/target/screening-service-1.0.0-SNAPSHOT.jar \
  --screening.match-threshold=0.85 \
  --screening.default-action=FLAG
```

---

## Verify It's Running

```bash
curl -s http://localhost:8092/api/v1/screening/health | python3 -m json.tool
```

Expected output:

```json
{
    "status": "UP",
    "service": "screening-service",
    "sanctionsLists": {
        "OFAC_SDN": 10,
        "EU_SANCTIONS": 4,
        "UN_SANCTIONS": 3
    },
    "lastRefresh": "2026-04-05T12:00:00.000Z"
}
```

> **Note:** The entry counts above reflect built-in sample data. If the OFAC live download succeeds, `OFAC_SDN` will show thousands of entries. The built-in sample includes organizations like "SBERBANK OF RUSSIA", "GAZPROMBANK", "HEZBOLLAH", "WAGNER GROUP", and others for testing.

**Windows PowerShell alternative:**

```powershell
Invoke-RestMethod http://localhost:8092/api/v1/screening/health | ConvertTo-Json
```

---

## Demo 1: Screen a CSV File with Names

This demo creates a CSV file containing business counterparty names and screens it against sanctions lists.

### Step 1 — Create a test CSV file

**Linux / macOS:**

```bash
cat > /tmp/counterparties.csv << 'EOF'
name,country,account_number
Acme Manufacturing Ltd,Germany,DE89370400440532013000
Sberbank of Russia,Russia,RU0406040000000000000000001
Global Trade Partners Inc,United States,US12345678901234
Wagner Group LLC,Belarus,BY86AKBB10100000002966000000
Sunrise Medical Supplies,Japan,JP0001234567890123456789
Gazprombank International,Russia,RU0402048900000000000000001
Al Shabaab Relief Fund,Somalia,SO000000000000001
Pinnacle Logistics Corp,Canada,CA12345678901234
EOF
```

**Windows PowerShell:**

```powershell
@"
name,country,account_number
Acme Manufacturing Ltd,Germany,DE89370400440532013000
Sberbank of Russia,Russia,RU0406040000000000000000001
Global Trade Partners Inc,United States,US12345678901234
Wagner Group LLC,Belarus,BY86AKBB10100000002966000000
Sunrise Medical Supplies,Japan,JP0001234567890123456789
Gazprombank International,Russia,RU0402048900000000000000001
Al Shabaab Relief Fund,Somalia,SO000000000000001
Pinnacle Logistics Corp,Canada,CA12345678901234
"@ | Out-File -Encoding utf8 $env:TEMP\counterparties.csv
```

### Step 2 — Upload the file for screening

**Linux / macOS:**

```bash
curl -s -X POST http://localhost:8092/api/v1/screening/scan \
  -F "file=@/tmp/counterparties.csv" \
  -F "trackId=DEMO-001" \
  -F "account=compliance-team" \
  -F "columns=name" \
  | python3 -m json.tool
```

**Windows PowerShell:**

```powershell
$form = @{
    file    = Get-Item "$env:TEMP\counterparties.csv"
    trackId = "DEMO-001"
    account = "compliance-team"
    columns = "name"
}
Invoke-RestMethod -Uri http://localhost:8092/api/v1/screening/scan `
  -Method Post -Form $form | ConvertTo-Json -Depth 5
```

Expected output (HIT detected):

```json
{
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "trackId": "DEMO-001",
    "filename": "counterparties.csv",
    "accountUsername": "compliance-team",
    "outcome": "HIT",
    "recordsScanned": 7,
    "hitsFound": 4,
    "durationMs": 12,
    "hits": [
        {
            "matchedName": "SBERBANK OF RUSSIA",
            "sanctionsListName": "RUSSIA-EO14024",
            "sanctionsListSource": "OFAC_SDN",
            "matchScore": 1.0,
            "fileField": "name",
            "fileValue": "Sberbank of Russia",
            "lineNumber": 3
        },
        {
            "matchedName": "WAGNER GROUP",
            "sanctionsListName": "EU_RUSSIA",
            "sanctionsListSource": "EU_SANCTIONS",
            "matchScore": 0.95,
            "fileField": "name",
            "fileValue": "Wagner Group LLC",
            "lineNumber": 5
        },
        {
            "matchedName": "GAZPROMBANK",
            "sanctionsListName": "RUSSIA-EO14024",
            "sanctionsListSource": "OFAC_SDN",
            "matchScore": 0.883,
            "fileField": "name",
            "fileValue": "Gazprombank International",
            "lineNumber": 7
        },
        {
            "matchedName": "AL-SHABAAB",
            "sanctionsListName": "UN_SOMALIA",
            "sanctionsListSource": "UN_SANCTIONS",
            "matchScore": 0.867,
            "fileField": "name",
            "fileValue": "Al Shabaab Relief Fund",
            "lineNumber": 8
        }
    ],
    "actionTaken": "BLOCKED",
    "screenedAt": "2026-04-05T12:01:00.000Z"
}
```

### Understanding the Results

| Name in File | Matched Against | Score | Verdict | Why |
|---|---|---|---|---|
| Sberbank of Russia | SBERBANK OF RUSSIA | 1.000 | HIT | Exact match after normalization |
| Wagner Group LLC | WAGNER GROUP | 0.950 | HIT | Score >= 0.95 = confirmed HIT |
| Gazprombank International | GAZPROMBANK | 0.883 | POSSIBLE_HIT | Fuzzy match above 0.82 threshold |
| Al Shabaab Relief Fund | AL-SHABAAB | 0.867 | POSSIBLE_HIT | Fuzzy match — name embedded in longer string |
| Acme Manufacturing Ltd | (none) | < 0.82 | CLEAR | No match found |
| Global Trade Partners Inc | (none) | < 0.82 | CLEAR | No match found |
| Sunrise Medical Supplies | (none) | < 0.82 | CLEAR | No match found |
| Pinnacle Logistics Corp | (none) | < 0.82 | CLEAR | No match found |

The overall outcome is **HIT** because at least one match scored >= 0.95. If all matches were between 0.82 and 0.95, the outcome would be **POSSIBLE_HIT**. If no matches exceeded 0.82, the outcome would be **CLEAR**.

---

## Demo 2: Screen Inline Text (No File Upload)

This demonstrates the text scanning API — useful when you have data in memory (from another service, a form submission, or an API call) and do not want to write it to a file first.

### Screen a single name

```bash
curl -s -X POST http://localhost:8092/api/v1/screening/scan/text \
  -H "Content-Type: application/json" \
  -d '{
    "content": "name\nRussian Direct Investment Fund\nHezbollah Foundation\nJohnson & Johnson\nKorea Kwangson Banking\nSiemens AG",
    "filename": "vendor-check.csv",
    "trackId": "DEMO-002",
    "account": "procurement",
    "columns": ["name"]
  }' | python3 -m json.tool
```

**Windows PowerShell:**

```powershell
$body = @{
    content  = "name`nRussian Direct Investment Fund`nHezbollah Foundation`nJohnson & Johnson`nKorea Kwangson Banking`nSiemens AG"
    filename = "vendor-check.csv"
    trackId  = "DEMO-002"
    account  = "procurement"
    columns  = @("name")
} | ConvertTo-Json

Invoke-RestMethod -Uri http://localhost:8092/api/v1/screening/scan/text `
  -Method Post -ContentType "application/json" -Body $body | ConvertTo-Json -Depth 5
```

Expected output:

```json
{
    "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "trackId": "DEMO-002",
    "filename": "vendor-check.csv",
    "accountUsername": "procurement",
    "outcome": "HIT",
    "recordsScanned": 5,
    "hitsFound": 3,
    "durationMs": 8,
    "hits": [
        {
            "matchedName": "RUSSIAN DIRECT INVESTMENT FUND",
            "sanctionsListName": "RUSSIA-EO14024",
            "sanctionsListSource": "OFAC_SDN",
            "matchScore": 1.0,
            "fileField": "name",
            "fileValue": "Russian Direct Investment Fund",
            "lineNumber": 2
        },
        {
            "matchedName": "HEZBOLLAH",
            "sanctionsListName": "SDGT",
            "sanctionsListSource": "OFAC_SDN",
            "matchScore": 0.882,
            "fileField": "name",
            "fileValue": "Hezbollah Foundation",
            "lineNumber": 3
        },
        {
            "matchedName": "KOREA KWANGSON BANKING CORP",
            "sanctionsListName": "DPRK",
            "sanctionsListSource": "OFAC_SDN",
            "matchScore": 0.928,
            "fileField": "name",
            "fileValue": "Korea Kwangson Banking",
            "lineNumber": 5
        }
    ],
    "actionTaken": "BLOCKED",
    "screenedAt": "2026-04-05T12:02:00.000Z"
}
```

### Retrieve results later by track ID

```bash
curl -s http://localhost:8092/api/v1/screening/results/DEMO-002 | python3 -m json.tool
```

This returns the same JSON as the scan response. Use this to look up screening results asynchronously — your application submits a file, gets back a track ID, and polls for the result.

### View all recent results

```bash
curl -s http://localhost:8092/api/v1/screening/results | python3 -m json.tool
```

Returns the 50 most recent screening results, ordered by time (newest first).

### View only hits (blocked/flagged results)

```bash
curl -s http://localhost:8092/api/v1/screening/hits | python3 -m json.tool
```

Returns all results with outcome HIT or POSSIBLE_HIT. This is the compliance officer's view — everything that needs human review.

---

## Demo 3: Integration Pattern — Python, Java, Node.js

### Python

```python
import requests
import json

SCREENING_URL = "http://localhost:8092/api/v1/screening"

# --- Screen inline text ---
response = requests.post(f"{SCREENING_URL}/scan/text", json={
    "content": "name,role\nPetroleos de Venezuela,supplier\nMicrosoft Corporation,partner",
    "filename": "partners.csv",
    "trackId": "PY-001",
    "account": "python-app",
    "columns": ["name"]
})

result = response.json()
print(f"Outcome: {result['outcome']}")
print(f"Records scanned: {result['recordsScanned']}")
print(f"Hits found: {result['hitsFound']}")

if result['hits']:
    for hit in result['hits']:
        print(f"  MATCH: '{hit['fileValue']}' -> '{hit['matchedName']}' "
              f"(score: {hit['matchScore']}, source: {hit['sanctionsListSource']})")

# --- Screen a file upload ---
with open("/tmp/counterparties.csv", "rb") as f:
    file_response = requests.post(
        f"{SCREENING_URL}/scan",
        files={"file": ("counterparties.csv", f, "text/csv")},
        data={"trackId": "PY-002", "account": "python-app", "columns": "name"}
    )
    print(json.dumps(file_response.json(), indent=2))

# --- Check sanctions list status ---
lists = requests.get(f"{SCREENING_URL}/lists").json()
print(f"Sanctions lists loaded: {lists['lists']}")
print(f"Last refresh: {lists['lastRefresh']}")
```

### Java

```java
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpResponse.BodyHandlers;

public class ScreeningDemo {
    private static final String BASE = "http://localhost:8092/api/v1/screening";

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // Screen inline text
        String body = """
            {
              "content": "name\\nBelaruskali Corp\\nApple Inc",
              "filename": "check.csv",
              "trackId": "JAVA-001",
              "columns": ["name"]
            }
            """;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/scan/text"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
        System.out.println("Status: " + response.statusCode());
        System.out.println("Body: " + response.body());

        // Get hits
        HttpRequest hitsReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE + "/hits"))
            .GET().build();
        HttpResponse<String> hitsResp = client.send(hitsReq, BodyHandlers.ofString());
        System.out.println("Hits: " + hitsResp.body());
    }
}
```

### Node.js

```javascript
const BASE = "http://localhost:8092/api/v1/screening";

// Screen inline text
async function screenNames() {
  const response = await fetch(`${BASE}/scan/text`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      content: "name\nIranian Shipping Lines\nFedEx Corporation\nBanco Delta Asia",
      filename: "vendors.csv",
      trackId: "NODE-001",
      account: "node-app",
      columns: ["name"],
    }),
  });

  const result = await response.json();
  console.log(`Outcome: ${result.outcome}`);
  console.log(`Scanned: ${result.recordsScanned}, Hits: ${result.hitsFound}`);

  for (const hit of result.hits || []) {
    console.log(
      `  MATCH: "${hit.fileValue}" -> "${hit.matchedName}" ` +
        `(score: ${hit.matchScore}, source: ${hit.sanctionsListSource})`
    );
  }
}

// Check list status
async function listStatus() {
  const response = await fetch(`${BASE}/lists`);
  const data = await response.json();
  console.log("Lists:", data.lists);
  console.log("Last refresh:", data.lastRefresh);
}

// Refresh sanctions lists manually
async function refreshLists() {
  const response = await fetch(`${BASE}/lists/refresh`, { method: "POST" });
  const data = await response.json();
  console.log("Refresh result:", data);
}

screenNames().then(listStatus).then(refreshLists);
```

---

## API Reference

All endpoints are prefixed with `/api/v1/screening`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/scan` | Screen an uploaded file (multipart). Params: `file` (required), `trackId`, `account`, `columns` |
| `POST` | `/scan/text` | Screen inline text/CSV content (JSON body). Fields: `content` (required), `filename`, `trackId`, `account`, `columns` |
| `GET` | `/results/{trackId}` | Get a screening result by track ID |
| `GET` | `/results` | Get the 50 most recent screening results |
| `GET` | `/hits` | Get all results with outcome HIT or POSSIBLE_HIT |
| `POST` | `/lists/refresh` | Force an immediate refresh of all sanctions lists |
| `GET` | `/lists` | Get sanctions list status (counts, last refresh time) |
| `GET` | `/health` | Health check with list status |

---

## Use Cases

1. **Pre-transfer compliance screening** — Before a file transfer completes, submit the file to the screening service. If the outcome is HIT or POSSIBLE_HIT, block the transfer and alert the compliance team.

2. **Batch counterparty onboarding** — When onboarding hundreds of new trading partners from a CSV, screen the entire file at once. The `columns` parameter lets you target only the name column, skipping addresses and account numbers.

3. **Real-time payment screening** — Integrate with your payment processing pipeline. Before approving a wire transfer, screen the sender and recipient names via the `/scan/text` endpoint (no file I/O needed).

4. **Periodic re-screening** — Sanctions lists change. Set up a cron job that re-screens your entire partner database weekly. Compare the new results against previous ones to catch newly sanctioned entities.

5. **Audit trail** — Every screening result is persisted in PostgreSQL with a track ID. Use `/results/{trackId}` to prove to auditors that a specific file was screened on a specific date with a specific outcome.

6. **Multi-jurisdictional compliance** — The service checks OFAC (US), EU, and UN sanctions lists simultaneously. A single scan covers three regulatory regimes.

7. **Tunable sensitivity** — Financial institutions may want a low threshold (0.75) to catch more potential matches. Logistics companies with high-volume, low-risk transfers may raise it to 0.90 to reduce false positives.

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC connection string |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `SCREENING_THRESHOLD` | `0.82` | Jaro-Winkler match threshold (0.0 to 1.0). Lower = more matches (more false positives). Higher = fewer matches (may miss fuzzy variants). |
| `SCREENING_DEFAULT_ACTION` | `BLOCK` | Action when a hit is found. `BLOCK` = file transfer is stopped. `FLAG` = file is flagged for review but not stopped. |
| `SCREENING_REFRESH_HOURS` | `24` | How often the OFAC SDN list is re-downloaded from the US Treasury (in hours). The scheduled cron runs every 6 hours regardless. |
| `EU_SANCTIONS_ENABLED` | `true` | Enable/disable EU consolidated sanctions list |
| `UN_SANCTIONS_ENABLED` | `true` | Enable/disable UN Security Council sanctions list |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier for multi-instance deployments |
| `CLUSTER_HOST` | `screening-service` | Hostname of this instance within the cluster |
| `CONTROL_API_KEY` | `internal_control_secret` | API key for internal control plane communication |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment label (PROD, STAGING, DEV) |
| `PROXY_ENABLED` | `false` | Enable HTTP proxy for sanctions list downloads |
| `PROXY_TYPE` | `HTTP` | Proxy type (HTTP or SOCKS5) |
| `PROXY_HOST` | `dmz-proxy` | Proxy hostname |
| `PROXY_PORT` | `8088` | Proxy port |

---

## Cleanup

### Stop the screening service

```bash
# Docker
docker stop mft-screening-service && docker rm mft-screening-service

# Docker Compose
docker compose -f docker-compose-screening.yml down

# From source — press Ctrl+C in the terminal where it is running
```

### Stop PostgreSQL (if you started it for this demo)

```bash
docker stop mft-postgres && docker rm mft-postgres
```

### Remove Docker volumes (deletes all data)

```bash
docker volume rm $(docker volume ls -q --filter name=mft)
```

---

## Troubleshooting

### All Platforms

**"No sanctions entries loaded — screening skipped"**

The service starts and begins downloading sanctions lists. If the download has not finished yet, or if it failed and no built-in data was loaded, screening will skip and return CLEAR for everything. Check the service logs:

```bash
# Docker
docker logs mft-screening-service | grep -i "sanctions"

# From source — check the terminal output
```

Wait 10-30 seconds after startup for the initial load to complete, then retry.

**"outcome: ERROR"**

The file could not be parsed. Common causes:
- File encoding is not UTF-8 (convert with `iconv -f ISO-8859-1 -t UTF-8 input.csv > output.csv`)
- File is binary (PDF, Excel) — the service only supports CSV, TSV, and plain text
- File is empty

**All results show CLEAR when you expect hits**

Check your threshold. The default is 0.82. If you are testing with very different name spellings, lower it:

```bash
# Restart with a lower threshold
docker stop mft-screening-service
docker run -d --name mft-screening-service -p 8092:8092 \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/filetransfer \
  -e SCREENING_THRESHOLD=0.70 \
  mft-screening-service
```

Also verify sanctions lists are loaded:

```bash
curl -s http://localhost:8092/api/v1/screening/lists | python3 -m json.tool
```

If all counts are 0, the lists failed to load. Check logs.

---

### Linux

**"Connection refused" to PostgreSQL at 172.17.0.1**

The Docker bridge network may use a different IP. Find it:

```bash
ip addr show docker0 | grep inet
```

Or use `--network host` when running the screening service container:

```bash
docker run -d --name mft-screening-service --network host \
  -e DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer \
  -e SCREENING_THRESHOLD=0.82 \
  mft-screening-service
```

---

### macOS

**"host.docker.internal" not resolving**

This hostname is supported in Docker Desktop for Mac. If you are using Colima or another Docker runtime:

```bash
# Find your host IP
ifconfig en0 | grep "inet "

# Use that IP in DATABASE_URL
docker run -d --name mft-screening-service -p 8092:8092 \
  -e DATABASE_URL=jdbc:postgresql://192.168.1.100:5432/filetransfer \
  mft-screening-service
```

---

### Windows

**curl not found**

Use PowerShell's built-in `Invoke-RestMethod` instead (shown in each demo above), or install curl:

```powershell
winget install cURL.cURL
```

**File path in `-F` flag**

On Windows with curl, use forward slashes or escape backslashes:

```powershell
curl -X POST http://localhost:8092/api/v1/screening/scan `
  -F "file=@C:/Users/you/counterparties.csv" `
  -F "trackId=WIN-001"
```

**Port 8092 blocked by firewall**

```powershell
# Allow the port through Windows Firewall
New-NetFirewallRule -DisplayName "MFT Screening" -Direction Inbound -Port 8092 -Protocol TCP -Action Allow
```

---

## What's Next

- **Integrate with the Onboarding API** — The Onboarding API can automatically call the screening service when a new partner account is created. See [ONBOARDING-API.md](ONBOARDING-API.md).
- **Connect to the Config Service** — Define file flows that include a screening step. See [CONFIG-SERVICE.md](CONFIG-SERVICE.md).
- **Lower your threshold for testing** — Set `SCREENING_THRESHOLD=0.70` to see more fuzzy matches and understand how Jaro-Winkler scoring works.
- **Try the Analytics Service** — Screening results feed into transfer analytics. See [ANALYTICS-SERVICE.md](ANALYTICS-SERVICE.md).
- **Explore the full platform** — Run `docker compose up -d` from the repository root to start all 17 services and 3 UIs.

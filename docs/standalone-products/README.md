# TranzFer MFT — Standalone Product Catalog

> Every TranzFer microservice is a **standalone product** that can be deployed and used independently. Third-party applications can invoke any service directly via REST API, file protocol, or message queue.

---

## Product Catalog

### Tier 1 — Zero-Dependency Products (No Database Required)

These services run with `java -jar` and nothing else. Ideal for embedding into existing pipelines.

| Product | Port | What It Does | Quick Start |
|---------|------|-------------|-------------|
| [**EDI Converter**](EDI-CONVERTER.md) | 8095 | Convert any EDI format (X12, EDIFACT, HL7, SWIFT, NACHA…) to JSON/XML/CSV. 66 conversion paths, self-healing, NLP creation. | `java -jar edi-converter.jar` |
| [**DMZ Proxy**](DMZ-PROXY.md) | 8088 | AI-powered reverse proxy with real-time threat scoring, rate limiting, protocol detection. | `java -jar dmz-proxy.jar` |

### Tier 2 — Lightweight Products (PostgreSQL Only)

Add a PostgreSQL database and you get full persistence, audit trails, and key management.

| Product | Port | What It Does | Quick Start |
|---------|------|-------------|-------------|
| [**Encryption Service**](ENCRYPTION-SERVICE.md) | 8086 | AES-256-GCM and PGP file encryption/decryption via REST. | `docker compose up encryption-service` |
| [**Keystore Manager**](KEYSTORE-MANAGER.md) | 8093 | Generate, import, rotate SSH/TLS/PGP/AES/HMAC keys. Full lifecycle management. | `docker compose up keystore-manager` |
| [**Screening Service**](SCREENING-SERVICE.md) | 8092 | OFAC/EU/UN sanctions screening with Jaro-Winkler fuzzy matching. | `docker compose up screening-service` |
| [**License Service**](LICENSE-SERVICE.md) | 8089 | RSA-signed license keys, trial management, product catalog, entitlements. | `docker compose up license-service` |
| [**Storage Manager**](STORAGE-MANAGER.md) | 8094 | Tiered storage (HOT/WARM/COLD) with SHA-256 deduplication, parallel I/O. | `docker compose up storage-manager` |
| [**AS2 Service**](AS2-SERVICE.md) | 8094 | B2B AS2/AS4 protocol (RFC 4130) with MDN receipts, MIC integrity. | `docker compose up as2-service` |
| [**AI Engine**](AI-ENGINE.md) | 8091 | Data classification, anomaly detection, threat scoring, NLP, partner profiling. | `docker compose up ai-engine` |
| [**Analytics Service**](ANALYTICS-SERVICE.md) | 8090 | Real-time dashboards, linear regression predictions, alert rules. | `docker compose up analytics-service` |

### Tier 3 — Protocol Servers

Full file transfer protocol servers with built-in routing, authentication, and audit.

| Product | Port | What It Does | Quick Start |
|---------|------|-------------|-------------|
| [**SFTP Service**](SFTP-SERVICE.md) | 2222 | Production SFTP server (Apache MINA SSHD) with password + public key auth. | `docker compose up sftp-service` |
| [**FTP Service**](FTP-SERVICE.md) | 21 | FTP/FTPS server (Apache FtpServer) with passive mode, TLS. | `docker compose up ftp-service` |
| [**FTP Web Service**](FTP-WEB-SERVICE.md) | 8083 | HTTP REST API for file operations (upload, download, list, delete). | `docker compose up ftp-web-service` |

### Tier 4 — Platform Services

Orchestration, routing, and configuration services.

| Product | Port | What It Does | Quick Start |
|---------|------|-------------|-------------|
| [**Gateway Service**](GATEWAY-SERVICE.md) | 8085 | Protocol gateway with user-based routing across SFTP/FTP/HTTP. | `docker compose up gateway-service` |
| [**External Forwarder**](EXTERNAL-FORWARDER.md) | 8087 | Multi-protocol file forwarding (SFTP, FTP, HTTP, AS2, Kafka). | `docker compose up external-forwarder-service` |
| [**Onboarding API**](ONBOARDING-API.md) | 8080 | Central API: auth, accounts, transfers, partner portal, multi-tenancy. | `docker compose up onboarding-api` |
| [**Config Service**](CONFIG-SERVICE.md) | 8084 | File flows, connectors, scheduling, security profiles, platform settings. | `docker compose up config-service` |

---

## Integration Patterns

### Pattern 1: Direct REST Call
```bash
# Convert an EDI file to JSON — no auth, no setup, just HTTP
curl -X POST http://localhost:8095/api/v1/convert/convert \
  -H "Content-Type: application/json" \
  -d '{"content": "ISA*00*...", "target": "JSON"}'
```

### Pattern 2: Microservice-to-Microservice
```java
// Your Java app calls the encryption service
RestTemplate rest = new RestTemplate();
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.MULTIPART_FORM_DATA);

MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
body.add("file", new FileSystemResource("/path/to/file.csv"));

ResponseEntity<byte[]> response = rest.postForEntity(
    "http://localhost:8086/api/encrypt?keyId=" + keyId,
    new HttpEntity<>(body, headers),
    byte[].class
);
```

### Pattern 3: Docker Compose (pick what you need)
```yaml
# docker-compose.yml — just the services you want
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: filetransfer
      POSTGRES_PASSWORD: postgres
    ports: ["5432:5432"]

  edi-converter:
    image: tranzfer/edi-converter:latest
    ports: ["8095:8095"]

  screening-service:
    image: tranzfer/screening-service:latest
    ports: ["8092:8092"]
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/filetransfer
    depends_on: [postgres]
```

### Pattern 4: Kubernetes Sidecar
```yaml
# Add as a sidecar to your existing pods
containers:
  - name: your-app
    image: your-app:latest
  - name: edi-converter
    image: tranzfer/edi-converter:latest
    ports:
      - containerPort: 8095
```

---

## How to Choose

| I need to... | Use this product |
|-------------|-----------------|
| Convert EDI/X12/EDIFACT to JSON | [EDI Converter](EDI-CONVERTER.md) |
| Encrypt/decrypt files via API | [Encryption Service](ENCRYPTION-SERVICE.md) |
| Screen names against sanctions lists | [Screening Service](SCREENING-SERVICE.md) |
| Manage SSH/TLS/PGP keys | [Keystore Manager](KEYSTORE-MANAGER.md) |
| Classify data for PCI/PII/PHI | [AI Engine](AI-ENGINE.md) |
| Store files with deduplication | [Storage Manager](STORAGE-MANAGER.md) |
| Exchange AS2/AS4 B2B messages | [AS2 Service](AS2-SERVICE.md) |
| Run an SFTP server | [SFTP Service](SFTP-SERVICE.md) |
| Forward files to external systems | [External Forwarder](EXTERNAL-FORWARDER.md) |
| Add a security proxy layer | [DMZ Proxy](DMZ-PROXY.md) |
| Validate and issue licenses | [License Service](LICENSE-SERVICE.md) |
| Monitor transfer metrics | [Analytics Service](ANALYTICS-SERVICE.md) |

---

## Common Configuration

All services accept these environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/filetransfer` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `postgres` | Database username |
| `DB_PASSWORD` | `postgres` | Database password |
| `JWT_SECRET` | (insecure default) | JWT signing secret (256-bit min) |
| `PLATFORM_ENVIRONMENT` | `PROD` | Environment identifier |
| `CLUSTER_ID` | `default-cluster` | Cluster identifier |

> **Note:** Tier 1 products (EDI Converter, DMZ Proxy) do not require a database.

---

## Build from Source

```bash
# Build everything
git clone https://github.com/rdship/tranzfer-mft.git
cd tranzfer-mft
mvn clean package -DskipTests

# Run any single service
java -jar edi-converter/target/edi-converter-*.jar
java -jar encryption-service/target/encryption-service-*.jar
java -jar screening-service/target/screening-service-*.jar
# ... etc
```

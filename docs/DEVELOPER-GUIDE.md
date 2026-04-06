# TranzFer MFT — Developer Guide

How to build, test, debug, and contribute to the project.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Repository Structure](#repository-structure)
3. [Building the Project](#building-the-project)
4. [Running Tests](#running-tests)
5. [Running Locally](#running-locally)
6. [Working on a Single Module](#working-on-a-single-module)
7. [Frontend Development](#frontend-development)
8. [Debugging](#debugging)
9. [Code Style](#code-style)
10. [Adding a New Service](#adding-a-new-service)
11. [Inter-Service Communication](#inter-service-communication)
12. [Platform Infrastructure](#platform-infrastructure)
13. [Partner Management API](#partner-management-api)
14. [Database Migrations](#database-migrations)
15. [Common Issues](#common-issues)

---

## Prerequisites

| Tool | Version | Required? | Install |
|------|---------|-----------|---------|
| Java JDK | 21+ | Yes | `brew install openjdk@21` (macOS) or [adoptium.net](https://adoptium.net/) |
| Maven | 3.9+ | Yes | `brew install maven` (macOS) or [maven.apache.org](https://maven.apache.org/) |
| Docker + Docker Compose | 24+ | Yes (for running) | [docker.com](https://www.docker.com/products/docker-desktop/) |
| Node.js | 18+ | For frontends only | `brew install node` or [nodejs.org](https://nodejs.org/) |
| Git | 2.30+ | Yes | Pre-installed on most systems |

**Verify your setup:**
```bash
java -version   # Should show 21+
mvn -version    # Should show 3.9+
docker --version
node --version  # Only needed for frontend work
```

**No Java/Maven?** You can build entirely with Docker:
```bash
docker run -v $(pwd):/app -w /app maven:3.9-eclipse-temurin-21 mvn clean package -DskipTests
```

---

## Repository Structure

```
file-transfer-platform/
├── pom.xml                     ← Parent POM (defines all modules)
├── docker-compose.yml          ← Development environment
├── shared/                     ← Shared library (entities, security, JWT)
├── onboarding-api/             ← Main API (accounts, auth)
├── config-service/             ← Configuration management
├── sftp-service/               ← SFTP server
├── ftp-service/                ← FTP server
├── ftp-web-service/            ← Web file upload API
├── gateway-service/            ← Protocol gateway
├── dmz-proxy/                  ← AI-powered DMZ proxy
├── ai-engine/                  ← AI brain (classification, proxy intelligence, threat intel, MITRE ATT&CK, automated response)
├── encryption-service/         ← AES/PGP encryption
├── external-forwarder-service/ ← File forwarding
├── screening-service/          ← OFAC sanctions screening
├── analytics-service/          ← Metrics and dashboards
├── license-service/            ← License management
├── keystore-manager/           ← Certificate management
├── storage-manager/            ← Tiered storage
├── edi-converter/              ← EDI format conversion
├── as2-service/                ← AS2 B2B protocol
├── cli/                        ← CLI tool
├── mft-client/                 ← Desktop client
├── admin-ui/                   ← Admin dashboard (React)
├── ftp-web-ui/                 ← File browser (React)
├── partner-portal/             ← Partner portal (React)
├── api-gateway/                ← Nginx reverse proxy
├── helm/                       ← Kubernetes Helm charts
├── installer/                  ← Installation tools
├── docs/                       ← Documentation
└── tests/                      ← Integration tests
```

---

## Building the Project

### Build everything

```bash
# Build all 20 Java modules (skip tests for speed)
mvn clean package -DskipTests

# Build with tests
mvn clean package
```

**Build time:** ~2-4 minutes (depends on machine and network).

### Build a single module

```bash
# Build only the shared library first (other modules depend on it)
mvn clean install -pl shared -DskipTests

# Then build your module
mvn clean package -pl dmz-proxy -DskipTests
mvn clean package -pl ai-engine -DskipTests
```

**Note:** Most modules depend on `shared`. Always build `shared` first if it hasn't been built:
```bash
mvn clean install -pl shared -DskipTests && mvn clean package -pl dmz-proxy -DskipTests
```

### Build Docker images

```bash
# Build all images
docker compose build

# Build a single image
docker compose build ai-engine
docker compose build dmz-proxy
```

---

## Running Tests

### Run all tests

```bash
mvn test
```

### Run tests for a single module

```bash
mvn test -pl ai-engine
mvn test -pl dmz-proxy
mvn test -pl onboarding-api
```

### Run a specific test class

```bash
mvn test -pl ai-engine -Dtest=IpReputationServiceTest
mvn test -pl dmz-proxy -Dtest=ProtocolDetectorTest
```

### Run a specific test method

```bash
mvn test -pl ai-engine -Dtest="IpReputationServiceTest#newIpStartsAtNeutralScore"
```

### Run integration tests

```bash
# DMZ proxy ↔ AI engine client tests (WireMock-based)
mvn test -pl dmz-proxy -Dtest=AiVerdictClientIntegrationTest

# AI engine REST endpoint tests (SpringBootTest)
mvn test -pl ai-engine -Dtest=ProxyIntelligenceControllerIntegrationTest
```

### AI Engine package structure

The AI engine module is organized into the following packages:

```
ai-engine/src/main/java/com/filetransfer/ai/
├── controller/
│   ├── ProxyIntelligenceController.java   ← Proxy verdict endpoints
│   └── ThreatIntelligenceController.java  ← 30+ threat intel endpoints (/api/v1/threats/*)
├── entity/
│   ├── threat/                            ← Security data models
│   │   ├── SecurityEvent.java             ← Unified event schema (~600 lines)
│   │   ├── SecurityAlert.java, SecurityEnums.java
│   │   ├── ThreatActor.java, AttackCampaign.java, VerdictRecord.java
│   │   └── ...
│   └── intelligence/                      ← Threat indicator models
│       ├── ThreatIndicator.java, IndicatorType.java, ThreatLevel.java
│       └── ...
├── service/
│   ├── intelligence/                      ← Threat intelligence layer
│   │   ├── MitreAttackMapper.java         ← 50+ MITRE ATT&CK techniques
│   │   ├── ThreatIntelligenceStore.java   ← Central IOC store (in-memory + DB)
│   │   ├── ThreatKnowledgeGraph.java      ← Graph DB: BFS, PageRank, clusters
│   │   ├── GeoIpResolver.java             ← ip-api.com + 50K cache
│   │   ├── ThreatFeedConfig.java          ← External feed configuration
│   │   └── AttackSurfaceAnalyzer.java     ← Platform attack surface analysis
│   ├── detection/                         ← ML-enhanced detection
│   │   ├── AnomalyEnsemble.java           ← Isolation Forest + Z-score + baselines
│   │   ├── NetworkBehaviorAnalyzer.java   ← Beaconing, DGA, DNS tunnel, exfil
│   │   ├── AttackChainDetector.java       ← MITRE kill chain progression
│   │   └── ExplainabilityEngine.java      ← Human-readable verdict explanations
│   ├── agent/                             ← Autonomous background agents
│   │   ├── BackgroundAgent.java           ← Abstract base (lifecycle, metrics)
│   │   ├── AgentManager.java              ← Scheduling, health checks
│   │   ├── OsintCollectorAgent.java       ← OSINT feeds (every 15 min)
│   │   ├── CveMonitorAgent.java           ← CVE/NVD monitoring (every 1 hour)
│   │   ├── ThreatCorrelationAgent.java    ← Cross-source correlation (every 2 min)
│   │   ├── ReputationDecayAgent.java      ← IP decay + cleanup (every 5 min)
│   │   └── AgentRegistrar.java            ← Spring config wiring all agents
│   ├── response/                          ← Automated response
│   │   ├── PlaybookEngine.java            ← 8 playbooks, rate-limited, audit trail
│   │   └── IncidentManager.java           ← Incident lifecycle + reports
│   └── ...                                ← Existing: classification, proxy, reputation
└── ...
```

### AI Engine performance optimizations

The verdict hot path includes 5 built-in optimizations:

1. **Async alert enrichment** — `raiseAlert()` runs MITRE/explainability on a separate `alertExecutor` thread pool (2 daemon threads). Verdict returns without waiting.
2. **Verdict caching** — `ConcurrentHashMap<String, CachedVerdict>` with risk-based TTL (BLOCK=5min, ALLOW=10s), 50K max. Auto-invalidated on block/allow changes. Evicted every 60s.
3. **Lock-free ring buffer** — Audit trail uses `Map[512]` + `AtomicInteger` instead of `synchronized(Deque)`. No locks on the hot path.
4. **Pattern analyzer LRU cap** — `ConnectionPatternAnalyzer` evicts oldest 10% when profile count exceeds 100K.
5. **Batch verdict API** — `POST /api/v1/proxy/verdicts/batch` for bulk verdict requests.

Run the benchmark: `mvn test -pl ai-engine -Dtest=ProxyIntelligenceLoadTest`

### AI Engine & DMZ proxy security hardening

Cache and API security measures to prevent intelligence leakage and exploitation:

**Cache & API layer:**
1. **API authentication** — All `/api/v1/proxy/*` endpoints require `X-Internal-Key` header. DMZ proxy sends this on every request.
2. **Composite cache key** — Verdict cache key is `ip:port:protocol` (not just IP), preventing cross-port/protocol verdict reuse.
3. **Asymmetric cache TTL** — BLOCK/BLACKHOLE cached 5 min (safe to cache denials). ALLOW cached 10–30s only. Borderline verdicts (risk 30–59) are **never cached**.
4. **DMZ proxy TTL cap** — Local cache enforces strict ceiling: ALLOW≤15s, THROTTLE≤60s, BLOCK≤300s — regardless of AI engine response.
5. **IP masking in audit trail** — Verdict ring buffer masks last octet (e.g. `192.168.1.***`).
6. **Actuator lockdown** — `heapdump`, `env`, `configprops` disabled.

**Input validation & defense-in-depth:**
7. **Event rate limiting** — Max 30 events/IP/minute prevents reputation manipulation via fake AUTH_FAILURE floods.
8. **Full input validation** — IPv4/IPv6 regex + octet range, port 0–65535, event type whitelist, metadata max 50 keys, country alpha-only.
9. **Log injection prevention** — Control characters stripped from all inputs; IPs masked in log messages.
10. **Constant-time key comparison** — `MessageDigest.isEqual()` replaces `String.equals()` in both controllers (timing attack prevention).
11. **SSRF protection** — Port mapping rejects loopback, link-local, cloud metadata, and `0.0.0.0` as targets.
12. **Error message sanitization** — Generic errors returned to callers; full exceptions logged server-side only.
13. **CORS lockdown** — Origins restricted to `localhost:*`; production must override.
14. **No HTTP redirects** — DMZ proxy verdict client uses `Redirect.NEVER` to prevent redirect-chain SSRF.
15. **Race condition fix** — `IpReputation.setScore()` now `synchronized`.
16. **Test coverage** — 33 AI engine tests (incl. 5 validation + 2 auth rejection) + 44 DMZ proxy tests.

**Production safety guards & transport security:**
17. **Startup secret validation** — `SecretSafetyValidator` blocks startup in PROD/STAGING/CERT if JWT secret, control API key, or DB password are still default values. Also validates minimum secret lengths.
18. **GeoIP HTTPS enforcement** — Default API URL changed to `https://ip-api.com`; redirect prevention added; startup blocks loopback/internal URLs.
19. **FTPS keystore password guard** — `FtpsConfig` throws `IllegalStateException` in production if keystore password is "changeit".
20. **`tlsTrustAll` production block** — `HttpForwarderService` throws `SecurityException` in PROD/STAGING/CERT when endpoint has `tlsTrustAll=true`.
21. **Service URL transport audit** — `SecretSafetyValidator` scans all `ServiceClientProperties` URLs and logs ERROR for HTTP in production.
22. **OpenTelemetry TLS** — Production Helm values enforce HTTPS for OTEL collector; OTLP exporter `insecure` set to `false`.

**Phase 4 — Authentication, authorization & input validation hardening:**
23. **Constant-time API key comparison** — `MessageDigest.isEqual()` replaces `String.equals()` in `LicenseController` + all 4 `FileReceiveController` variants (AS2, FTP, FTP-Web, SFTP).
24. **Gateway status endpoint authentication** — All `/internal/gateway/*` endpoints now require `X-Internal-Key` header with constant-time validation.
25. **TOTP 2FA authorization fix** — All TOTP endpoints extract username from `SecurityContext` instead of request body, preventing cross-user 2FA manipulation.
26. **Storage engine file size limits** — `ParallelIOEngine` validates configurable `storage.max-file-size-bytes` (default 10 GB) at all entry points.
27. **Keystore master password guard** — `KeyManagementService` blocks startup in PROD/STAGING/CERT if master password is default or < 16 chars.
28. **Partner receipt IDOR fix** — `/api/partner/receipt/{trackId}` now verifies ownership before returning delivery receipt.
29. **Admin CLI input hardening** — Command length cap (500), control-char stripping, search result cap (100), email validation on `onboard`.
30. **File upload extension blocklist** — FTP-Web rejects 28 dangerous extensions (`.jsp`, `.exe`, `.sh`, etc.) and special filenames.
31. **Batch API size limit** — `/verdicts/batch` and `/events` capped at 1000 items per request.
32. **Strong TOTP backup codes** — 12-char alphanumeric codes (32^12 entropy), SHA-256 hashed before storage.
33. **IP-based auth rate limiting** — `/api/auth/login` and `/register` throttled to 20 requests/minute/IP.
34. **Safe Content-Disposition headers** — RFC 6266 compliant `filename*=UTF-8''` encoding on file downloads.

### Test summary

| Module | Tests | What's tested |
|--------|-------|---------------|
| shared | 112 | Routing engine, resilience patterns, validation, encryption |
| ai-engine | 100 | IP reputation, proxy intelligence (14 unit + 19 integration), data classification, threat intelligence, MITRE ATT&CK, anomaly detection, network behavior, attack chain, playbook engine |
| dmz-proxy | 44 | Protocol detection, rate limiting, **AI verdict client integration (15 tests)** |
| Other modules | Varies | Unit tests for business logic |

---

## Running Locally

### Full platform (Docker Compose)

```bash
# 1. Build
mvn clean package -DskipTests

# 2. Start infrastructure
docker compose up -d postgres rabbitmq

# 3. Wait for Postgres to be ready
docker compose exec postgres pg_isready -U postgres

# 4. Start all services
docker compose up --build -d

# 5. Check status
docker compose ps
```

### Minimal setup (SFTP only)

```bash
docker compose up -d postgres rabbitmq onboarding-api sftp-service admin-ui
```

### Run a service outside Docker (for debugging)

```bash
# 1. Start infrastructure in Docker
docker compose up -d postgres rabbitmq

# 2. Run your service directly with Maven
cd ai-engine
mvn spring-boot:run -Dspring-boot.run.arguments="--DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer"
```

Or run the JAR directly:
```bash
cd ai-engine
mvn clean package -DskipTests
java -jar target/ai-engine-*.jar \
  --DATABASE_URL=jdbc:postgresql://localhost:5432/filetransfer \
  --DB_USERNAME=postgres \
  --DB_PASSWORD=postgres
```

---

## Working on a Single Module

### Example: Working on the AI Engine

```bash
# 1. Build shared library (dependency)
mvn clean install -pl shared -DskipTests

# 2. Build the module
cd ai-engine
mvn clean package

# 3. Run tests (91 tests covering all subsystems)
mvn test

# 4. Run specific test
mvn test -Dtest=ProxyIntelligenceServiceTest

# 5. Start for local development
mvn spring-boot:run

# 6. Test threat intelligence endpoints
curl http://localhost:8091/api/v1/threats/health
curl http://localhost:8091/api/v1/threats/dashboard
curl http://localhost:8091/api/v1/threats/mitre/coverage

# 7. Test background agents
curl http://localhost:8091/api/v1/threats/agents

# 8. Test network behavior analysis
curl -X POST http://localhost:8091/api/v1/threats/analyze/network \
  -H "Content-Type: application/json" \
  -d '{"sourceIp":"203.0.113.5","destinationIp":"10.0.0.1","port":443}'

# 9. Test GeoIP resolution
curl http://localhost:8091/api/v1/threats/geo/resolve/8.8.8.8
```

### Example: Working on the DMZ Proxy

```bash
# 1. Build shared (not actually needed — dmz-proxy doesn't use shared)
# 2. Build and test
cd dmz-proxy
mvn clean package

# 3. Run standalone (no database needed!)
java -jar target/dmz-proxy-*.jar \
  --DMZ_SECURITY_ENABLED=false  # Disable AI for standalone testing
```

### Example: Working on a Frontend

```bash
cd admin-ui
npm install
npm run dev     # Starts Vite dev server with hot reload on :3000
```

---

## Frontend Development

All three frontends use the same stack: **React 18 + Vite + TypeScript + TailwindCSS**

### Setup

```bash
cd admin-ui   # or ftp-web-ui, or partner-portal
npm install
```

### Development (hot reload)

```bash
npm run dev
# Opens http://localhost:3000 (or 3001, 3002)
```

### Build for production

```bash
npm run build
# Output in dist/
```

### Lint

```bash
npm run lint
```

### Key files

| File | Purpose |
|------|---------|
| `src/App.jsx` | Root component with routing |
| `src/pages/` | Page components (one per admin page) |
| `src/components/` | Reusable UI components |
| `vite.config.js` | Build configuration |
| `tailwind.config.js` | Tailwind theme |
| `nginx.conf` | Production nginx config (Docker) |

### Admin UI pages (partner management)

The following pages were added to the Admin UI (`admin-ui`) for partner management:

| Route | Page Component | Description |
|-------|----------------|-------------|
| `/partners` | `Partners` | Partners list with stats, status filters, search, and CRUD |
| `/partners/:id` | `PartnerDetail` | 5-tab detail view: Overview, Accounts, Flows, Endpoints, Settings |
| `/partner-setup` | `PartnerSetup` | 6-step onboarding wizard (Company Info, Protocols, Contacts, Account Setup, SLA, Review) |
| `/services` | `ServiceManagement` | Microservice health dashboard with architecture overview |

These pages are accessible from the sidebar navigation under "Partner Management", "Onboard Partner", and "Services". The main Dashboard page also includes a quick-action link for Partners.

---

## Debugging

### View service logs

```bash
# Follow logs for a service
docker compose logs -f ai-engine

# Last 100 lines
docker compose logs --tail=100 dmz-proxy

# All services
docker compose logs -f
```

### Check service health

```bash
# Spring Boot actuator
curl http://localhost:8091/actuator/health     # AI engine
curl http://localhost:8080/actuator/health     # Onboarding API
curl http://localhost:8088/api/proxy/health    # DMZ proxy

# Quick status check for all services
for port in 8080 8081 8082 8083 8084 8085 8086 8087 8088 8089 8090 8091 8092 8093 8094 8095; do
  status=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$port/actuator/health 2>/dev/null)
  echo "Port $port: $status"
done
```

### Debug database

```bash
# Open psql
docker compose exec postgres psql -U postgres -d filetransfer

# Useful queries
SELECT tablename FROM pg_tables WHERE schemaname = 'public';
SELECT count(*) FROM transfer_accounts;
SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT 10;
```

### Debug RabbitMQ

Open http://localhost:15672 (guest/guest)
- **Queues tab** — see message counts
- **Connections tab** — see which services are connected
- **Exchanges tab** — see event routing

### Remote debugging with IDE

Add to your `docker-compose.override.yml`:
```yaml
services:
  ai-engine:
    environment:
      JAVA_TOOL_OPTIONS: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
    ports:
      - "5005:5005"
```

Then in your IDE, create a "Remote JVM Debug" configuration pointing to `localhost:5005`.

---

## Code Style

### Java
- **Java 21** features are used (records, pattern matching, sealed classes)
- **Lombok** for boilerplate reduction (@Data, @Getter, @Builder, @Slf4j)
- **Spring Boot conventions** — constructor injection, @Service/@Component annotations
- No tabs — 4 spaces indentation
- Imports: java → javax → org → com (alphabetical within groups)

### Frontend
- **TypeScript** strict mode
- **Functional components** with hooks
- **TailwindCSS** for styling (no CSS files)

---

## Adding a New Service

1. **Create module directory:**
```bash
mkdir my-service
```

2. **Create `pom.xml`** (copy from an existing simple service like `edi-converter`):
```xml
<parent>
    <groupId>com.filetransfer</groupId>
    <artifactId>file-transfer-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</parent>
<artifactId>my-service</artifactId>
```

3. **Add to parent POM:**
```xml
<!-- In root pom.xml -->
<modules>
    ...
    <module>my-service</module>
</modules>
```

4. **Create main class:**
```java
@SpringBootApplication
public class MyServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyServiceApplication.class, args);
    }
}
```

5. **Create `application.yml`:**
```yaml
server:
  port: ${SERVER_PORT:8099}
```

6. **Create `Dockerfile`:**
```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/my-service-*.jar app.jar
EXPOSE 8099
ENTRYPOINT ["java", "-jar", "app.jar"]
```

7. **Add to `docker-compose.yml`:**
```yaml
my-service:
  build: ./my-service
  ports:
    - "8099:8099"
  environment:
    <<: *common-env
    SERVER_PORT: 8099
```

---

## Inter-Service Communication

### Service Client Framework

Every microservice can call any other microservice through typed client classes in the `shared` module (`com.filetransfer.shared.client`). This replaces ad-hoc `RestTemplate` calls and hardcoded URLs.

**Architecture:**

```
BaseServiceClient (abstract)
├── EncryptionServiceClient    — encrypt/decrypt files and credentials
├── ScreeningServiceClient     — malware/content scanning
├── AnalyticsServiceClient     — dashboard, metrics, alerts
├── KeystoreServiceClient      — keys, certs, TLS management
├── StorageServiceClient       — file store/retrieve, lifecycle
├── EdiConverterClient         — EDI parsing, conversion, validation
├── ConfigServiceClient        — flows, endpoints, connectors, SLA
├── ForwarderServiceClient     — external file delivery
├── GatewayServiceClient       — gateway status, legacy servers
├── As2ServiceClient           — AS2/AS4 messaging
├── AiEngineClient             — classification, DLP, routing AI
├── LicenseServiceClient       — license validation, catalog
├── DmzProxyClient             — proxy mappings
└── OnboardingApiClient        — accounts, service registry
```

### Using a Service Client

Just inject it — Spring auto-wires everything:

```java
@Service
@RequiredArgsConstructor
public class MyService {
    private final ScreeningServiceClient screeningService;
    private final EncryptionServiceClient encryptionService;

    public void process(Path file) {
        // Scan file for malware
        Map<String, Object> result = screeningService.scanFile(file, "TRZ123", "acme");

        // Encrypt a credential
        String encrypted = encryptionService.encryptCredential("password123");
    }
}
```

### Configuration

All service URLs are configured in `application.yml` under `platform.services`:

```yaml
platform:
  services:
    encryption-service:
      url: ${ENCRYPTION_SERVICE_URL:http://encryption-service:8086}
    screening-service:
      url: ${SCREENING_SERVICE_URL:http://screening-service:8092}
      enabled: false  # disable if not needed
```

Each endpoint supports: `url`, `enabled` (default: true), `connect-timeout-ms` (default: 5000), `read-timeout-ms` (default: 30000).

### Error Strategies

Each client uses an appropriate error strategy:

| Strategy | Behavior | Used By |
|---|---|---|
| **Fail-fast** | Exception propagates to caller | Encryption, Screening, Config, Storage, Forwarder, AS2, EDI |
| **Graceful degradation** | Returns safe default on failure | AI Engine, Analytics, Gateway, License |
| **Swallow-and-log** | Logs warning, returns null/empty | Keystore Manager |

### Adding a Client for a New Service

1. Create `MyServiceClient extends BaseServiceClient` in `shared/src/main/java/.../client/`
2. Add a `ServiceEndpoint` field to `ServiceClientProperties`
3. Add the URL config to all `application.yml` files
4. Annotate with `@Component` — auto-discovered by Spring

---

## Platform Infrastructure

### Resilience Patterns

All 14 inter-service REST clients use circuit breakers and retry via `ResilientServiceClient`. To make a new service client resilient:

```java
// Change extends BaseServiceClient to extends ResilientServiceClient
public class MyServiceClient extends ResilientServiceClient {
    
    // Wrap REST calls with withResilience()
    public Data getData(String id) {
        return withResilience("getData", () -> get("/api/data/" + id, Data.class));
    }
}
```

Monitor circuit breaker state:
```bash
# Check circuit breaker state via actuator
curl http://localhost:8080/actuator/health

# Override resilience defaults in application.yml
platform:
  resilience:
    circuit-breaker:
      failure-rate-threshold: 50
      sliding-window-size: 10
      wait-duration-seconds: 30
    retry:
      max-attempts: 3
      wait-duration-ms: 500
```

### Correlation IDs

Every request gets a correlation ID that flows across services:

```bash
# Pass correlation ID manually
curl -H "X-Correlation-ID: my-debug-123" http://localhost:8080/api/accounts

# Response includes the correlation ID
# X-Correlation-ID: my-debug-123

# Search logs by correlation ID
grep "my-debug-123" logs/*.log
```

Log format: `2024-01-15 10:30:00.123 [a1b2c3d4] [onboarding-api] INFO ...`

### Prometheus Metrics

All services expose Prometheus metrics:

```bash
# Scrape metrics
curl http://localhost:8080/actuator/prometheus

# Key metrics
jvm_memory_used_bytes{area="heap"}
jvm_gc_pause_seconds_count
system_cpu_usage
http_server_requests_seconds_count
```

### Error Handling

All services return standardized error responses:

```bash
# Example: Entity not found
curl http://localhost:8080/api/accounts/nonexistent
# Response:
# {
#   "timestamp": "2024-01-15T10:30:00Z",
#   "status": 404,
#   "error": "Not Found",
#   "code": "ENTITY_NOT_FOUND",
#   "message": "Account with ID nonexistent not found",
#   "path": "/api/accounts/nonexistent",
#   "correlationId": "a1b2c3d4"
# }

# Throw platform exceptions in your code:
throw new EntityNotFoundException("Account", id);
throw new ServiceUnavailableException("encryption-service");
throw new PlatformException(ErrorCode.QUOTA_EXCEEDED, "Monthly transfer limit reached");
```

### RBAC (Role-Based Access Control)

Protect endpoints with role annotations:

```java
import com.filetransfer.shared.security.Roles;

@RestController
public class AdminController {
    
    @GetMapping("/api/users")
    @PreAuthorize(Roles.ADMIN)           // Admin only
    public List<User> listUsers() { ... }
    
    @GetMapping("/api/transfers")
    @PreAuthorize(Roles.OPERATOR)        // Admin + Operator
    public List<Transfer> listTransfers() { ... }
    
    @GetMapping("/api/dashboard")
    @PreAuthorize(Roles.VIEWER)          // Any authenticated role
    public Dashboard getDashboard() { ... }
}
```

Available roles: ADMIN > OPERATOR > USER > VIEWER. Plus PARTNER (external) and SYSTEM (inter-service). All 29 user-facing controllers have class-level `@PreAuthorize` annotations enforced.

### Entity Auditing

18 entities extend `Auditable` for automatic audit fields (V13 + V14 migrations). New entities should also extend it:

```java
@Entity
public class MyEntity extends Auditable {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String name;
    // created_at, updated_at, created_by, updated_by are automatic
}
```

### OpenAPI / Swagger

Every service has auto-generated API docs:

```bash
# Swagger UI (interactive)
open http://localhost:8080/swagger-ui.html

# OpenAPI spec (JSON)
curl http://localhost:8080/v3/api-docs

# OpenAPI spec (YAML)
curl http://localhost:8080/v3/api-docs.yaml
```

### Environment Profiles

```bash
# Development (default) — debug logging, Swagger UI
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run

# Test — CI pipeline mode
SPRING_PROFILES_ACTIVE=test mvn test

# Production — env-var secrets, tuned resilience
SPRING_PROFILES_ACTIVE=prod java -jar service.jar
```

Production requires these environment variables:
- `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`
- `JWT_SECRET`, `CONTROL_API_KEY`

---

## Partner Management API

The Partner Management system is served by the `onboarding-api` service (`:8080`). The controller is `PartnerManagementController` and the business logic lives in `PartnerService`.

### Key source files

| File | Module | Purpose |
|------|--------|---------|
| `shared/.../entity/Partner.java` | shared | JPA entity for the `partners` table |
| `shared/.../entity/PartnerContact.java` | shared | JPA entity for the `partner_contacts` table |
| `onboarding-api/.../controller/PartnerManagementController.java` | onboarding-api | REST controller with 12 endpoints at `/api/partners` |
| `onboarding-api/.../service/PartnerService.java` | onboarding-api | CRUD + lifecycle operations |
| `onboarding-api/.../dto/request/CreatePartnerRequest.java` | onboarding-api | Request DTO for creating a partner (includes nested contacts) |
| `onboarding-api/.../dto/request/UpdatePartnerRequest.java` | onboarding-api | Request DTO for partial partner updates |
| `onboarding-api/.../dto/response/PartnerDetailResponse.java` | onboarding-api | Response DTO with partner, contacts, and resource counts |

### API endpoints

All endpoints require authentication (JWT). Base path: `/api/partners`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/partners` | Create partner with optional contacts |
| `GET` | `/api/partners` | List partners (filter: `?status=ACTIVE&type=EXTERNAL`) |
| `GET` | `/api/partners/{id}` | Get detail view (partner + contacts + counts) |
| `PUT` | `/api/partners/{id}` | Partial update |
| `DELETE` | `/api/partners/{id}` | Soft-delete (sets status to `OFFBOARDED`) |
| `POST` | `/api/partners/{id}/activate` | Set status=`ACTIVE`, phase=`LIVE` |
| `POST` | `/api/partners/{id}/suspend` | Set status=`SUSPENDED` |
| `GET` | `/api/partners/stats` | Counts by status (`total`, `PENDING`, `ACTIVE`, ...) |
| `GET` | `/api/partners/{id}/accounts` | Transfer accounts for this partner |
| `POST` | `/api/partners/{id}/accounts` | Create account and link to partner |
| `GET` | `/api/partners/{id}/flows` | File flows for this partner |
| `GET` | `/api/partners/{id}/endpoints` | Delivery endpoints for this partner |

### Example: Create a partner

```bash
curl -X POST http://localhost:8080/api/partners \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{
    "companyName": "Acme Corp",
    "industry": "Financial Services",
    "website": "https://acme.com",
    "partnerType": "EXTERNAL",
    "protocolsEnabled": ["SFTP", "AS2"],
    "slaTier": "PREMIUM",
    "maxFileSizeBytes": 1073741824,
    "maxTransfersPerDay": 5000,
    "retentionDays": 180,
    "contacts": [
      {
        "name": "Jane Doe",
        "email": "jane@acme.com",
        "phone": "+1-555-0100",
        "role": "Technical",
        "primary": true
      }
    ]
  }'
```

### Lifecycle operations

```bash
# Activate a partner (moves to ACTIVE status, LIVE phase)
curl -X POST http://localhost:8080/api/partners/{id}/activate \
  -H "Authorization: Bearer <JWT>"

# Suspend a partner
curl -X POST http://localhost:8080/api/partners/{id}/suspend \
  -H "Authorization: Bearer <JWT>"

# Soft-delete (offboard) a partner
curl -X DELETE http://localhost:8080/api/partners/{id} \
  -H "Authorization: Bearer <JWT>"
```

### Keystore Manager API (port 8093)

**Generate PGP keypair:**
```bash
curl -X POST http://localhost:8093/api/v1/keys/generate/pgp \
  -H "Content-Type: application/json" \
  -d '{"alias":"partner-acme-pgp","identity":"Acme Corp <sec@acme.com>","passphrase":"strong-pass"}'
```

**Generate SSH host key:**
```bash
curl -X POST http://localhost:8093/api/v1/keys/generate/ssh-host \
  -H "Content-Type: application/json" \
  -d '{"alias":"sftp-host-prod","ownerService":"sftp-service"}'
```

**Generate TLS certificate:**
```bash
curl -X POST http://localhost:8093/api/v1/keys/generate/tls \
  -H "Content-Type: application/json" \
  -d '{"alias":"tls-gateway","cn":"mft.company.com","validDays":"365"}'
```

**Import a key:**
```bash
curl -X POST http://localhost:8093/api/v1/keys/import \
  -H "Content-Type: application/json" \
  -d '{"alias":"partner-pgp-pub","keyType":"PGP_PUBLIC","keyMaterial":"-----BEGIN PGP PUBLIC KEY BLOCK-----\n...","description":"ACME PGP public key","ownerService":"sftp-service","partnerAccount":"acme-corp"}'
```

**Rotate a key:**
```bash
curl -X POST http://localhost:8093/api/v1/keys/sftp-host-prod/rotate \
  -H "Content-Type: application/json" \
  -d '{"newAlias":"sftp-host-prod-v2"}'
```

**Download public key:**
```bash
curl -O http://localhost:8093/api/v1/keys/partner-acme-pgp/download?part=public
```

**Deactivate a key:**
```bash
curl -X DELETE http://localhost:8093/api/v1/keys/old-key-alias
```

**View key statistics:**
```bash
curl http://localhost:8093/api/v1/keys/stats
```

**Check expiring keys:**
```bash
curl http://localhost:8093/api/v1/keys/expiring?days=30
```

### Gateway Service API (port 8085)

**Get gateway status:**
```bash
curl http://localhost:8085/internal/gateway/status
```

**Get full route table:**
```bash
curl http://localhost:8085/internal/gateway/routes
```

**Get gateway statistics:**
```bash
curl http://localhost:8085/internal/gateway/stats
```

**List legacy server fallbacks:**
```bash
curl http://localhost:8085/internal/gateway/legacy-servers
curl http://localhost:8085/internal/gateway/legacy-servers?protocol=SFTP
```

### DMZ Proxy API (port 8088)

**Get proxy health:**
```bash
curl http://localhost:8088/api/proxy/health
```

**List port mappings (requires control key):**
```bash
curl -H "X-Internal-Key: internal_control_secret" http://localhost:8088/api/proxy/mappings
```

**Add a port mapping:**
```bash
curl -X POST http://localhost:8088/api/proxy/mappings \
  -H "Content-Type: application/json" \
  -H "X-Internal-Key: internal_control_secret" \
  -d '{"name":"sftp-partner","listenPort":3333,"targetHost":"gateway-service","targetPort":2220}'
```

**Remove a port mapping:**
```bash
curl -X DELETE -H "X-Internal-Key: internal_control_secret" \
  http://localhost:8088/api/proxy/mappings/sftp-partner
```

**Get security stats:**
```bash
curl -H "X-Internal-Key: internal_control_secret" http://localhost:8088/api/proxy/security/stats
```

**Get per-IP intelligence:**
```bash
curl -H "X-Internal-Key: internal_control_secret" http://localhost:8088/api/proxy/security/ip/203.0.113.5
```

### External Forwarder API (port 8087)

**Check active transfers:**
```bash
curl http://localhost:8087/api/forward/transfers/active
```

**Forwarder health:**
```bash
curl http://localhost:8087/api/forward/health
```

---

## Database Migrations

TranzFer uses **Flyway** for database schema migrations. Migration files are in the `shared` module.

### Location
```
shared/src/main/resources/db/migration/
├── V1__baseline.sql
├── V2__add_server_instance.sql
├── V3__generalize_server_instances.sql
├── V4__platform_settings.sql
├── V5__cluster_awareness.sql
├── V6__delivery_endpoints.sql
├── V7__delivery_proxy_support.sql
├── V8__shedlock_and_as2.sql
├── V9__as2_protocol_support.sql
├── V10__add_updated_at_to_file_transfer_records.sql
├── V11__add_as2_partnership_id_to_delivery_endpoints.sql
├── V12__add_partners.sql
├── V13__add_audit_columns.sql
├── V14__add_audit_columns_phase2.sql
└── V15__add_threat_intelligence_tables.sql
```

### V12: Partner Management Tables

`V12__add_partners.sql` adds the partner management schema:

- **`partners`** table — stores partner company info, status/lifecycle, onboarding phase, protocol config (JSONB), and SLA parameters
- **`partner_contacts`** table — contact persons associated with a partner (many-to-one via `partner_id` FK with `ON DELETE CASCADE`)
- **Foreign keys** added to existing tables: `partner_id` column on `transfer_accounts`, `delivery_endpoints`, `file_flows`, and `partner_agreements`
- **Indexes** on `partners(status)`, `partners(partner_type)`, `partners(slug)`, `partner_contacts(partner_id)`, and partial indexes on the FK columns

### V15: Threat Intelligence & Incident Management Tables

`V15__add_threat_intelligence_tables.sql` adds the cybersecurity schema for the AI engine:

- **`threat_indicators`** — Indicators of compromise (IPs, domains, hashes, URLs) with type, severity, source, and confidence scores
- **`security_alerts`** — Generated security alerts with MITRE ATT&CK technique mapping
- **`security_events`** — Unified security event log (connections, verdicts, detections)
- **`threat_actors`** — Known threat actor profiles linked to campaigns and indicators
- **`attack_campaigns`** — Campaign records linking multiple actors and indicators
- **`verdict_records`** — Persisted verdict history for every proxy decision, enabling post-restart continuity
- **`security_incidents`** — Incident lifecycle records with timeline and status tracking

### Adding a migration

1. Create a new file following the naming convention: `V{next_number}__{description}.sql`
2. Write your SQL
3. Rebuild `shared`: `mvn clean install -pl shared`
4. Restart any service — Flyway runs migrations automatically on startup

### Important rules
- **Never modify existing migration files** — Flyway checksums will fail
- **Always add new files** with incrementing version numbers
- **Test migrations** on a fresh database: `docker compose down -v && docker compose up -d`

---

## Operations Scripts

Scripts in `scripts/` for production operations:

### Pre-Flight Security Check

**Must run before any production deployment.**

```bash
# Check current environment variables
./scripts/preflight-check.sh --env

# Audit a docker-compose file for default secrets
./scripts/preflight-check.sh --compose docker-compose.yml
```

Detects 9 categories of insecure defaults (JWT secret, DB password, encryption key, etc.). In PROD mode, any default found is a hard failure. See `ARCHITECTURE.md` for the full list.

### Database Backup & Restore

```bash
# Backup (Docker mode)
./scripts/backup.sh --docker --backup-dir ./backups --keep 30

# Backup (direct connection)
DB_HOST=db.prod DB_PASSWORD=secret ./scripts/backup.sh --host

# Restore (requires --confirm)
./scripts/restore.sh --docker backups/mft-backup-2026-04-05.dump --confirm

# Data-only restore (schema stays, only data replaced)
./scripts/restore.sh --docker backup.dump --data-only --confirm

# Set up daily cron
./scripts/backup-cron-setup.sh --mode docker --keep 30

# Generate Kubernetes CronJob manifest
./scripts/backup-cron-setup.sh --k8s-only
```

---

## Common Issues

### "Cannot find symbol" when building a module
The `shared` module hasn't been installed to your local Maven repo:
```bash
mvn clean install -pl shared -DskipTests
```

### "Port already in use"
```bash
# Find what's using the port
lsof -i :8080
# Kill it or change the port
```

### "Flyway migration checksum mismatch"
Someone modified an existing migration file. Reset:
```bash
docker compose down -v  # WARNING: deletes all data
docker compose up -d
```

### "Connection refused" to another service
Services may not be ready yet. Check health:
```bash
docker compose ps                          # Check if running
docker compose logs --tail=50 <service>    # Check for errors
```

### Mockito issues with Java 21+
Mockito cannot mock certain classes (especially Netty interfaces) on Java 21+. Use real implementations or `EmbeddedChannel` for Netty tests:
```java
// Don't do this:
Channel ch = mock(Channel.class);  // Fails on Java 21+

// Do this instead:
Channel ch = new EmbeddedChannel();  // Works
```

### Mockito / Java 25 compatibility
For Java 25+, the project uses `mockito-subclass` 5.14.2 as a Mockito mock-maker dependency (avoids illegal-access errors with the default inline mock-maker). The parent POM also includes surefire `--add-opens` arguments for `java.base` modules. If you see `InaccessibleObjectException` in tests, ensure you are building from the root POM or have the latest `shared` installed.

### "OutOfMemoryError" when building everything
```bash
export MAVEN_OPTS="-Xmx2g"
mvn clean package -DskipTests
```

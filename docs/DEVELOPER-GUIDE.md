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
11. [Database Migrations](#database-migrations)
12. [Common Issues](#common-issues)

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
├── ai-engine/                  ← AI brain (classification, proxy intelligence)
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

### Test summary

| Module | Tests | What's tested |
|--------|-------|---------------|
| ai-engine | 79 | IP reputation, protocol threats, connection patterns, geo anomalies, proxy intelligence, data classification |
| dmz-proxy | 29 | Protocol detection, rate limiting, connection tracking |
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

# 3. Run tests
mvn test

# 4. Run specific test
mvn test -Dtest=ProxyIntelligenceServiceTest

# 5. Start for local development
mvn spring-boot:run
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
| `src/App.tsx` | Root component with routing |
| `src/pages/` | Page components (one per admin page) |
| `src/components/` | Reusable UI components |
| `vite.config.js` | Build configuration |
| `tailwind.config.js` | Tailwind theme |
| `nginx.conf` | Production nginx config (Docker) |

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

## Database Migrations

TranzFer uses **Flyway** for database schema migrations. Migration files are in the `shared` module.

### Location
```
shared/src/main/resources/db/migration/
├── V1__initial_schema.sql
├── V2__add_audit_logs.sql
├── V3__add_flow_tables.sql
└── ...
```

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

### "OutOfMemoryError" when building everything
```bash
export MAVEN_OPTS="-Xmx2g"
mvn clean package -DskipTests
```

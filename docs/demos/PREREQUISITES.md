# TranzFer MFT — Prerequisites & Environment Setup

> **Read this FIRST.** Before installing any TranzFer microservice, this guide ensures your system is ready. Nothing should surprise you.

---

## How This Guide Works

Every TranzFer microservice needs **at minimum** one of two things:
1. **Docker** (recommended — works on any OS, no Java install needed), OR
2. **Java 21 + Maven** (build from source)

Some services also need **PostgreSQL** and/or **RabbitMQ**. The table below tells you exactly what each service requires so you can prepare in advance.

---

## Dependency Matrix — Know Before You Install

| Service | Java 21 | Docker | PostgreSQL | RabbitMQ | Special |
|---------|:-------:|:------:|:----------:|:--------:|---------|
| **EDI Converter** | * | * | — | — | Easiest to start with |
| **DMZ Proxy** | * | * | — | — | Ports 8088, 2222, 2121, 443 |
| **Encryption Service** | * | * | **Required** | — | Master key (64-char hex) |
| **Keystore Manager** | * | * | **Required** | — | Master password |
| **Screening Service** | * | * | **Required** | — | |
| **License Service** | * | * | **Required** | — | Admin key |
| **Storage Manager** | * | * | **Required** | — | Disk space for 4 tiers |
| **AS2 Service** | * | * | **Required** | — | |
| **AI Engine** | * | * | **Required** | — | Optional: Claude API key |
| **Analytics Service** | * | * | **Required** | **Required** | |
| **SFTP Service** | * | * | **Required** | **Required** | Port 2222 (SFTP protocol) |
| **FTP Service** | * | * | **Required** | **Required** | Port 21, passive 21000-21010 |
| **FTP-Web Service** | * | * | **Required** | **Required** | |
| **Gateway Service** | * | * | **Required** | **Required** | Ports 2220, 2122 |
| **External Forwarder** | * | * | **Required** | **Required** | Optional: Kafka |
| **Onboarding API** | * | * | **Required** | **Required** | Core platform service |
| **Config Service** | * | * | **Required** | **Required** | |

`*` = Only needed if using that installation method. Pick Docker OR Java — you don't need both.

---

## Step 1 — Choose Your Installation Method

### Option A: Docker (Recommended for All OS)

Docker is the fastest path. One command pulls everything you need.

#### Linux (Ubuntu / Debian)

```bash
# Update package index
sudo apt-get update

# Install Docker
sudo apt-get install -y docker.io docker-compose-plugin

# Start Docker and enable on boot
sudo systemctl start docker
sudo systemctl enable docker

# Add your user to the docker group (avoids needing sudo)
sudo usermod -aG docker $USER

# Log out and back in, then verify
docker --version
docker compose version
```

#### Linux (CentOS / RHEL / Fedora)

```bash
# Install Docker (CentOS/RHEL 8+)
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

# Start Docker
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker $USER

# Verify
docker --version
docker compose version
```

#### macOS

```bash
# Option 1: Homebrew (recommended)
brew install --cask docker
# Open Docker Desktop from Applications, wait for it to start

# Option 2: Download Docker Desktop from https://www.docker.com/products/docker-desktop/
# Choose "Mac with Apple chip" or "Mac with Intel chip"

# Verify
docker --version
docker compose version
```

#### Windows

```powershell
# Option 1: winget (Windows 11 / Windows 10 with App Installer)
winget install Docker.DockerDesktop

# Option 2: Chocolatey
choco install docker-desktop

# Option 3: Download from https://www.docker.com/products/docker-desktop/

# After install:
# 1. Restart your computer
# 2. Open Docker Desktop
# 3. If prompted, enable WSL 2 backend (recommended)
# 4. Wait for Docker to start (green icon in system tray)

# Verify (open PowerShell or Command Prompt)
docker --version
docker compose version
```

**Windows Notes:**
- WSL 2 backend is recommended over Hyper-V for better performance
- If you see "WSL 2 is not installed", run: `wsl --install` and restart
- Ensure virtualization is enabled in BIOS (usually under CPU settings)

---

### Option B: Java 21 + Maven (Build from Source)

Use this if you want to modify code, run in an IDE, or cannot use Docker.

#### Linux (Ubuntu / Debian)

```bash
# Install Java 21 (Eclipse Temurin — recommended)
sudo apt-get install -y wget apt-transport-https
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo apt-key add -
echo "deb https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update
sudo apt-get install -y temurin-21-jdk

# Install Maven
sudo apt-get install -y maven

# Verify
java -version    # Should show: openjdk version "21.x.x"
mvn -version     # Should show: Apache Maven 3.x.x
```

#### Linux (CentOS / RHEL / Fedora)

```bash
# Install Java 21
sudo dnf install -y java-21-openjdk java-21-openjdk-devel

# Install Maven
sudo dnf install -y maven

# Verify
java -version
mvn -version
```

#### macOS

```bash
# Install Java 21 via Homebrew
brew install openjdk@21

# Add to PATH (add to ~/.zshrc for persistence)
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc

# Install Maven
brew install maven

# Verify
java -version    # Should show: openjdk version "21.x.x"
mvn -version
```

#### Windows

```powershell
# Option 1: winget
winget install EclipseAdoptium.Temurin.21.JDK
winget install Apache.Maven

# Option 2: Chocolatey
choco install temurin21
choco install maven

# Option 3: Manual
# Download from https://adoptium.net/temurin/releases/?version=21
# Download Maven from https://maven.apache.org/download.cgi
# Add both to your PATH environment variable

# Verify (restart terminal after install)
java -version
mvn -version
```

**Windows Notes:**
- After installing Java, you may need to set `JAVA_HOME`:
  ```powershell
  [Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot", "Machine")
  ```
- Restart your terminal after setting environment variables

---

## Step 2 — Install PostgreSQL (If Your Service Needs It)

Check the [Dependency Matrix](#dependency-matrix--know-before-you-install) above. If your service shows **Required** under PostgreSQL, install it here.

### Option A: Docker (Recommended — Any OS)

```bash
# Start PostgreSQL 16 with optimized settings (same as TranzFer production config)
docker run -d \
  --name mft-postgres \
  -e POSTGRES_DB=filetransfer \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine

# Verify it's running
docker exec mft-postgres pg_isready -U postgres
# Should output: /var/run/postgresql:5432 - accepting connections
```

### Option B: Native Install

#### Linux (Ubuntu / Debian)

```bash
sudo apt-get install -y postgresql-16
sudo systemctl start postgresql
sudo systemctl enable postgresql

# Create the database
sudo -u postgres psql -c "CREATE DATABASE filetransfer;"
sudo -u postgres psql -c "ALTER USER postgres PASSWORD 'postgres';"
```

#### Linux (CentOS / RHEL)

```bash
sudo dnf install -y postgresql16-server
sudo postgresql-setup --initdb
sudo systemctl start postgresql
sudo systemctl enable postgresql

sudo -u postgres psql -c "CREATE DATABASE filetransfer;"
sudo -u postgres psql -c "ALTER USER postgres PASSWORD 'postgres';"
```

#### macOS

```bash
brew install postgresql@16
brew services start postgresql@16

createdb filetransfer
psql -c "ALTER USER $(whoami) PASSWORD 'postgres';"
```

#### Windows

```powershell
# Option 1: winget
winget install PostgreSQL.PostgreSQL.16

# Option 2: Chocolatey
choco install postgresql16

# Option 3: Download from https://www.postgresql.org/download/windows/

# After install, open pgAdmin or psql and create:
# Database: filetransfer
# User: postgres / Password: postgres
```

### Verify PostgreSQL

```bash
# From any OS (Docker or native)
psql -h localhost -U postgres -d filetransfer -c "SELECT version();"
# Should show: PostgreSQL 16.x
```

---

## Step 3 — Install RabbitMQ (If Your Service Needs It)

Check the [Dependency Matrix](#dependency-matrix--know-before-you-install) above. Only install if **Required**.

### Option A: Docker (Recommended — Any OS)

```bash
# Start RabbitMQ with management UI
docker run -d \
  --name mft-rabbitmq \
  -e RABBITMQ_DEFAULT_USER=guest \
  -e RABBITMQ_DEFAULT_PASS=guest \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3.13-management-alpine

# Verify
docker exec mft-rabbitmq rabbitmq-diagnostics ping
# Should output: Ping succeeded
```

**Management UI:** Open http://localhost:15672 in your browser (guest / guest)

### Option B: Native Install

#### Linux (Ubuntu / Debian)

```bash
# Add RabbitMQ repository and install
sudo apt-get install -y curl gnupg
curl -1sLf 'https://dl.cloudsmith.io/public/rabbitmq/rabbitmq-erlang/setup.deb.sh' | sudo -E bash
curl -1sLf 'https://dl.cloudsmith.io/public/rabbitmq/rabbitmq-server/setup.deb.sh' | sudo -E bash
sudo apt-get install -y rabbitmq-server

sudo systemctl start rabbitmq-server
sudo systemctl enable rabbitmq-server
sudo rabbitmq-plugins enable rabbitmq_management
```

#### macOS

```bash
brew install rabbitmq
brew services start rabbitmq
# Management UI: http://localhost:15672
```

#### Windows

```powershell
# Install Erlang first (required by RabbitMQ)
choco install erlang
choco install rabbitmq

# Or download from https://www.rabbitmq.com/install-windows.html
# Enable management plugin:
rabbitmq-plugins enable rabbitmq_management
```

### Verify RabbitMQ

```bash
# Management UI
curl -s http://localhost:15672/api/overview -u guest:guest | head -1
# Should return JSON with RabbitMQ version info
```

---

## Step 4 — Clone the Repository

```bash
# HTTPS
git clone https://github.com/rdship/tranzfer-mft.git
cd tranzfer-mft

# Or SSH
git clone git@github.com:rdship/tranzfer-mft.git
cd tranzfer-mft
```

**Windows note:** Use Git Bash, WSL, or PowerShell. If git is not installed:
```powershell
winget install Git.Git
```

---

## Step 5 — Build from Source (Only if NOT Using Docker)

```bash
# Build all services (from the repository root)
mvn clean package -DskipTests

# Build a single service (faster)
mvn clean package -DskipTests -pl edi-converter -am
mvn clean package -DskipTests -pl encryption-service -am
# Replace with any service directory name
```

**Build requirements:**
- ~2 GB free disk space for Maven dependencies (first build)
- ~4 GB RAM minimum
- Internet connection (for downloading dependencies)

**First build** will take 5-15 minutes (downloading dependencies). Subsequent builds take 1-3 minutes.

---

## Step 6 — Network & Firewall Checklist

Ensure these ports are available before starting services:

| Port | Service | Protocol |
|------|---------|----------|
| 5432 | PostgreSQL | TCP |
| 5672 | RabbitMQ (AMQP) | TCP |
| 15672 | RabbitMQ (Management UI) | TCP |
| 8080 | Onboarding API | HTTP |
| 8081 | SFTP Service (Admin) | HTTP |
| 8082 | FTP Service (Admin) | HTTP |
| 8083 | FTP-Web Service | HTTP |
| 8084 | Config Service | HTTP |
| 8085 | Gateway Service (Admin) | HTTP |
| 8086 | Encryption Service | HTTP |
| 8087 | External Forwarder | HTTP |
| 8088 | DMZ Proxy | HTTP |
| 8089 | License Service | HTTP |
| 8090 | Analytics Service | HTTP |
| 8091 | AI Engine | HTTP |
| 8092 | Screening Service | HTTP |
| 8093 | Keystore Manager | HTTP |
| 8094 | Storage Manager | HTTP |
| 8095 | EDI Converter | HTTP |
| 2222 | SFTP Service | SFTP |
| 21 | FTP Service | FTP |
| 21000-21010 | FTP Passive | FTP |
| 2220 | Gateway SFTP | SFTP |
| 2122 | Gateway FTP | FTP |

**Check if a port is in use:**

```bash
# Linux / macOS
lsof -i :8080
# or
ss -tlnp | grep 8080

# Windows (PowerShell)
netstat -ano | findstr :8080
# or
Get-NetTCPConnection -LocalPort 8080
```

**Free up a port:**
```bash
# Linux / macOS — kill process on port 8080
kill $(lsof -t -i:8080)

# Windows (PowerShell) — find PID then kill
$pid = (Get-NetTCPConnection -LocalPort 8080).OwningProcess
Stop-Process -Id $pid -Force
```

---

## Quick Verification Script

Run this to check if your system is ready:

```bash
echo "=== TranzFer MFT Prerequisites Check ==="
echo ""

# Check Docker
if command -v docker &> /dev/null; then
    echo "[OK] Docker: $(docker --version)"
else
    echo "[--] Docker: Not installed (needed for Docker installation method)"
fi

# Check Docker Compose
if docker compose version &> /dev/null 2>&1; then
    echo "[OK] Docker Compose: $(docker compose version --short)"
else
    echo "[--] Docker Compose: Not available"
fi

# Check Java
if command -v java &> /dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1)
    echo "[OK] Java: $JAVA_VER"
else
    echo "[--] Java: Not installed (needed for source build method)"
fi

# Check Maven
if command -v mvn &> /dev/null; then
    echo "[OK] Maven: $(mvn -version 2>&1 | head -1)"
else
    echo "[--] Maven: Not installed (needed for source build method)"
fi

# Check Git
if command -v git &> /dev/null; then
    echo "[OK] Git: $(git --version)"
else
    echo "[--] Git: Not installed"
fi

# Check PostgreSQL
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q mft-postgres; then
    echo "[OK] PostgreSQL: Running in Docker (mft-postgres)"
elif pg_isready -h localhost -p 5432 &> /dev/null; then
    echo "[OK] PostgreSQL: Running natively on port 5432"
else
    echo "[--] PostgreSQL: Not running (needed by most services)"
fi

# Check RabbitMQ
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q mft-rabbitmq; then
    echo "[OK] RabbitMQ: Running in Docker (mft-rabbitmq)"
elif curl -s http://localhost:15672 &> /dev/null; then
    echo "[OK] RabbitMQ: Running on port 15672"
else
    echo "[--] RabbitMQ: Not running (needed by some services)"
fi

# Check curl
if command -v curl &> /dev/null; then
    echo "[OK] curl: $(curl --version | head -1)"
else
    echo "[!!] curl: Not installed (needed for testing APIs)"
fi

echo ""
echo "=== Check complete ==="
```

**Windows PowerShell equivalent:**

```powershell
Write-Host "=== TranzFer MFT Prerequisites Check ===" -ForegroundColor Cyan
Write-Host ""

# Docker
try { docker --version; Write-Host "[OK] Docker installed" -ForegroundColor Green }
catch { Write-Host "[--] Docker not installed" -ForegroundColor Yellow }

# Java
try { java -version 2>&1 | Select-Object -First 1; Write-Host "[OK] Java installed" -ForegroundColor Green }
catch { Write-Host "[--] Java not installed" -ForegroundColor Yellow }

# Maven
try { mvn -version 2>&1 | Select-Object -First 1; Write-Host "[OK] Maven installed" -ForegroundColor Green }
catch { Write-Host "[--] Maven not installed" -ForegroundColor Yellow }

# PostgreSQL
try {
    $result = Test-NetConnection -ComputerName localhost -Port 5432 -WarningAction SilentlyContinue
    if ($result.TcpTestSucceeded) { Write-Host "[OK] PostgreSQL reachable on 5432" -ForegroundColor Green }
    else { Write-Host "[--] PostgreSQL not running on 5432" -ForegroundColor Yellow }
} catch { Write-Host "[--] PostgreSQL check failed" -ForegroundColor Yellow }

# RabbitMQ
try {
    $result = Test-NetConnection -ComputerName localhost -Port 5672 -WarningAction SilentlyContinue
    if ($result.TcpTestSucceeded) { Write-Host "[OK] RabbitMQ reachable on 5672" -ForegroundColor Green }
    else { Write-Host "[--] RabbitMQ not running on 5672" -ForegroundColor Yellow }
} catch { Write-Host "[--] RabbitMQ check failed" -ForegroundColor Yellow }

Write-Host ""
Write-Host "=== Check complete ===" -ForegroundColor Cyan
```

---

## Troubleshooting Common Issues

### "Port already in use"
Another process is using the port. Find and stop it (see [Network & Firewall Checklist](#step-6--network--firewall-checklist) above).

### "Docker daemon is not running"
```bash
# Linux
sudo systemctl start docker

# macOS / Windows
# Open Docker Desktop application and wait for it to start
```

### "Permission denied" on Docker
```bash
# Linux — add yourself to the docker group
sudo usermod -aG docker $USER
# Then log out and log back in
```

### "java: command not found" after installing Java
```bash
# Check if Java is installed but not in PATH
# Linux
find /usr/lib/jvm -name "java" 2>/dev/null

# macOS
/usr/libexec/java_home -V

# Add to PATH if found (example for Linux)
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

### PostgreSQL: "connection refused"
```bash
# Check if PostgreSQL is actually running
docker ps | grep postgres     # Docker method
systemctl status postgresql   # Native Linux
brew services list             # macOS

# Check if it's listening on the right port
docker logs mft-postgres 2>&1 | tail -5
```

### Maven: "Could not resolve dependencies"
```bash
# Clear Maven cache and retry
rm -rf ~/.m2/repository
mvn clean package -DskipTests

# If behind a corporate proxy, configure Maven:
# Edit ~/.m2/settings.xml with your proxy settings
```

### Windows: "Script execution is disabled"
```powershell
# Allow PowerShell scripts
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

---

## Hardware Recommendations

| Deployment | CPU | RAM | Disk | Notes |
|-----------|-----|-----|------|-------|
| **Single service demo** | 1 core | 1 GB | 5 GB | Minimum for one service + DB |
| **3-5 services** | 2 cores | 4 GB | 20 GB | Typical developer setup |
| **Full platform (all services)** | 4 cores | 8 GB | 50 GB | Includes all 17 services + UIs |
| **Production** | 8+ cores | 16+ GB | 200+ GB | See [Capacity Planning](../CAPACITY-PLANNING.md) |

---

## Next Steps

You're ready! Go to the [Demo Index](README.md) and pick any microservice to start with. We recommend starting with the **EDI Converter** (zero dependencies — just Docker or Java).

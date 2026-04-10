#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Bare Metal Installer
# =============================================================================
# Installs all TranzFer MFT services as native systemd units running JAR files.
# No Docker required — services run directly on the JVM.
#
# Prerequisites:
#   - JDK 21+ (Temurin or equivalent)
#   - PostgreSQL 16+ (external or local)
#   - Redis 7+ (external or local)
#   - RabbitMQ 3.12+ (external or local)
#   - Maven build completed: mvn clean package -DskipTests
#
# Usage:
#   sudo ./install-bare-metal.sh                          # Install from project root
#   sudo ./install-bare-metal.sh --jar-dir /path/to/jars  # Install from pre-built JARs
#   sudo ./install-bare-metal.sh --uninstall              # Remove all services
# =============================================================================
set -euo pipefail

MFT_HOME="/opt/tranzfer-mft"
MFT_USER="tranzfer"
MFT_GROUP="tranzfer"
JAR_DIR="$MFT_HOME/lib"
CONF_DIR="$MFT_HOME/config"
LOG_DIR="/var/log/tranzfer-mft"
DATA_DIR="/data/storage"
UNINSTALL=false
CUSTOM_JAR_DIR=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jar-dir) CUSTOM_JAR_DIR="$2"; shift 2 ;;
        --uninstall) UNINSTALL=true; shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Service definitions: name -> port mapping
# Order matches the platform port map
SERVICES=(
    onboarding-api
    sftp-service
    ftp-service
    ftp-web-service
    config-service
    gateway-service
    encryption-service
    external-forwarder-service
    dmz-proxy
    license-service
    analytics-service
    ai-engine
    screening-service
    keystore-manager
    as2-service
    edi-converter
    storage-manager
    notification-service
    platform-sentinel
)

PORTS=(
    8080 8081 8082 8083 8084
    8085 8086 8087 8088 8089
    8090 8091 8092 8093 8094
    8095 8096 8097 8098
)

# ---- Uninstall mode ----
if [ "$UNINSTALL" = "true" ]; then
    echo "======================================================="
    echo "  TranzFer MFT — Uninstalling Bare Metal Services"
    echo "======================================================="
    for svc in "${SERVICES[@]}"; do
        unit="tranzfer-${svc}.service"
        if [ -f "/etc/systemd/system/$unit" ]; then
            systemctl stop "$unit" 2>/dev/null || true
            systemctl disable "$unit" 2>/dev/null || true
            rm -f "/etc/systemd/system/$unit"
            echo "  Removed $unit"
        fi
    done
    systemctl daemon-reload
    echo "  Services removed. Data in $DATA_DIR and $MFT_HOME preserved."
    exit 0
fi

echo "======================================================="
echo "  TranzFer MFT — Bare Metal Installer"
echo "======================================================="

# ---- Step 1: Check prerequisites ----
echo "[1/5] Checking prerequisites..."

# Java
if ! command -v java &>/dev/null; then
    echo "ERROR: Java not found. Install JDK 21+:"
    echo "  Ubuntu:  sudo apt install openjdk-21-jdk"
    echo "  RHEL:    sudo dnf install java-21-openjdk-devel"
    echo "  Any OS:  https://adoptium.net/temurin/releases/?version=21"
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f1)
if [ "$JAVA_VER" -lt 21 ] 2>/dev/null; then
    echo "ERROR: JDK 21+ required (found JDK $JAVA_VER)"
    exit 1
fi
echo "  Java: OK (JDK $JAVA_VER)"

# Check for external services
echo "  Checking external services..."
if command -v pg_isready &>/dev/null; then
    pg_isready -q && echo "  PostgreSQL: reachable" || echo "  PostgreSQL: not reachable (configure in $CONF_DIR/application-production.yml)"
else
    echo "  PostgreSQL: pg_isready not found (ensure DB is configured)"
fi

# ---- Step 2: Create service user ----
echo "[2/5] Creating service user..."
if id "$MFT_USER" &>/dev/null; then
    echo "  User '$MFT_USER' already exists"
else
    useradd -r -m -s /usr/sbin/nologin "$MFT_USER"
    echo "  Created system user '$MFT_USER'"
fi

# ---- Step 3: Create directories ----
echo "[3/5] Creating directories..."
mkdir -p "$JAR_DIR" "$CONF_DIR" "$LOG_DIR"
mkdir -p "$DATA_DIR"/{hot,warm,cold,backup}
mkdir -p /data/partners
chown -R "$MFT_USER:$MFT_GROUP" "$MFT_HOME" "$LOG_DIR" "$DATA_DIR" /data/partners

# ---- Step 4: Copy JARs and create systemd units ----
echo "[4/5] Installing services..."
installed_count=0
skipped_count=0

for i in "${!SERVICES[@]}"; do
    svc="${SERVICES[$i]}"
    port="${PORTS[$i]}"

    # Find the JAR file
    jar_file=""
    if [ -n "$CUSTOM_JAR_DIR" ]; then
        jar_file=$(ls "$CUSTOM_JAR_DIR/$svc"*.jar 2>/dev/null | head -1)
    else
        jar_file=$(ls "$PROJECT_ROOT/$svc/target/$svc"-*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -1)
    fi

    if [ -z "$jar_file" ] || [ ! -f "$jar_file" ]; then
        echo "  SKIP $svc (JAR not found — run mvn package first)"
        skipped_count=$((skipped_count + 1))
        continue
    fi

    # Copy JAR
    cp "$jar_file" "$JAR_DIR/$svc.jar"
    chown "$MFT_USER:$MFT_GROUP" "$JAR_DIR/$svc.jar"

    # Install the pre-built systemd unit file if it exists, otherwise generate one
    unit_file="$SCRIPT_DIR/tranzfer-${svc}.service"
    if [ -f "$unit_file" ]; then
        cp "$unit_file" "/etc/systemd/system/"
    else
        # Generate unit file inline (fallback)
        cat > "/etc/systemd/system/tranzfer-${svc}.service" << EOF
[Unit]
Description=TranzFer MFT — ${svc}
After=network-online.target postgresql.service redis.service rabbitmq-server.service
Wants=network-online.target
Documentation=https://docs.tranzfer.io

[Service]
User=${MFT_USER}
Group=${MFT_GROUP}
WorkingDirectory=${MFT_HOME}
ExecStart=/usr/bin/java \\
    -Xms256m -Xmx512m \\
    -XX:+UseZGC -XX:+ZGenerational \\
    -XX:+EnableDynamicAgentLoading \\
    --add-opens java.base/java.lang=ALL-UNNAMED \\
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \\
    --add-opens java.base/java.util=ALL-UNNAMED \\
    --add-opens java.base/java.lang.invoke=ALL-UNNAMED \\
    --add-opens java.base/java.io=ALL-UNNAMED \\
    -Dserver.port=${port} \\
    -Dspring.profiles.active=production \\
    -Dspring.config.additional-location=${CONF_DIR}/ \\
    -jar ${JAR_DIR}/${svc}.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=tranzfer-${svc}

# Security hardening
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=${DATA_DIR} ${LOG_DIR} /data/partners /tmp
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
    fi

    echo "  Installed $svc (port $port)"
    installed_count=$((installed_count + 1))
done

# ---- Step 5: Enable services ----
echo "[5/5] Enabling services..."
systemctl daemon-reload

for svc in "${SERVICES[@]}"; do
    if [ -f "/etc/systemd/system/tranzfer-${svc}.service" ]; then
        systemctl enable "tranzfer-${svc}" 2>/dev/null
    fi
done

echo ""
echo "======================================================="
echo "  Installation complete!"
echo "  Installed: $installed_count services"
echo "  Skipped:   $skipped_count services (JAR not found)"
echo ""
echo "  Start all:   systemctl start tranzfer-\\*"
echo "  Stop all:    systemctl stop tranzfer-\\*"
echo "  Status:      systemctl status tranzfer-\\*"
echo "  Logs:        journalctl -u tranzfer-onboarding-api -f"
echo ""
echo "  Config dir:  $CONF_DIR/"
echo "  JAR dir:     $JAR_DIR/"
echo "  Data dir:    $DATA_DIR/"
echo "  Log output:  journalctl -u tranzfer-\\*"
echo ""
echo "  Before starting, create $CONF_DIR/application-production.yml"
echo "  with your database, Redis, and RabbitMQ connection settings."
echo "======================================================="

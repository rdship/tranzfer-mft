#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — First Boot Setup
# =============================================================================
# Runs ONCE after ISO/OVA install. Installs Docker, loads pre-built images,
# configures data directories, and starts the platform.
#
# Idempotent: checks /opt/tranzfer-mft/.installed marker before running.
# Logs to: /var/log/tranzfer-first-boot.log
# =============================================================================
set -euo pipefail

MFT_HOME="/opt/tranzfer-mft"
IMAGES_DIR="$MFT_HOME/docker-images"
INSTALLED_MARKER="$MFT_HOME/.installed"
LOG="/var/log/tranzfer-first-boot.log"

# Idempotency check
if [ -f "$INSTALLED_MARKER" ]; then
    echo "TranzFer MFT already installed (marker exists). Skipping first-boot setup."
    exit 0
fi

exec > >(tee -a "$LOG") 2>&1

echo "======================================================="
echo "  TranzFer MFT — First Boot Setup"
echo "  $(date '+%Y-%m-%d %H:%M:%S %Z')"
echo "======================================================="

# ---- 1. Install Docker ----
echo "[1/6] Installing Docker..."
if ! command -v docker &>/dev/null; then
    curl -fsSL https://get.docker.com | sh
    systemctl enable docker
    systemctl start docker
    # Add service user to docker group
    usermod -aG docker tranzfer 2>/dev/null || true
fi
echo "  Docker $(docker --version)"

# ---- 2. Install Docker Compose plugin ----
echo "[2/6] Verifying Docker Compose..."
if ! docker compose version &>/dev/null; then
    apt-get update -qq
    apt-get install -y -qq docker-compose-plugin
fi
echo "  $(docker compose version)"

# ---- 3. Load pre-built Docker images (offline/air-gapped) ----
echo "[3/6] Loading Docker images..."
if [ -d "$IMAGES_DIR" ] && ls "$IMAGES_DIR"/*.tar.gz &>/dev/null; then
    image_count=0
    for img in "$IMAGES_DIR"/*.tar.gz; do
        svc=$(basename "$img" .tar.gz)
        echo "  Loading $svc..."
        gunzip -c "$img" | docker load -q
        image_count=$((image_count + 1))
    done
    echo "  Loaded $image_count images"
else
    echo "  No pre-built images found — will pull from registry on first compose up"
fi

# ---- 4. Configure data directories ----
echo "[4/6] Setting up data directories..."
mkdir -p /data/storage/{hot,warm,cold,backup}
mkdir -p /data/partners
mkdir -p /data/postgres
mkdir -p /data/redis
mkdir -p /data/rabbitmq
chown -R 1000:1000 /data/storage /data/partners

# ---- 5. Start the platform ----
echo "[5/6] Starting TranzFer MFT..."
cd "$MFT_HOME"

# Load custom configuration if present
if [ -f "$MFT_HOME/tranzfer-mft.conf" ]; then
    set -a
    source "$MFT_HOME/tranzfer-mft.conf"
    set +a
fi

# Start all services
docker compose up -d

# Wait for core services to initialize
echo "  Waiting for services to start (30s)..."
sleep 30

# Quick health check on onboarding-api
if curl -sf http://localhost:8080/actuator/health/liveness &>/dev/null; then
    echo "  Core API is responsive"
else
    echo "  Core API not yet ready — may need a few more seconds"
fi

# ---- 6. Bootstrap SPIFFE/SPIRE ----
echo "[6/6] Bootstrapping SPIFFE/SPIRE..."
if [ -f "$MFT_HOME/spire/bootstrap.sh" ]; then
    bash "$MFT_HOME/spire/bootstrap.sh" || echo "  SPIRE bootstrap skipped (non-critical on first boot)"
fi

# Mark as installed — prevents re-running on subsequent boots
touch "$INSTALLED_MARKER"
echo "$(date '+%Y-%m-%d %H:%M:%S %Z')" > "$INSTALLED_MARKER"

# Detect IP address
IP=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "localhost")

echo ""
echo "======================================================="
echo "  TranzFer MFT is READY"
echo ""
echo "  UI:    http://${IP}:8080"
echo "  Login: superadmin@tranzfer.io / superadmin"
echo ""
echo "  SFTP:  sftp -P 2222 acme-sftp@${IP}"
echo "  FTP:   ftp ${IP} 21"
echo "  API:   http://${IP}:8080/api"
echo ""
echo "  Logs:  docker compose -f $MFT_HOME/docker-compose.yml logs -f"
echo "  Stop:  docker compose -f $MFT_HOME/docker-compose.yml down"
echo ""
echo "  First-boot log: $LOG"
echo "======================================================="

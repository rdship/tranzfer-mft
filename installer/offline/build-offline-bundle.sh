#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Offline / Air-Gapped Bundle Builder
# =============================================================================
# Builds a self-contained installation bundle containing all Docker images,
# compose files, scripts, and configuration. No internet required to install.
#
# Output: dist/tranzfer-mft-offline-VERSION.tar.gz
#
# Usage:
#   ./build-offline-bundle.sh                  # Default version
#   ./build-offline-bundle.sh --version 2.0.0  # Specific version
#   ./build-offline-bundle.sh --skip-build     # Use existing images (no mvn/docker build)
# =============================================================================
set -euo pipefail

VERSION="2.0.0"
SKIP_BUILD=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

BUNDLE="tranzfer-mft-offline-${VERSION}"
BUILD_DIR="$(mktemp -d)/$BUNDLE"
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

echo "======================================================="
echo "  TranzFer MFT — Offline Bundle Builder v${VERSION}"
echo "======================================================="

mkdir -p "$BUILD_DIR"/{docker-images,scripts,config,spire}

# ---- Step 1: Build and save application Docker images ----
echo "[1/4] Building & saving Docker images..."
cd "$PROJECT_ROOT"

SERVICES=(
    onboarding-api sftp-service ftp-service ftp-web-service config-service
    gateway-service encryption-service external-forwarder-service dmz-proxy
    license-service analytics-service ai-engine screening-service keystore-manager
    as2-service edi-converter storage-manager notification-service platform-sentinel
    ui-service
)

if [ "$SKIP_BUILD" = "false" ]; then
    echo "  Building Maven modules..."
    mvn clean package -DskipTests -q
fi

saved_count=0
for svc in "${SERVICES[@]}"; do
    if [ -f "$svc/Dockerfile" ]; then
        if [ "$SKIP_BUILD" = "false" ]; then
            docker build -t "tranzfer/$svc:$VERSION" "$svc/" -q 2>/dev/null || { echo "  SKIP $svc (build failed)"; continue; }
        fi
        if docker image inspect "tranzfer/$svc:$VERSION" &>/dev/null; then
            docker save "tranzfer/$svc:$VERSION" | gzip > "$BUILD_DIR/docker-images/$svc.tar.gz"
            echo "  Saved $svc"
            saved_count=$((saved_count + 1))
        else
            echo "  SKIP $svc (image not found)"
        fi
    fi
done
echo "  Saved $saved_count application images"

# ---- Step 2: Save infrastructure images ----
echo "[2/4] Saving infrastructure images..."
for img in postgres:16-alpine redis:7-alpine rabbitmq:3.12-management-alpine; do
    docker pull "$img" -q 2>/dev/null || true
    if docker image inspect "$img" &>/dev/null; then
        name=$(echo "$img" | cut -d: -f1 | tr '/' '-')
        docker save "$img" | gzip > "$BUILD_DIR/docker-images/$name.tar.gz"
        echo "  Saved $name"
    else
        echo "  SKIP $img (not available)"
    fi
done

# ---- Step 3: Copy deployment files ----
echo "[3/4] Copying deployment files..."
cp "$PROJECT_ROOT/docker-compose.yml" "$BUILD_DIR/"
if [ -d "$PROJECT_ROOT/spire" ]; then
    cp -r "$PROJECT_ROOT/spire/"* "$BUILD_DIR/spire/" 2>/dev/null || true
fi
cp "$PROJECT_ROOT/installer/first-boot/first-boot-setup.sh" "$BUILD_DIR/scripts/"
cp "$PROJECT_ROOT/installer/first-boot/tranzfer-mft.conf" "$BUILD_DIR/config/"
if [ -f "$PROJECT_ROOT/scripts/boot.sh" ]; then
    cp "$PROJECT_ROOT/scripts/boot.sh" "$BUILD_DIR/scripts/"
fi
if [ -f "$PROJECT_ROOT/scripts/preflight-check.sh" ]; then
    cp "$PROJECT_ROOT/scripts/preflight-check.sh" "$BUILD_DIR/scripts/"
fi

# Create the self-contained install script
cat > "$BUILD_DIR/install.sh" << 'INSTALLER'
#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Offline Installer
# =============================================================================
# Run this script on an air-gapped server with Docker already installed.
# It loads all pre-built images and starts the platform.
#
# Usage:
#   tar xzf tranzfer-mft-offline-*.tar.gz
#   cd tranzfer-mft-offline-*
#   sudo ./install.sh
# =============================================================================
set -euo pipefail

echo "======================================================="
echo "  TranzFer MFT — Offline Installer"
echo "======================================================="

# Check Docker
if ! command -v docker &>/dev/null; then
    echo "ERROR: Docker is required but not installed."
    echo "Install Docker first: https://docs.docker.com/engine/install/"
    exit 1
fi
echo "  Docker: $(docker --version)"

# Check Docker Compose
if ! docker compose version &>/dev/null; then
    echo "ERROR: Docker Compose plugin is required."
    echo "Install: sudo apt-get install docker-compose-plugin"
    exit 1
fi
echo "  $(docker compose version)"

# Load all Docker images
echo ""
echo "Loading Docker images (this may take several minutes)..."
loaded=0
for img in docker-images/*.tar.gz; do
    [ -f "$img" ] || continue
    name=$(basename "$img" .tar.gz)
    echo "  Loading $name..."
    gunzip -c "$img" | docker load -q
    loaded=$((loaded + 1))
done
echo "  Loaded $loaded images"

# Install files
echo ""
echo "Installing to /opt/tranzfer-mft..."
sudo mkdir -p /opt/tranzfer-mft
sudo cp docker-compose.yml /opt/tranzfer-mft/
if [ -d spire ] && ls spire/* &>/dev/null; then
    sudo cp -r spire /opt/tranzfer-mft/
fi
sudo cp scripts/first-boot-setup.sh /opt/tranzfer-mft/
sudo cp config/tranzfer-mft.conf /opt/tranzfer-mft/
sudo chmod +x /opt/tranzfer-mft/first-boot-setup.sh

# Create data directories
sudo mkdir -p /data/storage/{hot,warm,cold,backup}
sudo mkdir -p /data/partners /data/postgres /data/redis /data/rabbitmq
sudo chown -R 1000:1000 /data/storage /data/partners

# Start platform
echo ""
echo "Starting TranzFer MFT..."
cd /opt/tranzfer-mft
sudo docker compose up -d

# Wait briefly for startup
sleep 10

# Mark as installed
sudo touch /opt/tranzfer-mft/.installed

IP=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "localhost")
echo ""
echo "======================================================="
echo "  TranzFer MFT is starting..."
echo ""
echo "  UI:    http://${IP}:8080"
echo "  Login: superadmin@tranzfer.io / superadmin"
echo ""
echo "  SFTP:  sftp -P 2222 acme-sftp@${IP}"
echo "  FTP:   ftp ${IP} 21"
echo "  API:   http://${IP}:8080/api"
echo ""
echo "  Logs:  docker compose -f /opt/tranzfer-mft/docker-compose.yml logs -f"
echo "  Stop:  docker compose -f /opt/tranzfer-mft/docker-compose.yml down"
echo "======================================================="
INSTALLER
chmod +x "$BUILD_DIR/install.sh"

# ---- Step 4: Package bundle ----
echo "[4/4] Creating bundle..."
mkdir -p "$PROJECT_ROOT/dist"
cd "$(dirname "$BUILD_DIR")"
tar -czf "$PROJECT_ROOT/dist/$BUNDLE.tar.gz" "$BUNDLE"
SIZE=$(du -sh "$PROJECT_ROOT/dist/$BUNDLE.tar.gz" | cut -f1)

echo ""
echo "======================================================="
echo "  Offline bundle: dist/$BUNDLE.tar.gz ($SIZE)"
echo ""
echo "  Transfer to air-gapped server, then:"
echo "    tar xzf $BUNDLE.tar.gz"
echo "    cd $BUNDLE"
echo "    sudo ./install.sh"
echo "======================================================="

rm -rf "$(dirname "$BUILD_DIR")"

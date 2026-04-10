#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — ISO Image Builder
# =============================================================================
# Builds a bootable Ubuntu 24.04 ISO with TranzFer MFT pre-installed.
# The ISO includes all Docker images for offline/air-gapped deployment.
#
# Prerequisites: xorriso, docker, mvn, gzip
# Usage:
#   ./build-iso.sh                  # Build with default version
#   ./build-iso.sh --version 2.0.0  # Build with specific version
# =============================================================================
set -euo pipefail

VERSION="2.0.0"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

ISO_NAME="tranzfer-mft-${VERSION}.iso"
BUILD_DIR="$(mktemp -d)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "======================================================="
echo "  TranzFer MFT ISO Builder v${VERSION}"
echo "======================================================="

# ---- Step 1: Build all Maven modules ----
echo "[1/5] Building Maven modules..."
cd "$PROJECT_ROOT"
mvn clean package -DskipTests -q

# ---- Step 2: Build and save Docker images ----
echo "[2/5] Building Docker images..."

SERVICES=(
    onboarding-api sftp-service ftp-service ftp-web-service config-service
    gateway-service encryption-service external-forwarder-service dmz-proxy
    license-service analytics-service ai-engine screening-service keystore-manager
    as2-service edi-converter storage-manager notification-service platform-sentinel
    ui-service
)

mkdir -p "$BUILD_DIR/docker-images"
for svc in "${SERVICES[@]}"; do
    if [ -f "$PROJECT_ROOT/$svc/Dockerfile" ]; then
        echo "  Building $svc..."
        docker build -t "tranzfer/$svc:$VERSION" "$PROJECT_ROOT/$svc/" -q
        docker save "tranzfer/$svc:$VERSION" | gzip > "$BUILD_DIR/docker-images/$svc.tar.gz"
    else
        echo "  SKIP $svc (no Dockerfile)"
    fi
done

# Save infrastructure images
echo "  Pulling infrastructure images..."
for img in postgres:16-alpine redis:7-alpine rabbitmq:3.12-management-alpine; do
    docker pull "$img" -q 2>/dev/null || true
    name=$(echo "$img" | cut -d: -f1 | tr '/' '-')
    docker save "$img" | gzip > "$BUILD_DIR/docker-images/$name.tar.gz"
    echo "  Saved $name"
done

# ---- Step 3: Copy deployment files ----
echo "[3/5] Packaging deployment files..."
mkdir -p "$BUILD_DIR/tranzfer-mft"
cp "$PROJECT_ROOT/docker-compose.yml" "$BUILD_DIR/tranzfer-mft/"
if [ -d "$PROJECT_ROOT/spire" ]; then
    cp -r "$PROJECT_ROOT/spire" "$BUILD_DIR/tranzfer-mft/"
fi
cp -r "$PROJECT_ROOT/scripts" "$BUILD_DIR/tranzfer-mft/"
cp "$SCRIPT_DIR/../first-boot/first-boot-setup.sh" "$BUILD_DIR/tranzfer-mft/"
cp "$SCRIPT_DIR/../first-boot/tranzfer-mft.conf" "$BUILD_DIR/tranzfer-mft/"
# Include docker images inside tranzfer-mft for late-commands copy
cp -r "$BUILD_DIR/docker-images" "$BUILD_DIR/tranzfer-mft/"

# ---- Step 4: Copy autoinstall config ----
echo "[4/5] Preparing autoinstall..."
mkdir -p "$BUILD_DIR/autoinstall"
cp "$SCRIPT_DIR/autoinstall/user-data" "$BUILD_DIR/autoinstall/"
cp "$SCRIPT_DIR/autoinstall/meta-data" "$BUILD_DIR/autoinstall/"
# Replace version placeholder (portable sed — works on both Linux and macOS)
if [[ "$(uname)" == "Darwin" ]]; then
    sed -i '' "s/%%VERSION%%/$VERSION/g" "$BUILD_DIR/autoinstall/user-data"
else
    sed -i "s/%%VERSION%%/$VERSION/g" "$BUILD_DIR/autoinstall/user-data"
fi

# Copy GRUB config
mkdir -p "$BUILD_DIR/boot/grub"
cp "$SCRIPT_DIR/grub/grub.cfg" "$BUILD_DIR/boot/grub/"

# ---- Step 5: Build ISO ----
echo "[5/5] Building ISO..."
mkdir -p "$PROJECT_ROOT/dist"

# Package payload tarball (always produced)
tar -czf "$PROJECT_ROOT/dist/$ISO_NAME.payload.tar.gz" -C "$BUILD_DIR" .

# Attempt full ISO build if xorriso is available
if command -v xorriso &>/dev/null; then
    xorriso -as mkisofs \
        -V "TranzFer MFT $VERSION" \
        -o "$PROJECT_ROOT/dist/$ISO_NAME" \
        -J -R -l \
        -b boot/grub/grub.cfg \
        -no-emul-boot \
        -boot-load-size 4 \
        -boot-info-table \
        "$BUILD_DIR" 2>/dev/null || {
            echo "  ISO assembly requires Ubuntu host with full grub packages"
            echo "  Payload tarball is ready for manual ISO assembly"
        }
else
    echo "  xorriso not found — payload tarball created instead"
    echo "  To build bootable ISO, install xorriso on an Ubuntu system:"
    echo "    sudo apt-get install -y xorriso grub-pc-bin grub-efi-amd64-bin"
fi

echo ""
echo "======================================================="
echo "  Payload ready: dist/$ISO_NAME.payload.tar.gz"
echo ""
echo "  To build bootable ISO on Ubuntu:"
echo "    xorriso -as mkisofs -V 'TranzFer MFT' \\"
echo "      -o dist/$ISO_NAME \\"
echo "      -b boot/grub/grub.cfg \\"
echo "      --grub2-mbr /usr/lib/grub/i386-pc/boot_hybrid.img \\"
echo "      $BUILD_DIR"
echo "======================================================="

rm -rf "$BUILD_DIR"

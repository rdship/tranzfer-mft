#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — OVA Appliance Builder
# =============================================================================
# Wrapper script for Packer to build the OVA virtual appliance.
#
# Prerequisites: packer, virtualbox (or qemu)
# Usage:
#   ./build-ova.sh                  # Build with defaults
#   ./build-ova.sh --version 2.0.0  # Specify version
# =============================================================================
set -euo pipefail

VERSION="2.0.0"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "======================================================="
echo "  TranzFer MFT OVA Builder v${VERSION}"
echo "======================================================="

# Check prerequisites
echo "[1/3] Checking prerequisites..."
command -v packer &>/dev/null || { echo "ERROR: packer not found. Install from https://packer.io"; exit 1; }
echo "  Packer: $(packer --version)"

if command -v VBoxManage &>/dev/null; then
    echo "  VirtualBox: $(VBoxManage --version)"
elif command -v qemu-system-x86_64 &>/dev/null; then
    echo "  QEMU: available"
else
    echo "ERROR: VirtualBox or QEMU required"
    exit 1
fi

# Initialize Packer plugins
echo "[2/3] Initializing Packer plugins..."
cd "$SCRIPT_DIR"
packer init .

# Build OVA
echo "[3/3] Building OVA (this may take 15-30 minutes)..."
packer build -var "version=$VERSION" tranzfer-mft.pkr.hcl

echo ""
echo "======================================================="
echo "  OVA built: $SCRIPT_DIR/output-ova/tranzfer-mft-${VERSION}.ova"
echo ""
echo "  Import into VirtualBox, VMware, or Hyper-V:"
echo "    VirtualBox: File > Import Appliance"
echo "    VMware:     File > Open"
echo "    Hyper-V:    Convert OVA to VHDX first"
echo "======================================================="

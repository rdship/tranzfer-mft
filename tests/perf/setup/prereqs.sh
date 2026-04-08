#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Prerequisites Installer for Performance Testing
# Run once on the test laptop before executing any tests.
# Usage: ./tests/perf/setup/prereqs.sh [--verify]
# =============================================================================
set -euo pipefail

VERIFY_ONLY=false
[[ "${1:-}" == "--verify" ]] && VERIFY_ONLY=true

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[✓]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[✗]${NC} $1"; }

check_cmd() { command -v "$1" &>/dev/null; }

echo "=== TranzFer MFT Performance Test Prerequisites ==="
echo ""

# ── Detect OS ───────────────────────────────────────────────────────────────
OS="unknown"
if [[ "$OSTYPE" == "darwin"* ]]; then
  OS="macos"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
  OS="linux"
fi

if [[ "$VERIFY_ONLY" == "true" ]]; then
  echo "Verifying installed tools..."
  FAIL=0

  check_cmd docker      && ok "docker"      || { err "docker not found"; FAIL=1; }
  check_cmd docker      && docker compose version &>/dev/null && ok "docker compose" || { err "docker compose not found"; FAIL=1; }
  check_cmd k6          && ok "k6 $(k6 version 2>&1 | head -1)" || { err "k6 not found — run without --verify to install"; FAIL=1; }
  check_cmd python3     && ok "python3 $(python3 --version)" || { err "python3 not found"; FAIL=1; }
  check_cmd pip3        && ok "pip3"        || { err "pip3 not found"; FAIL=1; }
  check_cmd jq          && ok "jq"          || { err "jq not found"; FAIL=1; }
  check_cmd curl        && ok "curl"        || { err "curl not found"; FAIL=1; }
  check_cmd nc          && ok "nc (netcat)" || warn "nc not found (optional)"
  check_cmd openssl     && ok "openssl"     || warn "openssl not found (needed for TLS tests)"

  echo ""
  python3 -c "import paramiko"          2>/dev/null && ok "paramiko"          || { err "paramiko not installed";          FAIL=1; }
  python3 -c "import requests"          2>/dev/null && ok "requests"          || { err "requests not installed";          FAIL=1; }
  python3 -c "import aiohttp"           2>/dev/null && ok "aiohttp"           || { err "aiohttp not installed";           FAIL=1; }
  python3 -c "import tqdm"              2>/dev/null && ok "tqdm"              || { err "tqdm not installed";              FAIL=1; }
  python3 -c "import tabulate"          2>/dev/null && ok "tabulate"          || { err "tabulate not installed";          FAIL=1; }
  python3 -c "import asyncio_throttle"  2>/dev/null && ok "asyncio-throttle"  || { err "asyncio-throttle not installed"; FAIL=1; }

  echo ""
  if [[ $FAIL -eq 0 ]]; then
    ok "All prerequisites satisfied. Ready to run tests."
  else
    err "Some prerequisites missing. Run without --verify to install."
    exit 1
  fi
  exit 0
fi

# ── Install ──────────────────────────────────────────────────────────────────
if [[ "$OS" == "macos" ]]; then
  echo "Installing on macOS..."

  if ! check_cmd brew; then
    warn "Homebrew not found. Installing..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  fi

  # k6
  if ! check_cmd k6; then
    echo "Installing k6..."
    brew install k6
  else
    ok "k6 already installed"
  fi

  # jq
  if ! check_cmd jq; then
    brew install jq
  else
    ok "jq already installed"
  fi

  # Python deps — install from requirements.txt
  REQS_FILE="$(dirname "$0")/../python/requirements.txt"
  if [[ -f "$REQS_FILE" ]]; then
    pip3 install --quiet -r "$REQS_FILE" && ok "Python packages installed (from requirements.txt)"
  else
    pip3 install --quiet paramiko requests aiohttp tqdm tabulate asyncio-throttle && ok "Python packages installed"
  fi

  # sshpass (for SFTP scripting)
  if ! check_cmd sshpass; then
    brew install hudochenkov/sshpass/sshpass 2>/dev/null || warn "sshpass not available (non-critical)"
  fi

elif [[ "$OS" == "linux" ]]; then
  echo "Installing on Linux..."

  # k6
  if ! check_cmd k6; then
    sudo gpg --no-default-keyring \
      --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
      --keyserver hkp://keyserver.ubuntu.com:80 \
      --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
    echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | \
      sudo tee /etc/apt/sources.list.d/k6.list
    sudo apt-get update -q && sudo apt-get install -y k6 jq bc
  fi

  REQS_FILE="$(dirname "$0")/../python/requirements.txt"
  if [[ -f "$REQS_FILE" ]]; then
    pip3 install --quiet -r "$REQS_FILE" && ok "Python packages installed (from requirements.txt)"
  else
    pip3 install --quiet paramiko requests aiohttp tqdm tabulate asyncio-throttle && ok "Python packages installed"
  fi
fi

echo ""
ok "Setup complete. Run: ./tests/perf/setup/prereqs.sh --verify"

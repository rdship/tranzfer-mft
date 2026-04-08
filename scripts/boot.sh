#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Staged Boot Orchestrator
# =============================================================================
# Each microservice is self-contained: owns its tables, validates on boot,
# auto-repairs schema drift. If a service isn't deployed, its tables don't exist.
#
# Boot stages:
#   1. Infrastructure (postgres + rabbitmq) — wait until healthy
#   2. Run install-db.sh — pre-validates DB + optional Flyway
#   3. Core services boot — each self-validates its schema via ServiceReadinessValidator
#   4. Protocol services boot — same self-validation
#   5. Supporting services boot — same self-validation
#   6. Stateless + replicas + UI (optional)
#
# Every service on every boot:
#   - Checks its required tables exist (from application.yml)
#   - If missing → runs its own schema SQL file to create them
#   - Validates columns haven't been tampered with
#   - Logs a clear report
#
# Usage:
#   ./scripts/boot.sh                  # Full staged boot
#   ./scripts/boot.sh --skip-replicas  # Skip replicas (saves ~2GB RAM)
#   ./scripts/boot.sh --minimal        # Core 5 services only
#   ./scripts/boot.sh --service X      # Boot single service only
#   ./scripts/boot.sh --down           # Stop everything
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# --- Configuration ---
SKIP_REPLICAS=false
SKIP_UI=false
MINIMAL=false
DO_DOWN=false
SINGLE_SERVICE=""
TIMEOUT=180

# --- Colors ---
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; DIM='\033[2m'; RESET='\033[0m'

# --- Parse args ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-replicas) SKIP_REPLICAS=true; shift ;;
    --skip-ui)       SKIP_UI=true; shift ;;
    --minimal)       MINIMAL=true; SKIP_REPLICAS=true; SKIP_UI=true; shift ;;
    --service)       SINGLE_SERVICE="$2"; shift 2 ;;
    --down)          DO_DOWN=true; shift ;;
    *) echo "Unknown: $1"; exit 1 ;;
  esac
done

if [[ "$DO_DOWN" == "true" ]]; then
  echo -e "${CYAN}${BOLD}Stopping all containers...${RESET}"
  docker compose down
  exit 0
fi

# --- Helpers ---
log()  { echo -e "${CYAN}[BOOT]${RESET} $*"; }
ok()   { echo -e "  ${GREEN}[OK]${RESET} $*"; }
fail() { echo -e "  ${RED}[FAIL]${RESET} $*"; }
warn() { echo -e "  ${YELLOW}[WARN]${RESET} $*"; }

BOOT_START=$(date +%s)
elapsed() { echo $(( $(date +%s) - BOOT_START )); }

# --- RAM detection ---
if [[ "$(uname)" == "Darwin" ]]; then
  RAM_GB=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1024 / 1024 / 1024 ))
else
  RAM_GB=$(( $(grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}' || echo 0) / 1024 / 1024 ))
fi
log "System RAM: ${RAM_GB}GB"
[[ $RAM_GB -lt 12 ]] && { warn "Low RAM (${RAM_GB}GB) — auto-skipping replicas"; SKIP_REPLICAS=true; }

# --- Service wait helpers ---
wait_healthy() {
  local container="$1" label="$2" timeout="${3:-$TIMEOUT}"
  local start=$(date +%s)
  while true; do
    local health
    health=$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || echo "missing")
    [[ "$health" == "healthy" ]] && { ok "$label healthy ($(($(date +%s)-start))s)"; return 0; }
    [[ "$health" == "missing" ]] && { fail "$label container not found"; return 1; }
    (( $(date +%s) - start > timeout )) && {
      fail "$label not healthy after ${timeout}s"
      docker logs --tail 15 "$container" 2>&1 | grep -iE 'error|exception|fatal|caused|missing' | head -5 | while read l; do echo -e "    ${DIM}$l${RESET}"; done
      return 1
    }
    sleep 3
  done
}

boot_services() {
  local stage_name="$1"; shift
  local services=("$@")
  log "${BOLD}${stage_name}${RESET}: ${services[*]}"
  docker compose up -d "${services[@]}" 2>/dev/null || true
  local all_ok=true
  for svc in "${services[@]}"; do
    wait_healthy "mft-${svc}" "$svc" $TIMEOUT || { warn "$svc failed"; all_ok=false; }
  done
  echo -e "  ${DIM}${stage_name} done ($(elapsed)s elapsed)${RESET}"
  echo ""
  $all_ok
}

# =============================================================================
echo ""
echo -e "${CYAN}${BOLD}╔═══════════════════════════════════════════════════════════╗${RESET}"
echo -e "${CYAN}${BOLD}║  TranzFer MFT — Staged Boot Orchestrator                 ║${RESET}"
echo -e "${CYAN}${BOLD}║  Each service self-validates and self-heals on boot       ║${RESET}"
echo -e "${CYAN}${BOLD}╚═══════════════════════════════════════════════════════════╝${RESET}"
echo ""

# =============================================================================
# SINGLE SERVICE MODE
# =============================================================================
if [[ -n "$SINGLE_SERVICE" ]]; then
  log "Single-service mode: $SINGLE_SERVICE"

  # Ensure infra is running
  docker compose up -d postgres rabbitmq
  wait_healthy mft-postgres "PostgreSQL" 60 || exit 1
  wait_healthy mft-rabbitmq "RabbitMQ" 60 || true

  docker compose up -d "$SINGLE_SERVICE"
  wait_healthy "mft-${SINGLE_SERVICE}" "$SINGLE_SERVICE" $TIMEOUT || exit 1

  # Validate via install-db.sh
  bash "$SCRIPT_DIR/install-db.sh" --docker --validate-only --service "$SINGLE_SERVICE" || true
  exit 0
fi

# =============================================================================
# STAGE 1: Infrastructure
# =============================================================================
log "${BOLD}STAGE 1: Infrastructure${RESET}"
docker compose up -d postgres rabbitmq
wait_healthy mft-postgres "PostgreSQL" 60 || exit 1
wait_healthy mft-rabbitmq "RabbitMQ" 60 || exit 1

# RabbitMQ alarm check
sleep 2
rmq=$(curl -sf "http://localhost:15672/api/health/checks/alarms" -u guest:guest 2>/dev/null || echo '{}')
echo "$rmq" | grep -q '"status":"ok"' && ok "RabbitMQ no memory alarms" || warn "RabbitMQ alarm active"
echo ""

# =============================================================================
# STAGE 2: Pre-flight DB validation (optional Flyway)
# =============================================================================
log "${BOLD}STAGE 2: Database pre-flight${RESET}"
bash "$SCRIPT_DIR/install-db.sh" --docker --validate-only 2>/dev/null || {
  warn "Pre-flight found issues — services will self-heal on boot"
}
echo ""

# =============================================================================
# STAGE 3: Core services (each self-validates its own schema)
# =============================================================================
boot_services "STAGE 3 — Core" onboarding-api config-service encryption-service storage-manager || {
  fail "Critical service failed — check logs above"
  exit 1
}

# =============================================================================
# STAGE 4: Protocol services
# =============================================================================
boot_services "STAGE 4 — Protocol" sftp-service ftp-service ftp-web-service gateway-service as2-service || true

# =============================================================================
# STAGE 5: Supporting services
# =============================================================================
if [[ "$MINIMAL" != "true" ]]; then
  boot_services "STAGE 5 — Supporting" external-forwarder-service license-service analytics-service ai-engine screening-service keystore-manager notification-service || true
else
  log "${BOLD}STAGE 5 — Supporting: SKIPPED${RESET} (minimal mode)"
  echo ""
fi

# =============================================================================
# STAGE 6: Stateless (no DB, no self-validation needed)
# =============================================================================
if [[ "$MINIMAL" != "true" ]]; then
  log "${BOLD}STAGE 6 — Stateless${RESET}"
  docker compose up -d edi-converter dmz-proxy 2>/dev/null || true
  sleep 3
  ok "edi-converter + dmz-proxy started"
  echo ""
fi

# =============================================================================
# STAGE 7: Replicas
# =============================================================================
if [[ "$SKIP_REPLICAS" == "false" ]]; then
  boot_services "STAGE 7 — Replicas" sftp-service-2 sftp-service-3 ftp-service-2 ftp-service-3 ftp-web-service-2 ai-engine-2 || true
else
  log "${BOLD}STAGE 7 — Replicas: SKIPPED${RESET}"
  echo ""
fi

# =============================================================================
# STAGE 8: UI
# =============================================================================
if [[ "$SKIP_UI" == "false" ]]; then
  docker compose up -d ui-service 2>/dev/null && ok "ui-service started" || warn "ui-service not in compose"
  echo ""
fi

# =============================================================================
# Post-boot validation
# =============================================================================
log "${BOLD}Post-boot schema validation${RESET}"
bash "$SCRIPT_DIR/install-db.sh" --docker --validate-only 2>/dev/null && ok "All schemas valid" || warn "Some schemas have issues"
echo ""

# =============================================================================
TOTAL=$(( $(date +%s) - BOOT_START ))
echo -e "${CYAN}${BOLD}╔═══════════════════════════════════════════════════════════╗${RESET}"
echo -e "${CYAN}${BOLD}║  BOOT COMPLETE in $((TOTAL/60))m $((TOTAL%60))s                                     ║${RESET}"
echo -e "${CYAN}${BOLD}╚═══════════════════════════════════════════════════════════╝${RESET}"
echo ""
docker compose ps --format "table {{.Name}}\t{{.Status}}" 2>/dev/null | head -30
echo ""
echo -e "  ${GREEN}Admin UI:${RESET}  http://localhost:3000"
echo -e "  ${GREEN}API:${RESET}       http://localhost:8080"
echo -e "  ${GREEN}SFTP:${RESET}      localhost:2222"
echo ""

#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Tier-2 demo boot
# =============================================================================
# Boots a curated subset of services that fits on a ~10 GB laptop while still
# exercising the full admin UI, Flow Fabric, AI engine, and Platform Sentinel.
#
# Budget (rough):
#   Infra (postgres/redis/rabbit/redpanda/SPIRE) ..... ~2.3 GB
#   14 Java services @ -Xmx384m (≈350 MB RSS each) ... ~4.9 GB
#   UI (ui-service + partner-portal)  ................ ~0.3 GB
#   Docker Desktop / OS overhead ...................... ~2.0 GB
#   ---------------------------------------------------------
#   Total ............................................. ~9.5 GB
#
# Dropped vs full stack:
#   - Replicas: sftp-2/3, ftp-2/3, ftp-web-2, ai-engine-2
#   - Niche:    screening, edi-converter, as2, external-forwarder, dmz-proxy
#   - Observ.:  prometheus, alertmanager, loki, promtail, grafana
#   - UIs:      ftp-web-ui, api-gateway (admin UI and partner portal kept)
#   - Storage:  minio (local filesystem only)
#
# Usage:  ./scripts/demo-start.sh
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

# --- Services to boot (in dependency order) ---------------------------------
INFRA=(
  spire-server spire-init spire-agent
  postgres redis rabbitmq redpanda
)

CORE=(
  onboarding-api
  config-service
  gateway-service
  encryption-service
  storage-manager
  keystore-manager
  license-service
  analytics-service
  ai-engine
  platform-sentinel
  notification-service
  sftp-service
  ftp-service
  ftp-web-service
)

UIS=(
  ui-service
  partner-portal
)

# --- Helpers ----------------------------------------------------------------
log()  { printf '\n\033[1;34m[demo-start]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[demo-start] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[demo-start] %s\033[0m\n' "$*" >&2; exit 1; }

wait_healthy() {
  local container="$1" max_secs="${2:-180}"
  local waited=0
  log "Waiting up to ${max_secs}s for ${container} health..."
  while (( waited < max_secs )); do
    local state
    state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || echo "missing")
    case "$state" in
      healthy|running) return 0 ;;
      missing) warn "Container ${container} not found yet..." ;;
      *) ;;
    esac
    sleep 3; waited=$((waited + 3))
  done
  warn "${container} did not reach healthy within ${max_secs}s — continuing anyway"
  return 1
}

wait_http() {
  local url="$1" max_secs="${2:-300}" waited=0
  log "Polling ${url} (up to ${max_secs}s)..."
  while (( waited < max_secs )); do
    if curl -skf "$url" >/dev/null 2>&1; then
      log "${url} ready after ${waited}s"
      return 0
    fi
    sleep 5; waited=$((waited + 5))
  done
  warn "${url} did not respond within ${max_secs}s — continuing anyway"
  return 1
}

# --- Preflight ---------------------------------------------------------------
command -v docker >/dev/null || die "docker is not on PATH"
docker info >/dev/null 2>&1 || die "Docker daemon is not running. Start Docker Desktop first."

log "Platform root: $(pwd)"
log "Demo tier: 14 core services + infra + 2 UIs (no replicas, no observability stack)"

# --- Phase 1: infrastructure -------------------------------------------------
log "Phase 1/3 — Starting infrastructure (SPIRE, postgres, redis, rabbitmq, redpanda)..."
docker compose up -d "${INFRA[@]}"
wait_healthy mft-postgres 120
wait_healthy mft-redis    60
wait_healthy mft-rabbitmq 120
wait_healthy mft-redpanda 120

# --- Phase 2: core Java services --------------------------------------------
log "Phase 2/3 — Starting ${#CORE[@]} core services (this takes 2-5 min the first time)..."
docker compose up -d "${CORE[@]}"

# onboarding-api is the gate — the demo data script talks to it.
wait_http http://localhost:8080/actuator/health/readiness 480

# Best-effort health checks on the rest (don't block boot on these)
for port in 8084 8085 9086 9089 9090 9091 9093 9096 9097 9098; do
  wait_http "https://localhost:${port}/actuator/health/readiness" 120 || true
done

# --- Phase 3: UIs ------------------------------------------------------------
# --build forces a fresh build of ui-service + partner-portal so any old cached
# image (e.g., from before an Observatory.jsx or similar fix) is replaced.
# Java services skip rebuild because their cached images are pinned by source.
log "Phase 3/3 — Building and starting admin UI and partner portal..."
docker compose up -d --build "${UIS[@]}"
sleep 5

# --- Summary -----------------------------------------------------------------
GREEN=$'\e[1;32m'; RESET=$'\e[0m'
cat <<EOF

${GREEN}========================================================${RESET}
 Demo tier-2 stack is up.

 Admin UI   : https://localhost
 Partner    : https://localhost/partner
 Onboarding : http://localhost:8080/actuator/health
 Redpanda   : http://localhost:9644/v1/status/ready
 RabbitMQ   : http://localhost:15672    (guest / guest)
 Postgres   : localhost:5432            (postgres / postgres)

 Services running:
$(docker compose ps --format '  - {{.Name}} ({{.State}})' 2>/dev/null | head -40)

${GREEN}========================================================${RESET}

Next:
  ./scripts/demo-onboard.sh --skip-docker    # seed 1000+ entities
  ./scripts/demo-traffic.sh                   # populate Fabric/Sentinel dashboards
  (or just run: ./scripts/demo-all.sh for the whole flow)

EOF

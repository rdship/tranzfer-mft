#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Full-stack demo boot (end-to-end, real traffic, no mocks)
# =============================================================================
# Brings up the COMPLETE platform (~40 containers, all services + replicas +
# observability + every UI). Target machine: 9+ CPU / 25+ GB RAM.
# Docker Desktop memory allocation should be ≥ 20 GB.
#
# Use this if you want to exercise EVERY feature end-to-end (real SCREEN /
# EDI / AS2 / DMZ / Grafana / MinIO) rather than a curated subset.
#
# For smaller laptops (~10 GB RAM) use scripts/demo-start.sh instead, which
# boots a tier-2 subset.
#
# Budget (rough RSS, steady state):
#   Infra (SPIRE + postgres + redis + rabbitmq + redpanda) ...... ~2.5 GB
#   26 Java services @ -Xmx384m (≈400 MB RSS each) .............. ~10.5 GB
#   Observability (prometheus/grafana/loki/promtail/alert)  ..... ~1.2 GB
#   UIs (admin + partner portal + ftp-web-ui + api-gateway)  .... ~0.7 GB
#   MinIO (S3 gateway, optional)  ............................... ~0.3 GB
#   Docker Desktop / macOS overhead ............................. ~3.0 GB
#   ---------------------------------------------------------------
#   Total ....................................................... ~18 GB
#
# Phased boot avoids thundering-herd startup:
#   Phase 1: infra → wait healthy
#   Phase 2: everything else (compose resolves dependencies)
#   Phase 3: poll onboarding-api and key services until ready
#
# Usage:  ./scripts/demo-start-full.sh
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

INFRA=(
  spire-server spire-init spire-agent
  postgres redis rabbitmq redpanda
)

log()  { printf '\n\033[1;34m[demo-start-full]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[demo-start-full] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[demo-start-full] %s\033[0m\n' "$*" >&2; exit 1; }

wait_healthy() {
  local container="$1" max_secs="${2:-180}" waited=0
  log "Waiting up to ${max_secs}s for ${container} health..."
  while (( waited < max_secs )); do
    local state
    state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || echo "missing")
    case "$state" in
      healthy|running) return 0 ;;
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
    if curl -sf "$url" >/dev/null 2>&1; then
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

DOCKER_MEM_BYTES=$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)
DOCKER_MEM_GB=$(( DOCKER_MEM_BYTES / 1024 / 1024 / 1024 ))
if (( DOCKER_MEM_GB > 0 && DOCKER_MEM_GB < 18 )); then
  warn "Docker Desktop is allocated only ${DOCKER_MEM_GB} GB RAM."
  warn "Full stack needs ~18 GB. Open Docker Desktop → Settings → Resources → Memory"
  warn "and bump it to at least 20 GB, then re-run this script."
  warn "Continuing anyway — some services may be OOM-killed during boot."
fi

log "Platform root: $(pwd)"
log "Mode: FULL STACK (all services, replicas, observability, all UIs)"
log "Docker Desktop memory: ${DOCKER_MEM_GB} GB"

# --- Phase 1: infrastructure -------------------------------------------------
log "Phase 1/3 — Starting infrastructure (SPIRE, postgres, redis, rabbitmq, redpanda)..."
docker compose up -d "${INFRA[@]}"
wait_healthy mft-postgres 120
wait_healthy mft-redis    60
wait_healthy mft-rabbitmq 180
wait_healthy mft-redpanda 120

# --- Phase 2: everything else (compose resolves dependencies) ---------------
log "Phase 2/3 — Starting all remaining services (Java + UIs + observability + MinIO)..."
log "First run pulls images and builds ~26 service JARs. Expect 10-20 minutes."
# Include minio via its profile so the S3 gateway is available
COMPOSE_PROFILES="${COMPOSE_PROFILES:-minio}" docker compose --profile minio up -d

# --- Phase 3: wait for onboarding-api + best-effort key services -------------
log "Phase 3/3 — Waiting for onboarding-api (up to 10 min)..."
wait_http http://localhost:8080/actuator/health/readiness 600

log "Best-effort readiness poll on remaining services..."
for port in 8084 8085 8086 8087 8088 8089 8090 8091 8092 8093 8094 8095 8096 8097 8098; do
  wait_http "http://localhost:${port}/actuator/health/readiness" 180 || true
done

# --- Summary -----------------------------------------------------------------
G=$'\e[1;32m'; R=$'\e[0m'; B=$'\e[1m'
cat <<EOF

${G}================================================================${R}
 FULL STACK is up.

 ${B}Admin UI${R}         http://localhost:3000
 ${B}Partner Portal${R}   http://localhost:3002
 ${B}FTP Web UI${R}       http://localhost:3001
 ${B}API Gateway${R}      http://localhost:80
 ${B}Grafana${R}          http://localhost:3030   (admin / admin)
 ${B}Prometheus${R}       http://localhost:9090
 ${B}Alertmanager${R}     http://localhost:9093
 ${B}RabbitMQ${R}         http://localhost:15672  (guest / guest)
 ${B}Redpanda Admin${R}   http://localhost:9644
 ${B}MinIO Console${R}    http://localhost:9001   (minioadmin / minioadmin)
 ${B}Postgres${R}         localhost:5432          (postgres / postgres)

 ${B}Containers running${R}
$(docker compose ps --format '  {{.Name}} ({{.State}})' 2>/dev/null | head -50)

${G}================================================================${R}

Next:
  ./scripts/demo-onboard.sh --skip-docker    # seed 1000+ entities
  ./scripts/demo-traffic.sh                   # populate Fabric/Sentinel dashboards instantly
  (or just run: ./scripts/demo-all.sh --full  for the whole flow)

EOF

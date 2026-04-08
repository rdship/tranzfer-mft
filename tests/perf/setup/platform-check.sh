#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Platform Health Check
# Verifies all 20 services are UP before running performance tests.
# Usage:
#   ./tests/perf/setup/platform-check.sh            # Health check
#   ./tests/perf/setup/platform-check.sh --baseline  # Latency baseline per service
#   ./tests/perf/setup/platform-check.sh --wait      # Wait until all services UP (max 5 min)
# =============================================================================
set -uo pipefail

BASE_URL="${MFT_BASE_URL:-http://localhost}"
BASELINE=false
WAIT_MODE=false
MAX_WAIT=300
[[ "${1:-}" == "--baseline" ]] && BASELINE=true
[[ "${1:-}" == "--wait"     ]] && WAIT_MODE=true

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

declare -A SERVICES=(
  ["onboarding-api"]="8080"
  ["sftp-service"]="8081"
  ["ftp-service"]="8082"
  ["ftp-web-service"]="8083"
  ["config-service"]="8084"
  ["gateway-service"]="8085"
  ["encryption-service"]="8086"
  ["forwarder-service"]="8087"
  ["dmz-proxy"]="8088"
  ["license-service"]="8089"
  ["analytics-service"]="8090"
  ["ai-engine"]="8091"
  ["screening-service"]="8092"
  ["keystore-manager"]="8093"
  ["as2-service"]="8094"
  ["edi-converter"]="8095"
  ["storage-manager"]="8096"
  ["notification-service"]="8097"
  ["platform-sentinel"]="8098"
)

check_service() {
  local name="$1"
  local port="$2"
  local start_ms
  start_ms=$(date +%s%3N)
  local response
  response=$(curl -s -o /dev/null -w "%{http_code}" \
    --max-time 5 \
    "${BASE_URL}:${port}/actuator/health" 2>/dev/null)
  local end_ms
  end_ms=$(date +%s%3N)
  local latency=$((end_ms - start_ms))

  if [[ "$response" == "200" ]]; then
    printf "${GREEN}[✓]${NC} %-25s %5s  UP   (%dms)\n" "$name" "$port" "$latency"
    echo "UP $latency"
  else
    printf "${RED}[✗]${NC} %-25s %5s  DOWN (HTTP %s)\n" "$name" "$port" "$response"
    echo "DOWN 0"
  fi
}

check_infra() {
  # PostgreSQL
  if docker exec mft-postgres pg_isready -U mftuser -q 2>/dev/null; then
    printf "${GREEN}[✓]${NC} %-25s %5s  UP\n" "PostgreSQL" "5432"
  else
    printf "${RED}[✗]${NC} %-25s %5s  DOWN\n" "PostgreSQL" "5432"
  fi

  # RabbitMQ
  local rmq
  rmq=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 \
    http://localhost:15672/api/overview 2>/dev/null)
  if [[ "$rmq" == "200" || "$rmq" == "401" ]]; then
    printf "${GREEN}[✓]${NC} %-25s %5s  UP   (mgmt: 15672)\n" "RabbitMQ" "5672"
  else
    printf "${RED}[✗]${NC} %-25s %5s  DOWN\n" "RabbitMQ" "5672"
  fi

  # SFTP port 2222
  if nc -z localhost 2222 2>/dev/null; then
    printf "${GREEN}[✓]${NC} %-25s %5s  UP   (SSH)\n" "SFTP port" "2222"
  else
    printf "${YELLOW}[!]${NC} %-25s %5s  UNREACHABLE\n" "SFTP port" "2222"
  fi

  # FTP port 21
  if nc -z localhost 21 2>/dev/null; then
    printf "${GREEN}[✓]${NC} %-25s %5s  UP\n" "FTP port" "21"
  else
    printf "${YELLOW}[!]${NC} %-25s %5s  UNREACHABLE\n" "FTP port" "21"
  fi
}

run_check() {
  local DOWN_COUNT=0
  local TOTAL=0
  local TOTAL_LATENCY=0

  echo ""
  echo -e "${CYAN}=== TranzFer MFT Platform Health Check ===${NC}"
  echo -e "Time: $(date '+%Y-%m-%d %H:%M:%S')"
  echo ""

  for name in $(echo "${!SERVICES[@]}" | tr ' ' '\n' | sort); do
    port="${SERVICES[$name]}"
    result=$(check_service "$name" "$port")
    status=$(echo "$result" | tail -1 | awk '{print $1}')
    latency=$(echo "$result" | tail -1 | awk '{print $2}')
    TOTAL=$((TOTAL + 1))
    if [[ "$status" == "DOWN" ]]; then
      DOWN_COUNT=$((DOWN_COUNT + 1))
    else
      TOTAL_LATENCY=$((TOTAL_LATENCY + latency))
    fi
  done

  echo ""
  check_infra

  echo ""
  HEALTHY=$((TOTAL - DOWN_COUNT))
  if [[ $DOWN_COUNT -eq 0 ]]; then
    echo -e "${GREEN}All $TOTAL services healthy. Platform ready for testing.${NC}"
    if [[ "$BASELINE" == "true" && $TOTAL -gt 0 ]]; then
      AVG=$((TOTAL_LATENCY / TOTAL))
      echo -e "Avg health check latency: ${AVG}ms"
    fi
    return 0
  else
    echo -e "${RED}$DOWN_COUNT/$TOTAL services DOWN. Fix before running tests.${NC}"
    echo ""
    echo "Troubleshooting:"
    echo "  docker compose ps                        # See all container states"
    echo "  docker compose logs mft-<name> --tail=50 # Check specific service"
    echo "  docker compose up -d                     # Restart stopped services"
    return 1
  fi
}

if [[ "$WAIT_MODE" == "true" ]]; then
  echo "Waiting for all services to be healthy (max ${MAX_WAIT}s)..."
  START=$(date +%s)
  while true; do
    NOW=$(date +%s)
    ELAPSED=$((NOW - START))
    if [[ $ELAPSED -ge $MAX_WAIT ]]; then
      echo -e "${RED}Timeout: services not healthy after ${MAX_WAIT}s${NC}"
      exit 1
    fi
    if run_check 2>/dev/null | grep -q "All.*healthy"; then
      echo -e "${GREEN}Platform ready after ${ELAPSED}s${NC}"
      exit 0
    fi
    echo "Not ready yet (${ELAPSED}s elapsed)... retrying in 10s"
    sleep 10
  done
else
  run_check
fi

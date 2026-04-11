#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — MTTR (Mean Time To Recovery) Measurement
# Kills each service one at a time, measures time to recover.
#
# Usage: ./tests/perf/resilience/recovery-time.sh
# =============================================================================
set -uo pipefail

# Portable millisecond timestamp — macOS BSD date does not support %N.
# Prefer gdate (Homebrew coreutils) when available, fall back to python.
ms() {
  if command -v gdate &>/dev/null; then gdate +%s%3N
  else python3 -c "import time; print(int(time.time()*1000))"; fi
}
BASE_URL="${MFT_BASE_URL:-http://localhost}"
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

declare -A SERVICES=(
  ["onboarding-api"]="8080"
  ["analytics-service"]="8090"
  ["ai-engine"]="8091"
  ["notification-service"]="8097"
  ["keystore-manager"]="8093"
  ["license-service"]="8089"
  ["platform-sentinel"]="8098"
  ["edi-converter"]="8095"
)

echo ""
echo "=== MTTR (Mean Time To Recovery) Test ==="
echo "Kills each service, restarts it, measures time to first 200."
echo ""
printf "%-28s %-12s %-15s %s\n" "Service" "Kill(secs)" "MTTR(secs)" "Result"
printf "%-28s %-12s %-15s %s\n" "────────────────────────────" "──────────" "─────────────" "──────"

for svc in "${!SERVICES[@]}"; do
  port="${SERVICES[$svc]}"
  container="mft-${svc}"

  # Skip if container not running
  if ! docker ps --format '{{.Names}}' | grep -q "^${container}$"; then
    printf "%-28s %-12s %-15s %s\n" "$svc" "SKIP" "SKIP" "container not found"
    continue
  fi

  # Kill
  KILL_TIME=$(ms)
  docker stop "$container" > /dev/null 2>&1

  STOP_MS=$(( $(ms) - KILL_TIME ))

  sleep 2  # Brief pause

  # Restart
  RESTART_TIME=$(ms)
  docker start "$container" > /dev/null 2>&1

  # Wait for recovery
  RECOVERED=false
  MTTR_MS=99999

  for i in $(seq 1 60); do
    sleep 2
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 \
      "${BASE_URL}:${port}/actuator/health" 2>/dev/null)
    if [[ "$code" == "200" ]]; then
      MTTR_MS=$(( $(ms) - RESTART_TIME ))
      RECOVERED=true
      break
    fi
  done

  STOP_S=$(( STOP_MS / 1000 ))
  MTTR_S=$(( MTTR_MS / 1000 ))

  if [[ "$RECOVERED" == "true" ]]; then
    if   [[ $MTTR_S -le 15 ]]; then result="${GREEN}EXCELLENT${NC}"
    elif [[ $MTTR_S -le 30 ]]; then result="${GREEN}PASS${NC}"
    elif [[ $MTTR_S -le 60 ]]; then result="${YELLOW}WARN${NC}"
    else                             result="${RED}FAIL${NC}"
    fi
  else
    result="${RED}TIMEOUT${NC}"
    MTTR_S=">120"
  fi

  printf "%-28s %-12s %-15s " "$svc" "${STOP_S}s" "${MTTR_S}s"
  echo -e "$result"

  sleep 5  # Allow stabilization between services
done

echo ""
echo "Thresholds:"
echo "  EXCELLENT: < 15s | PASS: < 30s | WARN: 30-60s | FAIL: > 60s"

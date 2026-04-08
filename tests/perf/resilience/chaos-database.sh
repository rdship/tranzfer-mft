#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Database Chaos Test
# Kills PostgreSQL, verifies circuit breakers open with 503 (not hangs/500s).
# Measures MTTR per service after DB restart.
#
# Usage: ./tests/perf/resilience/chaos-database.sh
# =============================================================================
set -uo pipefail

BASE_URL="${MFT_BASE_URL:-http://localhost}"
ADMIN_EMAIL="${MFT_ADMIN_EMAIL:-admin@filetransfer.local}"
ADMIN_PASS="${MFT_ADMIN_PASS:-Admin@1234}"
KILL_DURATION=${DB_KILL_DURATION:-60}  # seconds to keep DB down

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

declare -A SERVICES=(
  ["onboarding-api"]="8080"     ["analytics-service"]="8090"
  ["screening-service"]="8092"  ["encryption-service"]="8086"
  ["keystore-manager"]="8093"   ["storage-manager"]="8096"
  ["platform-sentinel"]="8098"  ["license-service"]="8089"
)

echo ""
echo -e "${CYAN}=== Database Chaos Test ===${NC}"
echo "Kill duration: ${KILL_DURATION}s"
echo ""

check_service_health() {
  local port="$1"
  local res
  res=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 \
    "${BASE_URL}:${port}/actuator/health" 2>/dev/null)
  echo "$res"
}

check_endpoint() {
  local url="$1"
  local res
  res=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null)
  echo "$res"
}

# ── Pre-kill state ────────────────────────────────────────────────────────────
echo -e "${GREEN}[1] Pre-kill service health...${NC}"
declare -A PRE_STATES
for svc in "${!SERVICES[@]}"; do
  port="${SERVICES[$svc]}"
  code=$(check_service_health "$port")
  PRE_STATES[$svc]="$code"
  printf "  %-25s %s\n" "$svc" "$code"
done

# ── Kill PostgreSQL ───────────────────────────────────────────────────────────
echo ""
echo -e "${RED}[2] KILLING PostgreSQL (mft-postgres)...${NC}"
KILL_TIME=$(date +%s%3N)
docker stop mft-postgres
echo "  PostgreSQL stopped at $(date '+%H:%M:%S')"

# ── Monitor circuit breaker open times ───────────────────────────────────────
echo ""
echo "[3] Monitoring circuit breaker open times (expect 503, not 500 or hang)..."
echo ""
printf "%-8s" "Time(s)"
for svc in $(echo "${!SERVICES[@]}" | tr ' ' '\n' | sort); do
  printf "%-22s" "$svc"
done
echo ""
echo "─────────────────────────────────────────────────────────────────────────────"

declare -A CIRCUIT_OPEN_AT
declare -A CIRCUIT_OPEN_RESP

for attempt in $(seq 1 30); do
  sleep 2
  NOW_MS=$(date +%s%3N)
  ELAPSED=$(( (NOW_MS - KILL_TIME) / 1000 ))
  printf "%-8s" "${ELAPSED}s"

  for svc in $(echo "${!SERVICES[@]}" | tr ' ' '\n' | sort); do
    port="${SERVICES[$svc]}"
    code=$(check_service_health "$port")

    # Record first time circuit opened (not 200)
    if [[ "$code" != "200" && -z "${CIRCUIT_OPEN_AT[$svc]:-}" ]]; then
      CIRCUIT_OPEN_AT[$svc]=$ELAPSED
      CIRCUIT_OPEN_RESP[$svc]=$code
    fi

    # Color code
    if [[ "$code" == "200" ]];         then printf "${GREEN}%-22s${NC}" "$code (OK)"
    elif [[ "$code" == "503" ]];       then printf "${YELLOW}%-22s${NC}" "$code (circuit)"
    elif [[ "$code" == "000" ]];       then printf "${RED}%-22s${NC}" "TIMEOUT"
    elif [[ "$code" =~ ^5 ]];          then printf "${RED}%-22s${NC}" "$code (ERROR)"
    else                                    printf "%-22s" "$code"
    fi
  done
  echo ""
done

# ── Wait ──────────────────────────────────────────────────────────────────────
echo ""
echo "[4] DB down for ${KILL_DURATION}s total. Waiting..."
ELAPSED_SO_FAR=$(( ($(date +%s%3N) - KILL_TIME) / 1000 ))
REMAINING=$((KILL_DURATION - ELAPSED_SO_FAR))
[[ $REMAINING -gt 0 ]] && sleep $REMAINING

# ── Restart PostgreSQL ────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}[5] RESTARTING PostgreSQL...${NC}"
RESTART_TIME=$(date +%s%3N)
docker start mft-postgres

# Wait for PG to be ready
for i in $(seq 1 30); do
  sleep 2
  if docker exec mft-postgres pg_isready -U mftuser -q 2>/dev/null; then
    PG_READY=$(( ($(date +%s%3N) - RESTART_TIME) / 1000 ))
    echo "  PostgreSQL ready after ${PG_READY}s"
    break
  fi
done

# ── Measure MTTR per service ──────────────────────────────────────────────────
echo ""
echo "[6] Measuring MTTR per service (time to first 200 after restart)..."
declare -A RECOVERY_AT

for attempt in $(seq 1 60); do
  sleep 2
  NOW_MS=$(date +%s%3N)
  ELAPSED=$(( (NOW_MS - RESTART_TIME) / 1000 ))
  ALL_RECOVERED=true

  for svc in "${!SERVICES[@]}"; do
    if [[ -z "${RECOVERY_AT[$svc]:-}" ]]; then
      port="${SERVICES[$svc]}"
      code=$(check_service_health "$port")
      if [[ "$code" == "200" ]]; then
        RECOVERY_AT[$svc]=$ELAPSED
        echo "  ✓ ${svc} recovered at ${ELAPSED}s"
      else
        ALL_RECOVERED=false
      fi
    fi
  done

  [[ "$ALL_RECOVERED" == "true" ]] && break
done

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "DATABASE CHAOS TEST RESULTS"
echo "═══════════════════════════════════════════════════════════════"
printf "%-30s %-15s %-10s %s\n" "Service" "Circuit Open" "Response" "MTTR"
printf "%-30s %-15s %-10s %s\n" "─────────────────────────────" "────────────" "────────" "────"

for svc in $(echo "${!SERVICES[@]}" | tr ' ' '\n' | sort); do
  circuit_t="${CIRCUIT_OPEN_AT[$svc]:-not detected}"
  circuit_r="${CIRCUIT_OPEN_RESP[$svc]:-N/A}"
  recovery_t="${RECOVERY_AT[$svc]:-timeout}"

  # Assess quality of circuit open response
  quality=""
  if [[ "${CIRCUIT_OPEN_RESP[$svc]:-}" == "503" ]]; then quality="✓ clean 503"
  elif [[ "${CIRCUIT_OPEN_RESP[$svc]:-}" =~ ^5 ]];  then quality="! messy 5xx"
  elif [[ "${CIRCUIT_OPEN_RESP[$svc]:-}" == "000" ]]; then quality="✗ timeout/hang"
  fi

  printf "%-30s %-15s %-10s %s\n" \
    "$svc" \
    "${circuit_t}${circuit_t:+s}" \
    "${circuit_r} ${quality}" \
    "${recovery_t}${recovery_t:+s}"
done

echo ""
echo "Pass criteria:"
echo "  Circuit breaker opens: < 5s (PASS) / 5-15s (WARN) / >15s (FAIL)"
echo "  Recovery (MTTR): < 30s (PASS) / 30-60s (WARN) / >60s (FAIL)"
echo ""
echo "Expected: ALL services return 503 (not 500, not timeout) when DB is down."
echo "Expected: ALL services recover within 30s of DB restart."

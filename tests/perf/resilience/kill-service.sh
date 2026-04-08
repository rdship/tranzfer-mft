#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Kill Service Resilience Test
# Kills one service, measures circuit breaker open time + recovery time.
# Run WHILE a load test is already running in another terminal.
#
# Usage:
#   ./tests/perf/resilience/kill-service.sh --service ai-engine --duration 60
#   ./tests/perf/resilience/kill-service.sh --service analytics-service --duration 60
#   ./tests/perf/resilience/kill-service.sh --service keystore-manager --duration 30
# =============================================================================
set -uo pipefail

BASE_URL="${MFT_BASE_URL:-http://localhost}"
SENTINEL_PORT=8098
SERVICE=""
DURATION=60

for i in "$@"; do
  case $i in
    --service=*) SERVICE="${i#*=}" ;;
    --service)   SERVICE="${2}"; shift ;;
    --duration=*)DURATION="${i#*=}" ;;
    --duration)  DURATION="${2}"; shift ;;
  esac
  shift 2>/dev/null || true
done

if [[ -z "$SERVICE" ]]; then
  echo "Usage: $0 --service <service-name> [--duration <seconds>]"
  echo "Available: ai-engine, analytics-service, notification-service, keystore-manager,"
  echo "           encryption-service, screening-service, config-service, license-service,"
  echo "           platform-sentinel, sftp-service, ftp-service, storage-manager"
  exit 1
fi

CONTAINER="mft-${SERVICE}"
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

# ── Expected behavior per service ────────────────────────────────────────────
declare -A EXPECTED_BEHAVIOR=(
  ["ai-engine"]="GRACEFUL — files flow through with ALLOWED decision"
  ["analytics-service"]="GRACEFUL — analytics returns empty/cached, transfers continue"
  ["notification-service"]="GRACEFUL — notifications queue in RabbitMQ, not lost"
  ["keystore-manager"]="GRACEFUL — local key cache activates (24h TTL)"
  ["license-service"]="GRACEFUL — 24h cached license continues working"
  ["platform-sentinel"]="GRACEFUL — observer only, platform unaffected"
  ["screening-service"]="FAIL-FAST — transfers BLOCKED (compliance service)"
  ["encryption-service"]="FAIL-FAST — transfers BLOCKED (security required)"
  ["config-service"]="FAIL-FAST — dependent services may fail"
  ["sftp-service"]="PARTIAL — SFTP transfers fail, REST API continues"
  ["ftp-service"]="PARTIAL — FTP transfers fail, other protocols continue"
  ["storage-manager"]="FAIL-FAST — storage unavailable"
)

echo ""
echo -e "${YELLOW}=== Kill Service Resilience Test ===${NC}"
echo "Service:          $SERVICE"
echo "Container:        $CONTAINER"
echo "Kill duration:    ${DURATION}s"
echo "Expected:         ${EXPECTED_BEHAVIOR[$SERVICE]:-unknown}"
echo ""

# ── Pre-kill baseline ─────────────────────────────────────────────────────────
echo -e "${GREEN}[1] Pre-kill baseline...${NC}"
BEFORE_SCORE=$(curl -s --max-time 3 "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/health-score" \
  2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('overallScore','?'))" 2>/dev/null || echo "N/A")
BEFORE_TIME=$(date +%s%3N)
echo "  Sentinel health score before kill: $BEFORE_SCORE"

# ── Kill the service ──────────────────────────────────────────────────────────
echo ""
echo -e "${RED}[2] KILLING $CONTAINER...${NC}"
KILL_TIME=$(date +%s%3N)
docker stop "$CONTAINER" 2>/dev/null || { echo "Container not found: $CONTAINER"; exit 1; }
echo "  Killed at $(date '+%H:%M:%S')"

# ── Monitor circuit breaker open time ─────────────────────────────────────────
echo ""
echo "[3] Monitoring circuit state (watching for 503/circuit-open)..."
declare -A PORT_MAP=(
  ["ai-engine"]="8091" ["analytics-service"]="8090" ["notification-service"]="8097"
  ["keystore-manager"]="8093" ["license-service"]="8089" ["platform-sentinel"]="8098"
  ["screening-service"]="8092" ["encryption-service"]="8086" ["config-service"]="8084"
  ["sftp-service"]="8081" ["ftp-service"]="8082" ["storage-manager"]="8096"
)

SERVICE_PORT="${PORT_MAP[$SERVICE]:-}"
CIRCUIT_OPEN_MS=""

if [[ -n "$SERVICE_PORT" ]]; then
  for attempt in $(seq 1 30); do
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 \
      "${BASE_URL}:${SERVICE_PORT}/actuator/health" 2>/dev/null)
    NOW_MS=$(date +%s%3N)
    ELAPSED_MS=$((NOW_MS - KILL_TIME))

    if [[ "$RESPONSE" != "200" ]]; then
      CIRCUIT_OPEN_MS=$ELAPSED_MS
      echo "  Circuit OPEN after ${ELAPSED_MS}ms (HTTP ${RESPONSE})"
      break
    fi
    sleep 0.5
  done
fi

# ── Wait for kill duration ────────────────────────────────────────────────────
echo ""
echo "[4] Service DOWN for ${DURATION}s..."
ELAPSED=0
while [[ $ELAPSED -lt $DURATION ]]; do
  echo -ne "\r  Down: ${ELAPSED}s/${DURATION}s "
  sleep 5
  ELAPSED=$((ELAPSED + 5))
done
echo ""

# ── Restart ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}[5] RESTARTING $CONTAINER...${NC}"
RESTART_TIME=$(date +%s%3N)
docker start "$CONTAINER"

# ── Measure recovery time ─────────────────────────────────────────────────────
echo ""
echo "[6] Waiting for recovery..."
RECOVERED=false
RECOVERY_MS=""

for attempt in $(seq 1 60); do
  sleep 2
  RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 \
    "${BASE_URL}:${SERVICE_PORT:-8080}/actuator/health" 2>/dev/null)
  NOW_MS=$(date +%s%3N)

  if [[ "$RESPONSE" == "200" ]]; then
    RECOVERY_MS=$((NOW_MS - RESTART_TIME))
    RECOVERED=true
    echo "  Service recovered in ${RECOVERY_MS}ms (${RECOVERY_MS}ms after restart)"
    break
  fi

  echo -ne "\r  Waiting... ${attempt}s "
done
echo ""

# ── Post-recovery Sentinel check ──────────────────────────────────────────────
sleep 10
AFTER_SCORE=$(curl -s --max-time 3 "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/health-score" \
  2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('overallScore','?'))" 2>/dev/null || echo "N/A")

# Check for Sentinel findings generated during outage
FINDINGS=$(curl -s --max-time 5 "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/findings?status=OPEN" \
  2>/dev/null | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  items = d if isinstance(d, list) else d.get('content', [])
  recent = [f for f in items if '$SERVICE' in f.get('affectedService','') or 'service_unhealthy' in f.get('ruleName','')]
  for f in recent[:3]:
    print(f'  [{f[\"severity\"]}] {f[\"ruleName\"]}: {f[\"title\"]}')
except: print('  (could not parse findings)')
" 2>/dev/null)

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════"
echo "KILL SERVICE RESULTS: $SERVICE"
echo "═══════════════════════════════════════════════════"
printf "%-30s %s\n" "Expected behavior:"    "${EXPECTED_BEHAVIOR[$SERVICE]:-unknown}"
printf "%-30s %s\n" "Circuit open time:"    "${CIRCUIT_OPEN_MS:+${CIRCUIT_OPEN_MS}ms}${CIRCUIT_OPEN_MS:-not detected}"
printf "%-30s %s\n" "Kill duration:"        "${DURATION}s"
printf "%-30s %s\n" "Recovery time:"        "${RECOVERY_MS:+${RECOVERY_MS}ms recovered}${RECOVERY_MS:-timeout}"
printf "%-30s %s\n" "Health score before:"  "$BEFORE_SCORE"
printf "%-30s %s\n" "Health score after:"   "$AFTER_SCORE"
echo ""

if [[ -n "$FINDINGS" ]]; then
  echo "Sentinel findings generated:"
  echo "$FINDINGS"
fi

# ── Pass/Fail ─────────────────────────────────────────────────────────────────
echo ""
if [[ "$RECOVERED" == "true" ]]; then
  RECOVERY_S=$((${RECOVERY_MS:-99999} / 1000))
  if [[ $RECOVERY_S -le 30 ]]; then
    echo -e "${GREEN}✓ PASS — Service recovered in ${RECOVERY_S}s (threshold: 30s)${NC}"
  elif [[ $RECOVERY_S -le 60 ]]; then
    echo -e "${YELLOW}! WARN — Recovery took ${RECOVERY_S}s (target < 30s)${NC}"
  else
    echo -e "${RED}✗ FAIL — Recovery took ${RECOVERY_S}s (threshold: 60s)${NC}"
  fi
else
  echo -e "${RED}✗ FAIL — Service did not recover within test window${NC}"
fi

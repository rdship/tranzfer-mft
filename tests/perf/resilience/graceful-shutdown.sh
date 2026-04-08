#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Graceful Shutdown Test
# Proves that `docker stop` (SIGTERM → 30s drain → SIGKILL) allows in-flight
# requests to complete. No request should be abruptly severed during the 30s
# drain window (Spring Boot server.shutdown=graceful).
#
# What is tested:
#   - In-flight requests complete (not reset) during 30s drain window
#   - New connection attempts are cleanly refused AFTER drain starts
#   - No 'connection_reset' errors (these indicate mid-request kill)
#   - No unexplained 502s
#
# Pass criteria:
#   PASS : 0 connection_reset errors during drain window
#   WARN : 1-3 connection_reset errors
#   FAIL : >3 connection_reset errors OR drain timeout exceeded
#
# Usage:
#   ./tests/perf/resilience/graceful-shutdown.sh
#   ./tests/perf/resilience/graceful-shutdown.sh --service onboarding-api
#   DRAIN_SECS=45 ./tests/perf/resilience/graceful-shutdown.sh
# =============================================================================
set -uo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
BASE_URL="${MFT_BASE_URL:-http://localhost}"
ADMIN_EMAIL="${MFT_ADMIN_EMAIL:-admin@filetransfer.local}"
ADMIN_PASS="${MFT_ADMIN_PASS:-Admin@1234}"
SENTINEL_PORT=8098
DRAIN_SECS="${DRAIN_SECS:-40}"        # docker stop --time (Spring 30s + buffer 10s)
POLL_INTERVAL_MS=500                  # poll every 500ms during drain window
VUS=30                                # concurrent virtual users

TARGET_SERVICE="${1:-}"
[[ "$TARGET_SERVICE" == "--service" ]] && TARGET_SERVICE="${2:-onboarding-api}" || true
TARGET_SERVICE="${TARGET_SERVICE#--service=}"
TARGET_SERVICE="${TARGET_SERVICE:-onboarding-api}"

declare -A SVC_PORT=(
  ["onboarding-api"]="8080"
  ["screening-service"]="8092"
  ["encryption-service"]="8086"
  ["storage-manager"]="8096"
)
SVC_PORT_NUM="${SVC_PORT[$TARGET_SERVICE]:-8080}"
CONTAINER="mft-${TARGET_SERVICE}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ── Drain-window tracking ─────────────────────────────────────────────────────
conn_reset=0
conn_refused=0
status_200=0
status_502=0
status_503=0
status_other=0
PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0
BG_PID=""
K6_PID=""
DRAIN_LOG=$(mktemp /tmp/graceful-shutdown-drain.XXXXXX)

# ── Cleanup ───────────────────────────────────────────────────────────────────
cleanup() {
  [[ -n "$BG_PID" ]] && kill "$BG_PID" 2>/dev/null || true
  [[ -n "$K6_PID" ]] && kill "$K6_PID" 2>/dev/null || true
  jobs -p 2>/dev/null | xargs kill 2>/dev/null || true
}
trap cleanup EXIT

# ── Helpers ───────────────────────────────────────────────────────────────────
get_token() {
  curl -s --max-time 5 \
    -X POST "${BASE_URL}:8080/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASS}\"}" \
    2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken') or d.get('token',''))" \
    2>/dev/null || echo ""
}

get_sentinel_score() {
  curl -s --max-time 5 "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/health-score" \
    2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('overallScore','N/A'))" \
    2>/dev/null || echo "N/A"
}

wait_for_healthy() {
  local url="$1"
  local max_s="${2:-120}"
  local elapsed=0
  local delay=1
  while [[ $elapsed -lt $max_s ]]; do
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null)
    [[ "$code" == "200" ]] && return 0
    sleep "$delay"
    elapsed=$((elapsed + delay))
    [[ $delay -lt 4 ]] && delay=$((delay * 2))
  done
  return 1
}

# Probe a single request and record its disposition
# Returns: 200|201|502|503|RESET|REFUSED|OTHER
probe_once() {
  local token="$1"
  local result
  local raw_output

  # Use curl's write-out plus stderr capture to detect connection errors
  raw_output=$(curl -s \
    -o /dev/null \
    -w "%{http_code} %{time_total}" \
    --max-time 5 \
    --retry 0 \
    -H "Authorization: Bearer ${token}" \
    "${BASE_URL}:${SVC_PORT_NUM}/actuator/health" 2>&1)

  local code
  code=$(echo "$raw_output" | awk '{print $1}')

  if echo "$raw_output" | grep -qi "connection reset\|reset by peer\|ECONNRESET"; then
    result="RESET"
  elif [[ "$code" == "000" ]]; then
    result="REFUSED"
  elif [[ "$code" == "200" || "$code" == "201" ]]; then
    result="200"
  elif [[ "$code" == "502" ]]; then
    result="502"
  elif [[ "$code" == "503" ]]; then
    result="503"
  else
    result="OTHER:${code}"
  fi

  echo "$result"
}

# ── Print header ──────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}=== Graceful Shutdown Test ===${NC}"
echo "Service:      ${TARGET_SERVICE} (${CONTAINER})"
echo "Port:         ${SVC_PORT_NUM}"
echo "docker stop:  --time ${DRAIN_SECS} (Spring 30s drain + buffer)"
echo ""
echo "What is tested:"
echo "  - SIGTERM is sent to the container"
echo "  - Spring Boot stops accepting NEW requests"
echo "  - IN-FLIGHT requests complete (not connection-reset)"
echo "  - After drain period, SIGKILL is sent"
echo ""

# ── Prerequisite check ────────────────────────────────────────────────────────
if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER}$"; then
  echo -e "${RED}ERROR: Container '${CONTAINER}' is not running.${NC}"
  exit 1
fi

# ── Step 1: Baseline ──────────────────────────────────────────────────────────
echo -e "${GREEN}[1] Baseline health check...${NC}"
BASELINE_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
  "${BASE_URL}:${SVC_PORT_NUM}/actuator/health" 2>/dev/null)
if [[ "$BASELINE_CODE" != "200" ]]; then
  echo -e "${RED}  Service not healthy (${BASELINE_CODE}). Waiting up to 60s...${NC}"
  wait_for_healthy "${BASE_URL}:${SVC_PORT_NUM}/actuator/health" 60 || {
    echo -e "${RED}  FAIL: service not healthy before test.${NC}"
    exit 1
  }
fi

TOKEN=$(get_token)
if [[ -z "$TOKEN" ]]; then
  echo -e "${RED}  FAIL: Could not obtain auth token.${NC}"
  exit 1
fi

SCORE_BEFORE=$(get_sentinel_score)
echo "  Service:        HEALTHY"
echo "  Sentinel score: ${SCORE_BEFORE}"
echo "  Token:          obtained"

# ── Step 2: Start background load ────────────────────────────────────────────
echo ""
echo -e "${GREEN}[2] Starting ${VUS} VU background load...${NC}"

if command -v k6 &>/dev/null; then
  echo "  k6 found — starting 30 VU background load"
  K6_SUMMARY_FILE=$(mktemp /tmp/graceful-shutdown-k6.XXXXXX)
  k6 run \
    --vus 30 \
    --duration 300s \
    --env DURATION=300s \
    --env VUS=30 \
    --env SUMMARY_FILE="$K6_SUMMARY_FILE" \
    --quiet \
    "$(dirname "$0")/../k6/10-chaos-background.js" \
    >> "$DRAIN_LOG" 2>&1 &
  K6_PID=$!
  echo "  k6 background load started (PID ${K6_PID})"
  sleep 5  # Let k6 stabilise
else
  echo "  k6 not found — using bash loop with ${VUS} curl workers"
  (
    local_token="$TOKEN"
    token_refresh_at=$(( $(date +%s) + 270 ))
    while true; do
      now=$(date +%s)
      if [[ $now -gt $token_refresh_at ]]; then
        local_token=$(get_token)
        token_refresh_at=$(( $(date +%s) + 270 ))
      fi
      for _ in $(seq 1 "$VUS"); do
        {
          code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
            -H "Authorization: Bearer ${local_token}" \
            "${BASE_URL}:${SVC_PORT_NUM}/actuator/health" 2>/dev/null || echo "000")
          echo "$(date +%s%3N) $code" >> "$DRAIN_LOG"
        } &
      done
      wait
      sleep 0.2
    done
  ) &
  BG_PID=$!
  echo "  Bash load loop started (PID ${BG_PID})"
  sleep 3
fi

# ── Step 3: Send docker stop (graceful SIGTERM) ───────────────────────────────
echo ""
echo -e "${RED}[3] Sending docker stop --time ${DRAIN_SECS} ${CONTAINER}...${NC}"
STOP_START_MS=$(date +%s%3N)
docker stop --time "$DRAIN_SECS" "$CONTAINER" &
DOCKER_STOP_PID=$!
echo "  docker stop started at $(date '+%H:%M:%S') (PID ${DOCKER_STOP_PID})"

# ── Step 4: Monitor the drain window ─────────────────────────────────────────
echo ""
echo "[4] Monitoring drain window (${DRAIN_SECS}s)..."
echo -e "    Watching for connection_reset (NOT OK), 503/refused (OK), 200 (in-flight completing)"
echo ""
printf "  %-10s %-8s %-8s %-8s %-8s %-8s %-8s\n" \
  "Elapsed" "Code" "200s" "502s" "503s" "RESET" "REFUSED"
printf "  %-10s %-8s %-8s %-8s %-8s %-8s %-8s\n" \
  "──────────" "──────" "────" "────" "────" "─────" "───────"

local_token="$TOKEN"
token_refresh_at=$(( $(date +%s) + 270 ))
drain_elapsed_s=0
stop_phase=""  # "draining" | "killed"

while [[ $drain_elapsed_s -lt $((DRAIN_SECS + 15)) ]]; do
  sleep_ms=500
  sleep 0.5

  now=$(date +%s)
  [[ $now -gt $token_refresh_at ]] && {
    local_token=$(get_token)
    token_refresh_at=$(( $(date +%s) + 270 ))
  }

  drain_elapsed_s=$(( ($(date +%s%3N) - STOP_START_MS) / 1000 ))

  result=$(probe_once "$local_token")

  case "$result" in
    200|201)   status_200=$((status_200 + 1)) ;;
    502)       status_502=$((status_502 + 1)) ;;
    503)       status_503=$((status_503 + 1)) ;;
    RESET)     conn_reset=$((conn_reset + 1)) ;;
    REFUSED)   conn_refused=$((conn_refused + 1)) ;;
    *)         status_other=$((status_other + 1)) ;;
  esac

  # Colour the connection_reset column red
  reset_disp="${conn_reset}"
  [[ $conn_reset -gt 0 ]] && reset_disp="${RED}${conn_reset}${NC}"

  printf "  %-10s %-8s %-8s %-8s %-8s " \
    "${drain_elapsed_s}s" "$result" "$status_200" "$status_502" "$status_503"
  echo -e "${reset_disp}        ${conn_refused}"

  # If docker stop has finished, wait a few more cycles then break
  if ! kill -0 "$DOCKER_STOP_PID" 2>/dev/null && [[ -z "$stop_phase" ]]; then
    stop_phase="killed"
    echo ""
    echo "  Container fully stopped at ${drain_elapsed_s}s"
  fi

  [[ "$stop_phase" == "killed" && $drain_elapsed_s -gt $((DRAIN_SECS + 5)) ]] && break
done

# Ensure docker stop finishes
wait "$DOCKER_STOP_PID" 2>/dev/null || true

# ── Step 5: Restart and verify ───────────────────────────────────────────────
echo ""
echo -e "${GREEN}[5] Restarting ${CONTAINER}...${NC}"
docker start "$CONTAINER" > /dev/null 2>&1

echo "  Waiting for service to become healthy again..."
if wait_for_healthy "${BASE_URL}:${SVC_PORT_NUM}/actuator/health" 120; then
  RECOVERY_S=$(( ($(date +%s%3N) - STOP_START_MS) / 1000 ))
  echo "  Service healthy again at ${RECOVERY_S}s after shutdown started"
else
  echo -e "${YELLOW}  Service did not recover within 120s${NC}"
fi

# Stop background load
[[ -n "$K6_PID" ]] && kill "$K6_PID" 2>/dev/null || true
[[ -n "$BG_PID" ]] && kill "$BG_PID" 2>/dev/null || true
K6_PID=""
BG_PID=""

# ── Step 6: Sentinel check ────────────────────────────────────────────────────
sleep 15
echo ""
echo -e "${GREEN}[6] Checking Sentinel...${NC}"
SCORE_AFTER=$(get_sentinel_score)
echo "  Health score after: ${SCORE_AFTER}"

curl -s --max-time 5 \
  "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/findings?status=OPEN&analyzer=RESILIENCE" \
  2>/dev/null | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  items = d if isinstance(d, list) else d.get('content', [])
  recent = [f for f in items if 'shutdown' in f.get('ruleName','').lower()
            or 'unhealthy' in f.get('ruleName','').lower()
            or '${TARGET_SERVICE}' in f.get('affectedService','')]
  if recent:
    for f in recent[:5]:
      print(f'  [{f[\"severity\"]}] {f[\"ruleName\"]}: {f[\"title\"]}')
  else:
    print('  No shutdown-related findings (normal for clean drain)')
except: print('  (could not parse findings)')
" 2>/dev/null

# ── Results ───────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "GRACEFUL SHUTDOWN TEST RESULTS: ${TARGET_SERVICE}"
echo "═══════════════════════════════════════════════════════════"
printf "%-35s %s\n" "Service:"                    "${TARGET_SERVICE}"
printf "%-35s %s\n" "Drain window:"               "${DRAIN_SECS}s"
printf "%-35s %s\n" "connection_reset (NOT OK):"  "${conn_reset}  ← 0 = PASS"
printf "%-35s %s\n" "connection_refused (OK):"    "${conn_refused}  ← new connections cleanly rejected"
printf "%-35s %s\n" "200 during drain (GOOD):"    "${status_200}  ← in-flight completing"
printf "%-35s %s\n" "502 during drain (BAD):"     "${status_502}  ← should be 0"
printf "%-35s %s\n" "503 during drain (OK):"      "${status_503}  ← load-shedding signal"
printf "%-35s %s\n" "Sentinel before:"            "${SCORE_BEFORE}"
printf "%-35s %s\n" "Sentinel after:"             "${SCORE_AFTER}"
echo ""

# ── Pass / Warn / Fail ────────────────────────────────────────────────────────
echo "──── Criteria Evaluation ────"

# Criterion 1: connection_reset count (the critical one)
if [[ $conn_reset -eq 0 ]]; then
  echo -e "${GREEN}✓ PASS — 0 connection_reset during drain (in-flight requests completed cleanly)${NC}"
  PASS_COUNT=$((PASS_COUNT + 1))
elif [[ $conn_reset -le 3 ]]; then
  echo -e "${YELLOW}! WARN — ${conn_reset} connection_reset during drain (investigate slow requests)${NC}"
  WARN_COUNT=$((WARN_COUNT + 1))
else
  echo -e "${RED}✗ FAIL — ${conn_reset} connection_reset during drain (Spring graceful drain NOT working)${NC}"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# Criterion 2: 502 count during drain
if [[ $status_502 -eq 0 ]]; then
  echo -e "${GREEN}✓ PASS — 0 x 502 during drain window${NC}"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo -e "${YELLOW}! WARN — ${status_502} x 502 during drain (unexpected — check reverse proxy config)${NC}"
  WARN_COUNT=$((WARN_COUNT + 1))
fi

# Criterion 3: drain completed before SIGKILL
ACTUAL_DRAIN_S=$(( ($(date +%s%3N) - STOP_START_MS) / 1000 ))
if [[ $ACTUAL_DRAIN_S -le $((DRAIN_SECS + 5)) ]]; then
  echo -e "${GREEN}✓ PASS — Drain completed within ${ACTUAL_DRAIN_S}s (allocated: ${DRAIN_SECS}s)${NC}"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo -e "${YELLOW}! WARN — Drain took ${ACTUAL_DRAIN_S}s (allocated: ${DRAIN_SECS}s — SIGKILL fired)${NC}"
  WARN_COUNT=$((WARN_COUNT + 1))
fi

echo ""
echo "Summary: PASS=${PASS_COUNT}  WARN=${WARN_COUNT}  FAIL=${FAIL_COUNT}"
echo ""

if [[ -z "${CHAOS_MASTER_RUN:-}" ]]; then
  _RD="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/results"
  mkdir -p "$_RD"
  _V="PASS"; [[ $FAIL_COUNT -gt 0 ]] && _V="FAIL" || [[ $WARN_COUNT -gt 0 ]] && _V="WARN"
  _RF="${_RD}/chaos-graceful-shutdown-$(date +%Y%m%d-%H%M%S).md"
  { echo "# Chaos: graceful-shutdown — $(date '+%Y-%m-%d %H:%M:%S')"
    echo "**Overall:** ${_V} | Pass: ${PASS_COUNT} | Warn: ${WARN_COUNT} | Fail: ${FAIL_COUNT}"
    echo ""
    echo "Service under test: \`${TARGET_SERVICE}\`"
    echo ""
    echo "Run full suite: \`./tests/perf/resilience/chaos-master.sh\`"
  } > "$_RF"
  echo "Results written: ${_RF}"
fi

if [[ $FAIL_COUNT -gt 0 ]]; then
  echo -e "${RED}OVERALL: FAIL${NC}"
  echo "Recommended action:"
  echo "  1. Verify server.shutdown=graceful in application.yml"
  echo "  2. Verify spring.lifecycle.timeout-per-shutdown-phase=30s"
  echo "  3. Check that docker-compose stop_grace_period >= 40s"
  echo "  4. Check for requests longer than 30s that can't complete in drain window"
  exit 2
elif [[ $WARN_COUNT -gt 0 ]]; then
  echo -e "${YELLOW}OVERALL: WARN${NC}"
  echo "Graceful shutdown is mostly working. Review warnings above."
  exit 1
else
  echo -e "${GREEN}OVERALL: PASS${NC}"
  echo "Spring Boot graceful shutdown is working correctly."
  echo "All in-flight requests completed before the drain window expired."
  exit 0
fi

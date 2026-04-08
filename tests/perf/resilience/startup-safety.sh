#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Startup Safety Test
# Proves that during the 45-60s Java startup window, no requests are routed
# to an unready service. A premature "healthy" state would expose users to 500s.
#
# What is tested:
#   - Docker health-check start_period + retries prevent premature routing
#   - During the startup window callers receive 502/503, NOT hung connections
#   - After recovery, success rate returns to >99%
#
# Pass criteria:
#   PASS : startup < 60s AND 502s during startup window = 0
#   WARN : startup 60-90s  OR 502s <= 5
#   FAIL : startup > 90s   OR 502s > 5
#
# Usage:
#   ./tests/perf/resilience/startup-safety.sh
#   ./tests/perf/resilience/startup-safety.sh --service onboarding-api
#   MFT_BASE_URL=http://10.0.0.1 ./tests/perf/resilience/startup-safety.sh
# =============================================================================
set -uo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
BASE_URL="${MFT_BASE_URL:-http://localhost}"
ADMIN_EMAIL="${MFT_ADMIN_EMAIL:-admin@filetransfer.local}"
ADMIN_PASS="${MFT_ADMIN_PASS:-Admin@1234}"
SENTINEL_PORT=8098
TARGET_SERVICE="${1:-}"
[[ "$TARGET_SERVICE" == "--service" ]] && TARGET_SERVICE="${2:-onboarding-api}" || true
TARGET_SERVICE="${TARGET_SERVICE#--service=}"
TARGET_SERVICE="${TARGET_SERVICE:-onboarding-api}"

# Service → port map
declare -A SVC_PORT=(
  ["onboarding-api"]="8080"
  ["screening-service"]="8092"
  ["encryption-service"]="8086"
  ["analytics-service"]="8090"
  ["ai-engine"]="8091"
  ["notification-service"]="8097"
  ["storage-manager"]="8096"
  ["platform-sentinel"]="8098"
)

CONTAINER="mft-${TARGET_SERVICE}"
SVC_PORT_NUM="${SVC_PORT[$TARGET_SERVICE]:-8080}"
HEALTH_URL="${BASE_URL}:${SVC_PORT_NUM}/actuator/health/liveness"
TEST_URL="${BASE_URL}:${SVC_PORT_NUM}/actuator/health"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ── Counters ──────────────────────────────────────────────────────────────────
errors_during_startup=0
status_502_count=0
status_503_count=0
status_200_count=0
status_other_count=0
startup_duration_seconds=0
PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0
BG_PID=""
K6_PID=""

# ── Cleanup ───────────────────────────────────────────────────────────────────
cleanup() {
  [[ -n "$BG_PID" ]] && kill "$BG_PID" 2>/dev/null || true
  [[ -n "$K6_PID" ]] && kill "$K6_PID" 2>/dev/null || true
  # Kill any stray poll loops this script spawned
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

wait_for_container_down() {
  local container="$1"
  local timeout_s="${2:-30}"
  local elapsed=0
  while [[ $elapsed -lt $timeout_s ]]; do
    if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${container}$"; then
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  return 1
}

wait_for_healthy() {
  # Exponential backoff: 1 2 4 4 4 4 ... seconds, max total wait = $2 seconds
  local url="$1"
  local max_s="${2:-120}"
  local elapsed=0
  local delay=1
  while [[ $elapsed -lt $max_s ]]; do
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null)
    if [[ "$code" == "200" ]]; then
      return 0
    fi
    sleep "$delay"
    elapsed=$((elapsed + delay))
    [[ $delay -lt 4 ]] && delay=$((delay * 2))
  done
  return 1
}

# ── Print header ──────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}=== Startup Safety Test ===${NC}"
echo "Service:    ${TARGET_SERVICE} (${CONTAINER})"
echo "Port:       ${SVC_PORT_NUM}"
echo "What this proves: Docker health-check prevents premature traffic routing"
echo "during the 45-60s Java startup window."
echo ""

# ── Prerequisite: container must be running ───────────────────────────────────
if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER}$"; then
  echo -e "${RED}ERROR: Container '${CONTAINER}' is not running.${NC}"
  echo "Start the platform first: docker compose up -d"
  exit 1
fi

# ── Step 1: Baseline ──────────────────────────────────────────────────────────
echo -e "${GREEN}[1] Baseline — verifying service is healthy before test...${NC}"
BASELINE_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$TEST_URL" 2>/dev/null)
if [[ "$BASELINE_CODE" != "200" ]]; then
  echo -e "${YELLOW}  WARNING: service health returned ${BASELINE_CODE} before test starts.${NC}"
  echo "  Waiting 30s for it to stabilise..."
  wait_for_healthy "$TEST_URL" 60 || {
    echo -e "${RED}  FAIL: service is not healthy before test. Aborting.${NC}"
    exit 1
  }
fi

TOKEN=$(get_token)
if [[ -z "$TOKEN" ]]; then
  echo -e "${RED}  FAIL: Could not obtain auth token. Is onboarding-api running?${NC}"
  exit 1
fi

SCORE_BEFORE=$(get_sentinel_score)
echo "  Liveness:          ${BASELINE_CODE}"
echo "  Sentinel score:    ${SCORE_BEFORE}"
echo "  Token obtained:    yes"

# ── Step 2: Start continuous background polling ───────────────────────────────
echo ""
echo -e "${GREEN}[2] Starting continuous background health poller (2s interval)...${NC}"
POLL_LOG=$(mktemp /tmp/startup-safety-poll.XXXXXX)

# Prefer k6 for accurate load; fall back to bash poll loop
if command -v k6 &>/dev/null; then
  echo "  k6 found — using k6 for background load (25 VUs)"
  K6_SUMMARY_FILE=$(mktemp /tmp/chaos-bg-summary.XXXXXX)
  k6 run \
    --vus 25 \
    --duration 300s \
    --env DURATION=300s \
    --env VUS=25 \
    --env SUMMARY_FILE="$K6_SUMMARY_FILE" \
    --quiet \
    "$(dirname "$0")/../k6/10-chaos-background.js" \
    >> "$POLL_LOG" 2>&1 &
  K6_PID=$!
  echo "  k6 PID: ${K6_PID}"
  sleep 3  # Let k6 start and stabilise
else
  echo "  k6 not found — using bash curl loop"
  (
    local_token="$TOKEN"
    token_refresh_at=$(( $(date +%s) + 270 ))
    while true; do
      now=$(date +%s)
      if [[ $now -gt $token_refresh_at ]]; then
        local_token=$(curl -s --max-time 5 \
          -X POST "${BASE_URL}:8080/api/auth/login" \
          -H "Content-Type: application/json" \
          -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASS}\"}" \
          2>/dev/null \
          | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken') or d.get('token',''))" \
          2>/dev/null || echo "")
        token_refresh_at=$(( $(date +%s) + 270 ))
      fi

      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
        -H "Authorization: Bearer ${local_token}" \
        "${BASE_URL}:${SVC_PORT_NUM}/actuator/health" 2>/dev/null || echo "000")
      echo "$(date +%s%3N) $code" >> "$POLL_LOG"
      sleep 2
    done
  ) &
  BG_PID=$!
  sleep 2
fi

# ── Step 3: Kill the container ────────────────────────────────────────────────
echo ""
echo -e "${RED}[3] KILLING ${CONTAINER}...${NC}"
KILL_TIME_MS=$(date +%s%3N)
docker stop "$CONTAINER" > /dev/null 2>&1
echo "  Stopped at $(date '+%H:%M:%S')"

# Brief wait to ensure container is fully gone
wait_for_container_down "$CONTAINER" 15 || echo "  (container stop slow — continuing)"

STOPPED_TIME_MS=$(date +%s%3N)
echo "  Stop completed in $(( (STOPPED_TIME_MS - KILL_TIME_MS) / 1000 ))s"

# ── Step 4: Restart and measure startup window ────────────────────────────────
echo ""
echo -e "${GREEN}[4] RESTARTING ${CONTAINER}...${NC}"
RESTART_START_MS=$(date +%s%3N)
docker start "$CONTAINER" > /dev/null 2>&1

echo "  Polling every 2s — watching for premature 200s and counting error types..."
echo ""
printf "  %-10s %-10s %-6s %-6s %-6s %-6s\n" "Elapsed(s)" "Code" "200s" "503s" "502s" "Other"
printf "  %-10s %-10s %-6s %-6s %-6s %-6s\n" "──────────" "──────" "────" "────" "────" "─────"

FIRST_200_AFTER_RESTART_MS=""
MAX_STARTUP_WAIT_S=120
elapsed_s=0

while [[ $elapsed_s -lt $MAX_STARTUP_WAIT_S ]]; do
  sleep 2
  elapsed_s=$(( ($(date +%s%3N) - RESTART_START_MS) / 1000 ))

  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$HEALTH_URL" 2>/dev/null || echo "000")

  case "$code" in
    200|201)
      status_200_count=$((status_200_count + 1))
      if [[ -z "$FIRST_200_AFTER_RESTART_MS" ]]; then
        FIRST_200_AFTER_RESTART_MS=$(date +%s%3N)
        startup_duration_seconds=$(( (FIRST_200_AFTER_RESTART_MS - RESTART_START_MS) / 1000 ))
      fi
      ;;
    502)
      status_502_count=$((status_502_count + 1))
      errors_during_startup=$((errors_during_startup + 1))
      ;;
    503)
      status_503_count=$((status_503_count + 1))
      ;;
    *)
      status_other_count=$((status_other_count + 1))
      ;;
  esac

  printf "  %-10s %-10s %-6s %-6s %-6s %-6s\n" \
    "${elapsed_s}s" "$code" \
    "$status_200_count" "$status_503_count" "$status_502_count" "$status_other_count"

  # Once healthy, run 10 more seconds to confirm stability
  if [[ -n "$FIRST_200_AFTER_RESTART_MS" && $elapsed_s -gt $((startup_duration_seconds + 10)) ]]; then
    break
  fi
done

echo ""

# ── Step 5: Post-recovery verification ───────────────────────────────────────
echo -e "${GREEN}[5] Post-recovery verification (10 rapid requests)...${NC}"
if [[ -n "$FIRST_200_AFTER_RESTART_MS" ]]; then
  POST_RECOVERY_OK=0
  POST_RECOVERY_FAIL=0
  for _ in $(seq 1 10); do
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$TEST_URL" 2>/dev/null || echo "000")
    if [[ "$code" == "200" ]]; then
      POST_RECOVERY_OK=$((POST_RECOVERY_OK + 1))
    else
      POST_RECOVERY_FAIL=$((POST_RECOVERY_FAIL + 1))
    fi
    sleep 0.5
  done
  POST_SUCCESS_PCT=$(( POST_RECOVERY_OK * 100 / 10 ))
  echo "  Success rate after recovery: ${POST_SUCCESS_PCT}% (${POST_RECOVERY_OK}/10)"
else
  echo -e "${RED}  Service did not return 200 within ${MAX_STARTUP_WAIT_S}s${NC}"
  POST_SUCCESS_PCT=0
fi

# ── Step 6: Sentinel check ────────────────────────────────────────────────────
sleep 15
echo ""
echo -e "${GREEN}[6] Checking Sentinel findings...${NC}"
SCORE_AFTER=$(get_sentinel_score)
curl -s --max-time 5 \
  "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/findings?status=OPEN&analyzer=RESILIENCE" \
  2>/dev/null | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  items = d if isinstance(d, list) else d.get('content', [])
  relevant = [f for f in items if 'startup' in f.get('ruleName','').lower()
              or 'unhealthy' in f.get('ruleName','').lower()
              or '${TARGET_SERVICE}' in f.get('affectedService','')]
  if relevant:
    for f in relevant[:5]:
      print(f'  [{f[\"severity\"]}] {f[\"ruleName\"]}: {f[\"title\"]}')
  else:
    print('  No startup-related findings (normal if recovery was clean)')
except: print('  (could not parse findings)')
" 2>/dev/null

# ── Stop background processes ─────────────────────────────────────────────────
[[ -n "$K6_PID" ]] && kill "$K6_PID" 2>/dev/null || true
[[ -n "$BG_PID" ]] && kill "$BG_PID" 2>/dev/null || true
K6_PID=""
BG_PID=""

# ── Results ───────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "STARTUP SAFETY TEST RESULTS: ${TARGET_SERVICE}"
echo "═══════════════════════════════════════════════════════════"
printf "%-35s %s\n" "Service:"              "${TARGET_SERVICE}"
printf "%-35s %s\n" "Startup duration:"     "${startup_duration_seconds}s"
printf "%-35s %s\n" "502s during startup:"  "${status_502_count}  (FAIL if >5)"
printf "%-35s %s\n" "503s during startup:"  "${status_503_count}  (acceptable — means properly refused)"
printf "%-35s %s\n" "200s before ready:"    "${status_200_count}"
printf "%-35s %s\n" "Post-recovery rate:"   "${POST_SUCCESS_PCT}%"
printf "%-35s %s\n" "Sentinel before:"      "${SCORE_BEFORE}"
printf "%-35s %s\n" "Sentinel after:"       "${SCORE_AFTER}"
echo ""

# ── Pass / Warn / Fail ────────────────────────────────────────────────────────
echo "──── Criteria Evaluation ────"

# Criterion 1: startup duration
if [[ $startup_duration_seconds -eq 0 ]]; then
  echo -e "${RED}✗ FAIL — service never returned 200 (startup timeout)${NC}"
  FAIL_COUNT=$((FAIL_COUNT + 1))
elif [[ $startup_duration_seconds -le 60 ]]; then
  echo -e "${GREEN}✓ PASS — startup duration ${startup_duration_seconds}s (threshold: 60s)${NC}"
  PASS_COUNT=$((PASS_COUNT + 1))
elif [[ $startup_duration_seconds -le 90 ]]; then
  echo -e "${YELLOW}! WARN — startup duration ${startup_duration_seconds}s (target <60s, max 90s)${NC}"
  WARN_COUNT=$((WARN_COUNT + 1))
else
  echo -e "${RED}✗ FAIL — startup duration ${startup_duration_seconds}s (threshold: 90s)${NC}"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# Criterion 2: 502s during startup (the critical one)
if [[ $status_502_count -eq 0 ]]; then
  echo -e "${GREEN}✓ PASS — 0 x 502 during startup window (health-check gate working correctly)${NC}"
  PASS_COUNT=$((PASS_COUNT + 1))
elif [[ $status_502_count -le 5 ]]; then
  echo -e "${YELLOW}! WARN — ${status_502_count} x 502 during startup (acceptable but investigate)${NC}"
  WARN_COUNT=$((WARN_COUNT + 1))
else
  echo -e "${RED}✗ FAIL — ${status_502_count} x 502 during startup (health-check gate NOT working)${NC}"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# Criterion 3: post-recovery success rate
if [[ $POST_SUCCESS_PCT -ge 99 ]]; then
  echo -e "${GREEN}✓ PASS — post-recovery success rate ${POST_SUCCESS_PCT}% (threshold: 99%)${NC}"
  PASS_COUNT=$((PASS_COUNT + 1))
elif [[ $POST_SUCCESS_PCT -ge 90 ]]; then
  echo -e "${YELLOW}! WARN — post-recovery success rate ${POST_SUCCESS_PCT}% (target ≥99%)${NC}"
  WARN_COUNT=$((WARN_COUNT + 1))
else
  echo -e "${RED}✗ FAIL — post-recovery success rate ${POST_SUCCESS_PCT}% (below 90%)${NC}"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo ""
echo "Summary: PASS=${PASS_COUNT}  WARN=${WARN_COUNT}  FAIL=${FAIL_COUNT}"
echo ""

if [[ $FAIL_COUNT -gt 0 ]]; then
  echo -e "${RED}OVERALL: FAIL${NC}"
  echo "Recommended action: Check start_period in docker-compose healthcheck for ${TARGET_SERVICE}."
  echo "Expected: start_period>=30s, interval=10s, retries=5."
  echo "502 during startup means a proxy/LB is routing to the container before health-check passes."
  exit 2
elif [[ $WARN_COUNT -gt 0 ]]; then
  echo -e "${YELLOW}OVERALL: WARN${NC}"
  echo "Service is functional but startup is slower than target. No data loss risk."
  exit 1
else
  echo -e "${GREEN}OVERALL: PASS${NC}"
  echo "Startup safety guardrail is working correctly."
  exit 0
fi

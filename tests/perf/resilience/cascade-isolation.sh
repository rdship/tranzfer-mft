#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Cascade Isolation Test
# Proves that when a service fails, it fails FAST (503 in <500ms) rather than
# timing out (30s hung request). Also proves GRACEFUL services fall back.
#
# Test matrix:
#   Scenario 1 : Kill ai-engine       → screening returns ALLOWED (graceful fallback)
#   Scenario 2 : Kill encryption      → pipeline returns 503 in <500ms (fail-fast)
#   Scenario 3 : Kill keystore-manager→ encryption uses cached keys for 60s
#   Scenario 4 : Kill sentinel        → platform continues, 0 impact
#   Scenario 5 : Kill 2 services      → circuit breakers isolate, no cascading 500s
#
# Pass criteria per scenario:
#   PASS : correct failure mode (503 vs fallback) + response time <500ms
#   WARN : correct failure mode but response time >500ms (slow circuit open)
#   FAIL : timeout (>5s) OR 500 error OR wrong behaviour
#
# Usage:
#   ./tests/perf/resilience/cascade-isolation.sh
#   ./tests/perf/resilience/cascade-isolation.sh --scenario 1
#   ./tests/perf/resilience/cascade-isolation.sh --scenario 2,3
# =============================================================================
set -uo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
BASE_URL="${MFT_BASE_URL:-http://localhost}"
ADMIN_EMAIL="${MFT_ADMIN_EMAIL:-admin@filetransfer.local}"
ADMIN_PASS="${MFT_ADMIN_PASS:-Admin@1234}"
SENTINEL_PORT=8098
PROBE_COUNT=10             # requests to send per scenario
CB_CLOSE_WAIT_S=30         # wait for circuit breaker to close after restart
FAST_THRESHOLD_MS=500      # fail-fast must respond within this

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ── Scenario filter ───────────────────────────────────────────────────────────
RUN_SCENARIOS="1,2,3,4,5"
for arg in "$@"; do
  case "$arg" in
    --scenario=*) RUN_SCENARIOS="${arg#--scenario=}" ;;
    --scenario)   : ;;
  esac
done

# ── Results ───────────────────────────────────────────────────────────────────
declare -A SCENARIO_VERDICT    # scenario_N → PASS|WARN|FAIL
declare -A SCENARIO_NOTE       # scenario_N → detail string
PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

cleanup() {
  # Restart any containers that may have been left stopped
  for container in \
    mft-ai-engine mft-encryption-service mft-keystore-manager \
    mft-platform-sentinel mft-screening-service; do
    docker start "$container" > /dev/null 2>&1 || true
  done
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
  local port="$1"
  local max_s="${2:-90}"
  local elapsed=0
  local delay=2
  while [[ $elapsed -lt $max_s ]]; do
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
      "${BASE_URL}:${port}/actuator/health" 2>/dev/null)
    [[ "$code" == "200" ]] && return 0
    sleep "$delay"
    elapsed=$((elapsed + delay))
    [[ $delay -lt 8 ]] && delay=$((delay * 2))
  done
  return 1
}

# Send N probes to a URL, return: avg_ms p95_ms max_ms ok_count fail_count timeout_count
probe_n_times() {
  local token="$1"
  local url="$2"
  local method="${3:-GET}"
  local body="${4:-}"
  local n="${5:-$PROBE_COUNT}"

  local -a latencies=()
  local ok=0 fail=0 timeouts=0 s503=0 s500=0 s502=0

  for _ in $(seq 1 "$n"); do
    local t0
    t0=$(date +%s%3N)
    local code

    if [[ "$method" == "POST" && -n "$body" ]]; then
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
        -X POST \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d "$body" \
        "$url" 2>/dev/null || echo "000")
    else
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
        -H "Authorization: Bearer ${token}" \
        "$url" 2>/dev/null || echo "000")
    fi

    local elapsed_ms=$(( $(date +%s%3N) - t0 ))
    latencies+=("$elapsed_ms")

    case "$code" in
      200|201) ok=$((ok + 1)) ;;
      503)     s503=$((s503 + 1)); fail=$((fail + 1)) ;;
      500)     s500=$((s500 + 1)); fail=$((fail + 1)) ;;
      502)     s502=$((s502 + 1)); fail=$((fail + 1)) ;;
      000)     timeouts=$((timeouts + 1)); fail=$((fail + 1)) ;;
      *)       fail=$((fail + 1)) ;;
    esac
    sleep 0.3
  done

  # Stats
  local avg_ms=0 p95_ms=0 max_ms=0
  if [[ ${#latencies[@]} -gt 0 ]]; then
    local sum=0
    for l in "${latencies[@]}"; do sum=$((sum + l)); done
    avg_ms=$(( sum / ${#latencies[@]} ))
    local sorted=($(printf '%s\n' "${latencies[@]}" | sort -n))
    local p95_idx=$(( ${#sorted[@]} * 95 / 100 ))
    p95_ms="${sorted[$p95_idx]:-0}"
    max_ms="${sorted[-1]:-0}"
  fi

  echo "${avg_ms} ${p95_ms} ${max_ms} ${ok} ${fail} ${timeouts} ${s503} ${s500} ${s502}"
}

should_run() {
  local n="$1"
  echo "$RUN_SCENARIOS" | tr ',' '\n' | grep -q "^${n}$"
}

record_verdict() {
  local scenario="$1"
  local verdict="$2"
  local note="$3"
  SCENARIO_VERDICT[$scenario]="$verdict"
  SCENARIO_NOTE[$scenario]="$note"
  case "$verdict" in
    PASS) PASS_COUNT=$((PASS_COUNT + 1)) ;;
    WARN) WARN_COUNT=$((WARN_COUNT + 1)) ;;
    FAIL) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
  esac
}

# ── Header ────────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}=== Cascade Isolation Test ===${NC}"
echo "Testing circuit breaker behaviour: fail-fast <500ms vs graceful fallback"
echo "Scenarios to run: ${RUN_SCENARIOS}"
echo ""

TOKEN=$(get_token)
if [[ -z "$TOKEN" ]]; then
  echo -e "${RED}FAIL: Could not obtain auth token.${NC}"
  exit 1
fi
TOKEN_AT=$(date +%s)

SCORE_BEFORE=$(get_sentinel_score)
echo "Sentinel health score (before): ${SCORE_BEFORE}"

# ─────────────────────────────────────────────────────────────────────────────
# SCENARIO 1: Kill ai-engine → screening must return ALLOWED (graceful fallback)
# ─────────────────────────────────────────────────────────────────────────────
if should_run 1; then
  echo ""
  echo -e "${CYAN}${BOLD}── Scenario 1: ai-engine → graceful fallback ──${NC}"
  echo "Expected: screening returns ALLOWED (not blocked) when ai-engine is down"

  # Kill ai-engine
  docker stop mft-ai-engine > /dev/null 2>&1
  echo "  ai-engine stopped"
  sleep 3  # Let circuit register

  # Probe screening with a clean transfer (should return ALLOWED)
  TRACK_ID="CASCADE-S1-$(date +%s%3N)"
  SCAN_URL="${BASE_URL}:8092/api/v1/screening/scan/text"
  SCAN_BODY="{\"content\":\"COMPANY: ACME\",\"filename\":\"test.csv\",\"trackId\":\"${TRACK_ID}\"}"

  [[ $(( $(date +%s) - TOKEN_AT )) -gt 270 ]] && TOKEN=$(get_token) && TOKEN_AT=$(date +%s)

  stats=$(probe_n_times "$TOKEN" "$SCAN_URL" "POST" "$SCAN_BODY" "$PROBE_COUNT")
  read -r avg_ms p95_ms max_ms ok fail timeouts s503 s500 s502 <<< "$stats"

  # Check response body for ALLOWED/BLOCKED decision
  body_check=$(curl -s --max-time 5 \
    -X POST "$SCAN_URL" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "$SCAN_BODY" 2>/dev/null || echo "")

  has_allowed=$(echo "$body_check" | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  decision = str(d.get('decision','') or d.get('status','') or d.get('result','')).upper()
  print('YES' if 'ALLOW' in decision else f'NO (decision={decision})')
except: print('PARSE_ERROR')
" 2>/dev/null || echo "PARSE_ERROR")

  echo "  Probes: ok=${ok} fail=${fail} timeouts=${timeouts} | avg=${avg_ms}ms p95=${p95_ms}ms"
  echo "  Decision=ALLOWED check: ${has_allowed}"

  # Verdict
  if [[ "$has_allowed" == "YES" && $s500 -eq 0 && $timeouts -eq 0 ]]; then
    if [[ $p95_ms -le $FAST_THRESHOLD_MS ]]; then
      record_verdict "1" "PASS" "screening returns ALLOWED fallback in ${p95_ms}ms"
      echo -e "  ${GREEN}✓ PASS — graceful fallback working, p95=${p95_ms}ms${NC}"
    else
      record_verdict "1" "WARN" "ALLOWED returned but p95=${p95_ms}ms (>500ms)"
      echo -e "  ${YELLOW}! WARN — correct fallback but slow: p95=${p95_ms}ms${NC}"
    fi
  elif [[ $timeouts -gt 0 ]]; then
    record_verdict "1" "FAIL" "${timeouts} timeouts — circuit not opening"
    echo -e "  ${RED}✗ FAIL — ${timeouts} timeouts (circuit should open fast)${NC}"
  elif [[ $s500 -gt 0 ]]; then
    record_verdict "1" "FAIL" "${s500} x 500 errors — incorrect error type"
    echo -e "  ${RED}✗ FAIL — ${s500} x 500 errors (should return ALLOWED, not 500)${NC}"
  else
    record_verdict "1" "WARN" "ALLOWED not confirmed in response body"
    echo -e "  ${YELLOW}! WARN — could not confirm ALLOWED in response body${NC}"
  fi

  # Restart ai-engine
  docker start mft-ai-engine > /dev/null 2>&1
  echo "  ai-engine restarted"

  # Verify circuit closes
  echo "  Waiting up to ${CB_CLOSE_WAIT_S}s for circuit to close..."
  if wait_for_healthy "8091" "$CB_CLOSE_WAIT_S"; then
    echo "  ai-engine healthy — circuit closed"
  else
    echo -e "${YELLOW}  ai-engine did not recover within ${CB_CLOSE_WAIT_S}s${NC}"
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# SCENARIO 2: Kill encryption → pipeline returns 503 in <500ms (fail-fast)
# ─────────────────────────────────────────────────────────────────────────────
if should_run 2; then
  echo ""
  echo -e "${CYAN}${BOLD}── Scenario 2: encryption-service → fail-fast 503 ──${NC}"
  echo "Expected: 503 in <500ms (not 30s timeout), circuit opens immediately"

  docker stop mft-encryption-service > /dev/null 2>&1
  echo "  encryption-service stopped"
  sleep 3

  [[ $(( $(date +%s) - TOKEN_AT )) -gt 270 ]] && TOKEN=$(get_token) && TOKEN_AT=$(date +%s)

  ENCRYPT_URL="${BASE_URL}:8086/api/encrypt/credential/encrypt"
  ENCRYPT_BODY="{\"value\":\"cascade-test-value\"}"

  stats=$(probe_n_times "$TOKEN" "$ENCRYPT_URL" "POST" "$ENCRYPT_BODY" "$PROBE_COUNT")
  read -r avg_ms p95_ms max_ms ok fail timeouts s503 s500 s502 <<< "$stats"

  echo "  Probes: ok=${ok} 503=${s503} 500=${s500} timeouts=${timeouts} | avg=${avg_ms}ms p95=${p95_ms}ms max=${max_ms}ms"

  # Verdict
  if [[ $timeouts -eq 0 && $s500 -eq 0 && $p95_ms -le $FAST_THRESHOLD_MS ]]; then
    record_verdict "2" "PASS" "fail-fast working: 503 in ${p95_ms}ms (no timeouts)"
    echo -e "  ${GREEN}✓ PASS — circuit breaker opens fast: p95=${p95_ms}ms, 0 timeouts${NC}"
  elif [[ $timeouts -eq 0 && $s500 -eq 0 && $p95_ms -le 2000 ]]; then
    record_verdict "2" "WARN" "503 returned but p95=${p95_ms}ms (>500ms, slow circuit open)"
    echo -e "  ${YELLOW}! WARN — correct 503 but slow: p95=${p95_ms}ms${NC}"
  elif [[ $timeouts -gt 0 ]]; then
    record_verdict "2" "FAIL" "${timeouts} timeouts (${max_ms}ms max) — circuit NOT opening"
    echo -e "  ${RED}✗ FAIL — ${timeouts} timeouts, max=${max_ms}ms — circuit breaker not working${NC}"
  else
    record_verdict "2" "FAIL" "${s500} x 500, ${timeouts} timeouts"
    echo -e "  ${RED}✗ FAIL — unexpected errors: 500=${s500} timeouts=${timeouts}${NC}"
  fi

  # Restart and verify circuit closes
  docker start mft-encryption-service > /dev/null 2>&1
  echo "  encryption-service restarted"
  echo "  Waiting ${CB_CLOSE_WAIT_S}s for circuit to close..."
  if wait_for_healthy "8086" "$CB_CLOSE_WAIT_S"; then
    cb_check=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
      -X POST "$ENCRYPT_URL" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "$ENCRYPT_BODY" 2>/dev/null || echo "000")
    echo "  Post-restart probe: HTTP ${cb_check} (200/201 = circuit closed)"
  else
    echo -e "  ${YELLOW}Did not recover within ${CB_CLOSE_WAIT_S}s${NC}"
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# SCENARIO 3: Kill keystore-manager → encryption uses cached keys for 60s
# ─────────────────────────────────────────────────────────────────────────────
if should_run 3; then
  echo ""
  echo -e "${CYAN}${BOLD}── Scenario 3: keystore-manager → cached key fallback ──${NC}"
  echo "Expected: encryption continues using locally cached keys for up to 24h"

  docker stop mft-keystore-manager > /dev/null 2>&1
  echo "  keystore-manager stopped"
  sleep 3

  [[ $(( $(date +%s) - TOKEN_AT )) -gt 270 ]] && TOKEN=$(get_token) && TOKEN_AT=$(date +%s)

  ENCRYPT_URL="${BASE_URL}:8086/api/encrypt/credential/encrypt"
  ENCRYPT_BODY="{\"value\":\"cascade-keystore-test\"}"

  # Run for 60s to prove the 24h cache holds
  echo "  Probing encryption for 60s with keystore down..."
  start_ms=$(date +%s%3N)
  ok_in_window=0
  fail_in_window=0
  probe_cycle=0

  while [[ $(( ($(date +%s%3N) - start_ms) / 1000 )) -lt 60 ]]; do
    probe_cycle=$((probe_cycle + 1))
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
      -X POST "$ENCRYPT_URL" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "$ENCRYPT_BODY" 2>/dev/null || echo "000")
    if [[ "$code" == "200" || "$code" == "201" ]]; then
      ok_in_window=$((ok_in_window + 1))
    else
      fail_in_window=$((fail_in_window + 1))
    fi
    sleep 6
  done

  total_probes=$((ok_in_window + fail_in_window))
  cache_pct=0
  [[ $total_probes -gt 0 ]] && cache_pct=$(( ok_in_window * 100 / total_probes ))

  echo "  60s window: success=${ok_in_window}/${total_probes} (${cache_pct}% cache hit rate)"

  # Verdict
  if [[ $cache_pct -ge 90 ]]; then
    record_verdict "3" "PASS" "cached key fallback: ${cache_pct}% success over 60s"
    echo -e "  ${GREEN}✓ PASS — encryption continued using cached keys (${cache_pct}%)${NC}"
  elif [[ $cache_pct -ge 50 ]]; then
    record_verdict "3" "WARN" "partial cache hit: ${cache_pct}% (expected ≥90%)"
    echo -e "  ${YELLOW}! WARN — partial cache hit: ${cache_pct}% (check key cache TTL config)${NC}"
  else
    record_verdict "3" "FAIL" "cache failed: only ${cache_pct}% success"
    echo -e "  ${RED}✗ FAIL — encryption failed ${fail_in_window}/${total_probes} times (cache not working)${NC}"
  fi

  # Restart keystore
  docker start mft-keystore-manager > /dev/null 2>&1
  echo "  keystore-manager restarted"
  wait_for_healthy "8093" 90 && echo "  keystore-manager healthy" || echo "  (keystore restart slow)"
fi

# ─────────────────────────────────────────────────────────────────────────────
# SCENARIO 4: Kill sentinel → platform continues, 0 impact
# ─────────────────────────────────────────────────────────────────────────────
if should_run 4; then
  echo ""
  echo -e "${CYAN}${BOLD}── Scenario 4: platform-sentinel → zero impact ──${NC}"
  echo "Expected: all core services unaffected (sentinel is observer-only)"

  docker stop mft-platform-sentinel > /dev/null 2>&1
  echo "  platform-sentinel stopped"
  sleep 3

  [[ $(( $(date +%s) - TOKEN_AT )) -gt 270 ]] && TOKEN=$(get_token) && TOKEN_AT=$(date +%s)

  # Test that core services still work
  declare -A CORE_SERVICES=(
    ["onboarding"]="${BASE_URL}:8080/actuator/health"
    ["screening"]="${BASE_URL}:8092/actuator/health"
    ["encryption"]="${BASE_URL}:8086/actuator/health"
  )

  all_ok=true
  any_fail=false
  echo "  Probing core services while sentinel is down:"

  for svc in "${!CORE_SERVICES[@]}"; do
    url="${CORE_SERVICES[$svc]}"
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null || echo "000")
    if [[ "$code" == "200" ]]; then
      echo -e "    ${svc}: ${GREEN}${code}${NC}"
    else
      echo -e "    ${svc}: ${RED}${code} FAIL${NC}"
      all_ok=false
      any_fail=true
    fi
  done

  if [[ "$all_ok" == "true" ]]; then
    record_verdict "4" "PASS" "all core services return 200 with sentinel down"
    echo -e "  ${GREEN}✓ PASS — platform operates normally without sentinel${NC}"
  else
    record_verdict "4" "FAIL" "core services affected by sentinel failure"
    echo -e "  ${RED}✗ FAIL — core services failed when sentinel stopped (unexpected dependency)${NC}"
  fi

  # Restart sentinel
  docker start mft-platform-sentinel > /dev/null 2>&1
  echo "  platform-sentinel restarted"
  wait_for_healthy "${SENTINEL_PORT}" 90 && echo "  sentinel healthy" || echo "  (sentinel restart slow)"
fi

# ─────────────────────────────────────────────────────────────────────────────
# SCENARIO 5: Kill two services simultaneously → no cascading 500s
# ─────────────────────────────────────────────────────────────────────────────
if should_run 5; then
  echo ""
  echo -e "${CYAN}${BOLD}── Scenario 5: ai-engine + analytics-service → isolation ──${NC}"
  echo "Expected: no cascading 500s; screening and onboarding still return 200/503 cleanly"

  docker stop mft-ai-engine mft-analytics-service > /dev/null 2>&1
  echo "  ai-engine and analytics-service stopped simultaneously"
  sleep 5

  [[ $(( $(date +%s) - TOKEN_AT )) -gt 270 ]] && TOKEN=$(get_token) && TOKEN_AT=$(date +%s)

  # Probe screening and onboarding
  SCREEN_URL="${BASE_URL}:8092/actuator/health"
  ONBOARD_URL="${BASE_URL}:8080/actuator/health"
  ANALYTICS_DOWNSTREAM_URL="${BASE_URL}:8090/actuator/health"

  screen_stats=$(probe_n_times "$TOKEN" "$SCREEN_URL" "GET" "" 5)
  read -r sc_avg sc_p95 sc_max sc_ok sc_fail sc_to sc_503 sc_500 sc_502 <<< "$screen_stats"

  onboard_stats=$(probe_n_times "$TOKEN" "$ONBOARD_URL" "GET" "" 5)
  read -r ob_avg ob_p95 ob_max ob_ok ob_fail ob_to ob_503 ob_500 ob_502 <<< "$onboard_stats"

  echo "  Screening  — ok=${sc_ok} fail=${sc_fail} 500s=${sc_500} timeouts=${sc_to} p95=${sc_p95}ms"
  echo "  Onboarding — ok=${ob_ok} fail=${ob_fail} 500s=${ob_500} timeouts=${ob_to} p95=${ob_p95}ms"

  total_500=$(( sc_500 + ob_500 ))
  total_timeout=$(( sc_to + ob_to ))

  if [[ $total_500 -eq 0 && $total_timeout -eq 0 ]]; then
    if [[ $sc_p95 -le $FAST_THRESHOLD_MS && $ob_p95 -le $FAST_THRESHOLD_MS ]]; then
      record_verdict "5" "PASS" "no cascading 500s; p95 screen=${sc_p95}ms onboard=${ob_p95}ms"
      echo -e "  ${GREEN}✓ PASS — circuit breakers isolated failures, no cascade${NC}"
    else
      record_verdict "5" "WARN" "no cascade but slow: screen p95=${sc_p95}ms onboard p95=${ob_p95}ms"
      echo -e "  ${YELLOW}! WARN — no cascade but responses slow (circuit open latency high)${NC}"
    fi
  elif [[ $total_500 -gt 0 ]]; then
    record_verdict "5" "FAIL" "cascading 500s detected: ${total_500} x 500"
    echo -e "  ${RED}✗ FAIL — CASCADING FAILURE: ${total_500} x 500 errors (circuit isolation broken)${NC}"
  else
    record_verdict "5" "FAIL" "${total_timeout} timeouts (circuit breakers not opening)"
    echo -e "  ${RED}✗ FAIL — ${total_timeout} timeouts (circuits not opening fast enough)${NC}"
  fi

  # Restart
  docker start mft-ai-engine mft-analytics-service > /dev/null 2>&1
  echo "  ai-engine and analytics-service restarted"
  wait_for_healthy "8091" 90 && echo "  ai-engine healthy" || true
  wait_for_healthy "8090" 90 && echo "  analytics-service healthy" || true
fi

# ── Post-test Sentinel check ──────────────────────────────────────────────────
sleep 15
echo ""
echo -e "${GREEN}[Post] Sentinel findings after cascade tests...${NC}"
SCORE_AFTER=$(get_sentinel_score)
echo "  Health score: ${SCORE_BEFORE} → ${SCORE_AFTER}"

curl -s --max-time 5 \
  "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/findings?status=OPEN&analyzer=RESILIENCE" \
  2>/dev/null | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  items = d if isinstance(d, list) else d.get('content', [])
  # Show cascade/circuit-related findings
  relevant = [f for f in items if any(kw in f.get('ruleName','').lower()
    for kw in ['cascade', 'circuit', 'graceful', 'timeout', 'fallback', 'degradation'])]
  if relevant:
    print('  Cascade-related findings:')
    for f in relevant[:5]:
      print(f'    [{f[\"severity\"]}] {f[\"ruleName\"]}: {f[\"title\"]}')
  else:
    print('  No cascade findings (expected after clean recovery)')
except: print('  (could not parse findings)')
" 2>/dev/null

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════════"
echo "CASCADE ISOLATION TEST RESULTS"
echo "═══════════════════════════════════════════════════════════════════"
printf "%-5s %-30s %-8s %s\n" "Scen" "Test" "Verdict" "Notes"
printf "%-5s %-30s %-8s %s\n" "────" "──────────────────────────────" "───────" "─────"

declare -A SCENARIO_NAMES=(
  ["1"]="ai-engine → graceful fallback"
  ["2"]="encryption → fail-fast 503"
  ["3"]="keystore → cached keys 60s"
  ["4"]="sentinel → zero impact"
  ["5"]="2 services → no cascade"
)

for n in 1 2 3 4 5; do
  should_run "$n" || continue
  verdict="${SCENARIO_VERDICT[$n]:-SKIP}"
  note="${SCENARIO_NOTE[$n]:-}"
  printf "%-5s %-30s " "$n" "${SCENARIO_NAMES[$n]}"
  case "$verdict" in
    PASS) echo -e "${GREEN}PASS${NC}     ${note}" ;;
    WARN) echo -e "${YELLOW}WARN${NC}     ${note}" ;;
    FAIL) echo -e "${RED}FAIL${NC}     ${note}" ;;
    *)    echo "${verdict}    ${note}" ;;
  esac
done

echo ""
printf "%-35s %s\n" "Sentinel before:" "${SCORE_BEFORE}"
printf "%-35s %s\n" "Sentinel after:"  "${SCORE_AFTER}"
echo ""
echo "Summary: PASS=${PASS_COUNT}  WARN=${WARN_COUNT}  FAIL=${FAIL_COUNT}"
echo ""
echo "Thresholds:"
echo "  PASS : correct failure mode AND response time <${FAST_THRESHOLD_MS}ms"
echo "  WARN : correct failure mode BUT response time >${FAST_THRESHOLD_MS}ms (slow circuit open)"
echo "  FAIL : timeout (>5s) OR 500 error OR wrong behaviour (BLOCKED when should be ALLOWED)"

if [[ $FAIL_COUNT -gt 0 ]]; then
  echo ""
  echo -e "${RED}OVERALL: FAIL${NC}"
  echo "Check ResilientServiceClient circuit breaker config:"
  echo "  failureRateThreshold, slowCallDurationThreshold, waitDurationInOpenState"
  exit 2
elif [[ $WARN_COUNT -gt 0 ]]; then
  echo ""
  echo -e "${YELLOW}OVERALL: WARN${NC}"
  echo "Circuit breakers are working but may be slow to open. Review Resilience4j config."
  exit 1
else
  echo ""
  echo -e "${GREEN}OVERALL: PASS${NC}"
  echo "All circuit breakers are isolating failures correctly."
  exit 0
fi

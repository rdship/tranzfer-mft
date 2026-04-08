#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Scale Surge Test
# Simulates HPA-style scale-up under peak load and scale-down during off-peak.
# In Docker Compose this tests manual replica management used for DR/maintenance.
#
# For each service:
#   1. Baseline (30s steady load) — measure p95 / error rate
#   2. Scale-up  — start replica-2 while load continues (startup guardrail)
#   3. Peak load (60s at 2x VUs) — both containers should be reachable
#   4. Scale-down — stop replica-2 gracefully while load continues
#   5. Recovery  — original container handles load alone
#
# Key metric: % of requests that error during scale events (target: <2%)
#
# Pass criteria:
#   PASS : error rate during scale events < 2%
#   WARN : error rate 2-5%
#   FAIL : error rate > 5% OR any 502s
#
# Usage:
#   ./tests/perf/resilience/scale-surge.sh
#   ./tests/perf/resilience/scale-surge.sh --service onboarding-api
#   ./tests/perf/resilience/scale-surge.sh --quick
# =============================================================================
set -uo pipefail

# ── Bash 4+ required (associative arrays) ─────────────────────────────────────
if [[ "${BASH_VERSINFO[0]}" -lt 4 ]]; then
  for _b in /opt/homebrew/bin/bash /usr/local/bin/bash; do
    [[ -x "$_b" ]] && exec "$_b" "$0" "$@"
  done
  echo "ERROR: bash 4+ required (have ${BASH_VERSION}). Install: brew install bash" >&2; exit 1
fi

# Portable millisecond timestamp — macOS BSD date does not support %N
ms() {
  if command -v gdate &>/dev/null; then gdate +%s%3N
  else python3 -c "import time; print(int(time.time()*1000))"; fi
}

# ── Config ────────────────────────────────────────────────────────────────────
BASE_URL="${MFT_BASE_URL:-http://localhost}"
ADMIN_EMAIL="${MFT_ADMIN_EMAIL:-admin@filetransfer.local}"
ADMIN_PASS="${MFT_ADMIN_PASS:-Admin@1234}"
SENTINEL_PORT=8098
BASELINE_VUS="${BASELINE_VUS:-15}"
PEAK_VUS="${PEAK_VUS:-30}"
BASELINE_SECS=30
PEAK_SECS=60
SCALEDOWN_DRAIN="${SCALEDOWN_DRAIN:-40}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ── Services ──────────────────────────────────────────────────────────────────
ALL_SERVICES=(
  "onboarding-api:8080"
  "screening-service:8092"
  "encryption-service:8086"
)

SPECIFIC_SERVICE=""
QUICK_MODE=false

for arg in "$@"; do
  case "$arg" in
    --quick)           QUICK_MODE=true ;;
    --service=*)       SPECIFIC_SERVICE="${arg#--service=}" ;;
    --service)         : ;;  # next arg handled below
  esac
done
# Handle "--service foo" (two-arg form)
for i in "$@"; do
  [[ "$i" == "--service" ]] && SPECIFIC_SERVICE="${!OPTIND:-}" || true
done

SERVICES_TO_TEST=("${ALL_SERVICES[@]}")
if [[ -n "$SPECIFIC_SERVICE" ]]; then
  for spec in "${ALL_SERVICES[@]}"; do
    svc="${spec%%:*}"
    [[ "$svc" == "$SPECIFIC_SERVICE" ]] && SERVICES_TO_TEST=("$spec") && break
  done
fi
[[ "$QUICK_MODE" == "true" ]] && SERVICES_TO_TEST=("onboarding-api:8080")

# ── Global results ────────────────────────────────────────────────────────────
declare -A RESULT_BASELINE_ERR
declare -A RESULT_SCALEUP_ERR
declare -A RESULT_PEAK_ERR
declare -A RESULT_SCALEDOWN_ERR
declare -A RESULT_BASELINE_P95
declare -A RESULT_VERDICT

GLOBAL_PASS=0
GLOBAL_WARN=0
GLOBAL_FAIL=0

BG_PID=""
cleanup() {
  [[ -n "$BG_PID" ]] && kill "$BG_PID" 2>/dev/null || true
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

# Run timed load against a URL, return "error_pct p95_ms ok_count total"
run_load_window() {
  local token="$1"
  local url="$2"
  local vus="$3"
  local duration_s="$4"
  local label="$5"

  local tmpdir
  tmpdir=$(mktemp -d)
  local end_ms=$(( $(ms) + duration_s * 1000 ))
  local batch=0

  echo -ne "    ${label}: "

  while [[ $(ms) -lt $end_ms ]]; do
    batch=$((batch + 1))
    for i in $(seq 1 "$vus"); do
      {
        local t0
        t0=$(ms)
        local code
        code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
          -H "Authorization: Bearer ${token}" "$url" 2>/dev/null || echo "000")
        local t1
        t1=$(ms)
        echo "${code} $((t1 - t0))" >> "${tmpdir}/res_${batch}_${i}.txt"
      } &
    done
    wait
    echo -ne "."
    sleep 0.3
  done
  echo ""

  # Collect results
  local ok=0 err=0 s502=0 latencies=()
  for f in "${tmpdir}"/res_*.txt; do
    [[ -f "$f" ]] || continue
    read -r code latency < "$f"
    if [[ "$code" == "200" || "$code" == "201" ]]; then
      ok=$((ok + 1))
      latencies+=("$latency")
    elif [[ "$code" == "502" ]]; then
      s502=$((s502 + 1))
      err=$((err + 1))
    elif [[ "$code" == "503" || "$code" == "000" ]]; then
      err=$((err + 1))
    else
      err=$((err + 1))
    fi
  done
  rm -rf "$tmpdir"

  local total=$((ok + err))
  local err_pct=0
  [[ $total -gt 0 ]] && err_pct=$(( err * 100 / total ))

  # p95
  local p95=0
  if [[ ${#latencies[@]} -gt 0 ]]; then
    local sorted_latencies
    sorted_latencies=($(printf '%s\n' "${latencies[@]}" | sort -n))
    local idx=$(( ${#sorted_latencies[@]} * 95 / 100 ))
    p95="${sorted_latencies[$idx]:-0}"
  fi

  echo "${err_pct} ${p95} ${ok} ${total} ${s502}"
}

# ── Test one service ──────────────────────────────────────────────────────────
test_service() {
  local svc_spec="$1"
  local svc="${svc_spec%%:*}"
  local port="${svc_spec##*:}"
  local primary_container="mft-${svc}"
  local replica_container="mft-${svc}-2"
  local health_url="${BASE_URL}:${port}/actuator/health"

  echo ""
  echo -e "${CYAN}${BOLD}══ Scale Surge: ${svc} (port ${port}) ══${NC}"
  echo ""

  # Verify primary is up
  if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${primary_container}$"; then
    echo -e "${YELLOW}  SKIP — ${primary_container} not running${NC}"
    RESULT_VERDICT[$svc]="SKIP"
    return
  fi

  TOKEN=$(get_token)
  if [[ -z "$TOKEN" ]]; then
    echo -e "${RED}  FAIL — could not get auth token${NC}"
    RESULT_VERDICT[$svc]="FAIL"
    return
  fi

  # ── Phase 1: Baseline ──────────────────────────────────────────────────────
  echo -e "  ${GREEN}Phase 1: Baseline (${BASELINE_SECS}s, ${BASELINE_VUS} VUs)${NC}"
  read -r b_err b_p95 b_ok b_total _ <<< \
    $(run_load_window "$TOKEN" "$health_url" "$BASELINE_VUS" "$BASELINE_SECS" "baseline")
  RESULT_BASELINE_ERR[$svc]="${b_err}%"
  RESULT_BASELINE_P95[$svc]="${b_p95}ms"
  echo "    Result: error_rate=${b_err}%  p95=${b_p95}ms  ok=${b_ok}/${b_total}"

  # ── Phase 2: Scale-up (start replica-2 while load continues) ─────────────
  echo ""
  echo -e "  ${GREEN}Phase 2: Scale-up — starting ${replica_container}...${NC}"
  SCALEUP_START_MS=$(ms)

  # Check if replica-2 image/container exists
  if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^${replica_container}$"; then
    docker start "$replica_container" > /dev/null 2>&1
    echo "    Existing replica container started"
  else
    echo "    No pre-configured replica container found (${replica_container})"
    echo "    NOTE: In this platform, scale-up uses docker compose scale."
    echo "    Documenting startup guardrail behaviour with primary container restart instead."
    # Restart primary to simulate startup window
    docker stop "$primary_container" > /dev/null 2>&1
    sleep 2
    docker start "$primary_container" > /dev/null 2>&1
  fi

  # Monitor during startup window (60s max)
  echo "    Monitoring during startup window..."
  local scaleup_err=0 scaleup_ok=0 scaleup_502=0 scaleup_total=0
  local scaleup_elapsed=0
  local first_200=""

  while [[ $scaleup_elapsed -lt 75 ]]; do
    sleep 2
    scaleup_elapsed=$(( ($(ms) - SCALEUP_START_MS) / 1000 ))
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$health_url" 2>/dev/null || echo "000")
    scaleup_total=$((scaleup_total + 1))
    case "$code" in
      200|201)
        scaleup_ok=$((scaleup_ok + 1))
        [[ -z "$first_200" ]] && first_200="${scaleup_elapsed}s"
        ;;
      502)
        scaleup_502=$((scaleup_502 + 1))
        scaleup_err=$((scaleup_err + 1))
        ;;
      503|000)
        scaleup_err=$((scaleup_err + 1))
        ;;
    esac
    [[ -n "$first_200" && $scaleup_elapsed -gt $((${first_200%s} + 5)) ]] && break
  done

  local su_err_pct=0
  [[ $scaleup_total -gt 0 ]] && su_err_pct=$(( scaleup_err * 100 / scaleup_total ))
  RESULT_SCALEUP_ERR[$svc]="${su_err_pct}% (502s=${scaleup_502})"
  echo "    Scale-up window: error_rate=${su_err_pct}%  502s=${scaleup_502}  first_200=${first_200:-never}"

  # ── Phase 3: Peak load ────────────────────────────────────────────────────
  echo ""
  echo -e "  ${GREEN}Phase 3: Peak load (${PEAK_SECS}s, ${PEAK_VUS} VUs = 2x baseline)${NC}"
  # Refresh token if needed
  TOKEN=$(get_token)
  read -r p_err p_p95 p_ok p_total _ <<< \
    $(run_load_window "$TOKEN" "$health_url" "$PEAK_VUS" "$PEAK_SECS" "peak")
  RESULT_PEAK_ERR[$svc]="${p_err}%"
  echo "    Result: error_rate=${p_err}%  p95=${p_p95}ms  ok=${p_ok}/${p_total}"

  # ── Phase 4: Scale-down ───────────────────────────────────────────────────
  echo ""
  echo -e "  ${GREEN}Phase 4: Scale-down — graceful stop of replica${NC}"

  # Start background load for scale-down monitoring
  TOKEN=$(get_token)
  local sd_tmpdir
  sd_tmpdir=$(mktemp -d)
  local sd_end_ms=$(( $(ms) + (SCALEDOWN_DRAIN + 20) * 1000 ))

  # Background load during drain
  (
    local_token="$TOKEN"
    while [[ $(ms) -lt $sd_end_ms ]]; do
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
        -H "Authorization: Bearer ${local_token}" "$health_url" 2>/dev/null || echo "000")
      echo "$(ms) $code" >> "${sd_tmpdir}/drain.log"
      sleep 0.5
    done
  ) &
  BG_PID=$!

  # Trigger scale-down
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${replica_container}$"; then
    docker stop --time "$SCALEDOWN_DRAIN" "$replica_container" > /dev/null 2>&1 &
    echo "    docker stop --time ${SCALEDOWN_DRAIN} ${replica_container} running..."
  else
    echo "    (no separate replica — skipping drain, primary is the only instance)"
  fi

  sleep $((SCALEDOWN_DRAIN + 5))
  kill "$BG_PID" 2>/dev/null || true
  BG_PID=""

  # Analyse drain log
  local sd_ok=0 sd_502=0 sd_err=0 sd_total=0
  if [[ -f "${sd_tmpdir}/drain.log" ]]; then
    while read -r _ code; do
      sd_total=$((sd_total + 1))
      case "$code" in
        200|201) sd_ok=$((sd_ok + 1)) ;;
        502)     sd_502=$((sd_502 + 1)); sd_err=$((sd_err + 1)) ;;
        *)       sd_err=$((sd_err + 1)) ;;
      esac
    done < "${sd_tmpdir}/drain.log"
  fi
  rm -rf "$sd_tmpdir"

  local sd_err_pct=0
  [[ $sd_total -gt 0 ]] && sd_err_pct=$(( sd_err * 100 / sd_total ))
  RESULT_SCALEDOWN_ERR[$svc]="${sd_err_pct}% (502s=${sd_502})"
  echo "    Scale-down drain: error_rate=${sd_err_pct}%  502s=${sd_502}  ok=${sd_ok}/${sd_total}"

  # ── Phase 5: Recovery verification ───────────────────────────────────────
  echo ""
  echo -e "  ${GREEN}Phase 5: Recovery verification${NC}"
  if wait_for_healthy "$health_url" 60; then
    TOKEN=$(get_token)
    read -r r_err r_p95 r_ok r_total _ <<< \
      $(run_load_window "$TOKEN" "$health_url" "$BASELINE_VUS" 15 "recovery")
    echo "    Recovery: error_rate=${r_err}%  p95=${r_p95}ms  ok=${r_ok}/${r_total}"
  else
    echo -e "${RED}    FAIL — primary did not recover within 60s${NC}"
    RESULT_VERDICT[$svc]="FAIL"
    return
  fi

  # ── Verdict ───────────────────────────────────────────────────────────────
  local worst_err=0
  local total_502=0
  for err_str in "${su_err_pct}" "${p_err}" "${sd_err_pct}"; do
    [[ "$err_str" =~ ^[0-9]+$ ]] && [[ $err_str -gt $worst_err ]] && worst_err=$err_str
  done
  total_502=$(( scaleup_502 + sd_502 ))

  if [[ $worst_err -le 2 && $total_502 -eq 0 ]]; then
    echo -e "  ${GREEN}✓ PASS — max error rate during scale events: ${worst_err}%  502s: ${total_502}${NC}"
    RESULT_VERDICT[$svc]="PASS"
    GLOBAL_PASS=$((GLOBAL_PASS + 1))
  elif [[ $worst_err -le 5 && $total_502 -eq 0 ]]; then
    echo -e "  ${YELLOW}! WARN — max error rate during scale events: ${worst_err}% (threshold 2%)${NC}"
    RESULT_VERDICT[$svc]="WARN"
    GLOBAL_WARN=$((GLOBAL_WARN + 1))
  else
    echo -e "  ${RED}✗ FAIL — error rate: ${worst_err}%  502s: ${total_502}${NC}"
    RESULT_VERDICT[$svc]="FAIL"
    GLOBAL_FAIL=$((GLOBAL_FAIL + 1))
  fi
}

# ── Main ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}=== Scale Surge Test ===${NC}"
echo "Baseline VUs: ${BASELINE_VUS}  |  Peak VUs: ${PEAK_VUS}  |  Drain: ${SCALEDOWN_DRAIN}s"
echo "Services:     $(IFS=', '; echo "${SERVICES_TO_TEST[*]%%:*}")"
echo ""

SCORE_BEFORE=$(get_sentinel_score)
echo "Sentinel health score (before): ${SCORE_BEFORE}"

for spec in "${SERVICES_TO_TEST[@]}"; do
  test_service "$spec"
done

SCORE_AFTER=$(get_sentinel_score)

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "SCALE SURGE TEST RESULTS"
echo "═══════════════════════════════════════════════════════════════════════"
printf "%-25s %-12s %-16s %-16s %-16s %-8s\n" \
  "Service" "Baseline" "Scale-up err" "Peak err" "Scale-down err" "Verdict"
printf "%-25s %-12s %-16s %-16s %-16s %-8s\n" \
  "─────────────────────────" "──────────" "──────────────" "──────────────" "──────────────" "───────"

for spec in "${SERVICES_TO_TEST[@]}"; do
  svc="${spec%%:*}"
  printf "%-25s %-12s %-16s %-16s %-16s " \
    "$svc" \
    "${RESULT_BASELINE_ERR[$svc]:-N/A}" \
    "${RESULT_SCALEUP_ERR[$svc]:-N/A}" \
    "${RESULT_PEAK_ERR[$svc]:-N/A}" \
    "${RESULT_SCALEDOWN_ERR[$svc]:-N/A}"
  verdict="${RESULT_VERDICT[$svc]:-N/A}"
  case "$verdict" in
    PASS) echo -e "${GREEN}${verdict}${NC}" ;;
    WARN) echo -e "${YELLOW}${verdict}${NC}" ;;
    FAIL) echo -e "${RED}${verdict}${NC}" ;;
    *)    echo "$verdict" ;;
  esac
done

echo ""
printf "%-35s %s\n" "Sentinel before:" "${SCORE_BEFORE}"
printf "%-35s %s\n" "Sentinel after:"  "${SCORE_AFTER}"
echo ""
echo "PASS=${GLOBAL_PASS}  WARN=${GLOBAL_WARN}  FAIL=${GLOBAL_FAIL}"
echo ""

echo "Notes on Docker Compose scale semantics:"
echo "  - No built-in L7 load balancer; each service is a single named container."
echo "  - Replica-2 containers (mft-<svc>-2) must be pre-configured in docker-compose.yml."
echo "  - Startup guardrail is Docker's healthcheck start_period + retry policy."
echo "  - A 0% error rate during scale events confirms the guardrail is working."

if [[ -z "${CHAOS_MASTER_RUN:-}" ]]; then
  _RD="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/results"
  mkdir -p "$_RD"
  _V="PASS"; [[ $GLOBAL_FAIL -gt 0 ]] && _V="FAIL" || [[ $GLOBAL_WARN -gt 0 ]] && _V="WARN"
  _RF="${_RD}/chaos-scale-surge-$(date +%Y%m%d-%H%M%S).md"
  { echo "# Chaos: scale-surge — $(date '+%Y-%m-%d %H:%M:%S')"
    echo "**Overall:** ${_V} | Pass: ${GLOBAL_PASS} | Warn: ${GLOBAL_WARN} | Fail: ${GLOBAL_FAIL}"
    echo ""
    echo "Run full suite: \`./tests/perf/resilience/chaos-master.sh\`"
  } > "$_RF"
  echo "Results written: ${_RF}"
fi

if [[ $GLOBAL_FAIL -gt 0 ]]; then
  exit 2
elif [[ $GLOBAL_WARN -gt 0 ]]; then
  exit 1
else
  exit 0
fi

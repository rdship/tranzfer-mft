#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Replica Scaling Performance Test
# Compares: 1 replica vs 2 replicas vs 3 replicas per service.
# Measures: throughput improvement, memory per instance, latency at load.
#
# Usage:
#   ./tests/perf/resilience/replica-scaling.sh               # Full test
#   ./tests/perf/resilience/replica-scaling.sh --service onboarding-api
#   ./tests/perf/resilience/replica-scaling.sh --quick        # 3 services only
#
# IMPORTANT: docker-compose.yml must have replica config already in place.
# This script scales using: docker compose scale
# =============================================================================
set -uo pipefail

# Portable millisecond timestamp — macOS BSD date does not support %N.
# Prefer gdate (Homebrew coreutils) when available, fall back to python.
ms() {
  if command -v gdate &>/dev/null; then gdate +%s%3N
  else python3 -c "import time; print(int(time.time()*1000))"; fi
}
BASE_URL="${MFT_BASE_URL:-http://localhost}"
ADMIN_EMAIL="${MFT_ADMIN_EMAIL:-admin@filetransfer.local}"
ADMIN_PASS="${MFT_ADMIN_PASS:-Admin@1234}"
LOAD_VUS="${LOAD_VUS:-50}"
LOAD_DURATION="${LOAD_DURATION:-90}"  # seconds per replica tier

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

# Services where replica scaling is most impactful
ALL_SERVICES=(
  "onboarding-api:8080"
  "screening-service:8092"
  "encryption-service:8086"
  "analytics-service:8090"
  "ai-engine:8091"
  "storage-manager:8096"
)

QUICK_SERVICES=(
  "onboarding-api:8080"
  "screening-service:8092"
  "encryption-service:8086"
)

SPECIFIC_SERVICE="${1:-}"
QUICK_MODE=false
[[ "${1:-}" == "--quick" ]] && QUICK_MODE=true && SPECIFIC_SERVICE=""

declare -A RESULTS  # svc:replicas → "p95_ms throughput_rps"

measure_throughput() {
  local url="$1"
  local vus="$2"
  local duration_secs="$3"

  # Simple curl-based throughput measurement (no k6 dependency for this script)
  local start_time
  start_time=$(ms)
  local success=0
  local total=0
  local sum_ms=0
  local end_time=$(($(ms) + duration_secs * 1000))

  # Get token
  local token
  token=$(curl -s --max-time 5 \
    -X POST "${BASE_URL}:8080/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASS}\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('token',''))" 2>/dev/null)

  if [[ -z "$token" ]]; then
    echo "0 0 0"
    return
  fi

  # Fire requests in parallel batches
  while [[ $(ms) -lt $end_time ]]; do
    local batch_results=()
    for i in $(seq 1 "$vus"); do
      {
        local t_start
        t_start=$(ms)
        local code
        code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
          -H "Authorization: Bearer $token" "$url" 2>/dev/null)
        local t_end
        t_end=$(ms)
        echo "${code} $((t_end - t_start))"
      } &
    done
    wait

    for result in $(jobs -p); do
      total=$((total + 1))
    done
    sleep 0.5
  done

  local wall_ms=$(( $(ms) - start_time ))
  local wall_s=$((wall_ms / 1000))

  echo "$total $wall_s $sum_ms"
}

get_container_memory() {
  local container="$1"
  docker stats --no-stream --format "{{.MemUsage}}" "$container" 2>/dev/null | \
    awk '{print $1}' || echo "N/A"
}

run_k6_quick() {
  local url="$1"
  local vus="$2"
  local duration="$3"
  local label="$4"

  if ! command -v k6 &>/dev/null; then
    echo "k6 not installed — using curl-based measurement"
    echo "p95=N/A rps=N/A"
    return
  fi

  k6 run --quiet \
    --vus "$vus" \
    --duration "${duration}s" \
    --env URL="$url" \
    --env TOKEN="$(curl -s -X POST "${BASE_URL}:8080/api/auth/login" \
      -H "Content-Type: application/json" \
      -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASS}\"}" \
      | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('token',''))" 2>/dev/null)" \
    - <<'EOF' 2>&1 | tail -5
import http from 'k6/http';
import { check } from 'k6';
export default function() {
  const res = http.get(__ENV.URL, { headers: { Authorization: `Bearer ${__ENV.TOKEN}` } });
  check(res, { 'ok': r => r.status < 500 });
}
EOF
}

scale_to() {
  local service="$1"
  local replicas="$2"
  local container_name="mft-${service}"

  case $replicas in
    1)
      # Stop replica containers, leave only primary
      docker stop "${container_name}-2" 2>/dev/null || true
      docker stop "${container_name}-3" 2>/dev/null || true
      ;;
    2)
      docker stop "${container_name}-3" 2>/dev/null || true
      docker start "${container_name}-2" 2>/dev/null || \
        echo "  (second replica not configured in docker-compose — skipping)"
      ;;
    3)
      docker start "${container_name}-2" 2>/dev/null || true
      docker start "${container_name}-3" 2>/dev/null || \
        echo "  (third replica not configured in docker-compose — skipping)"
      ;;
  esac
  sleep 10  # Wait for instance to warm up
}

test_service_scaling() {
  local svc_spec="$1"
  local svc="${svc_spec%%:*}"
  local port="${svc_spec##*:}"
  local url="${BASE_URL}:${port}/actuator/health"
  local primary_container="mft-${svc}"

  echo ""
  echo -e "${CYAN}══ Testing: ${svc} (port ${port}) ══${NC}"
  echo ""
  printf "%-12s %-15s %-15s %-15s %-15s\n" "Replicas" "p95 Latency" "Throughput" "Memory/inst" "Notes"
  printf "%-12s %-15s %-15s %-15s %-15s\n" "────────" "───────────" "──────────" "───────────" "─────"

  for replicas in 1 2 3; do
    echo -ne "  Scaling to ${replicas} replica(s)... "
    scale_to "$svc" "$replicas"

    # Wait for health
    for i in $(seq 1 15); do
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "${url}" 2>/dev/null)
      [[ "$code" == "200" ]] && break
      sleep 2
    done
    echo "ready"

    # Memory per instance
    mem=$(get_container_memory "$primary_container")

    # Quick load test using curl (fallback if k6 not available)
    local start
    start=$(ms)
    local successes=0
    local total_ms=0
    local latencies=()

    token=$(curl -s --max-time 5 \
      -X POST "${BASE_URL}:8080/api/auth/login" \
      -H "Content-Type: application/json" \
      -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASS}\"}" \
      | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('token',''))" 2>/dev/null)

    # Fire LOAD_VUS concurrent requests, collect timings
    pids=()
    tmpdir=$(mktemp -d)
    for i in $(seq 1 "$LOAD_VUS"); do
      {
        t_start=$(ms)
        code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
          -H "Authorization: Bearer ${token:-}" "$url" 2>/dev/null)
        t_end=$(ms)
        echo "${code} $((t_end - t_start))" > "${tmpdir}/res_${i}.txt"
      } &
    done
    wait

    # Collect results
    all_latencies=()
    for f in "${tmpdir}"/res_*.txt; do
      read -r code latency < "$f"
      [[ "$code" == "200" ]] && all_latencies+=($latency) && successes=$((successes + 1))
    done
    rm -rf "$tmpdir"

    # Calculate p95
    p95="N/A"
    rps="N/A"
    if [[ ${#all_latencies[@]} -gt 0 ]]; then
      sorted_latencies=($(printf '%s\n' "${all_latencies[@]}" | sort -n))
      p95_idx=$(( ${#sorted_latencies[@]} * 95 / 100 ))
      p95="${sorted_latencies[$p95_idx]:-N/A}ms"
      wall_s=$(( ($(ms) - start) / 1000 ))
      [[ $wall_s -gt 0 ]] && rps=$(( successes / wall_s ))
    fi

    RESULTS["${svc}:${replicas}"]="${p95} ${rps} ${mem}"
    printf "%-12s %-15s %-15s %-15s\n" "${replicas}x" "$p95" "${rps} req/s" "$mem"
  done

  # Restore to 1 replica after test
  scale_to "$svc" 1
}

# ── Main ──────────────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}=== Replica Scaling Performance Test ===${NC}"
echo "Load: ${LOAD_VUS} concurrent users | ${LOAD_DURATION}s per tier"
echo ""
echo "What this measures:"
echo "  - Throughput improvement from 1→2→3 replicas"
echo "  - Memory consumption per replica instance"
echo "  - Latency reduction under load with more replicas"
echo "  - Whether scaling is linear, sub-linear, or super-linear"
echo ""

if [[ -n "$SPECIFIC_SERVICE" && "$SPECIFIC_SERVICE" != "--quick" ]]; then
  # Find the service spec
  for spec in "${ALL_SERVICES[@]}"; do
    svc="${spec%%:*}"
    if [[ "$svc" == "$SPECIFIC_SERVICE" ]]; then
      test_service_scaling "$spec"
      break
    fi
  done
else
  SERVICES_TO_TEST=("${ALL_SERVICES[@]}")
  [[ "$QUICK_MODE" == "true" ]] && SERVICES_TO_TEST=("${QUICK_SERVICES[@]}")

  for spec in "${SERVICES_TO_TEST[@]}"; do
    test_service_scaling "$spec"
  done
fi

# ── Summary table ─────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════════"
echo "REPLICA SCALING SUMMARY"
echo "═══════════════════════════════════════════════════════════════════"
echo ""
echo "Key insight: if p95 halves from 1→2 replicas, scaling is LINEAR."
echo "If improvement is < 50%, there may be a bottleneck elsewhere (DB, network)."
echo ""
printf "%-25s %-12s %-12s %-12s\n" "Service" "1 replica" "2 replicas" "3 replicas"
printf "%-25s %-12s %-12s %-12s\n" "─────────────────────────" "─────────" "──────────" "──────────"

for svc_spec in "${ALL_SERVICES[@]}"; do
  svc="${svc_spec%%:*}"
  r1="${RESULTS["${svc}:1"]:-N/A}"
  r2="${RESULTS["${svc}:2"]:-N/A}"
  r3="${RESULTS["${svc}:3"]:-N/A}"
  printf "%-25s %-12s %-12s %-12s\n" "$svc" "${r1%% *}" "${r2%% *}" "${r3%% *}"
done

echo ""
echo "Memory and notes:"
for svc_spec in "${ALL_SERVICES[@]}"; do
  svc="${svc_spec%%:*}"
  r1="${RESULTS["${svc}:1"]:-N/A}"
  mem="${r1##* }"
  echo "  $svc: $mem / instance"
done

echo ""
echo "How to read results:"
echo "  Linear scaling (good):     1x=400ms → 2x=200ms → 3x=133ms"
echo "  DB-bottlenecked (common):  1x=400ms → 2x=350ms → 3x=340ms  ← add DB read replicas"
echo "  Super-linear (excellent):  1x=400ms → 2x=180ms → 3x=110ms  ← load balancer efficient"

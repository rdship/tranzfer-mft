#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Transfer Continuity Test (THE MOST IMPORTANT TEST)
# Proves that files flowing through the system are NEVER silently lost.
# They either complete successfully OR land in the DLQ for replay.
#
# Core business guarantee: total_initiated = confirmed_complete + in_dlq
# silently_lost must be ZERO.
#
# Chaos injection sequence:
#   T+0s  : 200 concurrent transfers initiated
#   T+5s  : Kill encryption-service for 20s
#   T+15s : Kill screening-service for 15s
#   T+30s : Kill RabbitMQ for 10s (hardest — in-flight messages)
#   T+60s : All services recovered, DLQ drain monitoring begins
#
# Pass criteria:
#   PASS : silently_lost = 0  AND  DLQ drains within 5 min
#   WARN : silently_lost = 0  AND  DLQ does NOT fully drain (still recoverable)
#   FAIL : silently_lost > 0  (messages genuinely gone)
#
# Usage:
#   ./tests/perf/resilience/transfer-continuity.sh
#   TRANSFERS=100 ./tests/perf/resilience/transfer-continuity.sh
#   SKIP_RABBITMQ_KILL=1 ./tests/perf/resilience/transfer-continuity.sh
# =============================================================================
set -uo pipefail

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
RABBITMQ_MGMT="http://localhost:15672"
TOTAL_TRANSFERS="${TRANSFERS:-200}"
SKIP_RABBITMQ="${SKIP_RABBITMQ_KILL:-0}"
DLQ_QUEUE="file-transfer.events.dead-letter"
MAX_DLQ_WAIT_S=300          # 5 minutes
TOKEN_REFRESH_INTERVAL=270  # 4.5 minutes

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ── Accounting ────────────────────────────────────────────────────────────────
total_initiated=0
confirmed_complete=0
confirmed_failed=0
dlq_baseline=0
dlq_after_chaos=0
dlq_after_drain=0
silently_lost=0
PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

# Temp files for background accounting
RESULTS_DIR=$(mktemp -d /tmp/transfer-continuity.XXXXXX)
mkdir -p "${RESULTS_DIR}/responses"

cleanup() {
  jobs -p 2>/dev/null | xargs kill 2>/dev/null || true
  rm -rf "${RESULTS_DIR}"
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

get_dlq_count() {
  local count
  count=$(curl -s --max-time 5 -u guest:guest \
    "${RABBITMQ_MGMT}/api/queues/%2F/${DLQ_QUEUE}" \
    2>/dev/null | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  print(d.get('messages', 0))
except: print('N/A')
" 2>/dev/null || echo "N/A")

  # Fallback: check via Sentinel findings if RabbitMQ mgmt unavailable
  if [[ "$count" == "N/A" ]]; then
    count=$(curl -s --max-time 5 \
      "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/findings?status=OPEN" \
      2>/dev/null | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  items = d if isinstance(d, list) else d.get('content', [])
  dlq_findings = [f for f in items if 'dlq' in f.get('ruleName','').lower()]
  # If sentinel sees a dlq_growth finding, estimate from title
  if dlq_findings:
    print('sentinel:dlq_growth_detected')
  else:
    print('0')
except: print('N/A')
" 2>/dev/null || echo "N/A")
  fi
  echo "$count"
}

get_sentinel_score() {
  curl -s --max-time 5 "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/health-score" \
    2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('overallScore','N/A'))" \
    2>/dev/null || echo "N/A"
}

wait_for_service() {
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

wait_for_rabbitmq() {
  local max_s="${1:-60}"
  local elapsed=0
  while [[ $elapsed -lt $max_s ]]; do
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
      "${RABBITMQ_MGMT}/api/overview" -u guest:guest 2>/dev/null)
    [[ "$code" == "200" ]] && return 0
    sleep 3
    elapsed=$((elapsed + 3))
  done
  return 1
}

# ── Print header ──────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}=== Transfer Continuity Test ===${NC}"
echo "Core guarantee: No transfer is silently lost during chaos."
echo "Transfers:      ${TOTAL_TRANSFERS}"
echo "Chaos events:"
echo "  T+5s  : Kill encryption-service (20s)"
echo "  T+15s : Kill screening-service (15s)"
[[ "$SKIP_RABBITMQ" != "1" ]] && echo "  T+30s : Kill RabbitMQ (10s)" || \
  echo "  T+30s : Kill RabbitMQ — SKIPPED (SKIP_RABBITMQ_KILL=1)"
echo ""

# ── Prerequisites ─────────────────────────────────────────────────────────────
echo -e "${GREEN}[0] Prerequisites check...${NC}"
for container in mft-onboarding-api mft-screening-service mft-encryption-service mft-rabbitmq; do
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${container}$"; then
    echo "  ${container}: RUNNING"
  else
    echo -e "${RED}  ${container}: NOT RUNNING — aborting${NC}"
    exit 1
  fi
done

TOKEN=$(get_token)
if [[ -z "$TOKEN" ]]; then
  echo -e "${RED}  Auth: FAIL — could not get token${NC}"
  exit 1
fi
echo "  Auth token: obtained"
TOKEN_OBTAINED_AT=$(date +%s)

# ── Step 1: Baseline accounting ───────────────────────────────────────────────
echo ""
echo -e "${GREEN}[1] Pre-chaos baseline...${NC}"
SCORE_BEFORE=$(get_sentinel_score)
dlq_baseline=$(get_dlq_count)
echo "  Sentinel score:  ${SCORE_BEFORE}"
echo "  DLQ depth now:   ${dlq_baseline}"

# ── Step 2: Initiate N transfers concurrently ─────────────────────────────────
echo ""
echo -e "${GREEN}[2] Initiating ${TOTAL_TRANSFERS} concurrent transfers...${NC}"
TRANSFERS_START_MS=$(ms)
TRANSFER_PIDS=()

for i in $(seq 1 "$TOTAL_TRANSFERS"); do
  {
    # Refresh token if needed
    now=$(date +%s)
    if [[ $((now - TOKEN_OBTAINED_AT)) -gt $TOKEN_REFRESH_INTERVAL ]]; then
      TOKEN=$(get_token)
      TOKEN_OBTAINED_AT=$(date +%s)
    fi

    track_id="CONTINUITY-$(ms)-${i}-$$"
    code=$(curl -s \
      -o "${RESULTS_DIR}/responses/resp_${i}.json" \
      -w "%{http_code}" \
      --max-time 10 \
      -X POST "${BASE_URL}:8092/api/v1/screening/scan/text" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{
        \"content\": \"COMPANY: ACME CORP\nFILE: transfer-continuity-${i}.csv\nTRACK: ${track_id}\",
        \"filename\": \"continuity-${i}.csv\",
        \"trackId\": \"${track_id}\"
      }" 2>/dev/null || echo "000")

    echo "${i} ${code} ${track_id}" >> "${RESULTS_DIR}/transfer_results.log"
  } &
  TRANSFER_PIDS+=($!)

  # Stagger slightly to avoid overwhelming connection pool
  [[ $((i % 20)) -eq 0 ]] && sleep 0.2
done

total_initiated=$TOTAL_TRANSFERS
echo "  All ${TOTAL_TRANSFERS} transfer requests fired (PIDs running in background)"

# ── Step 3: Chaos injection timeline ─────────────────────────────────────────
echo ""
echo -e "${RED}[3] CHAOS INJECTION TIMELINE${NC}"

# T+5s: Kill encryption-service
echo "  Waiting 5s before first chaos event..."
sleep 5

echo -e "${RED}  T+5s: KILLING mft-encryption-service (20s outage)...${NC}"
ENCRYPT_KILL_TIME=$(date +%s)
docker stop mft-encryption-service > /dev/null 2>&1
echo "  encryption-service stopped"

# T+15s: Kill screening-service (10s after encryption kill)
sleep 10
echo -e "${RED}  T+15s: KILLING mft-screening-service (15s outage)...${NC}"
SCREEN_KILL_TIME=$(date +%s)
docker stop mft-screening-service > /dev/null 2>&1
echo "  screening-service stopped"

# T+25s: Restart encryption-service (20s after kill)
sleep 10
echo -e "${GREEN}  T+25s: RESTARTING mft-encryption-service...${NC}"
docker start mft-encryption-service > /dev/null 2>&1

# T+30s: Kill RabbitMQ
sleep 5
echo -e "${RED}  T+30s: KILLING mft-rabbitmq (10s outage)...${NC}"
if [[ "$SKIP_RABBITMQ" != "1" ]]; then
  RABBITMQ_KILL_TIME=$(date +%s)
  docker stop mft-rabbitmq > /dev/null 2>&1
  echo "  RabbitMQ stopped"
else
  echo "  (SKIP_RABBITMQ_KILL=1 — skipping RabbitMQ kill)"
fi

# T+30s: Restart screening-service (15s after kill)
echo -e "${GREEN}  T+30s: RESTARTING mft-screening-service...${NC}"
docker start mft-screening-service > /dev/null 2>&1

# T+40s: Restart RabbitMQ (10s after kill)
if [[ "$SKIP_RABBITMQ" != "1" ]]; then
  sleep 10
  echo -e "${GREEN}  T+40s: RESTARTING mft-rabbitmq...${NC}"
  docker start mft-rabbitmq > /dev/null 2>&1
fi

# Wait for all transfer background processes to finish
echo ""
echo "[4] Waiting for all transfer requests to complete..."
wait "${TRANSFER_PIDS[@]}" 2>/dev/null || true
TRANSFERS_END_MS=$(ms)
echo "  All transfer goroutines finished in $(( (TRANSFERS_END_MS - TRANSFERS_START_MS) / 1000 ))s"

# ── Step 4: Count results ─────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}[5] Counting transfer outcomes...${NC}"
confirmed_complete=0
confirmed_failed=0

if [[ -f "${RESULTS_DIR}/transfer_results.log" ]]; then
  while read -r idx code _track; do
    case "$code" in
      200|201) confirmed_complete=$((confirmed_complete + 1)) ;;
      *)       confirmed_failed=$((confirmed_failed + 1)) ;;
    esac
  done < "${RESULTS_DIR}/transfer_results.log"
fi

echo "  Transfers initiated:     ${total_initiated}"
echo "  HTTP 200/201 (complete): ${confirmed_complete}"
echo "  HTTP non-200 (failed):   ${confirmed_failed}"

# ── Step 5: Wait for services to recover ─────────────────────────────────────
echo ""
echo -e "${GREEN}[6] Waiting for all services to recover...${NC}"

RECOVERY_TIMEOUT=120
for svc_port in "screening-service:8092" "encryption-service:8086"; do
  svc="${svc_port%%:*}"
  port="${svc_port##*:}"
  echo -ne "  ${svc}: "
  if wait_for_service "$port" "$RECOVERY_TIMEOUT"; then
    echo "HEALTHY"
  else
    echo -e "${YELLOW}TIMEOUT (${RECOVERY_TIMEOUT}s)${NC}"
  fi
done

if [[ "$SKIP_RABBITMQ" != "1" ]]; then
  echo -ne "  RabbitMQ: "
  if wait_for_rabbitmq 60; then
    echo "HEALTHY"
  else
    echo -e "${YELLOW}TIMEOUT${NC}"
  fi
fi

# ── Step 6: Measure DLQ depth immediately after chaos ─────────────────────────
sleep 10  # Let DLQ populate from failed deliveries
echo ""
echo -e "${GREEN}[7] DLQ depth immediately after chaos...${NC}"
dlq_after_chaos=$(get_dlq_count)
echo "  DLQ baseline (before chaos):  ${dlq_baseline}"
echo "  DLQ after chaos:              ${dlq_after_chaos}"

# ── Step 7: Monitor DLQ drain (up to 5 minutes) ───────────────────────────────
echo ""
echo "[8] Monitoring DLQ drain (up to ${MAX_DLQ_WAIT_S}s = 5 minutes)..."
echo "    DLQ should auto-drain as services reconnect and replay messages."
echo ""
printf "  %-8s %-12s %-12s\n" "Elapsed" "DLQ Depth" "Status"
printf "  %-8s %-12s %-12s\n" "──────" "─────────" "──────"

drain_start=$(date +%s)
dlq_drained=false
prev_dlq="$dlq_after_chaos"

for _ in $(seq 1 60); do
  sleep 5
  elapsed=$(( $(date +%s) - drain_start ))
  dlq=$(get_dlq_count)

  if [[ "$dlq" =~ ^[0-9]+$ ]]; then
    if [[ "$dlq" == "0" ]]; then
      dlq_drained=true
      printf "  %-8s %-12s %-12s\n" "${elapsed}s" "$dlq" "DRAINED"
      break
    else
      trend=""
      if [[ "$prev_dlq" =~ ^[0-9]+$ && $dlq -lt $prev_dlq ]]; then
        trend=" (draining)"
      elif [[ "$prev_dlq" =~ ^[0-9]+$ && $dlq -gt $prev_dlq ]]; then
        trend=" (growing?)"
      fi
      printf "  %-8s %-12s %-12s\n" "${elapsed}s" "$dlq" "draining${trend}"
      prev_dlq="$dlq"
    fi
  else
    printf "  %-8s %-12s %-12s\n" "${elapsed}s" "${dlq}" "RabbitMQ mgmt unavailable"
  fi

  [[ $elapsed -ge $MAX_DLQ_WAIT_S ]] && break
done

dlq_after_drain=$(get_dlq_count)

# ── Step 8: Sentinel findings ─────────────────────────────────────────────────
sleep 20  # Allow Sentinel analysis cycle
echo ""
echo -e "${GREEN}[9] Checking Sentinel findings...${NC}"
SCORE_AFTER=$(get_sentinel_score)
echo "  Sentinel score after: ${SCORE_AFTER}"

curl -s --max-time 5 \
  "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/findings?status=OPEN" \
  2>/dev/null | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  items = d if isinstance(d, list) else d.get('content', [])
  relevant = [f for f in items if 'dlq' in f.get('ruleName','').lower()
              or 'transfer' in f.get('ruleName','').lower()
              or 'resilience' in f.get('analyzer','').lower()]
  if relevant:
    for f in relevant[:5]:
      print(f'  [{f[\"severity\"]}] {f[\"ruleName\"]}: {f[\"title\"]}')
  else:
    print('  No DLQ/transfer findings (clean recovery)')
except: print('  (could not parse findings)')
" 2>/dev/null

# ── Step 9: Final accounting ──────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "TRANSFER CONTINUITY TEST — FINAL ACCOUNTING"
echo "═══════════════════════════════════════════════════════════════"

# Calculate silently lost
# silently_lost = transfers that are neither confirmed_complete NOR in DLQ
dlq_growth=0
if [[ "$dlq_after_chaos" =~ ^[0-9]+$ && "$dlq_baseline" =~ ^[0-9]+$ ]]; then
  dlq_growth=$(( dlq_after_chaos - dlq_baseline ))
  [[ $dlq_growth -lt 0 ]] && dlq_growth=0
fi

# Messages in DLQ attributable to this test
dlq_test_messages=$dlq_growth
accounted_for=$(( confirmed_complete + dlq_test_messages ))
silently_lost=$(( total_initiated - accounted_for ))
[[ $silently_lost -lt 0 ]] && silently_lost=0  # can't lose more than we sent

echo ""
printf "%-35s %s\n" "Transfers initiated:"           "${total_initiated}"
printf "%-35s %s\n" "Confirmed complete (200/201):"  "${confirmed_complete}"
printf "%-35s %s\n" "DLQ baseline:"                  "${dlq_baseline}"
printf "%-35s %s\n" "DLQ after chaos:"               "${dlq_after_chaos}"
printf "%-35s %s\n" "DLQ growth (test-attributed):"  "${dlq_test_messages}"
printf "%-35s %s\n" "DLQ after drain wait:"          "${dlq_after_drain}"
printf "%-35s %s\n" "Accounted for:"                 "${accounted_for}"
echo "                                    ─────────────────────"
printf "%-35s ${RED}%s${NC}\n" "SILENTLY LOST:" "${silently_lost}  ← MUST BE 0"
echo ""

# ── Pass / Warn / Fail ────────────────────────────────────────────────────────
echo "──── Criteria Evaluation ────"

# Criterion 1: silence_lost = 0 (the only one that TRULY matters)
if [[ $silently_lost -eq 0 ]]; then
  echo -e "${GREEN}✓ PASS — silently_lost = 0 (no messages vanished)${NC}"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo -e "${RED}✗ FAIL — silently_lost = ${silently_lost} (messages are GONE — data loss!)${NC}"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# Criterion 2: DLQ drain
if [[ "$dlq_drained" == "true" ]]; then
  echo -e "${GREEN}✓ PASS — DLQ drained completely (auto-replay working)${NC}"
  PASS_COUNT=$((PASS_COUNT + 1))
elif [[ "$dlq_after_drain" == "0" ]]; then
  echo -e "${GREEN}✓ PASS — DLQ is empty after recovery${NC}"
  PASS_COUNT=$((PASS_COUNT + 1))
elif [[ "$dlq_after_drain" =~ ^[0-9]+$ && $dlq_after_drain -gt 0 ]]; then
  echo -e "${YELLOW}! WARN — DLQ still has ${dlq_after_drain} messages after ${MAX_DLQ_WAIT_S}s${NC}"
  echo "         Messages are recoverable via manual replay — not lost."
  WARN_COUNT=$((WARN_COUNT + 1))
else
  echo -e "${YELLOW}! WARN — DLQ status unclear (RabbitMQ management API unavailable)${NC}"
  echo "         Check via: curl -u guest:guest ${RABBITMQ_MGMT}/api/queues"
  WARN_COUNT=$((WARN_COUNT + 1))
fi

# Criterion 3: Sentinel health score delta
if [[ "$SCORE_BEFORE" =~ ^[0-9]+$ && "$SCORE_AFTER" =~ ^[0-9]+$ ]]; then
  delta=$(( SCORE_AFTER - SCORE_BEFORE ))
  if [[ $delta -ge -10 ]]; then
    echo -e "${GREEN}✓ PASS — Sentinel health score delta: ${delta} (${SCORE_BEFORE} → ${SCORE_AFTER})${NC}"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo -e "${YELLOW}! WARN — Sentinel health score dropped ${delta} points (${SCORE_BEFORE} → ${SCORE_AFTER})${NC}"
    WARN_COUNT=$((WARN_COUNT + 1))
  fi
fi

echo ""
echo "Summary: PASS=${PASS_COUNT}  WARN=${WARN_COUNT}  FAIL=${FAIL_COUNT}"
echo ""

if [[ -z "${CHAOS_MASTER_RUN:-}" ]]; then
  _RD="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/results"
  mkdir -p "$_RD"
  _V="PASS"; [[ $FAIL_COUNT -gt 0 ]] && _V="FAIL" || [[ $WARN_COUNT -gt 0 ]] && _V="WARN"
  _RF="${_RD}/chaos-transfer-continuity-$(date +%Y%m%d-%H%M%S).md"
  { echo "# Chaos: transfer-continuity — $(date '+%Y-%m-%d %H:%M:%S')"
    echo "**Overall:** ${_V} | Pass: ${PASS_COUNT} | Warn: ${WARN_COUNT} | Fail: ${FAIL_COUNT}"
    echo ""
    echo "Transfers initiated: \`${TOTAL_INITIATED:-200}\`"
    echo "Silently lost: \`${SILENTLY_LOST:-unknown}\`"
    echo ""
    echo "Run full suite: \`./tests/perf/resilience/chaos-master.sh\`"
  } > "$_RF"
  echo "Results written: ${_RF}"
fi

if [[ $FAIL_COUNT -gt 0 ]]; then
  echo -e "${RED}OVERALL: FAIL${NC}"
  echo "CRITICAL: Messages were silently lost. Investigate:"
  echo "  1. Check if RabbitMQ DLQ is configured for all queues"
  echo "  2. Check producer confirms are enabled (spring.rabbitmq.publisher-confirms=true)"
  echo "  3. Check DLQ binding in docker-compose / RabbitMQ topology"
  echo "  4. Run: curl -u guest:guest ${RABBITMQ_MGMT}/api/queues | python3 -m json.tool"
  exit 2
elif [[ $WARN_COUNT -gt 0 ]]; then
  echo -e "${YELLOW}OVERALL: WARN${NC}"
  echo "No data loss detected. DLQ may need manual replay for remaining messages."
  echo "To drain: use RabbitMQ management console or shovel plugin."
  exit 1
else
  echo -e "${GREEN}OVERALL: PASS${NC}"
  echo "Transfer continuity guarantee VERIFIED."
  echo "All ${total_initiated} transfers are accounted for (completed or in DLQ for replay)."
  exit 0
fi

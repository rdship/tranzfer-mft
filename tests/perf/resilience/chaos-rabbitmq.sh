#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — RabbitMQ Chaos Test
# 1. Start transfers. 2. Kill RabbitMQ. 3. Verify DLQ fills.
# 4. Restart RabbitMQ. 5. Verify DLQ drains (messages replayed).
# Sentinel should create dlq_growth finding during outage.
#
# Usage: ./tests/perf/resilience/chaos-rabbitmq.sh
# =============================================================================
set -uo pipefail

BASE_URL="${MFT_BASE_URL:-http://localhost}"
SENTINEL_PORT=8098
RABBITMQ_MGMT="http://localhost:15672"
ADMIN_EMAIL="${MFT_ADMIN_EMAIL:-admin@filetransfer.local}"
ADMIN_PASS="${MFT_ADMIN_PASS:-Admin@1234}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

echo ""
echo -e "${CYAN}=== RabbitMQ Chaos Test ===${NC}"
echo "This test will:"
echo "  1. Start background transfers via REST API"
echo "  2. Kill RabbitMQ container mid-transfer"
echo "  3. Monitor DLQ growth"
echo "  4. Restart RabbitMQ after 60s"
echo "  5. Verify DLQ drains (message replay)"
echo ""

# ── Login ─────────────────────────────────────────────────────────────────────
TOKEN=$(curl -s -X POST "${BASE_URL}:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASS}\"}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('token',''))" 2>/dev/null)

if [[ -z "$TOKEN" ]]; then
  echo -e "${RED}Failed to login. Is the platform running?${NC}"
  exit 1
fi
echo "Logged in ✓"

get_dlq_count() {
  # Query DLQ depth via RabbitMQ management API
  curl -s -u guest:guest \
    "${RABBITMQ_MGMT}/api/queues/%2F/file-transfer.events.dead-letter" \
    2>/dev/null | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  print(d.get('messages', 0))
except: print('N/A')
" 2>/dev/null || echo "N/A"
}

get_sentinel_score() {
  curl -s --max-time 3 "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/health-score" \
    2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('overallScore','?'))" 2>/dev/null || echo "N/A"
}

# ── Step 1: Start background transfers ───────────────────────────────────────
echo ""
echo -e "${GREEN}[1] Starting 200 background transfers...${NC}"
TRANSFER_PIDS=()

for i in $(seq 1 200); do
  curl -s -X POST "${BASE_URL}:8092/api/v1/screening/scan" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"fileName\":\"chaos-test-${i}.dat\",\"fileSize\":1024,\"senderName\":\"ACME\",\"receiverName\":\"PARTNER\",\"transferId\":\"CHAOS-${i}-$$\"}" \
    > /dev/null 2>&1 &
  TRANSFER_PIDS+=($!)
  sleep 0.05
done

echo "  Sent 200 transfer requests"
SCORE_BEFORE=$(get_sentinel_score)
DLQ_BEFORE=$(get_dlq_count)
echo "  Sentinel score before: $SCORE_BEFORE | DLQ: $DLQ_BEFORE"

sleep 3

# ── Step 2: Kill RabbitMQ ─────────────────────────────────────────────────────
echo ""
echo -e "${RED}[2] KILLING RabbitMQ (mft-rabbitmq)...${NC}"
KILL_TIME=$(date +%s)
docker stop mft-rabbitmq 2>/dev/null
echo "  RabbitMQ stopped at $(date '+%H:%M:%S')"

# ── Step 3: Monitor DLQ and service behavior ──────────────────────────────────
echo ""
echo "[3] Monitoring system behavior during RabbitMQ outage..."
echo -e "Time\t\t\tSentinel\tDLQ\tStatus"
echo "──────────────────────────────────────────────────────"

for i in $(seq 1 12); do  # 60 seconds
  sleep 5
  ELAPSED=$(( $(date +%s) - KILL_TIME ))
  SCORE=$(get_sentinel_score)
  DLQ=$(get_dlq_count)

  # Check if services still respond (should return 503 or queue events locally)
  SCREEN_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 \
    "${BASE_URL}:8092/actuator/health" 2>/dev/null)

  printf "%ds\t\t\t%s\t\t%s\t%s\n" "$ELAPSED" "$SCORE" "$DLQ" "screening: $SCREEN_STATUS"
done

# ── Step 4: Restart RabbitMQ ──────────────────────────────────────────────────
echo ""
echo -e "${GREEN}[4] RESTARTING RabbitMQ...${NC}"
RESTART_TIME=$(date +%s)
docker start mft-rabbitmq

echo "  Waiting for RabbitMQ to be ready..."
for i in $(seq 1 30); do
  sleep 2
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 \
    "${RABBITMQ_MGMT}/api/overview" -u guest:guest 2>/dev/null)
  if [[ "$STATUS" == "200" ]]; then
    READY_TIME=$(( $(date +%s) - RESTART_TIME ))
    echo "  RabbitMQ ready after ${READY_TIME}s"
    break
  fi
done

# ── Step 5: Watch DLQ drain ───────────────────────────────────────────────────
echo ""
echo "[5] Monitoring DLQ drain (should go back to 0 as messages replay)..."
DLQ_PEAK=0
DLQ_AFTER=999

for i in $(seq 1 24); do  # 2 min
  sleep 5
  DLQ=$(get_dlq_count)
  if [[ "$DLQ" =~ ^[0-9]+$ ]]; then
    if [[ $DLQ -gt $DLQ_PEAK ]]; then DLQ_PEAK=$DLQ; fi
    DLQ_AFTER=$DLQ
    echo "  DLQ: $DLQ"
  fi
  [[ "$DLQ" == "0" ]] && echo "  ✓ DLQ drained!" && break
done

# ── Check Sentinel findings ───────────────────────────────────────────────────
sleep 30  # Wait for Sentinel analysis cycle
echo ""
echo "[6] Checking Sentinel findings..."
curl -s --max-time 5 "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/findings?status=OPEN" \
  2>/dev/null | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  items = d if isinstance(d, list) else d.get('content', [])
  dlq_findings = [f for f in items if 'dlq' in f.get('ruleName','').lower()]
  if dlq_findings:
    print(f'  ✓ Sentinel detected DLQ growth: {len(dlq_findings)} finding(s)')
    for f in dlq_findings:
      print(f'    [{f[\"severity\"]}] {f[\"ruleName\"]}: {f[\"title\"]}')
  else:
    print('  ! No dlq_growth finding (may not have fired yet — check again after 5 min)')
except: print('  Could not check Sentinel findings')
" 2>/dev/null

# ── Summary ───────────────────────────────────────────────────────────────────
SCORE_AFTER=$(get_sentinel_score)
DOWN_DURATION=$(( $(date +%s) - KILL_TIME - 60 ))  # subtract restart time

echo ""
echo "═══════════════════════════════════════════════════"
echo "RABBITMQ CHAOS TEST RESULTS"
echo "═══════════════════════════════════════════════════"
printf "%-30s %s\n" "Outage duration:"     "~60s"
printf "%-30s %s\n" "DLQ peak depth:"      "$DLQ_PEAK messages"
printf "%-30s %s\n" "DLQ after recovery:"  "$DLQ_AFTER messages"
printf "%-30s %s\n" "Sentinel before:"     "$SCORE_BEFORE"
printf "%-30s %s\n" "Sentinel after:"      "$SCORE_AFTER"
echo ""

if [[ "$DLQ_AFTER" -le 0 ]] 2>/dev/null; then
  echo -e "${GREEN}✓ PASS — DLQ drained completely after recovery${NC}"
elif [[ "$DLQ_AFTER" -le 10 ]] 2>/dev/null; then
  echo -e "${YELLOW}! WARN — DLQ has $DLQ_AFTER remaining messages (may still be draining)${NC}"
else
  echo -e "${RED}✗ FAIL — DLQ still has $DLQ_AFTER messages after recovery${NC}"
fi

# Clean up background procs
for pid in "${TRANSFER_PIDS[@]}"; do
  kill "$pid" 2>/dev/null || true
done

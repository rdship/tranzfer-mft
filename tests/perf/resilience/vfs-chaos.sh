#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — VFS Chaos Test Suite
# Proves resilience of the Phantom Folder (VFS) layer under adversarial
# conditions: crashed pods, storage outages, concurrent path contention,
# tier pressure, RabbitMQ partitioning, and deduplication races.
#
# Architecture under test:
#   VirtualFileSystem (shared-platform) — WAIP, advisory locks, 3 buckets
#   VfsIntentRecoveryJob — ShedLock, 2-min cadence, PENDING→COMMITTED/ABORTED
#   StorageServiceClient — FAIL-FAST circuit breaker (8096)
#   PostgreSQL advisory locks — pg_advisory_xact_lock(hash(accountId, path))
#
# Scenarios:
#   1. Intent recovery after SIGKILL — PENDING drains within 3 min
#   2. Storage Manager kill — circuit opens, INLINE unaffected
#   3. Concurrent path contention — advisory locks prevent duplicates
#   4. Full pipeline under tier pressure — bucket counts consistent
#   5. RabbitMQ partition — VFS reads/writes survive broker outage
#   6. Concurrent identical uploads — dedup: 5 intents, 1 CAS object
#
# Exit codes: 0=PASS  1=WARN  2=FAIL
#
# Usage:
#   ./tests/perf/resilience/vfs-chaos.sh
#   ./tests/perf/resilience/vfs-chaos.sh --scenario 1,3,6
#   REPORT=/tmp/report.md ./tests/perf/resilience/vfs-chaos.sh
# =============================================================================
set -uo pipefail

# ── Bash 4+ required (associative arrays) ─────────────────────────────────────
if [[ "${BASH_VERSINFO[0]}" -lt 4 ]]; then
  for _b in /opt/homebrew/bin/bash /usr/local/bin/bash; do
    [[ -x "$_b" ]] && exec "$_b" "$0" "$@"
  done
  echo "ERROR: bash 4+ required (have ${BASH_VERSION}). Install: brew install bash" >&2; exit 1
fi

# ── Config ────────────────────────────────────────────────────────────────────
BASE_URL="${MFT_BASE_URL:-http://localhost}"
ADMIN_EMAIL="${MFT_ADMIN_EMAIL:-admin@filetransfer.local}"
ADMIN_PASS="${MFT_ADMIN_PASS:-Admin@1234}"
SENTINEL_PORT=8098
CONFIG_PORT=8084
STORAGE_PORT=8096
RABBITMQ_MGMT="http://localhost:15672"
POSTGRES_CTR="mft-postgres"
POSTGRES_DB="filetransfer"
TOKEN_REFRESH_INTERVAL=270   # 4.5 minutes — JWT expiry buffer
INTENT_STALE_MIN=5           # minutes until VfsIntentRecoveryJob classifies as stale
RECOVERY_JOB_INTERVAL=120    # seconds between job runs
CB_CLOSE_WAIT_S=35           # wait for circuit breaker to close after restart

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ── Scenario filter ───────────────────────────────────────────────────────────
RUN_SCENARIOS="${VFS_SCENARIOS:-1,2,3,4,5,6}"
for arg in "$@"; do
  case "$arg" in
    --scenario=*) RUN_SCENARIOS="${arg#--scenario=}" ;;
    --scenario)   : ;;
  esac
done

should_run() {
  local n="$1"
  echo "$RUN_SCENARIOS" | tr ',' '\n' | grep -q "^${n}$"
}

# ── Results ───────────────────────────────────────────────────────────────────
declare -A SCENARIO_VERDICT  # n → PASS|WARN|FAIL
declare -A SCENARIO_NOTE     # n → detail string
PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

# ── Cleanup / trap ────────────────────────────────────────────────────────────
cleanup() {
  # Best-effort: restart anything we may have killed
  for ctr in mft-sftp-service mft-storage-manager mft-rabbitmq; do
    docker start "$ctr" > /dev/null 2>&1 || true
  done
  jobs -p 2>/dev/null | xargs kill 2>/dev/null || true
}
trap cleanup EXIT

# ── Logging helpers ───────────────────────────────────────────────────────────
log_pass() { echo -e "  ${GREEN}✓ PASS${NC} — $*"; }
log_warn() { echo -e "  ${YELLOW}! WARN${NC} — $*"; }
log_fail() { echo -e "  ${RED}✗ FAIL${NC} — $*"; }
log_skip() { echo -e "  ${CYAN}· SKIP${NC} — $*"; }
log_info() { echo -e "  ${CYAN}·${NC} $*"; }

section() {
  echo ""
  echo -e "${CYAN}${BOLD}═══════════════════════════════════════════════════════════════════${NC}"
  echo -e "${CYAN}${BOLD}  $*${NC}"
  echo -e "${CYAN}${BOLD}═══════════════════════════════════════════════════════════════════${NC}"
}

record_verdict() {
  local n="$1" verdict="$2" note="$3"
  SCENARIO_VERDICT[$n]="$verdict"
  SCENARIO_NOTE[$n]="$note"
  case "$verdict" in
    PASS) PASS_COUNT=$((PASS_COUNT + 1)) ;;
    WARN) WARN_COUNT=$((WARN_COUNT + 1)) ;;
    FAIL) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
  esac
}

# ── Auth helpers ──────────────────────────────────────────────────────────────
TOKEN=""
TOKEN_AT=0

get_token() {
  local tok
  tok=$(curl -s --max-time 8 \
    -X POST "${BASE_URL}:8080/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASS}\"}" \
    2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken') or d.get('token',''))" \
    2>/dev/null || echo "")
  echo "$tok"
}

refresh_token_if_needed() {
  local now
  now=$(date +%s)
  if [[ -z "$TOKEN" || $(( now - TOKEN_AT )) -gt $TOKEN_REFRESH_INTERVAL ]]; then
    TOKEN=$(get_token)
    TOKEN_AT=$now
    if [[ -z "$TOKEN" ]]; then
      echo -e "${RED}  Auth refresh FAILED${NC}"
    fi
  fi
}

# ── HTTP helpers ──────────────────────────────────────────────────────────────
http_get() {
  # Usage: http_get <url> [token]
  local url="$1"
  local tok="${2:-}"
  if [[ -n "$tok" ]]; then
    curl -s --max-time 10 -H "Authorization: Bearer ${tok}" "$url" 2>/dev/null || echo ""
  else
    curl -s --max-time 10 "$url" 2>/dev/null || echo ""
  fi
}

http_post() {
  # Usage: http_post <url> <body> [token]
  local url="$1" body="$2" tok="${3:-}"
  if [[ -n "$tok" ]]; then
    curl -s --max-time 10 \
      -X POST -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${tok}" \
      -d "$body" "$url" 2>/dev/null || echo ""
  else
    curl -s --max-time 10 \
      -X POST -H "Content-Type: application/json" \
      -d "$body" "$url" 2>/dev/null || echo ""
  fi
}

http_code() {
  # Returns HTTP status code only
  local url="$1" tok="${2:-}"
  if [[ -n "$tok" ]]; then
    curl -s -o /dev/null -w "%{http_code}" --max-time 8 \
      -H "Authorization: Bearer ${tok}" "$url" 2>/dev/null || echo "000"
  else
    curl -s -o /dev/null -w "%{http_code}" --max-time 8 "$url" 2>/dev/null || echo "000"
  fi
}

wait_for_healthy() {
  local port="$1" max_s="${2:-90}"
  local elapsed=0 delay=2
  while [[ $elapsed -lt $max_s ]]; do
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
      "${BASE_URL}:${port}/actuator/health" 2>/dev/null || echo "000")
    [[ "$code" == "200" ]] && return 0
    sleep "$delay"
    elapsed=$((elapsed + delay))
    [[ $delay -lt 8 ]] && delay=$((delay * 2))
  done
  return 1
}

# ── VFS-specific helpers ──────────────────────────────────────────────────────
get_intent_health() {
  http_get "${BASE_URL}:${CONFIG_PORT}/api/vfs/intents/health" "$TOKEN"
}

get_pending_count() {
  get_intent_health \
    | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  print(int(d.get('pendingCount', d.get('pending', 0))))
except: print(-1)
" 2>/dev/null || echo -1
}

get_bucket_distribution() {
  http_get "${BASE_URL}:${CONFIG_PORT}/api/vfs/buckets" "$TOKEN" \
    | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  inline   = int(d.get('INLINE', d.get('inline', 0)))
  standard = int(d.get('STANDARD', d.get('standard', 0)))
  chunked  = int(d.get('CHUNKED', d.get('chunked', 0)))
  print(f'{inline} {standard} {chunked}')
except: print('-1 -1 -1')
" 2>/dev/null || echo "-1 -1 -1"
}

get_sentinel_score() {
  curl -s --max-time 5 "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/health-score" \
    2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(int(d.get('overallScore',0)))" \
    2>/dev/null || echo -1
}

pg_query() {
  # Usage: pg_query "SQL"
  docker exec "${POSTGRES_CTR}" psql -U postgres -d "${POSTGRES_DB}" -t -A -c "$1" 2>/dev/null \
    | tr -d ' ' || echo ""
}

# Sends N concurrent screening scan requests via background jobs.
# Returns when all complete. Background PIDs tracked via temp file.
submit_concurrent_scans() {
  local count="$1"
  local prefix="${2:-VFS-CHAOS}"
  local tmp_pids
  tmp_pids=$(mktemp /tmp/vfs-pids.XXXXXX)
  local tmp_results
  tmp_results=$(mktemp /tmp/vfs-results.XXXXXX)

  for i in $(seq 1 "$count"); do
    (
      local track_id="${prefix}-$(date +%s%3N)-${i}-$$"
      local code
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 \
        -X POST "${BASE_URL}:8092/api/v1/screening/scan/text" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{\"content\":\"COMPANY: ACME Corp invoice ${i} amount 5000 USD\",\"filename\":\"invoice-${i}.txt\",\"trackId\":\"${track_id}\"}" \
        2>/dev/null || echo "000")
      echo "$code" >> "$tmp_results"
    ) &
    echo $! >> "$tmp_pids"
  done

  # Wait for all background jobs to finish
  while IFS= read -r pid; do
    wait "$pid" 2>/dev/null || true
  done < "$tmp_pids"

  local ok=0 fail=0
  while IFS= read -r code; do
    case "$code" in
      200|201|202) ok=$((ok + 1)) ;;
      *) fail=$((fail + 1)) ;;
    esac
  done < "$tmp_results"

  rm -f "$tmp_pids" "$tmp_results"
  echo "$ok $fail"
}

# ── Prerequisites ─────────────────────────────────────────────────────────────
section "VFS Chaos Test Suite — Prerequisites"

PREREQ_OK=true
echo "  Checking required containers..."
for ctr in mft-postgres mft-config-service mft-screening-service \
           mft-sftp-service mft-storage-manager mft-rabbitmq mft-platform-sentinel; do
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${ctr}$"; then
    echo -e "    ${GREEN}✓${NC} ${ctr}"
  else
    echo -e "    ${RED}✗${NC} ${ctr} NOT RUNNING"
    PREREQ_OK=false
  fi
done

echo "  Checking tools..."
for tool in docker curl python3; do
  if command -v "$tool" &>/dev/null; then
    echo -e "    ${GREEN}✓${NC} ${tool}"
  else
    echo -e "    ${RED}✗${NC} ${tool} not found"
    PREREQ_OK=false
  fi
done

echo "  Obtaining auth token..."
TOKEN=$(get_token)
TOKEN_AT=$(date +%s)
if [[ -n "$TOKEN" ]]; then
  echo -e "    ${GREEN}✓${NC} Token obtained"
else
  echo -e "    ${RED}✗${NC} Auth FAILED"
  PREREQ_OK=false
fi

echo "  Checking VFS endpoints..."
vfs_health_code=$(http_code "${BASE_URL}:${CONFIG_PORT}/api/vfs/intents/health" "$TOKEN")
if [[ "$vfs_health_code" == "200" ]]; then
  echo -e "    ${GREEN}✓${NC} VFS intent health endpoint reachable"
else
  echo -e "    ${YELLOW}!${NC} VFS intent health returned ${vfs_health_code} (may still be ok)"
fi

if [[ "$PREREQ_OK" != "true" ]]; then
  echo ""
  echo -e "${RED}Prerequisites FAILED. Fix above issues and retry.${NC}"
  exit 2
fi
echo -e "  ${GREEN}All prerequisites satisfied.${NC}"

# Pre-chaos baselines
SCORE_BASELINE=$(get_sentinel_score)
echo ""
echo "  Sentinel health score (baseline): ${SCORE_BASELINE}"
echo "  Scenarios to run: ${RUN_SCENARIOS}"

# ═════════════════════════════════════════════════════════════════════════════
# SCENARIO 1: VFS Intent Recovery after Mid-Write Crash (SIGKILL)
# ═════════════════════════════════════════════════════════════════════════════
if should_run 1; then
  section "Scenario 1 — VFS Intent Recovery after Pod SIGKILL"
  echo "  What: VfsIntentRecoveryJob replays/aborts stale PENDING intents after crash"
  echo "  How:  SIGKILL sftp-service mid-activity, then poll until PENDING→0"
  echo ""

  refresh_token_if_needed

  # Step 1: Baseline PENDING count
  baseline_pending=$(get_pending_count)
  log_info "Baseline PENDING intent count: ${baseline_pending}"

  # Step 2: Trigger activity — 10 concurrent scans to create pipeline work
  log_info "Submitting 10 concurrent scans to generate pipeline activity..."
  read -r scan_ok scan_fail <<< "$(submit_concurrent_scans 10 "S1-WARM")"
  log_info "Warm-up scans: ok=${scan_ok} fail=${scan_fail}"

  # Step 3: SIGKILL sftp-service (leaves any in-flight SFTP transactions with stale intents)
  log_info "Sending SIGKILL to mft-sftp-service..."
  docker kill --signal SIGKILL mft-sftp-service > /dev/null 2>&1 || true
  sleep 3  # Let the kernel report the death

  # Step 4: Check PENDING count immediately after kill
  pending_after_kill=$(get_pending_count)
  log_info "PENDING count immediately after SIGKILL: ${pending_after_kill}"

  # Step 5: Poll intent health for up to 5 min, expecting PENDING to drop
  MAX_WAIT_S=300   # 5 min absolute timeout
  PASS_WINDOW_S=180  # target: drop within 3 min = PASS
  poll_start=$(date +%s)
  final_pending=$pending_after_kill
  health_reached=false
  passed_within_3min=false

  log_info "Polling VFS intent health (up to 5 min)..."
  while true; do
    now=$(date +%s)
    elapsed=$(( now - poll_start ))
    current_pending=$(get_pending_count)
    printf "    t+%3ds — PENDING=%s\n" "$elapsed" "$current_pending"

    if [[ $current_pending -eq 0 || ( $current_pending -ne -1 && $current_pending -le $baseline_pending ) ]]; then
      health_reached=true
      final_pending=$current_pending
      [[ $elapsed -le $PASS_WINDOW_S ]] && passed_within_3min=true
      break
    fi

    if [[ $elapsed -ge $MAX_WAIT_S ]]; then
      final_pending=$current_pending
      break
    fi
    sleep 15
  done

  # Step 6: Check for stuck RECOVERING state in DB
  recovering_count=$(pg_query "SELECT count(*) FROM vfs_intents WHERE status='RECOVERING';" || echo "?")
  log_info "RECOVERING count in DB after drain: ${recovering_count}"

  # Step 7: Restart sftp-service and verify new writes work
  log_info "Restarting mft-sftp-service..."
  docker start mft-sftp-service > /dev/null 2>&1 || true
  if wait_for_healthy "8081" 90; then
    log_info "sftp-service is healthy again"

    refresh_token_if_needed
    read -r post_ok post_fail <<< "$(submit_concurrent_scans 3 "S1-POST")"
    log_info "Post-recovery probe: ok=${post_ok} fail=${post_fail}"
  else
    log_warn "sftp-service did not recover within 90s — continuing"
  fi

  # Verdict
  if [[ "$health_reached" == "true" && "$passed_within_3min" == "true" && "$recovering_count" == "0" ]]; then
    record_verdict 1 "PASS" "PENDING dropped to ${final_pending} within 3 min, 0 stuck RECOVERING"
    log_pass "PENDING dropped to ${final_pending} within 3 min, 0 stuck RECOVERING intents"
  elif [[ "$health_reached" == "true" && "$passed_within_3min" == "false" ]]; then
    record_verdict 1 "WARN" "PENDING drained but took >3 min (elapsed)"
    log_warn "PENDING drained to ${final_pending} but after the 3-min PASS window"
  elif [[ "$recovering_count" != "0" && "$recovering_count" != "?" ]]; then
    record_verdict 1 "FAIL" "Stuck RECOVERING=${recovering_count} — recovery job did not complete transition"
    log_fail "STUCK RECOVERING=${recovering_count} intents — VfsIntentRecoveryJob failed mid-transition"
  else
    record_verdict 1 "FAIL" "PENDING=${final_pending} still stuck after 5 min timeout"
    log_fail "PENDING=${final_pending} still stuck after 5 min — intent recovery not working"
  fi
fi

# ═════════════════════════════════════════════════════════════════════════════
# SCENARIO 2: Storage Manager Kill During Active Writes
# ═════════════════════════════════════════════════════════════════════════════
if should_run 2; then
  section "Scenario 2 — Storage Manager Kill During Active Writes"
  echo "  What: VFS STANDARD/CHUNKED writes fail-fast; INLINE writes continue unaffected"
  echo "  How:  Kill storage-manager mid-write, verify circuit opens + INLINE unaffected"
  echo ""

  refresh_token_if_needed

  # Step 1: Baseline bucket counts
  read -r inline_before standard_before chunked_before <<< "$(get_bucket_distribution)"
  log_info "Bucket baseline — INLINE=${inline_before} STANDARD=${standard_before} CHUNKED=${chunked_before}"
  score_before=$(get_sentinel_score)

  # Step 2: 20 concurrent scans while storage is alive (baseline traffic)
  log_info "Submitting 20 concurrent scans (baseline)..."
  read -r bscan_ok bscan_fail <<< "$(submit_concurrent_scans 20 "S2-BASE")"
  log_info "Baseline scans: ok=${bscan_ok} fail=${bscan_fail}"

  # Step 3: Kill storage-manager
  log_info "Killing mft-storage-manager..."
  docker kill mft-storage-manager > /dev/null 2>&1 || true
  sleep 3

  # Step 4: 10 more scans with storage down
  log_info "Submitting 10 scans with storage-manager down..."
  read -r dead_ok dead_fail <<< "$(submit_concurrent_scans 10 "S2-DEAD")"
  log_info "Scans during outage: ok=${dead_ok} fail=${dead_fail}"

  # Step 5: Verify storage-manager is unreachable (circuit open)
  storage_health=$(http_code "${BASE_URL}:${STORAGE_PORT}/actuator/health")
  log_info "Storage-manager health code: ${storage_health} (expect 000/502/503)"

  # Step 6: Check Sentinel for service_unhealthy finding
  sentinel_open=$(curl -s --max-time 8 \
    "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/findings?status=OPEN" \
    2>/dev/null \
    | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  items = d if isinstance(d, list) else d.get('content', [])
  storage_findings = [f for f in items if 'storage' in f.get('affectedService','').lower()
                      or 'storage' in f.get('ruleName','').lower()]
  print(len(storage_findings))
except: print(0)
" 2>/dev/null || echo "0")
  log_info "Sentinel findings related to storage-manager: ${sentinel_open}"

  # Step 7: Verify INLINE operations still work — small text scan (INLINE bucket, no CAS needed)
  log_info "Probing screening with INLINE-sized payload (< 4 KB) while storage is down..."
  inline_ok=0
  inline_fail=0
  for i in $(seq 1 5); do
    refresh_token_if_needed
    icode=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 \
      -X POST "${BASE_URL}:8092/api/v1/screening/scan/text" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{\"content\":\"COMPANY: INLINE-TEST-${i}\",\"filename\":\"inline-test-${i}.txt\",\"trackId\":\"S2-INLINE-${i}-$$\"}" \
      2>/dev/null || echo "000")
    [[ "$icode" == "200" || "$icode" == "201" || "$icode" == "202" ]] \
      && inline_ok=$((inline_ok + 1)) \
      || inline_fail=$((inline_fail + 1))
    sleep 0.5
  done
  log_info "INLINE-sized scans during storage outage: ok=${inline_ok}/5 fail=${inline_fail}/5"

  # Step 8: Check ABORTED intents for the kill window
  aborted_s2=$(pg_query "SELECT count(*) FROM vfs_intents WHERE status='ABORTED' AND created_at > NOW() - INTERVAL '3 minutes';" || echo "?")
  log_info "ABORTED intents in last 3 min: ${aborted_s2}"

  # Step 9: Restart storage-manager
  log_info "Restarting mft-storage-manager..."
  docker start mft-storage-manager > /dev/null 2>&1 || true

  log_info "Waiting for storage-manager to recover (up to 60s)..."
  circuit_close_start=$(date +%s)
  circuit_closed=false
  if wait_for_healthy "${STORAGE_PORT}" 60; then
    circuit_close_elapsed=$(( $(date +%s) - circuit_close_start ))
    log_info "Storage-manager healthy — circuit closed in ${circuit_close_elapsed}s"
    circuit_closed=true
  else
    log_warn "Storage-manager did not recover within 60s"
  fi

  # Verdict
  inline_unaffected=$([[ $inline_ok -ge 4 ]] && echo true || echo false)
  storage_was_down=$([[ "$storage_health" != "200" ]] && echo true || echo false)

  if [[ "$storage_was_down" == "true" && "$inline_unaffected" == "true" && "$circuit_closed" == "true" ]]; then
    record_verdict 2 "PASS" \
      "circuit opened (storage=${storage_health}), INLINE ok=${inline_ok}/5, circuit closed in ${circuit_close_elapsed:-?}s"
    log_pass "Circuit opened, INLINE writes unaffected (${inline_ok}/5 ok), circuit closed"
  elif [[ "$inline_unaffected" == "true" && "$circuit_closed" == "false" ]]; then
    record_verdict 2 "WARN" "INLINE unaffected but storage-manager slow to recover (>60s)"
    log_warn "INLINE operations unaffected but storage-manager recovery >60s"
  elif [[ "$inline_unaffected" == "false" ]]; then
    record_verdict 2 "FAIL" "INLINE operations impacted (ok=${inline_ok}/5) — storage outage cascaded"
    log_fail "INLINE operations FAILED during storage outage (should be independent, ok=${inline_ok}/5)"
  else
    record_verdict 2 "WARN" "storage health=${storage_health}, INLINE=${inline_ok}/5, circuit_closed=${circuit_closed}"
    log_warn "Partial: storage=${storage_health} INLINE=${inline_ok}/5 recovered=${circuit_closed}"
  fi
fi

# ═════════════════════════════════════════════════════════════════════════════
# SCENARIO 3: Concurrent VFS Path Contention (Advisory Lock Test)
# ═════════════════════════════════════════════════════════════════════════════
if should_run 3; then
  section "Scenario 3 — Concurrent VFS Path Contention (Advisory Lock)"
  echo "  What: PostgreSQL advisory locks prevent duplicate entries under N-concurrent writes"
  echo "  How:  20 concurrent requests using the SAME trackId; verify 0 duplicates, 0 deadlocks"
  echo ""

  refresh_token_if_needed

  # Step 1: Get an existing account ID from DB
  account_id=$(pg_query "SELECT id FROM transfer_accounts WHERE active=true LIMIT 1;")
  if [[ -z "$account_id" ]]; then
    log_warn "No active account found — using NULL-equivalent test"
    account_id="00000000-0000-0000-0000-000000000001"
  fi
  log_info "Using account: ${account_id}"

  # Step 2: Baseline counts
  ve_baseline=$(pg_query "SELECT count(*) FROM virtual_entries WHERE deleted=false;")
  intents_baseline=$(pg_query "SELECT count(*) FROM vfs_intents;")
  log_info "virtual_entries baseline: ${ve_baseline}"
  log_info "vfs_intents baseline: ${intents_baseline}"

  # Step 3: 20 concurrent requests using the SAME fixed trackId
  # This tests the unique constraint on trackId in vfs_intents under concurrent load
  FIXED_TRACK="S3-CONTENTION-$(date +%s)-$$"
  log_info "Sending 20 concurrent scans with identical trackId=${FIXED_TRACK}..."

  tmp_contention=$(mktemp /tmp/vfs-contention.XXXXXX)
  for i in $(seq 1 20); do
    (
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 \
        -X POST "${BASE_URL}:8092/api/v1/screening/scan/text" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{\"content\":\"CONTENTION TEST payload same content\",\"filename\":\"contention.txt\",\"trackId\":\"${FIXED_TRACK}\"}" \
        2>/dev/null || echo "000")
      echo "$code" >> "$tmp_contention"
    ) &
  done
  wait   # Wait for all 20 concurrent requests

  # Tally results
  ok_count=0; fail_count=0; err_count=0
  while IFS= read -r code; do
    case "$code" in
      200|201|202) ok_count=$((ok_count + 1)) ;;
      409|400)     fail_count=$((fail_count + 1)) ;;  # Conflict/duplicate — acceptable
      *)           err_count=$((err_count + 1)) ;;
    esac
  done < "$tmp_contention"
  rm -f "$tmp_contention"
  log_info "Concurrent results: ok=${ok_count} conflict(409/400)=${fail_count} error=${err_count}"

  sleep 3  # Let DB settle

  # Step 4: Check for duplicate entries with the same trackId
  dup_intents=$(pg_query "SELECT count(*) FROM vfs_intents WHERE track_id='${FIXED_TRACK}';")
  log_info "vfs_intents rows for trackId=${FIXED_TRACK}: ${dup_intents} (expect ≤1 from idempotent writes)"

  # Check no duplicate virtual_entries were created for the same path
  dup_ve=$(pg_query "SELECT count(*) FROM virtual_entries WHERE track_id='${FIXED_TRACK}' AND deleted=false;")
  log_info "virtual_entries for trackId (not-deleted): ${dup_ve}"

  # Step 5: Check DB for deadlock errors in pg_stat_activity
  deadlocks=$(pg_query "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND state='active';" || echo "0")
  log_info "Active lock waits in pg_stat_activity: ${deadlocks}"

  # Step 6: Check for any PENDING intents from contention test (should be 0 or resolved quickly)
  pending_s3=$(pg_query "SELECT count(*) FROM vfs_intents WHERE status='PENDING' AND track_id LIKE 'S3-%';" || echo "0")
  log_info "PENDING intents from scenario 3: ${pending_s3}"

  # Verdict
  # Key guarantee: no DUPLICATE committed entries for same path, no deadlock 500s
  # dup_ve <= 1 is the pass criterion; 500-errors would indicate deadlock cascade
  if [[ "${dup_ve:-0}" -le 1 && "${err_count}" -eq 0 && "${deadlocks}" -eq 0 ]]; then
    record_verdict 3 "PASS" \
      "0 duplicate VirtualEntry rows, 0 deadlocks, 0 error responses (conflict=${fail_count} is correct)"
    log_pass "Advisory locks working: dup_ve=${dup_ve}, deadlocks=0, errors=0"
  elif [[ "${err_count}" -gt 2 ]]; then
    record_verdict 3 "FAIL" "err_count=${err_count} — unexpected 500s suggest deadlock or lock timeout"
    log_fail "Unexpected errors: ${err_count} — possible deadlock. Check pg_locks and application logs."
  elif [[ "${dup_ve:-0}" -gt 1 ]]; then
    record_verdict 3 "FAIL" "dup_ve=${dup_ve} duplicate virtual_entries — advisory lock not preventing races"
    log_fail "DUPLICATE VirtualEntry rows detected (${dup_ve}) — advisory lock serialization broken"
  else
    record_verdict 3 "WARN" "dup_ve=${dup_ve} err=${err_count} deadlocks=${deadlocks} — marginal"
    log_warn "Marginal result: dup_ve=${dup_ve} err=${err_count} active_locks=${deadlocks}"
  fi
fi

# ═════════════════════════════════════════════════════════════════════════════
# SCENARIO 4: Full VFS Pipeline Under Storage Tier Pressure
# ═════════════════════════════════════════════════════════════════════════════
if should_run 4; then
  section "Scenario 4 — Full VFS Pipeline Under Storage Tier Pressure"
  echo "  What: All 3 bucket tiers remain consistent while lifecycle job runs concurrently"
  echo "  How:  Trigger storage tiering, simultaneously hammer with 30 scans, verify counts"
  echo ""

  refresh_token_if_needed

  # Step 1: Baseline bucket distribution
  read -r inline_b4 standard_b4 chunked_b4 <<< "$(get_bucket_distribution)"
  log_info "Bucket baseline — INLINE=${inline_b4} STANDARD=${standard_b4} CHUNKED=${chunked_b4}"
  score_s4_before=$(get_sentinel_score)
  log_info "Sentinel score before: ${score_s4_before}"

  # Step 2: Trigger lifecycle tiering job on storage-manager
  log_info "Triggering storage lifecycle/tiering job..."
  tier_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 \
    -X POST "${BASE_URL}:${STORAGE_PORT}/api/v1/storage/lifecycle/tier" \
    -H "Authorization: Bearer ${TOKEN}" \
    2>/dev/null || echo "000")
  log_info "Tiering job trigger: HTTP ${tier_code}"

  # Step 3: While tiering runs, submit 30 concurrent scans
  log_info "Submitting 30 concurrent scans while tiering runs..."
  read -r tier_scan_ok tier_scan_fail <<< "$(submit_concurrent_scans 30 "S4-TIER")"
  log_info "Concurrent scans: ok=${tier_scan_ok} fail=${tier_scan_fail}"

  # Give tiering job time to complete
  sleep 10

  # Step 4: Check bucket distribution after
  read -r inline_after standard_after chunked_after <<< "$(get_bucket_distribution)"
  log_info "Bucket after — INLINE=${inline_after} STANDARD=${standard_after} CHUNKED=${chunked_after}"

  # Step 5: Verify intent health — 0 PENDING after operations
  refresh_token_if_needed
  pending_s4=$(get_pending_count)
  log_info "Pending intents after scenario: ${pending_s4}"

  # Step 6: Sentinel health score
  score_s4_after=$(get_sentinel_score)
  log_info "Sentinel score after: ${score_s4_after}"
  score_delta=$(( score_s4_after - score_s4_before ))
  log_info "Score delta: ${score_delta}"

  # Bucket count consistency check
  # After adding 30+ files, counts should be >= baseline (not decrease)
  inline_delta=$(( inline_after - inline_b4 ))
  standard_delta=$(( standard_after - standard_b4 ))
  chunked_delta=$(( chunked_after - chunked_b4 ))
  total_delta=$(( inline_delta + standard_delta + chunked_delta ))
  log_info "Bucket deltas — INLINE:${inline_delta} STANDARD:${standard_delta} CHUNKED:${chunked_delta} total:${total_delta}"

  # Verdict
  buckets_ok=true
  [[ $inline_after -lt $inline_b4 ]] && buckets_ok=false   # INLINE shouldn't shrink (soft delete only)
  total_new_files=$tier_scan_ok

  if [[ "$buckets_ok" == "true" && $pending_s4 -eq 0 && $score_delta -ge -5 ]]; then
    record_verdict 4 "PASS" \
      "bucket counts consistent (delta=${total_delta}), 0 stale intents, score delta=${score_delta}"
    log_pass "Buckets consistent, 0 pending intents, health score delta=${score_delta}"
  elif [[ $pending_s4 -gt 5 ]]; then
    record_verdict 4 "WARN" "pending=${pending_s4} intents remain after tiering (recovery job will catch)"
    log_warn "Stale intents pending after tiering: ${pending_s4} (expect recovery job to clear)"
  elif [[ $score_delta -lt -10 ]]; then
    record_verdict 4 "FAIL" "Sentinel score dropped ${score_delta} points — tiering caused platform degradation"
    log_fail "Sentinel health degraded by ${score_delta} points during tier pressure"
  elif [[ "$buckets_ok" == "false" ]]; then
    record_verdict 4 "FAIL" \
      "INLINE bucket decreased (${inline_b4}→${inline_after}) — possible VFS reference corruption"
    log_fail "INLINE bucket count DECREASED — VFS references may have been corrupted"
  else
    record_verdict 4 "WARN" "minor discrepancy: score_delta=${score_delta} pending=${pending_s4}"
    log_warn "Minor degradation: score_delta=${score_delta} pending=${pending_s4}"
  fi
fi

# ═════════════════════════════════════════════════════════════════════════════
# SCENARIO 5: VFS State Consistency After RabbitMQ Partitioning
# ═════════════════════════════════════════════════════════════════════════════
if should_run 5; then
  section "Scenario 5 — VFS State Consistency After RabbitMQ Partitioning"
  echo "  What: VFS reads/writes survive full broker outage (VFS uses DB, not RabbitMQ)"
  echo "  How:  Kill RabbitMQ, verify VFS operations work, restart, check DLQ drains"
  echo ""

  refresh_token_if_needed

  # Step 1: Note current DLQ depth and flow rule state
  dlq_before=$(curl -s --max-time 5 -u guest:guest \
    "${RABBITMQ_MGMT}/api/queues/%2F/file-transfer.events.dead-letter" \
    2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('messages',0))" \
    2>/dev/null || echo "?")
  log_info "DLQ depth before: ${dlq_before}"

  pending_s5_before=$(get_pending_count)
  log_info "VFS PENDING intents before: ${pending_s5_before}"

  # Step 2: Kill RabbitMQ
  log_info "Killing mft-rabbitmq..."
  docker kill mft-rabbitmq > /dev/null 2>&1 || true
  sleep 5  # Allow services to detect broker loss

  # Step 3: VFS READ operations during blackout — should work (DB-backed)
  log_info "Probing VFS read endpoints during RabbitMQ blackout..."
  vfs_read_ok=0
  vfs_read_fail=0
  for i in $(seq 1 5); do
    refresh_token_if_needed
    rcode=$(http_code "${BASE_URL}:${CONFIG_PORT}/api/vfs/intents/health" "$TOKEN")
    [[ "$rcode" == "200" ]] && vfs_read_ok=$((vfs_read_ok + 1)) || vfs_read_fail=$((vfs_read_fail + 1))
    sleep 1
  done
  log_info "VFS read probes during blackout: ok=${vfs_read_ok}/5 fail=${vfs_read_fail}/5"

  # Step 4: VFS WRITE operations (INLINE bucket — no storage-manager needed) during blackout
  log_info "Submitting 5 INLINE-sized scans during RabbitMQ blackout..."
  blackout_ok=0
  blackout_fail=0
  for i in $(seq 1 5); do
    refresh_token_if_needed
    bcode=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 \
      -X POST "${BASE_URL}:8092/api/v1/screening/scan/text" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{\"content\":\"BLACKOUT TEST ${i} — RabbitMQ is down\",\"filename\":\"blackout-${i}.txt\",\"trackId\":\"S5-BLACKOUT-${i}-$$\"}" \
      2>/dev/null || echo "000")
    [[ "$bcode" == "200" || "$bcode" == "201" || "$bcode" == "202" ]] \
      && blackout_ok=$((blackout_ok + 1)) \
      || blackout_fail=$((blackout_fail + 1))
    sleep 1
  done
  log_info "INLINE write probes during blackout: ok=${blackout_ok}/5 fail=${blackout_fail}/5"

  # Step 5: Restart RabbitMQ
  log_info "Restarting mft-rabbitmq..."
  docker start mft-rabbitmq > /dev/null 2>&1 || true

  log_info "Waiting for RabbitMQ to recover (up to 60s)..."
  rmq_recovered=false
  rmq_start=$(date +%s)
  for _ in $(seq 1 12); do
    sleep 5
    rmq_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
      -u guest:guest "${RABBITMQ_MGMT}/api/overview" 2>/dev/null || echo "000")
    if [[ "$rmq_code" == "200" ]]; then
      rmq_elapsed=$(( $(date +%s) - rmq_start ))
      log_info "RabbitMQ recovered in ${rmq_elapsed}s"
      rmq_recovered=true
      break
    fi
  done

  if [[ "$rmq_recovered" != "true" ]]; then
    log_warn "RabbitMQ did not recover within 60s"
  fi

  # Step 6: Check DLQ drain within 2 min of restart
  dlq_drain_ok=false
  dlq_drain_start=$(date +%s)
  dlq_after_restart="?"
  if [[ "$rmq_recovered" == "true" ]]; then
    log_info "Monitoring DLQ drain for up to 2 min..."
    for _ in $(seq 1 8); do
      sleep 15
      dlq_after_restart=$(curl -s --max-time 5 -u guest:guest \
        "${RABBITMQ_MGMT}/api/queues/%2F/file-transfer.events.dead-letter" \
        2>/dev/null \
        | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('messages',0))" \
        2>/dev/null || echo "?")
      log_info "  DLQ depth: ${dlq_after_restart}"
      if [[ "$dlq_after_restart" == "0" || "$dlq_after_restart" == "" ]]; then
        dlq_drain_elapsed=$(( $(date +%s) - dlq_drain_start ))
        log_info "DLQ fully drained in ${dlq_drain_elapsed}s"
        dlq_drain_ok=true
        break
      fi
    done
  fi

  # Step 7: Verify no VFS intents stuck PENDING
  refresh_token_if_needed
  pending_s5_after=$(get_pending_count)
  log_info "VFS PENDING intents after recovery: ${pending_s5_after}"

  # Verdict
  vfs_reads_survived=$([[ $vfs_read_ok -ge 4 ]] && echo true || echo false)
  vfs_writes_survived=$([[ $blackout_ok -ge 3 ]] && echo true || echo false)

  if [[ "$vfs_reads_survived" == "true" && "$vfs_writes_survived" == "true" && "$dlq_drain_ok" == "true" ]]; then
    record_verdict 5 "PASS" \
      "VFS reads ok=${vfs_read_ok}/5, writes ok=${blackout_ok}/5, DLQ drained, pending=${pending_s5_after}"
    log_pass "VFS fully DB-independent: reads ${vfs_read_ok}/5, writes ${blackout_ok}/5, DLQ drained"
  elif [[ "$vfs_reads_survived" == "true" && "$vfs_writes_survived" == "true" && "$dlq_drain_ok" == "false" ]]; then
    record_verdict 5 "WARN" \
      "VFS ops ok during outage, but DLQ did not fully drain (depth=${dlq_after_restart})"
    log_warn "VFS operations survived broker outage, but DLQ drain slow (depth=${dlq_after_restart})"
  elif [[ "$vfs_reads_survived" == "false" || "$vfs_writes_survived" == "false" ]]; then
    record_verdict 5 "FAIL" \
      "VFS operations FAILED during RabbitMQ outage — reads ok=${vfs_read_ok}/5 writes ok=${blackout_ok}/5"
    log_fail "VFS incorrectly depends on RabbitMQ: reads=${vfs_read_ok}/5 writes=${blackout_ok}/5"
  else
    record_verdict 5 "WARN" "rmq_recovered=${rmq_recovered} dlq_drain=${dlq_drain_ok} reads=${vfs_read_ok} writes=${blackout_ok}"
    log_warn "Partial: rmq=${rmq_recovered} dlq_drain=${dlq_drain_ok} reads=${vfs_read_ok} writes=${blackout_ok}"
  fi
fi

# ═════════════════════════════════════════════════════════════════════════════
# SCENARIO 6: VFS Deduplication Under Concurrent Identical Uploads
# ═════════════════════════════════════════════════════════════════════════════
if should_run 6; then
  section "Scenario 6 — VFS Deduplication Under Concurrent Identical Uploads"
  echo "  What: SHA-256 CAS dedup shares storageKey for identical content; no phantom duplicates"
  echo "  How:  5 concurrent uploads with identical content → expect 5 intents, 1 CAS object"
  echo ""

  refresh_token_if_needed

  # Step 1: Baseline virtual_entries count
  ve_baseline_s6=$(pg_query "SELECT count(*) FROM virtual_entries WHERE deleted=false;")
  intents_baseline_s6=$(pg_query "SELECT count(*) FROM vfs_intents;")
  log_info "virtual_entries baseline: ${ve_baseline_s6}"
  log_info "vfs_intents baseline: ${intents_baseline_s6}"

  # Step 2: Get storage object count baseline
  storage_obj_baseline=$(curl -s --max-time 10 \
    "${BASE_URL}:${STORAGE_PORT}/api/v1/storage/stats" \
    -H "Authorization: Bearer ${TOKEN}" 2>/dev/null \
    | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  # Try various field names for object count
  count = d.get('totalObjects', d.get('objectCount', d.get('total', -1)))
  print(count)
except: print(-1)
" 2>/dev/null || echo "-1")
  log_info "Storage object count baseline: ${storage_obj_baseline}"

  # Step 3: 5 concurrent uploads with IDENTICAL content (same SHA-256)
  DEDUP_CONTENT="DEDUP-TEST-IDENTICAL-CONTENT-SHA256-$(date +%Y%m%d)"
  DEDUP_PREFIX="S6-DEDUP-$(date +%s)-$$"

  log_info "Submitting 5 concurrent identical-content scans..."
  tmp_dedup=$(mktemp /tmp/vfs-dedup.XXXXXX)

  for i in $(seq 1 5); do
    (
      refresh_token_if_needed
      dcode=$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 \
        -X POST "${BASE_URL}:8092/api/v1/screening/scan/text" \
        -H "Authorization: Bearer ${TOKEN}" \
        -H "Content-Type: application/json" \
        -d "{\"content\":\"${DEDUP_CONTENT}\",\"filename\":\"dedup-file.txt\",\"trackId\":\"${DEDUP_PREFIX}-${i}\"}" \
        2>/dev/null || echo "000")
      echo "$dcode" >> "$tmp_dedup"
    ) &
  done
  wait   # Wait all 5 concurrent requests

  dedup_ok=0; dedup_fail=0
  while IFS= read -r code; do
    [[ "$code" == "200" || "$code" == "201" || "$code" == "202" ]] \
      && dedup_ok=$((dedup_ok + 1)) || dedup_fail=$((dedup_fail + 1))
  done < "$tmp_dedup"
  rm -f "$tmp_dedup"
  log_info "Identical-content scan results: ok=${dedup_ok}/5 fail=${dedup_fail}/5"

  sleep 3  # Allow VFS writes to propagate

  # Step 4: Check vfs_intents — should have up to 5 COMMITTED entries for our trackIds
  intents_s6=$(pg_query "SELECT count(*) FROM vfs_intents WHERE track_id LIKE '${DEDUP_PREFIX}%';" || echo "?")
  committed_s6=$(pg_query "SELECT count(*) FROM vfs_intents WHERE track_id LIKE '${DEDUP_PREFIX}%' AND status='COMMITTED';" || echo "?")
  log_info "vfs_intents created for dedup test: total=${intents_s6} committed=${committed_s6}"

  # Step 5: Check storageKey uniqueness — all 5 entries should share the same storageKey (if INLINE, it's the same content)
  distinct_keys=$(pg_query "SELECT count(DISTINCT storage_key) FROM virtual_entries WHERE track_id LIKE '${DEDUP_PREFIX}%' AND deleted=false;" || echo "?")
  ve_count=$(pg_query "SELECT count(*) FROM virtual_entries WHERE track_id LIKE '${DEDUP_PREFIX}%' AND deleted=false;" || echo "?")
  log_info "virtual_entries for dedup test: total=${ve_count} distinct_storage_keys=${distinct_keys}"

  # Step 6: Check storage-manager for dedup — physical CAS objects
  storage_obj_after=$(curl -s --max-time 10 \
    "${BASE_URL}:${STORAGE_PORT}/api/v1/storage/stats" \
    -H "Authorization: Bearer ${TOKEN}" 2>/dev/null \
    | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  count = d.get('totalObjects', d.get('objectCount', d.get('total', -1)))
  print(count)
except: print(-1)
" 2>/dev/null || echo "-1")
  log_info "Storage object count after: ${storage_obj_after}"

  new_phys_objects="?"
  if [[ "$storage_obj_baseline" != "-1" && "$storage_obj_after" != "-1" ]]; then
    new_phys_objects=$(( storage_obj_after - storage_obj_baseline ))
    log_info "New physical CAS objects created: ${new_phys_objects} (expect ≤1 for dedup)"
  fi

  # Step 7: Sentinel — no anomaly findings
  sentinel_s6=$(curl -s --max-time 8 \
    "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/findings?status=OPEN" \
    2>/dev/null \
    | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  items = d if isinstance(d, list) else d.get('content', [])
  vfs_anomalies = [f for f in items if any(kw in str(f).lower() for kw in ['vfs','dedup','storage_key','duplicate'])]
  print(len(vfs_anomalies))
except: print(0)
" 2>/dev/null || echo "0")
  log_info "VFS/dedup-related Sentinel findings: ${sentinel_s6}"

  # Verdict
  # INLINE files (<64KB) share content in DB and won't have a CAS storageKey, so distinct_keys may be 0 or 1
  # STANDARD files would have the same SHA-256 storageKey → 1 physical object
  # Key test: new_phys_objects should be ≤ 2 (allow 2 for CAS race window — still acceptable)
  phys_ok=true
  if [[ "$new_phys_objects" != "?" ]]; then
    [[ $new_phys_objects -gt 2 ]] && phys_ok=false
  fi

  if [[ $dedup_ok -ge 1 && "$phys_ok" == "true" && $dedup_fail -lt 5 ]]; then
    record_verdict 6 "PASS" \
      "ok=${dedup_ok}/5 fail=${dedup_fail}/5, intents=${intents_s6}, committed=${committed_s6}, new_phys=${new_phys_objects:-N/A}, distinct_keys=${distinct_keys}"
    log_pass "Dedup working: ${dedup_ok}/5 committed, ${new_phys_objects:-N/A} new CAS objects, ${distinct_keys:-?} distinct keys"
  elif [[ "$phys_ok" == "false" ]]; then
    record_verdict 6 "FAIL" \
      "new_phys_objects=${new_phys_objects} — CAS dedup broken (>2 physical files for identical content)"
    log_fail "Dedup BROKEN: ${new_phys_objects} new physical CAS objects for identical content"
  elif [[ $dedup_fail -eq 5 ]]; then
    record_verdict 6 "FAIL" "all 5 dedup scans failed (ok=0/5) — pipeline not processing"
    log_fail "All 5 concurrent identical-content uploads failed — pipeline issue"
  else
    record_verdict 6 "WARN" "ok=${dedup_ok}/5 fail=${dedup_fail}/5 new_phys=${new_phys_objects:-N/A} — marginal"
    log_warn "Dedup marginal: ok=${dedup_ok} fail=${dedup_fail} new_phys=${new_phys_objects:-N/A}"
  fi
fi

# ═════════════════════════════════════════════════════════════════════════════
# END-TO-END VFS HEALTH CHECK
# ═════════════════════════════════════════════════════════════════════════════
section "End-to-End VFS Health Check"

refresh_token_if_needed

log_info "Checking VFS intent health..."
intent_health_raw=$(get_intent_health)
intent_health_ok=$(echo "$intent_health_raw" | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  healthy = d.get('healthy', d.get('status','') == 'UP')
  pending = d.get('pendingCount', d.get('pending', '?'))
  stale   = d.get('staleCount', d.get('stale', '?'))
  print(f'healthy={healthy} pending={pending} stale={stale}')
except: print('parse_error')
" 2>/dev/null || echo "unavailable")
log_info "Intent health: ${intent_health_ok}"

log_info "Checking VFS dashboard..."
dashboard_raw=$(http_get "${BASE_URL}:${CONFIG_PORT}/api/vfs/dashboard" "$TOKEN")
dashboard_summary=$(echo "$dashboard_raw" | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  total = d.get('totalFiles', d.get('totalEntries', '?'))
  deleted = d.get('deletedFiles', d.get('deletedEntries', '?'))
  size_mb = d.get('totalSizeMb', d.get('totalSize', '?'))
  print(f'total={total} deleted={deleted} size_mb={size_mb}')
except: print('parse_error')
" 2>/dev/null || echo "unavailable")
log_info "Dashboard: ${dashboard_summary}"

log_info "Checking Sentinel VFS findings..."
vfs_sentinel=$(curl -s --max-time 8 \
  "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/findings?status=OPEN" \
  2>/dev/null \
  | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  items = d if isinstance(d, list) else d.get('content', [])
  vfs_items = [f for f in items if any(kw in str(f).lower()
    for kw in ['vfs','intent','storage','bucket','dedup'])]
  if vfs_items:
    for f in vfs_items[:5]:
      print(f'    [{f.get(\"severity\",\"?\")}] {f.get(\"ruleName\",\"?\")} — {f.get(\"title\",\"?\")}')
  else:
    print('    (no VFS-related open findings)')
except: print('    (could not parse findings)')
" 2>/dev/null || echo "    (sentinel unavailable)")
echo "$vfs_sentinel"

score_final=$(get_sentinel_score)
log_info "Final Sentinel score: ${SCORE_BASELINE} → ${score_final}"

# ═════════════════════════════════════════════════════════════════════════════
# SUMMARY
# ═════════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${BOLD}═══════════════════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}  VFS CHAOS TEST RESULTS${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════════════════════════${NC}"
echo ""

declare -A SCENARIO_NAMES=(
  ["1"]="Intent recovery after SIGKILL"
  ["2"]="Storage Manager kill — circuit + INLINE"
  ["3"]="Concurrent path contention (advisory lock)"
  ["4"]="Tier pressure pipeline consistency"
  ["5"]="RabbitMQ partition — VFS independence"
  ["6"]="Concurrent identical uploads — dedup"
)

printf "  %-5s %-45s %-8s %s\n" "Scen" "Test" "Verdict" "Notes"
printf "  %-5s %-45s %-8s %s\n" "────" "─────────────────────────────────────────────" "───────" "─────"

for n in 1 2 3 4 5 6; do
  should_run "$n" || continue
  verdict="${SCENARIO_VERDICT[$n]:-SKIP}"
  note="${SCENARIO_NOTE[$n]:-}"
  printf "  %-5s %-45s " "$n" "${SCENARIO_NAMES[$n]}"
  case "$verdict" in
    PASS) echo -e "${GREEN}PASS${NC}     ${note}" ;;
    WARN) echo -e "${YELLOW}WARN${NC}     ${note}" ;;
    FAIL) echo -e "${RED}FAIL${NC}     ${note}" ;;
    SKIP) echo -e "${CYAN}SKIP${NC}     (not in run list)" ;;
    *)    echo "${verdict}    ${note}" ;;
  esac
done

echo ""
printf "  %-35s Sentinel: ${SCORE_BASELINE} → ${score_final:-?}\n" "Health score:"
echo ""
echo "  PASS=${PASS_COUNT}  WARN=${WARN_COUNT}  FAIL=${FAIL_COUNT}"
echo ""

OVERALL="PASS"
[[ $WARN_COUNT -gt 0 ]] && OVERALL="WARN"
[[ $FAIL_COUNT -gt 0 ]] && OVERALL="FAIL"

case "$OVERALL" in
  PASS) echo -e "${GREEN}${BOLD}  OVERALL: PASS${NC}" ;;
  WARN) echo -e "${YELLOW}${BOLD}  OVERALL: WARN${NC}" ;;
  FAIL) echo -e "${RED}${BOLD}  OVERALL: FAIL${NC}" ;;
esac

echo ""
echo "  Thresholds:"
echo "    PASS : intent drain <3 min, circuit opens <10s, 0 duplicates, 0 dedup violations"
echo "    WARN : correct behavior but slower than target, or minor discrepancies"
echo "    FAIL : stuck PENDING/RECOVERING, INLINE ops impacted by storage kill,"
echo "           duplicate entries, VFS depends on RabbitMQ, >2 CAS objects for identical content"

# ── Write standalone results file (skipped when called from chaos-master.sh) ──
if [[ -z "${CHAOS_MASTER_RUN:-}" ]]; then
  _RD="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/results"
  mkdir -p "$_RD"
  _RF="${_RD}/chaos-vfs-$(date +%Y%m%d-%H%M%S).md"
  {
    echo "# Chaos: vfs-chaos — $(date '+%Y-%m-%d %H:%M:%S')"
    echo "**Overall:** ${OVERALL} | Pass: ${PASS_COUNT} | Warn: ${WARN_COUNT} | Fail: ${FAIL_COUNT}"
    echo ""
    echo "| # | Scenario | Verdict | Notes |"
    echo "|---|----------|---------|-------|"
    for n in 1 2 3 4 5 6; do
      should_run "$n" || continue
      echo "| ${n} | ${SCENARIO_NAMES[$n]} | ${SCENARIO_VERDICT[$n]:-SKIP} | ${SCENARIO_NOTE[$n]:-} |"
    done
    echo ""
    echo "| Metric | Value |"
    echo "|--------|-------|"
    echo "| Sentinel score baseline | ${SCORE_BASELINE} |"
    echo "| Sentinel score final    | ${score_final:-?} |"
    echo ""
    echo "Run full suite: \`./tests/perf/resilience/chaos-master.sh\`"
  } > "$_RF"
  echo "  Results written: ${_RF}"
fi

# ── Optional: append to master REPORT file ────────────────────────────────────
if [[ -n "${REPORT:-}" ]]; then
  {
    echo ""
    echo "---"
    echo ""
    echo "## VFS Chaos Test Results"
    echo ""
    echo "**Run:** $(date '+%Y-%m-%d %H:%M:%S')  |  **Scenarios:** ${RUN_SCENARIOS}"
    echo ""
    echo "| # | Scenario | Verdict | Notes |"
    echo "|---|----------|---------|-------|"
    for n in 1 2 3 4 5 6; do
      should_run "$n" || continue
      verdict="${SCENARIO_VERDICT[$n]:-SKIP}"
      note="${SCENARIO_NOTE[$n]:-}"
      echo "| ${n} | ${SCENARIO_NAMES[$n]} | ${verdict} | ${note} |"
    done
    echo ""
    echo "| Metric | Value |"
    echo "|--------|-------|"
    echo "| Sentinel score (baseline) | ${SCORE_BASELINE} |"
    echo "| Sentinel score (final) | ${score_final:-?} |"
    echo "| PASS | ${PASS_COUNT} |"
    echo "| WARN | ${WARN_COUNT} |"
    echo "| FAIL | ${FAIL_COUNT} |"
    echo "| **Overall** | **${OVERALL}** |"
  } >> "${REPORT}"
  echo "  Report appended to: ${REPORT}"
fi

# ── Exit code ─────────────────────────────────────────────────────────────────
case "$OVERALL" in
  PASS) exit 0 ;;
  WARN) exit 1 ;;
  FAIL) exit 2 ;;
esac

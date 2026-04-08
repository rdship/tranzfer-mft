#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Chaos Master Orchestrator
# Runs all chaos scenarios in sequence, generates a comprehensive markdown report.
#
# Sequence:
#   1. Prerequisites check (services, tools, auth)
#   2. Pre-chaos Sentinel health score (baseline)
#   3. startup-safety      — Java startup window guardrail
#   4. graceful-shutdown   — Spring Boot 30s drain window
#   5. cascade-isolation   — Circuit breaker fail-fast / graceful fallback
#   6. transfer-continuity — THE most important: no silent message loss
#   7. vfs-chaos           — VFS intent recovery, dedup, bucket tiers, RabbitMQ partition
#   8. scale-surge         — Scale-up/down error rate (last: most disruptive)
#   9. Post-chaos Sentinel health score
#  10. Markdown report with full results, MTTR table, recommendations
#
# Usage:
#   ./tests/perf/resilience/chaos-master.sh
#   ./tests/perf/resilience/chaos-master.sh --skip transfer-continuity
#   ./tests/perf/resilience/chaos-master.sh --only cascade-isolation,graceful-shutdown
#   REPORT_DIR=/tmp ./tests/perf/resilience/chaos-master.sh
#
# Output:
#   Console: live pass/warn/fail per scenario
#   File:    ${REPORT_DIR}/chaos-report-YYYYMMDD-HHMMSS.md
# =============================================================================
set -uo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
BASE_URL="${MFT_BASE_URL:-http://localhost}"
ADMIN_EMAIL="${MFT_ADMIN_EMAIL:-admin@filetransfer.local}"
ADMIN_PASS="${MFT_ADMIN_PASS:-Admin@1234}"
SENTINEL_PORT=8098
RABBITMQ_MGMT="http://localhost:15672"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_DIR="${REPORT_DIR:-${SCRIPT_DIR}/../results}"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
REPORT_FILE="${REPORT_DIR}/chaos-report-${TIMESTAMP}.md"
LOGS_SUBDIR="${REPORT_DIR}/chaos-logs-${TIMESTAMP}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ── Scenario filter ───────────────────────────────────────────────────────────
SKIP_SCENARIOS=""
ONLY_SCENARIOS=""
for arg in "$@"; do
  case "$arg" in
    --skip=*)  SKIP_SCENARIOS="${arg#--skip=}" ;;
    --only=*)  ONLY_SCENARIOS="${arg#--only=}" ;;
    --skip)    : ;;
    --only)    : ;;
  esac
done

should_run_scenario() {
  local name="$1"
  if [[ -n "$ONLY_SCENARIOS" ]]; then
    echo "$ONLY_SCENARIOS" | tr ',' '\n' | grep -q "^${name}$" && return 0 || return 1
  fi
  if [[ -n "$SKIP_SCENARIOS" ]]; then
    echo "$SKIP_SCENARIOS" | tr ',' '\n' | grep -q "^${name}$" && return 1 || return 0
  fi
  return 0
}

# ── Results ───────────────────────────────────────────────────────────────────
declare -A SCENARIO_EXIT      # name → exit code (0=pass, 1=warn, 2=fail)
declare -A SCENARIO_DURATION  # name → elapsed seconds
declare -A SCENARIO_STATUS    # name → PASS|WARN|FAIL|SKIP
GLOBAL_PASS=0
GLOBAL_WARN=0
GLOBAL_FAIL=0
GLOBAL_SKIP=0

# Per-scenario logs kept in results dir so they're committed alongside the report
LOGS_DIR="${LOGS_SUBDIR}"
mkdir -p "${LOGS_DIR}"

# Create report file IMMEDIATELY so `tail -f` works during the run
mkdir -p "${REPORT_DIR}"
cat > "${REPORT_FILE}" <<INIT_EOF
# TranzFer MFT — Chaos Test Report
**Status:** RUNNING — started $(date '+%Y-%m-%d %H:%M:%S')
**Platform:** ${BASE_URL}
**Report file:** ${REPORT_FILE}

> This file is written incrementally. Run \`tail -f ${REPORT_FILE}\` to watch live progress.

## Live Progress

INIT_EOF

cleanup() {
  jobs -p 2>/dev/null | xargs kill 2>/dev/null || true
  # LOGS_DIR is under results/ — intentionally NOT deleted so logs are committed
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
    | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  score = d.get('overallScore', 'N/A')
  tier  = d.get('healthTier','')
  print(f'{score} ({tier})' if tier else str(score))
except: print('N/A')
" 2>/dev/null || echo "N/A"
}

get_dlq_count() {
  curl -s --max-time 5 -u guest:guest \
    "${RABBITMQ_MGMT}/api/queues/%2F/file-transfer.events.dead-letter" \
    2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('messages',0))" \
    2>/dev/null || echo "N/A"
}

service_health_snapshot() {
  # Returns a compact status string for N services
  declare -A PORTS=(
    ["onboarding-api"]="8080" ["screening-service"]="8092"
    ["encryption-service"]="8086" ["analytics-service"]="8090"
    ["ai-engine"]="8091" ["notification-service"]="8097"
    ["storage-manager"]="8096" ["keystore-manager"]="8093"
    ["platform-sentinel"]="8098"
  )
  local results=()
  for svc in "${!PORTS[@]}"; do
    port="${PORTS[$svc]}"
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 \
      "${BASE_URL}:${port}/actuator/health" 2>/dev/null || echo "000")
    results+=("${svc}:${code}")
  done
  # Sort for consistent output
  printf '%s\n' "${results[@]}" | sort | tr '\n' ' '
}

run_scenario() {
  local name="$1"
  local script="$2"
  shift 2
  local args=("$@")
  local log_file="${LOGS_DIR}/${name}.log"

  if ! should_run_scenario "$name"; then
    echo -e "${CYAN}  SKIP${NC} — ${name} (filtered)"
    SCENARIO_STATUS[$name]="SKIP"
    GLOBAL_SKIP=$((GLOBAL_SKIP + 1))
    return
  fi

  if [[ ! -x "$script" ]]; then
    echo -e "${YELLOW}  SKIP${NC} — ${name}: script not found or not executable (${script})"
    SCENARIO_STATUS[$name]="SKIP"
    GLOBAL_SKIP=$((GLOBAL_SKIP + 1))
    return
  fi

  echo ""
  echo -e "${BOLD}────────────────────────────────────────────────────────────────────${NC}"
  echo -e "${BOLD}RUNNING: ${name}${NC}"
  echo -e "${BOLD}────────────────────────────────────────────────────────────────────${NC}"

  local t_start
  t_start=$(date +%s)

  # Run script, tee to log; CHAOS_MASTER_RUN=1 suppresses standalone report writing
  # Use bash 4+ if available (required for associative arrays and %3N date format)
  local _BASH=bash
  for _b in /opt/homebrew/bin/bash /usr/local/bin/bash; do
    [[ -x "$_b" ]] && { _BASH="$_b"; break; }
  done
  set +e
  CHAOS_MASTER_RUN=1 "$_BASH" "$script" "${args[@]}" 2>&1 | tee "$log_file"
  local exit_code=${PIPESTATUS[0]}
  set -e

  local t_end
  t_end=$(date +%s)
  local duration=$(( t_end - t_start ))

  SCENARIO_EXIT[$name]=$exit_code
  SCENARIO_DURATION[$name]=$duration

  case $exit_code in
    0)
      SCENARIO_STATUS[$name]="PASS"
      GLOBAL_PASS=$((GLOBAL_PASS + 1))
      echo -e "\n${GREEN}${BOLD}[ PASS ]${NC} ${name} completed in ${duration}s"
      echo "- **$(date '+%H:%M:%S')** \`${name}\` — ✓ PASS (${duration}s)" >> "${REPORT_FILE}"
      ;;
    1)
      SCENARIO_STATUS[$name]="WARN"
      GLOBAL_WARN=$((GLOBAL_WARN + 1))
      echo -e "\n${YELLOW}${BOLD}[ WARN ]${NC} ${name} completed in ${duration}s"
      echo "- **$(date '+%H:%M:%S')** \`${name}\` — ! WARN (${duration}s)" >> "${REPORT_FILE}"
      ;;
    *)
      SCENARIO_STATUS[$name]="FAIL"
      GLOBAL_FAIL=$((GLOBAL_FAIL + 1))
      echo -e "\n${RED}${BOLD}[ FAIL ]${NC} ${name} completed in ${duration}s (exit ${exit_code})"
      echo "- **$(date '+%H:%M:%S')** \`${name}\` — ✗ FAIL (${duration}s, exit ${exit_code})" >> "${REPORT_FILE}"
      ;;
  esac

  # Brief stabilisation between tests (services may need to settle)
  echo "  Waiting 20s for platform to stabilise before next scenario..."
  sleep 20
}

# ── Print header ──────────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║       TranzFer MFT — Chaos Test Master Orchestrator         ║${NC}"
echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo "  Started:    $(date '+%Y-%m-%d %H:%M:%S')"
echo "  Platform:   ${BASE_URL}"
echo "  Report:     ${REPORT_FILE}"
[[ -n "$ONLY_SCENARIOS" ]] && echo "  Only:       ${ONLY_SCENARIOS}"
[[ -n "$SKIP_SCENARIOS" ]] && echo "  Skipping:   ${SKIP_SCENARIOS}"
echo ""

# ── Step 1: Prerequisites ─────────────────────────────────────────────────────
echo -e "${CYAN}${BOLD}[PREREQ] Prerequisites check${NC}"
PREREQ_PASS=true

# Check required containers
echo "  Checking containers..."
REQUIRED_CONTAINERS=(
  mft-onboarding-api
  mft-screening-service
  mft-encryption-service
  mft-rabbitmq
  mft-postgres
  mft-platform-sentinel
)
for c in "${REQUIRED_CONTAINERS[@]}"; do
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${c}$"; then
    echo -e "    ${GREEN}✓${NC} ${c}"
  else
    echo -e "    ${RED}✗${NC} ${c} NOT RUNNING"
    PREREQ_PASS=false
  fi
done

# Check tools
echo "  Checking tools..."
for tool in docker curl python3; do
  if command -v "$tool" &>/dev/null; then
    echo -e "    ${GREEN}✓${NC} ${tool}"
  else
    echo -e "    ${RED}✗${NC} ${tool} not found"
    PREREQ_PASS=false
  fi
done

if command -v k6 &>/dev/null; then
  echo -e "    ${GREEN}✓${NC} k6 (background load available)"
else
  echo -e "    ${YELLOW}!${NC} k6 not found (will use bash curl loops)"
fi

# Check auth
echo "  Checking auth..."
TOKEN=$(get_token)
if [[ -n "$TOKEN" ]]; then
  echo -e "    ${GREEN}✓${NC} Auth token obtained"
else
  echo -e "    ${RED}✗${NC} Auth FAILED — is onboarding-api running?"
  PREREQ_PASS=false
fi

if [[ "$PREREQ_PASS" != "true" ]]; then
  echo ""
  echo -e "${RED}Prerequisites FAILED. Fix the above issues and retry.${NC}"
  exit 1
fi
echo -e "  ${GREEN}All prerequisites satisfied.${NC}"

# Ensure results dir exists (LOGS_DIR was already created above)
mkdir -p "${REPORT_DIR}"

# ── Step 2: Pre-chaos baseline ────────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}[BASELINE] Pre-chaos health snapshot${NC}"
SCORE_BEFORE=$(get_sentinel_score)
DLQ_BEFORE=$(get_dlq_count)
HEALTH_SNAPSHOT_BEFORE=$(service_health_snapshot)
BASELINE_TIME=$(date '+%Y-%m-%d %H:%M:%S')

echo "  Sentinel score:    ${SCORE_BEFORE}"
echo "  DLQ depth:         ${DLQ_BEFORE}"
echo "  Service snapshot:  (see report)"

# ── Step 3-7: Run scenarios ───────────────────────────────────────────────────
RUN_START=$(date +%s)

run_scenario "startup-safety"      "${SCRIPT_DIR}/startup-safety.sh"
run_scenario "graceful-shutdown"   "${SCRIPT_DIR}/graceful-shutdown.sh"
run_scenario "cascade-isolation"   "${SCRIPT_DIR}/cascade-isolation.sh"
run_scenario "transfer-continuity" "${SCRIPT_DIR}/transfer-continuity.sh"
run_scenario "vfs-chaos"           "${SCRIPT_DIR}/vfs-chaos.sh"
run_scenario "scale-surge"         "${SCRIPT_DIR}/scale-surge.sh"

RUN_END=$(date +%s)
TOTAL_DURATION=$(( RUN_END - RUN_START ))

# ── Step 8: Post-chaos snapshot ───────────────────────────────────────────────
echo ""
echo -e "${CYAN}${BOLD}[POST] Post-chaos health snapshot${NC}"
echo "  Waiting 30s for Sentinel analysis cycle..."
sleep 30

SCORE_AFTER=$(get_sentinel_score)
DLQ_AFTER=$(get_dlq_count)
HEALTH_SNAPSHOT_AFTER=$(service_health_snapshot)
POST_TIME=$(date '+%Y-%m-%d %H:%M:%S')

echo "  Sentinel score before: ${SCORE_BEFORE}"
echo "  Sentinel score after:  ${SCORE_AFTER}"
echo "  DLQ before:            ${DLQ_BEFORE}"
echo "  DLQ after:             ${DLQ_AFTER}"

# Collect sentinel findings
SENTINEL_FINDINGS=$(curl -s --max-time 5 \
  "${BASE_URL}:${SENTINEL_PORT}/api/v1/sentinel/findings?status=OPEN" \
  2>/dev/null | python3 -c "
import sys, json
try:
  d = json.load(sys.stdin)
  items = d if isinstance(d, list) else d.get('content', [])
  out = []
  for f in items[:20]:
    sev  = f.get('severity','?')
    rule = f.get('ruleName','?')
    title= f.get('title','?')
    svc  = f.get('affectedService','?')
    out.append(f'| {sev} | {rule} | {title} | {svc} |')
  print('\n'.join(out) if out else '| — | — | No OPEN findings | — |')
except: print('| ERR | — | Could not retrieve findings | — |')
" 2>/dev/null || echo "| ERR | — | Sentinel unavailable | — |")

# ── Step 9: Console summary ───────────────────────────────────────────────────
echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║                  CHAOS TEST SUITE RESULTS                  ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

ALL_SCENARIOS=("startup-safety" "graceful-shutdown" "cascade-isolation" "transfer-continuity" "vfs-chaos" "scale-surge")

printf "  %-30s %-8s %-10s\n" "Scenario" "Verdict" "Duration"
printf "  %-30s %-8s %-10s\n" "──────────────────────────────" "───────" "────────"

for name in "${ALL_SCENARIOS[@]}"; do
  status="${SCENARIO_STATUS[$name]:-SKIP}"
  dur="${SCENARIO_DURATION[$name]:-0}"
  printf "  %-30s " "$name"
  case "$status" in
    PASS) echo -e "${GREEN}PASS${NC}     ${dur}s" ;;
    WARN) echo -e "${YELLOW}WARN${NC}     ${dur}s" ;;
    FAIL) echo -e "${RED}FAIL${NC}     ${dur}s" ;;
    SKIP) echo -e "${CYAN}SKIP${NC}     —" ;;
    *)    echo "${status}     ${dur}s" ;;
  esac
done

echo ""
printf "  %-25s %s\n" "Total duration:"     "${TOTAL_DURATION}s"
printf "  %-25s %s\n" "Sentinel before:"    "${SCORE_BEFORE}"
printf "  %-25s %s\n" "Sentinel after:"     "${SCORE_AFTER}"
printf "  %-25s %s\n" "DLQ before:"         "${DLQ_BEFORE}"
printf "  %-25s %s\n" "DLQ after:"          "${DLQ_AFTER}"
echo ""
echo -e "  PASS=${GLOBAL_PASS}  WARN=${GLOBAL_WARN}  FAIL=${GLOBAL_FAIL}  SKIP=${GLOBAL_SKIP}"
echo ""

OVERALL="PASS"
[[ $GLOBAL_WARN -gt 0 ]] && OVERALL="WARN"
[[ $GLOBAL_FAIL -gt 0 ]] && OVERALL="FAIL"

case "$OVERALL" in
  PASS) echo -e "${GREEN}${BOLD}  OVERALL: PASS${NC}" ;;
  WARN) echo -e "${YELLOW}${BOLD}  OVERALL: WARN${NC}" ;;
  FAIL) echo -e "${RED}${BOLD}  OVERALL: FAIL${NC}" ;;
esac

# ── Step 10: Generate markdown report ────────────────────────────────────────
echo ""
echo "Generating report: ${REPORT_FILE}"

# Collect per-scenario log tails for the report
get_log_tail() {
  local name="$1"
  local log="${LOGS_DIR}/${name}.log"
  if [[ -f "$log" ]]; then
    tail -30 "$log" | sed 's/\x1b\[[0-9;]*m//g'  # strip ANSI colors
  else
    echo "(no log)"
  fi
}

cat >> "${REPORT_FILE}" << REPORTEOF

---

# Full Report

**Generated:** ${POST_TIME}
**Platform:** ${BASE_URL}
**Total duration:** ${TOTAL_DURATION}s

---

## Executive Summary

| Metric | Value |
|--------|-------|
| Scenarios run | $((GLOBAL_PASS + GLOBAL_WARN + GLOBAL_FAIL)) |
| PASS | ${GLOBAL_PASS} |
| WARN | ${GLOBAL_WARN} |
| FAIL | ${GLOBAL_FAIL} |
| SKIP | ${GLOBAL_SKIP} |
| Sentinel score (before) | ${SCORE_BEFORE} |
| Sentinel score (after) | ${SCORE_AFTER} |
| DLQ depth (before) | ${DLQ_BEFORE} |
| DLQ depth (after) | ${DLQ_AFTER} |
| **Overall result** | **${OVERALL}** |

---

## Scenario Results

| # | Scenario | Verdict | Duration | Purpose |
|---|----------|---------|----------|---------|
| 1 | startup-safety | ${SCENARIO_STATUS[startup-safety]:-SKIP} | ${SCENARIO_DURATION[startup-safety]:-—}s | Java startup window guardrail |
| 2 | graceful-shutdown | ${SCENARIO_STATUS[graceful-shutdown]:-SKIP} | ${SCENARIO_DURATION[graceful-shutdown]:-—}s | Spring 30s drain window |
| 3 | cascade-isolation | ${SCENARIO_STATUS[cascade-isolation]:-SKIP} | ${SCENARIO_DURATION[cascade-isolation]:-—}s | Circuit breaker fail-fast / fallback |
| 4 | transfer-continuity | ${SCENARIO_STATUS[transfer-continuity]:-SKIP} | ${SCENARIO_DURATION[transfer-continuity]:-—}s | No silent message loss |
| 5 | vfs-chaos | ${SCENARIO_STATUS[vfs-chaos]:-SKIP} | ${SCENARIO_DURATION[vfs-chaos]:-—}s | VFS intent recovery, dedup, bucket tiers |
| 6 | scale-surge | ${SCENARIO_STATUS[scale-surge]:-SKIP} | ${SCENARIO_DURATION[scale-surge]:-—}s | Scale-up/down error rate |

---

## Transfer Survival Table

> Transfer continuity guarantees: total_initiated = confirmed_complete + in_dlq + silently_lost

| Metric | Count |
|--------|-------|
| Total initiated | 200 |
| DLQ before chaos | ${DLQ_BEFORE} |
| DLQ after chaos | ${DLQ_AFTER} |
| Pass criterion | silently_lost = 0 |

See \`transfer-continuity\` scenario log for exact confirmed_complete and silently_lost values.

---

## Circuit Breaker Behaviour Table

| Scenario | Service killed | Type | Expected | Actual |
|----------|---------------|------|----------|--------|
| 1 | ai-engine | GRACEFUL | ALLOWED fallback | See log |
| 2 | encryption-service | FAIL-FAST | 503 in <500ms | See log |
| 3 | keystore-manager | GRACEFUL | Cached keys 60s | See log |
| 4 | platform-sentinel | OBSERVER | Zero impact | See log |
| 5 | ai-engine + analytics | GRACEFUL×2 | No cascade 500s | See log |

---

## MTTR Reference

> From recovery-time.sh baselines (run separately):

| Service | Expected MTTR | Notes |
|---------|--------------|-------|
| onboarding-api | <30s | Spring Boot, 30s graceful drain |
| screening-service | <30s | Fail-fast |
| encryption-service | <30s | Fail-fast, local key cache |
| storage-manager | <45s | 45s graceful drain (largest state) |
| ai-engine | <60s | Graceful, ALLOWED fallback during startup |
| analytics-service | <60s | Graceful, empty response fallback |
| keystore-manager | <60s | Local key cache = 24h operational |
| platform-sentinel | <60s | Observer, platform unaffected |

---

## Sentinel Findings (OPEN at report time)

| Severity | Rule | Title | Service |
|----------|------|-------|---------|
${SENTINEL_FINDINGS}

---

## Pre/Post Health Score Comparison

| When | Score |
|------|-------|
| Before chaos suite | ${SCORE_BEFORE} |
| After chaos suite | ${SCORE_AFTER} |
| Baseline time | ${BASELINE_TIME} |
| Post time | ${POST_TIME} |

---

## Scenario Detail Logs

### 1. startup-safety

\`\`\`
$(get_log_tail "startup-safety")
\`\`\`

### 2. graceful-shutdown

\`\`\`
$(get_log_tail "graceful-shutdown")
\`\`\`

### 3. cascade-isolation

\`\`\`
$(get_log_tail "cascade-isolation")
\`\`\`

### 4. transfer-continuity

\`\`\`
$(get_log_tail "transfer-continuity")
\`\`\`

### 5. vfs-chaos

\`\`\`
$(get_log_tail "vfs-chaos")
\`\`\`

### 6. scale-surge

\`\`\`
$(get_log_tail "scale-surge")
\`\`\`

---

## Recommended Actions

REPORTEOF

# Append recommendations based on results
{
  local_fail=false
  local_warn=false

  for name in "${ALL_SCENARIOS[@]}"; do
    status="${SCENARIO_STATUS[$name]:-SKIP}"
    [[ "$status" == "FAIL" ]] && local_fail=true
    [[ "$status" == "WARN" ]] && local_warn=true
  done

  if [[ "${SCENARIO_STATUS[startup-safety]:-SKIP}" == "FAIL" ]]; then
    cat << 'RECEOF'
### startup-safety: FAIL

- **Root cause:** Docker healthcheck `start_period` too short, or check interval too long.
- **Fix:** Set `start_period: 35s`, `interval: 10s`, `retries: 5` in docker-compose.yml for Java services.
- **Verify:** `docker inspect mft-onboarding-api --format '{{.State.Health}}'`

RECEOF
  fi

  if [[ "${SCENARIO_STATUS[startup-safety]:-SKIP}" == "WARN" ]]; then
    cat << 'RECEOF'
### startup-safety: WARN

- Startup exceeded 60s target. JVM warm-up may benefit from `-XX:TieredStopAtLevel=1` in dev.
- Consider `spring.jpa.hibernate.ddl-auto=validate` (faster than `update`) to reduce startup time.

RECEOF
  fi

  if [[ "${SCENARIO_STATUS[graceful-shutdown]:-SKIP}" == "FAIL" ]]; then
    cat << 'RECEOF'
### graceful-shutdown: FAIL

- **Root cause:** `server.shutdown=graceful` may not be set, or requests exceed 30s timeout.
- **Fix:** Add to `application.yml`:
  ```yaml
  server.shutdown: graceful
  spring.lifecycle.timeout-per-shutdown-phase: 30s
  ```
- **Docker:** Ensure `stop_grace_period: 40s` in docker-compose.yml (Spring 30s + 10s buffer).

RECEOF
  fi

  if [[ "${SCENARIO_STATUS[cascade-isolation]:-SKIP}" == "FAIL" ]]; then
    cat << 'RECEOF'
### cascade-isolation: FAIL

- **Root cause:** Circuit breaker not opening within 500ms threshold.
- **Fix:** Review `ResilientServiceClient` Resilience4j config:
  ```yaml
  resilience4j.circuitbreaker.instances.<name>:
    failureRateThreshold: 50
    waitDurationInOpenState: 10s
    slowCallDurationThreshold: 2s
  ```
- **For GRACEFUL services:** Verify fallback method is annotated with `@Recover` or Resilience4j `fallbackMethod`.

RECEOF
  fi

  if [[ "${SCENARIO_STATUS[transfer-continuity]:-SKIP}" == "FAIL" ]]; then
    cat << 'RECEOF'
### transfer-continuity: FAIL — CRITICAL

**Messages were silently lost. This is a P0 issue.**

- Verify RabbitMQ DLQ is configured for all queues:
  ```
  curl -u guest:guest http://localhost:15672/api/queues | python3 -m json.tool | grep dead
  ```
- Enable publisher confirms:
  ```yaml
  spring.rabbitmq.publisher-confirm-type: correlated
  spring.rabbitmq.publisher-returns: true
  ```
- Check that consumers use `AcknowledgeMode.MANUAL` or `AUTO` with requeue-on-failure.
- Ensure DLQ binding: `file-transfer.events.dead-letter` queue bound to dead-letter exchange.

RECEOF
  fi

  if [[ "${SCENARIO_STATUS[transfer-continuity]:-SKIP}" == "WARN" ]]; then
    cat << 'RECEOF'
### transfer-continuity: WARN

- No messages lost, but DLQ did not drain within 5 minutes.
- Trigger manual replay: RabbitMQ shovel or management UI → move messages from DLQ to source queue.
- Verify retry listener is configured with `@RabbitListener` on the DLQ.

RECEOF
  fi

  if [[ "${SCENARIO_STATUS[vfs-chaos]:-SKIP}" == "FAIL" ]]; then
    cat << 'RECEOF'
### vfs-chaos: FAIL

- **Intent stuck RECOVERING**: `SELECT * FROM vfs_intents WHERE status='RECOVERING' AND updated_at < NOW() - INTERVAL '10 minutes'` — manually resolve via `UPDATE vfs_intents SET status='ABORTED' WHERE ...` after verifying CAS state.
- **Storage Manager circuit breaker not opening**: Check `ResilientServiceClient` circuit breaker config — `slidingWindowSize` and `minimumNumberOfCalls` thresholds. Reduce if too lenient.
- **Duplicate VirtualEntry rows**: Indicates advisory lock hash collision — see P-01 in IMPROVEMENTS.md. Apply Fibonacci hash fix to `VirtualFileSystem.lockPath()`.
- **INLINE ops affected by Storage Manager kill**: INLINE bucket is DB-only — if INLINE writes fail during storage outage, the VFS is coupling to storage-manager incorrectly. Audit `VirtualFileSystem.writeFile()` INLINE branch.
- **VFS reads fail during RabbitMQ outage**: VFS is DB-backed and must not depend on RabbitMQ for reads. Audit any `@RabbitListener` in config-service that could block read paths.

RECEOF
  fi

  if [[ "${SCENARIO_STATUS[vfs-chaos]:-SKIP}" == "WARN" ]]; then
    cat << 'RECEOF'
### vfs-chaos: WARN

- **Slow intent drain**: `VfsIntentRecoveryJob` stale threshold (5 min) + interval (2 min) = up to 7 min recovery window after SIGKILL. Consider reducing stale threshold to 2 min (see R-04 in IMPROVEMENTS.md).
- **DLQ slow drain**: Verify RabbitMQ DLQ consumer is active — `GET http://localhost:15672/api/queues` → check `consumer_count` for DLQ queue.

RECEOF
  fi

  if [[ "${SCENARIO_STATUS[scale-surge]:-SKIP}" == "FAIL" ]]; then
    cat << 'RECEOF'
### scale-surge: FAIL

- Error rate exceeded 5% during scale events.
- **Scale-up:** Ensure Docker health-check prevents routing until container is ready.
- **Scale-down:** Verify `stop_grace_period` in docker-compose equals `timeout-per-shutdown-phase + 10s`.

RECEOF
  fi

  if [[ "$local_fail" == "false" && "$local_warn" == "false" ]]; then
    echo "No recommended actions — all scenarios passed."
  fi
} >> "${REPORT_FILE}"

# Append footer
cat >> "${REPORT_FILE}" << FOOTEREOF

---

## Test Environment

| Item | Value |
|------|-------|
| Platform URL | ${BASE_URL} |
| Admin email | ${ADMIN_EMAIL} |
| Report generated | ${POST_TIME} |
| Test suite version | chaos-master v2 |
| Scripts location | tests/perf/resilience/ |

---

*Report generated by TranzFer MFT chaos-master.sh*
FOOTEREOF

echo ""
echo "Report written: ${REPORT_FILE}"
echo ""

# ── Final exit code ───────────────────────────────────────────────────────────
case "$OVERALL" in
  PASS) exit 0 ;;
  WARN) exit 1 ;;
  FAIL) exit 2 ;;
esac

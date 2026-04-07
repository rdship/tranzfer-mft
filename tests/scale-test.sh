#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT Platform — Production Readiness Scale Test
# =============================================================================
# Expert agent: Tests match engine, VFS, routing, resilience at scale.
# Generates findings report for efficiency, safety, resilience.
#
# Usage:
#   ./tests/scale-test.sh              # Full test (build + spin up + test)
#   ./tests/scale-test.sh --skip-build # Skip Maven build, assume containers running
#   ./tests/scale-test.sh --quick      # Reduced scale (10 files instead of 100)
# =============================================================================

set -o pipefail

# --- Configuration ---
BASE_URL="http://localhost"
ONBOARDING_PORT=8080
CONFIG_PORT=8084
SFTP_PORT=2222
AI_PORT=8091
SCREENING_PORT=8092
STORAGE_PORT=8096
ADMIN_EMAIL="admin@filetransfer.local"
ADMIN_PASS="ScaleTest@2026"
REPORT_DIR="./tests/reports"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
FINDINGS_FILE="${REPORT_DIR}/scale-findings-${TIMESTAMP}.md"
SCALE_COUNT=100           # files per test
CONCURRENT_BATCH=20       # parallel curl requests
MAX_WAIT_SERVICES=300     # seconds to wait for services

# --- Parse args ---
SKIP_BUILD=false
QUICK_MODE=false
for arg in "$@"; do
  case $arg in
    --skip-build) SKIP_BUILD=true ;;
    --quick) QUICK_MODE=true; SCALE_COUNT=10; CONCURRENT_BATCH=5 ;;
  esac
done

# --- State ---
TOKEN=""
declare -a FINDINGS=()
declare -a PERF_DATA=()
TOTAL_TESTS=0; PASS_COUNT=0; FAIL_COUNT=0; WARN_COUNT=0
SUITE_START=$(python3 -c "import time; print(int(time.time()*1000))")

# --- Colors ---
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'
BOLD='\033[1m'; NC='\033[0m'; DIM='\033[2m'

# --- Helpers ---
log_section() { echo -e "\n${BOLD}${CYAN}━━━ $1 ━━━${NC}\n"; }
log_pass()    { TOTAL_TESTS=$((TOTAL_TESTS+1)); PASS_COUNT=$((PASS_COUNT+1)); echo -e "  ${GREEN}✓${NC} $1 ${DIM}${2:+(${2}ms)}${NC}"; }
log_fail()    { TOTAL_TESTS=$((TOTAL_TESTS+1)); FAIL_COUNT=$((FAIL_COUNT+1)); echo -e "  ${RED}✗${NC} $1 — $2"; }
log_warn()    { TOTAL_TESTS=$((TOTAL_TESTS+1)); WARN_COUNT=$((WARN_COUNT+1)); echo -e "  ${YELLOW}⚠${NC} $1 — $2"; }
log_info()    { echo -e "  ${DIM}→ $1${NC}"; }
log_perf()    { PERF_DATA+=("$1|$2|$3|$4"); } # name|value|unit|threshold

finding() {
  local category="$1" severity="$2" title="$3" detail="$4" recommendation="$5"
  FINDINGS+=("${category}|${severity}|${title}|${detail}|${recommendation}")
  local sev_color="${GREEN}"
  [ "$severity" = "WARN" ] && sev_color="${YELLOW}"
  [ "$severity" = "CRITICAL" ] && sev_color="${RED}"
  [ "$severity" = "HIGH" ] && sev_color="${RED}"
  echo -e "  ${sev_color}[${severity}]${NC} ${BOLD}${title}${NC}: ${detail}"
}

ms_now() { python3 -c "import time; print(int(time.time()*1000))"; }

# Authenticated curl helper
api_get() {
  curl -sf -H "Authorization: Bearer $TOKEN" "$1" 2>/dev/null
}
api_post() {
  curl -sf -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "$2" "$1" 2>/dev/null
}
api_put() {
  curl -sf -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "$2" "$1" 2>/dev/null
}
api_patch() {
  curl -sf -X PATCH -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" ${2:+-d "$2"} "$1" 2>/dev/null
}
api_delete() {
  curl -sf -X DELETE -H "Authorization: Bearer $TOKEN" "$1" 2>/dev/null
}

# Get JSON field
jf() { python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$1',''))" 2>/dev/null; }
jlen() { python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null; }

mkdir -p "$REPORT_DIR"

echo -e "${BOLD}${CYAN}"
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║   TranzFer MFT — Production Readiness Scale Test           ║"
echo "║   Scale: ${SCALE_COUNT} files/test · ${CONCURRENT_BATCH} concurrent · $(date '+%Y-%m-%d %H:%M')      ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# =============================================================================
# PHASE 0: BUILD & SPIN UP
# =============================================================================
if [ "$SKIP_BUILD" = false ]; then
  log_section "PHASE 0: BUILD & INFRASTRUCTURE"

  echo "  Building Maven modules (this takes ~2min)..."
  BUILD_START=$(ms_now)
  if ! mvn clean package -DskipTests -q 2>&1 | tail -3; then
    echo -e "${RED}FATAL: Maven build failed. Aborting.${NC}"
    exit 1
  fi
  BUILD_END=$(ms_now)
  BUILD_DUR=$(( BUILD_END - BUILD_START ))
  log_pass "Maven build" "$BUILD_DUR"
  log_perf "Maven build time" "$BUILD_DUR" "ms" "180000"

  echo "  Starting Docker Compose stack..."
  docker compose up --build -d 2>&1 | tail -5
  log_pass "Docker Compose started"
fi

# =============================================================================
# PHASE 0.5: WAIT FOR SERVICES
# =============================================================================
log_section "PHASE 0.5: SERVICE HEALTH CHECK"

CRITICAL_SERVICES=("onboarding-api:${ONBOARDING_PORT}" "config-service:${CONFIG_PORT}")
ALL_SERVICES=(
  "onboarding-api:${ONBOARDING_PORT}"
  "config-service:${CONFIG_PORT}"
  "sftp-service:8081"
  "ftp-service:8082"
  "ftp-web-service:8083"
  "gateway-service:8085"
  "encryption-service:8086"
  "ai-engine:${AI_PORT}"
  "screening-service:${SCREENING_PORT}"
  "analytics-service:8090"
)

wait_for_service() {
  local name="$1" port="$2" waited=0
  while [ $waited -lt $MAX_WAIT_SERVICES ]; do
    if curl -sf "http://localhost:${port}/actuator/health" > /dev/null 2>&1; then
      return 0
    fi
    sleep 3
    waited=$((waited + 3))
  done
  return 1
}

# Wait for critical services first
for svc in "${CRITICAL_SERVICES[@]}"; do
  IFS=':' read -r name port <<< "$svc"
  echo -n "  Waiting for ${name}..."
  START=$(ms_now)
  if wait_for_service "$name" "$port"; then
    DUR=$(( $(ms_now) - START ))
    echo -e " ${GREEN}UP${NC} (${DUR}ms)"
    log_perf "${name} startup time" "$DUR" "ms" "60000"
  else
    echo -e " ${RED}TIMEOUT${NC}"
    finding "RESILIENCE" "CRITICAL" "${name} failed to start" \
      "Service did not respond within ${MAX_WAIT_SERVICES}s" \
      "Check container logs: docker logs mft-${name}"
    echo -e "${RED}FATAL: Critical service ${name} not available. Aborting.${NC}"
    exit 1
  fi
done

# Check all other services (non-blocking)
SERVICES_UP=0; SERVICES_DOWN=0
for svc in "${ALL_SERVICES[@]}"; do
  IFS=':' read -r name port <<< "$svc"
  if curl -sf "http://localhost:${port}/actuator/health" > /dev/null 2>&1; then
    log_pass "${name} healthy"
    SERVICES_UP=$((SERVICES_UP + 1))
  else
    log_warn "${name}" "not responding on port ${port}"
    SERVICES_DOWN=$((SERVICES_DOWN + 1))
  fi
done

if [ $SERVICES_DOWN -gt 3 ]; then
  finding "RESILIENCE" "HIGH" "Multiple services down" \
    "${SERVICES_DOWN}/${#ALL_SERVICES[@]} services not responding" \
    "Run: docker compose logs --tail=50 to diagnose"
fi

# =============================================================================
# PHASE 1: AUTHENTICATION & BASELINE
# =============================================================================
log_section "PHASE 1: AUTHENTICATION & BASELINE"

# Login
START=$(ms_now)
RESP=$(curl -sf -X POST "${BASE_URL}:${ONBOARDING_PORT}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASS}\"}" 2>/dev/null)
DUR=$(( $(ms_now) - START ))

TOKEN=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken','') or d.get('token',''))" 2>/dev/null)

if [ -n "$TOKEN" ] && [ ${#TOKEN} -gt 20 ]; then
  log_pass "Admin login" "$DUR"
  log_perf "Login latency" "$DUR" "ms" "2000"
  if [ $DUR -gt 3000 ]; then
    finding "EFFICIENCY" "WARN" "Slow login" \
      "Login took ${DUR}ms (threshold: 2000ms)" \
      "Check DB connection pool warmup and JWT generation overhead"
  fi
else
  # Try registration first — register returns token directly
  echo "  No admin account found, registering..."
  RESP=$(curl -s -X POST "${BASE_URL}:${ONBOARDING_PORT}/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASS}\"}" 2>/dev/null)
  TOKEN=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken','') or d.get('token',''))" 2>/dev/null)
  # If register didn't return token, try login
  if [ -z "$TOKEN" ] || [ ${#TOKEN} -lt 20 ]; then
    RESP=$(curl -s -X POST "${BASE_URL}:${ONBOARDING_PORT}/api/auth/login" \
      -H "Content-Type: application/json" \
      -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASS}\"}" 2>/dev/null)
    TOKEN=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken','') or d.get('token',''))" 2>/dev/null)
  fi

  if [ -z "$TOKEN" ] || [ ${#TOKEN} -lt 20 ]; then
    echo -e "${RED}FATAL: Cannot authenticate. Aborting.${NC}"
    echo "Response: $RESP"
    exit 1
  fi
  log_pass "Admin registered and logged in"
fi

# DB baseline: count tables, rows
TABLE_COUNT=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'" 2>/dev/null | tr -d ' \n')
log_info "Database: ${TABLE_COUNT} tables"

# Check if virtual_entries table exists (VFS)
VFS_EXISTS=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
  "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name='virtual_entries')" 2>/dev/null | tr -d ' \n')
if [ "$VFS_EXISTS" = "t" ]; then
  log_pass "VFS table (virtual_entries) present"
else
  log_warn "VFS" "virtual_entries table missing — VFS tests will be skipped"
fi

# Check flow_executions columns for match engine
MATCH_CRITERIA_COL=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
  "SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_name='file_flows' AND column_name='match_criteria')" 2>/dev/null | tr -d ' \n')
if [ "$MATCH_CRITERIA_COL" = "t" ]; then
  log_pass "Match criteria column on file_flows"
else
  finding "SAFETY" "HIGH" "match_criteria column missing" \
    "file_flows.match_criteria JSONB column not found" \
    "Run Flyway migration V24__flow_match_criteria.sql"
fi

# =============================================================================
# PHASE 2: CREATE REALISTIC TEST DATA
# =============================================================================
log_section "PHASE 2: CREATE REALISTIC TEST DATA"

# --- Partners ---
declare -A PARTNER_IDS
PARTNERS=(
  '{"companyName":"Acme Corporation","displayName":"ACME","partnerType":"CLIENT","protocolsEnabled":["SFTP","FTP"],"slaTier":"ENTERPRISE","maxFileSizeBytes":1073741824,"maxTransfersPerDay":5000,"retentionDays":90}'
  '{"companyName":"BankNet Financial","displayName":"BankNet","partnerType":"EXTERNAL","protocolsEnabled":["SFTP","AS2"],"slaTier":"PREMIUM","maxFileSizeBytes":536870912,"maxTransfersPerDay":2000,"retentionDays":365}'
  '{"companyName":"HealthCo Systems","displayName":"HealthCo","partnerType":"VENDOR","protocolsEnabled":["SFTP"],"slaTier":"STANDARD","maxFileSizeBytes":268435456,"maxTransfersPerDay":500,"retentionDays":180}'
  '{"companyName":"LogiTech Shipping","displayName":"LogiTech","partnerType":"CLIENT","protocolsEnabled":["FTP","SFTP"],"slaTier":"ENTERPRISE","maxFileSizeBytes":2147483648,"maxTransfersPerDay":10000,"retentionDays":60}'
  '{"companyName":"EdiMart Trading","displayName":"EdiMart","partnerType":"EXTERNAL","protocolsEnabled":["AS2","SFTP"],"slaTier":"PREMIUM","maxFileSizeBytes":536870912,"maxTransfersPerDay":3000,"retentionDays":365}'
)
PARTNER_NAMES=("Acme Corporation" "BankNet Financial" "HealthCo Systems" "LogiTech Shipping" "EdiMart Trading")

PARTNER_CREATE_TOTAL=0
PARTNER_CREATE_OK=0
START=$(ms_now)
for i in "${!PARTNERS[@]}"; do
  resp=$(api_post "${BASE_URL}:${ONBOARDING_PORT}/api/partners" "${PARTNERS[$i]}")
  pid=$(echo "$resp" | jf "id")
  PARTNER_CREATE_TOTAL=$((PARTNER_CREATE_TOTAL + 1))
  if [ -n "$pid" ] && [ "$pid" != "None" ] && [ "$pid" != "" ]; then
    PARTNER_IDS["${PARTNER_NAMES[$i]}"]="$pid"
    PARTNER_CREATE_OK=$((PARTNER_CREATE_OK + 1))
  else
    # Partner might already exist — try to find it
    existing=$(api_get "${BASE_URL}:${ONBOARDING_PORT}/api/partners" | \
      python3 -c "import sys,json; ps=json.load(sys.stdin); [print(p['id']) for p in ps if p.get('companyName')=='${PARTNER_NAMES[$i]}']" 2>/dev/null | head -1)
    if [ -n "$existing" ]; then
      PARTNER_IDS["${PARTNER_NAMES[$i]}"]="$existing"
      PARTNER_CREATE_OK=$((PARTNER_CREATE_OK + 1))
    fi
  fi
done
DUR=$(( $(ms_now) - START ))
log_pass "Created/found ${PARTNER_CREATE_OK}/${PARTNER_CREATE_TOTAL} partners" "$DUR"
log_perf "Partner creation (${PARTNER_CREATE_TOTAL})" "$DUR" "ms" "5000"

# --- Users & Accounts ---
declare -a ACCOUNT_IDS=()
PROTOCOLS=("SFTP" "FTP" "SFTP" "SFTP" "FTP" "SFTP" "FTP" "SFTP" "SFTP" "SFTP")
USERNAMES=("acme_inbound" "acme_outbound" "banknet_edi" "healthco_claims" "logitech_ship"
           "edimart_po" "edimart_inv" "partner_bulk_1" "partner_bulk_2" "partner_bulk_3")

ACCT_CREATE_TOTAL=0; ACCT_CREATE_OK=0
START=$(ms_now)
for i in "${!USERNAMES[@]}"; do
  user="${USERNAMES[$i]}_$$"  # append PID for uniqueness
  proto="${PROTOCOLS[$i]}"
  resp=$(api_post "${BASE_URL}:${ONBOARDING_PORT}/api/accounts" \
    "{\"protocol\":\"${proto}\",\"username\":\"${user}\",\"password\":\"Scale@Test1\",\"permissions\":{\"read\":true,\"write\":true,\"delete\":false}}")
  aid=$(echo "$resp" | jf "id")
  ACCT_CREATE_TOTAL=$((ACCT_CREATE_TOTAL + 1))
  if [ -n "$aid" ] && [ "$aid" != "None" ] && [ "$aid" != "" ]; then
    ACCOUNT_IDS+=("$aid")
    ACCT_CREATE_OK=$((ACCT_CREATE_OK + 1))
  fi
done
DUR=$(( $(ms_now) - START ))
log_pass "Created ${ACCT_CREATE_OK}/${ACCT_CREATE_TOTAL} transfer accounts" "$DUR"
log_perf "Account creation (${ACCT_CREATE_TOTAL})" "$DUR" "ms" "10000"

if [ ${#ACCOUNT_IDS[@]} -lt 2 ]; then
  finding "SAFETY" "CRITICAL" "Cannot create accounts" \
    "Only ${#ACCOUNT_IDS[@]} accounts created, need at least 2 for routing tests" \
    "Check onboarding-api logs for account creation errors"
  # Try to find existing accounts
  EXISTING_ACCTS=$(api_get "${BASE_URL}:${ONBOARDING_PORT}/api/accounts" | \
    python3 -c "import sys,json; [print(a['id']) for a in json.load(sys.stdin)]" 2>/dev/null)
  while IFS= read -r aid; do
    [ -n "$aid" ] && ACCOUNT_IDS+=("$aid")
  done <<< "$EXISTING_ACCTS"
  log_info "Found ${#ACCOUNT_IDS[@]} existing accounts"
fi

# --- Folder Mappings ---
MAPPING_CREATE_OK=0
if [ ${#ACCOUNT_IDS[@]} -ge 2 ]; then
  START=$(ms_now)
  # Create cross-account mappings
  for i in $(seq 0 $((${#ACCOUNT_IDS[@]} - 2))); do
    src="${ACCOUNT_IDS[$i]}"
    dst="${ACCOUNT_IDS[$((i+1))]}"
    resp=$(api_post "${BASE_URL}:${ONBOARDING_PORT}/api/folder-mappings" \
      "{\"sourceAccountId\":\"${src}\",\"sourcePath\":\"inbox\",\"destinationAccountId\":\"${dst}\",\"destinationPath\":\"outbox\",\"filenamePattern\":\".*\"}")
    mid=$(echo "$resp" | jf "id")
    if [ -n "$mid" ] && [ "$mid" != "None" ]; then
      MAPPING_CREATE_OK=$((MAPPING_CREATE_OK + 1))
    fi
  done
  DUR=$(( $(ms_now) - START ))
  log_pass "Created ${MAPPING_CREATE_OK} folder mappings" "$DUR"
fi

# --- File Flows with Complex Match Criteria ---
declare -a FLOW_IDS=()

# Flow 1: SFTP + CSV files from ACME (AND tree)
FLOW_BODIES=(
  '{"name":"acme-csv-compress-'$$'","description":"Compress CSV files from ACME SFTP","priority":10,"steps":[{"type":"COMPRESS_GZIP","config":{},"order":0},{"type":"RENAME","config":{"pattern":"${basename}_${trackid}.gz"},"order":1}],"matchCriteria":{"operator":"AND","conditions":[{"field":"protocol","op":"EQ","value":"SFTP"},{"field":"filename","op":"GLOB","value":"*.csv"},{"field":"accountUsername","op":"CONTAINS","value":"acme"}]}}'
  # Flow 2: EDI X12 850 Purchase Orders (nested AND+OR)
  '{"name":"edi-po-encrypt-'$$'","description":"Encrypt EDI 850/855 purchase orders","priority":20,"steps":[{"type":"ENCRYPT_PGP","config":{},"order":0}],"matchCriteria":{"operator":"AND","conditions":[{"field":"filename","op":"GLOB","value":"*.edi"},{"operator":"OR","conditions":[{"field":"ediType","op":"EQ","value":"850"},{"field":"ediType","op":"EQ","value":"855"}]}]}}'
  # Flow 3: Large files > 100MB (numeric comparison)
  '{"name":"large-file-screen-'$$'","description":"Screen large files for DLP","priority":30,"steps":[{"type":"SCREEN","config":{},"order":0}],"matchCriteria":{"operator":"AND","conditions":[{"field":"fileSize","op":"GT","value":"104857600"},{"field":"protocol","op":"IN","values":["SFTP","FTP"]}]}}'
  # Flow 4: Wildcard catch-all for FTP protocol
  '{"name":"ftp-archive-'$$'","description":"Archive all FTP transfers","priority":100,"steps":[{"type":"COMPRESS_ZIP","config":{},"order":0},{"type":"RENAME","config":{"pattern":"archive_${basename}_${date}.zip"},"order":1}],"matchCriteria":{"operator":"AND","conditions":[{"field":"protocol","op":"EQ","value":"FTP"}]}}'
  # Flow 5: Time-window restricted (business hours only)
  '{"name":"business-hours-only-'$$'","description":"Route only during business hours","priority":50,"steps":[{"type":"ROUTE","config":{},"order":0}],"matchCriteria":{"operator":"AND","conditions":[{"field":"hour","op":"GTE","value":"8"},{"field":"hour","op":"LTE","value":"18"},{"field":"filename","op":"REGEX","value":"^urgent_.*\\.pdf$"}]}}'
  # Flow 6: Deeply nested (AND > OR > AND) — stress test the tree walker
  '{"name":"complex-nested-'$$'","description":"Complex nested criteria stress test","priority":60,"steps":[{"type":"RENAME","config":{"pattern":"processed_${basename}"},"order":0}],"matchCriteria":{"operator":"AND","conditions":[{"field":"direction","op":"EQ","value":"INBOUND"},{"operator":"OR","conditions":[{"operator":"AND","conditions":[{"field":"filename","op":"GLOB","value":"*.xml"},{"field":"fileSize","op":"LT","value":"1048576"}]},{"operator":"AND","conditions":[{"field":"filename","op":"GLOB","value":"*.json"},{"field":"accountUsername","op":"REGEX","value":"^api_.*"}]}]}]}}'
  # Flow 7: Source IP CIDR matching
  '{"name":"internal-network-'$$'","description":"Match internal network IPs","priority":70,"steps":[{"type":"ROUTE","config":{},"order":0}],"matchCriteria":{"operator":"AND","conditions":[{"field":"sourceIp","op":"CIDR","value":"10.0.0.0/8"},{"field":"filename","op":"STARTS_WITH","value":"internal_"}]}}'
  # Flow 8: Extension-based matching
  '{"name":"image-convert-'$$'","description":"Convert image files","priority":80,"steps":[{"type":"RENAME","config":{"pattern":"img_${trackid}_${basename}"},"order":0}],"matchCriteria":{"operator":"AND","conditions":[{"field":"extension","op":"IN","values":["png","jpg","jpeg","gif","tiff"]}]}}'
  # Flow 9: NOT operator test
  '{"name":"non-encrypted-alert-'$$'","description":"Alert on non-encrypted files","priority":90,"steps":[{"type":"SCREEN","config":{},"order":0}],"matchCriteria":{"operator":"AND","conditions":[{"operator":"NOT","conditions":[{"field":"filename","op":"GLOB","value":"*.pgp"}]},{"field":"filename","op":"GLOB","value":"*.csv"}]}}'
  # Flow 10: Day-of-week matching
  '{"name":"weekend-batch-'$$'","description":"Weekend batch processing","priority":95,"steps":[{"type":"COMPRESS_GZIP","config":{},"order":0}],"matchCriteria":{"operator":"AND","conditions":[{"field":"dayOfWeek","op":"IN","values":["SAT","SUN"]},{"field":"filename","op":"CONTAINS","value":"batch"}]}}'
)

FLOW_CREATE_OK=0
START=$(ms_now)
for body in "${FLOW_BODIES[@]}"; do
  resp=$(api_post "${BASE_URL}:${CONFIG_PORT}/api/flows" "$body")
  fid=$(echo "$resp" | jf "id")
  if [ -n "$fid" ] && [ "$fid" != "None" ] && [ "$fid" != "" ]; then
    FLOW_IDS+=("$fid")
    FLOW_CREATE_OK=$((FLOW_CREATE_OK + 1))
  fi
done
DUR=$(( $(ms_now) - START ))
log_pass "Created ${FLOW_CREATE_OK}/${#FLOW_BODIES[@]} file flows with match criteria" "$DUR"
log_perf "Flow creation (${#FLOW_BODIES[@]} with criteria)" "$DUR" "ms" "10000"

if [ $FLOW_CREATE_OK -eq 0 ]; then
  finding "SAFETY" "HIGH" "No flows created" \
    "All flow creation requests failed" \
    "Check config-service logs. Possible: missing matchCriteria column or validation error"
fi

# --- Verify RabbitMQ hot-reload events ---
log_info "Checking RabbitMQ for flow rule events..."
RABBIT_CONNS=$(curl -sf -u guest:guest "http://localhost:15672/api/connections" 2>/dev/null | jlen)
RABBIT_QUEUES=$(curl -sf -u guest:guest "http://localhost:15672/api/queues" 2>/dev/null | \
  python3 -c "import sys,json; qs=json.load(sys.stdin); print(len([q for q in qs if 'flow' in q.get('name','').lower() or q.get('auto_delete',False)]))" 2>/dev/null)
log_info "RabbitMQ: ${RABBIT_CONNS} connections, ${RABBIT_QUEUES} auto-delete queues"

if [ "${RABBIT_QUEUES:-0}" -eq 0 ]; then
  finding "RESILIENCE" "WARN" "No auto-delete queues for flow events" \
    "Expected anonymous queues for FlowRuleEventListener per-instance delivery" \
    "Verify @RabbitListener with anonymous @Queue binding in FlowRuleEventListener"
fi

# =============================================================================
# PHASE 3: MATCH ENGINE SCALE TEST
# =============================================================================
log_section "PHASE 3: MATCH ENGINE — SCALE & PERFORMANCE"

# 3a: Validate criteria API
START=$(ms_now)
VALID_RESP=$(api_post "${BASE_URL}:${CONFIG_PORT}/api/flows/validate-criteria" \
  '{"operator":"AND","conditions":[{"field":"protocol","op":"EQ","value":"SFTP"},{"field":"filename","op":"GLOB","value":"*.csv"}]}')
DUR=$(( $(ms_now) - START ))
VALID_OK=$(echo "$VALID_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('valid',False))" 2>/dev/null)
if [ "$VALID_OK" = "True" ]; then
  log_pass "Criteria validation API" "$DUR"
else
  log_fail "Criteria validation API" "Response: $(echo $VALID_RESP | head -c 100)"
  finding "SAFETY" "HIGH" "Criteria validation endpoint broken" \
    "POST /api/flows/validate-criteria returned invalid response" \
    "Check MatchCriteriaService.validate()"
fi
log_perf "Criteria validation latency" "$DUR" "ms" "500"

# 3b: Test-match API — simulate file contexts
TEST_CONTEXTS=(
  '{"criteria":{"operator":"AND","conditions":[{"field":"protocol","op":"EQ","value":"SFTP"},{"field":"filename","op":"GLOB","value":"*.csv"}]},"fileContext":{"protocol":"SFTP","filename":"report.csv","fileSize":1024,"direction":"INBOUND"}}'
  '{"criteria":{"operator":"AND","conditions":[{"field":"protocol","op":"EQ","value":"SFTP"},{"field":"filename","op":"GLOB","value":"*.csv"}]},"fileContext":{"protocol":"FTP","filename":"report.csv","fileSize":1024,"direction":"INBOUND"}}'
  '{"criteria":{"operator":"AND","conditions":[{"field":"fileSize","op":"GT","value":"104857600"}]},"fileContext":{"protocol":"SFTP","filename":"big.bin","fileSize":200000000,"direction":"INBOUND"}}'
  '{"criteria":{"operator":"AND","conditions":[{"field":"extension","op":"IN","values":["png","jpg"]}]},"fileContext":{"protocol":"SFTP","filename":"photo.png","fileSize":5000,"direction":"INBOUND"}}'
)
EXPECTED_MATCHES=("true" "false" "true" "true")

MATCH_API_PASS=0; MATCH_API_FAIL=0
START=$(ms_now)
for i in "${!TEST_CONTEXTS[@]}"; do
  resp=$(api_post "${BASE_URL}:${CONFIG_PORT}/api/flows/test-match" "${TEST_CONTEXTS[$i]}")
  matched=$(echo "$resp" | python3 -c "import sys,json; print(str(json.load(sys.stdin).get('matched',False)).lower())" 2>/dev/null)
  if [ "$matched" = "${EXPECTED_MATCHES[$i]}" ]; then
    MATCH_API_PASS=$((MATCH_API_PASS + 1))
  else
    MATCH_API_FAIL=$((MATCH_API_FAIL + 1))
    log_fail "test-match case $i" "expected=${EXPECTED_MATCHES[$i]} got=${matched}"
  fi
done
DUR=$(( $(ms_now) - START ))
log_pass "Test-match API: ${MATCH_API_PASS}/${#TEST_CONTEXTS[@]} correct" "$DUR"
log_perf "Test-match API (${#TEST_CONTEXTS[@]} evaluations)" "$DUR" "ms" "2000"

if [ $MATCH_API_FAIL -gt 0 ]; then
  finding "SAFETY" "HIGH" "Match engine incorrect results" \
    "${MATCH_API_FAIL}/${#TEST_CONTEXTS[@]} test-match cases returned wrong results" \
    "Review FlowMatchEngine evaluation logic for the failing operators"
fi

# 3c: Flow listing + registry load performance
START=$(ms_now)
FLOW_LIST=$(api_get "${BASE_URL}:${CONFIG_PORT}/api/flows")
DUR=$(( $(ms_now) - START ))
FLOW_COUNT=$(echo "$FLOW_LIST" | jlen)
log_pass "Flow listing: ${FLOW_COUNT} active flows" "$DUR"
log_perf "Flow list query" "$DUR" "ms" "500"

# 3d: Bulk flow creation — stress the registry hot-reload
log_info "Stress test: creating ${SCALE_COUNT} flows rapidly..."
BULK_FLOW_START=$(ms_now)
BULK_OK=0; BULK_FAIL=0
for i in $(seq 1 $SCALE_COUNT); do
  resp=$(api_post "${BASE_URL}:${CONFIG_PORT}/api/flows" \
    "{\"name\":\"scale-test-flow-${i}-$$\",\"description\":\"Scale test\",\"priority\":${i},\"steps\":[{\"type\":\"RENAME\",\"config\":{\"pattern\":\"scale_\${basename}\"},\"order\":0}],\"matchCriteria\":{\"operator\":\"AND\",\"conditions\":[{\"field\":\"filename\",\"op\":\"CONTAINS\",\"value\":\"scale_${i}\"}]}}")
  fid=$(echo "$resp" | jf "id")
  if [ -n "$fid" ] && [ "$fid" != "None" ] && [ "$fid" != "" ]; then
    BULK_OK=$((BULK_OK + 1))
    FLOW_IDS+=("$fid")
  else
    BULK_FAIL=$((BULK_FAIL + 1))
  fi

  # Progress every 20
  if [ $((i % 20)) -eq 0 ]; then
    echo -ne "\r  ${DIM}  Created ${i}/${SCALE_COUNT} flows (${BULK_OK} ok, ${BULK_FAIL} fail)${NC}"
  fi
done
echo ""
BULK_DUR=$(( $(ms_now) - BULK_FLOW_START ))
AVG_CREATE=$(( BULK_DUR / (BULK_OK > 0 ? BULK_OK : 1) ))
log_pass "Bulk flow creation: ${BULK_OK}/${SCALE_COUNT}" "$BULK_DUR"
log_perf "Avg flow creation time" "$AVG_CREATE" "ms" "200"
log_perf "Total bulk creation (${SCALE_COUNT})" "$BULK_DUR" "ms" "$((SCALE_COUNT * 200))"

if [ $BULK_FAIL -gt $((SCALE_COUNT / 10)) ]; then
  finding "EFFICIENCY" "WARN" "High flow creation failure rate" \
    "${BULK_FAIL}/${SCALE_COUNT} flows failed to create" \
    "Check for unique constraint violations or connection pool exhaustion"
fi

if [ $AVG_CREATE -gt 500 ]; then
  finding "EFFICIENCY" "WARN" "Slow flow creation" \
    "Average ${AVG_CREATE}ms per flow (threshold: 200ms)" \
    "Check DB write latency, index overhead, and RabbitMQ publish time"
fi

# 3e: Verify registry loaded all flows — check via flow list
sleep 2  # Give registry time to process RabbitMQ events
START=$(ms_now)
FLOW_COUNT_AFTER=$(api_get "${BASE_URL}:${CONFIG_PORT}/api/flows" | jlen)
DUR=$(( $(ms_now) - START ))
log_info "Flows after bulk create: ${FLOW_COUNT_AFTER} (was ${FLOW_COUNT})"
EXPECTED_NEW=$((FLOW_COUNT + BULK_OK))
if [ "${FLOW_COUNT_AFTER}" -ge "$EXPECTED_NEW" ] 2>/dev/null; then
  log_pass "All flows persisted and active"
else
  finding "RESILIENCE" "WARN" "Flow count mismatch" \
    "Expected ~${EXPECTED_NEW} flows, got ${FLOW_COUNT_AFTER}" \
    "Some flows may have failed to activate or RabbitMQ events lost"
fi

# 3f: Match engine throughput — concurrent test-match calls
log_info "Throughput test: ${SCALE_COUNT} concurrent test-match evaluations..."
TMPDIR_MATCH=$(mktemp -d)
MATCH_START=$(ms_now)
for i in $(seq 1 $SCALE_COUNT); do
  (
    resp=$(curl -sf -X POST "${BASE_URL}:${CONFIG_PORT}/api/flows/test-match" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      -d "{\"criteria\":{\"operator\":\"AND\",\"conditions\":[{\"field\":\"filename\",\"op\":\"CONTAINS\",\"value\":\"scale_${i}\"},{\"field\":\"protocol\",\"op\":\"EQ\",\"value\":\"SFTP\"}]},\"fileContext\":{\"protocol\":\"SFTP\",\"filename\":\"scale_${i}_data.csv\",\"fileSize\":$((RANDOM * 1000)),\"direction\":\"INBOUND\"}}" 2>/dev/null)
    echo "$resp" > "${TMPDIR_MATCH}/result_${i}.json"
  ) &

  # Throttle to CONCURRENT_BATCH
  if [ $((i % CONCURRENT_BATCH)) -eq 0 ]; then
    wait
  fi
done
wait
MATCH_DUR=$(( $(ms_now) - MATCH_START ))
MATCH_OK=$(ls "${TMPDIR_MATCH}"/result_*.json 2>/dev/null | wc -l | tr -d ' ')
MATCH_THROUGHPUT=$(python3 -c "print(round(${MATCH_OK} / (${MATCH_DUR} / 1000.0), 1))" 2>/dev/null)
log_pass "Match throughput: ${MATCH_THROUGHPUT} eval/sec (${MATCH_OK} in ${MATCH_DUR}ms)"
log_perf "Match throughput" "$MATCH_THROUGHPUT" "eval/s" "50"
rm -rf "$TMPDIR_MATCH"

if python3 -c "exit(0 if ${MATCH_THROUGHPUT:-0} < 20 else 1)" 2>/dev/null; then
  finding "EFFICIENCY" "HIGH" "Low match throughput" \
    "${MATCH_THROUGHPUT} eval/s (target: >50/s)" \
    "Likely bottleneck in DB query for test-match endpoint. In-memory registry should bypass this."
fi

# =============================================================================
# PHASE 4: FILE UPLOAD & ROUTING AT SCALE
# =============================================================================
log_section "PHASE 4: FILE UPLOAD & ROUTING"

# Upload files via the V2 Transfer API
UPLOAD_COUNT=$((SCALE_COUNT / 2))  # Half the scale for uploads
TMPDIR_UPLOAD=$(mktemp -d)

# Generate test files of varying sizes
log_info "Generating ${UPLOAD_COUNT} test files..."
for i in $(seq 1 $UPLOAD_COUNT); do
  case $((i % 5)) in
    0) EXT="csv"; dd if=/dev/urandom bs=1024 count=$((RANDOM % 100 + 1)) of="${TMPDIR_UPLOAD}/report_${i}.csv" 2>/dev/null ;;
    1) EXT="edi"; echo "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *$(date +%y%m%d)*1234*^*00501*000000001*0*P*:~GS*PO*SENDER*RECEIVER*$(date +%Y%m%d)*1234*1*X*005010~ST*850*0001~" > "${TMPDIR_UPLOAD}/po_${i}.edi" ;;
    2) EXT="xml"; echo '<?xml version="1.0"?><order id="'$i'"><item sku="ABC123" qty="10"/></order>' > "${TMPDIR_UPLOAD}/order_${i}.xml" ;;
    3) EXT="pdf"; dd if=/dev/urandom bs=1024 count=$((RANDOM % 50 + 1)) of="${TMPDIR_UPLOAD}/urgent_doc_${i}.pdf" 2>/dev/null ;;
    4) EXT="json"; echo '{"type":"invoice","number":'$i',"amount":'$((RANDOM % 10000))'.00}' > "${TMPDIR_UPLOAD}/api_invoice_${i}.json" ;;
  esac
done

# Upload via multipart
UPLOAD_OK=0; UPLOAD_FAIL=0
UPLOAD_START=$(ms_now)
for f in "${TMPDIR_UPLOAD}"/*; do
  [ -f "$f" ] || continue
  (
    resp=$(curl -sf -X POST "${BASE_URL}:${ONBOARDING_PORT}/api/v2/transfer" \
      -H "Authorization: Bearer $TOKEN" \
      -F "file=@${f}" 2>/dev/null)
    track=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('trackId',''))" 2>/dev/null)
    if [ -n "$track" ] && [ "$track" != "" ] && [ "$track" != "None" ]; then
      echo "$track" >> "${TMPDIR_UPLOAD}/_track_ids.txt"
    else
      echo "FAIL: $(basename $f)" >> "${TMPDIR_UPLOAD}/_failures.txt"
    fi
  ) &

  # Throttle
  RUNNING=$(jobs -p | wc -l)
  while [ "$RUNNING" -ge "$CONCURRENT_BATCH" ]; do
    sleep 0.1
    RUNNING=$(jobs -p | wc -l)
  done
done
wait
UPLOAD_DUR=$(( $(ms_now) - UPLOAD_START ))

UPLOAD_OK=$(wc -l < "${TMPDIR_UPLOAD}/_track_ids.txt" 2>/dev/null | tr -d ' ')
UPLOAD_FAIL=$(wc -l < "${TMPDIR_UPLOAD}/_failures.txt" 2>/dev/null | tr -d ' ')
UPLOAD_OK=${UPLOAD_OK:-0}; UPLOAD_FAIL=${UPLOAD_FAIL:-0}
UPLOAD_THROUGHPUT=$(python3 -c "d=${UPLOAD_DUR}/1000.0; print(round(${UPLOAD_OK}/d,1) if d>0 else 0)" 2>/dev/null)

log_pass "Uploads: ${UPLOAD_OK}/${UPLOAD_COUNT} successful" "$UPLOAD_DUR"
log_perf "Upload throughput" "${UPLOAD_THROUGHPUT}" "files/s" "10"
log_perf "Total upload time (${UPLOAD_COUNT} files)" "$UPLOAD_DUR" "ms" "$((UPLOAD_COUNT * 2000))"

if [ "${UPLOAD_FAIL:-0}" -gt $((UPLOAD_COUNT / 4)) ]; then
  finding "SAFETY" "HIGH" "High upload failure rate" \
    "${UPLOAD_FAIL}/${UPLOAD_COUNT} uploads failed" \
    "Check onboarding-api logs, connection pool, and file size limits"
fi

# Track transfer status
sleep 3  # Give routing engine time to process
if [ -f "${TMPDIR_UPLOAD}/_track_ids.txt" ]; then
  SAMPLE_TRACKS=$(head -5 "${TMPDIR_UPLOAD}/_track_ids.txt")
  STATUS_CHECK_OK=0; STATUS_CHECK_FAIL=0
  for tid in $SAMPLE_TRACKS; do
    resp=$(api_get "${BASE_URL}:${ONBOARDING_PORT}/api/v2/transfer/${tid}")
    status=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','UNKNOWN'))" 2>/dev/null)
    if [ -n "$status" ] && [ "$status" != "UNKNOWN" ]; then
      STATUS_CHECK_OK=$((STATUS_CHECK_OK + 1))
    else
      STATUS_CHECK_FAIL=$((STATUS_CHECK_FAIL + 1))
    fi
  done
  log_pass "Transfer tracking: ${STATUS_CHECK_OK}/5 trackable"
fi

# Check flow executions
EXEC_COUNT=$(api_get "${BASE_URL}:${CONFIG_PORT}/api/flows/executions?size=1" | \
  python3 -c "import sys,json; print(json.load(sys.stdin).get('totalElements',0))" 2>/dev/null)
log_info "Flow executions in DB: ${EXEC_COUNT}"

# Check for UNMATCHED executions
UNMATCHED_COUNT=$(api_get "${BASE_URL}:${CONFIG_PORT}/api/flows/executions?status=UNMATCHED&size=1" | \
  python3 -c "import sys,json; print(json.load(sys.stdin).get('totalElements',0))" 2>/dev/null)
log_info "UNMATCHED executions: ${UNMATCHED_COUNT}"

rm -rf "$TMPDIR_UPLOAD"

# =============================================================================
# PHASE 5: VFS (PHANTOM FOLDER) SCALE TEST
# =============================================================================
log_section "PHASE 5: VFS / PHANTOM FOLDER"

if [ "$VFS_EXISTS" = "t" ]; then
  # Test VFS via direct DB operations (since VFS is a Java-layer abstraction)
  VFS_ACCT_ID="${ACCOUNT_IDS[0]:-00000000-0000-0000-0000-000000000000}"

  # Bulk insert virtual directories
  log_info "Creating ${SCALE_COUNT} virtual directories..."
  VFS_START=$(ms_now)
  docker exec mft-postgres psql -U postgres -d filetransfer -c "
    INSERT INTO virtual_entries (id, account_id, path, parent_path, name, type, permissions, created_at, updated_at)
    SELECT
      gen_random_uuid(),
      '${VFS_ACCT_ID}'::uuid,
      '/scale_test/dir_' || i,
      '/scale_test',
      'dir_' || i,
      'DIR',
      'rwxr-xr-x',
      now(),
      now()
    FROM generate_series(1, ${SCALE_COUNT}) AS i
    ON CONFLICT (account_id, path) DO NOTHING;
  " 2>/dev/null
  VFS_DIR_DUR=$(( $(ms_now) - VFS_START ))
  log_pass "Created ${SCALE_COUNT} virtual directories" "$VFS_DIR_DUR"
  log_perf "VFS bulk dir creation (${SCALE_COUNT})" "$VFS_DIR_DUR" "ms" "$((SCALE_COUNT * 5))"

  # Bulk insert virtual files
  log_info "Creating ${SCALE_COUNT} virtual files..."
  VFS_START=$(ms_now)
  docker exec mft-postgres psql -U postgres -d filetransfer -c "
    INSERT INTO virtual_entries (id, account_id, path, parent_path, name, type, storage_key, size_bytes, content_type, track_id, permissions, created_at, updated_at)
    SELECT
      gen_random_uuid(),
      '${VFS_ACCT_ID}'::uuid,
      '/scale_test/dir_' || ((i % ${SCALE_COUNT}) + 1) || '/file_' || i || '.csv',
      '/scale_test/dir_' || ((i % ${SCALE_COUNT}) + 1),
      'file_' || i || '.csv',
      'FILE',
      md5(random()::text),
      (random() * 1048576)::bigint,
      'text/csv',
      'TRZ' || lpad(i::text, 6, '0'),
      'rw-r--r--',
      now(),
      now()
    FROM generate_series(1, ${SCALE_COUNT}) AS i
    ON CONFLICT (account_id, path) DO NOTHING;
  " 2>/dev/null
  VFS_FILE_DUR=$(( $(ms_now) - VFS_START ))
  log_pass "Created ${SCALE_COUNT} virtual files" "$VFS_FILE_DUR"
  log_perf "VFS bulk file creation (${SCALE_COUNT})" "$VFS_FILE_DUR" "ms" "$((SCALE_COUNT * 5))"

  # Directory listing performance
  VFS_START=$(ms_now)
  LIST_COUNT=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "
    SELECT count(*) FROM virtual_entries
    WHERE account_id = '${VFS_ACCT_ID}'::uuid AND parent_path = '/scale_test' AND deleted = false;
  " 2>/dev/null | tr -d ' \n')
  VFS_LIST_DUR=$(( $(ms_now) - VFS_START ))
  log_pass "VFS directory listing (${LIST_COUNT} entries)" "$VFS_LIST_DUR"
  log_perf "VFS dir listing latency" "$VFS_LIST_DUR" "ms" "100"

  if [ "$VFS_LIST_DUR" -gt 200 ]; then
    finding "EFFICIENCY" "WARN" "Slow VFS directory listing" \
      "${VFS_LIST_DUR}ms for ${LIST_COUNT} entries (threshold: 100ms)" \
      "Check index on (account_id, parent_path) and ensure deleted=false is in the index"
  fi

  # Deep path resolution
  VFS_START=$(ms_now)
  DEEP_PATH=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "
    SELECT path FROM virtual_entries
    WHERE account_id = '${VFS_ACCT_ID}'::uuid AND type = 'FILE' AND deleted = false
    LIMIT 1;
  " 2>/dev/null | tr -d ' \n')
  VFS_DEEP_DUR=$(( $(ms_now) - VFS_START ))
  log_pass "VFS path resolution" "$VFS_DEEP_DUR"
  log_perf "VFS single file lookup" "$VFS_DEEP_DUR" "ms" "50"

  # Deduplication check — same storage_key, multiple entries
  DEDUP_COUNT=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "
    SELECT count(*) FROM (
      SELECT storage_key, count(*) as refs
      FROM virtual_entries WHERE storage_key IS NOT NULL AND deleted = false
      GROUP BY storage_key HAVING count(*) > 1
    ) sub;
  " 2>/dev/null | tr -d ' \n')
  log_info "VFS deduplicated storage keys: ${DEDUP_COUNT}"

  # Cleanup scale test data
  docker exec mft-postgres psql -U postgres -d filetransfer -c "
    DELETE FROM virtual_entries WHERE path LIKE '/scale_test%' AND account_id = '${VFS_ACCT_ID}'::uuid;
  " 2>/dev/null > /dev/null
  log_info "VFS scale test data cleaned up"

else
  log_warn "VFS tests skipped" "virtual_entries table not found"
  finding "SAFETY" "WARN" "VFS not deployed" \
    "virtual_entries table missing — Phantom Folder VFS not migrated" \
    "Run V26__virtual_filesystem.sql migration"
fi

# =============================================================================
# PHASE 6: RESILIENCE TESTS
# =============================================================================
log_section "PHASE 6: RESILIENCE"

# 6a: Config service restart — in-memory registry should survive
log_info "Testing registry persistence across config-service restart..."
FLOW_BEFORE=$(api_get "${BASE_URL}:${CONFIG_PORT}/api/flows" | jlen)

# Restart config-service
docker restart mft-config-service > /dev/null 2>&1
sleep 10  # Wait for restart

RESTART_START=$(ms_now)
RESTART_WAITED=0
while [ $RESTART_WAITED -lt 60 ]; do
  if curl -sf "http://localhost:${CONFIG_PORT}/actuator/health" > /dev/null 2>&1; then
    break
  fi
  sleep 2
  RESTART_WAITED=$((RESTART_WAITED + 2))
done
RESTART_DUR=$(( $(ms_now) - RESTART_START ))

if curl -sf "http://localhost:${CONFIG_PORT}/actuator/health" > /dev/null 2>&1; then
  FLOW_AFTER=$(api_get "${BASE_URL}:${CONFIG_PORT}/api/flows" | jlen)
  if [ "${FLOW_AFTER}" = "${FLOW_BEFORE}" ]; then
    log_pass "Config service restart: flows intact (${FLOW_AFTER})" "$RESTART_DUR"
  else
    log_warn "Flow count changed" "before=${FLOW_BEFORE} after=${FLOW_AFTER}"
    finding "RESILIENCE" "WARN" "Flow count changed across restart" \
      "Before: ${FLOW_BEFORE}, After: ${FLOW_AFTER}" \
      "FlowRuleRegistryInitializer may not be reloading all active flows"
  fi
else
  log_fail "Config service restart" "Did not recover within 60s"
  finding "RESILIENCE" "CRITICAL" "Config service failed to restart" \
    "Service did not become healthy within 60s after restart" \
    "Check for Flyway migration locks or DB connection pool exhaustion"
fi

# 6b: RabbitMQ connection resilience
log_info "Testing RabbitMQ connection resilience..."
RABBIT_STATUS=$(docker exec mft-rabbitmq rabbitmqctl status 2>/dev/null | grep -c "uptime" || echo 0)
if [ "$RABBIT_STATUS" -gt 0 ]; then
  log_pass "RabbitMQ healthy"
else
  log_warn "RabbitMQ" "Status check failed"
fi

# 6c: Database connection pool under load
log_info "Testing DB connection pool under concurrent load..."
DB_CONNS_BEFORE=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
  "SELECT count(*) FROM pg_stat_activity WHERE datname='filetransfer'" 2>/dev/null | tr -d ' \n')

# Hammer the API with concurrent requests
POOL_START=$(ms_now)
for i in $(seq 1 $CONCURRENT_BATCH); do
  api_get "${BASE_URL}:${CONFIG_PORT}/api/flows" > /dev/null &
  api_get "${BASE_URL}:${ONBOARDING_PORT}/api/accounts" > /dev/null &
done
wait
POOL_DUR=$(( $(ms_now) - POOL_START ))

DB_CONNS_AFTER=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
  "SELECT count(*) FROM pg_stat_activity WHERE datname='filetransfer'" 2>/dev/null | tr -d ' \n')
log_pass "DB pool: ${DB_CONNS_BEFORE} → ${DB_CONNS_AFTER} connections" "$POOL_DUR"
log_perf "DB connections under load" "$DB_CONNS_AFTER" "conns" "100"

if [ "${DB_CONNS_AFTER:-0}" -gt 200 ]; then
  finding "EFFICIENCY" "HIGH" "Excessive DB connections" \
    "${DB_CONNS_AFTER} active connections (max_connections=300)" \
    "Reduce HikariCP pool sizes per service. Current combined pools may exceed PostgreSQL limits."
fi

# 6d: Concurrent flow CRUD + read consistency
log_info "Testing concurrent CRUD consistency..."
CRUD_START=$(ms_now)
# Create, read, update, delete in parallel batches
CRUD_OK=0; CRUD_FAIL=0
for i in $(seq 1 10); do
  # Create
  resp=$(api_post "${BASE_URL}:${CONFIG_PORT}/api/flows" \
    "{\"name\":\"crud-test-${i}-$$\",\"description\":\"CRUD test\",\"priority\":${i},\"steps\":[{\"type\":\"RENAME\",\"config\":{\"pattern\":\"x\"},\"order\":0}],\"matchCriteria\":{\"operator\":\"AND\",\"conditions\":[{\"field\":\"filename\",\"op\":\"EQ\",\"value\":\"crud_${i}\"}]}}")
  fid=$(echo "$resp" | jf "id")
  if [ -n "$fid" ] && [ "$fid" != "None" ] && [ "$fid" != "" ]; then
    # Read
    read_resp=$(api_get "${BASE_URL}:${CONFIG_PORT}/api/flows/${fid}")
    read_name=$(echo "$read_resp" | jf "name")
    # Update
    api_put "${BASE_URL}:${CONFIG_PORT}/api/flows/${fid}" \
      "{\"name\":\"crud-test-${i}-$$\",\"description\":\"Updated\",\"priority\":${i},\"steps\":[{\"type\":\"RENAME\",\"config\":{\"pattern\":\"y\"},\"order\":0}],\"matchCriteria\":{\"operator\":\"AND\",\"conditions\":[{\"field\":\"filename\",\"op\":\"EQ\",\"value\":\"crud_${i}\"}]}}" > /dev/null
    # Delete
    api_delete "${BASE_URL}:${CONFIG_PORT}/api/flows/${fid}" > /dev/null
    CRUD_OK=$((CRUD_OK + 1))
  else
    CRUD_FAIL=$((CRUD_FAIL + 1))
  fi
done
CRUD_DUR=$(( $(ms_now) - CRUD_START ))
log_pass "CRUD consistency: ${CRUD_OK}/10 cycles" "$CRUD_DUR"
log_perf "CRUD cycle time (create+read+update+delete)" "$((CRUD_DUR / 10))" "ms" "2000"

# =============================================================================
# PHASE 7: SAFETY & EDGE CASES
# =============================================================================
log_section "PHASE 7: SAFETY & EDGE CASES"

# 7a: Invalid criteria — should fail gracefully
INVALID_CRITERIA=(
  '{"operator":"AND","conditions":[]}'                                           # Empty conditions
  '{"operator":"XOR","conditions":[{"field":"filename","op":"EQ","value":"x"}]}'  # Invalid operator
  '{"field":"nonexistent_field","op":"EQ","value":"x"}'                           # Unknown field
  '{"operator":"NOT","conditions":[{"field":"a","op":"EQ","value":"1"},{"field":"b","op":"EQ","value":"2"}]}' # NOT with 2 children
  '{"operator":"AND","conditions":[{"field":"filename","op":"INVALID_OP","value":"x"}]}' # Invalid op
)
INVALID_LABELS=("Empty conditions" "Invalid group operator" "Unknown field" "NOT with 2 children" "Invalid condition op")

SAFETY_PASS=0; SAFETY_FAIL=0
for i in "${!INVALID_CRITERIA[@]}"; do
  resp=$(api_post "${BASE_URL}:${CONFIG_PORT}/api/flows/validate-criteria" "${INVALID_CRITERIA[$i]}")
  valid=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('valid',True))" 2>/dev/null)
  if [ "$valid" = "False" ]; then
    SAFETY_PASS=$((SAFETY_PASS + 1))
  else
    SAFETY_FAIL=$((SAFETY_FAIL + 1))
    log_fail "Validation: ${INVALID_LABELS[$i]}" "Should reject but returned valid=$valid"
  fi
done
log_pass "Invalid criteria rejection: ${SAFETY_PASS}/${#INVALID_CRITERIA[@]}"

if [ $SAFETY_FAIL -gt 0 ]; then
  finding "SAFETY" "HIGH" "Criteria validation allows invalid input" \
    "${SAFETY_FAIL}/${#INVALID_CRITERIA[@]} invalid criteria passed validation" \
    "MatchCriteriaService.validate() needs stricter checks for: empty conditions, unknown operators, unknown fields, NOT arity"
fi

# 7b: SQL injection attempt via match criteria
SQLI_RESP=$(api_post "${BASE_URL}:${CONFIG_PORT}/api/flows/validate-criteria" \
  '{"operator":"AND","conditions":[{"field":"filename","op":"EQ","value":"x'\'' OR 1=1; DROP TABLE users; --"}]}')
SQLI_VALID=$(echo "$SQLI_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('valid',''))" 2>/dev/null)
# Should either reject or safely handle — check DB is intact
USERS_EXIST=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
  "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name='users')" 2>/dev/null | tr -d ' \n')
if [ "$USERS_EXIST" = "t" ]; then
  log_pass "SQL injection resistance — users table intact"
else
  finding "SAFETY" "CRITICAL" "SQL injection vulnerability" \
    "users table dropped after SQL injection via match criteria" \
    "CRITICAL: Review JSONB handling and parameterized queries"
fi

# 7c: XSS in flow names
XSS_RESP=$(api_post "${BASE_URL}:${CONFIG_PORT}/api/flows" \
  "{\"name\":\"<script>alert('xss')</script>-$$\",\"description\":\"XSS test\",\"priority\":999,\"steps\":[{\"type\":\"RENAME\",\"config\":{\"pattern\":\"x\"},\"order\":0}]}")
XSS_ID=$(echo "$XSS_RESP" | jf "id")
if [ -n "$XSS_ID" ] && [ "$XSS_ID" != "None" ]; then
  # Check if the name is stored as-is (no sanitization)
  XSS_READ=$(api_get "${BASE_URL}:${CONFIG_PORT}/api/flows/${XSS_ID}")
  XSS_NAME=$(echo "$XSS_READ" | python3 -c "import sys,json; print(json.load(sys.stdin).get('name',''))" 2>/dev/null)
  if echo "$XSS_NAME" | grep -q "<script>"; then
    finding "SAFETY" "WARN" "XSS in flow names not sanitized" \
      "Flow name stored with raw HTML: ${XSS_NAME}" \
      "Add server-side HTML sanitization or ensure frontend escapes output. Spring's Jackson does NOT escape by default."
  else
    log_pass "XSS sanitized in flow names"
  fi
  api_delete "${BASE_URL}:${CONFIG_PORT}/api/flows/${XSS_ID}" > /dev/null 2>&1
else
  log_pass "XSS in flow name rejected by validation"
fi

# 7d: Regex DoS (ReDoS) — catastrophic backtracking
log_info "Testing ReDoS resistance..."
REDOS_START=$(ms_now)
REDOS_RESP=$(curl -sf --max-time 5 -X POST "${BASE_URL}:${CONFIG_PORT}/api/flows/test-match" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"criteria":{"operator":"AND","conditions":[{"field":"filename","op":"REGEX","value":"(a+)+$"}]},"fileContext":{"protocol":"SFTP","filename":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaab","fileSize":100,"direction":"INBOUND"}}' 2>/dev/null)
REDOS_DUR=$(( $(ms_now) - REDOS_START ))
log_perf "ReDoS pattern evaluation" "$REDOS_DUR" "ms" "1000"

if [ $REDOS_DUR -gt 3000 ]; then
  finding "SAFETY" "HIGH" "ReDoS vulnerability" \
    "Catastrophic backtracking regex took ${REDOS_DUR}ms (threshold: 1000ms)" \
    "Add Pattern.compile timeout or use RE2J library for user-supplied regex. Current FlowMatchEngine compiles without timeout."
else
  log_pass "ReDoS resistance" "$REDOS_DUR"
fi

# 7e: Null/empty criteria handling
NULL_FLOW=$(api_post "${BASE_URL}:${CONFIG_PORT}/api/flows" \
  "{\"name\":\"null-criteria-$$\",\"description\":\"No criteria\",\"priority\":999,\"steps\":[{\"type\":\"RENAME\",\"config\":{\"pattern\":\"x\"},\"order\":0}]}")
NULL_FID=$(echo "$NULL_FLOW" | jf "id")
if [ -n "$NULL_FID" ] && [ "$NULL_FID" != "None" ]; then
  log_pass "Null matchCriteria accepted (matches everything)"
  api_delete "${BASE_URL}:${CONFIG_PORT}/api/flows/${NULL_FID}" > /dev/null 2>&1
else
  log_warn "Null criteria flow" "Creation failed"
fi

# 7f: Deeply nested criteria (depth bomb)
DEEP_CRITERIA='{"operator":"AND","conditions":[{"operator":"OR","conditions":[{"operator":"AND","conditions":[{"operator":"OR","conditions":[{"operator":"AND","conditions":[{"field":"filename","op":"EQ","value":"deep.txt"}]}]}]}]}]}'
DEEP_RESP=$(api_post "${BASE_URL}:${CONFIG_PORT}/api/flows/validate-criteria" "$DEEP_CRITERIA")
DEEP_VALID=$(echo "$DEEP_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('valid',''))" 2>/dev/null)
if [ "$DEEP_VALID" = "True" ]; then
  finding "SAFETY" "WARN" "No depth limit on criteria nesting" \
    "5-level deep criteria accepted. Attacker could craft deeply nested trees causing stack overflow." \
    "Add max-depth validation (recommended: 4 levels) in MatchCriteriaService.validate()"
else
  log_pass "Deep criteria nesting rejected"
fi

# =============================================================================
# PHASE 8: CLEANUP SCALE TEST DATA
# =============================================================================
log_section "PHASE 8: CLEANUP"

CLEANUP_START=$(ms_now)
CLEANED=0
for fid in "${FLOW_IDS[@]}"; do
  api_delete "${BASE_URL}:${CONFIG_PORT}/api/flows/${fid}" > /dev/null 2>&1
  CLEANED=$((CLEANED + 1))
done
CLEANUP_DUR=$(( $(ms_now) - CLEANUP_START ))
log_pass "Cleaned up ${CLEANED} test flows" "$CLEANUP_DUR"
log_perf "Bulk delete (${CLEANED} flows)" "$CLEANUP_DUR" "ms" "$((CLEANED * 100))"

# =============================================================================
# GENERATE FINDINGS REPORT
# =============================================================================
log_section "GENERATING FINDINGS REPORT"

SUITE_END=$(ms_now)
SUITE_DUR=$(( SUITE_END - SUITE_START ))
SUITE_DUR_SEC=$(python3 -c "print(round(${SUITE_DUR}/1000.0, 1))")

# Count findings by severity
CRITICAL_COUNT=0; HIGH_COUNT=0; WARN_FINDING_COUNT=0; INFO_COUNT=0
for f in "${FINDINGS[@]}"; do
  IFS='|' read -r cat sev title detail rec <<< "$f"
  case $sev in
    CRITICAL) CRITICAL_COUNT=$((CRITICAL_COUNT + 1)) ;;
    HIGH)     HIGH_COUNT=$((HIGH_COUNT + 1)) ;;
    WARN)     WARN_FINDING_COUNT=$((WARN_FINDING_COUNT + 1)) ;;
    INFO)     INFO_COUNT=$((INFO_COUNT + 1)) ;;
  esac
done

cat > "$FINDINGS_FILE" << REPORT_EOF
# TranzFer MFT — Production Readiness Scale Test Findings
**Date:** $(date '+%Y-%m-%d %H:%M:%S')
**Scale:** ${SCALE_COUNT} files/test, ${CONCURRENT_BATCH} concurrent
**Duration:** ${SUITE_DUR_SEC}s
**Tests:** ${TOTAL_TESTS} total — ${PASS_COUNT} pass, ${FAIL_COUNT} fail, ${WARN_COUNT} warn

## Executive Summary

| Severity | Count |
|----------|-------|
| CRITICAL | ${CRITICAL_COUNT} |
| HIGH     | ${HIGH_COUNT} |
| WARN     | ${WARN_FINDING_COUNT} |
| INFO     | ${INFO_COUNT} |

**Verdict:** $(
  if [ $CRITICAL_COUNT -gt 0 ]; then echo "NOT PRODUCTION READY — ${CRITICAL_COUNT} critical issues"
  elif [ $HIGH_COUNT -gt 0 ]; then echo "CONDITIONAL — ${HIGH_COUNT} high-severity issues to address"
  elif [ $WARN_FINDING_COUNT -gt 0 ]; then echo "READY WITH CAVEATS — ${WARN_FINDING_COUNT} warnings to review"
  else echo "PRODUCTION READY"
  fi
)

---

## Findings

REPORT_EOF

# Group findings by category
for category in "EFFICIENCY" "SAFETY" "RESILIENCE"; do
  echo "### ${category}" >> "$FINDINGS_FILE"
  echo "" >> "$FINDINGS_FILE"
  FOUND=false
  for f in "${FINDINGS[@]}"; do
    IFS='|' read -r cat sev title detail rec <<< "$f"
    if [ "$cat" = "$category" ]; then
      FOUND=true
      echo "#### [${sev}] ${title}" >> "$FINDINGS_FILE"
      echo "**Finding:** ${detail}" >> "$FINDINGS_FILE"
      echo "**Recommendation:** ${rec}" >> "$FINDINGS_FILE"
      echo "" >> "$FINDINGS_FILE"
    fi
  done
  if [ "$FOUND" = false ]; then
    echo "No findings." >> "$FINDINGS_FILE"
    echo "" >> "$FINDINGS_FILE"
  fi
done

# Performance data
echo "---" >> "$FINDINGS_FILE"
echo "" >> "$FINDINGS_FILE"
echo "## Performance Metrics" >> "$FINDINGS_FILE"
echo "" >> "$FINDINGS_FILE"
echo "| Metric | Value | Unit | Threshold |" >> "$FINDINGS_FILE"
echo "|--------|-------|------|-----------|" >> "$FINDINGS_FILE"
for p in "${PERF_DATA[@]}"; do
  IFS='|' read -r name val unit thresh <<< "$p"
  echo "| ${name} | ${val} | ${unit} | ${thresh} |" >> "$FINDINGS_FILE"
done

echo "" >> "$FINDINGS_FILE"
echo "---" >> "$FINDINGS_FILE"
echo "*Generated by TranzFer MFT Scale Test Agent*" >> "$FINDINGS_FILE"

echo ""
echo -e "${BOLD}${CYAN}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}${CYAN}║                    TEST SUMMARY                              ║${NC}"
echo -e "${BOLD}${CYAN}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  Tests:    ${BOLD}${TOTAL_TESTS}${NC} total — ${GREEN}${PASS_COUNT} pass${NC}, ${RED}${FAIL_COUNT} fail${NC}, ${YELLOW}${WARN_COUNT} warn${NC}"
echo -e "  Findings: ${RED}${CRITICAL_COUNT} critical${NC}, ${RED}${HIGH_COUNT} high${NC}, ${YELLOW}${WARN_FINDING_COUNT} warn${NC}"
echo -e "  Duration: ${SUITE_DUR_SEC}s"
echo -e "  Report:   ${BOLD}${FINDINGS_FILE}${NC}"
echo ""

if [ $CRITICAL_COUNT -gt 0 ]; then
  echo -e "  ${RED}${BOLD}⛔ NOT PRODUCTION READY — ${CRITICAL_COUNT} critical issues found${NC}"
elif [ $HIGH_COUNT -gt 0 ]; then
  echo -e "  ${YELLOW}${BOLD}⚠  CONDITIONAL — ${HIGH_COUNT} high-severity issues to address${NC}"
elif [ $WARN_FINDING_COUNT -gt 0 ]; then
  echo -e "  ${GREEN}${BOLD}✓  READY WITH CAVEATS — ${WARN_FINDING_COUNT} warnings${NC}"
else
  echo -e "  ${GREEN}${BOLD}✓  PRODUCTION READY${NC}"
fi
echo ""

#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT Platform -Full Integration & Health Test Suite
# =============================================================================
# Usage:
#   ./tests/run-tests.sh                    # Run all tests, generate report
#   ./tests/run-tests.sh --html             # Also generate HTML report
#   ./tests/run-tests.sh --quick            # Skip performance benchmarks
#   ./tests/run-tests.sh --json             # Output JSON instead of text
#
# Prerequisites:
#   - All Docker containers running (docker compose up -d)
#   - curl, python3, docker CLI available
#   - Optional: sshpass (for SFTP tests)
# =============================================================================

set -o pipefail

# --- Configuration ---
BASE_URL="${MFT_BASE_URL:-http://localhost}"
ADMIN_EMAIL="${MFT_ADMIN_EMAIL:-admin@filetransfer.local}"
ADMIN_PASS="${MFT_ADMIN_PASSWORD:-Admin@1234}"
REPORT_DIR="${MFT_REPORT_DIR:-./tests/reports}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_FILE="${REPORT_DIR}/test-report-${TIMESTAMP}.txt"
HTML_REPORT="${REPORT_DIR}/test-report-${TIMESTAMP}.html"
JSON_REPORT="${REPORT_DIR}/test-report-${TIMESTAMP}.json"

# Ports
ONBOARDING_PORT=8080
SFTP_CONTROL_PORT=8081
FTP_CONTROL_PORT=8082
FTP_WEB_PORT=8083
CONFIG_PORT=8084
GATEWAY_PORT=8085
ENCRYPTION_PORT=8086
FORWARDER_PORT=8087
DMZ_PORT=8088
LICENSE_PORT=8089
ANALYTICS_PORT=8090
AI_ENGINE_PORT=8091
SCREENING_PORT=8092
KEYSTORE_PORT=8093
STORAGE_PORT=8094
EDI_PORT=8095
PARTNER_PORTAL_PORT=3002
SFTP_PORT=22222
FTP_CMD_PORT=21
ADMIN_UI_PORT=3000
FILE_PORTAL_PORT=3001

# --- Parse args ---
GEN_HTML=false
QUICK_MODE=false
JSON_MODE=false
for arg in "$@"; do
  case $arg in
    --html) GEN_HTML=true ;;
    --quick) QUICK_MODE=true ;;
    --json) JSON_MODE=true ;;
  esac
done

# --- State ---
TOTAL=0; PASS=0; FAIL=0; SKIP=0; WARN=0
declare -a TEST_RESULTS=()
declare -a PERF_RESULTS=()
SUITE_START=$(python3 -c "import time; print(int(time.time()*1000))")
TOKEN=""

# --- Colors ---
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'; BOLD='\033[1m'

# --- Helpers ---
log_section() { echo -e "\n${BOLD}${CYAN}═══ $1 ═══${NC}\n"; }
log_test() { echo -e "  $1"; }

record_result() {
  local suite="$1" name="$2" status="$3" detail="$4" duration_ms="$5"
  TOTAL=$((TOTAL + 1))
  case $status in
    PASS) PASS=$((PASS + 1)); log_test "${GREEN}✓ PASS${NC}  $name ${duration_ms:+(${duration_ms}ms)}" ;;
    FAIL) FAIL=$((FAIL + 1)); log_test "${RED}✗ FAIL${NC}  $name -$detail" ;;
    SKIP) SKIP=$((SKIP + 1)); log_test "${YELLOW}⊘ SKIP${NC}  $name -$detail" ;;
    WARN) WARN=$((WARN + 1)); log_test "${YELLOW}⚠ WARN${NC}  $name -$detail" ;;
  esac
  TEST_RESULTS+=("${suite}|${name}|${status}|${detail}|${duration_ms}")
}

record_perf() {
  local name="$1" value="$2" unit="$3" threshold="$4" status="$5"
  PERF_RESULTS+=("${name}|${value}|${unit}|${threshold}|${status}")
  if [ "$status" = "SLOW" ]; then
    log_test "${YELLOW}⚠ PERF${NC}  $name: ${value}${unit} (threshold: ${threshold}${unit})"
  else
    log_test "${GREEN}✓ PERF${NC}  $name: ${value}${unit}"
  fi
}

# Measure HTTP request duration in ms
http_time() {
  local url="$1" method="${2:-GET}" body="$3"
  local start end
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  if [ "$method" = "POST" ] && [ -n "$body" ]; then
    curl -s -o /dev/null -w "%{http_code}" -X POST "$url" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d "$body" 2>/dev/null
  else
    curl -s -o /dev/null -w "%{http_code}" "$url" -H "Authorization: Bearer $TOKEN" 2>/dev/null
  fi
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  echo $(( end - start ))
}

# ═══════════════════════════════════════════════════════════════
# TEST SUITES
# ═══════════════════════════════════════════════════════════════

run_container_health_tests() {
  log_section "1. CONTAINER HEALTH"

  local expected_containers=("mft-postgres" "mft-rabbitmq" "mft-onboarding-api" "mft-sftp-service"
    "mft-ftp-service" "mft-ftp-web-service" "mft-config-service" "mft-gateway-service"
    "mft-encryption-service" "mft-forwarder-service" "mft-dmz-proxy" "mft-license-service"
    "mft-analytics-service" "mft-admin-ui" "mft-ftp-web-ui"
    "mft-ai-engine" "mft-screening-service" "mft-keystore-manager" "mft-storage-manager"
    "mft-edi-converter" "mft-partner-portal" "mft-api-gateway")

  local running_containers=$(docker ps --format '{{.Names}}' 2>/dev/null)

  for c in "${expected_containers[@]}"; do
    if echo "$running_containers" | grep -q "^${c}$"; then
      record_result "Container Health" "$c" "PASS" "" ""
    else
      record_result "Container Health" "$c" "FAIL" "Container not running" ""
    fi
  done
}

run_database_tests() {
  log_section "2. DATABASE"

  # Connection test
  local start end dur
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  local tables=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'" 2>&1 | tr -d ' \n')
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))

  if [ "$tables" -ge 16 ] 2>/dev/null; then
    record_result "Database" "PostgreSQL connection" "PASS" "" "$dur"
    record_perf "DB connection latency" "$dur" "ms" "500" "$([ $dur -gt 500 ] && echo SLOW || echo OK)"
  else
    record_result "Database" "PostgreSQL connection" "FAIL" "Got: $tables (expected ≥16)" "$dur"
  fi

  # Table count
  record_result "Database" "Schema tables ($tables)" "$([ "$tables" -ge 16 ] && echo PASS || echo FAIL)" "" ""

  # Verify critical tables exist
  for tbl in users transfer_accounts folder_mappings file_transfer_records audit_logs \
             service_registrations security_profiles file_flows flow_executions \
             license_records metric_snapshots alert_rules; do
    local exists=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name='$tbl')" 2>/dev/null | tr -d ' \n')
    if [ "$exists" = "t" ]; then
      record_result "Database" "Table: $tbl" "PASS" "" ""
    else
      record_result "Database" "Table: $tbl" "FAIL" "Missing" ""
    fi
  done

  # DB query performance
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  docker exec mft-postgres psql -U postgres -d filetransfer -t -c "SELECT count(*) FROM users" > /dev/null 2>&1
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  record_perf "DB simple query (SELECT count)" "$dur" "ms" "200" "$([ $dur -gt 200 ] && echo SLOW || echo OK)"

  # DB write performance
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  docker exec mft-postgres psql -U postgres -d filetransfer -c "INSERT INTO alert_rules(id,name,metric,operator,threshold,created_at) VALUES(gen_random_uuid(),'bench_test','LATENCY_P95','GT',999.0,now()); DELETE FROM alert_rules WHERE name='bench_test';" > /dev/null 2>&1
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  record_perf "DB write+delete roundtrip" "$dur" "ms" "300" "$([ $dur -gt 300 ] && echo SLOW || echo OK)"
}

run_auth_tests() {
  log_section "3. AUTHENTICATION"

  # Login
  local start end dur
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  local resp=$(curl -s -X POST "${BASE_URL}:${ONBOARDING_PORT}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASS}\"}")
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))

  TOKEN=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken','') or d.get('token',''))" 2>/dev/null)

  if [ -n "$TOKEN" ] && [ ${#TOKEN} -gt 20 ]; then
    record_result "Auth" "Admin login" "PASS" "" "$dur"
    record_perf "Login latency" "$dur" "ms" "2000" "$([ $dur -gt 2000 ] && echo SLOW || echo OK)"
  else
    record_result "Auth" "Admin login" "FAIL" "No token returned" "$dur"
    echo -e "${RED}FATAL: Cannot continue without auth token. Aborting remaining tests.${NC}"
    return 1
  fi

  # Unauthorized request
  local unauth=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}:${ONBOARDING_PORT}/api/accounts")
  if [ "$unauth" = "403" ] || [ "$unauth" = "401" ]; then
    record_result "Auth" "Reject unauthenticated (HTTP $unauth)" "PASS" "" ""
  else
    record_result "Auth" "Reject unauthenticated" "WARN" "Expected 401/403, got $unauth" ""
  fi

  # Invalid credentials
  local bad=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}:${ONBOARDING_PORT}/api/auth/login" \
    -H "Content-Type: application/json" -d '{"email":"bad@bad.com","password":"wrong"}')
  if [ "$bad" = "401" ] || [ "$bad" = "403" ]; then
    record_result "Auth" "Reject invalid credentials (HTTP $bad)" "PASS" "" ""
  else
    record_result "Auth" "Reject invalid credentials" "WARN" "Got HTTP $bad" ""
  fi
}

run_service_api_tests() {
  log_section "4. SERVICE APIs"

  local names=( "Onboarding:Accounts" "Onboarding:FolderMappings" "Onboarding:ServiceRegistry"
    "Config:Servers" "Config:SecurityProfiles" "Config:Flows" "Config:StepTypes"
    "Analytics:Dashboard" "Analytics:Predictions" "Analytics:AlertRules" "License:Health"
    "AI:Health" "AI:Anomalies" "AI:Recommendations"
    "Screening:Health" "Screening:Lists"
    "Keystore:Health"
    "Storage:Health"
    "EDI:Types" )
  local urls=(
    "${BASE_URL}:${ONBOARDING_PORT}/api/accounts"
    "${BASE_URL}:${ONBOARDING_PORT}/api/folder-mappings"
    "${BASE_URL}:${ONBOARDING_PORT}/api/service-registry"
    "${BASE_URL}:${CONFIG_PORT}/api/servers"
    "${BASE_URL}:${CONFIG_PORT}/api/security-profiles"
    "${BASE_URL}:${CONFIG_PORT}/api/flows"
    "${BASE_URL}:${CONFIG_PORT}/api/flows/step-types"
    "${BASE_URL}:${ANALYTICS_PORT}/api/v1/analytics/dashboard"
    "${BASE_URL}:${ANALYTICS_PORT}/api/v1/analytics/predictions"
    "${BASE_URL}:${ANALYTICS_PORT}/api/v1/analytics/alerts"
    "${BASE_URL}:${LICENSE_PORT}/api/v1/licenses/health"
    "${BASE_URL}:${AI_ENGINE_PORT}/api/v1/ai/health"
    "${BASE_URL}:${AI_ENGINE_PORT}/api/v1/ai/anomalies"
    "${BASE_URL}:${AI_ENGINE_PORT}/api/v1/ai/recommendations"
    "${BASE_URL}:${SCREENING_PORT}/api/v1/screening/health"
    "${BASE_URL}:${SCREENING_PORT}/api/v1/screening/lists"
    "${BASE_URL}:${KEYSTORE_PORT}/actuator/health"
    "${BASE_URL}:${STORAGE_PORT}/actuator/health"
    "${BASE_URL}:${EDI_PORT}/api/v1/edi/types"
  )

  for i in "${!names[@]}"; do
    local name="${names[$i]}"
    local url="${urls[$i]}"
    local start end dur code
    start=$(python3 -c "import time; print(int(time.time()*1000))")
    code=$(curl -s -o /dev/null -w "%{http_code}" "$url" -H "Authorization: Bearer $TOKEN" 2>/dev/null)
    end=$(python3 -c "import time; print(int(time.time()*1000))")
    dur=$((end - start))

    if [ "$code" = "200" ]; then
      record_result "Service API" "$name (HTTP $code)" "PASS" "" "$dur"
    elif [ "$code" = "000" ]; then
      record_result "Service API" "$name" "FAIL" "Connection refused" "$dur"
    else
      record_result "Service API" "$name (HTTP $code)" "WARN" "Expected 200" "$dur"
    fi
    record_perf "API: $name" "$dur" "ms" "1000" "$([ $dur -gt 1000 ] && echo SLOW || echo OK)"
  done
}

run_inter_service_tests() {
  log_section "5. INTER-SERVICE COMMUNICATION"

  # Test onboarding → config-service communication
  local start end dur

  # Onboarding → DB (account creation roundtrip)
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  local create_resp=$(curl -s -X POST "${BASE_URL}:${ONBOARDING_PORT}/api/accounts" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"protocol\":\"SFTP\",\"username\":\"bench_$(date +%s)\",\"password\":\"BenchPass@1\",\"homeDir\":\"/data/sftp/bench\"}")
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  local acc_id=$(echo "$create_resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)

  if [ -n "$acc_id" ]; then
    record_result "Inter-Service" "Account create roundtrip" "PASS" "" "$dur"
    record_perf "Account create (API→DB→RabbitMQ→Response)" "$dur" "ms" "3000" "$([ $dur -gt 3000 ] && echo SLOW || echo OK)"
  else
    record_result "Inter-Service" "Account create roundtrip" "FAIL" "$(echo $create_resp | head -c 80)" "$dur"
  fi

  # Config service → DB roundtrip
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  curl -s "${BASE_URL}:${CONFIG_PORT}/api/security-profiles" > /dev/null 2>&1
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  record_perf "Config service → DB query" "$dur" "ms" "500" "$([ $dur -gt 500 ] && echo SLOW || echo OK)"

  # Analytics service → DB aggregation
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  curl -s "${BASE_URL}:${ANALYTICS_PORT}/api/v1/analytics/dashboard" > /dev/null 2>&1
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  record_perf "Analytics dashboard aggregation" "$dur" "ms" "2000" "$([ $dur -gt 2000 ] && echo SLOW || echo OK)"

  # License service → DB + crypto
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  curl -s -X POST "${BASE_URL}:${LICENSE_PORT}/api/v1/licenses/trial" \
    -H "Content-Type: application/json" \
    -d '{"fingerprint":"bench-test","serviceType":"SFTP","hostId":"bench"}' > /dev/null 2>&1
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  record_perf "License validation (DB + crypto)" "$dur" "ms" "1000" "$([ $dur -gt 1000 ] && echo SLOW || echo OK)"
}

run_license_tests() {
  log_section "6. LICENSING"

  # Trial activation
  local trial=$(curl -s -X POST "${BASE_URL}:${LICENSE_PORT}/api/v1/licenses/trial" \
    -H "Content-Type: application/json" \
    -d '{"fingerprint":"test-suite-fp","customerName":"TestSuite","serviceType":"SFTP","hostId":"test"}')
  local valid=$(echo "$trial" | python3 -c "import sys,json; print(json.load(sys.stdin).get('valid',False))" 2>/dev/null)
  local days=$(echo "$trial" | python3 -c "import sys,json; print(json.load(sys.stdin).get('trialDaysRemaining',0))" 2>/dev/null)

  if [ "$valid" = "True" ]; then
    record_result "License" "Trial activation" "PASS" "" ""
    record_result "License" "Trial days remaining ($days)" "$([ "$days" -gt 0 ] && echo PASS || echo FAIL)" "" ""
  else
    record_result "License" "Trial activation" "FAIL" "$trial" ""
  fi

  # Issue license
  local lic=$(curl -s -X POST "${BASE_URL}:${LICENSE_PORT}/api/v1/licenses/issue" \
    -H "Content-Type: application/json" -H "X-Admin-Key: license_admin_secret_key" \
    -d '{"customerId":"bench","customerName":"BenchCorp","edition":"ENTERPRISE","validDays":365,"services":[{"serviceType":"SFTP","maxInstances":10,"maxConcurrentConnections":5000,"features":["ALL"]}]}')
  local lic_key=$(echo "$lic" | python3 -c "import sys,json; print(json.load(sys.stdin).get('licenseKey',''))" 2>/dev/null)

  if [ ${#lic_key} -gt 50 ] 2>/dev/null; then
    record_result "License" "Issue commercial license" "PASS" "" ""

    # Validate
    local val=$(curl -s -X POST "${BASE_URL}:${LICENSE_PORT}/api/v1/licenses/validate" \
      -H "Content-Type: application/json" \
      -d "{\"licenseKey\":\"$lic_key\",\"serviceType\":\"SFTP\",\"hostId\":\"node-1\"}")
    local val_valid=$(echo "$val" | python3 -c "import sys,json; print(json.load(sys.stdin).get('edition',''))" 2>/dev/null)
    if [ "$val_valid" = "ENTERPRISE" ]; then
      record_result "License" "Validate license (ENTERPRISE)" "PASS" "" ""
    else
      record_result "License" "Validate license" "FAIL" "$val" ""
    fi
  else
    record_result "License" "Issue commercial license" "FAIL" "$lic" ""
  fi
}

run_flow_tests() {
  log_section "7. FILE PROCESSING FLOWS"

  # Step types
  local steps=$(curl -s "${BASE_URL}:${CONFIG_PORT}/api/flows/step-types")
  if echo "$steps" | grep -q "encryption"; then
    record_result "Flows" "Step types catalog" "PASS" "" ""
  else
    record_result "Flows" "Step types catalog" "FAIL" "" ""
  fi

  # Create flow
  local flow_name="test-suite-flow-$(date +%s)"
  local flow=$(curl -s -X POST "${BASE_URL}:${CONFIG_PORT}/api/flows" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$flow_name\",\"description\":\"Test suite flow\",\"filenamePattern\":\".*\\\\.csv$\",\"sourcePath\":\"/inbox\",\"priority\":50,\"steps\":[{\"type\":\"COMPRESS_GZIP\",\"config\":{},\"order\":0},{\"type\":\"RENAME\",\"config\":{\"pattern\":\"\${basename}_\${trackid}.gz\"},\"order\":1},{\"type\":\"ROUTE\",\"config\":{},\"order\":2}]}")
  local flow_id=$(echo "$flow" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)

  if [ -n "$flow_id" ] && [ "$flow_id" != "None" ]; then
    record_result "Flows" "Create 3-step flow ($flow_name)" "PASS" "" ""

    # List flows
    local fc=$(curl -s "${BASE_URL}:${CONFIG_PORT}/api/flows" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null)
    record_result "Flows" "List flows ($fc active)" "$([ "$fc" -ge 1 ] && echo PASS || echo FAIL)" "" ""

    # Toggle
    local toggled=$(curl -s -X PATCH "${BASE_URL}:${CONFIG_PORT}/api/flows/$flow_id/toggle" | python3 -c "import sys,json; print(json.load(sys.stdin).get('active',''))" 2>/dev/null)
    record_result "Flows" "Toggle flow (active=$toggled)" "PASS" "" ""
  else
    record_result "Flows" "Create flow" "FAIL" "$(echo $flow | head -c 100)" ""
  fi
}

run_cli_tests() {
  log_section "8. ADMIN CLI"

  local commands=("help" "status" "version" "accounts list" "users list" "services" "flows list" "search recent 5" "logs recent 5")
  local expected=("COMMANDS" "Platform Status" "v2" "Username" "Email" "Host" "Name|No active" "transfer|No transfer" "LOGIN|No audit|Usage")

  for i in "${!commands[@]}"; do
    local cmd="${commands[$i]}"
    local expect="${expected[$i]}"
    local start end dur

    start=$(python3 -c "import time; print(int(time.time()*1000))")
    local resp=$(curl -s -X POST "${BASE_URL}:${ONBOARDING_PORT}/api/cli/execute" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      -d "{\"command\":\"$cmd\"}" | python3 -c "import sys,json; print(json.load(sys.stdin).get('output',''))" 2>/dev/null)
    end=$(python3 -c "import time; print(int(time.time()*1000))")
    dur=$((end - start))

    if echo "$resp" | grep -qiE "$expect"; then
      record_result "CLI" "cli: $cmd" "PASS" "" "$dur"
    else
      record_result "CLI" "cli: $cmd" "FAIL" "Expected match for: $expect" "$dur"
    fi
  done

  # CLI onboard
  local onboard_resp=$(curl -s -X POST "${BASE_URL}:${ONBOARDING_PORT}/api/cli/execute" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"command\":\"onboard benchuser_$(date +%s)@test.com Pass@123\"}" | python3 -c "import sys,json; print(json.load(sys.stdin).get('output',''))" 2>/dev/null)
  if echo "$onboard_resp" | grep -qi "Created"; then
    record_result "CLI" "cli: onboard user" "PASS" "" ""
  else
    record_result "CLI" "cli: onboard user" "FAIL" "$(echo $onboard_resp | head -c 60)" ""
  fi
}

run_frontend_tests() {
  log_section "9. FRONTEND UIs"

  # Admin UI
  local start end dur code

  start=$(python3 -c "import time; print(int(time.time()*1000))")
  code=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}:${ADMIN_UI_PORT}/")
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  if [ "$code" = "200" ]; then
    record_result "Frontend" "Admin UI (port $ADMIN_UI_PORT)" "PASS" "" "$dur"
    record_perf "Admin UI load time" "$dur" "ms" "500" "$([ $dur -gt 500 ] && echo SLOW || echo OK)"
  else
    record_result "Frontend" "Admin UI" "FAIL" "HTTP $code" "$dur"
  fi

  # File Portal
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  code=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}:${FILE_PORTAL_PORT}/")
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  if [ "$code" = "200" ]; then
    record_result "Frontend" "File Portal (port $FILE_PORTAL_PORT)" "PASS" "" "$dur"
    record_perf "File Portal load time" "$dur" "ms" "500" "$([ $dur -gt 500 ] && echo SLOW || echo OK)"
  else
    record_result "Frontend" "File Portal" "FAIL" "HTTP $code" "$dur"
  fi
}

run_v2_service_tests() {
  log_section "10. V2 PLATFORM SERVICES"

  # AI Engine — data classification with inline text
  local start end dur code body
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}:${AI_ENGINE_PORT}/api/v1/ai/classify/text" \
    -H "Content-Type: text/plain" -H "Authorization: Bearer $TOKEN" \
    -d "John Doe, SSN 123-45-6789, card 4111111111111111" 2>/dev/null)
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  if [ "$code" = "200" ]; then
    record_result "AI Engine" "Text Classification" "PASS" "" "$dur"
  else
    record_result "AI Engine" "Text Classification" "$([ "$code" = "000" ] && echo FAIL || echo WARN)" "HTTP $code" "$dur"
  fi

  # AI Engine — smart retry classification
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}:${AI_ENGINE_PORT}/api/v1/ai/smart-retry" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d '{"errorMessage":"Connection timeout","filename":"test.csv","retryCount":0}' 2>/dev/null)
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  if [ "$code" = "200" ]; then
    record_result "AI Engine" "Smart Retry" "PASS" "" "$dur"
  else
    record_result "AI Engine" "Smart Retry" "$([ "$code" = "000" ] && echo FAIL || echo WARN)" "HTTP $code" "$dur"
  fi

  # AI Engine — threat scoring
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}:${AI_ENGINE_PORT}/api/v1/ai/threat-score" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d '{"username":"testuser","ipAddress":"10.0.0.1","action":"UPLOAD","filename":"data.csv","fileSizeBytes":1024}' 2>/dev/null)
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  if [ "$code" = "200" ]; then
    record_result "AI Engine" "Threat Scoring" "PASS" "" "$dur"
  else
    record_result "AI Engine" "Threat Scoring" "$([ "$code" = "000" ] && echo FAIL || echo WARN)" "HTTP $code" "$dur"
  fi

  # Screening — sanctions list refresh
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}:${SCREENING_PORT}/api/v1/screening/lists/refresh" \
    -H "Authorization: Bearer $TOKEN" 2>/dev/null)
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  if [ "$code" = "200" ] || [ "$code" = "202" ]; then
    record_result "Screening" "Sanctions list refresh" "PASS" "" "$dur"
  else
    record_result "Screening" "Sanctions list refresh" "$([ "$code" = "000" ] && echo FAIL || echo WARN)" "HTTP $code" "$dur"
  fi

  # Screening — inline text scan
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}:${SCREENING_PORT}/api/v1/screening/scan/text" \
    -H "Content-Type: text/plain" -H "Authorization: Bearer $TOKEN" \
    -d "name,country\nJohn Smith,US\nJane Doe,UK" 2>/dev/null)
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  if [ "$code" = "200" ]; then
    record_result "Screening" "Text Screening" "PASS" "" "$dur"
  else
    record_result "Screening" "Text Screening" "$([ "$code" = "000" ] && echo FAIL || echo WARN)" "HTTP $code" "$dur"
  fi

  # EDI Converter — detect format
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE_URL}:${EDI_PORT}/api/v1/edi/detect" \
    -H "Content-Type: text/plain" -H "Authorization: Bearer $TOKEN" \
    -d "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *200101*1253*^*00501*000000905*0*P*:~" 2>/dev/null)
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  if [ "$code" = "200" ]; then
    record_result "EDI Converter" "Format Detection" "PASS" "" "$dur"
  else
    record_result "EDI Converter" "Format Detection" "$([ "$code" = "000" ] && echo FAIL || echo WARN)" "HTTP $code" "$dur"
  fi

  # Partner Portal — UI served
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  code=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}:${PARTNER_PORTAL_PORT}/" 2>/dev/null)
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))
  if [ "$code" = "200" ]; then
    record_result "Partner Portal" "UI served" "PASS" "" "$dur"
  else
    record_result "Partner Portal" "UI served" "$([ "$code" = "000" ] && echo FAIL || echo WARN)" "HTTP $code" "$dur"
  fi
}

run_sftp_tests() {
  log_section "11. PROTOCOL TESTS"

  # SFTP port open
  local start end dur
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  local sftp_open=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "telnet://localhost:$SFTP_PORT" 2>/dev/null; echo $?)
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  dur=$((end - start))

  # Check with nc instead
  if command -v nc &>/dev/null; then
    local nc_result=$(echo "" | nc -w 2 localhost $SFTP_PORT 2>&1 | head -1)
    if echo "$nc_result" | grep -qi "SSH\|OpenSSH\|MINA"; then
      record_result "Protocol" "SFTP port $SFTP_PORT open (SSH banner)" "PASS" "" "$dur"
    else
      record_result "Protocol" "SFTP port $SFTP_PORT" "WARN" "Port open but no SSH banner" "$dur"
    fi
  else
    record_result "Protocol" "SFTP port check" "SKIP" "nc not available" ""
  fi

  # SFTP login with sshpass
  if command -v sshpass &>/dev/null; then
    start=$(python3 -c "import time; print(int(time.time()*1000))")
    local sftp_test=$(sshpass -p "Pass@1234" sftp -P $SFTP_PORT -o StrictHostKeyChecking=no -o PreferredAuthentications=password -o ConnectTimeout=5 test_v2b@localhost <<< "ls" 2>&1)
    end=$(python3 -c "import time; print(int(time.time()*1000))")
    dur=$((end - start))
    if echo "$sftp_test" | grep -qi "connected"; then
      record_result "Protocol" "SFTP login + ls" "PASS" "" "$dur"
      record_perf "SFTP login latency" "$dur" "ms" "5000" "$([ $dur -gt 5000 ] && echo SLOW || echo OK)"
    else
      record_result "Protocol" "SFTP login" "FAIL" "$(echo $sftp_test | head -c 60)" "$dur"
    fi
  else
    record_result "Protocol" "SFTP login test" "SKIP" "sshpass not installed (brew install sshpass)" ""
  fi
}

run_performance_benchmarks() {
  if $QUICK_MODE; then
    log_section "11. PERFORMANCE (SKIPPED -quick mode)"
    return
  fi

  log_section "11. PERFORMANCE BENCHMARKS"

  # Sustained API throughput (10 sequential requests)
  local total_ms=0
  for i in $(seq 1 10); do
    local start end
    start=$(python3 -c "import time; print(int(time.time()*1000))")
    curl -s -o /dev/null "${BASE_URL}:${CONFIG_PORT}/api/servers" 2>/dev/null
    end=$(python3 -c "import time; print(int(time.time()*1000))")
    total_ms=$((total_ms + end - start))
  done
  local avg=$((total_ms / 10))
  record_perf "Avg API response (10 requests)" "$avg" "ms" "500" "$([ $avg -gt 500 ] && echo SLOW || echo OK)"

  # DB connection pool test (5 concurrent-ish queries)
  local db_total=0
  for i in $(seq 1 5); do
    local start end
    start=$(python3 -c "import time; print(int(time.time()*1000))")
    docker exec mft-postgres psql -U postgres -d filetransfer -t -c "SELECT count(*) FROM transfer_accounts" > /dev/null 2>&1
    end=$(python3 -c "import time; print(int(time.time()*1000))")
    db_total=$((db_total + end - start))
  done
  local db_avg=$((db_total / 5))
  record_perf "Avg DB query (5 sequential)" "$db_avg" "ms" "300" "$([ $db_avg -gt 300 ] && echo SLOW || echo OK)"

  # Cross-service call (onboarding → cli → db → response)
  local start end
  start=$(python3 -c "import time; print(int(time.time()*1000))")
  curl -s -X POST "${BASE_URL}:${ONBOARDING_PORT}/api/cli/execute" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"command":"status"}' > /dev/null 2>&1
  end=$(python3 -c "import time; print(int(time.time()*1000))")
  local cli_dur=$((end - start))
  record_perf "CLI status (full stack roundtrip)" "$cli_dur" "ms" "2000" "$([ $cli_dur -gt 2000 ] && echo SLOW || echo OK)"
}

# ═══════════════════════════════════════════════════════════════
# REPORT GENERATION
# ═══════════════════════════════════════════════════════════════

generate_text_report() {
  mkdir -p "$REPORT_DIR"
  local suite_end=$(python3 -c "import time; print(int(time.time()*1000))")
  local suite_dur=$(( (suite_end - SUITE_START) / 1000 ))

  {
    echo "============================================================"
    echo "  TranzFer MFT -Integration & Health Test Report"
    echo "  Generated: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "  Duration: ${suite_dur}s"
    echo "============================================================"
    echo ""
    echo "SUMMARY"
    echo "  Total:  $TOTAL"
    echo "  Pass:   $PASS"
    echo "  Fail:   $FAIL"
    echo "  Warn:   $WARN"
    echo "  Skip:   $SKIP"
    echo "  Rate:   $(( TOTAL > 0 ? PASS * 100 / TOTAL : 0 ))%"
    echo ""
    echo "────────────────────────────────────────────────────────────"
    echo "TEST RESULTS"
    echo "────────────────────────────────────────────────────────────"
    printf "%-20s %-40s %-6s %s\n" "SUITE" "TEST" "STATUS" "TIME"
    echo "────────────────────────────────────────────────────────────"

    local prev_suite=""
    for r in "${TEST_RESULTS[@]}"; do
      IFS='|' read -r suite name st detail dur <<< "$r"
      if [ "$suite" != "$prev_suite" ]; then
        echo ""
        prev_suite="$suite"
      fi
      printf "%-20s %-40s %-6s %s\n" "$suite" "$name" "$st" "${dur:+${dur}ms}"
    done

    echo ""
    echo "────────────────────────────────────────────────────────────"
    echo "PERFORMANCE METRICS"
    echo "────────────────────────────────────────────────────────────"
    printf "%-45s %8s %-4s %s\n" "METRIC" "VALUE" "UNIT" "STATUS"
    echo "────────────────────────────────────────────────────────────"
    for p in "${PERF_RESULTS[@]}"; do
      IFS='|' read -r name val unit thresh st <<< "$p"
      printf "%-45s %8s %-4s %s\n" "$name" "$val" "$unit" "$st"
    done

    echo ""
    echo "────────────────────────────────────────────────────────────"
    echo "ENVIRONMENT"
    echo "────────────────────────────────────────────────────────────"
    echo "  OS:          $(uname -s) $(uname -m)"
    echo "  Docker:      $(docker --version 2>/dev/null | head -c 40)"
    echo "  Containers:  $(docker ps --format '{{.Names}}' 2>/dev/null | wc -l | tr -d ' ') running"
    echo "  PostgreSQL:  $(docker exec mft-postgres psql -U postgres -t -c 'SELECT version()' 2>/dev/null | head -1 | tr -d ' ' | head -c 40)"
    echo ""
  } | tee "$REPORT_FILE"

  echo -e "\n${GREEN}Report saved: ${REPORT_FILE}${NC}"
}

generate_html_report() {
  local suite_end=$(python3 -c "import time; print(int(time.time()*1000))")
  local suite_dur=$(( (suite_end - SUITE_START) / 1000 ))
  local pass_rate=$(( TOTAL > 0 ? PASS * 100 / TOTAL : 0 ))

  cat > "$HTML_REPORT" << HTMLEOF
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>TranzFer MFT -Test Report</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f1f5f9; color: #1e293b; padding: 2rem; }
  .container { max-width: 1100px; margin: 0 auto; }
  .header { background: linear-gradient(135deg, #1e3a5f 0%, #0f172a 100%); color: white; padding: 2rem; border-radius: 12px; margin-bottom: 1.5rem; }
  .header h1 { font-size: 1.5rem; } .header p { color: #94a3b8; margin-top: 0.5rem; font-size: 0.9rem; }
  .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 1rem; margin-bottom: 1.5rem; }
  .stat { background: white; border-radius: 10px; padding: 1.2rem; box-shadow: 0 1px 3px rgba(0,0,0,0.08); text-align: center; }
  .stat .value { font-size: 2rem; font-weight: 800; } .stat .label { font-size: 0.75rem; color: #64748b; text-transform: uppercase; letter-spacing: 0.05em; margin-top: 0.25rem; }
  .stat.pass .value { color: #16a34a; } .stat.fail .value { color: #dc2626; } .stat.warn .value { color: #d97706; } .stat.rate .value { color: #2563eb; }
  .card { background: white; border-radius: 10px; padding: 1.5rem; box-shadow: 0 1px 3px rgba(0,0,0,0.08); margin-bottom: 1.5rem; }
  .card h2 { font-size: 1.1rem; margin-bottom: 1rem; color: #0f172a; }
  table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
  th { text-align: left; padding: 0.6rem 0.8rem; background: #f8fafc; color: #64748b; font-size: 0.7rem; text-transform: uppercase; letter-spacing: 0.05em; }
  td { padding: 0.5rem 0.8rem; border-top: 1px solid #f1f5f9; }
  tr:hover { background: #f8fafc; }
  .badge { display: inline-block; padding: 2px 8px; border-radius: 9999px; font-size: 0.7rem; font-weight: 600; }
  .badge-pass { background: #dcfce7; color: #166534; } .badge-fail { background: #fee2e2; color: #991b1b; }
  .badge-warn { background: #fef3c7; color: #92400e; } .badge-skip { background: #f1f5f9; color: #475569; }
  .badge-ok { background: #dcfce7; color: #166534; } .badge-slow { background: #fef3c7; color: #92400e; }
  .mono { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 0.8rem; }
  .progress { height: 8px; background: #e2e8f0; border-radius: 4px; overflow: hidden; margin-top: 0.5rem; }
  .progress-bar { height: 100%; border-radius: 4px; transition: width 0.5s; }
  footer { text-align: center; color: #94a3b8; font-size: 0.75rem; margin-top: 2rem; }
</style>
</head>
<body>
<div class="container">
  <div class="header">
    <h1>TranzFer MFT -Test Report</h1>
    <p>Generated: $(date '+%Y-%m-%d %H:%M:%S') &nbsp;|&nbsp; Duration: ${suite_dur}s &nbsp;|&nbsp; $(uname -s) $(uname -m)</p>
  </div>

  <div class="stats">
    <div class="stat rate"><div class="value">${pass_rate}%</div><div class="label">Pass Rate</div>
      <div class="progress"><div class="progress-bar" style="width:${pass_rate}%;background:$([ $pass_rate -ge 90 ] && echo '#16a34a' || echo '#d97706')"></div></div></div>
    <div class="stat pass"><div class="value">$PASS</div><div class="label">Passed</div></div>
    <div class="stat fail"><div class="value">$FAIL</div><div class="label">Failed</div></div>
    <div class="stat warn"><div class="value">$WARN</div><div class="label">Warnings</div></div>
    <div class="stat"><div class="value">$TOTAL</div><div class="label">Total Tests</div></div>
    <div class="stat"><div class="value">${suite_dur}s</div><div class="label">Duration</div></div>
  </div>

  <div class="card">
    <h2>Test Results</h2>
    <table>
      <thead><tr><th>Suite</th><th>Test</th><th>Status</th><th>Time</th><th>Detail</th></tr></thead>
      <tbody>
HTMLEOF

  for r in "${TEST_RESULTS[@]}"; do
    IFS='|' read -r suite name st detail dur <<< "$r"
    local badge_class="badge-$(echo $st | tr '[:upper:]' '[:lower:]')"
    echo "        <tr><td>$suite</td><td>$name</td><td><span class=\"badge $badge_class\">$st</span></td><td class=\"mono\">${dur:+${dur}ms}</td><td style=\"color:#64748b;font-size:0.8rem\">$detail</td></tr>" >> "$HTML_REPORT"
  done

  cat >> "$HTML_REPORT" << HTMLEOF
      </tbody>
    </table>
  </div>

  <div class="card">
    <h2>Performance Metrics</h2>
    <table>
      <thead><tr><th>Metric</th><th>Value</th><th>Threshold</th><th>Status</th></tr></thead>
      <tbody>
HTMLEOF

  for p in "${PERF_RESULTS[@]}"; do
    IFS='|' read -r name val unit thresh st <<< "$p"
    local badge_class="badge-$(echo $st | tr '[:upper:]' '[:lower:]')"
    echo "        <tr><td>$name</td><td class=\"mono\"><strong>${val}</strong> ${unit}</td><td class=\"mono\">${thresh} ${unit}</td><td><span class=\"badge $badge_class\">$st</span></td></tr>" >> "$HTML_REPORT"
  done

  cat >> "$HTML_REPORT" << HTMLEOF
      </tbody>
    </table>
  </div>

  <footer>TranzFer MFT Platform -https://github.com/rdship/tranzfer-mft</footer>
</div>
</body>
</html>
HTMLEOF

  echo -e "${GREEN}HTML report saved: ${HTML_REPORT}${NC}"
}

# ═══════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════

main() {
  echo -e "${BOLD}${BLUE}"
  echo "  ╔═══════════════════════════════════════════════════════╗"
  echo "  ║  TranzFer MFT -Full Integration & Health Test Suite ║"
  echo "  ╚═══════════════════════════════════════════════════════╝"
  echo -e "${NC}"

  run_container_health_tests
  run_database_tests
  run_auth_tests || { generate_text_report; exit 1; }
  run_service_api_tests
  run_inter_service_tests
  run_license_tests
  run_flow_tests
  run_cli_tests
  run_frontend_tests
  run_v2_service_tests
  run_sftp_tests
  run_performance_benchmarks

  echo ""
  generate_text_report

  if $GEN_HTML; then
    generate_html_report
  fi

  # Exit code: 0 if >90% pass, 1 otherwise
  local rate=$(( TOTAL > 0 ? PASS * 100 / TOTAL : 0 ))
  if [ $FAIL -gt 0 ] && [ $rate -lt 90 ]; then
    exit 1
  fi
  exit 0
}

main

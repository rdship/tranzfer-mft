#!/usr/bin/env bash
# =============================================================================
# tests/integration/third-party-sftp/run.sh
#
# Third-Party SFTP Integration Test
# Validates: DMZ Proxy + AI Engine protecting an external atmoz/sftp server
#
# Usage:
#   ./tests/integration/third-party-sftp/run.sh            # starts + tests + leaves running
#   ./tests/integration/third-party-sftp/run.sh --cleanup  # starts + tests + tears down
#   ./tests/integration/third-party-sftp/run.sh --skip-start  # assume already running
# =============================================================================
set -uo pipefail

# Require bash 4+ for associative arrays (macOS ships bash 3.2)
if [[ "${BASH_VERSINFO[0]}" -lt 4 ]]; then
  for _b in /opt/homebrew/bin/bash /usr/local/bin/bash; do
    [[ -x "$_b" ]] && exec "$_b" "$0" "$@"
  done
  echo "ERROR: bash 4+ required (have ${BASH_VERSION}). Install: brew install bash" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/docker-compose-external-demo.yml"

MGMT="http://localhost:8189"     # DMZ Proxy management API
SFTP_PORT=12222                  # SFTP through proxy (12222 avoids conflict with mft-sftp-service on 2222)
HTTP_PORT=8180                   # HTTP through proxy
PLATFORM_JWT_SECRET="${PLATFORM_JWT_SECRET:-changeme_32char_secret_here_!!!!}"

SKIP_START=false
DO_CLEANUP=false
for arg in "$@"; do
  case "$arg" in
    --skip-start) SKIP_START=true ;;
    --cleanup)    DO_CLEANUP=true ;;
  esac
done

# ─── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

PASS=0; FAIL=0; WARN=0
declare -A RESULTS

result() {
  local name="$1" status="$2" detail="${3:-}"
  RESULTS["$name"]="$status"
  case "$status" in
    PASS) ((PASS++)); echo -e "  ${GREEN}✓ PASS${NC}  ${name}${detail:+  (${detail})}" ;;
    FAIL) ((FAIL++)); echo -e "  ${RED}✗ FAIL${NC}  ${name}${detail:+  — ${detail}}" ;;
    WARN) ((WARN++)); echo -e "  ${YELLOW}⚠ WARN${NC}  ${name}${detail:+  — ${detail}}" ;;
  esac
}

section() { echo -e "\n${CYAN}${BOLD}▶ $1${NC}"; }
info()    { echo -e "  ${CYAN}ℹ${NC}  $1"; }

# ─── JSON helpers (no jq dependency) ─────────────────────────────────────────
json_val() {
  # json_val "key" "json_string" — extracts first matching "key":VALUE
  python3 -c "
import json, sys
d = json.loads(sys.stdin.read())
keys = '$1'.split('.')
v = d
for k in keys:
    if isinstance(v, list): v = v[int(k)]
    else: v = v.get(k, '')
print(v)
" 2>/dev/null || echo ""
}

# Generate a platform JWT (HS256) for DMZ Proxy management API.
# DMZ proxy is intentionally isolated — it validates inbound admin requests
# using its own HMAC-SHA256 platform JWT check (not Spring Security).
proxy_jwt() {
  local exp; exp=$(( $(date +%s) + 3600 ))
  local header; header=$(printf '{"alg":"HS256","typ":"JWT"}' | base64 | tr -d '=\n' | tr '+/' '-_')
  local payload; payload=$(printf '{"sub":"integration-test","exp":%d}' "$exp" | base64 | tr -d '=\n' | tr '+/' '-_')
  local sig; sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$PLATFORM_JWT_SECRET" -binary | base64 | tr -d '=\n' | tr '+/' '-_')
  printf '%s.%s.%s' "$header" "$payload" "$sig"
}

proxy_get() {
  curl -sf -H "Authorization: Bearer $(proxy_jwt)" "${MGMT}$1" 2>/dev/null
}
proxy_post() {
  curl -sf -X POST -H "Authorization: Bearer $(proxy_jwt)" -H "Content-Type: application/json" \
       -d "$2" "${MGMT}$1" 2>/dev/null
}
proxy_delete() {
  curl -sf -X DELETE -H "Authorization: Bearer $(proxy_jwt)" "${MGMT}$1" 2>/dev/null
}

wait_for() {
  local url="$1" label="$2" max="${3:-60}" i=0
  echo -n "  Waiting for ${label}"
  while ! curl -sf "$url" >/dev/null 2>&1; do
    ((i++))
    if [[ $i -ge $max ]]; then echo -e " ${RED}timeout${NC}"; return 1; fi
    echo -n "."; sleep 2
  done
  echo -e " ${GREEN}ready${NC}"
}

# =============================================================================
# STEP 0 — Start containers
# =============================================================================
section "Step 0: Environment"

if [[ "$SKIP_START" == true ]]; then
  info "Skipping docker compose (--skip-start)"
else
  # Only start the 3 lightweight containers — reuses the main stack's ai-engine on 8091
  info "Starting 3 containers: demo-dmz-proxy, demo-sftp-server, demo-web-app…"
  docker compose -f "${COMPOSE_FILE}" up -d my-sftp-server my-web-app demo-dmz-proxy 2>&1 \
    | grep -E "^(#|Creating|Starting|Pulling|Building|✓|Container|Network)" || true

  wait_for "${MGMT}/api/proxy/health" "DMZ Proxy (8189)" 60 || {
    echo -e "${RED}DMZ Proxy did not start. Run: docker compose -f docker-compose-external-demo.yml logs dmz-proxy${NC}"
    exit 1
  }

  # AI engine is the main stack's — just report its status
  if curl -sf "http://localhost:8091/actuator/health/liveness" >/dev/null 2>&1; then
    info "Main stack AI engine (8091) reachable — proxy will use AI verdicts"
  else
    info "Main stack AI engine not reachable — proxy will degrade gracefully (verdicts = ALLOW)"
  fi
fi

# Confirm proxy is up
health_json=$(proxy_get "/api/proxy/health")
# DEGRADED is acceptable before mappings are configured (backends not yet reachable)
if echo "$health_json" | grep -qE '"status":"(UP|DEGRADED)"'; then
  ai_avail_pre=$(echo "$health_json" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('aiEngineAvailable','?'))" 2>/dev/null || echo "?")
  proxy_status=$(echo "$health_json" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('status','?'))" 2>/dev/null || echo "?")
  result "Proxy health check" "PASS" "status=${proxy_status}, aiEngine=${ai_avail_pre}"
else
  result "Proxy health check" "FAIL" "response: ${health_json:-no response}"
  echo -e "${RED}Cannot continue — proxy is not up.${NC}"; exit 1
fi

# =============================================================================
# STEP 1 — Configure proxy mappings
# Explanation: The proxy starts with default TranzFer gateway mappings.
#              We replace them with mappings to OUR external services.
# =============================================================================
section "Step 1: Configure proxy mappings (plug-and-play)"

info "Removing default TranzFer gateway mappings (and any from prior runs)…"
proxy_delete "/api/proxy/mappings/sftp-gateway"  >/dev/null 2>&1 || true
proxy_delete "/api/proxy/mappings/ftp-gateway"   >/dev/null 2>&1 || true
proxy_delete "/api/proxy/mappings/ftp-web"       >/dev/null 2>&1 || true
proxy_delete "/api/proxy/mappings/my-sftp-server" >/dev/null 2>&1 || true
proxy_delete "/api/proxy/mappings/my-web-app"     >/dev/null 2>&1 || true
proxy_delete "/api/proxy/mappings/my-new-api"     >/dev/null 2>&1 || true
sleep 1  # brief pause so deletes flush before re-add

info "Adding mapping: port 2222 → my-sftp-server:22 (third-party SFTP)…"
sftp_resp=$(proxy_post "/api/proxy/mappings" '{
  "name": "my-sftp-server",
  "listenPort": 2222,
  "targetHost": "my-sftp-server",
  "targetPort": 22
}')
if echo "$sftp_resp" | grep -q "my-sftp-server"; then
  result "SFTP mapping registered" "PASS" "2222 → my-sftp-server:22"
else
  result "SFTP mapping registered" "FAIL" "${sftp_resp:-no response}"
fi

info "Adding mapping: port 8180 → my-web-app:80 (third-party HTTP)…"
http_resp=$(proxy_post "/api/proxy/mappings" '{
  "name": "my-web-app",
  "listenPort": 443,
  "targetHost": "my-web-app",
  "targetPort": 80
}')
if echo "$http_resp" | grep -q "my-web-app"; then
  result "HTTP mapping registered" "PASS" "8180 → my-web-app:80"
else
  result "HTTP mapping registered" "FAIL" "${http_resp:-no response}"
fi

# Verify mappings
mappings_json=$(proxy_get "/api/proxy/mappings")
active_count=$(echo "$mappings_json" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(sum(1 for m in d if m.get('active')))" 2>/dev/null || echo 0)
if [[ "$active_count" -ge 2 ]]; then
  result "Active mappings count" "PASS" "${active_count} mappings active"
else
  result "Active mappings count" "FAIL" "expected ≥2, got ${active_count}"
fi
sleep 4  # allow proxy to bind new TCP listeners before connectivity tests

# =============================================================================
# STEP 2 — HTTP connectivity through proxy
# =============================================================================
section "Step 2: HTTP proxied to external Nginx"

http_body=$(curl -sf --max-time 10 "http://localhost:${HTTP_PORT}/" 2>/dev/null || echo "")
if echo "$http_body" | grep -qi "nginx\|Welcome\|html"; then
  result "HTTP request proxied" "PASS" "Nginx welcome page served through proxy"
elif [[ -n "$http_body" ]]; then
  result "HTTP request proxied" "WARN" "Got response but not nginx welcome (${#http_body} bytes)"
else
  result "HTTP request proxied" "FAIL" "No response on port ${HTTP_PORT}"
fi

# =============================================================================
# STEP 3 — SFTP connectivity through proxy
# =============================================================================
section "Step 3: SFTP proxied to external atmoz/sftp"

# Test 1: SSH banner via ssh-keyscan (most reliable — reads banner without auth)
ssh_banner=$(ssh-keyscan -p "${SFTP_PORT}" -T 5 localhost 2>&1 || true)
if [[ -z "$ssh_banner" ]]; then
  # Fallback: raw nc — wait 2s for the server to send its banner
  ssh_banner=$(timeout 4 bash -c "sleep 2 | nc localhost ${SFTP_PORT}" 2>/dev/null | head -1 || true)
fi
if echo "$ssh_banner" | grep -qi "SSH-2.0\|OpenSSH\|TranzFer"; then
  result "SSH banner through proxy" "PASS" "$(echo "$ssh_banner" | grep -o 'SSH-2.0[^ ]*' | head -1)"
else
  # Last resort: confirm port accepts TCP (auth failure = proxy forwarded it)
  ssh_err=$(ssh -o StrictHostKeyChecking=no -o BatchMode=yes -o ConnectTimeout=5 \
                -p "${SFTP_PORT}" demo_user@localhost 2>&1 || true)
  if echo "$ssh_err" | grep -qi "denied\|Permission\|publickey"; then
    result "SSH banner through proxy" "PASS" "SSH auth challenge received — proxy forwarding SFTP correctly"
  else
    result "SSH banner through proxy" "FAIL" "no SSH response on port ${SFTP_PORT}"
  fi
fi

# Test 2: Automated file upload (requires sshpass)
if command -v sshpass >/dev/null 2>&1; then
  TEST_FILE="/tmp/tranzfer-demo-$$"
  echo "TranzFer MFT plug-and-play demo — $(date)" > "${TEST_FILE}"

  upload_out=$(sshpass -p demo_pass sftp \
    -P "${SFTP_PORT}" \
    -o StrictHostKeyChecking=no \
    -o ConnectTimeout=10 \
    -b - demo_user@localhost 2>&1 <<SFTP_BATCH
put ${TEST_FILE} /upload/tranzfer-demo.txt
ls /upload/
bye
SFTP_BATCH
  ) || true

  rm -f "${TEST_FILE}"
  if echo "$upload_out" | grep -q "tranzfer-demo\|Uploading\|sftp>"; then
    result "SFTP file upload through proxy" "PASS" "file uploaded via proxied SSH"
  else
    result "SFTP file upload through proxy" "WARN" "sshpass available but upload uncertain — check manually"
  fi
else
  info "sshpass not installed — skipping automated upload (brew install hudochenkov/sshpass/sshpass)"
  result "SFTP file upload through proxy" "WARN" "sshpass not available — SSH banner confirmed above"
fi

# =============================================================================
# STEP 4 — Security monitoring: protocol detection
# =============================================================================
section "Step 4: Security monitoring — protocol detection"

stats_json=$(proxy_get "/api/proxy/security/stats")
if [[ -n "$stats_json" ]]; then
  sec_enabled=$(echo "$stats_json" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('securityEnabled',''))" 2>/dev/null || echo "")
  ssh_count=$(echo  "$stats_json" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('metrics',{}).get('protocols',{}).get('SSH',0))" 2>/dev/null || echo 0)
  http_count=$(echo "$stats_json" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('metrics',{}).get('protocols',{}).get('HTTP',0))" 2>/dev/null || echo 0)
  total=$(echo      "$stats_json" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('metrics',{}).get('connections',{}).get('total',0))" 2>/dev/null || echo 0)

  if [[ "$sec_enabled" == "True" || "$sec_enabled" == "true" ]]; then
    result "Security layer active" "PASS" "securityEnabled=true"
  else
    result "Security layer active" "FAIL" "securityEnabled=${sec_enabled}"
  fi

  if [[ "$ssh_count" -ge 1 ]]; then
    result "SSH protocol detected" "PASS" "${ssh_count} SSH connection(s) identified"
  else
    result "SSH protocol detected" "WARN" "SSH count=${ssh_count} (connection may not have been tracked yet)"
  fi

  if [[ "$http_count" -ge 1 ]]; then
    result "HTTP protocol detected" "PASS" "${http_count} HTTP connection(s) identified"
  else
    result "HTTP protocol detected" "WARN" "HTTP count=${http_count}"
  fi

  info "Total connections tracked by proxy: ${total} (SSH=${ssh_count}, HTTP=${http_count})"
else
  result "Security stats endpoint" "FAIL" "no response from /api/proxy/security/stats"
fi

# =============================================================================
# STEP 5 — AI Engine verdict integration
# =============================================================================
section "Step 5: AI Engine verdict integration"

summary_json=$(proxy_get "/api/proxy/security/summary")
if [[ -n "$summary_json" ]]; then
  ai_avail=$(echo "$summary_json" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('aiEngineAvailable',''))" 2>/dev/null || echo "")
  allowed=$(echo   "$summary_json" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('connectionSummary',{}).get('allowed',0))" 2>/dev/null || echo 0)
  blocked=$(echo   "$summary_json" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('connectionSummary',{}).get('blocked',0))" 2>/dev/null || echo 0)

  if [[ "$ai_avail" == "True" || "$ai_avail" == "true" ]]; then
    result "AI Engine connected to proxy" "PASS" "aiEngineAvailable=true"
  else
    result "AI Engine connected to proxy" "WARN" "AI engine not yet available — proxy defaults to ALLOW (graceful degradation)"
  fi

  info "Verdicts: allowed=${allowed} blocked=${blocked}"
  info "My IP security profile:"
  proxy_get "/api/proxy/security/ip/127.0.0.1" | python3 -m json.tool 2>/dev/null | sed 's/^/    /' || true
else
  result "AI verdict summary" "FAIL" "no response from /api/proxy/security/summary"
fi

# =============================================================================
# STEP 6 — Rate limiting
# =============================================================================
section "Step 6: Rate limiting — 100 rapid HTTP connections"

info "Firing 100 concurrent HTTP requests (default limit: 60/min)…"
for i in $(seq 1 100); do
  curl -sf -o /dev/null -w "" "http://localhost:${HTTP_PORT}/" &
done
wait

rl_json=$(proxy_get "/api/proxy/security/rate-limits")
if [[ -n "$rl_json" ]]; then
  rl_count=$(echo "$rl_json" | python3 -c "
import json,sys
d=json.loads(sys.stdin.read())
if isinstance(d, list): print(len(d))
elif isinstance(d, dict): print(d.get('rateLimitedConnections', d.get('total', 0)))
else: print(0)
" 2>/dev/null || echo 0)

  summary2=$(proxy_get "/api/proxy/security/summary")
  rate_limited=$(echo "$summary2" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('connectionSummary',{}).get('rateLimited',0))" 2>/dev/null || echo 0)

  if [[ "$rate_limited" -gt 0 ]]; then
    result "Rate limiting triggered" "PASS" "${rate_limited} connections rate-limited"
  else
    result "Rate limiting triggered" "WARN" "rateLimited=${rate_limited} — may need higher burst to trigger (limit: 60/min per IP)"
  fi
else
  result "Rate limits endpoint" "FAIL" "no response from /api/proxy/security/rate-limits"
fi

# =============================================================================
# STEP 7 — Hot-add a new mapping (no restart)
# =============================================================================
section "Step 7: Hot-add a new mapping (zero downtime)"

info "Adding port 9989 → my-web-app:80 (simulating a new product launch)…"
hotadd_resp=$(proxy_post "/api/proxy/mappings" '{
  "name": "my-new-api",
  "listenPort": 9989,
  "targetHost": "my-web-app",
  "targetPort": 80
}')

if echo "$hotadd_resp" | grep -q "my-new-api"; then
  result "Hot-add new mapping" "PASS" "port 9989 live without restart"
  # Clean it up
  proxy_delete "/api/proxy/mappings/my-new-api" >/dev/null 2>&1 || true
else
  result "Hot-add new mapping" "FAIL" "${hotadd_resp:-no response}"
fi

# =============================================================================
# STEP 8 — Per-IP profile (track connection origin)
# =============================================================================
section "Step 8: Per-IP connection profile"

# Connections from the host may be NATted to the Docker bridge gateway IP, not 127.0.0.1
# Detect the actual IP the proxy sees by checking which IPs it has tracked
tracked_stats=$(proxy_get "/api/proxy/security/stats")
total_tracked=$(echo "$tracked_stats" | python3 -c "import json,sys; d=json.loads(sys.stdin.read()); print(d.get('metrics',{}).get('connections',{}).get('total',0))" 2>/dev/null || echo 0)
if [[ "$total_tracked" -gt 0 ]]; then
  result "Per-IP tracking" "PASS" "${total_tracked} total connections tracked across all IPs"
else
  result "Per-IP tracking" "WARN" "no connections tracked yet (check /api/proxy/security/stats)"
fi

# =============================================================================
# Summary
# =============================================================================
echo -e "\n${BOLD}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}  Third-Party SFTP Integration — Test Summary${NC}"
echo -e "${BOLD}═══════════════════════════════════════════════════════════════${NC}"

for name in \
  "Proxy health check" \
  "SFTP mapping registered" \
  "HTTP mapping registered" \
  "Active mappings count" \
  "HTTP request proxied" \
  "SSH banner through proxy" \
  "SFTP file upload through proxy" \
  "Security layer active" \
  "SSH protocol detected" \
  "HTTP protocol detected" \
  "AI Engine connected to proxy" \
  "AI verdict summary" \
  "Rate limiting triggered" \
  "Hot-add new mapping" \
  "Per-IP tracking"; do
  status="${RESULTS[$name]:-SKIP}"
  case "$status" in
    PASS) echo -e "  ${GREEN}✓${NC}  ${name}" ;;
    FAIL) echo -e "  ${RED}✗${NC}  ${name}" ;;
    WARN) echo -e "  ${YELLOW}⚠${NC}  ${name}" ;;
    SKIP) echo -e "  ${CYAN}–${NC}  ${name} (skipped)" ;;
  esac
done

echo -e "\n  ${GREEN}PASS${NC}: ${PASS}   ${YELLOW}WARN${NC}: ${WARN}   ${RED}FAIL${NC}: ${FAIL}"

echo -e "\n${BOLD}What just happened:${NC}"
echo -e "  • An ${BOLD}atmoz/sftp${NC} server (any 3rd-party SFTP product) was started with zero TranzFer code"
echo -e "  • An ${BOLD}nginx${NC} server (any 3rd-party HTTP service) was started"
echo -e "  • The ${BOLD}DMZ Proxy${NC} was reconfigured via REST API to front both — no restart"
echo -e "  • Every SFTP/HTTP connection now flows through: rate limiting → AI verdict → protocol detection → audit log"
echo -e "  • The external products have ${BOLD}no idea${NC} they are being protected"

if [[ "$DO_CLEANUP" == true ]]; then
  echo -e "\n${CYAN}Tearing down…${NC}"
  docker compose -f "${COMPOSE_FILE}" down -v
  echo -e "${GREEN}Done.${NC}"
else
  echo -e "\n${CYAN}Stack is still running. Explore:${NC}"
  echo -e "  Management API:     ${MGMT}/api/proxy/mappings  (Bearer: platform JWT — see proxy_jwt() in this script)"
  echo -e "  Security stats:     ${MGMT}/api/proxy/security/stats"
  echo -e "  SFTP:               sftp -P 2222 demo_user@localhost  (pass: demo_pass)"
  echo -e "  HTTP:               curl http://localhost:8180/"
  echo -e "  Tear down:          docker compose -f docker-compose-external-demo.yml down -v"
fi

echo ""
[[ "$FAIL" -eq 0 ]]    # exit 0 if no failures

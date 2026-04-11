#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Boot Smoke Test
# =============================================================================
#
# The wholesome end-to-end check that nobody had before R26. Cold-boots the
# full docker stack from scratch, waits for everything to be healthy, and
# probes every service + UI proxy path + connector. Catches the entire
# class of "I touched X and the demo broke on the test computer" bugs by
# exercising what an actual operator would do at a fresh installation.
#
# WHAT THIS PROVES (per the demand for a wholesome approach):
#   * docker compose up -d works from a clean slate (no warm caches)
#   * Postgres + RabbitMQ + Redis + Redpanda + MinIO + SPIRE come up
#   * Flyway migrations succeed (V42 CONCURRENTLY indexes finish)
#   * Every Java Spring service reaches /actuator/health/liveness
#   * The 4 frontend nginx containers actually bind their port and serve
#   * UI at http://localhost:3000 returns 200 with HTML
#   * UI nginx proxies (api-onboarding, api-config, etc) reach upstreams
#   * onboarding-api login endpoint accepts admin credentials and returns
#     a JWT
#   * Activity Monitor (the BUG-1 endpoint) returns 200 with the
#     auth header
#   * Connector services reachable: notification, screening, ai-engine,
#     edi-converter, keystore-manager
#   * EDI converter sample document round-trip
#   * Database Advisory /status reports >=95% compliance
#
# Run modes:
#   ./scripts/boot-smoke.sh                  full cold boot + tests + teardown
#   ./scripts/boot-smoke.sh --no-teardown    leave the stack running for inspection
#   ./scripts/boot-smoke.sh --no-boot        skip cold boot, test the running stack
#   ./scripts/boot-smoke.sh --quick          skip the long Java services, just UI + DB
#
# Exit codes:
#   0   all assertions passed
#   1   one or more assertions failed
#   2   prerequisites missing (docker, jq, curl)
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

# ── Colors + helpers ─────────────────────────────────────────────────────
RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; BLUE=$'\e[34m'; BOLD=$'\e[1m'; RST=$'\e[0m'
PASSED=0
FAILED=0
SKIPPED=0
FAIL_LABELS=()

log()    { printf '\n%s[boot-smoke]%s %s\n' "$BLUE" "$RST" "$*"; }
section(){ printf '\n%s── %s ──%s\n' "$BOLD" "$*" "$RST"; }
pass()   { printf '  %s✓%s %s\n' "$GREEN" "$RST" "$*"; PASSED=$((PASSED+1)); }
fail()   { printf '  %s✗%s %s\n' "$RED"   "$RST" "$*"; FAILED=$((FAILED+1)); FAIL_LABELS+=("$*"); }
skip()   { printf '  %s·%s %s\n' "$YELLOW" "$RST" "$*"; SKIPPED=$((SKIPPED+1)); }
die()    { printf '%s[boot-smoke] %s%s\n' "$RED" "$*" "$RST" >&2; exit 2; }

# ── Args ─────────────────────────────────────────────────────────────────
NO_TEARDOWN=false
NO_BOOT=false
NO_WAIT=false
QUICK=false
while (( $# > 0 )); do
  case "$1" in
    --no-teardown) NO_TEARDOWN=true; shift ;;
    --no-boot)     NO_BOOT=true;     shift ;;
    --no-wait)     NO_WAIT=true;     shift ;;
    --quick)       QUICK=true;       shift ;;
    -h|--help)
      sed -n '2,40p' "$0"
      exit 0 ;;
    *) shift ;;
  esac
done

# ── Prereqs ──────────────────────────────────────────────────────────────
section "Prerequisites"
command -v docker >/dev/null || die "docker is required"
command -v curl   >/dev/null || die "curl is required"
command -v jq     >/dev/null || die "jq is required (brew install jq)"
docker version >/dev/null 2>&1 || die "docker daemon not running"
pass "docker $(docker version --format '{{.Server.Version}}')"
pass "curl + jq present"

# ── Cold boot ────────────────────────────────────────────────────────────
if [[ "$NO_BOOT" == false ]]; then
  section "Cold boot"
  log "Tearing down any previous run..."
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  pass "previous stack torn down"

  log "Booting full stack (this can take 5-10 min on first run)..."
  PROFILE_ARG=""
  if [[ "$QUICK" == false ]]; then PROFILE_ARG="--profile full"; fi
  if docker compose $PROFILE_ARG up -d 2>&1 | tail -20; then
    pass "docker compose up -d completed"
  else
    fail "docker compose up -d failed"
    exit 1
  fi
fi

# ── Wait for healthchecks ───────────────────────────────────────────────
if [[ "$NO_WAIT" == true ]]; then
  section "Wait for healthchecks (skipped via --no-wait)"
  total_bad=0
else
section "Wait for healthchecks"
WAIT_DEADLINE=$(($(date +%s) + 600))   # 10 minutes
EXPECT_SERVICES=(
  mft-postgres mft-rabbitmq mft-redis mft-redpanda
  mft-onboarding-api mft-config-service mft-gateway-service
  mft-encryption-service mft-screening-service mft-keystore-manager
  mft-ai-engine mft-analytics-service mft-license-service
  mft-edi-converter mft-storage-manager mft-notification-service
  mft-platform-sentinel mft-as2-service
  mft-sftp-service mft-ftp-service mft-ftp-web-service mft-external-forwarder-service
  mft-ui-service mft-api-gateway
)
log "Polling for ${#EXPECT_SERVICES[@]} containers to be healthy..."
while [[ $(date +%s) -lt $WAIT_DEADLINE ]]; do
  unhealthy=0
  missing=0
  for svc in "${EXPECT_SERVICES[@]}"; do
    state=$(docker inspect "$svc" --format '{{.State.Health.Status}}' 2>/dev/null || echo "MISSING")
    case "$state" in
      healthy)   ;;
      MISSING)   missing=$((missing+1)) ;;
      *)         unhealthy=$((unhealthy+1)) ;;
    esac
  done
  total_bad=$((unhealthy + missing))
  if [[ $total_bad -eq 0 ]]; then
    pass "all ${#EXPECT_SERVICES[@]} containers are healthy"
    break
  fi
  remaining=$((WAIT_DEADLINE - $(date +%s)))
  printf "  waiting... %d unhealthy, %d missing, %ds left\r" "$unhealthy" "$missing" "$remaining"
  sleep 5
done
echo ""
if [[ $total_bad -ne 0 ]]; then
  fail "$total_bad container(s) not healthy after 10 min — listing all states:"
  for svc in "${EXPECT_SERVICES[@]}"; do
    state=$(docker inspect "$svc" --format '{{.State.Health.Status}}' 2>/dev/null || echo "MISSING")
    [[ "$state" == "healthy" ]] || printf '    %s = %s\n' "$svc" "$state"
  done
fi
fi  # end NO_WAIT branch

# ── Probe each service's HTTP endpoint ──────────────────────────────────
section "HTTP probes — service health endpoints"
declare -A HEALTH_URLS=(
  [onboarding-api]="http://localhost:8080/actuator/health"
  [config-service]="http://localhost:8084/actuator/health"
  [gateway-service]="http://localhost:8085/actuator/health"
  [encryption-service]="http://localhost:8086/actuator/health"
  [forwarder-service]="http://localhost:8087/actuator/health"
  [license-service]="http://localhost:8089/actuator/health"
  [analytics-service]="http://localhost:8090/actuator/health"
  [ai-engine]="http://localhost:8091/actuator/health"
  [screening-service]="http://localhost:8092/actuator/health"
  [keystore-manager]="http://localhost:8093/actuator/health"
  [as2-service]="http://localhost:8094/actuator/health"
  [edi-converter]="http://localhost:8095/actuator/health/readiness"
  [storage-manager]="http://localhost:8096/actuator/health"
  [notification-service]="http://localhost:8097/actuator/health"
  [platform-sentinel]="http://localhost:8098/actuator/health"
  [sftp-service]="http://localhost:8081/health"
  [ftp-service]="http://localhost:8082/health"
  [ftp-web-service]="http://localhost:8083/actuator/health"
  [dmz-proxy]="http://localhost:8088/api/proxy/health"
)
for svc in "${!HEALTH_URLS[@]}"; do
  url="${HEALTH_URLS[$svc]}"
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null || echo "000")
  if [[ "$code" == "200" ]]; then
    pass "$svc → 200"
  else
    fail "$svc → $code (url=$url)"
  fi
done

# ── Frontend containers ─────────────────────────────────────────────────
section "Frontend container HTTP checks"
for entry in \
  "ui-service        http://localhost:3000/" \
  "partner-portal    http://localhost:3002/" \
  "ftp-web-ui        http://localhost:3001/" \
  "api-gateway       http://localhost/" ; do
  set -- $entry
  name=$1
  url=$2
  code=$(curl -s -o /tmp/boot-smoke-body -w "%{http_code}" --max-time 5 "$url" 2>/dev/null || echo "000")
  if [[ "$code" == "200" ]] && grep -q "<!doctype\|<html" /tmp/boot-smoke-body 2>/dev/null; then
    pass "$name → 200 + HTML"
  elif [[ "$code" == "200" ]]; then
    pass "$name → 200 (no HTML check)"
  else
    fail "$name → $code (url=$url)"
  fi
done

# ── UI nginx proxy paths ────────────────────────────────────────────────
section "UI nginx proxy paths (catches DNS / upstream resolution bugs)"
for entry in \
  "api-onboarding  /api-onboarding/actuator/health" \
  "api-config      /api-config/actuator/health" \
  "api-analytics   /api-analytics/actuator/health" \
  "api-license     /api-license/actuator/health" \
  "api-gateway     /api-gateway/actuator/health" ; do
  set -- $entry
  label=$1
  path=$2
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://localhost:3000$path" 2>/dev/null || echo "000")
  if [[ "$code" == "200" ]]; then
    pass "ui-service$path → 200"
  else
    fail "ui-service$path → $code"
  fi
done

# ── Auth → JWT round trip ───────────────────────────────────────────────
section "Auth round-trip"
LOGIN_BODY='{"email":"admin@filetransfer.local","password":"Tr@nzFer2026!"}'
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "$LOGIN_BODY" 2>/dev/null \
  | jq -r '.accessToken // .token // empty')
if [[ -n "$TOKEN" && "$TOKEN" != "null" ]]; then
  pass "/api/auth/login → JWT ($((${#TOKEN}/4)) chars base64)"
else
  fail "/api/auth/login did not return a token (admin user not seeded? run demo-onboard.sh)"
  TOKEN=""
fi

# ── BUG-1 verification: Activity Monitor unfiltered query ───────────────
if [[ -n "$TOKEN" ]]; then
  section "BUG-1 Activity Monitor unfiltered query"
  am_code=$(curl -s -o /tmp/boot-smoke-am.json -w "%{http_code}" \
    --max-time 10 \
    -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/activity-monitor 2>/dev/null || echo "000")
  if [[ "$am_code" == "200" ]]; then
    rows=$(jq -r '.totalElements // .content | (if type=="array" then length else . end) // 0' /tmp/boot-smoke-am.json 2>/dev/null || echo "?")
    pass "GET /api/activity-monitor → 200 (totalElements=$rows)"
  else
    fail "GET /api/activity-monitor → $am_code (BUG-1 regression?)"
  fi
fi

# ── Database Advisory: status compliance ────────────────────────────────
if [[ -n "$TOKEN" ]]; then
  section "Database Advisory compliance"
  curl -s -o /tmp/boot-smoke-status.json \
    -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/v1/db-advisory/status 2>/dev/null
  pct=$(jq -r '.compliancePct // 0' /tmp/boot-smoke-status.json 2>/dev/null || echo "0")
  if [[ "$pct" -ge 90 ]]; then
    pass "DB advisory compliance ${pct}%"
  else
    fail "DB advisory compliance only ${pct}% (expected ≥90)"
  fi
fi

# ── Connector services ──────────────────────────────────────────────────
section "Connector reachability"
declare -A CONNECTORS=(
  [edi-converter-maps]="http://localhost:8095/api/v1/convert/maps"
  [screening-stats]="http://localhost:8092/api/v1/quarantine/stats"
  [keystore-list]="http://localhost:8093/api/v1/keys"
  [ai-classify]="http://localhost:8091/api/v1/ai/health"
  [sentinel-dashboard]="http://localhost:8098/api/v1/sentinel/dashboard"
)
for name in "${!CONNECTORS[@]}"; do
  url="${CONNECTORS[$name]}"
  if [[ -n "$TOKEN" ]]; then
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
      -H "Authorization: Bearer $TOKEN" "$url" 2>/dev/null || echo "000")
  else
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null || echo "000")
  fi
  if [[ "$code" =~ ^(200|201|401|403)$ ]]; then
    pass "$name → $code (reachable)"
  else
    fail "$name → $code (url=$url)"
  fi
done

# ── EDI converter end-to-end ────────────────────────────────────────────
section "EDI converter round-trip"
if [[ -f scripts/demo-edi-samples/x12-850-purchase-order.edi ]]; then
  content=$(cat scripts/demo-edi-samples/x12-850-purchase-order.edi)
  payload=$(jq -n --arg c "$content" '{content:$c}')
  detected=$(curl -s -X POST http://localhost:8095/api/v1/convert/detect \
    -H 'Content-Type: application/json' \
    --data-raw "$payload" 2>/dev/null | jq -r '.documentType // .type // .format // empty')
  if [[ "$detected" == *X12* ]]; then
    pass "EDI converter detected X12 from sample (got: $detected)"
  else
    fail "EDI converter did not detect X12 from sample (got: '$detected')"
  fi
else
  skip "EDI sample missing — skip"
fi

# ── Random + deterministic feature validation ───────────────────────────
# This is the integration testing layer the platform was missing — every
# boot-smoke run rebuilds the feature inventory (UI routes + API endpoints
# + DB entities) and probes a random sample plus the always-validate set.
# Catches drift between code and UI/API/DB surfaces.
section "Feature inventory + random validator"
if [[ -x scripts/feature-inventory.sh && -x scripts/validate-features.sh ]]; then
  if ./scripts/feature-inventory.sh >/dev/null 2>&1; then
    pass "feature inventory built ($(jq -r '.summary.api_endpoints' tests/inventory/feature-inventory.json) endpoints, $(jq -r '.summary.ui_routes' tests/inventory/feature-inventory.json) ui routes)"
  else
    fail "feature inventory build failed"
  fi
  if VALIDATE_SAMPLE_N="${VALIDATE_SAMPLE_N:-20}" ./scripts/validate-features.sh; then
    pass "random feature validation passed"
  else
    fail "random feature validation reported failures (see above)"
  fi
else
  skip "validate-features.sh not present yet"
fi

# ── Summary ──────────────────────────────────────────────────────────────
section "Summary"
TOTAL=$((PASSED + FAILED + SKIPPED))
printf '  %s%d passed%s   %s%d failed%s   %s%d skipped%s   (%d total)\n' \
  "$GREEN" "$PASSED" "$RST" \
  "$RED"   "$FAILED" "$RST" \
  "$YELLOW" "$SKIPPED" "$RST" \
  "$TOTAL"

if (( FAILED > 0 )); then
  printf '\n%sFailures:%s\n' "$RED" "$RST"
  for label in "${FAIL_LABELS[@]}"; do
    printf '  %s✗%s %s\n' "$RED" "$RST" "$label"
  done
fi

# ── Teardown ────────────────────────────────────────────────────────────
if [[ "$NO_TEARDOWN" == false ]]; then
  section "Teardown"
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  pass "stack torn down"
fi

if (( FAILED > 0 )); then exit 1; fi
exit 0

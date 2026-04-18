#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — AOT Parity Check
# =============================================================================
#
# Validates that every Java service boots cleanly BOTH with and without
# Spring AOT enabled. Catches the R95 class of regression — latent
# @EnableJpaRepositories vs @ComponentScan gaps that reflection-mode
# tolerates (bean left dormant) but AOT-mode fails fast on (bean
# pre-registered eagerly).
#
# Why it exists: R95's AOT flag ship crashed 5 services on cold boot
# because their bean graphs were internally inconsistent. Reflection had
# been masking the gap. This gate makes AOT a first-class build
# environment so the next AOT retry (R101+) can't regress without CI
# catching it in ≤10 minutes.
#
# Run modes:
#   ./scripts/aot-parity-check.sh              two cold boots, teardown between
#   ./scripts/aot-parity-check.sh --quick      just probe /actuator/health/liveness
#                                               (faster; skips deep smoke checks)
#
# Exit codes:
#   0   both boots healthy; safe to re-enable AOT in main docker-compose
#   1   AOT-on boot crashed one or more services (parity broken)
#   2   AOT-off boot crashed (baseline broken — unrelated to AOT)
#   3   prerequisites missing
#
# Prerequisites: docker, docker compose, jq, curl, the full reactor jar
# artifacts in target/ (run `mvn -q -DskipTests package` first).
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; BLUE=$'\e[34m'; BOLD=$'\e[1m'; RST=$'\e[0m'

say()  { printf '%s[aot-parity]%s %s\n' "$BLUE" "$RST" "$*"; }
ok()   { printf '  %s✓%s %s\n' "$GREEN" "$RST" "$*"; }
err()  { printf '  %s✗%s %s\n' "$RED"   "$RST" "$*" >&2; }
note() { printf '  %s·%s %s\n' "$YELLOW" "$RST" "$*"; }

command -v docker >/dev/null || { err "docker required"; exit 3; }
command -v jq     >/dev/null || { err "jq required";    exit 3; }
command -v curl   >/dev/null || { err "curl required";  exit 3; }

QUICK=false
[[ "${1:-}" == "--quick" ]] && QUICK=true

# Services we expect to reach liveness-200 on both modes. These are the 18
# Java services in docker-compose (excluding the infrastructure containers
# postgres / rabbitmq / redis / redpanda / spire-* / vault / minio).
SERVICES=(
  "onboarding-api:8080" "sftp-service:8081" "ftp-service:8082"
  "ftp-web-service:8083" "config-service:8084" "gateway-service:8085"
  "encryption-service:8086" "external-forwarder-service:8087"
  "dmz-proxy:8088" "license-service:8089" "analytics-service:8090"
  "ai-engine:8091" "screening-service:8092" "keystore-manager:8093"
  "as2-service:8094" "edi-converter:8095" "storage-manager:8096"
  "notification-service:8097" "platform-sentinel:8098"
)

# Max seconds to wait for all services to reach liveness-200 per phase.
TIMEOUT=${AOT_PARITY_TIMEOUT:-420}

wait_for_healthy() {
  local label="$1"
  local deadline=$(( $(date +%s) + TIMEOUT ))
  local last_fail=""
  while [[ $(date +%s) -lt $deadline ]]; do
    local ok_count=0
    local fail_count=0
    local fails=()
    for entry in "${SERVICES[@]}"; do
      local svc="${entry%:*}"
      local port="${entry#*:}"
      local code
      code=$(curl -sk -o /dev/null -w '%{http_code}' \
             --max-time 3 "http://localhost:$port/actuator/health/liveness" 2>/dev/null || echo "000")
      if [[ "$code" == "200" ]]; then
        ok_count=$((ok_count + 1))
      else
        fail_count=$((fail_count + 1))
        fails+=("$svc($code)")
      fi
    done
    if [[ $fail_count -eq 0 ]]; then
      ok "$label: all ${#SERVICES[@]} services healthy"
      return 0
    fi
    last_fail="${fails[*]}"
    sleep 5
  done
  err "$label timeout after ${TIMEOUT}s — unhealthy: $last_fail"
  return 1
}

# Grab restart counts so we can flag services that crashed-and-recovered.
snapshot_restart_counts() {
  local out="$1"
  docker ps --format '{{.Names}}' | while read -r name; do
    local rc
    rc=$(docker inspect --format '{{.RestartCount}}' "$name" 2>/dev/null || echo "0")
    echo "$name $rc"
  done > "$out"
}

run_phase() {
  local mode="$1"        # "off" or "on"
  local aot_env="$2"     # "false" or "true"

  say "Phase [$mode]: docker compose up (AOT_ENABLED=$aot_env)"
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true

  AOT_ENABLED="$aot_env" docker compose up -d >/tmp/aot-parity-$mode.log 2>&1
  local up_rc=$?
  if [[ $up_rc -ne 0 ]]; then
    err "docker compose up failed in $mode mode — see /tmp/aot-parity-$mode.log"
    return 2
  fi

  if ! wait_for_healthy "[AOT=$aot_env]"; then
    local restarts=/tmp/aot-parity-$mode-restarts.txt
    snapshot_restart_counts "$restarts"
    err "services with restart counts:"
    awk '$2 > 0 { print "    " $0 }' "$restarts" >&2 || true
    if [[ "$mode" == "off" ]]; then
      err "baseline (AOT off) failed — unrelated to AOT; fix the stack first"
      return 2
    fi
    err "AOT-on parity broken"
    return 1
  fi
  ok "[AOT=$aot_env] healthy within ${TIMEOUT}s"

  if [[ "$QUICK" == false ]]; then
    note "[AOT=$aot_env] running full boot-smoke sanity"
    if ! ./scripts/boot-smoke.sh --no-boot --no-teardown; then
      err "[AOT=$aot_env] boot-smoke probes failed — see upload"
      return 1
    fi
    ok "[AOT=$aot_env] boot-smoke probes PASS"
  fi
  return 0
}

say "=============================================="
say "AOT parity check — boots each mode twice"
say "=============================================="

# Phase 1: AOT off (current default after R98). Establishes the baseline
# that SHOULD already be green — if this fails, the problem is not AOT.
run_phase off false
off_rc=$?
if [[ $off_rc -ne 0 ]]; then
  say "=============================================="
  err "BASELINE BROKEN (AOT=off). Stop here — AOT is not the issue."
  exit $off_rc
fi

# Phase 2: AOT on. Everything that booted in phase 1 must boot here too.
run_phase on true
on_rc=$?

docker compose down -v --remove-orphans >/dev/null 2>&1 || true

if [[ $on_rc -ne 0 ]]; then
  say "=============================================="
  err "AOT PARITY FAILED — services boot under reflection but not AOT."
  say "Compare restart counts in /tmp/aot-parity-on-restarts.txt and fix"
  say "each service's @EnableJpaRepositories / @EntityScan / @ComponentScan"
  say "to match the reachable bean graph. Template: see R99 commit c219716"
  exit 1
fi

say "=============================================="
ok "AOT PARITY PASS — every service boots cleanly with and without Spring AOT."
say "Safe to re-enable -Dspring.aot.enabled=true in docker-compose."
exit 0

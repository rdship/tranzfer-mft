#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — First-Boot-Healthy Gate
# =============================================================================
#
# Strict CI gate: fails the build if ANY Java service restarts even ONCE
# during cold boot. Zero tolerance for restart loops.
#
# Why this exists: R118 and R119 both shipped with 6-12 services in
# permanent restart loops at cold boot. Both passed the existing
# aot-parity-check.sh because that gate only asserts "eventually healthy"
# (services boot → crash → restart → boot again → pass). The acceptance
# report caught both, but on the tester's side, not in our CI.
#
# This gate closes that loop: a service that needed even 1 restart to
# reach healthy is reported as FAIL, with the specific service + crash
# signature. Catches the R117→R118→R119 class of bug before merge.
#
# Run modes:
#   ./scripts/first-boot-healthy-gate.sh          cold boot + strict gate + teardown
#   ./scripts/first-boot-healthy-gate.sh --keep   leave stack running on failure
#
# Environment:
#   FIRST_BOOT_TIMEOUT     max seconds to wait for healthy (default 600)
#   FIRST_BOOT_AOT         "true" | "false" (default "true" — matches docker default)
#
# Exit codes:
#   0   all Java services reached healthy on FIRST attempt
#   1   one or more services restarted → boot is not stable
#   2   one or more services never reached healthy → baseline broken
#   3   prerequisites missing
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; BLUE=$'\e[34m'; BOLD=$'\e[1m'; RST=$'\e[0m'

say()  { printf '%s[first-boot-gate]%s %s\n' "$BLUE" "$RST" "$*"; }
ok()   { printf '  %s✓%s %s\n' "$GREEN" "$RST" "$*"; }
err()  { printf '  %s✗%s %s\n' "$RED"   "$RST" "$*" >&2; }
note() { printf '  %s·%s %s\n' "$YELLOW" "$RST" "$*"; }

command -v docker >/dev/null || { err "docker required"; exit 3; }

KEEP=false
[[ "${1:-}" == "--keep" ]] && KEEP=true

TIMEOUT="${FIRST_BOOT_TIMEOUT:-600}"
AOT="${FIRST_BOOT_AOT:-true}"

# The Java services we expect to reach healthy WITHOUT restarting on the
# DEFAULT docker-compose profile (no --profile flags). Replicas and
# external-proxy are opt-in via profiles: ["replicas"] / ["external-proxy"]
# so listing them here produced false positives against phantom containers
# that were never started. Keep this list narrow; a separate profile-aware
# gate can cover the replica set when we start shipping that scale-out
# topology in CI.
EXPECT_CONTAINERS=(
  mft-onboarding-api
  mft-config-service mft-gateway-service
  mft-encryption-service mft-forwarder-service
  mft-license-service mft-analytics-service
  mft-ai-engine
  mft-screening-service mft-keystore-manager
  mft-as2-service mft-edi-converter
  mft-storage-manager mft-notification-service
  mft-platform-sentinel
  mft-sftp-service
  mft-ftp-service
  mft-ftp-web-service
  mft-dmz-proxy-internal
)

say "=============================================="
say "First-Boot-Healthy Gate  (AOT_ENABLED=$AOT, timeout=${TIMEOUT}s)"
say "  zero-tolerance: any container restart = FAIL"
say "=============================================="

# ── Fresh-start every CI run to simulate a real cold boot ────────────────────
say "Tearing down any previous stack..."
docker compose down -v --remove-orphans >/dev/null 2>&1 || true
ok "previous stack gone"

# ── Cold boot ────────────────────────────────────────────────────────────────
say "docker compose up -d"
if ! AOT_ENABLED="$AOT" docker compose up -d >/tmp/first-boot.log 2>&1; then
  err "docker compose up failed — see /tmp/first-boot.log"
  exit 2
fi
ok "compose up completed"

# ── Poll until all healthy OR any restart detected ───────────────────────────
say "Polling ${#EXPECT_CONTAINERS[@]} containers..."
DEADLINE=$(( $(date +%s) + TIMEOUT ))
FIRST_RESTART_SEEN=false
RESTART_REPORT=""

while [[ $(date +%s) -lt $DEADLINE ]]; do
  all_healthy=true
  any_restarted=false
  unhealthy_list=()
  restart_list=()

  for svc in "${EXPECT_CONTAINERS[@]}"; do
    state=$(docker inspect "$svc" --format '{{.State.Health.Status}}' 2>/dev/null | tr -d '\n\r ' || echo "MISSING")
    [[ -z "$state" ]] && state="MISSING"
    rc=$(docker inspect "$svc"   --format '{{.RestartCount}}'       2>/dev/null | tr -d '\n\r ' || echo "0")
    [[ -z "$rc" ]] && rc="0"

    if [[ "$rc" != "0" ]]; then
      any_restarted=true
      restart_list+=("$svc (restarts=$rc)")
    fi
    if [[ "$state" != "healthy" ]]; then
      all_healthy=false
      unhealthy_list+=("$svc=$state")
    fi
  done

  if [[ "$any_restarted" == true ]]; then
    # Don't exit immediately — gather full picture on first detection
    if [[ "$FIRST_RESTART_SEEN" == false ]]; then
      FIRST_RESTART_SEEN=true
      RESTART_REPORT="${restart_list[*]}"
      err "RESTART DETECTED — first-attempt boot failed for: $RESTART_REPORT"
      note "Collecting logs for next 30s so the crash cause is captured..."
      sleep 30
      break
    fi
  fi

  if [[ "$all_healthy" == true && "$any_restarted" == false ]]; then
    ok "all ${#EXPECT_CONTAINERS[@]} containers reached healthy on first attempt"
    FIRST_RESTART_SEEN=false
    break
  fi

  remaining=$((DEADLINE - $(date +%s)))
  printf "\r  %s·%s %d/%d healthy, %d still booting, %ds left     " \
    "$YELLOW" "$RST" \
    $(( ${#EXPECT_CONTAINERS[@]} - ${#unhealthy_list[@]} )) \
    "${#EXPECT_CONTAINERS[@]}" \
    "${#unhealthy_list[@]}" \
    "$remaining"
  sleep 5
done
echo ""

# ── Verdict ──────────────────────────────────────────────────────────────────
EXIT=0
if [[ "$FIRST_RESTART_SEEN" == true ]]; then
  say "=============================================="
  err "FIRST-BOOT GATE FAILED — services restarted on first boot attempt"
  err "  services with restarts:"
  for svc in "${EXPECT_CONTAINERS[@]}"; do
    rc=$(docker inspect "$svc" --format '{{.RestartCount}}' 2>/dev/null | tr -d '\n\r ' || echo "0")
    [[ -z "$rc" ]] && rc="0"
    if [[ "$rc" != "0" ]]; then
      # Print last 15 log lines to surface the crash signature
      printf '\n    %s✗ %s (restarts=%s)%s\n' "$RED" "$svc" "$rc" "$RST" >&2
      docker logs "$svc" 2>&1 | tail -15 | sed 's/^/        /' >&2 || true
    fi
  done
  say ""
  say "This gate blocks merge on any restart-loop regression."
  say "Root causes seen historically:"
  say "  - Bean's @Autowired constructor dep was @ConditionalOnProperty-gated with narrower scope"
  say "  - @EnableJpaRepositories missing a shared repo package"
  say "  - AOT + Spring condition mismatch (see docs/AOT-SAFETY.md)"
  EXIT=1
else
  # All good — also sanity-check no container is UNHEALTHY (late failure).
  bad=0
  for svc in "${EXPECT_CONTAINERS[@]}"; do
    state=$(docker inspect "$svc" --format '{{.State.Health.Status}}' 2>/dev/null || echo "MISSING")
    if [[ "$state" != "healthy" ]]; then
      err "$svc state=$state (never reached healthy)"
      docker logs "$svc" 2>&1 | tail -10 | sed 's/^/      /' >&2 || true
      bad=$((bad + 1))
    fi
  done
  if [[ $bad -gt 0 ]]; then
    err "$bad containers never reached healthy within ${TIMEOUT}s"
    EXIT=2
  else
    say "=============================================="
    ok "FIRST-BOOT GATE PASS — every service healthy on first attempt, zero restarts."
  fi
fi

# ── Teardown unless --keep ───────────────────────────────────────────────────
if [[ "$KEEP" == false ]]; then
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true
fi

exit $EXIT

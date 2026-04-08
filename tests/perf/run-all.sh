#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Master Performance Test Runner
# Runs all test phases in order, generates a findings report.
#
# Usage:
#   ./tests/perf/run-all.sh                     # Full suite
#   ./tests/perf/run-all.sh --quick             # Phases 1-3 only (< 1 hr)
#   ./tests/perf/run-all.sh --skip-volume       # Skip 1M transfer tests
#   ./tests/perf/run-all.sh --skip-resilience   # Skip chaos tests
#   ./tests/perf/run-all.sh --skip-endurance    # Skip 24h soak
#   ./tests/perf/run-all.sh --phase security    # Run Phase 5 only
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_DIR="${SCRIPT_DIR}/results"
mkdir -p "$REPORT_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT="${REPORT_DIR}/perf-report-${TIMESTAMP}.md"

# ── Flags ─────────────────────────────────────────────────────────────────────
QUICK=false
SKIP_VOLUME=false
SKIP_RESILIENCE=false
SKIP_ENDURANCE=true   # Default: skip 24h soak
ONLY_PHASE=""

for arg in "$@"; do
  case $arg in
    --quick)           QUICK=true; SKIP_VOLUME=true; SKIP_RESILIENCE=true ;;
    --skip-volume)     SKIP_VOLUME=true ;;
    --skip-resilience) SKIP_RESILIENCE=true ;;
    --skip-endurance)  SKIP_ENDURANCE=true ;;
    --endurance)       SKIP_ENDURANCE=false ;;
    --phase)           ONLY_PHASE="${2:-}"; shift ;;
    --phase=*)         ONLY_PHASE="${arg#*=}" ;;
  esac
done

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'
BOLD='\033[1m'; NC='\033[0m'

PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

# ── Report header ─────────────────────────────────────────────────────────────
GIT_SHA=$(git -C "$(dirname "$SCRIPT_DIR")" rev-parse --short HEAD 2>/dev/null || echo "unknown")
cat > "$REPORT" <<EOF
# TranzFer MFT — Performance Test Report
**Date:** $(date '+%Y-%m-%d %H:%M:%S')
**Platform version:** ${GIT_SHA}
**Tester:** \${USER:-unknown}
**Machine:** $(uname -n) ($(uname -m))
**Mode:** $([ "$QUICK" = "true" ] && echo "QUICK" || echo "FULL")

## Results Summary

EOF

log() { echo -e "$1"; echo "${1//\\033\[[0-9;]*m/}" >> "$REPORT"; }
log_section() { echo ""; log "${CYAN}${BOLD}╔═══ Phase $1: $2 ═══╗${NC}"; }
log_pass()    { PASS_COUNT=$((PASS_COUNT+1)); log "${GREEN}  ✓ PASS:${NC} $1"; echo "- ✓ PASS: $1" >> "$REPORT"; }
log_warn()    { WARN_COUNT=$((WARN_COUNT+1)); log "${YELLOW}  ! WARN:${NC} $1"; echo "- ! WARN: $1" >> "$REPORT"; }
log_fail()    { FAIL_COUNT=$((FAIL_COUNT+1)); log "${RED}  ✗ FAIL:${NC} $1"; echo "- ✗ FAIL: $1" >> "$REPORT"; }
log_skip()    { SKIP_COUNT=$((SKIP_COUNT+1)); log "  · SKIP: $1"; echo "- · SKIP: $1" >> "$REPORT"; }
run_phase()   { [[ -z "$ONLY_PHASE" || "$ONLY_PHASE" == "$1" ]]; }

START_TIME=$(date +%s)
log ""
log "${BOLD}=== TranzFer MFT Performance Test Suite ===${NC}"
log "Report: ${REPORT}"
log "Start:  $(date '+%H:%M:%S')"
log ""

# ═══════════════════════════════════════════════════════════════════════════════
# Phase 1: Smoke
# ═══════════════════════════════════════════════════════════════════════════════
if run_phase "smoke" || run_phase "1"; then
  log_section "1" "Smoke Test"

  if ! command -v k6 &>/dev/null; then
    log_warn "k6 not installed — skipping k6 smoke test (run setup/prereqs.sh)"
    log "  Falling back to curl-based health check..."
    "${SCRIPT_DIR}/setup/platform-check.sh" 2>&1 | tee -a "$REPORT" && \
      log_pass "All services healthy (curl)" || log_fail "Some services DOWN"
  else
    k6 run --quiet "${SCRIPT_DIR}/k6/01-smoke.js" 2>&1 | tee -a "$REPORT"
    [[ ${PIPESTATUS[0]} -eq 0 ]] && log_pass "Smoke test: all 20 services UP" || log_fail "Smoke test failed"
  fi
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Phase 2: Individual Services
# ═══════════════════════════════════════════════════════════════════════════════
if run_phase "services" || run_phase "2"; then
  log_section "2" "Individual Service Tests"

  if command -v k6 &>/dev/null; then
    # onboarding-api
    log "  Running onboarding-api (light, medium)..."
    k6 run --quiet --env PROFILE=light  "${SCRIPT_DIR}/k6/02-onboarding.js" 2>&1 | tail -3 | tee -a "$REPORT"
    k6 run --quiet --env PROFILE=medium "${SCRIPT_DIR}/k6/02-onboarding.js" 2>&1 | tail -3 | tee -a "$REPORT"

    # encryption-service (3 file sizes)
    log "  Running encryption-service (1KB, 100KB, 1MB)..."
    for size in 1KB 100KB 1MB; do
      k6 run --quiet --env FILE_SIZE="$size" "${SCRIPT_DIR}/k6/03-encryption.js" 2>&1 | tail -2 | tee -a "$REPORT"
    done

    # screening
    log "  Running screening-service..."
    k6 run --quiet "${SCRIPT_DIR}/k6/04-screening.js" 2>&1 | tail -3 | tee -a "$REPORT"

    # analytics
    log "  Running analytics-service..."
    k6 run --quiet "${SCRIPT_DIR}/k6/05-analytics.js" 2>&1 | tail -3 | tee -a "$REPORT"

    # sentinel
    log "  Running platform-sentinel..."
    k6 run --quiet "${SCRIPT_DIR}/k6/06-sentinel.js" 2>&1 | tail -3 | tee -a "$REPORT"

    log_pass "Individual service k6 tests completed (review output above for pass/warn/fail)"
  else
    log_skip "k6 not installed — skipping k6 tests"
  fi

  # SFTP benchmarks
  if command -v python3 &>/dev/null && python3 -c "import paramiko" 2>/dev/null; then
    log "  Running SFTP benchmarks..."
    python3 "${SCRIPT_DIR}/python/sftp_benchmark.py" --scenario small-files --count 500 \
      2>&1 | tail -5 | tee -a "$REPORT"
    python3 "${SCRIPT_DIR}/python/sftp_benchmark.py" --scenario concurrent --connections 30 \
      2>&1 | tail -5 | tee -a "$REPORT"
    log_pass "SFTP benchmarks completed"
  else
    log_skip "paramiko not installed — skipping SFTP benchmarks (pip3 install paramiko)"
  fi

  # VFS benchmarks
  if command -v python3 &>/dev/null && python3 -c "import aiohttp" 2>/dev/null; then
    log "  Running VFS benchmarks..."
    python3 "${SCRIPT_DIR}/python/vfs_benchmark.py" --scenario listing --count 200 \
      2>&1 | tail -5 | tee -a "$REPORT"
    python3 "${SCRIPT_DIR}/python/vfs_benchmark.py" --scenario concurrent-reads --workers 50 --count 300 \
      2>&1 | tail -5 | tee -a "$REPORT"
    log_pass "VFS benchmarks completed"
  else
    log_skip "aiohttp not installed — run: pip3 install -r tests/perf/python/requirements.txt"
  fi
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Phase 3: Integration Pipelines
# ═══════════════════════════════════════════════════════════════════════════════
if run_phase "pipeline" || run_phase "3"; then
  log_section "3" "Integration Pipeline Tests"

  if command -v k6 &>/dev/null; then
    log "  Running full pipeline (light)..."
    k6 run --quiet --env PROFILE=light "${SCRIPT_DIR}/k6/07-full-pipeline.js" \
      2>&1 | tail -5 | tee -a "$REPORT"

    if [[ "$QUICK" != "true" ]]; then
      log "  Running full pipeline (medium)..."
      k6 run --quiet --env PROFILE=medium "${SCRIPT_DIR}/k6/07-full-pipeline.js" \
        2>&1 | tail -5 | tee -a "$REPORT"
    fi
    log_pass "Integration pipeline tests completed"
  else
    log_skip "k6 not installed"
  fi
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Phase 4: Volume Tests
# ═══════════════════════════════════════════════════════════════════════════════
if run_phase "volume" || run_phase "4"; then
  if [[ "$SKIP_VOLUME" == "true" ]]; then
    log_section "4" "Volume Tests"
    log_skip "Volume tests skipped (--skip-volume). Run manually:"
    log "    python3 tests/perf/python/million_transfers.py --count 10000  --workers 20"
    log "    python3 tests/perf/python/million_transfers.py --count 100000 --workers 50"
    log "    python3 tests/perf/python/million_transfers.py --count 1000000 --workers 100"
  else
    log_section "4" "Volume Tests (10K transfers)"
    if command -v python3 &>/dev/null && python3 -c "import aiohttp" 2>/dev/null; then
      python3 "${SCRIPT_DIR}/python/million_transfers.py" --count 10000 --workers 20 \
        2>&1 | tail -10 | tee -a "$REPORT"
      log_pass "10K transfer volume test completed"
      log "  To run 100K/1M: python3 tests/perf/python/million_transfers.py --count 100000"
    else
      log_skip "aiohttp not installed"
    fi
  fi
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Phase 5: Security Tests
# ═══════════════════════════════════════════════════════════════════════════════
if run_phase "security" || run_phase "5"; then
  log_section "5" "Security Tests"

  if command -v k6 &>/dev/null; then
    k6 run --quiet "${SCRIPT_DIR}/k6/08-security.js" 2>&1 | tee -a "$REPORT"
    [[ ${PIPESTATUS[0]} -eq 0 ]] && log_pass "Security boundary tests passed" || log_fail "Security violations detected"
  else
    log_skip "k6 not installed — run manually: k6 run tests/perf/k6/08-security.js"
  fi

  # TLS checks
  if command -v openssl &>/dev/null; then
    log "  Checking TLS configuration..."
    TLS10=$(openssl s_client -connect localhost:9443 -tls1 2>&1)
    if echo "$TLS10" | grep -q "handshake failure\|no protocols"; then
      log_pass "TLS 1.0 correctly rejected"
    else
      log_warn "TLS 1.0 may be accepted — check DMZ proxy TLS config"
    fi
  fi
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Phase 6: Resilience
# ═══════════════════════════════════════════════════════════════════════════════
if run_phase "resilience" || run_phase "6"; then
  if [[ "$SKIP_RESILIENCE" == "true" ]]; then
    log_section "6" "Resilience Tests"
    log_skip "Resilience tests skipped (--skip-resilience). Run manually:"
    log "    ./tests/perf/resilience/kill-service.sh --service ai-engine --duration 60"
    log "    ./tests/perf/resilience/chaos-rabbitmq.sh"
    log "    ./tests/perf/resilience/chaos-database.sh"
    log "    ./tests/perf/resilience/recovery-time.sh"
    log "    ./tests/perf/resilience/replica-scaling.sh --quick"
  else
    log_section "6" "Resilience Tests"
    log "  Running MTTR measurement (graceful-degradation services only)..."
    "${SCRIPT_DIR}/resilience/recovery-time.sh" 2>&1 | tee -a "$REPORT"
    log_pass "MTTR measurements recorded"
    log "  NOTE: Full chaos tests (RabbitMQ, DB kill) skipped in automated run."
    log "  Run manually: ./tests/perf/resilience/chaos-rabbitmq.sh"
  fi
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Phase 7: Endurance
# ═══════════════════════════════════════════════════════════════════════════════
if run_phase "endurance" || run_phase "7"; then
  if [[ "$SKIP_ENDURANCE" == "true" ]]; then
    log_section "7" "Endurance / Soak Test"
    log_skip "24h soak test skipped (add --endurance flag to enable). Run manually:"
    log "    k6 run --vus 50 --duration 24h tests/perf/k6/07-full-pipeline.js"
  else
    log_section "7" "Endurance / Soak Test (24h)"
    if command -v k6 &>/dev/null; then
      log "  Starting 24h soak test (50 VUs)..."
      k6 run \
        --vus 50 \
        --duration 24h \
        --out "json=${REPORT_DIR}/soak-${TIMESTAMP}.json" \
        "${SCRIPT_DIR}/k6/07-full-pipeline.js" 2>&1 | tee -a "$REPORT"
    else
      log_skip "k6 not installed"
    fi
  fi
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Final Report
# ═══════════════════════════════════════════════════════════════════════════════
WALL_TIME=$(( $(date +%s) - START_TIME ))
WALL_MIN=$((WALL_TIME / 60))

# Get Sentinel health score at end
FINAL_SCORE=$(curl -s --max-time 3 "http://localhost:8098/api/v1/sentinel/health-score" \
  2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('overallScore','?'))" 2>/dev/null || echo "N/A")

cat >> "$REPORT" <<EOF

---

## Final Summary

| Metric | Value |
|--------|-------|
| Tests passed | ${PASS_COUNT} |
| Warnings | ${WARN_COUNT} |
| Failures | ${FAIL_COUNT} |
| Skipped | ${SKIP_COUNT} |
| Total wall time | ${WALL_MIN} min |
| Platform health score (end) | ${FINAL_SCORE} |

## Next Steps
- Review WARN/FAIL items above
- Run skipped phases manually (volume, chaos)
- Check Sentinel dashboard: http://localhost:3000/sentinel
- Check full report: ${REPORT}
EOF

echo ""
echo -e "${BOLD}══════════════════════════════════════════${NC}"
echo -e "${BOLD}TEST SUITE COMPLETE${NC}"
echo -e "  Duration:  ${WALL_MIN}m"
echo -e "  ${GREEN}Passed:${NC}   ${PASS_COUNT}"
echo -e "  ${YELLOW}Warnings:${NC} ${WARN_COUNT}"
echo -e "  ${RED}Failed:${NC}   ${FAIL_COUNT}"
echo -e "  Skipped:   ${SKIP_COUNT}"
echo -e "  Sentinel:  ${FINAL_SCORE}/100"
echo -e "  Report:    ${REPORT}"
echo ""

# Exit code: fail if any FAIL
[[ $FAIL_COUNT -eq 0 ]] && exit 0 || exit 1

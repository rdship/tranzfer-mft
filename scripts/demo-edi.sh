#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — EDI converter validating test
# =============================================================================
# Exercises the live edi-converter service (port 8095) by running each sample
# in scripts/demo-edi-samples/ through /detect and /convert endpoints, then
# asserting that the output contains expected fields. PASS/FAIL per check,
# exit non-zero if ANY assertion fails. Pure REST, read-only.
#
# edi-converter is dropped in the tier-2 demo profile — this test only works
# against the full-stack boot (./scripts/demo-all.sh --full).
#
# Exit codes:
#   0  all assertions pass
#   1  one or more assertions failed
#   2  edi-converter unreachable (prerequisite failure)
#
# Usage:  ./scripts/demo-edi.sh
#         ./scripts/demo-edi.sh --quiet      (summary only, no per-check detail)
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

EDI_URL="${EDI_URL:-https://localhost:9095}"
SAMPLES_DIR="scripts/demo-edi-samples"
QUIET=false

while (( $# > 0 )); do
  case "$1" in
    --quiet|-q) QUIET=true; shift ;;
    *) shift ;;
  esac
done

RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; BLUE=$'\e[34m'; BOLD=$'\e[1m'; RST=$'\e[0m'

log()   { printf '\n%s[demo-edi]%s %s\n' "$BLUE" "$RST" "$*"; }
pass()  { printf '  %s✓%s %s\n' "$GREEN" "$RST" "$*"; PASSED=$((PASSED+1)); }
fail()  { printf '  %s✗%s %s\n' "$RED"   "$RST" "$*"; FAILED=$((FAILED+1)); FAILED_LABELS+=("$*"); }
skip()  { printf '  %s·%s %s\n' "$YELLOW" "$RST" "$*"; SKIPPED=$((SKIPPED+1)); }
die()   { printf '%s[demo-edi] %s%s\n' "$RED" "$*" "$RST" >&2; exit 2; }

PASSED=0
FAILED=0
SKIPPED=0
FAILED_LABELS=()

# --- Preflight ---------------------------------------------------------------
command -v curl >/dev/null || die "curl is required"
if ! command -v jq >/dev/null; then
  die "jq is required for response assertions. Install with: brew install jq"
fi

if ! curl -skf "${EDI_URL}/actuator/health/liveness" >/dev/null 2>&1 && \
   ! curl -skf "${EDI_URL}/actuator/health" >/dev/null 2>&1; then
  die "edi-converter not reachable at ${EDI_URL}. Boot the full stack first: ./scripts/demo-all.sh --full"
fi

log "edi-converter is up at ${EDI_URL}"

# --- Helper: call /detect, capture detected type ----------------------------
detect_format() {
  local content="$1"
  local payload
  payload=$(jq -n --arg c "$content" '{content:$c}')
  curl -skf -X POST "${EDI_URL}/api/v1/convert/detect" \
    -H 'Content-Type: application/json' \
    --data-raw "$payload" 2>/dev/null
}

# --- Helper: call /convert with a target format -----------------------------
convert_format() {
  local content="$1" target="$2"
  local payload
  payload=$(jq -n --arg c "$content" --arg t "$target" '{content:$c, target:$t}')
  curl -skf -X POST "${EDI_URL}/api/v1/convert/convert" \
    -H 'Content-Type: application/json' \
    --data-raw "$payload" 2>/dev/null
}

# --- Helper: assert JSON response contains a field ---------------------------
# Usage: assert_contains "label" "$json" ".some.path" "expected-substring"
assert_contains() {
  local label="$1" json="$2" path="$3" expect="$4"
  if [[ -z "$json" ]]; then
    fail "$label — empty response from edi-converter"
    return
  fi
  local val
  val=$(echo "$json" | jq -r "$path // empty" 2>/dev/null)
  if [[ -z "$val" ]]; then
    fail "$label — path $path not found in response"
    return
  fi
  if [[ "$val" == *"$expect"* ]]; then
    pass "$label — $path contains '$expect'"
  else
    fail "$label — $path='$val' does not contain '$expect'"
  fi
}

# --- Helper: assert response is a non-empty JSON object / array --------------
assert_nonempty_json() {
  local label="$1" json="$2"
  if [[ -z "$json" ]]; then
    fail "$label — empty response"
    return
  fi
  local len
  len=$(echo "$json" | jq -r 'if type == "object" then (keys | length) elif type == "array" then length else 0 end' 2>/dev/null || echo 0)
  if [[ "$len" -gt 0 ]]; then
    pass "$label — response has $len top-level keys/items"
  else
    fail "$label — response is empty or not valid JSON"
  fi
}

# =============================================================================
# TEST 1: X12 850 Purchase Order → auto-detect + convert to JSON
# =============================================================================
log "Test 1: X12 850 Purchase Order"
PATH1="${SAMPLES_DIR}/x12-850-purchase-order.edi"
if [[ ! -f "$PATH1" ]]; then
  skip "Missing sample file: $PATH1"
else
  CONTENT=$(cat "$PATH1")

  DETECT=$(detect_format "$CONTENT" || true)
  assert_contains "X12 850 detect" "$DETECT" '.documentType // .type // .format' 'X12'

  JSON=$(convert_format "$CONTENT" 'JSON' || true)
  assert_nonempty_json "X12 850 → JSON conversion" "$JSON"
  # X12 850 must contain BEG (Beginning Segment for Purchase Order) somewhere
  if [[ -n "$JSON" ]] && echo "$JSON" | jq -e '.. | strings? | select(. == "BEG" or contains("BEG"))' >/dev/null 2>&1; then
    pass "X12 850 → JSON contains BEG segment reference"
  else
    fail "X12 850 → JSON missing BEG segment marker"
  fi
fi

# =============================================================================
# TEST 2: X12 810 Invoice → auto-detect + convert to JSON
# =============================================================================
log "Test 2: X12 810 Invoice"
PATH2="${SAMPLES_DIR}/x12-810-invoice.edi"
if [[ ! -f "$PATH2" ]]; then
  skip "Missing sample file: $PATH2"
else
  CONTENT=$(cat "$PATH2")

  DETECT=$(detect_format "$CONTENT" || true)
  assert_contains "X12 810 detect" "$DETECT" '.documentType // .type // .format' 'X12'

  JSON=$(convert_format "$CONTENT" 'JSON' || true)
  assert_nonempty_json "X12 810 → JSON conversion" "$JSON"
  if [[ -n "$JSON" ]] && echo "$JSON" | jq -e '.. | strings? | select(. == "BIG" or contains("BIG"))' >/dev/null 2>&1; then
    pass "X12 810 → JSON contains BIG segment reference"
  else
    fail "X12 810 → JSON missing BIG segment marker"
  fi
fi

# =============================================================================
# TEST 3: EDIFACT ORDERS → auto-detect + convert to JSON
# =============================================================================
log "Test 3: EDIFACT ORDERS"
PATH3="${SAMPLES_DIR}/edifact-orders.edi"
if [[ ! -f "$PATH3" ]]; then
  skip "Missing sample file: $PATH3"
else
  CONTENT=$(cat "$PATH3")

  DETECT=$(detect_format "$CONTENT" || true)
  assert_contains "EDIFACT detect" "$DETECT" '.documentType // .type // .format' 'EDIFACT'

  JSON=$(convert_format "$CONTENT" 'JSON' || true)
  assert_nonempty_json "EDIFACT ORDERS → JSON conversion" "$JSON"
  if [[ -n "$JSON" ]] && echo "$JSON" | jq -e '.. | strings? | select(. == "BGM" or contains("BGM"))' >/dev/null 2>&1; then
    pass "EDIFACT ORDERS → JSON contains BGM segment reference"
  else
    fail "EDIFACT ORDERS → JSON missing BGM segment marker"
  fi
fi

# =============================================================================
# TEST 4: HL7 ADT^A01 → auto-detect + convert to JSON
# =============================================================================
log "Test 4: HL7 ADT^A01"
PATH4="${SAMPLES_DIR}/hl7-adt-admission.hl7"
if [[ ! -f "$PATH4" ]]; then
  skip "Missing sample file: $PATH4"
else
  CONTENT=$(cat "$PATH4")

  DETECT=$(detect_format "$CONTENT" || true)
  assert_contains "HL7 detect" "$DETECT" '.documentType // .type // .format' 'HL7'

  JSON=$(convert_format "$CONTENT" 'JSON' || true)
  assert_nonempty_json "HL7 ADT → JSON conversion" "$JSON"
  # HL7 ADT^A01 admissions always have a PID segment with patient info
  if [[ -n "$JSON" ]] && echo "$JSON" | jq -e '.. | strings? | select(. == "PID" or contains("PID"))' >/dev/null 2>&1; then
    pass "HL7 ADT → JSON contains PID segment reference"
  else
    fail "HL7 ADT → JSON missing PID segment marker"
  fi
  # Patient surname DOE should survive parsing
  if [[ -n "$JSON" ]] && echo "$JSON" | grep -qi 'DOE'; then
    pass "HL7 ADT → JSON preserves patient surname DOE"
  else
    fail "HL7 ADT → JSON lost patient surname DOE"
  fi
fi

# =============================================================================
# Summary
# =============================================================================
TOTAL=$((PASSED + FAILED + SKIPPED))
printf '\n%s═══════════════════════════════════════════════════════════%s\n' "$BOLD" "$RST"
printf ' Results:  %s%d passed%s   %s%d failed%s   %s%d skipped%s   (%d total)\n' \
  "$GREEN" "$PASSED" "$RST" \
  "$RED"   "$FAILED" "$RST" \
  "$YELLOW" "$SKIPPED" "$RST" \
  "$TOTAL"
printf '%s═══════════════════════════════════════════════════════════%s\n' "$BOLD" "$RST"

if (( FAILED > 0 )); then
  printf '\n%sFailures:%s\n' "$RED" "$RST"
  for label in "${FAILED_LABELS[@]}"; do
    printf '  %s✗%s %s\n' "$RED" "$RST" "$label"
  done
  printf '\n'
  exit 1
fi

printf '\n%sAll EDI conversion tests passed.%s\n\n' "$GREEN" "$RST"
exit 0

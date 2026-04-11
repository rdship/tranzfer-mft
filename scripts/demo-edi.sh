#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — EDI converter demo runner
# =============================================================================
# Runs each sample in scripts/demo-edi-samples/ through the live edi-converter
# service (port 8095) and prints a formatted before/after. Purely read-only —
# no DB writes, no state changes.
#
# edi-converter is dropped in the tier-2 demo profile, so this script only
# works against the full-stack boot (./scripts/demo-all.sh --full).
#
# Usage:  ./scripts/demo-edi.sh
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

EDI_URL="${EDI_URL:-http://localhost:8095}"
SAMPLES_DIR="scripts/demo-edi-samples"

log()  { printf '\n\033[1;34m[demo-edi]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[demo-edi] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[demo-edi] %s\033[0m\n' "$*" >&2; exit 1; }

# --- Preflight ---------------------------------------------------------------
command -v curl >/dev/null || die "curl is required"
command -v jq >/dev/null || warn "jq not found — JSON output will be raw (brew install jq for pretty printing)"

if ! curl -sf "${EDI_URL}/actuator/health/liveness" >/dev/null 2>&1; then
  die "edi-converter not reachable at ${EDI_URL}. Is the full-stack demo up? (./scripts/demo-all.sh --full)"
fi

log "edi-converter is up at ${EDI_URL}"

# Pretty-print helper: use jq if available, else cat
pretty() {
  if command -v jq >/dev/null 2>&1; then
    jq -r '.' 2>/dev/null || cat
  else
    cat
  fi
}

# --- Sample → conversion pairs -----------------------------------------------
# Each entry is:  sample-file | target-format | description
SAMPLES=(
  "x12-850-purchase-order.edi|JSON|X12 850 Purchase Order → JSON"
  "x12-850-purchase-order.edi|XML|X12 850 Purchase Order → XML"
  "x12-810-invoice.edi|JSON|X12 810 Invoice → JSON"
  "edifact-orders.edi|JSON|EDIFACT ORDERS → JSON"
  "hl7-adt-admission.hl7|JSON|HL7 ADT^A01 Admission → JSON"
)

BAR='======================================================================'

for entry in "${SAMPLES[@]}"; do
  IFS='|' read -r file target label <<< "$entry"
  path="${SAMPLES_DIR}/${file}"

  [[ -f "$path" ]] || { warn "Missing sample: $path"; continue; }

  printf '\n%s\n%s\n%s\n' "$BAR" " $label" "$BAR"

  CONTENT=$(cat "$path")

  # 1. Detect format
  log "POST /api/v1/convert/detect"
  DETECT=$(curl -sf -X POST "${EDI_URL}/api/v1/convert/detect" \
    -H 'Content-Type: application/json' \
    --data-raw "$(jq -n --arg c "$CONTENT" '{content:$c}' 2>/dev/null || printf '{"content": %s}' "$(printf '%s' "$CONTENT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""')")" \
    2>/dev/null || echo '{"error":"detect failed"}')
  printf '  Detected: '
  echo "$DETECT" | pretty

  # 2. Convert
  log "POST /api/v1/convert/convert  (target=${target})"
  PAYLOAD=$(jq -n --arg c "$CONTENT" --arg t "$target" '{content:$c, target:$t}' 2>/dev/null) || \
    PAYLOAD="{\"content\":$(printf '%s' "$CONTENT" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo '""'),\"target\":\"${target}\"}"
  RESULT=$(curl -sf -X POST "${EDI_URL}/api/v1/convert/convert" \
    -H 'Content-Type: application/json' \
    --data-raw "$PAYLOAD" \
    2>/dev/null || echo '{"error":"convert failed"}')
  echo "$RESULT" | pretty | head -40
  echo "  (...output truncated at 40 lines)"
done

printf '\n%s\n' "$BAR"
cat <<'EOF'

Try the same conversions in the UI:
  1. Open http://localhost:3000 → EDI Translation → Convert tab
  2. Copy the content of any file in scripts/demo-edi-samples/
  3. Paste into the EDI Content textarea
  4. Click Detect → pick a target → Convert

See scripts/demo-edi-samples/README.md for the full walkthrough including
Explain, Validate, Heal, Diff, and Compliance tabs.

EOF

#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — resource snapshot helper
# =============================================================================
# Captures a point-in-time view of CPU / memory / network per container and
# appends it to DEMO-RESULTS.md so the tester can record what the platform
# was actually doing at a given moment.
#
# Usage:
#   ./scripts/demo-stats.sh                  # append a labeled snapshot with timestamp
#   ./scripts/demo-stats.sh --snapshot idle  # append with an explicit label
#   ./scripts/demo-stats.sh --print          # just print, don't append
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

LABEL="$(date '+%Y-%m-%d %H:%M:%S')"
APPEND=true

while (( $# > 0 )); do
  case "$1" in
    --snapshot) shift; LABEL="$1 — $(date '+%H:%M:%S')"; shift ;;
    --print)    APPEND=false; shift ;;
    *)          shift ;;
  esac
done

# Collect stats in non-streaming mode (single pass, exits immediately)
STATS=$(docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}' 2>/dev/null || echo "docker stats unavailable")

# Container count + aggregate memory (parse docker stats RAW output)
RAW=$(docker stats --no-stream --format '{{.MemUsage}}' 2>/dev/null || echo "")
TOTAL_MB=0
COUNT=0
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  USED=$(echo "$line" | awk '{print $1}')
  NUM=$(echo "$USED" | sed 's/[A-Za-z]*$//')
  UNIT=$(echo "$USED" | sed 's/[0-9.]*//g')
  case "$UNIT" in
    GiB|GB) MB=$(awk -v n="$NUM" 'BEGIN{printf "%.0f", n*1024}') ;;
    MiB|MB) MB=$(awk -v n="$NUM" 'BEGIN{printf "%.0f", n}') ;;
    KiB|KB) MB=$(awk -v n="$NUM" 'BEGIN{printf "%.0f", n/1024}') ;;
    *)      MB=0 ;;
  esac
  TOTAL_MB=$((TOTAL_MB + MB))
  COUNT=$((COUNT + 1))
done <<< "$RAW"

SUMMARY="Containers: ${COUNT} · Total memory used: ${TOTAL_MB} MB (~$((TOTAL_MB/1024)).$((TOTAL_MB%1024*10/1024)) GB)"

OUT=$(cat <<EOF

### Snapshot — ${LABEL}

\`\`\`
${SUMMARY}
\`\`\`

\`\`\`
${STATS}
\`\`\`

EOF
)

if $APPEND && [[ -f DEMO-RESULTS.md ]]; then
  # Append under the "Resource snapshots" heading, or at the end if missing
  if grep -q '^## Resource snapshots' DEMO-RESULTS.md; then
    printf '%s\n' "$OUT" >> DEMO-RESULTS.md
  else
    printf '\n## Resource snapshots\n%s\n' "$OUT" >> DEMO-RESULTS.md
  fi
  echo "Snapshot appended to DEMO-RESULTS.md (${SUMMARY})"
else
  printf '%s\n' "$OUT"
fi

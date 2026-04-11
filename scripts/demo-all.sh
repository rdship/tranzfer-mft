#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — one-button demo setup
# =============================================================================
# Two modes:
#
#   Tier-2 (default)    — curated 14-service subset, fits ~10 GB machines
#                         ./scripts/demo-all.sh
#
#   Full stack          — all ~40 containers, requires ~25 GB RAM and ~20 GB
#                         allocated to Docker Desktop
#                         ./scripts/demo-all.sh --full
#
# Both modes run the same seeding pipeline after boot:
#   1. Boot selected stack
#   2. Seed 1000+ config entities (demo-onboard.sh)
#   3. Seed historical Fabric + Sentinel + Analytics data (demo-traffic.sh)
#   4. Capture a baseline resource snapshot (demo-stats.sh)
#
# Wall time on first run: ~15 min tier-2, ~25-30 min full stack.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

MODE=tier2
while (( $# > 0 )); do
  case "$1" in
    --full) MODE=full; shift ;;
    --tier2|--tier-2) MODE=tier2; shift ;;
    -h|--help)
      sed -n '1,22p' "$0"
      exit 0 ;;
    *) echo "Unknown flag: $1" >&2; exit 2 ;;
  esac
done

STEP=0
step() { STEP=$((STEP+1)); printf '\n\033[1;35m[demo-all %d/4]\033[0m %s\n' "$STEP" "$*"; }

START=$(date +%s)

if [[ "$MODE" == full ]]; then
  step "Booting FULL STACK (all services, replicas, observability, all UIs)"
  ./scripts/demo-start-full.sh
else
  step "Booting tier-2 service stack (14 core services + infra + 2 UIs)"
  ./scripts/demo-start.sh
fi

step "Seeding 1000+ config entities (this is the long step — grab a coffee)"
./scripts/demo-onboard.sh --skip-docker

step "Seeding historical Fabric + Sentinel + Analytics data"
./scripts/demo-traffic.sh

step "Capturing baseline resource snapshot"
./scripts/demo-stats.sh --snapshot "baseline (${MODE})" || true

ELAPSED=$(( $(date +%s) - START ))
MIN=$((ELAPSED/60)); SEC=$((ELAPSED%60))

G=$'\e[1;32m'; B=$'\e[1m'; R=$'\e[0m'
cat <<EOF

${G}================================================================${R}
  ${B}${MODE^^} mode ready${R} (took ${MIN}m ${SEC}s)

  ${B}Admin UI${R}        http://localhost:3000
  ${B}Partner Portal${R}  http://localhost:3002
EOF

if [[ "$MODE" == full ]]; then
  cat <<EOF
  ${B}FTP Web UI${R}      http://localhost:3001
  ${B}Grafana${R}         http://localhost:3030  (admin / admin)
  ${B}Prometheus${R}      http://localhost:9090
  ${B}MinIO Console${R}   http://localhost:9001  (minioadmin / minioadmin)
EOF
fi

cat <<EOF

  ${B}Login${R}           admin@filetransfer.local
  ${B}Password${R}        Tr@nzFer2026!

  ${B}What to do next${R}
  1. Open DEMO.md for a walkthrough of the key pages to test
  2. As you go, record observations in DEMO-RESULTS.md
  3. To grab a live resource snapshot while testing:
        ./scripts/demo-stats.sh --snapshot "what you were doing"
  4. When done, commit DEMO-RESULTS.md and push so Roshan can review:
        git add DEMO-RESULTS.md && git commit -m 'demo results' && git push

  ${B}To tear down${R}
        docker compose down

${G}================================================================${R}
EOF

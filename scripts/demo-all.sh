#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — one-button demo setup
# =============================================================================
# Runs the full tester-ready setup:
#   1. Boot a curated tier-2 stack that fits ~10 GB RAM
#   2. Seed 1000+ config entities (partners, flows, accounts, keys, ...)
#   3. Seed historical Fabric + Sentinel + Analytics data
#   4. Print login URL + credentials + "next steps" pointing at DEMO.md
#
# Expected wall time on a quiet 10 GB / 9-CPU Mac: ~10-15 minutes first run,
# ~3-5 minutes on subsequent runs (images cached).
#
# Usage:  ./scripts/demo-all.sh
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

STEP=0
step() { STEP=$((STEP+1)); printf '\n\033[1;35m[demo-all %d/4]\033[0m %s\n' "$STEP" "$*"; }

START=$(date +%s)

step "Booting tier-2 service stack"
./scripts/demo-start.sh

step "Seeding 1000+ config entities (this is the long step — grab a coffee)"
./scripts/demo-onboard.sh --skip-docker

step "Seeding historical Fabric + Sentinel + Analytics data"
./scripts/demo-traffic.sh

step "Capturing baseline resource snapshot"
./scripts/demo-stats.sh --snapshot baseline || true

ELAPSED=$(( $(date +%s) - START ))
MIN=$((ELAPSED/60)); SEC=$((ELAPSED%60))

G=$'\e[1;32m'; B=$'\e[1m'; R=$'\e[0m'
cat <<EOF

${G}================================================================${R}
  Demo environment ready (took ${MIN}m ${SEC}s)

  ${B}Admin UI${R}        http://localhost:3000
  ${B}Partner Portal${R}  http://localhost:3002

  ${B}Login${R}           admin@filetransfer.local
  ${B}Password${R}        Tr@nzFer2026!

  ${B}What to do next${R}
  1. Open DEMO.md for a walkthrough of the key pages to test
  2. As you go, record observations in DEMO-RESULTS.md
  3. To grab a live resource snapshot while testing:
        ./scripts/demo-stats.sh
  4. When done, commit DEMO-RESULTS.md and push so Roshan can review:
        git add DEMO-RESULTS.md && git commit -m 'demo results' && git push

  ${B}To tear down${R}
        docker compose down

${G}================================================================${R}
EOF

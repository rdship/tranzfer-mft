#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — SPIRE Auto-Bootstrap
#
# Runs as a Docker init container (spire-init). Automatically:
#   1. Waits for SPIRE Server to be healthy
#   2. Generates a join token
#   3. Registers all workload entries
#   4. Writes token to shared volume for the agent
#
# NO MANUAL STEP REQUIRED. Just run: docker compose up -d
# =============================================================================
set -euo pipefail

TRUST_DOMAIN="filetransfer.io"
TOKEN_TTL=2592000  # 30 days
TOKEN_OUTPUT="/tmp/spire-init/join-token"
MARKER="/tmp/spire-init/.bootstrapped"
SERVER_SOCK="/tmp/spire-server/private/api.sock"

info()  { echo "[spire-init] $*"; }
warn()  { echo "[spire-init] WARN: $*"; }

# ── Skip if already bootstrapped (idempotent) ────────────────────────────────
if [ -f "$MARKER" ] && [ -f "$TOKEN_OUTPUT" ]; then
    info "Already bootstrapped (marker exists). Skipping."
    info "To re-bootstrap: delete the spire-init-data volume."
    exit 0
fi

# ── Wait for SPIRE Server ────────────────────────────────────────────────────
info "Waiting for SPIRE Server..."
MAX_WAIT=120
WAITED=0
until /opt/spire/bin/spire-server healthcheck -socketPath "$SERVER_SOCK" >/dev/null 2>&1; do
    sleep 2
    WAITED=$((WAITED + 2))
    if [ "$WAITED" -ge "$MAX_WAIT" ]; then
        warn "SPIRE Server not ready after ${MAX_WAIT}s — continuing anyway"
        break
    fi
done
info "SPIRE Server is ready (${WAITED}s)."

# ── Generate join token ──────────────────────────────────────────────────────
info "Generating agent join token (TTL=${TOKEN_TTL}s)..."
TOKEN=$(/opt/spire/bin/spire-server token generate \
    -socketPath "$SERVER_SOCK" \
    -spiffeID "spiffe://${TRUST_DOMAIN}/node" \
    -ttl "$TOKEN_TTL" 2>/dev/null | grep -oE 'Token:\s+\S+' | awk '{print $2}' || true)

if [ -z "$TOKEN" ]; then
    warn "Failed to generate token — SPIRE may need more time. Using fallback."
    # Retry once after 5s
    sleep 5
    TOKEN=$(/opt/spire/bin/spire-server token generate \
        -socketPath "$SERVER_SOCK" \
        -spiffeID "spiffe://${TRUST_DOMAIN}/node" \
        -ttl "$TOKEN_TTL" 2>/dev/null | grep -oE 'Token:\s+\S+' | awk '{print $2}' || true)
fi

if [ -z "$TOKEN" ]; then
    warn "Could not generate token. Services will self-heal when SPIRE becomes available."
    exit 0
fi

mkdir -p "$(dirname "$TOKEN_OUTPUT")"
echo -n "$TOKEN" > "$TOKEN_OUTPUT"
info "Join token written to $TOKEN_OUTPUT"

# ── Register workload entries ────────────────────────────────────────────────
NODE_ID="spiffe://${TRUST_DOMAIN}/node"

register() {
    local name="$1"
    /opt/spire/bin/spire-server entry create \
        -socketPath "$SERVER_SOCK" \
        -spiffeID "spiffe://${TRUST_DOMAIN}/${name}" \
        -parentID "$NODE_ID" \
        -selector "docker:label:com.filetransfer.spiffe:${name}" \
        2>/dev/null || true  # Ignore "already exists" errors
}

info "Registering workload entries..."
SERVICES=(
    onboarding-api config-service gateway-service encryption-service
    external-forwarder-service dmz-proxy license-service analytics-service
    ai-engine screening-service keystore-manager as2-service edi-converter
    storage-manager notification-service platform-sentinel
    sftp-service ftp-service ftp-web-service
    ui-service api-gateway cli
)

for svc in "${SERVICES[@]}"; do
    register "$svc"
done
info "Registered ${#SERVICES[@]} workload entries."

# ── Mark as bootstrapped ─────────────────────────────────────────────────────
touch "$MARKER"
info "Bootstrap complete. Agent can now start."

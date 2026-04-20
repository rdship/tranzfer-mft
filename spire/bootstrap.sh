#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — SPIRE Bootstrap Script
#
# Run ONCE after `docker compose up spire-server` to:
#   1. Generate a join token for the SPIRE agent
#   2. Register workload entries for all 22 platform services
#   3. Start the SPIRE agent (which is waiting for the token file)
#
# Usage:
#   docker compose up -d spire-server
#   bash spire/bootstrap.sh
#   docker compose up -d spire-agent          # agent reads token, connects
#   docker compose up -d                      # bring up the platform
#
# Re-run after: server data volume wipe, new services added, token expiry.
# =============================================================================
set -euo pipefail

TRUST_DOMAIN="filetransfer.io"
SERVER_CONTAINER="mft-spire-server"
TOKEN_TTL=2592000  # 30 days (dev); use shorter in prod with automated rotation
TOKEN_FILE="$(dirname "$0")/join-token.txt"

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[SPIRE]${NC} $*"; }
warn()  { echo -e "${YELLOW}[SPIRE]${NC} $*"; }
error() { echo -e "${RED}[SPIRE]${NC} $*" >&2; exit 1; }

spire_server() {
  docker exec "$SERVER_CONTAINER" /opt/spire/bin/spire-server "$@"
}

# ── Wait for server ───────────────────────────────────────────────────────────
info "Waiting for SPIRE Server to be healthy..."
until docker exec "$SERVER_CONTAINER" \
    wget -qO- http://localhost:8080/ready >/dev/null 2>&1; do
  sleep 2
done
info "SPIRE Server is ready."

# ── Generate join token ───────────────────────────────────────────────────────
info "Generating agent join token (TTL=${TOKEN_TTL}s)..."
TOKEN=$(spire_server token generate \
  -socketPath /tmp/spire-server/private/api.sock \
  -spiffeID "spiffe://${TRUST_DOMAIN}/node" \
  -ttl "$TOKEN_TTL" | grep -oE 'Token:\s+\S+' | awk '{print $2}')

[[ -z "$TOKEN" ]] && error "Failed to generate join token"
echo -n "$TOKEN" > "$TOKEN_FILE"
info "Join token saved to spire/join-token.txt"

# ── Register workload entries ─────────────────────────────────────────────────
# Format: register_entry <service-name> [<parent-spiffe-id>]
# The docker WorkloadAttestor matches containers by:
#   label: com.filetransfer.spiffe=<service-name>

NODE_ID="spiffe://${TRUST_DOMAIN}/node"

register_entry() {
  local name="$1"
  local spiffe_id="spiffe://${TRUST_DOMAIN}/${name}"
  info "  Registering: ${spiffe_id}"
  spire_server entry create \
    -socketPath /tmp/spire-server/private/api.sock \
    -spiffeID  "$spiffe_id" \
    -parentID  "$NODE_ID" \
    -selector  "docker:label:com.filetransfer.spiffe:${name}" \
    2>/dev/null || warn "    Entry may already exist (skipping)"
}

info "Registering workload entries for all platform services..."
# Core services
register_entry "onboarding-api"
register_entry "config-service"
register_entry "gateway-service"
register_entry "encryption-service"
register_entry "external-forwarder-service"
register_entry "dmz-proxy"
register_entry "license-service"
register_entry "analytics-service"
register_entry "ai-engine"
register_entry "screening-service"
register_entry "keystore-manager"
register_entry "as2-service"
register_entry "edi-converter"
register_entry "storage-manager"
register_entry "notification-service"
register_entry "platform-sentinel"
register_entry "https-service"

# Protocol services (multiple replicas share the same SPIFFE ID)
register_entry "sftp-service"
register_entry "ftp-service"
register_entry "ftp-web-service"

# CLI tool (runs as a short-lived container)
register_entry "cli"

info ""
info "Bootstrap complete. Next steps:"
info "  1. docker compose up -d spire-agent  (reads spire/join-token.txt via volume)"
info "  2. docker compose up -d              (start the rest of the platform)"
info ""
warn "Note: join-token.txt is a secret. Add it to .gitignore."
warn "      In production, use automated token rotation or Kubernetes attestation."

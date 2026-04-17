#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Regression test fixture builder
# =============================================================================
#
# Creates a deterministic set of VIRTUAL listeners, accounts, and file flows
# via the onboarding-api + config-service REST APIs. Designed to give the
# regression test suite a non-seed, runtime-created fixture that exercises
# the dynamic-listener path (R65-R73).
#
# All listeners use defaultStorageMode=VIRTUAL (per standing rule).
# Idempotent-ish: fails cleanly on duplicates (409/400); re-run after a
# `docker compose down -v` for a clean slate.
#
# Requires: curl, jq, and a running stack with the seeded superadmin user.
# Usage:    ./scripts/build-regression-fixture.sh
# =============================================================================
set -uo pipefail

API_ONBOARDING="${API_ONBOARDING:-http://localhost:8080}"
API_CONFIG="${API_CONFIG:-http://localhost:8084}"
API_KEYSTORE="${API_KEYSTORE:-http://localhost:8093}"
ADMIN_EMAIL="${ADMIN_EMAIL:-superadmin@tranzfer.io}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-superadmin}"

GREEN=$'\e[32m'; RED=$'\e[31m'; YELLOW=$'\e[33m'; BOLD=$'\e[1m'; RST=$'\e[0m'
pass() { printf '  %s✓%s %s\n' "$GREEN" "$RST" "$*"; }
fail() { printf '  %s✗%s %s\n' "$RED"   "$RST" "$*"; }
skip() { printf '  %s·%s %s\n' "$YELLOW" "$RST" "$*"; }
section() { printf '\n%s── %s ──%s\n' "$BOLD" "$*" "$RST"; }

section "Authenticate"
TOKEN=$(curl -s -X POST "$API_ONBOARDING/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" \
  | jq -r '.accessToken // .token // empty')
if [ -z "$TOKEN" ]; then fail "login failed"; exit 1; fi
pass "logged in as $ADMIN_EMAIL (token ${#TOKEN} chars)"
HDR_AUTH="Authorization: Bearer $TOKEN"
HDR_JSON="Content-Type: application/json"

# ─────────────────────────────────────────────────────────────────────────────
# 1. VIRTUAL SFTP listeners (2)
# ─────────────────────────────────────────────────────────────────────────────
section "SFTP listeners (VIRTUAL)"
create_listener() {
  local instance_id="$1" port="$2" proto="$3" name="$4"
  local proto_lc; proto_lc=$(printf '%s' "$proto" | tr '[:upper:]' '[:lower:]')
  local host="${proto_lc}-service"
  local body; body=$(jq -n \
    --arg iid "$instance_id" --arg name "$name" --arg proto "$proto" \
    --arg ihost "$host" --argjson iport "$port" \
    --arg ehost "localhost" --argjson eport "$port" \
    '{instanceId:$iid, name:$name, protocol:$proto, internalHost:$ihost,
      internalPort:$iport, externalHost:$ehost, externalPort:$eport,
      maxConnections:50}')
  local resp; resp=$(curl -s -w "\n%{http_code}" -X POST "$API_ONBOARDING/api/servers" \
    -H "$HDR_AUTH" -H "$HDR_JSON" -d "$body")
  local status="${resp##*$'\n'}"; local body_out="${resp%$'\n'*}"
  if [ "$status" = "201" ]; then
    local id; id=$(echo "$body_out" | jq -r '.id'); local sm; sm=$(echo "$body_out" | jq -r '.defaultStorageMode')
    pass "$instance_id on $proto:$port — id=${id:0:8} defaultStorageMode=$sm"
    echo "$id" > "/tmp/fixture_${instance_id}_id"
  elif [ "$status" = "409" ] || echo "$body_out" | grep -qi 'already'; then
    skip "$instance_id: already exists"
  else
    fail "$instance_id: HTTP $status — $body_out"
  fi
}
create_listener "sftp-reg-1" 2231 SFTP  "Regression SFTP listener 1"
create_listener "sftp-reg-2" 2232 SFTP  "Regression SFTP listener 2"

section "FTP listeners (VIRTUAL)"
create_listener "ftp-reg-1"  2100 FTP   "Regression FTP listener 1"
create_listener "ftp-reg-2"  2101 FTP   "Regression FTP listener 2"

# ─────────────────────────────────────────────────────────────────────────────
# 2. Transfer accounts — bound to specific listener by instanceId
# ─────────────────────────────────────────────────────────────────────────────
section "Transfer accounts"
create_account() {
  local username="$1" protocol="$2" password="$3" server_instance="$4" pubkey="${5:-}"
  local body; body=$(jq -n \
    --arg u "$username" --arg pr "$protocol" --arg pw "$password" \
    --arg si "$server_instance" --arg pk "$pubkey" \
    '{username:$u, protocol:$pr, password:$pw, serverInstance:$si,
      permissions:{read:true, write:true, delete:false}}
     + (if $pk != "" then {publicKey:$pk} else {} end)')
  local resp; resp=$(curl -s -w "\n%{http_code}" -X POST "$API_ONBOARDING/api/accounts" \
    -H "$HDR_AUTH" -H "$HDR_JSON" -d "$body")
  local status="${resp##*$'\n'}"; local body_out="${resp%$'\n'*}"
  if [ "$status" = "201" ] || [ "$status" = "200" ]; then
    pass "$username ($protocol) → serverInstance=$server_instance"
  elif [ "$status" = "409" ] || echo "$body_out" | grep -qi 'already'; then
    skip "$username: already exists"
  else
    fail "$username: HTTP $status — $(echo "$body_out" | head -c 200)"
  fi
}
create_account "regtest-sftp-1"   SFTP  "RegTest@2026!"  "sftp-reg-1"
create_account "regtest-sftp-2"   SFTP  "RegTest@2026!"  "sftp-reg-2"
create_account "regtest-ftp-1"    FTP   "RegTest@2026!"  "ftp-reg-1"
create_account "regtest-ftp-2"    FTP   "RegTest@2026!"  "ftp-reg-2"
# SSH key variant — key is illustrative (will not verify; key auth is off by default)
create_account "regtest-sftp-key" SFTP  "RegTest@2026!"  "sftp-reg-1" \
  "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMRegressionTestPublicKeyPlaceholder regtest"

# ─────────────────────────────────────────────────────────────────────────────
# 3. Encryption keys — required by ENCRYPT_AES / DECRYPT_PGP flow steps
# ─────────────────────────────────────────────────────────────────────────────
section "Encryption keys (centralized keystore)"
generate_aes() {
  local alias="$1" owner="$2"
  local body; body=$(jq -n --arg a "$alias" --arg o "$owner" '{alias:$a, ownerService:$o}')
  local resp; resp=$(curl -s -w "\n%{http_code}" -X POST "$API_KEYSTORE/api/v1/keys/generate/aes" \
    -H "$HDR_AUTH" -H "$HDR_JSON" -d "$body")
  local status="${resp##*$'\n'}"; local body_out="${resp%$'\n'*}"
  if [ "$status" = "201" ]; then
    local fp; fp=$(echo "$body_out" | jq -r '.fingerprint[:16]')
    pass "AES key '$alias' — owner=$owner fp=$fp"
  elif [ "$status" = "409" ] || echo "$body_out" | grep -qi 'already'; then
    skip "AES key '$alias': already exists"
  else
    fail "AES key '$alias': HTTP $status — $(echo "$body_out" | head -c 200)"
  fi
}
generate_pgp() {
  local alias="$1" identity="$2"
  local body; body=$(jq -n --arg a "$alias" --arg i "$identity" \
    '{alias:$a, identity:$i, passphrase:"RegTest@2026!"}')
  local resp; resp=$(curl -s -w "\n%{http_code}" -X POST "$API_KEYSTORE/api/v1/keys/generate/pgp" \
    -H "$HDR_AUTH" -H "$HDR_JSON" -d "$body")
  local status="${resp##*$'\n'}"; local body_out="${resp%$'\n'*}"
  if [ "$status" = "201" ]; then
    local fp; fp=$(echo "$body_out" | jq -r '.fingerprint[:16]')
    pass "PGP key '$alias' — identity=$identity fp=$fp"
  elif [ "$status" = "500" ]; then
    skip "PGP key '$alias': BLOCKED — known BouncyCastle bug 'only SHA1 supported' in KeyManagementService.java:174 (see server log; fix: use newer BC PGPSecretKey API)"
  elif [ "$status" = "409" ] || echo "$body_out" | grep -qi 'already'; then
    skip "PGP key '$alias': already exists"
  else
    fail "PGP key '$alias': HTTP $status — $(echo "$body_out" | head -c 200)"
  fi
}
generate_aes "aes-default"  "encryption-service"   # used by flow f3 ENCRYPT_AES
generate_aes "aes-outbound" "encryption-service"   # spare for additional test flows
generate_pgp "pgp-inbound"  "regtest-inbound@tranzfer.io"   # used by flow f4 DECRYPT_PGP
generate_pgp "pgp-outbound" "regtest-outbound@tranzfer.io"  # spare

# ─────────────────────────────────────────────────────────────────────────────
# 4. File flows — varied step pipelines for regression coverage
# ─────────────────────────────────────────────────────────────────────────────
section "File flows"
create_flow() {
  local name="$1" direction="$2" pattern="$3" steps_json="$4" description="${5:-Regression flow}"
  local body; body=$(jq -n \
    --arg n "$name" --arg d "$direction" --arg p "$pattern" \
    --arg desc "$description" --argjson steps "$steps_json" \
    '{name:$n, description:$desc, direction:$d, filenamePattern:$p,
      sourcePath:"/inbox", destinationPath:"/outbox",
      priority:10, active:true, steps:$steps}')
  local resp; resp=$(curl -s -w "\n%{http_code}" -X POST "$API_CONFIG/api/flows" \
    -H "$HDR_AUTH" -H "$HDR_JSON" -d "$body")
  local status="${resp##*$'\n'}"; local body_out="${resp%$'\n'*}"
  if [ "$status" = "201" ]; then
    local id; id=$(echo "$body_out" | jq -r '.id')
    pass "flow '$name' — ${#steps_json} byte step graph — id=${id:0:8}"
  elif [ "$status" = "400" ] && echo "$body_out" | grep -q 'already exists'; then
    skip "flow '$name': already exists"
  else
    fail "flow '$name': HTTP $status — $(echo "$body_out" | head -c 200)"
  fi
}

# F1: simplest — compress + deliver
create_flow "regtest-f1-compress-deliver" INBOUND ".*\\.csv" \
  '[{"type":"COMPRESS_GZIP","order":0,"config":{}},
    {"type":"MAILBOX","order":1,"config":{"destinationUsername":"regtest-sftp-2"}}]' \
  "Compress .csv with gzip then mailbox to regtest-sftp-2"

# F2: security first — screen + compress + deliver
create_flow "regtest-f2-screen-zip-deliver" INBOUND ".*\\.xml" \
  '[{"type":"SCREEN","order":0,"config":{}},
    {"type":"COMPRESS_ZIP","order":1,"config":{}},
    {"type":"MAILBOX","order":2,"config":{"destinationUsername":"regtest-sftp-2"}}]' \
  "OFAC-screen, ZIP, deliver .xml to regtest-sftp-2"

# F3: encrypt before delivery
create_flow "regtest-f3-aes-encrypt-deliver" OUTBOUND ".*\\.json" \
  '[{"type":"ENCRYPT_AES","order":0,"config":{"keyAlias":"aes-default"}},
    {"type":"FILE_DELIVERY","order":1,"config":{"endpoint":"partner-sftp"}}]' \
  "AES-256 encrypt outbound .json then external delivery"

# F4: inbound PGP-decrypt then screen
create_flow "regtest-f4-pgp-decrypt-screen" INBOUND ".*\\.pgp" \
  '[{"type":"DECRYPT_PGP","order":0,"config":{"keyAlias":"pgp-inbound"}},
    {"type":"SCREEN","order":1,"config":{}},
    {"type":"MAILBOX","order":2,"config":{"destinationUsername":"regtest-sftp-1"}}]' \
  "PGP-decrypt, screen, deliver to regtest-sftp-1"

# F5: rename + screen + external forward
create_flow "regtest-f5-rename-fwd" INBOUND "invoice-.*\\.txt" \
  '[{"type":"RENAME","order":0,"config":{"pattern":"invoice-{yyyy-MM-dd}-{seq}.txt"}},
    {"type":"SCREEN","order":1,"config":{}},
    {"type":"FILE_DELIVERY","order":2,"config":{"endpoint":"partner-ftp"}}]' \
  "Rename, screen, external-forward invoice files"

# F6: EDI → JSON + checksum verify
create_flow "regtest-f6-edi-to-json" INBOUND ".*\\.edi" \
  '[{"type":"CONVERT_EDI","order":0,"config":{"targetFormat":"JSON"}},
    {"type":"CHECKSUM_VERIFY","order":1,"config":{}},
    {"type":"MAILBOX","order":2,"config":{"destinationUsername":"regtest-sftp-1"}}]' \
  "EDI X12/EDIFACT to JSON, verify checksum, mailbox"

# F7: script + mailbox (custom pipeline)
#
# NOTE: EXECUTE_SCRIPT is NOT supported for VIRTUAL-mode files (see
#   FlowProcessingEngine.java:1679 — "EXECUTE_SCRIPT is not supported for
#   VIRTUAL-mode accounts"). This flow exists to exercise the config and
#   scheduler paths; it will throw at runtime for VIRTUAL uploads. The
#   script itself is deployed into the worker containers below so that
#   the command path resolves if someone later flips a listener to
#   PHYSICAL for script testing (or runs via scheduler/CRON, which also
#   uses ScheduledTaskRunner.executeScript on real files).
#
# The delete-then-create dance replaces any previous f7 whose `command` was
# the stale "uppercase-header" value, pointing it at the absolute path now
# deployed in the container's /opt/scripts.
section "Copy sample EXECUTE_SCRIPT handler into worker containers"
copy_script_to() {
  local container="$1"
  if ! docker ps --format '{{.Names}}' | grep -qx "$container"; then
    skip "$container: not running — skipping script copy"
    return
  fi
  docker exec -u 0 "$container" sh -c 'mkdir -p /opt/scripts && chmod 755 /opt/scripts' >/dev/null 2>&1 \
    && docker cp scripts/flow-samples/uppercase-header.sh "$container":/opt/scripts/uppercase-header.sh >/dev/null 2>&1 \
    && docker exec -u 0 "$container" chmod +x /opt/scripts/uppercase-header.sh >/dev/null 2>&1 \
    && pass "$container: /opt/scripts/uppercase-header.sh deployed" \
    || fail "$container: failed to copy script"
}
copy_script_to "mft-onboarding-api"   # EXECUTE_SCRIPT consumer per flow.step.EXECUTE_SCRIPT
copy_script_to "mft-sftp-service"     # also the SFTP ingress-side flow step consumer
copy_script_to "mft-ftp-service"      # and FTP

section "Replace flow f7 to reference deployed script"
F7_EXISTING_ID=$(curl -s -H "$HDR_AUTH" \
  "$API_CONFIG/api/flows?name=regtest-f7-script-mailbox" 2>/dev/null \
  | jq -r '.[]? | select(.name=="regtest-f7-script-mailbox") | .id' 2>/dev/null || true)
if [ -z "$F7_EXISTING_ID" ] || [ "$F7_EXISTING_ID" = "null" ]; then
  # /api/flows list is currently broken by a Redis-cache deserialization bug —
  # fall back to DB lookup so we can still find it
  F7_EXISTING_ID=$(docker exec mft-postgres psql -U postgres -d filetransfer -tA -c \
    "SELECT id FROM file_flows WHERE name='regtest-f7-script-mailbox'" 2>/dev/null | tr -d '[:space:]')
fi
if [ -n "$F7_EXISTING_ID" ] && [ "$F7_EXISTING_ID" != "null" ]; then
  curl -s -X DELETE "$API_CONFIG/api/flows/$F7_EXISTING_ID" -H "$HDR_AUTH" >/dev/null
  # Soft-delete marks active=false; to let re-create pass the unique-name
  # check we also clear the name directly (the delete is logical, not physical).
  docker exec mft-postgres psql -U postgres -d filetransfer -c \
    "UPDATE file_flows SET name='regtest-f7-script-mailbox--deleted-'||id, active=false
       WHERE id='$F7_EXISTING_ID'" >/dev/null 2>&1
  pass "cleared existing f7 (id=${F7_EXISTING_ID:0:8}) so it can be re-created"
fi
create_flow "regtest-f7-script-mailbox" INBOUND ".*\\.dat" \
  '[{"type":"EXECUTE_SCRIPT","order":0,"config":{"command":"sh /opt/scripts/uppercase-header.sh ${file}","timeoutSeconds":"60"}},
    {"type":"MAILBOX","order":1,"config":{"destinationUsername":"regtest-sftp-1"}}]' \
  "Run uppercase-header.sh on .dat then mailbox (requires PHYSICAL mode at runtime)"

# F8: outbound gzip+fwd (OUTBOUND direction coverage)
create_flow "regtest-f8-gzip-out-fwd" OUTBOUND ".*\\.log" \
  '[{"type":"COMPRESS_GZIP","order":0,"config":{}},
    {"type":"FILE_DELIVERY","order":1,"config":{"endpoint":"partner-sftp"}}]' \
  "Gzip outbound .log and external delivery"

# ─────────────────────────────────────────────────────────────────────────────
# 4. Summary
# ─────────────────────────────────────────────────────────────────────────────
section "Summary"
echo "Listeners:"
curl -s -H "$HDR_AUTH" "$API_ONBOARDING/api/servers" | \
  jq -r '.[] | select(.instanceId | startswith("sftp-reg") or startswith("ftp-reg")) |
         "  \(.instanceId)\t\(.protocol)\t\(.internalPort)\t\(.defaultStorageMode)\t\(.bindState)"'
echo
echo "Accounts:"
curl -s -H "$HDR_AUTH" "$API_ONBOARDING/api/accounts" | \
  jq -r '.[] | select(.username | startswith("regtest")) |
         "  \(.username)\t\(.protocol)\t\(.homeDir // "-")"'
echo
echo "Flows (via DB — GET /api/flows currently fails w/ Redis cache Jackson type-id bug):"
docker exec mft-postgres psql -U postgres -d filetransfer -tA -F $'\t' -c \
  "SELECT '  '||name, direction, filename_pattern, 'steps='||jsonb_array_length(steps)
     FROM file_flows WHERE name LIKE 'regtest%' ORDER BY name" 2>/dev/null
echo
echo "Done. Use these fixtures to drive upload → journey → delivery regression tests."

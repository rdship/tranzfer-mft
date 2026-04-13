#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Comprehensive Demo Data Onboarding Script
# =============================================================================
# Creates a fully populated demo environment with real onboarded data on every
# microservice and every admin UI screen (24+ items per screen minimum).
#
# Usage: ./scripts/demo-onboard.sh [--skip-docker] [--base-url http://localhost]
# =============================================================================
set -euo pipefail

# --- Configuration ---
BASE=${MFT_BASE_URL:-https://localhost}
API="${BASE}:9080"     # onboarding-api (HTTPS)
CFG="${BASE}:9084"     # config-service
KEY="${BASE}:9093"     # keystore-manager
ANA="${BASE}:9090"     # analytics-service
SCR="${BASE}:9092"     # screening-service
LIC="${BASE}:9089"     # license-service
NOT="${BASE}:9097"     # notification-service
EDI="${BASE}:9095"     # edi-converter
FWD="${BASE}:9087"     # external-forwarder
DMZ="${BASE}:9088"     # dmz-proxy
STR="${BASE}:9094"     # storage-manager/as2
# Accept self-signed certs in all curl calls
export CURL_OPTS="-k"
PLATFORM_JWT_SECRET="${PLATFORM_JWT_SECRET:-changeme_32char_secret_here_!!!!}"  # for DMZ management API calls

ADMIN_EMAIL="${MFT_ADMIN_EMAIL:-superadmin@tranzfer.io}"
ADMIN_PASS="${MFT_ADMIN_PASS:-superadmin}"
TOKEN=""
SKIP_DOCKER=false

# Counters
CREATED=0
FAILED=0
SKIPPED=0

# --- Parse args ---
for arg in "$@"; do
  case $arg in
    --skip-docker) SKIP_DOCKER=true ;;
    --base-url=*) BASE="${arg#*=}"; API="${BASE}:8080"; CFG="${BASE}:8084"; KEY="${BASE}:8093"; ANA="${BASE}:8090"; SCR="${BASE}:8092"; LIC="${BASE}:8089"; NOT="${BASE}:8097"; EDI="${BASE}:8095"; FWD="${BASE}:8087"; DMZ="${BASE}:8088"; STR="${BASE}:8094" ;;
  esac
done

# --- Helpers ---
log()  { echo -e "\033[1;34m[ONBOARD]\033[0m $*"; }
ok()   { echo -e "\033[1;32m  ✓\033[0m $*"; CREATED=$((CREATED+1)); }
fail() { echo -e "\033[1;31m  ✗\033[0m $*"; FAILED=$((FAILED+1)); }
skip() { echo -e "\033[1;33m  ⊘\033[0m $*"; SKIPPED=$((SKIPPED+1)); }

post() {
  local url="$1" data="$2" label="${3:-}"
  local resp code body attempt
  for attempt in 1 2; do
    resp=$(curl -sk -w "\n%{http_code}" -X POST "$url" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "$data" 2>/dev/null)
    code=$(echo "$resp" | tail -1)
    body=$(echo "$resp" | sed '$d')
    if [[ "$code" =~ ^2[0-9][0-9]$ ]]; then
      ok "$label"
      echo "$body"
      return
    elif [[ "$code" == "409" ]] || echo "$body" | grep -qi "already exists\|duplicate\|unique"; then
      skip "$label (already exists)"
      echo "$body"
      return
    fi
    # Retry once after 1s for transient cold-boot failures (connection refused, 500, etc.)
    [[ $attempt -eq 1 ]] && sleep 1
  done
  fail "$label (HTTP $code): $(echo "$body" | head -c 120)"
  echo ""
}

post_noauth() {
  local url="$1" data="$2" label="${3:-}"
  local resp code
  resp=$(curl -sk -w "\n%{http_code}" -X POST "$url" \
    -H "Content-Type: application/json" \
    -d "$data" 2>/dev/null)
  code=$(echo "$resp" | tail -1)
  local body=$(echo "$resp" | sed '$d')
  if [[ "$code" =~ ^2[0-9][0-9]$ ]]; then
    ok "$label"
    echo "$body"
  else
    skip "$label (HTTP $code)"
    echo "$body"
  fi
}

post_admin_key() {
  local url="$1" data="$2" label="${3:-}"
  local resp code
  resp=$(curl -sk -w "\n%{http_code}" -X POST "$url" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$data" 2>/dev/null)
  code=$(echo "$resp" | tail -1)
  local body=$(echo "$resp" | sed '$d')
  if [[ "$code" =~ ^2[0-9][0-9]$ ]]; then
    ok "$label"
    echo "$body"
  else
    skip "$label (HTTP $code)"
    echo "$body"
  fi
}

get() {
  local url="$1"
  curl -sk -X GET "$url" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" 2>/dev/null
}

# Generate a platform JWT (HS256) for DMZ Proxy management API calls.
# DMZ proxy is isolated (no shared Spring Security), so it validates
# requests with its own HMAC-SHA256 platform JWT check.
platform_jwt() {
  local secret="$PLATFORM_JWT_SECRET"
  local exp; exp=$(( $(date +%s) + 3600 ))
  local header; header=$(printf '{"alg":"HS256","typ":"JWT"}' | base64 | tr -d '=\n' | tr '+/' '-_')
  local payload; payload=$(printf '{"sub":"demo-onboard","exp":%d}' "$exp" | base64 | tr -d '=\n' | tr '+/' '-_')
  local sig; sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$secret" -binary | base64 | tr -d '=\n' | tr '+/' '-_')
  printf '%s.%s.%s' "$header" "$payload" "$sig"
}

dmz_post() {
  local url="$1" data="$2" label="${3:-}"
  local resp code
  resp=$(curl -sk -w "\n%{http_code}" -X POST "$url" \
    -H "Authorization: Bearer $(platform_jwt)" \
    -H "Content-Type: application/json" \
    -d "$data" 2>/dev/null)
  code=$(echo "$resp" | tail -1)
  local body=$(echo "$resp" | sed '$d')
  if [[ "$code" =~ ^2[0-9][0-9]$ ]]; then
    ok "$label"
    echo "$body"
  else
    skip "$label (HTTP $code)"
    echo "$body"
  fi
}

# Arrays to store created IDs for cross-referencing
ACCOUNT_IDS=()
SERVER_IDS=()

# Fetch created account IDs (call after create_accounts)
fetch_account_ids() {
  log "Fetching account IDs for cross-references..."
  local resp
  resp=$(get "$API/api/accounts?size=500")
  ACCOUNT_IDS=($(echo "$resp" | python3 -c "
import sys,json
data = json.load(sys.stdin)
if isinstance(data, list):
    accounts = data
elif isinstance(data, dict) and 'content' in data:
    accounts = data['content']
else:
    accounts = []
for a in accounts:
    if isinstance(a, dict) and 'id' in a:
        print(a['id'])
" 2>/dev/null || true))
  ok "Fetched ${#ACCOUNT_IDS[@]} account IDs"
}

# Fetch created server instance IDs (call after create_server_instances)
fetch_server_ids() {
  log "Fetching server instance IDs for cross-references..."
  local resp
  # BUG-C fix (R28): add size=1000 so we don't silently get only page 0.
  # The default page size varies per endpoint (12-20) and previously cut
  # us off at 12 server IDs even when 28 were created, which cascaded
  # failures to every downstream step that cross-references a server.
  resp=$(get "$API/api/servers?size=1000")
  SERVER_IDS=($(echo "$resp" | python3 -c "
import sys,json
data = json.load(sys.stdin)
# Handle both raw list and Spring Page wrapper
servers = data if isinstance(data, list) else data.get('content', data.get('servers', []))
for s in servers:
    sid = s.get('id', '')
    if sid:
        print(sid)
" 2>/dev/null || true))
  ok "Fetched ${#SERVER_IDS[@]} server IDs"
}

wait_for_service() {
  local url="$1" name="$2" max="${3:-60}"
  log "Waiting for $name ($url)..."
  for i in $(seq 1 $max); do
    if curl -skf "${url}/readiness" > /dev/null 2>&1 || curl -skf "$url" > /dev/null 2>&1; then
      ok "$name is ready"
      return 0
    fi
    sleep 2
  done
  fail "$name not ready after ${max}s"
  return 1
}

# =============================================================================
# 0. AUTHENTICATION
# =============================================================================
authenticate() {
  log "=== STEP 0: Authentication ==="

  # Register admin user (idempotent)
  post_noauth "$API/api/auth/register" \
    "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASS\"}" \
    "Register admin user" > /dev/null

  # Login
  local resp
  resp=$(curl -sk -X POST "$API/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASS\"}")
  TOKEN=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null || true)

  if [ -n "$TOKEN" ] && [ "$TOKEN" != "None" ]; then
    ok "Authenticated (token: ${TOKEN:0:20}...)"
  else
    fail "Login failed: $resp"
    exit 1
  fi

  # Ensure admin role (register creates USER role by default)
  log "Promoting user to ADMIN role..."
  docker exec mft-postgres psql -U postgres -d filetransfer -c \
    "UPDATE users SET role = 'ADMIN' WHERE email = '$ADMIN_EMAIL' AND role != 'ADMIN';" > /dev/null 2>&1
  # Re-login to get ADMIN token
  resp=$(curl -sk -X POST "$API/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASS\"}")
  TOKEN=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null || true)
  ok "Re-authenticated with ADMIN role"

  # Ensure /data directories exist in onboarding-api container
  docker exec -u 0 mft-onboarding-api sh -c "mkdir -p /data/sftp /data/ftp /data/web && chmod -R 777 /data" > /dev/null 2>&1
}

# =============================================================================
# 1. TENANTS (24+)
# =============================================================================
create_tenants() {
  log "=== STEP 1: Tenants (26) ==="
  local plans=("TRIAL" "STANDARD" "PROFESSIONAL" "ENTERPRISE")
  local industries=("finance" "healthcare" "logistics" "retail" "manufacturing" "energy" "telecom" "government" "education" "media" "pharma" "insurance" "automotive" "aerospace" "agri" "mining" "construction" "legal" "realestate" "transport" "foodbev" "textile" "chemical" "defense" "tech" "consulting")

  for i in "${!industries[@]}"; do
    local slug="${industries[$i]}-corp"
    local plan="${plans[$((i % ${#plans[@]}))]}"
    post "$API/api/v1/tenants/signup" \
      "{\"slug\":\"$slug\",\"companyName\":\"${industries[$i]^} Corporation\",\"contactEmail\":\"admin@${slug}.com\",\"plan\":\"$plan\"}" \
      "Tenant: $slug" > /dev/null
  done
}

# =============================================================================
# 2. SERVER INSTANCES (28 — 8 SFTP, 8 FTP, 6 FTP_WEB, 6 HTTPS)
# =============================================================================
create_server_instances() {
  log "=== STEP 2: Server Instances (28) ==="

  # SFTP instances (ServerConfig format: name, serviceType, host, port, properties)
  for i in $(seq 1 8); do
    post "$API/api/servers" \
      "{\"name\":\"sftp-$i\",\"serviceType\":\"SFTP\",\"host\":\"sftp-service\",\"port\":$((2222+i-1)),\"properties\":{\"maxConnections\":\"500\",\"externalHost\":\"sftp$i.tranzfer.io\",\"externalPort\":\"22\"}}" \
      "ServerConfig: sftp-$i" > /dev/null
    sleep 0.05
  done

  # FTP instances
  for i in $(seq 1 8); do
    post "$API/api/servers" \
      "{\"name\":\"ftp-$i\",\"serviceType\":\"FTP\",\"host\":\"ftp-service\",\"port\":$((21+i-1)),\"properties\":{\"maxConnections\":\"500\",\"externalHost\":\"ftp$i.tranzfer.io\",\"externalPort\":\"21\"}}" \
      "ServerConfig: ftp-$i" > /dev/null
    sleep 0.05
  done

  # FTP_WEB instances
  for i in $(seq 1 6); do
    post "$API/api/servers" \
      "{\"name\":\"ftpweb-$i\",\"serviceType\":\"FTP_WEB\",\"host\":\"ftp-web-service\",\"port\":8083,\"properties\":{\"maxConnections\":\"200\",\"externalHost\":\"web$i.tranzfer.io\",\"externalPort\":\"443\"}}" \
      "ServerConfig: ftpweb-$i" > /dev/null
    sleep 0.05
  done

  # Gateway instances
  for i in $(seq 1 6); do
    post "$API/api/servers" \
      "{\"name\":\"https-$i\",\"serviceType\":\"GATEWAY\",\"host\":\"gateway-service\",\"port\":8085,\"properties\":{\"maxConnections\":\"1000\",\"externalHost\":\"api$i.tranzfer.io\",\"externalPort\":\"443\"}}" \
      "ServerConfig: https-$i" > /dev/null
    sleep 0.05
  done
}

# =============================================================================
# 3. FOLDER TEMPLATES (26)
# =============================================================================
create_folder_templates() {
  log "=== STEP 3: Folder Templates (26) ==="
  local templates=(
    "edi-standard|EDI Standard Layout|/inbound,/outbound,/archive,/error,/ack"
    "healthcare-hl7|Healthcare HL7|/inbound/hl7,/outbound/hl7,/archive,/error,/audit"
    "finance-swift|SWIFT Banking|/inbound/swift,/outbound/swift,/archive,/nack,/audit"
    "logistics-edi|Logistics EDI|/inbound/edi,/outbound/edi,/archive,/tracking,/pod"
    "retail-pos|Retail POS|/sales,/inventory,/returns,/archive,/reports"
    "insurance-claims|Insurance Claims|/inbound/claims,/outbound/remit,/archive,/denied,/audit"
    "pharma-gxp|Pharma GxP|/inbound/validated,/outbound/validated,/quarantine,/audit,/signatures"
    "automotive-edifact|Automotive EDIFACT|/inbound/orders,/outbound/asn,/archive,/error,/ack"
    "government-sftp|Government SFTP|/inbound/secure,/outbound/secure,/archive,/audit,/classified"
    "media-assets|Media Asset Transfer|/inbound/raw,/outbound/transcoded,/archive,/preview,/metadata"
    "telecom-cdr|Telecom CDR|/inbound/cdr,/outbound/rated,/archive,/rejects,/reports"
    "energy-scada|Energy SCADA|/inbound/realtime,/outbound/commands,/archive,/alerts,/logs"
    "legal-discovery|Legal eDiscovery|/inbound/documents,/outbound/reviewed,/archive,/privileged,/productions"
    "construction-bim|Construction BIM|/models,/drawings,/specs,/archive,/submittals"
    "education-sis|Education SIS|/student-data,/transcripts,/financial-aid,/archive,/reports"
    "agri-supply|Agriculture Supply|/orders,/shipments,/invoices,/archive,/quality"
    "mining-telemetry|Mining Telemetry|/sensors,/processed,/archive,/alerts,/maintenance"
    "realestate-docs|Real Estate Docs|/listings,/contracts,/closings,/archive,/compliance"
    "textile-orders|Textile Orders|/purchase-orders,/confirmations,/shipping,/archive,/quality"
    "chemical-msds|Chemical MSDS|/inbound/msds,/outbound/sds,/archive,/regulatory,/audit"
    "defense-secure|Defense Secure|/classified,/unclassified,/transit,/archive,/audit"
    "consulting-reports|Consulting Reports|/deliverables,/drafts,/final,/archive,/feedback"
    "foodbev-haccp|Food & Bev HACCP|/inbound/test-results,/outbound/certificates,/archive,/recalls,/audit"
    "transport-tms|Transport TMS|/bookings,/tracking,/pods,/archive,/invoices"
    "aerospace-as9100|Aerospace AS9100|/engineering,/quality,/procurement,/archive,/nonconformance"
    "generic-simple|Generic Simple|/inbound,/outbound,/archive,/error"
  )

  for tmpl in "${templates[@]}"; do
    IFS='|' read -r slug name folders <<< "$tmpl"
    local json_folders="["
    local first=true
    IFS=',' read -ra dirs <<< "$folders"
    for dir in "${dirs[@]}"; do
      $first || json_folders+=","
      first=false
      json_folders+="{\"path\":\"$dir\",\"description\":\"$(echo $dir | tr '/' ' ' | xargs)\"}"
    done
    json_folders+="]"
    post "$CFG/api/folder-templates" \
      "{\"name\":\"$name\",\"description\":\"Template for ${name} workflows\",\"folders\":$json_folders}" \
      "FolderTemplate: $name" > /dev/null
  done
}

# =============================================================================
# 4. SECURITY PROFILES (26)
# =============================================================================
create_security_profiles() {
  log "=== STEP 4: Security Profiles (26) ==="

  # SSH profiles
  local ssh_ciphers='["aes256-gcm@openssh.com","aes128-gcm@openssh.com","aes256-ctr","chacha20-poly1305@openssh.com"]'
  local ssh_macs='["hmac-sha2-512-etm@openssh.com","hmac-sha2-256-etm@openssh.com","hmac-sha2-512","hmac-sha2-256"]'
  local ssh_kex='["curve25519-sha256","ecdh-sha2-nistp521","ecdh-sha2-nistp384","diffie-hellman-group18-sha512"]'
  local ssh_hostkey='["ssh-ed25519","ecdsa-sha2-nistp384","rsa-sha2-512","rsa-sha2-256"]'

  local ssh_names=("FIPS-140-SSH" "PCI-DSS-SSH" "NIST-800-53-SSH" "HIPAA-SSH" "SOC2-SSH" "ISO27001-SSH" "DOD-STD-SSH" "SWIFT-CSP-SSH" "GDPR-SSH" "FedRAMP-SSH" "SSH-Modern" "SSH-Legacy-Compat" "SSH-High-Security")
  for name in "${ssh_names[@]}"; do
    post "$CFG/api/security-profiles" \
      "{\"name\":\"$name\",\"type\":\"SSH\",\"sshCiphers\":$ssh_ciphers,\"sshMacs\":$ssh_macs,\"kexAlgorithms\":$ssh_kex,\"hostKeyAlgorithms\":$ssh_hostkey}" \
      "SecurityProfile: $name" > /dev/null
  done

  # TLS profiles
  local tls_ciphers='["TLS_AES_256_GCM_SHA384","TLS_AES_128_GCM_SHA256","TLS_CHACHA20_POLY1305_SHA256"]'
  local tls_names=("FIPS-140-TLS" "PCI-DSS-TLS" "NIST-TLS" "HIPAA-TLS" "SOC2-TLS" "ISO27001-TLS" "DOD-STD-TLS" "SWIFT-CSP-TLS" "GDPR-TLS" "FedRAMP-TLS" "TLS-Modern" "TLS-1.3-Only" "TLS-Strict")
  for name in "${tls_names[@]}"; do
    post "$CFG/api/security-profiles" \
      "{\"name\":\"$name\",\"type\":\"TLS\",\"tlsMinVersion\":\"TLSv1.3\",\"tlsCiphers\":$tls_ciphers,\"clientAuthRequired\":false}" \
      "SecurityProfile: $name" > /dev/null
  done
}

# =============================================================================
# 5. PARTNERS (42+) — with various types and phases
# =============================================================================
create_partners() {
  log "=== STEP 5: Partners (48) ==="
  local types=("EXTERNAL" "INTERNAL" "VENDOR" "CLIENT")
  local phases=("SETUP" "CREDENTIALS" "TESTING" "LIVE")
  local statuses=("ACTIVE" "ACTIVE" "ACTIVE" "PENDING" "SUSPENDED")
  local tiers=("STANDARD" "PREMIUM" "ENTERPRISE")

  local names=(
    "Acme Financial" "GlobalBank Holdings" "MedTech Solutions" "PharmaFirst Inc"
    "RetailMax Corp" "LogiTrans Global" "EnergyOne Corp" "TelecomHub Ltd"
    "GovSecure Agency" "MediaStream Inc" "InsureCo Group" "AutoParts Direct"
    "AeroSpace Dynamics" "AgriFood Trading" "MineralEx Corp" "BuildRight Construction"
    "LegalEdge LLP" "PropVest Realty" "TextilePro Mfg" "ChemSafe Industries"
    "DefenseCorp" "ConsultPro Group" "FreshFoods Distribution" "TransGlobal Shipping"
    "SkyHigh Airlines" "TechVenture Labs" "EduConnect Systems" "Pacific Trading Co"
    "Nordic Exports AB" "Sahara Resources" "Amazon Logistics" "Deutsche Handel GmbH"
    "Tokyo Electronics" "Mumbai Finserv" "Sydney Healthcare" "Cairo Textiles"
    "Seoul Semiconductor" "Mexico Auto Parts" "Brazil Agri Export" "Kenya Coffee Co"
    "Dubai Port Authority" "Singapore Shipping" "London Insurance" "Paris Fashion House"
    "Rome Food Group" "Moscow Energy" "Jakarta Palm Oil" "Manila BPO Services"
  )

  for i in "${!names[@]}"; do
    local name="${names[$i]}"
    local slug=$(echo "$name" | tr '[:upper:]' '[:lower:]' | tr ' ' '-' | tr -cd 'a-z0-9-')
    local type="${types[$((i % ${#types[@]}))]}"
    local phase="${phases[$((i % ${#phases[@]}))]}"
    local status="${statuses[$((i % ${#statuses[@]}))]}"
    local tier="${tiers[$((i % ${#tiers[@]}))]}"
    local protocols='["SFTP","FTP"]'
    [[ $((i % 3)) -eq 0 ]] && protocols='["SFTP","AS2"]'
    [[ $((i % 5)) -eq 0 ]] && protocols='["SFTP","FTP","AS2","AS4"]'

    local contact_first=$(echo "$name" | awk '{print $1}')
    local contact_domain=$(echo "$slug" | head -c 20)
    post "$API/api/partners" \
      "{\"companyName\":\"$name\",\"displayName\":\"$name\",\"partnerType\":\"$type\",\"protocolsEnabled\":$protocols,\"slaTier\":\"$tier\",\"industry\":\"$(echo $slug | cut -d- -f1)\",\"maxFileSizeBytes\":$((512*1024*1024)),\"maxTransfersPerDay\":$((1000 + i*100)),\"retentionDays\":$((30 + i*3)),\"contacts\":[{\"name\":\"${contact_first} Admin\",\"email\":\"admin@${contact_domain}.com\",\"phone\":\"+1-555-$(printf '%04d' $i)\",\"role\":\"IT Manager\",\"primary\":true},{\"name\":\"${contact_first} Support\",\"email\":\"support@${contact_domain}.com\",\"role\":\"Technical Support\",\"primary\":false}]}" \
      "Partner: $name" > /dev/null
  done
}

# =============================================================================
# 6. TRANSFER ACCOUNTS — 100 SFTP + 100 FTP + 25 FTP_WEB
# =============================================================================
create_accounts() {
  log "=== STEP 6a: SFTP Accounts (100) ==="
  for i in $(seq 1 100); do
    local user="sftp_user_$(printf '%03d' $i)"
    local server="sftp-$((((i-1) % 2) + 1))"
    post "$API/api/accounts" \
      "{\"protocol\":\"SFTP\",\"username\":\"$user\",\"password\":\"SftpPass@${i}!\",\"serverInstance\":\"$server\",\"permissions\":{\"read\":true,\"write\":true,\"delete\":$([ $((i%5)) -eq 0 ] && echo true || echo false)}}" \
      "SFTP Account: $user" > /dev/null
  done

  log "=== STEP 6b: FTP Accounts (100) ==="
  for i in $(seq 1 100); do
    local user="ftp_user_$(printf '%03d' $i)"
    local server="ftp-$((((i-1) % 2) + 1))"
    post "$API/api/accounts" \
      "{\"protocol\":\"FTP\",\"username\":\"$user\",\"password\":\"FtpPass@${i}!\",\"serverInstance\":\"$server\",\"permissions\":{\"read\":true,\"write\":true,\"delete\":false}}" \
      "FTP Account: $user" > /dev/null
  done

  log "=== STEP 6c: FTP_WEB Accounts (25) ==="
  for i in $(seq 1 25); do
    local user="web_user_$(printf '%03d' $i)"
    post "$API/api/accounts" \
      "{\"protocol\":\"FTP_WEB\",\"username\":\"$user\",\"password\":\"WebPass@${i}!\",\"serverInstance\":\"ftpweb-$((((i-1) % 2) + 1))\",\"permissions\":{\"read\":true,\"write\":true,\"delete\":false}}" \
      "FTP_WEB Account: $user" > /dev/null
  done
}

# =============================================================================
# 7. KEYS & CERTIFICATES via Keystore Manager (30+)
# =============================================================================
create_keys() {
  log "=== STEP 7: Keys & Certificates (30) ==="

  # SSH host keys
  for i in $(seq 1 6); do
    post_admin_key "$KEY/api/v1/keys/generate/ssh-host" \
      "{\"alias\":\"host-key-sftp-$i\",\"ownerService\":\"sftp-service\"}" \
      "SSH Host Key: sftp-$i" > /dev/null
  done

  # SSH user keys
  for i in $(seq 1 6); do
    post_admin_key "$KEY/api/v1/keys/generate/ssh-user" \
      "{\"alias\":\"user-key-partner-$i\",\"partnerAccount\":\"sftp_user_$(printf '%03d' $i)\",\"keySize\":4096}" \
      "SSH User Key: partner-$i" > /dev/null
  done

  # AES keys
  for i in $(seq 1 4); do
    post_admin_key "$KEY/api/v1/keys/generate/aes" \
      "{\"alias\":\"aes-key-$i\",\"ownerService\":\"encryption-service\"}" \
      "AES Key: $i" > /dev/null
  done

  # TLS certificates
  for i in $(seq 1 6); do
    local cn="server$i.tranzfer.io"
    post_admin_key "$KEY/api/v1/keys/generate/tls" \
      "{\"alias\":\"tls-cert-$cn\",\"cn\":\"$cn\",\"validDays\":365}" \
      "TLS Cert: $cn" > /dev/null
  done

  # PGP keypairs
  for i in $(seq 1 4); do
    post_admin_key "$KEY/api/v1/keys/generate/pgp" \
      "{\"alias\":\"pgp-partner-$i\",\"identity\":\"partner$i@tranzfer.io\",\"passphrase\":\"PgpPass@$i\"}" \
      "PGP Key: partner-$i" > /dev/null
  done

  # HMAC keys
  for i in $(seq 1 4); do
    post_admin_key "$KEY/api/v1/keys/generate/hmac" \
      "{\"alias\":\"hmac-webhook-$i\",\"ownerService\":\"notification-service\"}" \
      "HMAC Key: webhook-$i" > /dev/null
  done
}

# =============================================================================
# 8. ENCRYPTION KEYS in config-service (26)
# =============================================================================
create_encryption_keys() {
  log "=== STEP 8: Encryption Keys (26) ==="
  local algos=("PGP" "AES_256_GCM")

  if [ ${#ACCOUNT_IDS[@]} -lt 26 ]; then
    fail "Not enough account IDs (${#ACCOUNT_IDS[@]}) for encryption keys"
    return
  fi

  for i in $(seq 1 26); do
    local algo="${algos[$((i % 2))]}"
    local name="enc-key-$(printf '%03d' $i)"
    local acct_id="${ACCOUNT_IDS[$((i-1))]}"
    local json="{\"keyName\":\"$name\",\"algorithm\":\"$algo\",\"account\":{\"id\":\"$acct_id\"}"
    if [ "$algo" = "PGP" ]; then
      json+=",\"publicKey\":\"-----BEGIN PGP PUBLIC KEY BLOCK-----\nFAKE_PGP_KEY_FOR_DEMO_$i\n-----END PGP PUBLIC KEY BLOCK-----\""
    fi
    json+="}"
    post "$CFG/api/encryption-keys" "$json" "EncryptionKey: $name ($algo)" > /dev/null
  done
}

# =============================================================================
# 9. EXTERNAL DESTINATIONS (30 — SFTP/FTP/Kafka mix)
# =============================================================================
create_external_destinations() {
  log "=== STEP 9: External Destinations (30) ==="

  # SFTP destinations
  for i in $(seq 1 12); do
    post "$CFG/api/external-destinations" \
      "{\"name\":\"SFTP Dest - Partner $i\",\"type\":\"SFTP\",\"host\":\"partner${i}.example.com\",\"port\":22,\"username\":\"upload_$i\",\"encryptedPassword\":\"enc_pass_$i\",\"remotePath\":\"/inbound\",\"active\":$([ $((i%4)) -ne 0 ] && echo true || echo false)}" \
      "ExtDest SFTP: Partner $i" > /dev/null
  done

  # FTP destinations
  for i in $(seq 1 12); do
    post "$CFG/api/external-destinations" \
      "{\"name\":\"FTP Dest - Vendor $i\",\"type\":\"FTP\",\"host\":\"vendor${i}.example.com\",\"port\":21,\"username\":\"vendor_$i\",\"encryptedPassword\":\"enc_pass_$i\",\"remotePath\":\"/upload\",\"active\":$([ $((i%4)) -ne 0 ] && echo true || echo false)}" \
      "ExtDest FTP: Vendor $i" > /dev/null
  done

  # Kafka destinations
  for i in $(seq 1 6); do
    local topics=("file-events" "audit-trail" "compliance-log" "etl-ingest" "realtime-feed" "archive-queue")
    post "$CFG/api/external-destinations" \
      "{\"name\":\"Kafka - ${topics[$((i-1))]}\",\"type\":\"KAFKA\",\"kafkaTopic\":\"${topics[$((i-1))]}\",\"kafkaBootstrapServers\":\"kafka.internal:9092\",\"active\":true}" \
      "ExtDest Kafka: ${topics[$((i-1))]}" > /dev/null
  done
}

# =============================================================================
# 10. DELIVERY ENDPOINTS (30 — multi-protocol)
# =============================================================================
create_delivery_endpoints() {
  log "=== STEP 10: Delivery Endpoints (30) ==="
  local protocols=("SFTP" "FTP" "FTPS" "HTTP" "HTTPS" "API" "AS2" "AS4")
  local auth_types=("BASIC" "SSH_KEY" "BEARER_TOKEN" "API_KEY" "BASIC" "OAUTH2" "BASIC" "BASIC")

  for i in $(seq 1 30); do
    local idx=$(( (i-1) % ${#protocols[@]} ))
    local proto="${protocols[$idx]}"
    local auth="${auth_types[$idx]}"
    local name="EP-${proto}-$(printf '%03d' $i)"
    local host="endpoint${i}.tranzfer.io"
    local port=$((8443 + i))

    post "$CFG/api/delivery-endpoints" \
      "{\"name\":\"$name\",\"protocol\":\"$proto\",\"host\":\"$host\",\"port\":$port,\"basePath\":\"/api/v1/receive\",\"authType\":\"$auth\",\"username\":\"svc_$i\",\"encryptedPassword\":\"enc_$i\",\"connectionTimeoutMs\":30000,\"readTimeoutMs\":60000,\"retryCount\":3,\"retryDelayMs\":5000,\"tlsEnabled\":true,\"active\":$([ $((i%5)) -ne 0 ] && echo true || echo false)}" \
      "DeliveryEndpoint: $name" > /dev/null
  done
}

# =============================================================================
# 11. AS2/AS4 PARTNERSHIPS (42 — split AS2/AS4)
# =============================================================================
create_as2_partnerships() {
  log "=== STEP 11: AS2/AS4 Partnerships (42) ==="
  local enc_algos=("AES128" "AES256" "AES192")
  local sign_algos=("SHA256" "SHA384" "SHA512")

  for i in $(seq 1 28); do
    post "$CFG/api/as2-partnerships" \
      "{\"partnerName\":\"AS2 Partner $i\",\"partnerAs2Id\":\"AS2-PARTNER-$(printf '%03d' $i)\",\"ourAs2Id\":\"TRANZFER-MFT\",\"endpointUrl\":\"https://as2-partner${i}.example.com/as2/receive\",\"signingAlgorithm\":\"${sign_algos[$((i%3))]}\",\"encryptionAlgorithm\":\"${enc_algos[$((i%3))]}\",\"mdnRequired\":true,\"mdnAsync\":$([ $((i%3)) -eq 0 ] && echo true || echo false),\"protocol\":\"AS2\",\"active\":$([ $((i%5)) -ne 0 ] && echo true || echo false)}" \
      "AS2 Partnership: Partner $i" > /dev/null
  done

  for i in $(seq 1 14); do
    post "$CFG/api/as2-partnerships" \
      "{\"partnerName\":\"AS4 Partner $i\",\"partnerAs2Id\":\"AS4-PARTNER-$(printf '%03d' $i)\",\"ourAs2Id\":\"TRANZFER-MFT-AS4\",\"endpointUrl\":\"https://as4-partner${i}.example.com/as4/receive\",\"signingAlgorithm\":\"SHA256\",\"encryptionAlgorithm\":\"AES256\",\"mdnRequired\":true,\"protocol\":\"AS4\",\"active\":$([ $((i%4)) -ne 0 ] && echo true || echo false)}" \
      "AS4 Partnership: Partner $i" > /dev/null
  done
}

# =============================================================================
# 12. FILE FLOWS (200+ — varied use cases)
# =============================================================================
create_file_flows() {
  log "=== STEP 12: File Flows (200) ==="

  local flow_num=0
  # Throttle: config-service's connection pool gets exhausted by 200 rapid POSTs.
  # 50ms delay prevents piling up connections while adding only ~10s total.
  local FLOW_DELAY=0.05

  # --- Encrypt & Deliver via SFTP (40 flows) ---
  for i in $(seq 1 40); do
    ((flow_num++))
    local name="encrypt-sftp-deliver-$(printf '%03d' $flow_num)"
    post "$CFG/api/flows" \
      "{\"name\":\"$name\",\"filenamePattern\":\".*\\\\.csv$\",\"direction\":\"OUTBOUND\",\"priority\":$((100+i)),\"active\":true,\"steps\":[{\"type\":\"ENCRYPT_PGP\",\"config\":{\"keyAlias\":\"pgp-partner-$((i%4+1))\"},\"order\":1},{\"type\":\"FILE_DELIVERY\",\"config\":{\"protocol\":\"SFTP\",\"destination\":\"partner$((i%12+1)).example.com\"},\"order\":2}]}" \
      "Flow: $name" > /dev/null
    sleep $FLOW_DELAY
  done

  # --- Decrypt & Archive (30 flows) ---
  for i in $(seq 1 30); do
    ((flow_num++))
    local name="decrypt-archive-$(printf '%03d' $flow_num)"
    post "$CFG/api/flows" \
      "{\"name\":\"$name\",\"filenamePattern\":\".*\\\\.pgp$\",\"direction\":\"INBOUND\",\"priority\":$((200+i)),\"active\":true,\"steps\":[{\"type\":\"DECRYPT_PGP\",\"config\":{\"keyAlias\":\"pgp-partner-$((i%4+1))\"},\"order\":1},{\"type\":\"MAILBOX\",\"config\":{\"folder\":\"/archive\"},\"order\":2}]}" \
      "Flow: $name" > /dev/null
    sleep $FLOW_DELAY
  done

  # --- AES Encrypt & FTP Deliver (25 flows) ---
  for i in $(seq 1 25); do
    ((flow_num++))
    local name="aes-ftp-deliver-$(printf '%03d' $flow_num)"
    post "$CFG/api/flows" \
      "{\"name\":\"$name\",\"filenamePattern\":\".*\\\\.dat$\",\"direction\":\"OUTBOUND\",\"priority\":$((300+i)),\"active\":true,\"steps\":[{\"type\":\"ENCRYPT_AES\",\"config\":{\"keyAlias\":\"aes-key-$((i%4+1))\"},\"order\":1},{\"type\":\"FILE_DELIVERY\",\"config\":{\"protocol\":\"FTP\",\"destination\":\"vendor$((i%12+1)).example.com\"},\"order\":2}]}" \
      "Flow: $name" > /dev/null
    sleep $FLOW_DELAY
  done

  # --- Compress & Screen & Deliver (25 flows) ---
  for i in $(seq 1 25); do
    ((flow_num++))
    local name="compress-screen-deliver-$(printf '%03d' $flow_num)"
    post "$CFG/api/flows" \
      "{\"name\":\"$name\",\"filenamePattern\":\".*\\\\.(xml|json)$\",\"direction\":\"OUTBOUND\",\"priority\":$((400+i)),\"active\":true,\"steps\":[{\"type\":\"COMPRESS_GZIP\",\"config\":{},\"order\":1},{\"type\":\"SCREEN\",\"config\":{\"policyId\":\"dlp-$((i%5+1))\"},\"order\":2},{\"type\":\"FILE_DELIVERY\",\"config\":{\"protocol\":\"SFTP\"},\"order\":3}]}" \
      "Flow: $name" > /dev/null
    sleep $FLOW_DELAY
  done

  # --- EDI Convert & Route (20 flows) ---
  for i in $(seq 1 20); do
    ((flow_num++))
    local name="edi-convert-route-$(printf '%03d' $flow_num)"
    local formats=("X12_850" "X12_810" "X12_856" "EDIFACT_ORDERS" "EDIFACT_INVOIC")
    post "$CFG/api/flows" \
      "{\"name\":\"$name\",\"filenamePattern\":\".*\\\\.edi$\",\"direction\":\"INBOUND\",\"priority\":$((500+i)),\"active\":true,\"steps\":[{\"type\":\"ROUTE\",\"config\":{\"format\":\"${formats[$((i%5))]}\"},\"order\":1},{\"type\":\"MAILBOX\",\"config\":{\"folder\":\"/processed\"},\"order\":2}]}" \
      "Flow: $name" > /dev/null
    sleep $FLOW_DELAY
  done

  # --- Decompress & Decrypt & Rename (20 flows) ---
  for i in $(seq 1 20); do
    ((flow_num++))
    local name="decomp-decrypt-rename-$(printf '%03d' $flow_num)"
    post "$CFG/api/flows" \
      "{\"name\":\"$name\",\"filenamePattern\":\".*\\\\.gz\\\\.pgp$\",\"direction\":\"INBOUND\",\"priority\":$((600+i)),\"active\":true,\"steps\":[{\"type\":\"DECRYPT_PGP\",\"config\":{},\"order\":1},{\"type\":\"DECOMPRESS_GZIP\",\"config\":{},\"order\":2},{\"type\":\"RENAME\",\"config\":{\"pattern\":\"\${filename}_processed_\${timestamp}\"},\"order\":3}]}" \
      "Flow: $name" > /dev/null
    sleep $FLOW_DELAY
  done

  # --- Script Execute & Deliver (20 flows) ---
  for i in $(seq 1 20); do
    ((flow_num++))
    local name="script-deliver-$(printf '%03d' $flow_num)"
    post "$CFG/api/flows" \
      "{\"name\":\"$name\",\"filenamePattern\":\".*\\\\.txt$\",\"direction\":\"OUTBOUND\",\"priority\":$((700+i)),\"active\":true,\"steps\":[{\"type\":\"EXECUTE_SCRIPT\",\"config\":{\"script\":\"/opt/scripts/transform.sh\"},\"order\":1},{\"type\":\"FILE_DELIVERY\",\"config\":{\"protocol\":\"HTTPS\"},\"order\":2}]}" \
      "Flow: $name" > /dev/null
    sleep $FLOW_DELAY
  done

  # --- ZIP Compress & AS2 Deliver (20 flows) ---
  for i in $(seq 1 20); do
    ((flow_num++))
    local name="zip-as2-deliver-$(printf '%03d' $flow_num)"
    post "$CFG/api/flows" \
      "{\"name\":\"$name\",\"filenamePattern\":\".*\\\\.xml$\",\"direction\":\"OUTBOUND\",\"priority\":$((800+i)),\"active\":true,\"steps\":[{\"type\":\"COMPRESS_ZIP\",\"config\":{},\"order\":1},{\"type\":\"FILE_DELIVERY\",\"config\":{\"protocol\":\"AS2\",\"partnershipId\":\"AS2-PARTNER-$(printf '%03d' $((i%28+1)))\"},\"order\":2}]}" \
      "Flow: $name" > /dev/null
    sleep $FLOW_DELAY
  done

  # BUG-D fix (R28): query the DB for the real count instead of trusting
  # the loop counter, which counts iterations not successes.
  local real_count
  real_count=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "SELECT COUNT(*) FROM file_flows" 2>/dev/null | tr -d ' ' || echo "?")
  log "  Total flows attempted: $flow_num, actual in DB: $real_count"
}

# =============================================================================
# 13. FOLDER MAPPINGS (30)
# =============================================================================
create_folder_mappings() {
  log "=== STEP 13: Folder Mappings (30) ==="
  if [ ${#ACCOUNT_IDS[@]} -lt 200 ]; then
    fail "Not enough account IDs (${#ACCOUNT_IDS[@]}) for folder mappings — need at least 200 (100 SFTP + 100 FTP)"
    return
  fi
  # SFTP accounts are indices 0-99, FTP accounts are indices 100-199
  for i in $(seq 0 29); do
    local src_id="${ACCOUNT_IDS[$i]}"
    local dst_id="${ACCOUNT_IDS[$((i + 100))]}"
    post "$API/api/folder-mappings" \
      "{\"sourceAccountId\":\"$src_id\",\"destinationAccountId\":\"$dst_id\",\"sourcePath\":\"/outbound\",\"destinationPath\":\"/inbound\"}" \
      "FolderMapping: account[$i] → account[$((i+100))]" > /dev/null
  done
}

# =============================================================================
# 14. SCHEDULED TASKS (26)
# =============================================================================
create_scheduled_tasks() {
  log "=== STEP 14: Scheduled Tasks (26) ==="
  local tasks=(
    "Nightly SFTP Pull - Finance|0 0 2 * * *|PULL_FILES|America/New_York"
    "Hourly FTP Push - Logistics|0 0 * * * *|PUSH_FILES|UTC"
    "Daily EDI Processing|0 30 3 * * *|RUN_FLOW|America/Chicago"
    "Weekly Cleanup - Archive|0 0 4 * * 0|CLEANUP|UTC"
    "Morning SFTP Pull - Healthcare|0 0 6 * * *|PULL_FILES|America/New_York"
    "Afternoon FTP Push - Retail|0 0 14 * * *|PUSH_FILES|America/Los_Angeles"
    "Nightly Script - Data Transform|0 0 1 * * *|EXECUTE_SCRIPT|UTC"
    "Bi-hourly SFTP Sync|0 0 */2 * * *|PULL_FILES|Europe/London"
    "Daily Invoice Push|0 0 8 * * MON-FRI|PUSH_FILES|America/New_York"
    "Weekly Report Generation|0 0 5 * * 1|RUN_FLOW|UTC"
    "Monthly Archive Cleanup|0 0 3 1 * *|CLEANUP|UTC"
    "Daily PGP Key Rotation Check|0 0 7 * * *|EXECUTE_SCRIPT|UTC"
    "Hourly Compliance Scan|0 30 * * * *|RUN_FLOW|America/New_York"
    "Nightly Backup Push|0 0 23 * * *|PUSH_FILES|UTC"
    "Morning EDI Pull - Automotive|0 0 5 * * MON-FRI|PULL_FILES|Europe/Berlin"
    "Afternoon AS2 Batch|0 0 15 * * MON-FRI|PUSH_FILES|UTC"
    "Daily SFTP Sweep|0 0 22 * * *|PULL_FILES|Asia/Tokyo"
    "Weekly Vendor Sync|0 0 6 * * 3|PUSH_FILES|UTC"
    "Daily CAS Cleanup|0 0 4 * * *|CLEANUP|UTC"
    "Hourly DMZ Health Check|0 15 * * * *|EXECUTE_SCRIPT|UTC"
    "Daily Sanctions List Refresh|0 0 3 * * *|EXECUTE_SCRIPT|UTC"
    "Nightly Analytics Roll-up|0 0 2 * * *|RUN_FLOW|UTC"
    "Weekly Certificate Expiry Check|0 0 8 * * 1|EXECUTE_SCRIPT|UTC"
    "Daily FTP Cleanup - Temp|0 0 5 * * *|CLEANUP|UTC"
    "Bi-daily SWIFT Pull|0 0 6,18 * * MON-FRI|PULL_FILES|Europe/London"
    "Monthly License Audit|0 0 9 1 * *|EXECUTE_SCRIPT|UTC"
  )

  for task in "${tasks[@]}"; do
    IFS='|' read -r name cron type tz <<< "$task"
    # EXECUTE_SCRIPT tasks require a config.command — provide sensible defaults
    local config_field=""
    if [ "$type" = "EXECUTE_SCRIPT" ]; then
      case "$name" in
        *"PGP Key Rotation"*)  config_field=',"config":{"command":"check-pgp-expiry","timeoutSeconds":"300"}' ;;
        *"Data Transform"*)    config_field=',"config":{"command":"transform-nightly","timeoutSeconds":"600"}' ;;
        *"DMZ Health"*)        config_field=',"config":{"command":"dmz-health-probe","timeoutSeconds":"60"}' ;;
        *"Sanctions List"*)    config_field=',"config":{"command":"refresh-sanctions-lists","timeoutSeconds":"300"}' ;;
        *"Certificate Expiry"*) config_field=',"config":{"command":"check-cert-expiry","timeoutSeconds":"120"}' ;;
        *"License Audit"*)     config_field=',"config":{"command":"license-usage-audit","timeoutSeconds":"300"}' ;;
        *)                     config_field=',"config":{"command":"noop","timeoutSeconds":"60"}' ;;
      esac
    fi
    post "$CFG/api/scheduler" \
      "{\"name\":\"$name\",\"cronExpression\":\"$cron\",\"taskType\":\"$type\",\"timezone\":\"$tz\",\"enabled\":true${config_field}}" \
      "Scheduler: $name" > /dev/null
  done
}

# =============================================================================
# 15. SLA AGREEMENTS (26)
# =============================================================================
create_sla_agreements() {
  log "=== STEP 15: SLA Agreements (26) ==="
  local days_sets=(
    '["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"]'
    '["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY"]'
    '["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"]'
  )

  for i in $(seq 1 26); do
    local start_hr=$(( (i*2) % 24 ))
    local end_hr=$(( (start_hr + 4) % 24 ))
    [ $end_hr -le $start_hr ] && end_hr=$((start_hr + 4))
    [ $end_hr -gt 23 ] && end_hr=23
    local days="${days_sets[$((i%3))]}"

    post "$CFG/api/sla" \
      "{\"name\":\"SLA - Partner Agreement $i\",\"expectedDeliveryStartHour\":$start_hr,\"expectedDeliveryEndHour\":$end_hr,\"expectedDays\":$days,\"minFilesPerWindow\":$((i*2)),\"maxErrorRate\":0.0$((i%9+1)),\"gracePeriodMinutes\":$((15+i*5)),\"breachAction\":\"ALERT\"}" \
      "SLA: Agreement $i" > /dev/null
  done
}

# =============================================================================
# 16. DLP POLICIES (26)
# =============================================================================
create_dlp_policies() {
  log "=== STEP 16: DLP Policies (26) ==="
  local policies=(
    "PCI-Credit-Cards|PCI_CREDIT_CARD|BLOCK"
    "PII-SSN-Detection|PII_SSN|BLOCK"
    "PII-Email-Scanner|PII_EMAIL|FLAG"
    "PII-Phone-Numbers|PII_PHONE|FLAG"
    "PCI-IBAN-Scanner|PCI_IBAN|BLOCK"
    "HIPAA-Patient-Data|CUSTOM|BLOCK"
    "GDPR-EU-Residents|CUSTOM|BLOCK"
    "SOX-Financial-Data|CUSTOM|FLAG"
    "PCI-CVV-Detection|CUSTOM|BLOCK"
    "FERPA-Student-Records|CUSTOM|FLAG"
    "PII-Passport-Numbers|CUSTOM|BLOCK"
    "PII-Drivers-License|CUSTOM|FLAG"
    "Tax-ID-Detection|CUSTOM|BLOCK"
    "Medical-Record-Numbers|CUSTOM|BLOCK"
    "Bank-Account-Detection|CUSTOM|BLOCK"
    "Routing-Number-Scanner|CUSTOM|FLAG"
    "API-Key-Leak-Detection|CUSTOM|BLOCK"
    "Private-Key-Detection|CUSTOM|BLOCK"
    "JWT-Token-Scanner|CUSTOM|FLAG"
    "AWS-Key-Detection|CUSTOM|BLOCK"
    "Connection-String-Scanner|CUSTOM|BLOCK"
    "Password-Pattern-Detection|CUSTOM|FLAG"
    "Biometric-Data-Detection|CUSTOM|BLOCK"
    "Genetic-Data-Scanner|CUSTOM|BLOCK"
    "Trade-Secret-Markers|CUSTOM|FLAG"
    "Export-Control-Detection|CUSTOM|BLOCK"
  )

  for entry in "${policies[@]}"; do
    IFS='|' read -r name ptype action <<< "$entry"
    local regex='\b[0-9]{4}[- ]?[0-9]{4}[- ]?[0-9]{4}[- ]?[0-9]{4}\b'
    post "$SCR/api/v1/dlp/policies" \
      "{\"name\":\"$name\",\"patterns\":[{\"type\":\"$ptype\",\"regex\":\"$regex\",\"label\":\"$name match\"}],\"action\":\"$action\",\"active\":true}" \
      "DLP Policy: $name" > /dev/null
  done
}

# =============================================================================
# 17. NOTIFICATION TEMPLATES (26) + RULES (26)
# =============================================================================
create_notifications() {
  log "=== STEP 17a: Notification Templates (26) ==="
  local events=("transfer.completed" "transfer.failed" "transfer.started" "flow.failed" "flow.completed"
    "security.blocked" "security.anomaly" "screening.quarantined" "screening.passed" "sla.breach"
    "key.expiring" "key.rotated" "license.warning" "license.expired" "partner.onboarded"
    "partner.suspended" "system.error" "system.warning" "audit.alert" "compliance.violation"
    "backup.completed" "backup.failed" "certificate.expiring" "dlp.violation" "as2.mdn.failed"
    "as2.delivery.failed")
  local channels=("EMAIL" "WEBHOOK" "SMS")

  for i in "${!events[@]}"; do
    local evt="${events[$i]}"
    local ch="${channels[$((i%3))]}"
    local name="tmpl-${evt//\./-}"
    post "$NOT/api/notifications/templates" \
      "{\"name\":\"$name\",\"channel\":\"$ch\",\"eventType\":\"$evt\",\"subjectTemplate\":\"[TranzFer] ${evt}: \${summary}\",\"bodyTemplate\":\"Event: ${evt}\\nTime: \${timestamp}\\nDetails: \${details}\\nAccount: \${accountName}\"}" \
      "NotifTemplate: $name" > /dev/null
  done

  log "=== STEP 17b: Notification Rules (26) ==="
  for i in "${!events[@]}"; do
    local evt="${events[$i]}"
    local ch="${channels[$((i%3))]}"
    local name="rule-${evt//\./-}"
    local recipients='["ops-team@tranzfer.io"]'
    [ "$ch" = "WEBHOOK" ] && recipients='["https://hooks.slack.com/services/demo/channel"]'
    [ "$ch" = "SMS" ] && recipients='["+1-555-0100"]'

    post "$NOT/api/notifications/rules" \
      "{\"name\":\"$name\",\"eventTypePattern\":\"${evt}\",\"channel\":\"$ch\",\"recipients\":$recipients}" \
      "NotifRule: $name" > /dev/null
  done
}

# =============================================================================
# 18. WEBHOOK CONNECTORS (26)
# =============================================================================
create_connectors() {
  log "=== STEP 18: Webhook Connectors (26) ==="
  local types=("SLACK" "TEAMS" "PAGERDUTY" "SERVICENOW" "OPSGENIE" "WEBHOOK")
  local severities=("LOW" "MEDIUM" "HIGH" "CRITICAL")

  local names=(
    "Slack - #ops-alerts" "Slack - #security" "Slack - #compliance" "Slack - #transfers"
    "Teams - Operations" "Teams - Security Team" "Teams - Compliance" "Teams - Management"
    "PagerDuty - P1 Alerts" "PagerDuty - Security" "PagerDuty - Infrastructure"
    "ServiceNow - Incidents" "ServiceNow - Changes" "ServiceNow - Security Events"
    "OpsGenie - Critical" "OpsGenie - Warning" "OpsGenie - Info"
    "Webhook - DataDog" "Webhook - Splunk" "Webhook - Elastic"
    "Webhook - Custom ERP" "Webhook - Custom CRM" "Webhook - Audit System"
    "Slack - #dev-alerts" "Teams - Dev Team" "Webhook - Monitoring"
  )

  for i in "${!names[@]}"; do
    local name="${names[$i]}"
    local type="${types[$((i % ${#types[@]}))]}"
    local sev="${severities[$((i % ${#severities[@]}))]}"
    local type_lower=$(echo "$type" | tr '[:upper:]' '[:lower:]')
    local url="https://hooks.example.com/${type_lower}/demo-$((i+1))"

    post "$CFG/api/connectors" \
      "{\"name\":\"$name\",\"type\":\"$type\",\"url\":\"$url\",\"minSeverity\":\"$sev\",\"triggerEvents\":[\"TRANSFER_FAILED\",\"AI_BLOCKED\",\"FLOW_FAIL\"],\"active\":true}" \
      "Connector: $name" > /dev/null
  done
}

# =============================================================================
# 19. PLATFORM SETTINGS (30)
# =============================================================================
create_platform_settings() {
  log "=== STEP 19: Platform Settings (30) ==="
  local envs=("DEV" "TEST" "STAGING" "PROD")
  local services=("GLOBAL" "sftp-service" "ftp-service" "gateway-service" "config-service" "encryption-service")
  local categories=("SECURITY" "PERFORMANCE" "LOGGING" "NETWORK" "STORAGE")

  local settings=(
    "max.file.size.bytes|536870912|LONG|STORAGE|Maximum file size for uploads"
    "session.timeout.minutes|30|INTEGER|SECURITY|Session idle timeout"
    "max.concurrent.transfers|100|INTEGER|PERFORMANCE|Max concurrent file transfers"
    "audit.log.retention.days|90|INTEGER|LOGGING|Audit log retention period"
    "tls.min.version|TLSv1.3|STRING|SECURITY|Minimum TLS version"
    "rate.limit.per.minute|60|INTEGER|NETWORK|API rate limit per minute"
    "encryption.algorithm|AES-256-GCM|STRING|SECURITY|Default encryption algorithm"
    "compression.threshold.bytes|4096|LONG|PERFORMANCE|Compression threshold"
    "retry.max.attempts|3|INTEGER|NETWORK|Max retry attempts"
    "retry.backoff.ms|5000|LONG|NETWORK|Retry backoff milliseconds"
    "cleanup.cron|0 0 3 * * *|STRING|STORAGE|Cleanup cron schedule"
    "notification.batch.size|50|INTEGER|PERFORMANCE|Notification batch size"
    "thread.pool.size|20|INTEGER|PERFORMANCE|Worker thread pool size"
    "connection.pool.max|50|INTEGER|NETWORK|Connection pool max size"
    "heartbeat.interval.seconds|30|INTEGER|NETWORK|Service heartbeat interval"
    "log.level|INFO|STRING|LOGGING|Default log level"
    "log.format|JSON|STRING|LOGGING|Log output format"
    "cors.allowed.origins|*|STRING|SECURITY|CORS allowed origins"
    "jwt.expiry.hours|24|INTEGER|SECURITY|JWT token expiry"
    "password.min.length|8|INTEGER|SECURITY|Minimum password length"
    "mfa.enabled|true|BOOLEAN|SECURITY|Multi-factor auth enabled"
    "quarantine.auto.delete.days|30|INTEGER|STORAGE|Auto-delete quarantined files"
    "bandwidth.limit.mbps|100|INTEGER|NETWORK|Bandwidth limit per transfer"
    "checksum.algorithm|SHA-256|STRING|SECURITY|File checksum algorithm"
    "dedup.enabled|true|BOOLEAN|STORAGE|Deduplication enabled"
    "cdn.enabled|false|BOOLEAN|NETWORK|CDN acceleration enabled"
    "backup.schedule|0 0 2 * * *|STRING|STORAGE|Backup schedule"
    "metrics.export.enabled|true|BOOLEAN|LOGGING|Export metrics to Prometheus"
    "ip.whitelist.enabled|false|BOOLEAN|SECURITY|IP whitelist enforcement"
    "maintenance.mode|false|BOOLEAN|NETWORK|Maintenance mode flag"
  )

  for i in "${!settings[@]}"; do
    IFS='|' read -r key value dtype cat desc <<< "${settings[$i]}"
    local env="${envs[$((i % ${#envs[@]}))]}"
    local svc="${services[$((i % ${#services[@]}))]}"
    post "$CFG/api/platform-settings" \
      "{\"settingKey\":\"$key\",\"settingValue\":\"$value\",\"environment\":\"$env\",\"serviceName\":\"$svc\",\"dataType\":\"$dtype\",\"category\":\"$cat\",\"description\":\"$desc\",\"sensitive\":false}" \
      "Setting: $key ($env/$svc)" > /dev/null
  done
}

# =============================================================================
# 20. ANALYTICS ALERT RULES (26)
# =============================================================================
create_analytics_alerts() {
  log "=== STEP 20: Analytics Alert Rules (26) ==="
  local names=(
    "High Transfer Failure Rate" "SFTP Connection Spike" "FTP Timeout Surge"
    "Disk Usage Critical" "Memory Pressure Warning" "CPU Overload"
    "Queue Depth Alert" "Latency P99 Breach" "Error Rate Threshold"
    "Bandwidth Saturation" "Connection Pool Exhaustion" "Thread Pool Starvation"
    "DLP Violation Spike" "Quarantine Queue Full" "Certificate Expiry Warning"
    "License Usage 90%" "SLA Breach Imminent" "AS2 MDN Failure Rate"
    "Encryption Key Near Expiry" "Screening Service Degraded" "Gateway Route Failure"
    "DMZ Proxy Overload" "Notification Delivery Failure" "Storage Tier Overflow"
    "Anomaly Detection Alert" "Compliance Audit Warning"
  )

  for i in "${!names[@]}"; do
    post "$ANA/api/v1/analytics/alerts" \
      "{\"name\":\"${names[$i]}\",\"condition\":\"threshold > $((50+i*5))\",\"threshold\":$((50+i*5)),\"windowMinutes\":$((5+i%10)),\"severity\":\"$([ $((i%3)) -eq 0 ] && echo CRITICAL || ([ $((i%3)) -eq 1 ] && echo HIGH || echo MEDIUM))\",\"enabled\":true}" \
      "AlertRule: ${names[$i]}" > /dev/null
  done
}

# =============================================================================
# 21. LISTENER SECURITY POLICIES (26)
# =============================================================================
create_security_policies() {
  log "=== STEP 21: Listener Security Policies (26) ==="
  local tiers=("RULES" "AI" "AI_LLM")

  if [ ${#SERVER_IDS[@]} -lt 1 ]; then
    fail "No server IDs available for listener security policies"
    return
  fi

  for i in $(seq 1 26); do
    local tier="${tiers[$((i%3))]}"
    local name="LSP-$(printf '%03d' $i)-${tier}"
    local srv_idx=$(( (i-1) % ${#SERVER_IDS[@]} ))
    local srv_id="${SERVER_IDS[$srv_idx]}"
    post "$CFG/api/listener-security-policies" \
      "{\"name\":\"$name\",\"securityTier\":\"$tier\",\"serverInstance\":{\"id\":\"$srv_id\"},\"ipWhitelist\":[\"10.0.0.0/8\",\"172.16.0.0/12\",\"192.168.0.0/16\"],\"ipBlacklist\":[\"203.0.113.0/24\"],\"geoAllowedCountries\":[\"US\",\"GB\",\"DE\",\"JP\",\"AU\",\"CA\"],\"rateLimitPerMinute\":$((60+i*10)),\"maxConcurrent\":$((20+i*5)),\"maxBytesPerMinute\":$((500*1024*1024)),\"allowedFileExtensions\":[\".csv\",\".xml\",\".json\",\".edi\",\".txt\",\".pdf\",\".zip\",\".pgp\"],\"blockedFileExtensions\":[\".exe\",\".bat\",\".sh\",\".ps1\",\".cmd\"],\"maxFileSizeBytes\":$((256*1024*1024))}" \
      "SecurityPolicy: $name" > /dev/null
  done
}

# =============================================================================
# 22. LEGACY SERVERS (26)
# =============================================================================
create_legacy_servers() {
  log "=== STEP 22: Legacy Servers (26) ==="
  local protocols=("SFTP" "FTP" "FTP" "SFTP")
  local prefixes=("legacy-sftp" "legacy-ftp" "migration-ftp" "migration-sftp")

  for i in $(seq 1 26); do
    local idx=$(( (i-1) % ${#protocols[@]} ))
    local proto="${protocols[$idx]}"
    local prefix="${prefixes[$idx]}"
    post "$CFG/api/legacy-servers" \
      "{\"name\":\"${prefix}-${i}\",\"protocol\":\"$proto\",\"host\":\"${prefix}${i}.legacy.internal\",\"port\":$([ \"$proto\" = \"SFTP\" ] && echo 22 || echo 21),\"healthCheckUser\":\"monitor\"}" \
      "LegacyServer: ${prefix}-${i}" > /dev/null
  done
}

# =============================================================================
# 23. EDI PARTNER PROFILES (26)
# =============================================================================
create_edi_profiles() {
  log "=== STEP 23: EDI Partner Profiles (26) ==="
  local formats=("X12" "EDIFACT" "HL7" "TRADACOMS")

  for i in $(seq 1 26); do
    local fmt="${formats[$((i%4))]}"
    post "$EDI/api/v1/convert/partners" \
      "{\"partnerName\":\"EDI Partner $i\",\"primaryFormat\":\"$fmt\",\"senderId\":\"SENDER$(printf '%03d' $i)\",\"receiverId\":\"RECV$(printf '%03d' $i)\",\"qualifierType\":\"ZZ\",\"active\":true}" \
      "EDI Profile: Partner $i ($fmt)" > /dev/null
  done
}

# =============================================================================
# 24. LICENSES
# =============================================================================
create_licenses() {
  log "=== STEP 24: Licenses ==="

  # Activate trial
  post_noauth "$LIC/api/v1/licenses/trial" \
    "{\"fingerprint\":\"demo-$(hostname)\",\"serviceType\":\"SFTP\",\"hostId\":\"demo-host\"}" \
    "Trial License" > /dev/null

  # Issue enterprise license (admin key)
  post_admin_key "$LIC/api/v1/licenses/issue" \
    "{\"organization\":\"TranzFer Demo Corp\",\"tier\":\"ENTERPRISE\",\"maxAccounts\":10000,\"maxTransfersPerDay\":100000,\"validDays\":365,\"components\":[\"SFTP\",\"FTP\",\"AS2\",\"AS4\",\"EDI\",\"SCREENING\",\"ANALYTICS\",\"AI_ENGINE\"]}" \
    "Enterprise License" "license_admin_secret_key" > /dev/null
}

# =============================================================================
# 25. DMZ PROXY MAPPINGS (26)
# =============================================================================
create_dmz_mappings() {
  log "=== STEP 25: DMZ Proxy Mappings (26) ==="

  for i in $(seq 1 13); do
    dmz_post "$DMZ/api/proxy/mappings" \
      "{\"name\":\"sftp-partner-$i\",\"listenPort\":$((32222+i)),\"backendHost\":\"sftp-service\",\"backendPort\":2222,\"protocol\":\"SFTP\"}" \
      "DMZ Mapping: sftp-partner-$i :$((32222+i))" > /dev/null
  done

  for i in $(seq 1 13); do
    dmz_post "$DMZ/api/proxy/mappings" \
      "{\"name\":\"ftp-partner-$i\",\"listenPort\":$((32300+i)),\"backendHost\":\"ftp-service\",\"backendPort\":21,\"protocol\":\"FTP\"}" \
      "DMZ Mapping: ftp-partner-$i :$((32300+i))" > /dev/null
  done
}

# =============================================================================
# 26. SERVER CONFIGS in config-service (26)
# =============================================================================
create_server_configs() {
  log "=== STEP 26: Server Configs (26) ==="
  local types=("SFTP" "FTP" "FTP_WEB" "GATEWAY" "ENCRYPTION" "FORWARDER" "DMZ" "ANALYTICS" "SCREENING" "KEYSTORE" "STORAGE" "NOTIFICATION" "AI_ENGINE")

  for i in $(seq 1 26); do
    local idx=$(( (i-1) % ${#types[@]} ))
    local stype="${types[$idx]}"
    local stype_lower=$(echo "$stype" | tr '[:upper:]' '[:lower:]')
    local name="config-${stype_lower}-$(printf '%02d' $i)"
    local port=$((8080 + i))
    post "$CFG/api/servers" \
      "{\"name\":\"$name\",\"serviceType\":\"$stype\",\"host\":\"${stype_lower}-service\",\"port\":$port,\"properties\":{\"maxConnections\":\"500\",\"idleTimeout\":\"300\",\"bufferSize\":\"32768\"},\"active\":true}" \
      "ServerConfig: $name ($stype)" > /dev/null
  done
}

# =============================================================================
# 27. PERMISSIONS & ROLE MAPPINGS (seed RBAC data)
# =============================================================================
create_permissions() {
  log "=== STEP 27: Permissions & Role Mappings (via SQL) ==="

  docker exec mft-postgres psql -U postgres -d filetransfer -c "
    -- Seed permissions
    INSERT INTO permissions (id, name, description, resource_type, action, created_at)
    SELECT gen_random_uuid(), name, desc_text, rtype, act, now()
    FROM (VALUES
      ('accounts.read',    'Read transfer accounts',       'ACCOUNT',    'READ'),
      ('accounts.write',   'Create/update accounts',       'ACCOUNT',    'WRITE'),
      ('accounts.delete',  'Delete transfer accounts',     'ACCOUNT',    'DELETE'),
      ('flows.read',       'Read file flows',              'FLOW',       'READ'),
      ('flows.write',      'Create/update file flows',     'FLOW',       'WRITE'),
      ('flows.delete',     'Delete file flows',            'FLOW',       'DELETE'),
      ('partners.read',    'Read partners',                'PARTNER',    'READ'),
      ('partners.write',   'Create/update partners',       'PARTNER',    'WRITE'),
      ('partners.delete',  'Delete partners',              'PARTNER',    'DELETE'),
      ('servers.read',     'Read server instances',        'SERVER',     'READ'),
      ('servers.write',    'Create/update servers',        'SERVER',     'WRITE'),
      ('keys.read',        'Read encryption keys',         'KEY',        'READ'),
      ('keys.write',       'Manage encryption keys',       'KEY',        'WRITE'),
      ('audit.read',       'Read audit logs',              'AUDIT',      'READ'),
      ('settings.read',    'Read platform settings',       'SETTING',    'READ'),
      ('settings.write',   'Modify platform settings',     'SETTING',    'WRITE'),
      ('dlp.read',         'Read DLP policies',            'DLP',        'READ'),
      ('dlp.write',        'Manage DLP policies',          'DLP',        'WRITE'),
      ('analytics.read',   'Read analytics',               'ANALYTICS',  'READ'),
      ('scheduler.read',   'Read scheduled tasks',         'SCHEDULER',  'READ'),
      ('scheduler.write',  'Manage scheduled tasks',       'SCHEDULER',  'WRITE')
    ) AS t(name, desc_text, rtype, act)
    WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permissions.name = t.name);

    -- Map permissions to roles
    INSERT INTO role_permissions (id, role, permission_id, created_at)
    SELECT gen_random_uuid(), r.role_name, p.id, now()
    FROM permissions p
    CROSS JOIN (VALUES ('ADMIN'), ('OPERATOR'), ('VIEWER')) AS r(role_name)
    WHERE NOT EXISTS (
      SELECT 1 FROM role_permissions rp WHERE rp.role = r.role_name AND rp.permission_id = p.id
    )
    AND (
      r.role_name = 'ADMIN'
      OR (r.role_name = 'OPERATOR' AND p.action IN ('READ', 'WRITE'))
      OR (r.role_name = 'VIEWER' AND p.action = 'READ')
    );
  " > /dev/null 2>&1

  if [ $? -eq 0 ]; then
    ok "Permissions & role mappings seeded (21 permissions, 3 roles)"
  else
    fail "Permission seeding failed"
  fi
}

# =============================================================================
# MAIN
# =============================================================================
main() {
  echo ""
  echo "╔═══════════════════════════════════════════════════════════╗"
  echo "║  TranzFer MFT — Demo Data Onboarding                    ║"
  echo "║  Creating production-like demo environment               ║"
  echo "╚═══════════════════════════════════════════════════════════╝"
  echo ""

  # Wait for core services
  wait_for_service "$API/actuator/health" "onboarding-api" 90
  wait_for_service "$CFG/actuator/health" "config-service" 60

  authenticate

  # Run all onboarding steps
  create_tenants
  create_server_instances
  fetch_server_ids
  create_folder_templates
  create_security_profiles
  create_partners
  create_accounts
  fetch_account_ids

  # These depend on external services being up
  if wait_for_service "$KEY/actuator/health" "keystore-manager" 30; then
    create_keys
  fi

  create_encryption_keys
  create_external_destinations
  create_delivery_endpoints
  create_as2_partnerships
  create_file_flows
  create_folder_mappings
  create_scheduled_tasks
  create_sla_agreements

  if wait_for_service "$SCR/actuator/health" "screening-service" 30; then
    create_dlp_policies
  fi

  if wait_for_service "$NOT/actuator/health" "notification-service" 30; then
    create_notifications
  fi

  create_connectors
  create_platform_settings

  if wait_for_service "$ANA/actuator/health" "analytics-service" 30; then
    create_analytics_alerts
  fi

  create_security_policies
  create_legacy_servers

  if wait_for_service "$EDI/actuator/health" "edi-converter" 30; then
    create_edi_profiles
  fi

  if wait_for_service "$LIC/actuator/health" "license-service" 30; then
    create_licenses
  fi

  if wait_for_service "$DMZ/actuator/health" "dmz-proxy" 30; then
    create_dmz_mappings
  fi

  create_server_configs
  create_permissions

  echo ""
  echo "╔═══════════════════════════════════════════════════════════╗"
  echo "║  ONBOARDING COMPLETE                                     ║"
  echo "╠═══════════════════════════════════════════════════════════╣"
  echo "║  Created:  $CREATED                                      "
  echo "║  Skipped:  $SKIPPED (already existed)                    "
  echo "║  Failed:   $FAILED                                       "
  echo "║  Total:    $((CREATED + SKIPPED + FAILED))               "
  echo "╚═══════════════════════════════════════════════════════════╝"
  echo ""
  echo "Admin UI: https://localhost"
  echo "Login:    $ADMIN_EMAIL / $ADMIN_PASS"
  echo ""
}

main "$@"

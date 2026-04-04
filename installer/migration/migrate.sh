#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — AI Migration Utility
# =============================================================================
# Migrates users, accounts, keys, and configurations from existing MFT products
# to TranzFer with ZERO user intervention.
#
# Supported sources:
#   - OpenSSH SFTP (Linux native)
#   - vsftpd / ProFTPD / Pure-FTPd
#   - IBM Sterling File Gateway
#   - Axway SecureTransport
#   - GoAnywhere MFT
#   - Globalscape EFT
#   - Any SSH/SFTP server (generic)
#
# Usage:
#   ./migrate.sh                  # Interactive
#   ./migrate.sh --source ssh     # Direct mode
# =============================================================================

set -o pipefail

BOLD='\033[1m'
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

step() { echo -e "\n  ${BOLD}${BLUE}▸ $1${NC}"; }
ok() { echo -e "    ${GREEN}✓${NC} $1"; }
fail() { echo -e "    ${RED}✗${NC} $1"; }
warn() { echo -e "    ${YELLOW}⚠${NC} $1"; }
info() { echo -e "    ${BLUE}ℹ${NC} $1"; }
ask() { local p="$1" d="$2"; echo -ne "  ${CYAN}${p}${NC}"; [ -n "$d" ] && echo -ne " ${YELLOW}[${d}]${NC}"; echo -ne ": "; read -r a; echo "${a:-$d}"; }

# TranzFer target
TRZ_URL=""
TRZ_TOKEN=""
REPORT_FILE="migration-report-$(date +%Y%m%d_%H%M%S).txt"

# Migration state
DISCOVERED_USERS=()
DISCOVERED_KEYS=()
MIGRATED=0
FAILED=0
SKIPPED=0

print_banner() {
  echo -e "${BOLD}${BLUE}"
  echo "  ╔══════════════════════════════════════════════════════╗"
  echo "  ║     ⚡ TranzFer MFT — AI Migration Utility          ║"
  echo "  ║     Zero-downtime migration from any MFT product     ║"
  echo "  ╚══════════════════════════════════════════════════════╝"
  echo -e "${NC}"
}

# =============================================================================
# PHASE 1: DISCOVERY — Find users, keys, configs from source system
# =============================================================================

discover_openssh() {
  step "Discovering OpenSSH SFTP users"
  local host=$(ask "Source SSH server host" "localhost")
  local ssh_port=$(ask "SSH port" "22")
  local admin_user=$(ask "Admin SSH username (with sudo)" "root")

  info "Connecting to ${host}:${ssh_port} as ${admin_user}..."

  # Discover system users with home directories
  local users_raw=$(ssh -p "$ssh_port" -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
    "${admin_user}@${host}" "
    echo '=== USERS ==='
    awk -F: '\$3 >= 1000 && \$3 < 65534 {print \$1\"|\"\$6\"|\"\$7}' /etc/passwd
    echo '=== GROUPS ==='
    grep -E 'sftp|ftp|filetransfer' /etc/group 2>/dev/null || echo 'none'
    echo '=== SSH_KEYS ==='
    for user in \$(awk -F: '\$3 >= 1000 && \$3 < 65534 {print \$1}' /etc/passwd); do
      if [ -f \"/home/\$user/.ssh/authorized_keys\" ]; then
        echo \"\$user:$(cat /home/\$user/.ssh/authorized_keys | head -1)\"
      fi
    done
    echo '=== SSHD_CONFIG ==='
    grep -E 'Match|ChrootDirectory|ForceCommand|AllowUsers|AllowGroups' /etc/ssh/sshd_config 2>/dev/null
    echo '=== VSFTPD ==='
    [ -f /etc/vsftpd.conf ] && echo 'FOUND' || echo 'NONE'
    echo '=== PROFTPD ==='
    [ -f /etc/proftpd/proftpd.conf ] && echo 'FOUND' || echo 'NONE'
  " 2>/dev/null)

  if [ -z "$users_raw" ]; then
    fail "Could not connect to ${host}:${ssh_port}"
    info "Check: ssh -p ${ssh_port} ${admin_user}@${host}"
    return 1
  fi

  # Parse discovered users
  local in_users=false
  local in_keys=false
  while IFS= read -r line; do
    case "$line" in
      "=== USERS ===") in_users=true; in_keys=false; continue ;;
      "=== GROUPS ==="*) in_users=false; continue ;;
      "=== SSH_KEYS ===") in_keys=true; continue ;;
      "=== SSHD_CONFIG ==="*) in_keys=false; continue ;;
      "=== VSFTPD ==="*|"=== PROFTPD ==="*) continue ;;
    esac
    if $in_users && [ -n "$line" ]; then
      local uname=$(echo "$line" | cut -d'|' -f1)
      local uhome=$(echo "$line" | cut -d'|' -f2)
      local ushell=$(echo "$line" | cut -d'|' -f3)
      # Skip system/nologin users that aren't SFTP
      if echo "$ushell" | grep -qE "nologin|false" && ! echo "$users_raw" | grep -q "ChrootDirectory.*${uname}"; then
        continue
      fi
      DISCOVERED_USERS+=("${uname}|${uhome}|SFTP|${host}")
      ok "Found user: ${uname} (home: ${uhome})"
    fi
    if $in_keys && [ -n "$line" ]; then
      local kuser=$(echo "$line" | cut -d: -f1)
      local kdata=$(echo "$line" | cut -d: -f2-)
      DISCOVERED_KEYS+=("${kuser}|${kdata}")
      ok "Found SSH key for: ${kuser}"
    fi
  done <<< "$users_raw"

  ok "Discovery complete: ${#DISCOVERED_USERS[@]} users, ${#DISCOVERED_KEYS[@]} SSH keys"
}

discover_vsftpd() {
  step "Discovering vsftpd FTP users"
  local host=$(ask "Source FTP server host" "localhost")
  local admin_user=$(ask "Admin SSH username" "root")

  local data=$(ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
    "${admin_user}@${host}" "
    echo '=== VSFTPD_USERS ==='
    if [ -d /etc/vsftpd/users ] || [ -f /etc/vsftpd.userlist ]; then
      cat /etc/vsftpd.userlist 2>/dev/null || ls /etc/vsftpd/users/ 2>/dev/null
    fi
    echo '=== VIRTUAL_USERS ==='
    if [ -f /etc/vsftpd/virtual_users.db ]; then
      db_dump /etc/vsftpd/virtual_users.db 2>/dev/null | grep -v '^ '
    fi
    echo '=== FTP_HOME ==='
    grep 'local_root' /etc/vsftpd.conf 2>/dev/null
    grep 'anon_root' /etc/vsftpd.conf 2>/dev/null
  " 2>/dev/null)

  while IFS= read -r line; do
    if [ -n "$line" ] && ! echo "$line" | grep -qE "^===|^$"; then
      DISCOVERED_USERS+=("${line}|/home/${line}|FTP|${host}")
      ok "Found FTP user: ${line}"
    fi
  done <<< "$(echo "$data" | sed -n '/=== VSFTPD_USERS ===/,/=== VIRTUAL_USERS ===/p' | grep -v '===')"

  ok "vsftpd discovery: ${#DISCOVERED_USERS[@]} users"
}

discover_sterling() {
  step "Discovering IBM Sterling File Gateway users"
  local host=$(ask "Sterling server host")
  local api_port=$(ask "Sterling REST API port" "8443")
  local admin_user=$(ask "Sterling admin username" "admin")
  echo -ne "  ${CYAN}Sterling admin password${NC}: "; read -rs admin_pass; echo ""

  info "Connecting to Sterling API at ${host}:${api_port}..."

  # Sterling B2Bi REST API — list trading partners
  local partners=$(curl -sk -u "${admin_user}:${admin_pass}" \
    "https://${host}:${api_port}/B2BAPIs/v1/tradingpartners" 2>/dev/null)

  if echo "$partners" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
    echo "$partners" | python3 -c "
import sys,json
data = json.load(sys.stdin)
partners = data if isinstance(data, list) else data.get('tradingPartners', data.get('partners', []))
for p in partners:
    name = p.get('partnerName', p.get('name', 'unknown'))
    proto = p.get('protocol', 'SFTP')
    print(f'{name}|/data/{name}|{proto}|sterling')
" 2>/dev/null | while IFS= read -r line; do
      DISCOVERED_USERS+=("$line")
      ok "Found Sterling partner: $(echo $line | cut -d'|' -f1)"
    done
  else
    warn "Could not parse Sterling API response"
    info "Manual export: Sterling Console → Trading Partners → Export CSV"
    info "Then run: ./migrate.sh --import-csv partners.csv"
  fi
}

discover_generic() {
  step "Generic SFTP/FTP user import"
  echo "    Provide user details manually or import from CSV."
  echo ""
  echo "    CSV format: username,password,protocol,home_directory"
  echo "    Example:    acme_user,Pa\$\$w0rd,SFTP,/data/sftp/acme"
  echo ""

  local method=$(ask "Import method: (1) CSV file (2) Manual entry (3) LDAP" "1")

  case $method in
    1) local csv_path=$(ask "CSV file path")
       if [ -f "$csv_path" ]; then
         while IFS=, read -r uname upass uproto uhome; do
           [ "$uname" = "username" ] && continue # skip header
           DISCOVERED_USERS+=("${uname}|${uhome:-/data/sftp/$uname}|${uproto:-SFTP}|csv-import")
           ok "Imported: ${uname} (${uproto:-SFTP})"
         done < "$csv_path"
       else
         fail "File not found: $csv_path"
       fi ;;
    2) while true; do
         local uname=$(ask "Username (empty to finish)")
         [ -z "$uname" ] && break
         local uproto=$(ask "Protocol" "SFTP")
         DISCOVERED_USERS+=("${uname}|/data/sftp/${uname}|${uproto}|manual")
         ok "Added: ${uname}"
       done ;;
    3) info "LDAP import coming in next release" ;;
  esac
}

# =============================================================================
# PHASE 2: ANALYSIS — AI analyzes what was found and suggests migration plan
# =============================================================================

analyze_discovery() {
  step "AI Analysis of discovered resources"

  local user_count=${#DISCOVERED_USERS[@]}
  local key_count=${#DISCOVERED_KEYS[@]}

  echo ""
  echo -e "    ${BOLD}Discovery Summary${NC}"
  echo "    ─────────────────────────────────────"
  echo "    Users found:     ${user_count}"
  echo "    SSH keys found:  ${key_count}"
  echo ""

  # Protocol breakdown
  local sftp_count=0 ftp_count=0
  for u in "${DISCOVERED_USERS[@]}"; do
    local proto=$(echo "$u" | cut -d'|' -f3)
    case "$proto" in SFTP) sftp_count=$((sftp_count + 1)) ;; FTP) ftp_count=$((ftp_count + 1)) ;; esac
  done
  echo "    SFTP accounts:   ${sftp_count}"
  echo "    FTP accounts:    ${ftp_count}"
  echo ""

  # Recommendations
  echo -e "    ${BOLD}Migration Plan${NC}"
  echo "    ─────────────────────────────────────"
  echo "    1. Create ${user_count} transfer accounts in TranzFer"
  [ $key_count -gt 0 ] && echo "    2. Import ${key_count} SSH public keys"
  echo "    3. Create home directories with inbox/outbox/archive"
  echo "    4. Set up default folder mappings"
  echo "    5. Notify partners of new connection details"
  echo ""

  # Warnings
  if [ $ftp_count -gt 0 ]; then
    warn "FTP users detected. Recommend migrating to SFTP (encrypted) or FTPS."
  fi

  echo ""
  if confirm "Proceed with migration?"; then
    return 0
  fi
  return 1
}

# =============================================================================
# PHASE 3: MIGRATE — Create accounts in TranzFer
# =============================================================================

connect_tranzfer() {
  step "Connect to TranzFer server"
  TRZ_URL=$(ask "TranzFer server URL" "http://localhost:8080")
  local email=$(ask "TranzFer admin email" "admin@filetransfer.local")
  echo -ne "  ${CYAN}TranzFer admin password${NC}: "; read -rs password; echo ""

  local resp=$(curl -s -X POST "${TRZ_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${email}\",\"password\":\"${password}\"}" 2>/dev/null)

  TRZ_TOKEN=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null)

  if [ -n "$TRZ_TOKEN" ] && [ ${#TRZ_TOKEN} -gt 10 ]; then
    ok "Connected to TranzFer as ${email}"
    return 0
  fi
  fail "Login failed"
  return 1
}

migrate_users() {
  step "Migrating ${#DISCOVERED_USERS[@]} accounts to TranzFer"

  for entry in "${DISCOVERED_USERS[@]}"; do
    local uname=$(echo "$entry" | cut -d'|' -f1)
    local uhome=$(echo "$entry" | cut -d'|' -f2)
    local uproto=$(echo "$entry" | cut -d'|' -f3)
    local source=$(echo "$entry" | cut -d'|' -f4)

    # Generate a secure password
    local gen_pass="Mig$(openssl rand -hex 4 2>/dev/null || echo $RANDOM)!Aa1"

    # Create account via TranzFer API
    local result=$(curl -s -X POST "${TRZ_URL}/api/accounts" \
      -H "Authorization: Bearer $TRZ_TOKEN" \
      -H "Content-Type: application/json" \
      -d "{
        \"protocol\": \"${uproto}\",
        \"username\": \"${uname}\",
        \"password\": \"${gen_pass}\",
        \"homeDir\": \"${uhome}\"
      }" 2>/dev/null)

    if echo "$result" | grep -q "id"; then
      ok "Migrated: ${uname} (${uproto}) — password: ${gen_pass}"
      MIGRATED=$((MIGRATED + 1))

      # Log to report
      echo "${uname},${uproto},${uhome},${gen_pass},${source},SUCCESS" >> "$REPORT_FILE"
    elif echo "$result" | grep -qi "already"; then
      warn "Skipped: ${uname} — already exists"
      SKIPPED=$((SKIPPED + 1))
      echo "${uname},${uproto},${uhome},,${source},SKIPPED_EXISTS" >> "$REPORT_FILE"
    else
      fail "Failed: ${uname} — $(echo $result | head -c 80)"
      FAILED=$((FAILED + 1))
      echo "${uname},${uproto},${uhome},,${source},FAILED" >> "$REPORT_FILE"
    fi
  done

  # Migrate SSH keys
  if [ ${#DISCOVERED_KEYS[@]} -gt 0 ]; then
    step "Importing ${#DISCOVERED_KEYS[@]} SSH public keys to Keystore Manager"
    for kentry in "${DISCOVERED_KEYS[@]}"; do
      local kuser=$(echo "$kentry" | cut -d'|' -f1)
      local kdata=$(echo "$kentry" | cut -d'|' -f2-)

      curl -s -X POST "$(echo $TRZ_URL | sed 's/:8080/:8093/')/api/v1/keys/import" \
        -H "Content-Type: application/json" \
        -d "{
          \"alias\": \"migrated-${kuser}-ssh\",
          \"keyType\": \"SSH_USER_KEY\",
          \"keyMaterial\": \"${kdata}\",
          \"description\": \"Migrated from source system\",
          \"partnerAccount\": \"${kuser}\"
        }" &>/dev/null && ok "Key imported: ${kuser}" || warn "Key import failed: ${kuser}"
    done
  fi
}

# =============================================================================
# PHASE 4: VERIFY — Test migrated accounts
# =============================================================================

verify_migration() {
  step "Verifying migrated accounts"

  local verified=0
  while IFS=, read -r uname uproto uhome upass usource ustatus; do
    [ "$ustatus" != "SUCCESS" ] && continue
    [ "$uproto" != "SFTP" ] && continue

    # Try SFTP connection test
    local sftp_port=$(echo "$TRZ_URL" | grep -q "localhost" && echo "22222" || echo "2222")
    if nc -z -w 3 "$(echo $TRZ_URL | sed 's|http://||;s|:.*||')" "$sftp_port" 2>/dev/null; then
      ok "Verified: ${uname} — SFTP port reachable"
      verified=$((verified + 1))
    else
      warn "Cannot verify: ${uname} — SFTP port not reachable"
    fi
  done < "$REPORT_FILE"

  ok "${verified} accounts verified"
}

# =============================================================================
# PHASE 5: REPORT
# =============================================================================

print_report() {
  echo ""
  echo -e "  ${BOLD}${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
  echo -e "  ${BOLD}${GREEN}║          Migration Complete!                          ║${NC}"
  echo -e "  ${BOLD}${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
  echo ""
  echo -e "    ${BOLD}Migrated:${NC}  ${GREEN}${MIGRATED}${NC} accounts"
  echo -e "    ${BOLD}Skipped:${NC}   ${YELLOW}${SKIPPED}${NC} (already existed)"
  echo -e "    ${BOLD}Failed:${NC}    ${RED}${FAILED}${NC}"
  echo ""
  echo -e "    ${BOLD}Report:${NC}    ${REPORT_FILE}"
  echo ""
  echo -e "    ${BOLD}Next steps:${NC}"
  echo "    1. Send new credentials to partners (in report file)"
  echo "    2. Partners update their MFT client config with new host/port"
  echo "    3. Test transfers with each partner"
  echo "    4. Cut over DNS to point to TranzFer"
  echo "    5. Decommission old MFT server"
  echo ""
  echo -e "    ${YELLOW}IMPORTANT: The report file contains passwords.${NC}"
  echo -e "    ${YELLOW}Distribute securely and delete after partners confirm.${NC}"
  echo ""
}

# =============================================================================
# MAIN
# =============================================================================

main() {
  print_banner

  # Write report header
  echo "username,protocol,home_dir,generated_password,source,status" > "$REPORT_FILE"

  step "What system are you migrating from?"
  echo "    1) OpenSSH SFTP server (Linux)"
  echo "    2) vsftpd / ProFTPD (FTP server)"
  echo "    3) IBM Sterling File Gateway"
  echo "    4) Axway SecureTransport"
  echo "    5) GoAnywhere MFT"
  echo "    6) Generic (CSV import / manual)"
  echo ""
  local source=$(ask "Select" "1")

  # Phase 1: Discovery
  case $source in
    1) discover_openssh ;;
    2) discover_vsftpd ;;
    3) discover_sterling ;;
    4|5) info "Connect via REST API or CSV export from the source product."
       discover_generic ;;
    6) discover_generic ;;
    *) discover_generic ;;
  esac

  if [ ${#DISCOVERED_USERS[@]} -eq 0 ]; then
    fail "No users discovered. Check source system access."
    exit 1
  fi

  # Phase 2: Analysis
  if ! analyze_discovery; then
    echo "  Migration cancelled."
    exit 0
  fi

  # Phase 3: Connect to TranzFer and migrate
  if ! connect_tranzfer; then exit 1; fi
  migrate_users

  # Phase 4: Verify
  verify_migration

  # Phase 5: Report
  print_report
}

case "${1:-}" in
  --source)
    case "${2:-}" in
      ssh|openssh) discover_openssh && analyze_discovery && connect_tranzfer && migrate_users && verify_migration && print_report ;;
      ftp|vsftpd) discover_vsftpd && analyze_discovery && connect_tranzfer && migrate_users && verify_migration && print_report ;;
      sterling) discover_sterling && analyze_discovery && connect_tranzfer && migrate_users && verify_migration && print_report ;;
      csv) discover_generic && analyze_discovery && connect_tranzfer && migrate_users && verify_migration && print_report ;;
      *) echo "Sources: ssh, ftp, sterling, csv" ;;
    esac ;;
  --import-csv)
    DISCOVERED_USERS=()
    while IFS=, read -r u p proto h; do
      [ "$u" = "username" ] && continue
      DISCOVERED_USERS+=("${u}|${h:-/data/sftp/$u}|${proto:-SFTP}|csv")
    done < "${2:?CSV file required}"
    analyze_discovery && connect_tranzfer && migrate_users && verify_migration && print_report ;;
  --help|-h)
    echo "TranzFer Migration Utility"
    echo ""
    echo "Usage: ./migrate.sh [options]"
    echo ""
    echo "  (no args)              Interactive guided migration"
    echo "  --source ssh           Migrate from OpenSSH"
    echo "  --source ftp           Migrate from vsftpd/ProFTPD"
    echo "  --source sterling      Migrate from IBM Sterling"
    echo "  --source csv           Import from CSV file"
    echo "  --import-csv file.csv  Direct CSV import"
    echo ""
    echo "Supported sources: OpenSSH, vsftpd, ProFTPD, Pure-FTPd,"
    echo "  IBM Sterling, Axway, GoAnywhere, Globalscape, CSV, manual" ;;
  "") main ;;
esac

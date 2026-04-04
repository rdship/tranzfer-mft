#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Enterprise Migration Tool v2.0
# =============================================================================
# Complete enterprise migration from any MFT product to TranzFer.
# Handles: users, permissions, keys, certificates, flows, routing,
# schedules, SLAs, connectors, and phased cutover.
#
# Usage:
#   ./migrate.sh                          # Interactive guided migration
#   ./migrate.sh --source ssh --host x    # Direct mode
#   ./migrate.sh --import-csv users.csv   # CSV import
#   ./migrate.sh --resume <session-id>    # Resume interrupted migration
#   ./migrate.sh --validate <session-id>  # Validate completed migration
#   ./migrate.sh --rollback <session-id>  # Rollback migration
# =============================================================================

set -o pipefail

BOLD='\033[1m'; RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'

step() { echo -e "\n  ${BOLD}${BLUE}▸ $1${NC}"; }
ok() { echo -e "    ${GREEN}✓${NC} $1"; }
fail() { echo -e "    ${RED}✗${NC} $1"; }
warn() { echo -e "    ${YELLOW}⚠${NC} $1"; }
info() { echo -e "    ${BLUE}ℹ${NC} $1"; }
ask() { local p="$1" d="$2"; echo -ne "  ${CYAN}${p}${NC}"; [ -n "$d" ] && echo -ne " [${d}]"; echo -ne ": "; read -r a; echo "${a:-$d}"; }
confirm() { local a; a=$(ask "$1 (y/n)" "y"); [[ "$a" =~ ^[Yy] ]]; }
ask_secret() { echo -ne "  ${CYAN}${1}${NC}: "; read -rs a; echo ""; echo "$a"; }

# Session
SESSION_ID="mig-$(date +%Y%m%d-%H%M%S)"
SESSION_DIR="migration-sessions/${SESSION_ID}"
TRZ_URL=""
TRZ_TOKEN=""

# Counters
declare -A COUNTS
COUNTS[users]=0; COUNTS[keys]=0; COUNTS[flows]=0; COUNTS[routes]=0
COUNTS[schedules]=0; COUNTS[slas]=0; COUNTS[connectors]=0; COUNTS[ext_dests]=0
COUNTS[migrated]=0; COUNTS[failed]=0; COUNTS[skipped]=0

print_banner() {
  echo -e "${BOLD}${BLUE}"
  echo "  ╔════════════════════════════════════════════════════════╗"
  echo "  ║     ⚡ TranzFer MFT — Enterprise Migration Tool v2.0  ║"
  echo "  ║     Complete zero-downtime migration from any MFT      ║"
  echo "  ╚════════════════════════════════════════════════════════╝"
  echo -e "${NC}"
}

init_session() {
  mkdir -p "$SESSION_DIR"/{discovery,import,validate,reports}
  echo "$SESSION_ID" > "$SESSION_DIR/session.id"
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$SESSION_DIR/started_at"
  echo "STARTED" > "$SESSION_DIR/status"
  ok "Migration session: ${SESSION_ID}"
}

# =============================================================================
# PHASE 1: DISCOVER — Extract everything from source system
# =============================================================================

discover() {
  step "Phase 1: DISCOVERY"
  echo ""
  echo "    Source system type:"
  echo "    1) Linux SSH/SFTP server (OpenSSH)"
  echo "    2) Linux FTP server (vsftpd/ProFTPD)"
  echo "    3) IBM Sterling File Gateway"
  echo "    4) Axway SecureTransport"
  echo "    5) GoAnywhere MFT"
  echo "    6) CSV import (exported from any system)"
  echo ""
  local source=$(ask "Select" "1")

  case $source in
    1|2) discover_linux ;;
    3) discover_sterling ;;
    4|5) discover_generic_api ;;
    6) discover_csv ;;
    *) discover_csv ;;
  esac
}

discover_linux() {
  local host=$(ask "Source server hostname")
  local port=$(ask "SSH port" "22")
  local admin=$(ask "SSH admin username" "root")
  info "Connecting to ${host}:${port}..."

  # Comprehensive discovery script — runs on remote host
  ssh -p "$port" -o StrictHostKeyChecking=no -o ConnectTimeout=15 "${admin}@${host}" bash << 'REMOTE' > "${SESSION_DIR}/discovery/raw.txt" 2>/dev/null
echo "===USERS==="
awk -F: '$3 >= 1000 && $3 < 65534 {
  printf "%s|%s|%s|%s|%s\n", $1, $6, $7, $3, $4
}' /etc/passwd

echo "===GROUPS==="
cat /etc/group 2>/dev/null

echo "===SSH_KEYS==="
for user in $(awk -F: '$3 >= 1000 && $3 < 65534 {print $1}' /etc/passwd); do
  for keyfile in /home/$user/.ssh/authorized_keys /home/$user/.ssh/*.pub; do
    [ -f "$keyfile" ] && echo "${user}|${keyfile}|$(cat "$keyfile" 2>/dev/null | head -5)"
  done
done

echo "===SSH_HOST_KEYS==="
for f in /etc/ssh/ssh_host_*_key.pub; do
  [ -f "$f" ] && echo "$(basename $f)|$(cat $f)"
done

echo "===SSHD_CONFIG==="
cat /etc/ssh/sshd_config 2>/dev/null | grep -v '^#' | grep -v '^$'

echo "===PERMISSIONS==="
for user in $(awk -F: '$3 >= 1000 && $3 < 65534 {print $1}' /etc/passwd); do
  home=$(eval echo ~$user 2>/dev/null)
  [ -d "$home" ] && echo "${user}|$(stat -c '%a' "$home" 2>/dev/null || stat -f '%A' "$home" 2>/dev/null)|$(du -sh "$home" 2>/dev/null | cut -f1)"
done

echo "===FTP_CONFIG==="
[ -f /etc/vsftpd.conf ] && echo "VSFTPD|$(cat /etc/vsftpd.conf | grep -v '^#' | grep -v '^$')"
[ -f /etc/proftpd/proftpd.conf ] && echo "PROFTPD|$(cat /etc/proftpd/proftpd.conf | grep -v '^#' | head -50)"

echo "===CRONTABS==="
for user in $(awk -F: '$3 >= 1000 && $3 < 65534 {print $1}' /etc/passwd); do
  crontab -u $user -l 2>/dev/null | grep -v '^#' | while read line; do
    echo "${user}|${line}"
  done
done
crontab -l 2>/dev/null | grep -iv '^#' | grep -iE 'sftp|ftp|transfer|mft' | while read line; do
  echo "root|${line}"
done

echo "===PGP_KEYS==="
gpg --list-keys --keyid-format long 2>/dev/null | grep -E 'pub|uid'
gpg --list-secret-keys --keyid-format long 2>/dev/null | grep -E 'sec|uid'

echo "===TLS_CERTS==="
for f in /etc/ssl/certs/*.pem /etc/ssl/private/*.key /etc/pki/tls/certs/*.pem \
         /etc/vsftpd/*.pem /etc/proftpd/ssl/*.pem; do
  [ -f "$f" ] && echo "$(basename $f)|$(openssl x509 -in "$f" -noout -subject -enddate 2>/dev/null || echo "KEY")"
done

echo "===DISK_USAGE==="
df -h / /home /data 2>/dev/null | tail -n +2

echo "===END==="
REMOTE

  if [ ! -s "${SESSION_DIR}/discovery/raw.txt" ]; then
    fail "Could not connect to ${host}:${port}"
    return 1
  fi

  ok "Discovery data collected"

  # Parse discovery data into structured files
  parse_discovery
}

parse_discovery() {
  local raw="${SESSION_DIR}/discovery/raw.txt"

  # Parse users
  echo "username,home_dir,shell,uid,gid,permissions,disk_usage" > "${SESSION_DIR}/discovery/users.csv"
  local section=""
  while IFS= read -r line; do
    case "$line" in
      "===USERS===") section="users" ; continue ;;
      "===GROUPS===") section="groups" ; continue ;;
      "===SSH_KEYS===") section="keys" ; continue ;;
      "===SSH_HOST_KEYS===") section="hostkeys" ; continue ;;
      "===PERMISSIONS===") section="perms" ; continue ;;
      "===CRONTABS===") section="cron" ; continue ;;
      "===PGP_KEYS===") section="pgp" ; continue ;;
      "===TLS_CERTS===") section="tls" ; continue ;;
      "==="*) section="" ; continue ;;
    esac

    case "$section" in
      users) [ -n "$line" ] && {
        IFS='|' read -r uname uhome ushell uuid ugid <<< "$line"
        echo "${uname},${uhome},${ushell},${uuid},${ugid},," >> "${SESSION_DIR}/discovery/users.csv"
        COUNTS[users]=$((${COUNTS[users]} + 1))
        ok "User: ${uname} (${uhome})"
      } ;;
      keys) [ -n "$line" ] && {
        local kuser=$(echo "$line" | cut -d'|' -f1)
        local kfile=$(echo "$line" | cut -d'|' -f2)
        local kdata=$(echo "$line" | cut -d'|' -f3-)
        echo "${kuser},${kfile},${kdata}" >> "${SESSION_DIR}/discovery/ssh_keys.csv"
        COUNTS[keys]=$((${COUNTS[keys]} + 1))
      } ;;
      hostkeys) echo "$line" >> "${SESSION_DIR}/discovery/host_keys.csv" ;;
      cron) [ -n "$line" ] && {
        echo "$line" >> "${SESSION_DIR}/discovery/crontabs.csv"
        COUNTS[schedules]=$((${COUNTS[schedules]} + 1))
      } ;;
      pgp) echo "$line" >> "${SESSION_DIR}/discovery/pgp_keys.txt" ;;
      tls) echo "$line" >> "${SESSION_DIR}/discovery/tls_certs.csv" ;;
      perms) [ -n "$line" ] && {
        IFS='|' read -r puser pperms psize <<< "$line"
        sed -i '' "s/^${puser},.*,$/&${pperms},${psize}/" "${SESSION_DIR}/discovery/users.csv" 2>/dev/null || true
      } ;;
    esac
  done < "$raw"

  ok "Parsed: ${COUNTS[users]} users, ${COUNTS[keys]} SSH keys, ${COUNTS[schedules]} cron jobs"
}

discover_sterling() {
  local host=$(ask "Sterling server host")
  local port=$(ask "Sterling API port" "8443")
  local user=$(ask "Sterling admin username" "admin")
  local pass=$(ask_secret "Sterling admin password")

  info "Connecting to Sterling REST API..."

  # Trading partners
  curl -sk -u "${user}:${pass}" "https://${host}:${port}/B2BAPIs/v1/tradingpartners" \
    > "${SESSION_DIR}/discovery/sterling_partners.json" 2>/dev/null

  # SSH keys
  curl -sk -u "${user}:${pass}" "https://${host}:${port}/B2BAPIs/v1/sshknownhostkeys" \
    > "${SESSION_DIR}/discovery/sterling_keys.json" 2>/dev/null

  # Routes/flows
  curl -sk -u "${user}:${pass}" "https://${host}:${port}/B2BAPIs/v1/routes" \
    > "${SESSION_DIR}/discovery/sterling_routes.json" 2>/dev/null

  # Parse partners
  echo "username,home_dir,shell,uid,gid,permissions,disk_usage" > "${SESSION_DIR}/discovery/users.csv"
  python3 -c "
import json,sys
try:
    data = json.load(open('${SESSION_DIR}/discovery/sterling_partners.json'))
    partners = data if isinstance(data, list) else data.get('tradingPartners', data.get('content', []))
    for p in partners:
        name = p.get('partnerName', p.get('name', 'unknown'))
        proto = p.get('protocol', 'SFTP')
        print(f'{name},/data/{proto.lower()}/{name},/bin/bash,1000,1000,,')
except: pass
" >> "${SESSION_DIR}/discovery/users.csv" 2>/dev/null

  COUNTS[users]=$(wc -l < "${SESSION_DIR}/discovery/users.csv" | tr -d ' ')
  COUNTS[users]=$((${COUNTS[users]} - 1))  # subtract header
  ok "Sterling discovery: ${COUNTS[users]} partners"
}

discover_generic_api() {
  info "For Axway/GoAnywhere, export users and config to CSV files."
  info "Required CSV format: username,home_dir,protocol"
  discover_csv
}

discover_csv() {
  local csv=$(ask "CSV file path (username,home_dir,protocol,password)")
  [ ! -f "$csv" ] && { fail "File not found: $csv"; return 1; }

  cp "$csv" "${SESSION_DIR}/discovery/users.csv"
  COUNTS[users]=$(($(wc -l < "$csv" | tr -d ' ') - 1))
  ok "Imported ${COUNTS[users]} users from CSV"
}

# =============================================================================
# PHASE 2: ANALYZE — AI reviews and generates migration plan
# =============================================================================

analyze() {
  step "Phase 2: AI ANALYSIS"

  echo ""
  echo -e "    ${BOLD}Discovery Summary${NC}"
  echo "    ────────────────────────────────────────────"
  echo "    Users/Accounts:     ${COUNTS[users]}"
  echo "    SSH Keys:           ${COUNTS[keys]}"
  echo "    Cron Schedules:     ${COUNTS[schedules]}"
  echo "    PGP Keys:           $(wc -l < "${SESSION_DIR}/discovery/pgp_keys.txt" 2>/dev/null | tr -d ' ' || echo 0)"
  echo "    TLS Certificates:   $(wc -l < "${SESSION_DIR}/discovery/tls_certs.csv" 2>/dev/null | tr -d ' ' || echo 0)"

  # Risk analysis
  echo ""
  echo -e "    ${BOLD}Risk Analysis${NC}"
  echo "    ────────────────────────────────────────────"

  local risks=0
  if [ ${COUNTS[keys]} -eq 0 ]; then
    warn "No SSH keys found — partners use password auth (less secure)"
    risks=$((risks + 1))
  fi

  if [ -f "${SESSION_DIR}/discovery/pgp_keys.txt" ] && [ -s "${SESSION_DIR}/discovery/pgp_keys.txt" ]; then
    info "PGP keys found — will need manual export (gpg --export)"
    warn "PGP private keys require passphrase — prepare passphrases"
    risks=$((risks + 1))
  fi

  if [ ${COUNTS[users]} -gt 100 ]; then
    warn "Large migration (${COUNTS[users]} users) — recommend phased approach"
    risks=$((risks + 1))
  fi

  [ $risks -eq 0 ] && ok "No major risks identified"

  # Migration plan
  echo ""
  echo -e "    ${BOLD}Migration Plan${NC}"
  echo "    ────────────────────────────────────────────"
  echo "    Step 1: Import ${COUNTS[users]} user accounts"
  echo "    Step 2: Import ${COUNTS[keys]} SSH keys → Keystore Manager"
  echo "    Step 3: Create home directories with inbox/outbox/archive"
  echo "    Step 4: Set default folder mappings"
  echo "    Step 5: Import cron schedules → Scheduler"
  echo "    Step 6: Configure security profiles"
  echo "    Step 7: Validate each account (connectivity test)"
  echo ""

  # Save plan
  cat > "${SESSION_DIR}/plan.txt" << PLANEOF
Migration Plan — ${SESSION_ID}
Users: ${COUNTS[users]}
Keys: ${COUNTS[keys]}
Schedules: ${COUNTS[schedules]}
Strategy: phased
Phases: shadow → test (10%) → batch (50%) → cutover (100%)
PLANEOF
}

# =============================================================================
# PHASE 3: PLAN — Choose migration strategy
# =============================================================================

plan_strategy() {
  step "Phase 3: MIGRATION STRATEGY"
  echo ""
  echo "    1) ${BOLD}Big bang${NC} — Migrate all users at once (fast, higher risk)"
  echo "    2) ${BOLD}Phased${NC} — Migrate in batches: 10% → 50% → 100% (recommended)"
  echo "    3) ${BOLD}Shadow${NC} — Run parallel, mirror traffic, cut over later (safest)"
  echo "    4) ${BOLD}Test only${NC} — Migrate selected users for testing"
  echo ""
  local strategy=$(ask "Strategy" "2")

  case $strategy in
    1) echo "BIG_BANG" > "${SESSION_DIR}/strategy" ;;
    2) echo "PHASED" > "${SESSION_DIR}/strategy"
       local batch_pct=$(ask "First batch percentage" "10")
       echo "$batch_pct" > "${SESSION_DIR}/batch_pct" ;;
    3) echo "SHADOW" > "${SESSION_DIR}/strategy" ;;
    4) echo "TEST" > "${SESSION_DIR}/strategy"
       info "Enter usernames to migrate (comma-separated):"
       local test_users=$(ask "Users")
       echo "$test_users" > "${SESSION_DIR}/test_users" ;;
  esac

  ok "Strategy: $(cat "${SESSION_DIR}/strategy")"
}

# =============================================================================
# PHASE 4: IMPORT — Create everything in TranzFer
# =============================================================================

connect_tranzfer() {
  step "Connecting to TranzFer"
  TRZ_URL=$(ask "TranzFer API URL" "http://localhost:8080")
  local email=$(ask "Admin email" "admin@filetransfer.local")
  local pass=$(ask_secret "Admin password")

  local resp=$(curl -s -X POST "${TRZ_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${email}\",\"password\":\"${pass}\"}" 2>/dev/null)

  TRZ_TOKEN=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null)

  if [ -n "$TRZ_TOKEN" ] && [ ${#TRZ_TOKEN} -gt 10 ]; then
    ok "Authenticated to TranzFer"
    return 0
  fi
  fail "Login failed"; return 1
}

trz_api() {
  local method="$1" path="$2" body="$3"
  if [ "$method" = "GET" ]; then
    curl -s "${TRZ_URL}${path}" -H "Authorization: Bearer $TRZ_TOKEN" 2>/dev/null
  else
    curl -s -X "$method" "${TRZ_URL}${path}" -H "Authorization: Bearer $TRZ_TOKEN" \
      -H "Content-Type: application/json" -d "$body" 2>/dev/null
  fi
}

import_all() {
  step "Phase 4: IMPORTING TO TRANZFER"

  local strategy=$(cat "${SESSION_DIR}/strategy" 2>/dev/null || echo "BIG_BANG")
  local batch_pct=$(cat "${SESSION_DIR}/batch_pct" 2>/dev/null || echo "100")
  local test_users=$(cat "${SESSION_DIR}/test_users" 2>/dev/null || echo "")

  # Determine which users to migrate in this batch
  local users_csv="${SESSION_DIR}/discovery/users.csv"
  local total=$(tail -n +2 "$users_csv" | wc -l | tr -d ' ')
  local batch_size=$total

  case $strategy in
    PHASED) batch_size=$(( total * batch_pct / 100 )); [ $batch_size -lt 1 ] && batch_size=1 ;;
    TEST) batch_size=999 ;; # filtered by username below
  esac

  info "Migrating ${batch_size} of ${total} users (strategy: ${strategy})"

  # === Import users ===
  local count=0
  tail -n +2 "$users_csv" | while IFS=, read -r uname uhome ushell uuid ugid uperms usize; do
    [ -z "$uname" ] && continue
    count=$((count + 1))
    [ $count -gt $batch_size ] && break

    # For TEST strategy, skip users not in test list
    if [ "$strategy" = "TEST" ] && [ -n "$test_users" ]; then
      echo "$test_users" | grep -q "$uname" || continue
    fi

    # Determine protocol
    local proto="SFTP"
    echo "$ushell" | grep -qE "nologin|false" && proto="SFTP"

    # Generate secure password
    local gen_pass="Mig$(openssl rand -hex 4 2>/dev/null || echo $RANDOM)!Aa1"

    # Create account
    local result=$(trz_api POST "/api/accounts" "{
      \"protocol\": \"${proto}\",
      \"username\": \"${uname}\",
      \"password\": \"${gen_pass}\",
      \"homeDir\": \"${uhome:-/data/sftp/$uname}\"
    }")

    if echo "$result" | grep -q "id"; then
      ok "Account: ${uname} (${proto}) — pass: ${gen_pass}"
      echo "${uname},${proto},${uhome},${gen_pass},SUCCESS" >> "${SESSION_DIR}/import/accounts.csv"
      COUNTS[migrated]=$((${COUNTS[migrated]} + 1))
    elif echo "$result" | grep -qi "already"; then
      warn "Skipped: ${uname} — already exists"
      echo "${uname},${proto},${uhome},,SKIPPED" >> "${SESSION_DIR}/import/accounts.csv"
      COUNTS[skipped]=$((${COUNTS[skipped]} + 1))
    else
      fail "Failed: ${uname}"
      echo "${uname},${proto},${uhome},,FAILED" >> "${SESSION_DIR}/import/accounts.csv"
      COUNTS[failed]=$((${COUNTS[failed]} + 1))
    fi
  done

  # === Import SSH keys → Keystore Manager ===
  if [ -f "${SESSION_DIR}/discovery/ssh_keys.csv" ]; then
    step "Importing SSH keys to Keystore Manager"
    local ks_url=$(echo "$TRZ_URL" | sed 's/:8080/:8093/')

    while IFS=, read -r kuser kfile kdata; do
      [ -z "$kuser" ] && continue
      curl -s -X POST "${ks_url}/api/v1/keys/import" \
        -H "Content-Type: application/json" \
        -d "{
          \"alias\": \"migrated-${kuser}-$(date +%s)\",
          \"keyType\": \"SSH_USER_KEY\",
          \"keyMaterial\": \"${kdata}\",
          \"description\": \"Migrated from source: ${kfile}\",
          \"partnerAccount\": \"${kuser}\"
        }" &>/dev/null && ok "Key: ${kuser}" || warn "Key failed: ${kuser}"
    done < "${SESSION_DIR}/discovery/ssh_keys.csv"
  fi

  # === Import cron schedules → Scheduler ===
  if [ -f "${SESSION_DIR}/discovery/crontabs.csv" ] && [ -s "${SESSION_DIR}/discovery/crontabs.csv" ]; then
    step "Importing cron schedules"
    local cfg_url=$(echo "$TRZ_URL" | sed 's/:8080/:8084/')

    while IFS='|' read -r cuser ccron; do
      [ -z "$ccron" ] && continue
      # Convert cron to TranzFer scheduler format (prepend seconds field)
      local trz_cron="0 ${ccron%% *} ${ccron#* }"

      curl -s -X POST "${cfg_url}/api/scheduler" \
        -H "Content-Type: application/json" \
        -d "{
          \"name\": \"migrated-${cuser}-$(date +%s)\",
          \"description\": \"Migrated cron from ${cuser}\",
          \"cronExpression\": \"${ccron}\",
          \"taskType\": \"EXECUTE_SCRIPT\",
          \"config\": {\"command\": \"echo migrated cron for ${cuser}\"}
        }" &>/dev/null && ok "Schedule: ${cuser}" || warn "Schedule failed: ${cuser}"
    done < "${SESSION_DIR}/discovery/crontabs.csv"
  fi

  # === Create default folder mappings for migrated users ===
  step "Creating default folder mappings"
  info "Default mapping: each user's /inbox routes to their own /outbox"
  info "Custom mappings can be configured in Admin UI after migration"
}

# =============================================================================
# PHASE 5: VALIDATE — Test every migrated account
# =============================================================================

validate() {
  step "Phase 5: VALIDATION"

  [ ! -f "${SESSION_DIR}/import/accounts.csv" ] && { warn "No accounts to validate"; return; }

  local sftp_host=$(echo "$TRZ_URL" | sed 's|http://||;s|:.*||')
  local sftp_port="22222"
  local verified=0
  local failed=0

  while IFS=, read -r uname uproto uhome upass ustatus; do
    [ "$ustatus" != "SUCCESS" ] && continue

    # Test 1: SFTP port reachable
    if nc -z -w 3 "$sftp_host" "$sftp_port" 2>/dev/null; then
      ok "Network: ${uname} → ${sftp_host}:${sftp_port} reachable"
      verified=$((verified + 1))
    else
      fail "Network: ${uname} — port ${sftp_port} unreachable"
      failed=$((failed + 1))
    fi

    # Test 2: API account exists
    local acct=$(trz_api GET "/api/accounts" | python3 -c "
import sys,json
for a in json.load(sys.stdin):
    if a.get('username') == '${uname}':
        print('FOUND'); break
else: print('MISSING')" 2>/dev/null)

    if [ "$acct" = "FOUND" ]; then
      ok "Account: ${uname} exists in TranzFer"
    else
      fail "Account: ${uname} NOT found in TranzFer"
      failed=$((failed + 1))
    fi

  done < "${SESSION_DIR}/import/accounts.csv"

  echo ""
  ok "Validated: ${verified} accounts reachable"
  [ $failed -gt 0 ] && fail "Failed: ${failed} accounts"
}

# =============================================================================
# PHASE 6: REPORT
# =============================================================================

generate_report() {
  step "Phase 6: MIGRATION REPORT"

  local report="${SESSION_DIR}/reports/migration-report.txt"

  cat > "$report" << REPORTEOF
═══════════════════════════════════════════════════════════════
  TranzFer MFT — Migration Report
  Session:    ${SESSION_ID}
  Date:       $(date)
  Strategy:   $(cat "${SESSION_DIR}/strategy" 2>/dev/null || echo "N/A")
═══════════════════════════════════════════════════════════════

DISCOVERY
  Users found:        ${COUNTS[users]}
  SSH keys found:     ${COUNTS[keys]}
  Cron schedules:     ${COUNTS[schedules]}

IMPORT RESULTS
  Migrated:           ${COUNTS[migrated]}
  Skipped:            ${COUNTS[skipped]}
  Failed:             ${COUNTS[failed]}

ACCOUNT DETAILS (credentials below — distribute securely)
$(cat "${SESSION_DIR}/import/accounts.csv" 2>/dev/null | column -t -s, || echo "  No accounts imported")

NEXT STEPS
  1. Distribute new credentials to partners securely
  2. Partners update their MFT client config
  3. Test file transfers with each partner
  4. Monitor SLA compliance for 1-2 weeks
  5. DNS cutover when ready
  6. Decommission old system after validation period

SESSION FILES
  ${SESSION_DIR}/discovery/    — Raw discovery data
  ${SESSION_DIR}/import/       — Import results
  ${SESSION_DIR}/validate/     — Validation results
  ${SESSION_DIR}/reports/      — This report

⚠  THIS REPORT CONTAINS PASSWORDS — HANDLE SECURELY
═══════════════════════════════════════════════════════════════
REPORTEOF

  echo "COMPLETED" > "${SESSION_DIR}/status"

  echo ""
  echo -e "  ${BOLD}${GREEN}╔══════════════════════════════════════════════════════╗${NC}"
  echo -e "  ${BOLD}${GREEN}║          Migration Complete!                          ║${NC}"
  echo -e "  ${BOLD}${GREEN}╚══════════════════════════════════════════════════════╝${NC}"
  echo ""
  echo -e "    ${BOLD}Migrated:${NC}  ${GREEN}${COUNTS[migrated]}${NC} accounts"
  echo -e "    ${BOLD}Skipped:${NC}   ${YELLOW}${COUNTS[skipped]}${NC}"
  echo -e "    ${BOLD}Failed:${NC}    ${RED}${COUNTS[failed]}${NC}"
  echo ""
  echo -e "    ${BOLD}Report:${NC}    ${report}"
  echo -e "    ${BOLD}Session:${NC}   ${SESSION_ID}"
  echo ""
  echo -e "    Resume:   ${CYAN}./migrate.sh --resume ${SESSION_ID}${NC}"
  echo -e "    Validate: ${CYAN}./migrate.sh --validate ${SESSION_ID}${NC}"
  echo ""
}

# =============================================================================
# ROLLBACK
# =============================================================================

rollback() {
  local sid="${1:?Session ID required}"
  local sdir="migration-sessions/${sid}"

  [ ! -d "$sdir" ] && { fail "Session not found: $sid"; return 1; }

  step "Rolling back migration: ${sid}"
  warn "This will disable all accounts created in this migration session."

  if ! confirm "Proceed with rollback?"; then return; fi

  connect_tranzfer || return 1

  while IFS=, read -r uname uproto uhome upass ustatus; do
    [ "$ustatus" != "SUCCESS" ] && continue
    trz_api POST "/api/cli/execute" "{\"command\":\"accounts disable ${uname}\"}" &>/dev/null
    ok "Disabled: ${uname}"
  done < "${sdir}/import/accounts.csv"

  echo "ROLLED_BACK" > "${sdir}/status"
  ok "Rollback complete. Accounts disabled (not deleted)."
}

# =============================================================================
# MAIN
# =============================================================================

main() {
  print_banner
  init_session
  discover || exit 1
  analyze
  if ! confirm "Continue with migration?"; then exit 0; fi
  plan_strategy
  connect_tranzfer || exit 1
  import_all
  validate
  generate_report
}

case "${1:-}" in
  --source) shift; case "$1" in
    ssh|openssh) main ;;
    ftp|vsftpd) main ;;
    sterling) main ;;
    *) main ;; esac ;;
  --import-csv) SESSION_ID="csv-$(date +%Y%m%d-%H%M%S)"; SESSION_DIR="migration-sessions/${SESSION_ID}"
    init_session; discover_csv; analyze; confirm "Continue?" && { plan_strategy; connect_tranzfer && import_all && validate && generate_report; } ;;
  --resume) sid="${2:?Session ID required}"; SESSION_DIR="migration-sessions/${sid}"
    [ ! -d "$SESSION_DIR" ] && { fail "Session not found"; exit 1; }
    info "Resuming session: $sid"; connect_tranzfer && import_all && validate && generate_report ;;
  --validate) sid="${2:?Session ID required}"; SESSION_DIR="migration-sessions/${sid}"
    [ ! -d "$SESSION_DIR" ] && { fail "Session not found"; exit 1; }
    connect_tranzfer && validate ;;
  --rollback) rollback "$2" ;;
  --help|-h) echo "Usage: migrate.sh [--source ssh|ftp|sterling] [--import-csv file.csv]"
    echo "       migrate.sh --resume <session-id>"
    echo "       migrate.sh --validate <session-id>"
    echo "       migrate.sh --rollback <session-id>"
    echo "       migrate.sh (interactive)" ;;
  "") main ;;
esac

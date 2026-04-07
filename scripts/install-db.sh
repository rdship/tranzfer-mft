#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — Database Installation & Validation
# =============================================================================
# Runs Flyway migrations ONCE (instead of 16 services fighting for the lock),
# then validates every table and critical column each service needs.
#
# Usage:
#   ./scripts/install-db.sh                     # Run migrations + validate
#   ./scripts/install-db.sh --validate-only     # Skip migrations, just validate
#   ./scripts/install-db.sh --service sftp-service  # Validate one service only
#
# Exit codes:
#   0  All tables/columns present
#   1  Missing tables or columns (details printed)
#   2  Cannot connect to database
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MANIFEST="$SCRIPT_DIR/service-manifest.json"

# --- Configuration ---
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-filetransfer}"
DB_USER="${DB_USER:-postgres}"
DB_PASS="${DB_PASSWORD:-postgres}"
DOCKER_MODE="${DOCKER_MODE:-false}"
VALIDATE_ONLY=false
SINGLE_SERVICE=""

# --- Colors ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

# --- Parse args ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --validate-only) VALIDATE_ONLY=true; shift ;;
    --service) SINGLE_SERVICE="$2"; shift 2 ;;
    --docker) DOCKER_MODE=true; DB_HOST=postgres; shift ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# --- Helpers ---
log()  { echo -e "${CYAN}[INSTALL]${RESET} $*"; }
ok()   { echo -e "  ${GREEN}[OK]${RESET} $*"; }
fail() { echo -e "  ${RED}[FAIL]${RESET} $*"; }
warn() { echo -e "  ${YELLOW}[WARN]${RESET} $*"; }

PGCMD="psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -A"
if [[ "$DOCKER_MODE" == "true" ]]; then
  PGCMD="docker exec mft-postgres psql -U $DB_USER -d $DB_NAME -t -A"
fi

pg() {
  if [[ "$DOCKER_MODE" == "true" ]]; then
    docker exec mft-postgres psql -U "$DB_USER" -d "$DB_NAME" -t -A -c "$1" 2>/dev/null
  else
    PGPASSWORD="$DB_PASS" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t -A -c "$1" 2>/dev/null
  fi
}

# =============================================================================
# STEP 0: Connectivity check
# =============================================================================
check_connectivity() {
  log "Checking database connectivity..."
  local retries=30
  for i in $(seq 1 $retries); do
    if pg "SELECT 1;" > /dev/null 2>&1; then
      ok "Connected to $DB_HOST:$DB_PORT/$DB_NAME"
      return 0
    fi
    [ $i -lt $retries ] && sleep 2
  done
  fail "Cannot connect to PostgreSQL at $DB_HOST:$DB_PORT/$DB_NAME after ${retries} attempts"
  exit 2
}

# =============================================================================
# STEP 1: Check RabbitMQ health (prevents services from blocking on connection)
# =============================================================================
check_rabbitmq() {
  log "Checking RabbitMQ health..."
  local rmq_host="${RABBITMQ_HOST:-localhost}"
  if [[ "$DOCKER_MODE" == "true" ]]; then
    rmq_host="localhost"
  fi

  local retries=30
  for i in $(seq 1 $retries); do
    if curl -sf "http://${rmq_host}:15672/api/health/checks/alarms" -u guest:guest > /dev/null 2>&1; then
      local alarm_check
      alarm_check=$(curl -sf "http://${rmq_host}:15672/api/health/checks/alarms" -u guest:guest 2>/dev/null)
      if echo "$alarm_check" | grep -q '"status":"ok"'; then
        ok "RabbitMQ healthy (no alarms)"
        return 0
      else
        warn "RabbitMQ has active alarms — services may block on connection"
        warn "Alarm details: $alarm_check"
        return 0
      fi
    fi
    [ $i -lt $retries ] && sleep 2
  done
  warn "RabbitMQ not reachable at ${rmq_host}:15672 — non-critical services may fail"
}

# =============================================================================
# STEP 2: Run Flyway migrations (SINGLE EXECUTION, not 16 parallel)
# =============================================================================
run_migrations() {
  if [[ "$VALIDATE_ONLY" == "true" ]]; then
    log "Skipping migrations (--validate-only)"
    return 0
  fi

  log "Running Flyway migrations (single execution)..."

  # Check if Flyway CLI is available, otherwise use the shared module's embedded Flyway
  if command -v flyway &> /dev/null; then
    flyway -url="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
           -user="$DB_USER" -password="$DB_PASS" \
           -locations="filesystem:$PROJECT_DIR/shared/src/main/resources/db/migration" \
           -baselineOnMigrate=true -baselineVersion=0 \
           migrate
    ok "Flyway CLI migrations complete"
  else
    # Run via Maven — shared module has Flyway on classpath
    log "Flyway CLI not found, running via Maven..."
    (cd "$PROJECT_DIR" && mvn flyway:migrate -pl shared \
      -Dflyway.url="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
      -Dflyway.user="$DB_USER" \
      -Dflyway.password="$DB_PASS" \
      -Dflyway.baselineOnMigrate=true \
      -Dflyway.baselineVersion=0 \
      -q 2>&1) || {
        # Fallback: let the first service handle it, but with a lock guard
        warn "Maven Flyway failed — migrations will run on first service boot"
        return 0
      }
    ok "Maven Flyway migrations complete"
  fi

  # Show migration status
  local version
  version=$(pg "SELECT MAX(version) FROM flyway_schema_history WHERE success = true;" 2>/dev/null || echo "unknown")
  ok "Schema at version: $version"
}

# =============================================================================
# STEP 3: Validate schema against service manifest
# =============================================================================
TOTAL_PASS=0
TOTAL_FAIL=0
TOTAL_WARN=0
MISSING_TABLES=()
MISSING_COLUMNS=()

validate_service() {
  local svc="$1"
  local has_db tables_json columns_json

  has_db=$(python3 -c "
import json
m = json.load(open('$MANIFEST'))
print(m['services']['$svc']['database'])
" 2>/dev/null)

  if [[ "$has_db" != "True" ]]; then
    ok "$svc — no database required"
    TOTAL_PASS=$((TOTAL_PASS + 1))
    return 0
  fi

  local svc_pass=0
  local svc_fail=0

  # Check tables
  local tables
  tables=$(python3 -c "
import json
m = json.load(open('$MANIFEST'))
for t in m['services']['$svc']['tables']:
    print(t)
" 2>/dev/null)

  for table in $tables; do
    local exists
    exists=$(pg "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='$table');" 2>/dev/null)
    if [[ "$exists" == "t" ]]; then
      svc_pass=$((svc_pass + 1))
    else
      svc_fail=$((svc_fail + 1))
      MISSING_TABLES+=("$svc:$table")
    fi
  done

  # Check critical columns
  local col_specs
  col_specs=$(python3 -c "
import json
m = json.load(open('$MANIFEST'))
cols = m['services']['$svc'].get('critical_columns', {})
for table, columns in cols.items():
    for col in columns:
        print(f'{table}:{col}')
" 2>/dev/null)

  for spec in $col_specs; do
    local tbl="${spec%%:*}"
    local col="${spec##*:}"
    local exists
    exists=$(pg "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='$tbl' AND column_name='$col');" 2>/dev/null)
    if [[ "$exists" == "t" ]]; then
      svc_pass=$((svc_pass + 1))
    else
      svc_fail=$((svc_fail + 1))
      MISSING_COLUMNS+=("$svc:$tbl.$col")
    fi
  done

  if [[ $svc_fail -eq 0 ]]; then
    ok "$svc — ${svc_pass} checks passed"
    TOTAL_PASS=$((TOTAL_PASS + svc_pass))
  else
    fail "$svc — ${svc_fail} missing (${svc_pass} ok)"
    TOTAL_FAIL=$((TOTAL_FAIL + svc_fail))
    TOTAL_PASS=$((TOTAL_PASS + svc_pass))
  fi
}

validate_all_services() {
  log "Validating database schema against service manifest..."
  echo ""

  local services
  if [[ -n "$SINGLE_SERVICE" ]]; then
    services="$SINGLE_SERVICE"
  else
    services=$(python3 -c "
import json
m = json.load(open('$MANIFEST'))
for svc in m['services']:
    print(svc)
" 2>/dev/null)
  fi

  for svc in $services; do
    validate_service "$svc"
  done
}

# =============================================================================
# STEP 4: Check Flyway lock status (detect stuck migrations)
# =============================================================================
check_flyway_locks() {
  log "Checking for stuck Flyway migrations..."
  local stuck
  stuck=$(pg "SELECT count(*) FROM flyway_schema_history WHERE success = false;" 2>/dev/null || echo "0")
  if [[ "$stuck" -gt 0 ]]; then
    warn "$stuck failed migration(s) in flyway_schema_history — may need manual repair"
    pg "SELECT version, description, success, installed_on FROM flyway_schema_history WHERE success = false;" 2>/dev/null | while read line; do
      warn "  $line"
    done
  else
    ok "No stuck migrations"
  fi
}

# =============================================================================
# STEP 5: Check connection pool capacity
# =============================================================================
check_connection_capacity() {
  log "Checking PostgreSQL connection capacity..."
  local max_conn
  max_conn=$(pg "SHOW max_connections;" 2>/dev/null || echo "100")
  local current_conn
  current_conn=$(pg "SELECT count(*) FROM pg_stat_activity;" 2>/dev/null || echo "0")

  # 16 services × 8 max pool = 128 connections needed
  local needed=128
  if [[ "$max_conn" -lt "$needed" ]]; then
    warn "max_connections=$max_conn but 16 services need ~$needed — increase to 300+"
  else
    ok "Connection capacity: $current_conn/$max_conn (need ~$needed for all services)"
  fi
}

# =============================================================================
# MAIN
# =============================================================================
main() {
  echo ""
  echo -e "${CYAN}${BOLD}╔═══════════════════════════════════════════════════════════╗${RESET}"
  echo -e "${CYAN}${BOLD}║  TranzFer MFT — Database Installation & Validation       ║${RESET}"
  echo -e "${CYAN}${BOLD}╚═══════════════════════════════════════════════════════════╝${RESET}"
  echo ""

  check_connectivity
  echo ""
  check_rabbitmq
  echo ""
  run_migrations
  echo ""
  check_flyway_locks
  echo ""
  check_connection_capacity
  echo ""
  validate_all_services

  echo ""
  echo -e "${BOLD}═══════════════════════════════════════════════════════════${RESET}"
  echo -e "  ${GREEN}Passed:${RESET}  $TOTAL_PASS"
  echo -e "  ${RED}Failed:${RESET}  $TOTAL_FAIL"

  if [[ ${#MISSING_TABLES[@]} -gt 0 ]]; then
    echo ""
    echo -e "  ${RED}${BOLD}Missing tables:${RESET}"
    for item in "${MISSING_TABLES[@]}"; do
      echo -e "    ${RED}•${RESET} $item"
    done
  fi

  if [[ ${#MISSING_COLUMNS[@]} -gt 0 ]]; then
    echo ""
    echo -e "  ${RED}${BOLD}Missing columns:${RESET}"
    for item in "${MISSING_COLUMNS[@]}"; do
      echo -e "    ${RED}•${RESET} $item"
    done
  fi

  echo -e "${BOLD}═══════════════════════════════════════════════════════════${RESET}"

  if [[ $TOTAL_FAIL -gt 0 ]]; then
    echo ""
    echo -e "  ${RED}${BOLD}SCHEMA VALIDATION FAILED${RESET} — $TOTAL_FAIL missing table(s)/column(s)"
    echo -e "  ${DIM}Run Flyway migrations to fix: mvn flyway:migrate -pl shared${RESET}"
    echo ""
    exit 1
  else
    echo ""
    echo -e "  ${GREEN}${BOLD}DATABASE READY${RESET} — All services can boot safely"
    echo ""
    exit 0
  fi
}

main "$@"

#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# TranzFer MFT Platform — PostgreSQL Restore Script
# =============================================================================
# Restores a custom-format backup (.dump) to the filetransfer database.
# Supports Docker Compose and direct (host) connection modes.
# Safety: requires --confirm, shows metadata, creates pre-restore backup.
# =============================================================================

readonly SCRIPT_NAME="$(basename "$0")"
readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------------------------------------------------------------------------
# Color output
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log()     { echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${CYAN}INFO${NC}  $*"; }
success() { echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${GREEN}OK${NC}    $*"; }
warn()    { echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${YELLOW}WARN${NC}  $*"; }
error()   { echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${RED}ERROR${NC} $*" >&2; }

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
MODE=""
BACKUP_FILE=""
CONFIRMED=false
DATA_ONLY=false
BACKUP_DIR="${BACKUP_DIR:-$PROJECT_DIR/backups}"
DB_NAME="${DB_NAME:-filetransfer}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USERNAME:-${DB_USER:-postgres}}"
DB_PASS="${DB_PASSWORD:-${DB_PASS:-postgres}}"
COMPOSE_SERVICE="postgres"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_DIR/docker-compose.yml}"
# For drop/recreate we need to connect to a maintenance database
MAINTENANCE_DB="postgres"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
    cat <<USAGE
${CYAN}TranzFer MFT Platform — PostgreSQL Restore${NC}

Usage:
  $SCRIPT_NAME --docker  BACKUP_FILE --confirm [OPTIONS]
  $SCRIPT_NAME --host    BACKUP_FILE --confirm [OPTIONS]

Arguments:
  BACKUP_FILE             Path to the .dump backup file to restore

Required:
  --confirm               Acknowledge that this is a destructive operation

Options:
  --docker                Use Docker Compose to run pg_restore inside the container
  --host                  Use local pg_restore binary with host/port/user/password
  --data-only             Restore data only (assumes schema exists via Flyway)
  --backup-dir DIR        Directory for pre-restore safety backup (default: ./backups/)
  --db-name NAME          Database name (default: filetransfer)
  --db-host HOST          Database host (default: localhost, --host mode only)
  --db-port PORT          Database port (default: 5432, --host mode only)
  --db-user USER          Database user (default: postgres, --host mode only)
  --db-pass PASS          Database password (default: postgres, --host mode only)
  --compose-file FILE     Path to docker-compose.yml (default: ../docker-compose.yml)
  --help                  Show this help message

Environment variables (override defaults):
  DB_USERNAME, DB_PASSWORD, DATABASE_URL, DB_HOST, DB_PORT, DB_NAME
  BACKUP_DIR, COMPOSE_FILE

Examples:
  $SCRIPT_NAME --docker backups/mft-backup-2026-04-05-020000.dump --confirm
  $SCRIPT_NAME --host backups/mft-backup-2026-04-05-020000.dump --confirm --data-only
  $SCRIPT_NAME --docker backups/mft-backup-2026-04-05-020000.dump --confirm --backup-dir /mnt/safe

${YELLOW}WARNING: Without --data-only, this drops and recreates the entire database.${NC}
USAGE
    exit 0
}

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
parse_args() {
    if [[ $# -eq 0 ]]; then
        error "No arguments provided."
        echo ""
        usage
    fi

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --docker)       MODE="docker"; shift ;;
            --host)         MODE="host"; shift ;;
            --confirm)      CONFIRMED=true; shift ;;
            --data-only)    DATA_ONLY=true; shift ;;
            --backup-dir)   BACKUP_DIR="$2"; shift 2 ;;
            --db-name)      DB_NAME="$2"; shift 2 ;;
            --db-host)      DB_HOST="$2"; shift 2 ;;
            --db-port)      DB_PORT="$2"; shift 2 ;;
            --db-user)      DB_USER="$2"; shift 2 ;;
            --db-pass)      DB_PASS="$2"; shift 2 ;;
            --compose-file) COMPOSE_FILE="$2"; shift 2 ;;
            --help|-h)      usage ;;
            -*)
                error "Unknown option: $1"
                echo ""
                usage
                ;;
            *)
                if [[ -z "$BACKUP_FILE" ]]; then
                    BACKUP_FILE="$1"
                else
                    error "Unexpected argument: $1"
                    exit 1
                fi
                shift
                ;;
        esac
    done

    # Validation
    if [[ -z "$MODE" ]]; then
        error "No mode specified. Use --docker or --host."
        exit 1
    fi

    if [[ -z "$BACKUP_FILE" ]]; then
        error "No backup file specified."
        exit 1
    fi

    if [[ ! -f "$BACKUP_FILE" ]]; then
        error "Backup file not found: $BACKUP_FILE"
        exit 1
    fi

    if [[ "$CONFIRMED" != true ]]; then
        error "Restore is a destructive operation. You must pass --confirm to proceed."
        echo ""
        echo -e "${YELLOW}This will:"
        if [[ "$DATA_ONLY" == true ]]; then
            echo "  - Truncate existing data and restore from backup"
        else
            echo "  - DROP the '${DB_NAME}' database entirely"
            echo "  - Recreate it and restore all schema + data from backup"
        fi
        echo -e "  - A pre-restore safety backup will be created first${NC}"
        echo ""
        echo "Re-run with --confirm to proceed."
        exit 1
    fi
}

# ---------------------------------------------------------------------------
# Preflight checks
# ---------------------------------------------------------------------------
preflight() {
    if [[ "$MODE" == "docker" ]]; then
        if ! command -v docker &>/dev/null; then
            error "docker is not installed or not in PATH."
            exit 1
        fi
        if [[ ! -f "$COMPOSE_FILE" ]]; then
            error "Docker Compose file not found: $COMPOSE_FILE"
            exit 1
        fi
        if ! docker compose -f "$COMPOSE_FILE" ps --status running "$COMPOSE_SERVICE" 2>/dev/null | grep -q "$COMPOSE_SERVICE"; then
            error "PostgreSQL container '$COMPOSE_SERVICE' is not running."
            exit 1
        fi
    else
        if ! command -v pg_restore &>/dev/null; then
            error "pg_restore is not installed or not in PATH."
            exit 1
        fi
        if ! command -v psql &>/dev/null; then
            error "psql is not installed or not in PATH."
            exit 1
        fi
    fi

    mkdir -p "$BACKUP_DIR"
}

# ---------------------------------------------------------------------------
# Show backup metadata
# ---------------------------------------------------------------------------
show_backup_metadata() {
    local backup_file="$1"

    echo ""
    echo -e "${BOLD}=============================================${NC}"
    echo -e "${BOLD}  Backup Metadata${NC}"
    echo -e "${BOLD}=============================================${NC}"

    local file_size
    file_size=$(du -h "$backup_file" | cut -f1)
    local file_date
    file_date=$(stat -f '%Sm' -t '%Y-%m-%d %H:%M:%S' "$backup_file" 2>/dev/null || stat -c '%y' "$backup_file" 2>/dev/null | cut -d. -f1)

    echo -e "  File:       $(basename "$backup_file")"
    echo -e "  Size:       $file_size"
    echo -e "  Date:       $file_date"
    echo -e "  Target DB:  $DB_NAME"
    if [[ "$DATA_ONLY" == true ]]; then
        echo -e "  Mode:       ${YELLOW}DATA ONLY${NC} (schema preserved)"
    else
        echo -e "  Mode:       ${RED}FULL RESTORE${NC} (drop + recreate)"
    fi

    echo ""
    echo -e "  ${BOLD}Tables in backup:${NC}"

    local table_list
    if command -v pg_restore &>/dev/null; then
        table_list=$(pg_restore --list "$backup_file" 2>/dev/null | grep "TABLE DATA" | awk '{print "    - " $NF}' || echo "    (unable to list)")
    elif [[ "$MODE" == "docker" ]]; then
        local container_tmp="/tmp/restore-inspect.dump"
        docker cp "$backup_file" "mft-postgres:$container_tmp"
        table_list=$(docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" \
            pg_restore --list "$container_tmp" 2>/dev/null | grep "TABLE DATA" | awk '{print "    - " $NF}' || echo "    (unable to list)")
        docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" rm -f "$container_tmp"
    else
        table_list="    (pg_restore not available to inspect)"
    fi

    echo "$table_list"
    echo -e "${BOLD}=============================================${NC}"
    echo ""
}

# ---------------------------------------------------------------------------
# Create pre-restore safety backup
# ---------------------------------------------------------------------------
create_safety_backup() {
    log "Creating pre-restore safety backup..."

    local timestamp
    timestamp="$(date '+%Y-%m-%d-%H%M%S')"
    local safety_file="$BACKUP_DIR/mft-pre-restore-${timestamp}.dump"

    if [[ "$MODE" == "docker" ]]; then
        docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" \
            pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc \
            > "$safety_file" 2>/dev/null || true
    else
        PGPASSWORD="$DB_PASS" pg_dump \
            -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" \
            -d "$DB_NAME" -Fc \
            > "$safety_file" 2>/dev/null || true
    fi

    if [[ -s "$safety_file" ]]; then
        local size
        size=$(du -h "$safety_file" | cut -f1)
        success "Pre-restore backup saved: $safety_file ($size)"
    else
        warn "Pre-restore backup is empty (database may be empty or inaccessible). Continuing..."
        rm -f "$safety_file"
    fi
}

# ---------------------------------------------------------------------------
# Execute SQL helper
# ---------------------------------------------------------------------------
exec_sql() {
    local sql="$1"
    local db="${2:-$MAINTENANCE_DB}"

    if [[ "$MODE" == "docker" ]]; then
        docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" \
            psql -U "$DB_USER" -d "$db" -c "$sql" 2>&1
    else
        PGPASSWORD="$DB_PASS" psql \
            -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" \
            -d "$db" -c "$sql" 2>&1
    fi
}

# ---------------------------------------------------------------------------
# Drop and recreate the database
# ---------------------------------------------------------------------------
drop_and_recreate() {
    log "Terminating active connections to '${DB_NAME}'..."
    exec_sql "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${DB_NAME}' AND pid <> pg_backend_pid();" "$MAINTENANCE_DB" >/dev/null 2>&1 || true

    log "Dropping database '${DB_NAME}'..."
    exec_sql "DROP DATABASE IF EXISTS ${DB_NAME};" "$MAINTENANCE_DB" >/dev/null

    log "Creating database '${DB_NAME}'..."
    exec_sql "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};" "$MAINTENANCE_DB" >/dev/null

    success "Database '${DB_NAME}' recreated."
}

# ---------------------------------------------------------------------------
# Restore the backup
# ---------------------------------------------------------------------------
run_restore() {
    local backup_file="$1"

    local start_time
    start_time=$(date +%s)

    if [[ "$DATA_ONLY" == true ]]; then
        log "Restoring data only from backup (schema assumed to exist via Flyway)..."

        if [[ "$MODE" == "docker" ]]; then
            local container_backup="/tmp/restore-data.dump"
            log "Copying backup into container..."
            docker cp "$backup_file" "mft-postgres:$container_backup"

            docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" \
                pg_restore -U "$DB_USER" -d "$DB_NAME" \
                --data-only --disable-triggers --verbose \
                "$container_backup" 2>&1 \
                | while IFS= read -r line; do log "  pg_restore: $line"; done || true

            docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" rm -f "$container_backup"
        else
            PGPASSWORD="$DB_PASS" pg_restore \
                -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" \
                -d "$DB_NAME" \
                --data-only --disable-triggers --verbose \
                "$backup_file" 2>&1 \
                | while IFS= read -r line; do log "  pg_restore: $line"; done || true
        fi
    else
        log "Performing full restore (schema + data)..."

        drop_and_recreate

        if [[ "$MODE" == "docker" ]]; then
            local container_backup="/tmp/restore-full.dump"
            log "Copying backup into container..."
            docker cp "$backup_file" "mft-postgres:$container_backup"

            docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" \
                pg_restore -U "$DB_USER" -d "$DB_NAME" \
                --verbose --no-owner --no-privileges \
                "$container_backup" 2>&1 \
                | while IFS= read -r line; do log "  pg_restore: $line"; done || true

            docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" rm -f "$container_backup"
        else
            PGPASSWORD="$DB_PASS" pg_restore \
                -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" \
                -d "$DB_NAME" \
                --verbose --no-owner --no-privileges \
                "$backup_file" 2>&1 \
                | while IFS= read -r line; do log "  pg_restore: $line"; done || true
        fi
    fi

    local end_time
    end_time=$(date +%s)
    local duration=$(( end_time - start_time ))

    success "Restore completed in ${duration}s."
}

# ---------------------------------------------------------------------------
# Post-restore validation
# ---------------------------------------------------------------------------
validate_restore() {
    log "Validating restore — checking table row counts..."

    echo ""
    echo -e "${BOLD}  Table                                     Rows${NC}"
    echo -e "  -------------------------------------------  --------"

    local query="
SELECT schemaname || '.' || relname AS table_name,
       n_live_tup AS row_count
FROM pg_stat_user_tables
ORDER BY schemaname, relname;
"

    local result
    if [[ "$MODE" == "docker" ]]; then
        result=$(docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" \
            psql -U "$DB_USER" -d "$DB_NAME" -t -A -F '|' -c "$query" 2>/dev/null)
    else
        result=$(PGPASSWORD="$DB_PASS" psql \
            -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" \
            -d "$DB_NAME" -t -A -F '|' -c "$query" 2>/dev/null)
    fi

    local table_count=0
    local total_rows=0

    if [[ -n "$result" ]]; then
        while IFS='|' read -r tname rows; do
            [[ -z "$tname" ]] && continue
            printf "  %-43s %s\n" "$tname" "$rows"
            (( table_count++ )) || true
            (( total_rows += rows )) || true
        done <<< "$result"
    fi

    echo ""
    if [[ $table_count -eq 0 ]]; then
        warn "No tables found after restore. The database may be empty."
        warn "If you used --data-only, ensure Flyway migrations were applied first."
    else
        success "Validated: ${table_count} tables with ${total_rows} total rows."
    fi
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
summary() {
    echo ""
    echo -e "${GREEN}=============================================${NC}"
    echo -e "${GREEN}  Restore Complete${NC}"
    echo -e "${GREEN}=============================================${NC}"
    echo -e "  Database:    ${DB_NAME}"
    echo -e "  Source:      $(basename "$BACKUP_FILE")"
    if [[ "$DATA_ONLY" == true ]]; then
        echo -e "  Mode:        Data only"
    else
        echo -e "  Mode:        Full (drop + recreate)"
    fi
    echo -e "  Connection:  ${MODE}"
    echo -e "${GREEN}=============================================${NC}"
    echo ""
    echo -e "${YELLOW}NOTE: If you restored schema (not --data-only), Flyway's schema_version${NC}"
    echo -e "${YELLOW}table was restored too. Services should start without re-running migrations.${NC}"
    echo ""
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    parse_args "$@"
    preflight

    echo ""
    warn "=== DESTRUCTIVE OPERATION ==="
    echo ""

    show_backup_metadata "$BACKUP_FILE"

    create_safety_backup
    run_restore "$BACKUP_FILE"
    validate_restore
    summary
}

main "$@"

#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# TranzFer MFT Platform — PostgreSQL Backup Script
# =============================================================================
# Creates compressed custom-format backups of the filetransfer database.
# Supports Docker Compose and direct (host) connection modes.
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
NC='\033[0m' # No Color

log()     { echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${CYAN}INFO${NC}  $*"; }
success() { echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${GREEN}OK${NC}    $*"; }
warn()    { echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${YELLOW}WARN${NC}  $*"; }
error()   { echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] ${RED}ERROR${NC} $*" >&2; }

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
MODE=""                            # "docker" or "host"
BACKUP_DIR="${BACKUP_DIR:-$PROJECT_DIR/backups}"
KEEP_DAYS=30
DB_NAME="${DB_NAME:-filetransfer}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USERNAME:-${DB_USER:-postgres}}"
DB_PASS="${DB_PASSWORD:-${DB_PASS:-postgres}}"
COMPOSE_SERVICE="postgres"
COMPOSE_FILE="${COMPOSE_FILE:-$PROJECT_DIR/docker-compose.yml}"

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
    cat <<USAGE
${CYAN}TranzFer MFT Platform — PostgreSQL Backup${NC}

Usage:
  $SCRIPT_NAME --docker  [OPTIONS]   Backup via Docker Compose
  $SCRIPT_NAME --host    [OPTIONS]   Backup via direct pg_dump connection

Options:
  --docker              Use Docker Compose to run pg_dump inside the container
  --host                Use local pg_dump binary with host/port/user/password
  --backup-dir DIR      Directory to store backups (default: ./backups/)
  --keep N              Delete backups older than N days (default: 30)
  --db-name NAME        Database name (default: filetransfer)
  --db-host HOST        Database host (default: localhost, --host mode only)
  --db-port PORT        Database port (default: 5432, --host mode only)
  --db-user USER        Database user (default: postgres, --host mode only)
  --db-pass PASS        Database password (default: postgres, --host mode only)
  --compose-file FILE   Path to docker-compose.yml (default: ../docker-compose.yml)
  --help                Show this help message

Environment variables (override defaults):
  DB_USERNAME, DB_PASSWORD, DATABASE_URL, DB_HOST, DB_PORT, DB_NAME
  BACKUP_DIR, COMPOSE_FILE

Examples:
  $SCRIPT_NAME --docker
  $SCRIPT_NAME --docker --keep 7 --backup-dir /mnt/backups
  $SCRIPT_NAME --host --db-host db.prod.internal --db-user mft_app
  DB_USERNAME=admin DB_PASSWORD=secret $SCRIPT_NAME --host
USAGE
    exit 0
}

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
parse_args() {
    if [[ $# -eq 0 ]]; then
        error "No mode specified. Use --docker or --host."
        echo ""
        usage
    fi

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --docker)       MODE="docker"; shift ;;
            --host)         MODE="host"; shift ;;
            --backup-dir)   BACKUP_DIR="$2"; shift 2 ;;
            --keep)         KEEP_DAYS="$2"; shift 2 ;;
            --db-name)      DB_NAME="$2"; shift 2 ;;
            --db-host)      DB_HOST="$2"; shift 2 ;;
            --db-port)      DB_PORT="$2"; shift 2 ;;
            --db-user)      DB_USER="$2"; shift 2 ;;
            --db-pass)      DB_PASS="$2"; shift 2 ;;
            --compose-file) COMPOSE_FILE="$2"; shift 2 ;;
            --help|-h)      usage ;;
            *)
                error "Unknown option: $1"
                echo ""
                usage
                ;;
        esac
    done

    if [[ -z "$MODE" ]]; then
        error "No mode specified. Use --docker or --host."
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
        # Check that the postgres container is running
        if ! docker compose -f "$COMPOSE_FILE" ps --status running "$COMPOSE_SERVICE" 2>/dev/null | grep -q "$COMPOSE_SERVICE"; then
            error "PostgreSQL container '$COMPOSE_SERVICE' is not running."
            error "Start it with: docker compose -f $COMPOSE_FILE up -d $COMPOSE_SERVICE"
            exit 1
        fi
    else
        if ! command -v pg_dump &>/dev/null; then
            error "pg_dump is not installed or not in PATH."
            exit 1
        fi
    fi

    mkdir -p "$BACKUP_DIR"
}

# ---------------------------------------------------------------------------
# Execute backup
# ---------------------------------------------------------------------------
run_backup() {
    local timestamp
    timestamp="$(date '+%Y-%m-%d-%H%M%S')"
    local backup_file="$BACKUP_DIR/mft-backup-${timestamp}.dump"

    log "Starting backup of database '${DB_NAME}'..."
    log "Mode: ${MODE}"
    log "Output: ${backup_file}"

    local start_time
    start_time=$(date +%s)

    if [[ "$MODE" == "docker" ]]; then
        docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" \
            pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc --verbose 2>&1 \
            > "$backup_file" \
            | while IFS= read -r line; do log "  pg_dump: $line"; done
    else
        PGPASSWORD="$DB_PASS" pg_dump \
            -h "$DB_HOST" \
            -p "$DB_PORT" \
            -U "$DB_USER" \
            -d "$DB_NAME" \
            -Fc --verbose \
            2>&1 > "$backup_file" \
            | while IFS= read -r line; do log "  pg_dump: $line"; done
    fi

    local end_time
    end_time=$(date +%s)
    local duration=$(( end_time - start_time ))

    # Check that the file was created and is non-empty
    if [[ ! -s "$backup_file" ]]; then
        error "Backup file is empty or was not created: $backup_file"
        rm -f "$backup_file"
        exit 1
    fi

    local size
    size=$(du -h "$backup_file" | cut -f1)
    success "Backup created: $backup_file ($size) in ${duration}s"

    echo "$backup_file"
}

# ---------------------------------------------------------------------------
# Verify backup integrity
# ---------------------------------------------------------------------------
verify_backup() {
    local backup_file="$1"

    log "Verifying backup integrity..."

    local table_count
    if [[ "$MODE" == "docker" ]]; then
        table_count=$(docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" \
            pg_restore --list "$backup_file" 2>/dev/null | grep -c "TABLE DATA" || true)
        # pg_restore --list inside the container needs the file — it's on the host.
        # Instead, use pg_restore on host if available, or copy into container.
        if command -v pg_restore &>/dev/null; then
            if pg_restore --list "$backup_file" >/dev/null 2>&1; then
                table_count=$(pg_restore --list "$backup_file" 2>/dev/null | grep -c "TABLE DATA" || echo "0")
                success "Backup verified: valid custom-format dump with ${table_count} table data entries."
            else
                error "Backup verification FAILED: pg_restore --list reported errors."
                exit 1
            fi
        else
            # Copy file into container and verify there
            local container_backup="/tmp/verify-backup.dump"
            docker cp "$backup_file" "mft-postgres:$container_backup"
            if docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" \
                pg_restore --list "$container_backup" >/dev/null 2>&1; then
                table_count=$(docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" \
                    pg_restore --list "$container_backup" 2>/dev/null | grep -c "TABLE DATA" || echo "0")
                success "Backup verified: valid custom-format dump with ${table_count} table data entries."
            else
                error "Backup verification FAILED: pg_restore --list reported errors."
                docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" rm -f "$container_backup"
                exit 1
            fi
            docker compose -f "$COMPOSE_FILE" exec -T "$COMPOSE_SERVICE" rm -f "$container_backup"
        fi
    else
        if pg_restore --list "$backup_file" >/dev/null 2>&1; then
            table_count=$(pg_restore --list "$backup_file" 2>/dev/null | grep -c "TABLE DATA" || echo "0")
            success "Backup verified: valid custom-format dump with ${table_count} table data entries."
        else
            error "Backup verification FAILED: pg_restore --list reported errors."
            exit 1
        fi
    fi
}

# ---------------------------------------------------------------------------
# Retention: prune old backups
# ---------------------------------------------------------------------------
prune_old_backups() {
    log "Pruning backups older than ${KEEP_DAYS} days in ${BACKUP_DIR}..."

    local count=0
    while IFS= read -r -d '' old_file; do
        warn "Deleting old backup: $(basename "$old_file")"
        rm -f "$old_file"
        (( count++ )) || true
    done < <(find "$BACKUP_DIR" -name "mft-backup-*.dump" -type f -mtime +"$KEEP_DAYS" -print0 2>/dev/null)

    if [[ $count -eq 0 ]]; then
        log "No backups older than ${KEEP_DAYS} days found."
    else
        success "Pruned ${count} old backup(s)."
    fi
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
summary() {
    local total
    total=$(find "$BACKUP_DIR" -name "mft-backup-*.dump" -type f 2>/dev/null | wc -l | tr -d ' ')
    local total_size
    total_size=$(du -sh "$BACKUP_DIR" 2>/dev/null | cut -f1)

    echo ""
    echo -e "${GREEN}=============================================${NC}"
    echo -e "${GREEN}  Backup Complete${NC}"
    echo -e "${GREEN}=============================================${NC}"
    echo -e "  Database:   ${DB_NAME}"
    echo -e "  Backups:    ${total} file(s) in ${BACKUP_DIR}"
    echo -e "  Total size: ${total_size}"
    echo -e "  Retention:  ${KEEP_DAYS} days"
    echo -e "${GREEN}=============================================${NC}"
    echo ""
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    parse_args "$@"
    preflight

    local backup_file
    backup_file=$(run_backup | tail -1)

    verify_backup "$backup_file"
    prune_old_backups
    summary
}

main "$@"

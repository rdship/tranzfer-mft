#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# TranzFer MFT Platform — Backup Cron Setup
# =============================================================================
# Sets up automated daily PostgreSQL backups via cron (Linux/macOS)
# and provides a Kubernetes CronJob YAML snippet for K8s deployments.
# =============================================================================

readonly SCRIPT_NAME="$(basename "$0")"
readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly BACKUP_SCRIPT="$SCRIPT_DIR/backup.sh"

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
MODE="docker"
KEEP_DAYS=30
CRON_HOUR=2
CRON_MINUTE=0
LOG_FILE="/var/log/mft-backup.log"
BACKUP_DIR=""
INSTALL=false
K8S_ONLY=false
COMPOSE_FILE=""

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
    cat <<USAGE
${CYAN}TranzFer MFT Platform — Backup Cron Setup${NC}

Usage:
  $SCRIPT_NAME [OPTIONS]

Options:
  --mode MODE          Backup mode: docker or host (default: docker)
  --keep N             Retention in days (default: 30)
  --hour H             Cron hour (0-23, default: 2)
  --minute M           Cron minute (0-59, default: 0)
  --log-file FILE      Log file path (default: /var/log/mft-backup.log)
  --backup-dir DIR     Backup directory (passed to backup.sh)
  --compose-file FILE  Docker Compose file (passed to backup.sh)
  --install            Install the cron job without prompting
  --k8s-only           Only print the Kubernetes CronJob YAML (skip cron setup)
  --help               Show this help message

Examples:
  $SCRIPT_NAME                           # Show cron entry, prompt to install
  $SCRIPT_NAME --install                 # Install without prompting
  $SCRIPT_NAME --hour 3 --keep 14        # Daily at 3 AM, 14-day retention
  $SCRIPT_NAME --k8s-only               # Only show K8s CronJob YAML
  $SCRIPT_NAME --mode host --install     # Use host pg_dump mode
USAGE
    exit 0
}

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --mode)          MODE="$2"; shift 2 ;;
            --keep)          KEEP_DAYS="$2"; shift 2 ;;
            --hour)          CRON_HOUR="$2"; shift 2 ;;
            --minute)        CRON_MINUTE="$2"; shift 2 ;;
            --log-file)      LOG_FILE="$2"; shift 2 ;;
            --backup-dir)    BACKUP_DIR="$2"; shift 2 ;;
            --compose-file)  COMPOSE_FILE="$2"; shift 2 ;;
            --install)       INSTALL=true; shift ;;
            --k8s-only)      K8S_ONLY=true; shift ;;
            --help|-h)       usage ;;
            *)
                error "Unknown option: $1"
                echo ""
                usage
                ;;
        esac
    done
}

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
preflight() {
    if [[ ! -f "$BACKUP_SCRIPT" ]]; then
        error "Backup script not found at: $BACKUP_SCRIPT"
        error "Ensure backup.sh exists in the same directory as this script."
        exit 1
    fi

    if [[ ! -x "$BACKUP_SCRIPT" ]]; then
        warn "backup.sh is not executable. Making it executable..."
        chmod +x "$BACKUP_SCRIPT"
    fi
}

# ---------------------------------------------------------------------------
# Build the cron command
# ---------------------------------------------------------------------------
build_cron_command() {
    local cmd="$BACKUP_SCRIPT --${MODE} --keep ${KEEP_DAYS}"

    if [[ -n "$BACKUP_DIR" ]]; then
        cmd="$cmd --backup-dir $BACKUP_DIR"
    fi

    if [[ -n "$COMPOSE_FILE" ]]; then
        cmd="$cmd --compose-file $COMPOSE_FILE"
    fi

    echo "$cmd"
}

# ---------------------------------------------------------------------------
# Cron setup
# ---------------------------------------------------------------------------
setup_cron() {
    local backup_cmd
    backup_cmd="$(build_cron_command)"

    local cron_entry="${CRON_MINUTE} ${CRON_HOUR} * * * ${backup_cmd} >> ${LOG_FILE} 2>&1"
    local cron_marker="# TranzFer MFT Platform — daily database backup"

    echo ""
    echo -e "${BOLD}=============================================${NC}"
    echo -e "${BOLD}  Cron Job Configuration${NC}"
    echo -e "${BOLD}=============================================${NC}"
    echo ""
    echo -e "  Schedule:   Daily at ${CRON_HOUR}:$(printf '%02d' "$CRON_MINUTE") (server time)"
    echo -e "  Mode:       ${MODE}"
    echo -e "  Retention:  ${KEEP_DAYS} days"
    echo -e "  Log file:   ${LOG_FILE}"
    echo -e "  Script:     ${BACKUP_SCRIPT}"
    echo ""
    echo -e "  ${BOLD}Cron entry:${NC}"
    echo -e "  ${CYAN}${cron_marker}${NC}"
    echo -e "  ${CYAN}${cron_entry}${NC}"
    echo ""
    echo -e "${BOLD}=============================================${NC}"
    echo ""

    # Check if already installed
    if crontab -l 2>/dev/null | grep -qF "TranzFer MFT Platform"; then
        warn "An existing TranzFer MFT backup cron job was detected."
        warn "It will be replaced with the new configuration."
    fi

    if [[ "$INSTALL" == true ]]; then
        install_cron "$cron_marker" "$cron_entry"
    else
        echo -e "${YELLOW}Do you want to install this cron job? [y/N]${NC} "
        read -r answer
        if [[ "$answer" =~ ^[Yy]$ ]]; then
            install_cron "$cron_marker" "$cron_entry"
        else
            log "Cron job was NOT installed."
            log "To install manually, run: crontab -e"
            echo ""
            echo "Add these lines:"
            echo "  $cron_marker"
            echo "  $cron_entry"
        fi
    fi
}

install_cron() {
    local marker="$1"
    local entry="$2"

    # Remove any existing TranzFer backup entries, then append the new one
    local existing
    existing=$(crontab -l 2>/dev/null || true)

    local filtered
    filtered=$(echo "$existing" | grep -v "TranzFer MFT Platform" | grep -v "backup.sh" || true)

    local new_crontab
    new_crontab=$(printf '%s\n%s\n%s\n' "$filtered" "$marker" "$entry")

    echo "$new_crontab" | crontab -

    success "Cron job installed successfully."
    log "Verify with: crontab -l"

    # Ensure the log file parent directory exists
    local log_dir
    log_dir="$(dirname "$LOG_FILE")"
    if [[ ! -d "$log_dir" ]]; then
        warn "Log directory does not exist: $log_dir"
        warn "Create it with: sudo mkdir -p $log_dir && sudo chown \$(whoami) $log_dir"
    fi

    # Test that the log file is writable
    if ! touch "$LOG_FILE" 2>/dev/null; then
        warn "Cannot write to log file: $LOG_FILE"
        warn "Ensure the file is writable: sudo touch $LOG_FILE && sudo chown \$(whoami) $LOG_FILE"
    fi
}

# ---------------------------------------------------------------------------
# Kubernetes CronJob YAML
# ---------------------------------------------------------------------------
print_k8s_cronjob() {
    echo ""
    echo -e "${BOLD}=============================================${NC}"
    echo -e "${BOLD}  Kubernetes CronJob — MFT Database Backup${NC}"
    echo -e "${BOLD}=============================================${NC}"
    echo ""
    echo -e "${YELLOW}Save the following YAML and apply with: kubectl apply -f mft-backup-cronjob.yaml${NC}"
    echo ""

    cat <<'K8S_YAML'
# =============================================================================
# TranzFer MFT Platform — Kubernetes CronJob for PostgreSQL Backup
# =============================================================================
# Apply with: kubectl apply -f mft-backup-cronjob.yaml
# =============================================================================
apiVersion: batch/v1
kind: CronJob
metadata:
  name: mft-database-backup
  namespace: mft-platform
  labels:
    app.kubernetes.io/name: mft-database-backup
    app.kubernetes.io/part-of: tranzfer-mft
    app.kubernetes.io/component: backup
spec:
  schedule: "0 2 * * *"          # Daily at 2:00 AM UTC
  timeZone: "UTC"
  concurrencyPolicy: Forbid       # Never run two backups at once
  successfulJobsHistoryLimit: 7
  failedJobsHistoryLimit: 3
  startingDeadlineSeconds: 600     # Skip if delayed > 10 min
  jobTemplate:
    spec:
      backoffLimit: 2
      activeDeadlineSeconds: 3600  # Timeout after 1 hour
      template:
        metadata:
          labels:
            app.kubernetes.io/name: mft-database-backup
        spec:
          restartPolicy: OnFailure
          serviceAccountName: mft-backup-sa
          securityContext:
            runAsNonRoot: true
            runAsUser: 1000
            fsGroup: 1000
          containers:
            - name: pg-backup
              image: postgres:16-alpine
              imagePullPolicy: IfNotPresent
              command:
                - /bin/sh
                - -c
                - |
                  set -euo pipefail

                  TIMESTAMP=$(date +%Y-%m-%d-%H%M%S)
                  BACKUP_FILE="/backups/mft-backup-${TIMESTAMP}.dump"
                  RETENTION_DAYS=30

                  echo "[$(date)] Starting backup of database: ${PGDATABASE}"

                  # Create the backup
                  pg_dump -Fc --verbose -f "${BACKUP_FILE}" 2>&1

                  # Verify the backup
                  if pg_restore --list "${BACKUP_FILE}" > /dev/null 2>&1; then
                    SIZE=$(du -h "${BACKUP_FILE}" | cut -f1)
                    TABLES=$(pg_restore --list "${BACKUP_FILE}" 2>/dev/null | grep -c "TABLE DATA" || echo 0)
                    echo "[$(date)] Backup OK: ${BACKUP_FILE} (${SIZE}, ${TABLES} tables)"
                  else
                    echo "[$(date)] ERROR: Backup verification failed!"
                    exit 1
                  fi

                  # Prune old backups
                  DELETED=$(find /backups -name "mft-backup-*.dump" -mtime +${RETENTION_DAYS} -delete -print | wc -l)
                  echo "[$(date)] Pruned ${DELETED} backup(s) older than ${RETENTION_DAYS} days"

                  echo "[$(date)] Backup complete."
              env:
                - name: PGHOST
                  value: "postgresql"              # Matches Helm service name
                - name: PGPORT
                  value: "5432"
                - name: PGDATABASE
                  value: "filetransfer"
                - name: PGUSER
                  valueFrom:
                    secretKeyRef:
                      name: mft-postgresql-credentials
                      key: username
                - name: PGPASSWORD
                  valueFrom:
                    secretKeyRef:
                      name: mft-postgresql-credentials
                      key: password
              volumeMounts:
                - name: backup-storage
                  mountPath: /backups
              resources:
                requests:
                  cpu: 100m
                  memory: 256Mi
                limits:
                  cpu: 500m
                  memory: 512Mi
          volumes:
            - name: backup-storage
              persistentVolumeClaim:
                claimName: mft-backup-pvc
---
# PVC for backup storage — adjust storageClassName for your cluster
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: mft-backup-pvc
  namespace: mft-platform
  labels:
    app.kubernetes.io/name: mft-database-backup
    app.kubernetes.io/part-of: tranzfer-mft
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
  # storageClassName: gp3          # Uncomment and set for your cloud provider
---
# ServiceAccount for backup jobs
apiVersion: v1
kind: ServiceAccount
metadata:
  name: mft-backup-sa
  namespace: mft-platform
  labels:
    app.kubernetes.io/name: mft-database-backup
    app.kubernetes.io/part-of: tranzfer-mft
---
# Secret for PostgreSQL credentials (replace with your actual values or use ExternalSecrets)
# apiVersion: v1
# kind: Secret
# metadata:
#   name: mft-postgresql-credentials
#   namespace: mft-platform
# type: Opaque
# stringData:
#   username: postgres
#   password: <your-production-password>
K8S_YAML

    echo ""
    echo -e "${YELLOW}Prerequisites:${NC}"
    echo "  1. Create the 'mft-platform' namespace: kubectl create namespace mft-platform"
    echo "  2. Create the 'mft-postgresql-credentials' secret with your DB credentials"
    echo "  3. Adjust 'storageClassName' in the PVC for your cloud provider"
    echo "  4. Ensure the PostgreSQL service is accessible as 'postgresql' within the cluster"
    echo "     (matches helm/mft-platform/ Helm chart service name)"
    echo ""
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    parse_args "$@"

    echo ""
    echo -e "${BOLD}TranzFer MFT Platform — Automated Backup Setup${NC}"
    echo ""

    if [[ "$K8S_ONLY" == true ]]; then
        print_k8s_cronjob
        exit 0
    fi

    preflight
    setup_cron
    print_k8s_cronjob

    echo -e "${GREEN}=============================================${NC}"
    echo -e "${GREEN}  Setup Complete${NC}"
    echo -e "${GREEN}=============================================${NC}"
    echo ""
    echo "  Cron:       Configured for daily backup"
    echo "  K8s:        YAML snippet shown above"
    echo ""
    echo "  Test the backup now with:"
    echo "    $BACKUP_SCRIPT --${MODE} --keep ${KEEP_DAYS}"
    echo ""
}

main "$@"

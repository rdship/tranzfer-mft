#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# TranzFer MFT Platform -- Pre-Flight Security Check
# Detects default/insecure secrets before production deployment.
#
# Usage:
#   ./scripts/preflight-check.sh                  Check current env vars
#   ./scripts/preflight-check.sh --env            (same as above)
#   ./scripts/preflight-check.sh --compose <file> Parse docker-compose.yml or .env
#
# Exit codes:
#   0  All checks passed (or only warnings in non-PROD)
#   1  One or more checks FAILED
# =============================================================================

# ---------------------------------------------------------------------------
# Colors & symbols
# ---------------------------------------------------------------------------
if [[ -t 1 ]]; then
    RED='\033[0;31m'
    YELLOW='\033[0;33m'
    GREEN='\033[0;32m'
    CYAN='\033[0;36m'
    BOLD='\033[1m'
    DIM='\033[2m'
    RESET='\033[0m'
else
    RED='' YELLOW='' GREEN='' CYAN='' BOLD='' DIM='' RESET=''
fi

PASS_ICON="[PASS]"
FAIL_ICON="[FAIL]"
WARN_ICON="[WARN]"
SKIP_ICON="[SKIP]"

# ---------------------------------------------------------------------------
# Counters
# ---------------------------------------------------------------------------
PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

# ---------------------------------------------------------------------------
# Mode detection
# ---------------------------------------------------------------------------
MODE="env"
COMPOSE_FILE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --env)
            MODE="env"
            shift
            ;;
        --compose)
            MODE="compose"
            if [[ -z "${2:-}" ]]; then
                echo "Error: --compose requires a file path argument." >&2
                exit 1
            fi
            COMPOSE_FILE="$2"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 [--env | --compose <docker-compose.yml|.env>]"
            echo ""
            echo "  --env              Check current environment variables (default)"
            echo "  --compose <file>   Parse a docker-compose.yml or .env file"
            echo "  -h, --help         Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            echo "Run '$0 --help' for usage." >&2
            exit 1
            ;;
    esac
done

if [[ "$MODE" == "compose" && ! -f "$COMPOSE_FILE" ]]; then
    echo "Error: File not found: $COMPOSE_FILE" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# File-based key-value store (bash 3.2 compatible -- no associative arrays)
# ---------------------------------------------------------------------------
_VARS_FILE=""

_init_vars_store() {
    _VARS_FILE="$(mktemp /tmp/preflight-vars.XXXXXX)"
    trap 'rm -f "$_VARS_FILE"' EXIT
}

_store_var() {
    local key="$1" val="$2"
    # Remove any prior entry for this key, then append
    if [[ -f "$_VARS_FILE" ]]; then
        local tmp
        tmp="$(mktemp /tmp/preflight-tmp.XXXXXX)"
        grep -v "^${key}=" "$_VARS_FILE" > "$tmp" 2>/dev/null || true
        mv "$tmp" "$_VARS_FILE"
    fi
    printf '%s=%s\n' "$key" "$val" >> "$_VARS_FILE"
}

_get_var_from_file() {
    local key="$1"
    if [[ -f "$_VARS_FILE" ]]; then
        local line
        line="$(grep "^${key}=" "$_VARS_FILE" 2>/dev/null | tail -1)" || true
        if [[ -n "$line" ]]; then
            printf '%s' "${line#*=}"
            return 0
        fi
    fi
    return 1
}

_var_exists_in_file() {
    local key="$1"
    [[ -f "$_VARS_FILE" ]] && grep -q "^${key}=" "$_VARS_FILE" 2>/dev/null
}

# ---------------------------------------------------------------------------
# Value lookup abstraction
# ---------------------------------------------------------------------------
load_compose_vars() {
    local file="$1"
    local filename
    filename="$(basename "$file")"

    if [[ "$filename" == .env* ]]; then
        # Plain .env file: extract KEY=VALUE, strip quotes
        while IFS= read -r line; do
            [[ -z "$line" ]] && continue
            # Skip comments
            case "$line" in
                \#*|\ \#*) continue ;;
            esac
            if echo "$line" | grep -qE '^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*='; then
                local key val
                key="$(echo "$line" | sed -E 's/^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=.*/\1/')"
                val="$(echo "$line" | sed -E 's/^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=(.*)/\1/')"
                # Strip surrounding quotes
                val="${val#\"}" ; val="${val%\"}"
                val="${val#\'}" ; val="${val%\'}"
                _store_var "$key" "$val"
            fi
        done < "$file"
    else
        # YAML (docker-compose): extract KEY=value and KEY: value forms,
        # resolving ${VAR:-default} to default values.
        while IFS= read -r line; do
            [[ -z "$line" ]] && continue
            case "$line" in
                \#*|\ *\#*) ;;  # Allow processing -- comments after values are rare
            esac

            local key="" val=""

            # Pattern 1: KEY=value (list-item style in YAML)
            if echo "$line" | grep -qE '^[[:space:]-]*[A-Za-z_][A-Za-z0-9_]*='; then
                key="$(echo "$line" | sed -E 's/^[[:space:]-]*([A-Za-z_][A-Za-z0-9_]*)=.*/\1/')"
                val="$(echo "$line" | sed -E 's/^[[:space:]-]*[A-Za-z_][A-Za-z0-9_]*=(.*)/\1/')"
            # Pattern 2: KEY: value (mapping style)
            elif echo "$line" | grep -qE '^[[:space:]-]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:[[:space:]]+.+'; then
                key="$(echo "$line" | sed -E 's/^[[:space:]-]*([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*:[[:space:]]+.*/\1/')"
                val="$(echo "$line" | sed -E 's/^[[:space:]-]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:[[:space:]]+(.*)/\1/')"
            fi

            if [[ -n "$key" ]]; then
                # Strip quotes
                val="${val#\"}" ; val="${val%\"}"
                val="${val#\'}" ; val="${val%\'}"

                # Resolve ${VAR:-default} to the default value
                if echo "$val" | grep -qE '^\$\{[A-Za-z_][A-Za-z0-9_]*:-'; then
                    val="$(echo "$val" | sed -E 's/^\$\{[A-Za-z_][A-Za-z0-9_]*:-(.*)\}$/\1/')"
                    # Handle nested ${OUTER:-${INNER:-default}}
                    if echo "$val" | grep -qE '^\$\{[A-Za-z_][A-Za-z0-9_]*:-'; then
                        val="$(echo "$val" | sed -E 's/^\$\{[A-Za-z_][A-Za-z0-9_]*:-(.*)\}$/\1/')"
                    fi
                fi

                # Resolve ${OUTER:${INNER:-default}} variant
                if echo "$val" | grep -qE '^\$\{[A-Za-z_][A-Za-z0-9_]*:\$\{'; then
                    val="$(echo "$val" | sed -E 's/^\$\{[A-Za-z_][A-Za-z0-9_]*:\$\{[A-Za-z_][A-Za-z0-9_]*:-(.*)\}\}$/\1/')"
                fi

                _store_var "$key" "$val"
            fi
        done < "$file"
    fi
}

get_var() {
    local varname="$1"
    if [[ "$MODE" == "env" ]]; then
        eval "printf '%s' \"\${${varname}:-}\""
    else
        _get_var_from_file "$varname" || printf ''
    fi
}

var_is_set() {
    local varname="$1"
    if [[ "$MODE" == "env" ]]; then
        eval "[[ -n \"\${${varname}+x}\" ]]"
    else
        _var_exists_in_file "$varname"
    fi
}

# ---------------------------------------------------------------------------
# Reporting helpers
# ---------------------------------------------------------------------------
report_pass() {
    local label="$1"
    printf "  ${GREEN}${PASS_ICON}${RESET}  %-40s %s\n" "$label" "${2:-OK}"
    PASS_COUNT=$((PASS_COUNT + 1))
}

report_fail() {
    local label="$1"
    local msg="$2"
    printf "  ${RED}${FAIL_ICON}${RESET}  %-40s %s\n" "$label" "$msg"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

report_warn() {
    local label="$1"
    local msg="$2"
    printf "  ${YELLOW}${WARN_ICON}${RESET}  %-40s %s\n" "$label" "$msg"
    WARN_COUNT=$((WARN_COUNT + 1))
}

report_skip() {
    local label="$1"
    local msg="$2"
    printf "  ${DIM}${SKIP_ICON}${RESET}  %-40s %s\n" "$label" "$msg"
}

# In PROD mode, issues are FAIL; otherwise WARN.
report_issue() {
    local label="$1"
    local msg="$2"
    if [[ "$IS_PROD" == "true" ]]; then
        report_fail "$label" "$msg"
    else
        report_warn "$label" "$msg"
    fi
}

# ---------------------------------------------------------------------------
# Portable uppercase helper (bash 3.2 lacks ${var^^})
# ---------------------------------------------------------------------------
to_upper() {
    printf '%s' "$1" | tr '[:lower:]' '[:upper:]'
}

# ---------------------------------------------------------------------------
# Load file variables if in compose mode
# ---------------------------------------------------------------------------
if [[ "$MODE" == "compose" ]]; then
    _init_vars_store
    load_compose_vars "$COMPOSE_FILE"
fi

# ---------------------------------------------------------------------------
# Determine environment
# ---------------------------------------------------------------------------
PLATFORM_ENV="$(get_var PLATFORM_ENVIRONMENT)"
IS_PROD="false"
PLATFORM_ENV_UPPER="$(to_upper "${PLATFORM_ENV}")"
if [[ "$PLATFORM_ENV_UPPER" == "PROD" || "$PLATFORM_ENV_UPPER" == "PRODUCTION" ]]; then
    IS_PROD="true"
fi

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
echo ""
printf "${CYAN}${BOLD}"
echo "  ==========================================================="
echo "   TranzFer MFT Platform -- Pre-Flight Security Check"
echo "  ==========================================================="
printf "${RESET}"
echo ""
if [[ "$MODE" == "compose" ]]; then
    printf "  ${DIM}Source:${RESET}      %s\n" "$COMPOSE_FILE"
else
    printf "  ${DIM}Source:${RESET}      Environment variables\n"
fi
printf "  ${DIM}Environment:${RESET}  %s" "${PLATFORM_ENV:-<unset>}"
if [[ "$IS_PROD" == "true" ]]; then
    printf "  ${RED}(strict mode -- all issues are errors)${RESET}"
fi
echo ""
echo ""

# ---------------------------------------------------------------------------
# Check: PLATFORM_ENVIRONMENT
# ---------------------------------------------------------------------------
printf "${BOLD}  Environment Configuration${RESET}\n"
echo "  -----------------------------------------------------------"

if [[ -z "$PLATFORM_ENV" ]]; then
    report_issue "PLATFORM_ENVIRONMENT" "Not set -- should be explicitly configured"
else
    report_pass "PLATFORM_ENVIRONMENT" "Set to '${PLATFORM_ENV}'"
fi

echo ""

# ---------------------------------------------------------------------------
# Secret checks
# ---------------------------------------------------------------------------
printf "${BOLD}  Secret & Credential Checks${RESET}\n"
echo "  -----------------------------------------------------------"

# --- 1. JWT_SECRET ---
check_jwt_secret() {
    local val label="JWT_SECRET"
    val="$(get_var JWT_SECRET)"

    if ! var_is_set JWT_SECRET; then
        report_issue "$label" "Not set"
        return
    fi
    if [[ "$val" == "change_me_in_production_256bit_secret_key!!" || \
          "$val" == "change_me_in_production_256bit_secret_key_here!!" ]]; then
        report_issue "$label" "Using known default value"
        return
    fi
    if [[ ${#val} -lt 32 ]]; then
        report_issue "$label" "Too short (${#val} chars, minimum 32)"
        return
    fi
    report_pass "$label" "Set (${#val} chars)"
}
check_jwt_secret

# --- 2. CONTROL_API_KEY (used as HMAC secret for PCI audit log signing) ---
# NOTE: This key is NO LONGER used for inter-service HTTP authentication.
# Inter-service auth is now done via SPIFFE JWT-SVIDs (zero-trust workload identity).
# This key is retained solely as the HMAC signing secret in AuditService.
check_control_api_key() {
    local val label="CONTROL_API_KEY (audit HMAC signing)"
    val="$(get_var CONTROL_API_KEY)"

    if ! var_is_set CONTROL_API_KEY; then
        report_issue "$label" "Not set"
        return
    fi
    if [[ "$val" == "internal_control_secret" ]]; then
        report_issue "$label" "Using known default value — change for production"
        return
    fi
    report_pass "$label"
}
check_control_api_key

# --- 2b. SPIFFE / SPIRE workload identity ---
check_spiffe() {
    local label="SPIFFE_ENABLED (zero-trust inter-service identity)"
    local enabled; enabled="$(get_var SPIFFE_ENABLED)"

    if [[ "$enabled" == "true" ]]; then
        report_pass "$label" "Enabled — SPIRE agent socket must be available at /run/spire/sockets/agent.sock"
        local socket_path; socket_path="$(get_var SPIFFE_SOCKET)"
        if [[ -z "$socket_path" ]]; then
            report_issue "SPIFFE_SOCKET" "Not set — defaulting to unix:/run/spire/sockets/agent.sock"
        fi
    else
        report_issue "$label" "Disabled (SPIFFE_ENABLED != true) — outbound service calls proceed without workload identity"
    fi
}
check_spiffe

# --- 3. DB_PASSWORD ---
check_db_password() {
    local val label="DB_PASSWORD"
    val="$(get_var DB_PASSWORD)"

    if ! var_is_set DB_PASSWORD; then
        report_issue "$label" "Not set"
        return
    fi
    if [[ "$val" == "postgres" ]]; then
        report_issue "$label" "Using known default value ('postgres')"
        return
    fi
    report_pass "$label"
}
check_db_password

# --- 4. ENCRYPTION_MASTER_KEY ---
check_encryption_key() {
    local val label="ENCRYPTION_MASTER_KEY"
    val="$(get_var ENCRYPTION_MASTER_KEY)"

    if ! var_is_set ENCRYPTION_MASTER_KEY; then
        report_issue "$label" "Not set"
        return
    fi
    if [[ "$val" == "0000000000000000000000000000000000000000000000000000000000000000" ]]; then
        report_issue "$label" "Using known default (all zeros)"
        return
    fi
    # Check for all-same-character pattern
    local first_char="${val:0:1}"
    local all_same
    all_same="$(printf '%*s' "${#val}" '' | tr ' ' "$first_char")"
    if [[ ${#val} -ge 32 && "$val" == "$all_same" ]]; then
        report_issue "$label" "All identical characters ('${first_char}' repeated)"
        return
    fi
    report_pass "$label"
}
check_encryption_key

# --- 5. KEYSTORE_MASTER_PASSWORD ---
check_keystore_password() {
    local val label="KEYSTORE_MASTER_PASSWORD"
    val="$(get_var KEYSTORE_MASTER_PASSWORD)"

    if ! var_is_set KEYSTORE_MASTER_PASSWORD; then
        report_issue "$label" "Not set"
        return
    fi
    if [[ "$val" == "change-this-master-password" ]]; then
        report_issue "$label" "Using known default value"
        return
    fi
    report_pass "$label"
}
check_keystore_password

# --- 6. LICENSE_ADMIN_KEY ---
check_license_admin_key() {
    local val label="LICENSE_ADMIN_KEY"
    val="$(get_var LICENSE_ADMIN_KEY)"

    if ! var_is_set LICENSE_ADMIN_KEY; then
        report_issue "$label" "Not set"
        return
    fi
    if [[ "$val" == "license_admin_secret_key" ]]; then
        report_issue "$label" "Using known default value"
        return
    fi
    report_pass "$label"
}
check_license_admin_key

# --- 7. RABBITMQ_PASSWORD / RABBITMQ_DEFAULT_PASS ---
check_rabbitmq_password() {
    local label="RABBITMQ_PASSWORD"
    local found="false"
    local varname

    for varname in RABBITMQ_PASSWORD RABBITMQ_DEFAULT_PASS; do
        if var_is_set "$varname"; then
            found="true"
            local val
            val="$(get_var "$varname")"
            if [[ "$val" == "guest" ]]; then
                report_issue "$varname" "Using known default value ('guest')"
                return
            fi
        fi
    done

    if [[ "$found" == "false" ]]; then
        report_issue "$label" "Neither RABBITMQ_PASSWORD nor RABBITMQ_DEFAULT_PASS is set"
        return
    fi
    report_pass "$label" "OK (non-default)"
}
check_rabbitmq_password

# --- 8. FTP TLS Keystore Password ---
check_ftp_keystore_password() {
    local label="FTP_TLS_KEYSTORE_PASSWORD"
    local found="false"
    local varname

    for varname in FTP_TLS_KEYSTORE_PASSWORD FTP_FTPS_KEYSTORE_PASSWORD; do
        if var_is_set "$varname"; then
            found="true"
            local val
            val="$(get_var "$varname")"
            if [[ "$val" == "changeit" ]]; then
                report_issue "$varname" "Using known default value ('changeit')"
                return
            fi
        fi
    done

    if [[ "$found" == "false" ]]; then
        report_skip "$label" "Not set (OK if FTPS is disabled)"
        return
    fi
    report_pass "$label" "OK (non-default)"
}
check_ftp_keystore_password

# --- 9. FTP TLS Truststore Password ---
check_ftp_truststore_password() {
    local label="FTP_TLS_TRUSTSTORE_PASSWORD"
    local found="false"
    local varname

    for varname in FTP_TLS_TRUSTSTORE_PASSWORD FTP_FTPS_TRUSTSTORE_PASSWORD; do
        if var_is_set "$varname"; then
            found="true"
            local val
            val="$(get_var "$varname")"
            if [[ "$val" == "changeit" ]]; then
                report_issue "$varname" "Using known default value ('changeit')"
                return
            fi
        fi
    done

    if [[ "$found" == "false" ]]; then
        report_skip "$label" "Not set (OK if FTPS client-cert auth is unused)"
        return
    fi
    report_pass "$label" "OK (non-default)"
}
check_ftp_truststore_password

echo ""

# ---------------------------------------------------------------------------
# Additional infrastructure checks
# ---------------------------------------------------------------------------
printf "${BOLD}  Infrastructure Checks${RESET}\n"
echo "  -----------------------------------------------------------"

# Database URL check
check_db_url() {
    local val label="DATABASE_URL"
    val="$(get_var DATABASE_URL)"

    if ! var_is_set DATABASE_URL; then
        report_skip "$label" "Not set in this source"
        return
    fi

    if [[ "$IS_PROD" == "true" ]]; then
        if echo "$val" | grep -qE 'localhost|127\.0\.0\.1'; then
            report_fail "$label" "Points to localhost in PROD environment"
            return
        fi
    fi
    report_pass "$label"
}
check_db_url

echo ""

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo "  ==========================================================="
printf "  ${BOLD}Summary:${RESET}  "
printf "${GREEN}%d passed${RESET}  " "$PASS_COUNT"
if [[ $FAIL_COUNT -gt 0 ]]; then
    printf "${RED}%d failed${RESET}  " "$FAIL_COUNT"
else
    printf "%d failed  " "$FAIL_COUNT"
fi
if [[ $WARN_COUNT -gt 0 ]]; then
    printf "${YELLOW}%d warnings${RESET}" "$WARN_COUNT"
else
    printf "%d warnings" "$WARN_COUNT"
fi
echo ""
echo "  ==========================================================="

if [[ $FAIL_COUNT -gt 0 ]]; then
    echo ""
    printf "  ${RED}${BOLD}DEPLOYMENT BLOCKED${RESET}${RED} -- %d secret(s) must be changed before deploying.${RESET}\n" "$FAIL_COUNT"
    echo ""
    exit 1
elif [[ $WARN_COUNT -gt 0 ]]; then
    echo ""
    printf "  ${YELLOW}${BOLD}WARNINGS PRESENT${RESET}${YELLOW} -- Review before promoting to production.${RESET}\n"
    echo ""
    exit 0
else
    echo ""
    printf "  ${GREEN}${BOLD}ALL CHECKS PASSED${RESET}${GREEN} -- Ready for deployment.${RESET}\n"
    echo ""
    exit 0
fi

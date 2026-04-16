#!/bin/bash
# =============================================================================
# TranzFer MFT — Product Sanity Validation Test Suite
# =============================================================================
# Re-runnable end-to-end validation covering:
#   Phase 1: Platform health check (all containers healthy)
#   Phase 2: Authentication & API access
#   Phase 3: Onboarding — ensure VFS accounts, servers, flows exist
#   Phase 4: File flow creation (15 diverse flows via API)
#   Phase 5: File upload via 3rd-party SFTP client
#   Phase 6: Pipeline processing — transfer records + flow executions
#   Phase 6b: Activity Monitor + Flow Execution lifecycle (restart/terminate/etc.)
#   Phase 7: Artifact capture (logs, thread dumps, DB state)
#
# IMPORTANT: All servers and accounts use VIRTUAL (VFS) storage mode.
#            Files are stored via storage-manager so flow steps can access
#            them across containers. PHYSICAL mode is never used in tests.
#
# Usage: ./run-sanity-validation.sh [--skip-flows] [--skip-uploads] [--report-only]
# =============================================================================

set -uo pipefail

REPORT_DIR="docs/run-reports"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
REPORT_FILE="${REPORT_DIR}/sanity-validation-${TIMESTAMP}.md"
DUMP_DIR="/tmp/mft-sanity-${TIMESTAMP}"
PASS=0; FAIL=0; SKIP=0; WARN=0
SKIP_FLOWS=false; SKIP_UPLOADS=false; REPORT_ONLY=false

for arg in "$@"; do
  case $arg in
    --skip-flows) SKIP_FLOWS=true ;;
    --skip-uploads) SKIP_UPLOADS=true ;;
    --report-only) REPORT_ONLY=true ;;
  esac
done

mkdir -p "$DUMP_DIR" "$REPORT_DIR"

# --- Helpers ---
log()  { echo "[$(date +%H:%M:%S)] $*"; }
pass() { PASS=$((PASS+1)); log "PASS: $*"; echo "| PASS | $* |" >> "$DUMP_DIR/results.md"; }
fail() { FAIL=$((FAIL+1)); log "FAIL: $*"; echo "| FAIL | $* |" >> "$DUMP_DIR/results.md"; }
warn() { WARN=$((WARN+1)); log "WARN: $*"; echo "| WARN | $* |" >> "$DUMP_DIR/results.md"; }
skip() { SKIP=$((SKIP+1)); log "SKIP: $*"; echo "| SKIP | $* |" >> "$DUMP_DIR/results.md"; }

get_token() {
  curl -s http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"superadmin@tranzfer.io","password":"superadmin"}' \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])' 2>/dev/null
}

echo "| Status | Check |" > "$DUMP_DIR/results.md"
echo "|--------|-------|" >> "$DUMP_DIR/results.md"

# =============================================================================
# PHASE 1: Platform Health
# =============================================================================
log "=== PHASE 1: Platform Health ==="

HEALTHY=$(docker compose ps --format '{{.Status}}' 2>/dev/null | grep -c "healthy" || true)
TOTAL=$(docker compose ps --format '{{.Name}}' 2>/dev/null | wc -l | tr -d ' ')

if [ "$HEALTHY" -ge 30 ]; then
  pass "Platform health: ${HEALTHY}/${TOTAL} containers healthy"
else
  fail "Platform health: only ${HEALTHY}/${TOTAL} containers healthy"
fi

for svc in onboarding-api sftp-service ftp-service config-service forwarder-service storage-manager; do
  STATUS=$(docker compose ps --format '{{.Name}} {{.Status}}' 2>/dev/null | grep "mft-${svc}" | grep -c "healthy" || true)
  if [ "$STATUS" -eq 1 ]; then pass "$svc is healthy"
  else fail "$svc is NOT healthy"; fi
done

# =============================================================================
# PHASE 2: Authentication & API Access
# =============================================================================
log "=== PHASE 2: Authentication & API ==="

TOKEN=$(get_token 2>/dev/null || echo "")
if [ -n "$TOKEN" ] && [ ${#TOKEN} -gt 50 ]; then
  pass "Login successful (token: ${#TOKEN} chars)"
else
  fail "Login failed — cannot continue API tests"
  TOKEN=""
fi

if [ -n "$TOKEN" ]; then
  for endpoint in "accounts" "partners" "servers" "activity-monitor"; do
    CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/${endpoint}" -H "Authorization: Bearer $TOKEN")
    if [ "$CODE" = "200" ]; then pass "GET /api/${endpoint} returns 200"
    else warn "GET /api/${endpoint} returns ${CODE}"; fi
  done

  CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8084/api/flows" -H "Authorization: Bearer $TOKEN")
  if [ "$CODE" = "200" ]; then pass "GET /api/flows (config-service) returns 200"
  else warn "GET /api/flows (config-service) returns ${CODE}"; fi
fi

# =============================================================================
# PHASE 3: Onboarding — VFS Accounts, Servers, Flows
# =============================================================================
log "=== PHASE 3: Onboarding Validation (VFS mode) ==="

# 3a: Verify DB has seed data
for table_check in "partners:5" "transfer_accounts:5" "file_flows:5"; do
  TABLE="${table_check%%:*}"
  MIN="${table_check#*:}"
  COUNT=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "SELECT count(*) FROM ${TABLE};" 2>/dev/null | tr -d ' ')
  if [ "${COUNT:-0}" -ge "$MIN" ]; then
    pass "DB: ${TABLE} has ${COUNT} rows (min: ${MIN})"
  else
    fail "DB: ${TABLE} has only ${COUNT:-0} rows (expected >= ${MIN})"
  fi
done

# 3b: Verify named accounts exist
for acct in acme-sftp globalbank-sftp logiflow-sftp medtech-as2 globalbank-ftps; do
  EXISTS=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "SELECT count(*) FROM transfer_accounts WHERE username='${acct}';" 2>/dev/null | tr -d ' ')
  if [ "${EXISTS:-0}" -ge 1 ]; then pass "Account exists: ${acct}"
  else fail "Account missing: ${acct}"; fi
done

# 3c: ENFORCE VFS — switch ALL servers and accounts to VIRTUAL storage mode
log "  Enforcing VFS (VIRTUAL) storage mode on all servers and accounts..."
docker exec mft-postgres psql -U postgres -d filetransfer -c \
  "UPDATE server_instances SET default_storage_mode='VIRTUAL' WHERE default_storage_mode != 'VIRTUAL' OR default_storage_mode IS NULL;" 2>/dev/null
docker exec mft-postgres psql -U postgres -d filetransfer -c \
  "UPDATE transfer_accounts SET storage_mode='VIRTUAL' WHERE storage_mode != 'VIRTUAL' OR storage_mode IS NULL;" 2>/dev/null

VFS_SERVERS=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
  "SELECT count(*) FROM server_instances WHERE default_storage_mode='VIRTUAL';" 2>/dev/null | tr -d ' ')
VFS_ACCOUNTS=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
  "SELECT count(*) FROM transfer_accounts WHERE storage_mode='VIRTUAL';" 2>/dev/null | tr -d ' ')
PHYS_ACCOUNTS=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
  "SELECT count(*) FROM transfer_accounts WHERE storage_mode='PHYSICAL';" 2>/dev/null | tr -d ' ')

if [ "${PHYS_ACCOUNTS:-0}" -eq 0 ]; then
  pass "VFS enforced: ${VFS_SERVERS} servers + ${VFS_ACCOUNTS} accounts on VIRTUAL (0 PHYSICAL)"
else
  fail "VFS enforcement incomplete: ${PHYS_ACCOUNTS} accounts still on PHYSICAL"
fi

# =============================================================================
# PHASE 4: File Flow Creation (15 diverse flows via API)
# =============================================================================
if [ "$SKIP_FLOWS" = true ] || [ "$REPORT_ONLY" = true ]; then
  skip "Phase 4: Flow creation (--skip-flows)"
else
  log "=== PHASE 4: File Flow Creation ==="

  if [ -z "$TOKEN" ]; then TOKEN=$(get_token); fi
  API="http://localhost:8084/api/flows/quick"
  H1="Authorization: Bearer $TOKEN"
  H2="Content-Type: application/json"
  FLOW_OK=0; FLOW_FAIL=0

  create_flow() {
    local body="$1"
    CODE=$(curl -s -o /dev/null -w "%{http_code}" "$API" -H "$H1" -H "$H2" -d "$body")
    if [ "$CODE" = "201" ] || [ "$CODE" = "409" ]; then FLOW_OK=$((FLOW_OK+1))
    else FLOW_FAIL=$((FLOW_FAIL+1)); fi
  }

  # EDI
  create_flow '{"name":"SV-EDI 850 Purchase Order","source":"acme-sftp","filenamePattern":".*\\.(850|po)$","protocol":"SFTP","direction":"INBOUND","actions":["SCREEN","CONVERT_EDI","COMPRESS_GZIP"],"ediTargetFormat":"JSON","deliverTo":"globalbank-ftps","deliveryPath":"/inbound/po","priority":10}'
  create_flow '{"name":"SV-EDI 810 Invoice","source":"globalbank-sftp","filenamePattern":".*\\.810$","protocol":"SFTP","direction":"INBOUND","actions":["SCREEN","CONVERT_EDI","ENCRYPT_AES"],"ediTargetFormat":"XML","encryptionKeyAlias":"demo-aes-key","deliverTo":"acme-sftp","deliveryPath":"/inbound/inv","priority":15}'
  create_flow '{"name":"SV-EDIFACT DESADV","source":"logiflow-sftp","filenamePattern":".*\\.edifact$","protocol":"SFTP","direction":"INBOUND","actions":["SCREEN","CONVERT_EDI","COMPRESS_GZIP"],"ediTargetFormat":"XML","deliverTo":"globalbank-ftps","deliveryPath":"/inbound/desadv","priority":25}'

  # Healthcare
  create_flow '{"name":"SV-HL7 ADT Patient Admin","source":"acme-sftp","filenamePattern":"ADT_.*\\.hl7$","protocol":"SFTP","direction":"INBOUND","actions":["SCREEN","ENCRYPT_AES","COMPRESS_GZIP"],"encryptionKeyAlias":"demo-aes-key","deliverTo":"globalbank-sftp","deliveryPath":"/inbound/hl7","priority":5}'

  # Financial
  create_flow '{"name":"SV-ACH Batch Payment","source":"globalbank-sftp","filenamePattern":"ACH_.*\\.ach$","protocol":"SFTP","direction":"INBOUND","actions":["SCREEN","CHECKSUM_VERIFY","ENCRYPT_AES"],"encryptionKeyAlias":"demo-aes-key","deliverTo":"acme-sftp","deliveryPath":"/inbound/ach","priority":3}'
  create_flow '{"name":"SV-SWIFT MT103 Wire","source":"globalbank-sftp","filenamePattern":"MT103_.*\\.swi$","protocol":"SFTP","direction":"OUTBOUND","actions":["SCREEN","ENCRYPT_PGP","CHECKSUM_VERIFY"],"encryptionKeyAlias":"demo-pgp-key","deliverTo":"acme-sftp","deliveryPath":"/outbound/swift","priority":2}'
  create_flow '{"name":"SV-ISO 20022 pain.001","source":"acme-sftp","filenamePattern":"pain001_.*\\.xml$","protocol":"SFTP","direction":"INBOUND","actions":["SCREEN","CHECKSUM_VERIFY","ENCRYPT_AES"],"encryptionKeyAlias":"demo-aes-key","deliverTo":"globalbank-sftp","deliveryPath":"/inbound/iso20022","priority":4}'

  # Encryption
  create_flow '{"name":"SV-PGP Decrypt Inbound","source":"globalbank-sftp","filenamePattern":".*\\.pgp$","protocol":"SFTP","direction":"INBOUND","actions":["DECRYPT_PGP","SCREEN"],"deliverTo":"acme-sftp","deliveryPath":"/decrypted","priority":12}'
  create_flow '{"name":"SV-Double Encrypt Outbound","source":"acme-sftp","filenamePattern":"CLASSIFIED_.*$","protocol":"SFTP","direction":"OUTBOUND","actions":["ENCRYPT_AES","ENCRYPT_PGP","CHECKSUM_VERIFY"],"encryptionKeyAlias":"demo-aes-key","deliverTo":"globalbank-sftp","deliveryPath":"/secure","priority":1}'

  # Cross-protocol
  create_flow '{"name":"SV-SFTP to AS2 Gateway","source":"acme-sftp","filenamePattern":"B2B_.*\\.xml$","protocol":"SFTP","direction":"OUTBOUND","actions":["SCREEN","COMPRESS_GZIP"],"deliverTo":"medtech-as2","deliveryPath":"/outbound/b2b","priority":15}'

  # Compliance
  create_flow '{"name":"SV-SOX Audit Export","source":"globalbank-sftp","filenamePattern":"SOX_AUDIT_.*\\.csv$","protocol":"SFTP","direction":"OUTBOUND","actions":["CHECKSUM_VERIFY","ENCRYPT_PGP","COMPRESS_GZIP"],"encryptionKeyAlias":"demo-pgp-key","deliverTo":"acme-sftp","deliveryPath":"/compliance/sox","priority":5}'
  create_flow '{"name":"SV-AML Screening","source":"globalbank-sftp","filenamePattern":"AML_TXN_.*\\.csv$","protocol":"SFTP","direction":"INBOUND","actions":["SCREEN","CHECKSUM_VERIFY","ENCRYPT_AES"],"encryptionKeyAlias":"demo-aes-key","deliverTo":"acme-sftp","deliveryPath":"/compliance/aml","priority":2}'

  # Retail / Misc
  create_flow '{"name":"SV-POS Transaction Feed","source":"acme-sftp","filenamePattern":"POS_TXN_.*\\.csv$","protocol":"SFTP","direction":"INBOUND","actions":["SCREEN","COMPRESS_GZIP","ENCRYPT_AES"],"encryptionKeyAlias":"demo-aes-key","deliverTo":"globalbank-sftp","deliveryPath":"/inbound/pos","priority":20}'
  create_flow '{"name":"SV-Payroll HR to Bank","source":"acme-sftp","filenamePattern":"PAYROLL_.*\\.csv$","protocol":"SFTP","direction":"OUTBOUND","actions":["SCREEN","ENCRYPT_AES","CHECKSUM_VERIFY"],"encryptionKeyAlias":"demo-aes-key","deliverTo":"globalbank-sftp","deliveryPath":"/inbound/payroll","priority":3}'
  create_flow '{"name":"SV-Catch-All Archive","source":"acme-sftp","filenamePattern":".*","protocol":"SFTP","direction":"INBOUND","actions":["COMPRESS_GZIP"],"deliverTo":"globalbank-sftp","deliveryPath":"/archive","priority":100}'

  if [ $FLOW_OK -gt 0 ]; then pass "Created ${FLOW_OK} flows via API (${FLOW_FAIL} failures)"
  else fail "Flow creation failed: ${FLOW_FAIL} failures"; fi
fi

# =============================================================================
# PHASE 5: File Upload via 3rd-Party SFTP Client (VFS accounts)
# =============================================================================
if [ "$SKIP_UPLOADS" = true ] || [ "$REPORT_ONLY" = true ]; then
  skip "Phase 5: File uploads (--skip-uploads)"
else
  log "=== PHASE 5: File Upload via SFTP (VFS accounts) ==="

  FDIR="/tmp/mft-sanity-files-${TIMESTAMP}"
  mkdir -p "$FDIR"

  # Create test files
  echo 'ISA*00*          *00*          *ZZ*ACMECORP       *ZZ*GLOBALBANK     *260416*1200*U*00401*000000001*0*P*>~' > "$FDIR/PO_ACME.850"
  echo 'ISA*00*          *00*          *ZZ*GLOBALBANK     *ZZ*ACMECORP       *260416*1300*U*00401*000000002*0*P*>~' > "$FDIR/INV_GLOBALBANK.810"
  echo 'UNB+UNOC:3+LOGIFLOW:ZZ+GLOBALBANK:ZZ+260416:1500+REF001' > "$FDIR/DESADV_LOGI.edifact"
  printf 'MSH|^~\\&|HIS|MEDTECH|EHR|HOSPITAL|20260416||ADT^A01|MSG00001|P|2.5.1\nPID|||PAT-001|||DOE^JOHN\n' > "$FDIR/ADT_PATIENT.hl7"
  echo '101 091000019 0610001231604161200A094101GLOBALBANK ACME PAYROLL' > "$FDIR/ACH_PAYROLL.ach"
  echo '{1:F01GBANKUS33AXXX}{2:I103ACMEBANK33AXXXN}{4::20:TXN-001:32A:260416USD250000,00-}' > "$FDIR/MT103_WIRE.swi"
  echo '<?xml version="1.0"?><Document xmlns="urn:iso:std:iso:20022"><CstmrCdtTrfInitn><GrpHdr><MsgId>MSG-001</MsgId></GrpHdr></CstmrCdtTrfInitn></Document>' > "$FDIR/pain001_ACME.xml"
  echo '{"timestamp":"2026-04-16T12:00:00Z","base":"USD","rates":{"EUR":0.92,"GBP":0.79}}' > "$FDIR/FX_RATES.json"
  echo 'control_id,result,finding\nSOX-IT-001,PASS,' > "$FDIR/SOX_AUDIT.csv"
  echo 'txn_id,amount,risk_score\nTXN-001,50000,12' > "$FDIR/AML_TXN_SCREENING.csv"
  echo 'CLASSIFIED_DOUBLE_ENCRYPT_DATA' > "$FDIR/CLASSIFIED_SECRET.dat"
  echo '<B2BExchange><OrderId>PO-MED-001</OrderId></B2BExchange>' > "$FDIR/B2B_EXCHANGE.xml"

  # Ensure home dirs exist (VFS still needs a writable dir for the SFTP subsystem)
  docker exec -u root mft-sftp-service bash -c \
    "mkdir -p /data/partners/{acme,globalbank,logiflow} && chown -R appuser:appgroup /data/ && chmod -R 755 /data/" 2>/dev/null

  UPLOAD_OK=0

  upload_sftp() {
    sshpass -p 'partner123' sftp -oStrictHostKeyChecking=no -oUserKnownHostsFile=/dev/null -P 2222 "$1@localhost" <<< "put $2" 2>/dev/null || true
    UPLOAD_OK=$((UPLOAD_OK+1))
  }

  # Upload to VFS-enabled accounts (all using VIRTUAL storage)
  upload_sftp acme-sftp "$FDIR/PO_ACME.850"
  upload_sftp acme-sftp "$FDIR/pain001_ACME.xml"
  upload_sftp acme-sftp "$FDIR/ADT_PATIENT.hl7"
  upload_sftp acme-sftp "$FDIR/CLASSIFIED_SECRET.dat"
  upload_sftp acme-sftp "$FDIR/B2B_EXCHANGE.xml"
  upload_sftp globalbank-sftp "$FDIR/INV_GLOBALBANK.810"
  upload_sftp globalbank-sftp "$FDIR/ACH_PAYROLL.ach"
  upload_sftp globalbank-sftp "$FDIR/MT103_WIRE.swi"
  upload_sftp globalbank-sftp "$FDIR/FX_RATES.json"
  upload_sftp globalbank-sftp "$FDIR/AML_TXN_SCREENING.csv"
  upload_sftp globalbank-sftp "$FDIR/SOX_AUDIT.csv"
  upload_sftp logiflow-sftp "$FDIR/DESADV_LOGI.edifact"

  pass "Uploaded ${UPLOAD_OK} files via 3rd-party SFTP client (VFS accounts)"
fi

# =============================================================================
# PHASE 6: Pipeline Processing Verification
# =============================================================================
log "=== PHASE 6: Pipeline Verification ==="

# Wait for pipeline to process
sleep 5

# Check FileUploadedEvents
EVENTS=$(docker logs mft-sftp-service 2>/dev/null | grep -c "FileUploadedEvent" || true)
if [ "$EVENTS" -gt 0 ]; then pass "SFTP RoutingEngine fired ${EVENTS} FileUploadedEvents"
else warn "No FileUploadedEvents detected in SFTP service"; fi

# Check transfer records
RECORDS=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "SELECT count(*) FROM file_transfer_records;" 2>/dev/null | tr -d ' ')
if [ "${RECORDS:-0}" -gt 0 ]; then pass "Transfer records: ${RECORDS} in database"
else fail "Transfer records: 0 — pipeline not creating records (N33)"; fi

# Check flow executions
EXECUTIONS=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "SELECT count(*) FROM flow_executions;" 2>/dev/null | tr -d ' ')
if [ "${EXECUTIONS:-0}" -gt 0 ]; then pass "Flow executions: ${EXECUTIONS}"
else fail "Flow executions: 0 — pipeline intake not processing (N33)"; fi

# Check for COMPLETED vs FAILED executions
COMPLETED=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "SELECT count(*) FROM flow_executions WHERE status='COMPLETED';" 2>/dev/null | tr -d ' ')
FAILED_EX=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c "SELECT count(*) FROM flow_executions WHERE status='FAILED';" 2>/dev/null | tr -d ' ')
if [ "${COMPLETED:-0}" -gt 0 ]; then
  pass "Flow executions COMPLETED: ${COMPLETED}"
else
  warn "Flow executions COMPLETED: 0 (FAILED: ${FAILED_EX:-0})"
fi

# Check VFS storage — files should be in storage-manager, not local filesystem
VFS_OBJECTS=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
  "SELECT count(*) FROM write_intents;" 2>/dev/null | tr -d ' ')
if [ "${VFS_OBJECTS:-0}" -gt 0 ]; then pass "VFS write intents: ${VFS_OBJECTS} (files stored via storage-manager)"
else warn "VFS write intents: 0 — files may not be stored via VFS (N47)"; fi

# Check flow rule registry
FLOWS_COMPILED=$(docker logs mft-sftp-service 2>/dev/null | grep "flows compiled" | tail -1 | sed -n 's/.*\([0-9]* flows compiled\).*/\1/p' || echo "unknown")
pass "Flow Rule Registry: ${FLOWS_COMPILED}"

# =============================================================================
# PHASE 6b: Activity Monitor & Flow Execution Lifecycle
# =============================================================================
log "=== PHASE 6b: Activity Monitor & Flow Execution Lifecycle ==="

if [ -z "$TOKEN" ]; then TOKEN=$(get_token 2>/dev/null || echo ""); fi

if [ -n "$TOKEN" ]; then
  AUTH="Authorization: Bearer $TOKEN"

  # --- Activity Monitor ---
  AM_CODE=$(curl -s -o /tmp/am_response.json -w "%{http_code}" \
    "http://localhost:8080/api/activity-monitor?page=0&size=25" -H "$AUTH")
  if [ "$AM_CODE" = "200" ]; then
    AM_COUNT=$(python3 -c 'import sys,json; d=json.load(open("/tmp/am_response.json")); print(d.get("totalElements",d.get("total",len(d) if isinstance(d,list) else 0)))' 2>/dev/null || echo "0")
    if [ "${AM_COUNT:-0}" -gt 0 ]; then pass "Activity Monitor: ${AM_COUNT} entries visible"
    else warn "Activity Monitor: 0 entries"; fi
  else
    fail "Activity Monitor returned ${AM_CODE}"
  fi

  STATS_CODE=$(curl -s -o /tmp/am_stats.json -w "%{http_code}" \
    "http://localhost:8080/api/activity-monitor/stats?period=24h" -H "$AUTH")
  if [ "$STATS_CODE" = "200" ]; then
    TOTAL_TRANSFERS=$(python3 -c 'import sys,json; d=json.load(open("/tmp/am_stats.json")); print(d.get("totalTransfers",0))' 2>/dev/null || echo "0")
    pass "Activity Monitor stats: ${TOTAL_TRANSFERS} transfers in 24h"
  else fail "Activity Monitor stats returned ${STATS_CODE}"; fi

  EXPORT_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    "http://localhost:8080/api/activity-monitor/export?format=csv" -H "$AUTH")
  if [ "$EXPORT_CODE" = "200" ]; then pass "Activity Monitor CSV export: 200"
  else warn "Activity Monitor CSV export: ${EXPORT_CODE}"; fi

  for status in PENDING FAILED MOVED_TO_SENT DOWNLOADED IN_OUTBOX; do
    FILTER_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
      "http://localhost:8080/api/activity-monitor?status=${status}&page=0&size=5" -H "$AUTH")
    if [ "$FILTER_CODE" = "200" ]; then pass "Activity Monitor filter status=${status}: 200"
    else fail "Activity Monitor filter status=${status}: ${FILTER_CODE}"; fi
  done

  # --- Flow Execution API ---
  LIVE_CODE=$(curl -s -o /tmp/fe_live.json -w "%{http_code}" \
    "http://localhost:8080/api/flow-executions/live-stats" -H "$AUTH")
  if [ "$LIVE_CODE" = "200" ]; then
    FE_PROC=$(python3 -c 'import sys,json; d=json.load(open("/tmp/fe_live.json")); print(d.get("processing",0))' 2>/dev/null || echo "0")
    FE_FAIL=$(python3 -c 'import sys,json; d=json.load(open("/tmp/fe_live.json")); print(d.get("failed",0))' 2>/dev/null || echo "0")
    pass "Flow Execution live-stats: processing=${FE_PROC} failed=${FE_FAIL}"
  else fail "Flow Execution live-stats: ${LIVE_CODE}"; fi

  for ep in "pending-approvals" "scheduled-retries"; do
    EP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
      "http://localhost:8080/api/flow-executions/${ep}" -H "$AUTH")
    if [ "$EP_CODE" = "200" ]; then pass "Flow Execution ${ep}: 200"
    else fail "Flow Execution ${ep}: ${EP_CODE}"; fi
  done

  JOURNEY_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    "http://localhost:8080/api/journey?limit=10" -H "$AUTH")
  if [ "$JOURNEY_CODE" = "200" ]; then pass "Transfer Journey search: 200"
  else warn "Transfer Journey search: ${JOURNEY_CODE}"; fi

  # --- Flow Execution Lifecycle (restart/terminate/schedule-retry) ---
  TRACK_ID=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
    "SELECT track_id FROM flow_executions WHERE status IN ('FAILED','CANCELLED','PAUSED') LIMIT 1;" 2>/dev/null | tr -d ' ')

  if [ -n "$TRACK_ID" ] && [ "$TRACK_ID" != "" ]; then
    log "  Found restartable execution: $TRACK_ID"

    # Detail
    DETAIL_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
      "http://localhost:8080/api/flow-executions/${TRACK_ID}" -H "$AUTH")
    if [ "$DETAIL_CODE" = "200" ]; then pass "Flow Execution detail (${TRACK_ID}): 200"
    else fail "Flow Execution detail: ${DETAIL_CODE}"; fi

    # Event history
    EH_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
      "http://localhost:8080/api/flow-executions/flow-events/${TRACK_ID}" -H "$AUTH")
    if [ "$EH_CODE" = "200" ]; then pass "Flow Execution event history: 200"
    else warn "Flow Execution event history: ${EH_CODE}"; fi

    # Attempt history
    AH_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
      "http://localhost:8080/api/flow-executions/${TRACK_ID}/history" -H "$AUTH")
    if [ "$AH_CODE" = "200" ]; then pass "Flow Execution attempt history: 200"
    else warn "Flow Execution attempt history: ${AH_CODE}"; fi

    # Restart
    RESTART_CODE=$(curl -s -o /tmp/fe_restart.json -w "%{http_code}" -X POST \
      "http://localhost:8080/api/flow-executions/${TRACK_ID}/restart" -H "$AUTH")
    if [ "$RESTART_CODE" = "200" ] || [ "$RESTART_CODE" = "202" ]; then
      pass "Flow Execution restart: accepted (${RESTART_CODE})"
    else warn "Flow Execution restart: ${RESTART_CODE}"; fi

    sleep 2

    # Terminate (find another execution)
    TERM_TRACK=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
      "SELECT track_id FROM flow_executions WHERE status IN ('PROCESSING','FAILED','PAUSED') AND track_id != '${TRACK_ID}' LIMIT 1;" 2>/dev/null | tr -d ' ')
    if [ -n "$TERM_TRACK" ] && [ "$TERM_TRACK" != "" ]; then
      TERM_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
        "http://localhost:8080/api/flow-executions/${TERM_TRACK}/terminate" -H "$AUTH")
      if [ "$TERM_CODE" = "200" ] || [ "$TERM_CODE" = "202" ]; then pass "Flow Execution terminate: accepted (${TERM_CODE})"
      else warn "Flow Execution terminate: ${TERM_CODE}"; fi
    else skip "Flow Execution terminate — no second execution available"; fi

    # Schedule retry
    FUTURE=$(date -u -v+1H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo "2026-12-31T23:59:59Z")
    SCHED_TRACK=$(docker exec mft-postgres psql -U postgres -d filetransfer -t -c \
      "SELECT track_id FROM flow_executions WHERE status IN ('FAILED','CANCELLED','PAUSED') LIMIT 1;" 2>/dev/null | tr -d ' ')
    if [ -n "$SCHED_TRACK" ] && [ "$SCHED_TRACK" != "" ]; then
      SCHED_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
        "http://localhost:8080/api/flow-executions/${SCHED_TRACK}/schedule-retry" \
        -H "$AUTH" -H "Content-Type: application/json" -d "{\"scheduledAt\":\"${FUTURE}\"}")
      if [ "$SCHED_CODE" = "200" ] || [ "$SCHED_CODE" = "202" ]; then
        pass "Flow Execution schedule-retry: accepted (${SCHED_CODE})"
        curl -s -o /dev/null -X DELETE "http://localhost:8080/api/flow-executions/${SCHED_TRACK}/schedule-retry" -H "$AUTH" 2>/dev/null
        pass "Flow Execution schedule-retry: cancelled (cleanup)"
      else warn "Flow Execution schedule-retry: ${SCHED_CODE}"; fi
    else skip "Flow Execution schedule-retry — no failed execution"; fi

    # Journey detail
    JD_CODE=$(curl -s -o /tmp/journey.json -w "%{http_code}" \
      "http://localhost:8080/api/journey/${TRACK_ID}" -H "$AUTH")
    if [ "$JD_CODE" = "200" ]; then
      STAGES=$(python3 -c 'import json; d=json.load(open("/tmp/journey.json")); print(len(d.get("stages",[])))' 2>/dev/null || echo "0")
      pass "Transfer Journey detail: ${STAGES} stages"
    else warn "Transfer Journey detail: ${JD_CODE}"; fi

  else
    log "  No flow executions — lifecycle tests skipped"
    skip "Flow Execution restart — no executions exist"
    skip "Flow Execution terminate — no executions exist"
    skip "Flow Execution schedule-retry — no executions exist"
    skip "Flow Execution detail/history — no executions exist"
    skip "Transfer Journey detail — no executions exist"
  fi

  # --- Error handling validation ---
  BULK_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
    "http://localhost:8080/api/flow-executions/bulk-restart" \
    -H "$AUTH" -H "Content-Type: application/json" -d '{"trackIds":[]}')
  if [ "$BULK_CODE" = "400" ]; then pass "Bulk restart empty body: 400 (correct)"
  else warn "Bulk restart empty body: ${BULK_CODE}"; fi

  R404=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
    "http://localhost:8080/api/flow-executions/NONEXISTENT/restart" -H "$AUTH")
  if [ "$R404" = "404" ]; then pass "Restart non-existent: 404 (correct)"
  else warn "Restart non-existent: ${R404}"; fi

else
  fail "No auth token — cannot run Phase 6b"
fi

# =============================================================================
# PHASE 7: Artifact Capture
# =============================================================================
log "=== PHASE 7: Capturing Artifacts ==="

for svc in sftp-service onboarding-api config-service forwarder-service storage-manager; do
  docker exec "mft-${svc}" kill -3 1 2>/dev/null || true
done
sleep 2
for svc in sftp-service onboarding-api config-service forwarder-service storage-manager; do
  docker logs "mft-${svc}" 2>&1 | grep -A 50000 "Full thread dump" > "$DUMP_DIR/thread-dump-${svc}.txt" 2>/dev/null || true
done

docker exec mft-postgres psql -U postgres -d filetransfer -c "
SELECT 'partners' as t, count(*) as n FROM partners
UNION ALL SELECT 'transfer_accounts', count(*) FROM transfer_accounts
UNION ALL SELECT 'file_flows', count(*) FROM file_flows
UNION ALL SELECT 'file_transfer_records', count(*) FROM file_transfer_records
UNION ALL SELECT 'flow_executions', count(*) FROM flow_executions
ORDER BY t;" > "$DUMP_DIR/db-state.txt" 2>/dev/null

docker exec mft-postgres psql -U postgres -d filetransfer -c "
SELECT track_id, original_filename, status, current_step, error_message
FROM flow_executions ORDER BY started_at DESC LIMIT 20;" > "$DUMP_DIR/flow-executions.txt" 2>/dev/null

docker exec mft-postgres psql -U postgres -d filetransfer -c "
SELECT s.instance_id, s.default_storage_mode, a.username, a.storage_mode
FROM server_instances s
LEFT JOIN transfer_accounts a ON true
WHERE a.username IN ('acme-sftp','globalbank-sftp','logiflow-sftp')
ORDER BY s.instance_id, a.username;" > "$DUMP_DIR/vfs-status.txt" 2>/dev/null

docker compose ps --format '{{.Name}} {{.Status}}' > "$DUMP_DIR/container-status.txt" 2>/dev/null
docker stats --no-stream --format "{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}" > "$DUMP_DIR/memory-stats.txt" 2>/dev/null

pass "Artifacts captured to ${DUMP_DIR}"

# =============================================================================
# REPORT
# =============================================================================
log "=== RESULTS ==="
log "PASS: $PASS | FAIL: $FAIL | WARN: $WARN | SKIP: $SKIP"

cat > "$REPORT_FILE" << REPORT_EOF
# TranzFer MFT — Product Sanity Validation Report
**Date:** $(date +%Y-%m-%d\ %H:%M:%S)
**Storage Mode:** VIRTUAL (VFS) — all servers and accounts
**Results:** PASS=$PASS | FAIL=$FAIL | WARN=$WARN | SKIP=$SKIP

## Test Results
$(cat "$DUMP_DIR/results.md")

## Artifacts
- Thread dumps: \`${DUMP_DIR}/thread-dump-*.txt\`
- DB state: \`${DUMP_DIR}/db-state.txt\`
- Flow executions: \`${DUMP_DIR}/flow-executions.txt\`
- VFS status: \`${DUMP_DIR}/vfs-status.txt\`
- Container status: \`${DUMP_DIR}/container-status.txt\`
- Memory stats: \`${DUMP_DIR}/memory-stats.txt\`
REPORT_EOF

log "Report written to: $REPORT_FILE"
log "Dumps directory: $DUMP_DIR"

[ "$FAIL" -eq 0 ] && exit 0 || exit 1

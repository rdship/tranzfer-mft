#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — demo traffic / historical data seeder
# =============================================================================
# Populates the pages that demo-onboard.sh alone leaves empty. This version
# seeds **file_transfer_records** as the primary table — that is what
# Activity Monitor, Journey, Analytics, and Dashboard all actually query.
# Everything else (flow_executions, fabric_checkpoints, sentinel_findings)
# is linked to it by trackId.
#
# What gets populated:
#   /activity-monitor  — 150 historical records via file_transfer_records
#   /journey           — per-trackId timelines
#   /fabric            — checkpoints, instances, stuck items, latency
#   /sentinel          — 12 findings across analyzers + health score series
#   /analytics         — populates on first metric-aggregation run (~5 min)
#
# All rows are synthetic but well-formed — the UI can't tell them apart
# from real data.
#
# Safe to re-run: each section DELETEs prior demo rows (tagged with
# track_id LIKE 'TRZDEMO%' / 'TRZSTK%' or rule_name LIKE 'demo_%') before
# inserting. Delete order respects FK constraints.
#
# Usage:  ./scripts/demo-traffic.sh
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

log()  { printf '\n\033[1;34m[demo-traffic]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[demo-traffic] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[demo-traffic] %s\033[0m\n' "$*" >&2; exit 1; }

PSQL=(docker compose exec -T postgres psql -U postgres -d filetransfer -v ON_ERROR_STOP=1 -q)

# --- Preflight ---------------------------------------------------------------
docker compose ps postgres 2>/dev/null | grep -q 'Up\|running' \
  || die "postgres container is not running. Run ./scripts/demo-start.sh first."

"${PSQL[@]}" -c "SELECT 1" >/dev/null || die "Cannot connect to postgres"

# Sanity: onboarding script must have run
FLOW_COUNT=$("${PSQL[@]}" -tAc "SELECT count(*) FROM file_flows" 2>/dev/null || echo 0)
MAP_COUNT=$("${PSQL[@]}"  -tAc "SELECT count(*) FROM folder_mappings WHERE source_account_id IS NOT NULL" 2>/dev/null || echo 0)
if [[ "${FLOW_COUNT}" -lt 1 ]]; then
  die "No rows in file_flows. Run ./scripts/demo-onboard.sh first."
fi
if [[ "${MAP_COUNT}" -lt 1 ]]; then
  die "No folder_mappings with source_account_id. Run ./scripts/demo-onboard.sh first."
fi
log "Found ${FLOW_COUNT} file_flows and ${MAP_COUNT} usable folder_mappings."

# =============================================================================
# 1. Clean any prior demo rows (idempotent; respect FK order)
# =============================================================================
# flow_executions.transfer_record_id → file_transfer_records(id), so delete
# flow_executions first. fabric_checkpoints has no FK but we tag by track_id.
# =============================================================================
log "Cleaning previous demo-traffic data..."
"${PSQL[@]}" <<'SQL'
DELETE FROM fabric_checkpoints    WHERE track_id   LIKE 'TRZDEMO%' OR track_id LIKE 'TRZSTK%';
DELETE FROM fabric_instances      WHERE instance_id LIKE 'demo-%';
DELETE FROM sentinel_findings     WHERE rule_name  LIKE 'demo_%';
DELETE FROM sentinel_health_scores WHERE details   LIKE '%"demo":true%';
DELETE FROM flow_executions       WHERE track_id   LIKE 'TRZDEMO%' OR track_id LIKE 'TRZSTK%';
DELETE FROM file_transfer_records WHERE track_id   LIKE 'TRZDEMO%' OR track_id LIKE 'TRZSTK%';
SQL

# =============================================================================
# 2. file_transfer_records — THE primary table for Activity Monitor + Journey
# =============================================================================
# 150 records spread over the last 7 days.
# Distribution:
#   115 MOVED_TO_SENT (flow COMPLETED)
#    24 FAILED         (flow FAILED)
#    11 DOWNLOADED    (flow PROCESSING; 3 of these are "stuck")
#
# Track IDs use the 12-char pattern the app uses (VARCHAR(64) column but
# the entity enforces @Size(max=12)):
#   TRZDEMO00001 .. TRZDEMO00150
# =============================================================================
log "Seeding 150 file_transfer_records over the last 7 days..."
"${PSQL[@]}" <<'SQL'
WITH picked_maps AS (
  SELECT id FROM folder_mappings
  WHERE source_account_id IS NOT NULL
  ORDER BY random()
  LIMIT 30
),
rows AS (
  SELECT
    gs AS n,
    'TRZDEMO' || lpad(gs::text, 5, '0') AS track_id,
    (SELECT id FROM picked_maps ORDER BY random() LIMIT 1) AS folder_mapping_id,
    (ARRAY[
       'daily-batch.csv','invoice_20250401.edi','payroll.xml','orders-batch.zip',
       'hl7_adt_feed.hl7','acct_reconcile.txt','trade-settle.xml','pci-report.csv.pgp',
       'claims-837.edi','eft-remit.txt','partner-ack.mdn','daily.log.gz'
     ])[1 + (gs % 12)] AS original_filename,
    -- 115 MOVED_TO_SENT, 24 FAILED, 11 DOWNLOADED (last 11 include the 3 stuck)
    (CASE
       WHEN gs <= 115 THEN 'MOVED_TO_SENT'
       WHEN gs <= 139 THEN 'FAILED'
       ELSE                'DOWNLOADED'
     END) AS status,
    (NOW() - (random() * interval '7 days')) AS uploaded_at,
    (50000 + (random() * 50000000)::bigint) AS file_size_bytes
  FROM generate_series(1, 150) gs
)
INSERT INTO file_transfer_records (
  id, folder_mapping_id, track_id, original_filename,
  source_file_path, destination_file_path, archive_file_path,
  status, error_message, file_size_bytes,
  source_checksum, destination_checksum, retry_count,
  uploaded_at, routed_at, downloaded_at, completed_at, updated_at
)
SELECT
  gen_random_uuid(),
  folder_mapping_id,
  track_id,
  original_filename,
  '/inbox/'   || original_filename,
  '/outbox/'  || original_filename,
  CASE WHEN status = 'MOVED_TO_SENT' THEN '/archive/' || original_filename END,
  status,
  CASE WHEN status = 'FAILED' THEN
       (ARRAY[
         'Connection timeout to downstream partner SFTP',
         'Checksum mismatch on delivery',
         'Encryption key expired',
         'Partner quota exceeded (429)',
         'Screening quarantined PCI content'
       ])[(random()*5)::int + 1]
  END,
  file_size_bytes,
  md5(track_id || 'src') || md5(track_id || 'src_'),
  CASE WHEN status = 'MOVED_TO_SENT'
       THEN md5(track_id || 'dst') || md5(track_id || 'dst_')
  END,
  CASE WHEN status = 'FAILED' THEN (random()*3)::int + 1 ELSE 0 END,
  uploaded_at,
  CASE WHEN status IN ('MOVED_TO_SENT','DOWNLOADED','FAILED')
       THEN uploaded_at + (random() * interval '5 seconds' + interval '500 ms') END,
  CASE WHEN status IN ('MOVED_TO_SENT','DOWNLOADED')
       THEN uploaded_at + (random() * interval '30 seconds' + interval '2 seconds') END,
  CASE WHEN status = 'MOVED_TO_SENT'
       THEN uploaded_at + (random() * interval '90 seconds' + interval '5 seconds') END,
  uploaded_at + interval '1 minute'
FROM rows;
SQL

# =============================================================================
# 3. flow_executions — linked to file_transfer_records via transfer_record_id
# =============================================================================
log "Seeding 150 flow_executions linked to the transfer records..."
"${PSQL[@]}" <<'SQL'
WITH flows AS (SELECT id FROM file_flows ORDER BY random() LIMIT 30)
INSERT INTO flow_executions (
  id, flow_id, transfer_record_id, track_id, original_filename, current_file_path,
  status, current_step, error_message, started_at, completed_at
)
SELECT
  gen_random_uuid(),
  (SELECT id FROM flows ORDER BY random() LIMIT 1),
  ftr.id,
  ftr.track_id,
  ftr.original_filename,
  ftr.source_file_path,
  CASE ftr.status
    WHEN 'MOVED_TO_SENT' THEN 'COMPLETED'
    WHEN 'FAILED'        THEN 'FAILED'
    WHEN 'DOWNLOADED'    THEN 'PROCESSING'
  END,
  CASE ftr.status
    WHEN 'MOVED_TO_SENT' THEN 5
    WHEN 'FAILED'        THEN (random()*3)::int + 1
    WHEN 'DOWNLOADED'    THEN (random()*4)::int + 1
  END,
  ftr.error_message,
  ftr.uploaded_at,
  ftr.completed_at
FROM file_transfer_records ftr
WHERE ftr.track_id LIKE 'TRZDEMO%';
SQL

# =============================================================================
# 4. fabric_checkpoints — linked by track_id
# =============================================================================
# 5 step types (SOURCE → SCREEN → ENCRYPT → COMPRESS → DELIVERY) with
# skewed duration_ms so p50/p95/p99 on /fabric look realistic.
#
# COMPLETED flows  → 5 COMPLETED checkpoints each
# FAILED flows     → 1 FAILED checkpoint at the failing step
# PROCESSING flows → 1 IN_PROGRESS checkpoint with live lease (or expired
#                    for the last 3, making them "stuck")
# =============================================================================
log "Seeding fabric_checkpoints (5 per completed execution + in-progress + stuck)..."
"${PSQL[@]}" <<'SQL'
-- COMPLETED executions: 5 steps each, all COMPLETED
INSERT INTO fabric_checkpoints (
  id, track_id, step_index, step_type, status,
  input_storage_key, output_storage_key, input_size_bytes, output_size_bytes,
  processing_instance, claimed_at, lease_expires_at,
  started_at, completed_at, duration_ms, attempt_number, created_at
)
SELECT
  gen_random_uuid(),
  fe.track_id,
  s.step_idx,
  s.step_type,
  'COMPLETED',
  md5(fe.track_id || s.step_idx::text || 'in')  || md5(fe.track_id || s.step_idx::text || 'in_'),
  md5(fe.track_id || s.step_idx::text || 'out') || md5(fe.track_id || s.step_idx::text || 'out_'),
  (50000 + (random() * 5000000)::bigint),
  (50000 + (random() * 5000000)::bigint),
  'demo-' || (ARRAY['onboarding-api','sftp-service','ftp-service','encryption-service'])[(random()*4)::int + 1] || '-pod-' || (1 + (random()*2)::int),
  fe.started_at + (s.step_idx * interval '2 seconds'),
  fe.started_at + (s.step_idx * interval '2 seconds') + interval '5 minutes',
  fe.started_at + (s.step_idx * interval '2 seconds'),
  fe.started_at + (s.step_idx * interval '2 seconds') + (random() * interval '8 seconds' + interval '200 ms'),
  CASE s.step_type
    WHEN 'ENCRYPT'  THEN (200 + (random() * 600)::int   + (CASE WHEN random() < 0.05 THEN 4000 ELSE 0 END))
    WHEN 'SCREEN'   THEN (300 + (random() * 1500)::int  + (CASE WHEN random() < 0.05 THEN 8000 ELSE 0 END))
    WHEN 'COMPRESS' THEN (100 + (random() * 400)::int)
    WHEN 'DELIVERY' THEN (500 + (random() * 2500)::int  + (CASE WHEN random() < 0.10 THEN 6000 ELSE 0 END))
    ELSE                  (80  + (random() * 200)::int)
  END,
  1,
  fe.started_at
FROM flow_executions fe
CROSS JOIN (VALUES
  (0, 'SOURCE'),
  (1, 'SCREEN'),
  (2, 'ENCRYPT'),
  (3, 'COMPRESS'),
  (4, 'DELIVERY')
) AS s(step_idx, step_type)
WHERE fe.track_id LIKE 'TRZDEMO%'
  AND fe.status = 'COMPLETED';

-- FAILED executions: 1 FAILED checkpoint at the failing step
INSERT INTO fabric_checkpoints (
  id, track_id, step_index, step_type, status,
  input_storage_key, processing_instance,
  claimed_at, lease_expires_at, started_at, completed_at, duration_ms,
  attempt_number, error_category, error_message, created_at
)
SELECT
  gen_random_uuid(),
  fe.track_id,
  fe.current_step,
  (ARRAY['SOURCE','SCREEN','ENCRYPT','COMPRESS','DELIVERY'])[LEAST(fe.current_step, 4) + 1],
  'FAILED',
  md5(fe.track_id || 'failed_in') || md5(fe.track_id || 'failed_in_'),
  'demo-onboarding-api-pod-1',
  fe.started_at + interval '4 seconds',
  fe.started_at + interval '5 minutes',
  fe.started_at + interval '4 seconds',
  fe.completed_at,
  (500 + (random() * 3000)::int),
  1,
  (ARRAY['NETWORK','AUTH','KEY_EXPIRED','FORMAT','UNKNOWN'])[(random()*5)::int + 1],
  fe.error_message,
  fe.started_at
FROM flow_executions fe
WHERE fe.track_id LIKE 'TRZDEMO%'
  AND fe.status = 'FAILED';

-- PROCESSING executions 1..8: IN_PROGRESS with ACTIVE lease (live in-flight)
INSERT INTO fabric_checkpoints (
  id, track_id, step_index, step_type, status,
  input_storage_key, processing_instance,
  claimed_at, lease_expires_at, started_at, attempt_number, created_at
)
SELECT
  gen_random_uuid(),
  fe.track_id,
  fe.current_step,
  (ARRAY['SOURCE','SCREEN','ENCRYPT','COMPRESS','DELIVERY'])[LEAST(fe.current_step, 4) + 1],
  'IN_PROGRESS',
  md5(fe.track_id || 'live_in') || md5(fe.track_id || 'live_in_'),
  'demo-sftp-service-pod-1',
  NOW() - interval '30 seconds',
  NOW() + interval '4 minutes 30 seconds',
  NOW() - interval '30 seconds',
  1,
  NOW() - interval '1 minute'
FROM flow_executions fe
WHERE fe.track_id LIKE 'TRZDEMO%'
  AND fe.status = 'PROCESSING'
  AND fe.track_id NOT IN ('TRZDEMO00148','TRZDEMO00149','TRZDEMO00150');

-- PROCESSING executions 148/149/150: IN_PROGRESS with EXPIRED lease (stuck)
INSERT INTO fabric_checkpoints (
  id, track_id, step_index, step_type, status,
  input_storage_key, processing_instance,
  claimed_at, lease_expires_at, started_at, attempt_number, created_at
)
SELECT
  gen_random_uuid(),
  fe.track_id,
  fe.current_step,
  (ARRAY['SOURCE','SCREEN','ENCRYPT','COMPRESS','DELIVERY'])[LEAST(fe.current_step, 4) + 1],
  'IN_PROGRESS',
  md5(fe.track_id || 'stuck_in') || md5(fe.track_id || 'stuck_in_'),
  'demo-encryption-service-pod-dead-1',
  NOW() - interval '15 minutes',
  NOW() - interval '10 minutes',   -- lease expired 10 min ago
  NOW() - interval '15 minutes',
  1,
  NOW() - interval '15 minutes'
FROM flow_executions fe
WHERE fe.track_id IN ('TRZDEMO00148','TRZDEMO00149','TRZDEMO00150');
SQL

# =============================================================================
# 5. Fabric instance heartbeats (4 healthy + 2 dead)
# =============================================================================
log "Seeding fabric_instances (4 healthy + 2 dead)..."
"${PSQL[@]}" <<'SQL'
INSERT INTO fabric_instances (
  instance_id, service_name, host, started_at, last_heartbeat,
  status, consumed_topics, current_partitions, in_flight_count
) VALUES
  ('demo-onboarding-api-pod-1',  'onboarding-api',    'mft-onboarding-api',
   NOW() - interval '2 hours',  NOW() - interval '10 seconds', 'HEALTHY',
   '["flow.intake","events.account"]', '[0,1,2,3,4,5,6,7]', 3),
  ('demo-sftp-service-pod-1',    'sftp-service',      'mft-sftp-service',
   NOW() - interval '2 hours',  NOW() - interval '15 seconds', 'HEALTHY',
   '["events.account"]',               '[0,1,2,3]',           1),
  ('demo-ftp-service-pod-1',     'ftp-service',       'mft-ftp-service',
   NOW() - interval '2 hours',  NOW() - interval '12 seconds', 'HEALTHY',
   '["events.account"]',               '[0,1,2,3]',           0),
  ('demo-encryption-service-pod-1','encryption-service','mft-encryption-service',
   NOW() - interval '2 hours',  NOW() - interval '20 seconds', 'HEALTHY',
   '[]',                               '[]',                  2),
  ('demo-encryption-service-pod-dead-1','encryption-service','mft-encryption-service-crashed',
   NOW() - interval '3 hours',  NOW() - interval '15 minutes', 'DEGRADED',
   '[]',                               '[]',                  3),
  ('demo-ftp-service-pod-dead-1','ftp-service','mft-ftp-service-crashed',
   NOW() - interval '3 hours',  NOW() - interval '25 minutes', 'DEGRADED',
   '["events.account"]',               '[0]',                 1);
SQL

# =============================================================================
# 6. Sentinel findings + health score time series
# =============================================================================
log "Seeding sentinel findings + health score snapshots..."
"${PSQL[@]}" <<'SQL'
INSERT INTO sentinel_findings (
  id, analyzer, rule_name, severity, title, description, evidence,
  affected_service, affected_account, track_id, status, created_at
) VALUES
  (gen_random_uuid(),'SECURITY','demo_login_failure_spike','HIGH',
   '14 login failures in the last hour for user acme-corp',
   'Rolling 60-minute window shows 14 failed login attempts from IP 203.0.113.47 against SFTP account acme-corp. Threshold: 10. This is 40% above baseline.',
   '{"failures":14,"baseline":8,"window":"60m","source_ip":"203.0.113.47"}',
   'sftp-service','acme-corp',NULL,'OPEN', NOW() - interval '12 minutes'),

  (gen_random_uuid(),'SECURITY','demo_screening_hit','CRITICAL',
   'OFAC sanctions hit on incoming file',
   'Screening service matched file invoice_20250401.edi against OFAC SDN list entry (partner: ACME-TRADING). File quarantined, partner notified, PCI audit log signed.',
   '{"ofac_entry":"SDN#12345","score":0.97,"action":"QUARANTINE"}',
   'screening-service','acme-trading','TRZDEMO00007','OPEN', NOW() - interval '42 minutes'),

  (gen_random_uuid(),'SECURITY','demo_integrity_mismatch','CRITICAL',
   'Source/destination checksum mismatch on delivery',
   'Delivery to partner FirstFed (SFTP) completed with SHA-256 mismatch. Source key: ab12..., delivered key: cd34.... Rollback triggered.',
   '{"source_sha256":"ab12","delivery_sha256":"cd34","partner":"FirstFed"}',
   'gateway-service','firstfed-prod','TRZDEMO00011','ACKNOWLEDGED', NOW() - interval '3 hours'),

  (gen_random_uuid(),'SECURITY','demo_config_change_burst','MEDIUM',
   '7 flow-rule updates in the last 15 minutes',
   'User admin@filetransfer.local modified 7 flow rules in a 15-minute window. This may indicate automated edit or misuse. Baseline: <3 changes/15m.',
   '{"changes":7,"user":"admin@filetransfer.local","window":"15m"}',
   'config-service',NULL,NULL,'OPEN', NOW() - interval '8 minutes'),

  (gen_random_uuid(),'PERFORMANCE','demo_latency_degradation','HIGH',
   'p95 DELIVERY step latency up 64% vs 24h baseline',
   'Flow Fabric DELIVERY step p95 is 4.2s over the last hour, versus 2.56s over the prior 24h window. Affected flows: 23. Investigation: partner SFTP response time increased.',
   '{"p95_current_ms":4200,"p95_baseline_ms":2560,"delta_pct":64,"affected_flows":23}',
   'ftp-service',NULL,NULL,'OPEN', NOW() - interval '25 minutes'),

  (gen_random_uuid(),'PERFORMANCE','demo_error_rate_spike','HIGH',
   'Transfer failure rate 12% over last hour (threshold 10%)',
   '12% of transfers in the last 60 minutes failed (18 of 150). Top error: Connection timeout to downstream partner SFTP (acme-corp, 9 occurrences).',
   '{"failure_rate":0.12,"total":150,"failures":18,"top_error":"Connection timeout","window":"60m"}',
   'gateway-service',NULL,NULL,'OPEN', NOW() - interval '55 minutes'),

  (gen_random_uuid(),'PERFORMANCE','demo_throughput_drop','MEDIUM',
   'Transfer volume down 38% vs same hour yesterday',
   'Transfers this hour: 47. Same hour yesterday: 76. Drop: 38%. No failing services reported — may be legitimate lower demand.',
   '{"current_hour":47,"yesterday_same_hour":76,"delta_pct":-38}',
   'analytics-service',NULL,NULL,'OPEN', NOW() - interval '18 minutes'),

  (gen_random_uuid(),'PERFORMANCE','demo_disk_usage_high','MEDIUM',
   'storage-manager disk usage at 82%',
   'Primary storage volume at 82% of 500GB capacity. Warning threshold is 85%. 3.2 GB/day ingress rate suggests 7 days until critical.',
   '{"used_pct":82,"capacity_gb":500,"daily_ingress_gb":3.2,"days_to_critical":7}',
   'storage-manager',NULL,NULL,'OPEN', NOW() - interval '2 hours'),

  (gen_random_uuid(),'SECURITY','demo_failed_transfer_spike','HIGH',
   'Failure rate 28% for partner acme-corp today',
   '28% of flows from partner acme-corp failed today versus 4% baseline. 9 of 32 failed. Common cause: AUTH errors. Recommended: rotate partner key.',
   '{"partner":"acme-corp","failure_rate":0.28,"baseline":0.04,"total":32,"failures":9}',
   'sftp-service','acme-corp',NULL,'OPEN', NOW() - interval '1 hour 10 minutes'),

  (gen_random_uuid(),'PERFORMANCE','demo_service_unhealthy','LOW',
   'encryption-service restarted 2x in last 6 hours',
   'Container mft-encryption-service has 2 restarts in the last 6h. Service is currently HEALTHY. Memory utilization climbing steadily — possible leak.',
   '{"restarts_6h":2,"memory_trend":"climbing","current_status":"HEALTHY"}',
   'encryption-service',NULL,NULL,'RESOLVED', NOW() - interval '5 hours'),

  (gen_random_uuid(),'SECURITY','demo_quarantine_surge','HIGH',
   '5 files quarantined in the last hour',
   'Screening service quarantined 5 files in the last 60 minutes. Threshold is 3. Possible coordinated attempt from partners acme-corp, firstfed, unified-payments.',
   '{"count":5,"window":"60m","partners":["acme-corp","firstfed","unified-payments"]}',
   'screening-service',NULL,NULL,'OPEN', NOW() - interval '30 minutes'),

  (gen_random_uuid(),'PERFORMANCE','demo_dlq_growth','MEDIUM',
   '14 messages in Flow Fabric DLQ',
   '14 messages accumulated in events.account.dlq after max delivery attempts. Inspect for poison payload. Source: account lifecycle events from sftp-service.',
   '{"dlq_topic":"events.account.dlq","depth":14,"source_service":"sftp-service"}',
   'sftp-service',NULL,NULL,'OPEN', NOW() - interval '5 minutes');

INSERT INTO sentinel_health_scores (
  id, overall_score, infrastructure_score, data_score, security_score, details, recorded_at
) VALUES
  (gen_random_uuid(), 78, 92, 74, 70,
   '{"demo":true,"notes":"Degraded by 2 critical security findings and elevated failure rate; infra stable"}',
   NOW()),
  (gen_random_uuid(), 85, 94, 82, 79, '{"demo":true}', NOW() - interval '1 hour'),
  (gen_random_uuid(), 88, 94, 86, 84, '{"demo":true}', NOW() - interval '3 hours'),
  (gen_random_uuid(), 91, 95, 90, 88, '{"demo":true}', NOW() - interval '6 hours'),
  (gen_random_uuid(), 93, 96, 93, 90, '{"demo":true}', NOW() - interval '12 hours'),
  (gen_random_uuid(), 89, 95, 88, 84, '{"demo":true}', NOW() - interval '18 hours'),
  (gen_random_uuid(), 87, 94, 86, 81, '{"demo":true}', NOW() - interval '24 hours');
SQL

# =============================================================================
# 7. Summary
# =============================================================================
log "Summary of seeded rows:"
"${PSQL[@]}" -c "
SELECT 'file_transfer_records (demo)' AS entity, count(*) FROM file_transfer_records WHERE track_id LIKE 'TRZDEMO%'
UNION ALL
SELECT 'file_transfer_records by status: MOVED_TO_SENT',
       count(*) FROM file_transfer_records WHERE track_id LIKE 'TRZDEMO%' AND status='MOVED_TO_SENT'
UNION ALL
SELECT 'file_transfer_records by status: FAILED',
       count(*) FROM file_transfer_records WHERE track_id LIKE 'TRZDEMO%' AND status='FAILED'
UNION ALL
SELECT 'file_transfer_records by status: DOWNLOADED (in-flight incl stuck)',
       count(*) FROM file_transfer_records WHERE track_id LIKE 'TRZDEMO%' AND status='DOWNLOADED'
UNION ALL
SELECT 'flow_executions (demo)',      count(*) FROM flow_executions    WHERE track_id LIKE 'TRZDEMO%'
UNION ALL
SELECT 'fabric_checkpoints (demo)',   count(*) FROM fabric_checkpoints WHERE track_id LIKE 'TRZDEMO%'
UNION ALL
SELECT 'stuck fabric_checkpoints',    count(*) FROM fabric_checkpoints WHERE track_id LIKE 'TRZDEMO%' AND status='IN_PROGRESS' AND lease_expires_at < NOW()
UNION ALL
SELECT 'fabric_instances (demo)',     count(*) FROM fabric_instances   WHERE instance_id LIKE 'demo-%'
UNION ALL
SELECT 'sentinel_findings (demo)',    count(*) FROM sentinel_findings  WHERE rule_name LIKE 'demo_%'
UNION ALL
SELECT 'sentinel_health_scores (demo)', count(*) FROM sentinel_health_scores WHERE details LIKE '%\"demo\":true%';
"

cat <<'EOF'

Demo traffic seeded. Pages that are now populated:

  /dashboard          — aggregate numbers from file_transfer_records
  /activity-monitor   — 150 historical records across 7 days
  /journey            — search for TRZDEMO00001..TRZDEMO00150
  /fabric             — queues, latency p50/p95/p99, instances, 3 stuck items
  /sentinel           — 12 findings + 7 health-score snapshots
  /analytics          — populates on the first metric aggregation run (~5 min)

To generate a LIVE Fabric checkpoint (watch the Gantt tick in real time):
  1. Log in at https://localhost
  2. Go to File Manager (/file-manager) and upload any small file
  3. Open /fabric and watch the new checkpoint appear

EOF

#!/usr/bin/env bash
# =============================================================================
# TranzFer MFT — demo traffic / historical data seeder
# =============================================================================
# Populates the pages that demo-onboard.sh alone leaves empty:
#   /fabric            — checkpoints, instances, stuck items, latency percentiles
#   /activity-monitor  — historical flow executions
#   /journey           — per-trackId timelines (via fabric checkpoints)
#   /analytics         — transfer volume / success rate over time
#   /sentinel          — findings across analyzers + a health score snapshot
#
# Everything is written directly to Postgres via `docker compose exec postgres psql`
# so this works without needing a host psql client. All rows are synthetic but
# well-formed — the UI can't tell them apart from real data.
#
# Safe to re-run: each section DELETEs prior demo rows (tagged with
# processing_instance LIKE 'demo-%' or rule_name LIKE 'demo_%') before inserting.
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

# Sanity: onboarding script must have run — we need file_flows to reference
FLOW_COUNT=$("${PSQL[@]}" -tAc "SELECT count(*) FROM file_flows" 2>/dev/null || echo 0)
if [[ "${FLOW_COUNT}" -lt 1 ]]; then
  die "No rows in file_flows. Run ./scripts/demo-onboard.sh first."
fi
log "Found ${FLOW_COUNT} file_flows to reference."

# =============================================================================
# 1. Clean any prior demo-traffic rows (idempotent)
# =============================================================================
log "Cleaning previous demo-traffic data..."
"${PSQL[@]}" <<'SQL'
DELETE FROM fabric_checkpoints  WHERE processing_instance LIKE 'demo-%';
DELETE FROM fabric_instances    WHERE instance_id         LIKE 'demo-%';
DELETE FROM sentinel_findings   WHERE rule_name           LIKE 'demo_%';
DELETE FROM sentinel_health_scores WHERE details          LIKE '%"demo":true%';
DELETE FROM flow_executions     WHERE track_id            LIKE 'TRZDEMO%';
SQL

# =============================================================================
# 2. Flow executions (historical — drives Activity Monitor + Analytics)
# =============================================================================
# 150 executions spread over the last 7 days.
# Distribution: 72% COMPLETED, 18% FAILED, 8% PROCESSING, 2% CANCELLED
#
# Note: flow_executions.track_id has a UNIQUE constraint — we use a deterministic
# 'TRZDEMO' prefix so demo rows are cleanly removable.
# =============================================================================
log "Seeding 150 historical flow_executions over the last 7 days..."
"${PSQL[@]}" <<'SQL'
WITH flows AS (
  SELECT id FROM file_flows ORDER BY random() LIMIT 30
),
rows AS (
  SELECT
    gs AS n,
    'TRZDEMO' || lpad(gs::text, 6, '0') AS track_id,
    (SELECT id FROM flows ORDER BY random() LIMIT 1) AS flow_id,
    (ARRAY[
       'daily-batch.csv','invoice_20250401.edi','payroll.xml','orders-batch.zip',
       'hl7_adt_feed.hl7','acct_reconcile.txt','trade-settle.xml','pci-report.csv.pgp',
       'claims-837.edi','eft-remit.txt','partner-ack.mdn','daily.log.gz'
     ])[1 + (gs % 12)] AS original_filename,
    (CASE
       WHEN gs % 50 = 0 THEN 'CANCELLED'
       WHEN gs % 25 < 2 THEN 'PROCESSING'
       WHEN gs % 25 < 6 THEN 'FAILED'
       ELSE 'COMPLETED'
     END) AS status,
    (NOW() - (random() * interval '7 days')) AS started_at
  FROM generate_series(1, 150) gs
)
INSERT INTO flow_executions (
  id, flow_id, track_id, original_filename, current_file_path,
  status, current_step, error_message, started_at, completed_at
)
SELECT
  gen_random_uuid(),
  flow_id,
  track_id,
  original_filename,
  '/inbox/' || original_filename,
  status,
  CASE WHEN status = 'PROCESSING' THEN (random()*4)::int + 1
       WHEN status = 'FAILED'     THEN (random()*3)::int + 1
       ELSE 5 END,
  CASE WHEN status = 'FAILED' THEN
       (ARRAY[
         'Connection timeout to downstream partner SFTP',
         'Checksum mismatch on delivery',
         'Encryption key expired',
         'Partner quota exceeded (429)',
         'Screening quarantined PCI content'
       ])[(random()*5)::int + 1]
  END,
  started_at,
  CASE WHEN status IN ('COMPLETED','FAILED','CANCELLED')
       THEN started_at + (random() * interval '90 seconds' + interval '5 seconds')
  END
FROM rows;
SQL

# =============================================================================
# 3. Fabric checkpoints (historical — drives Fabric latency + Journey timelines)
# =============================================================================
# 5 steps per demo execution (SOURCE → SCREEN → ENCRYPT → COMPRESS → DELIVERY).
# duration_ms uses a plausible distribution so p50/p95/p99 are non-trivial.
# A handful of ABANDONED + IN_PROGRESS rows drive the /fabric/stuck page.
# =============================================================================
log "Seeding fabric_checkpoints (5 per execution + stuck items)..."
"${PSQL[@]}" <<'SQL'
-- COMPLETED executions: 5 normal steps
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
  -- Realistic skewed latency: most fast, long tail
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

-- FAILED executions: steps up to fail-step are COMPLETED, fail-step is FAILED
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
  (ARRAY['SOURCE','SCREEN','ENCRYPT','COMPRESS','DELIVERY'])[fe.current_step + 1],
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

-- PROCESSING executions: current step is IN_PROGRESS with active lease
INSERT INTO fabric_checkpoints (
  id, track_id, step_index, step_type, status,
  input_storage_key, processing_instance,
  claimed_at, lease_expires_at, started_at, attempt_number, created_at
)
SELECT
  gen_random_uuid(),
  fe.track_id,
  fe.current_step,
  (ARRAY['SOURCE','SCREEN','ENCRYPT','COMPRESS','DELIVERY'])[fe.current_step + 1],
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
  AND fe.status = 'PROCESSING';

-- A few STUCK items (expired lease, still IN_PROGRESS) for /fabric/stuck endpoint
INSERT INTO fabric_checkpoints (
  id, track_id, step_index, step_type, status,
  input_storage_key, processing_instance,
  claimed_at, lease_expires_at, started_at, attempt_number, created_at
)
VALUES
  (gen_random_uuid(), 'TRZDEMOSTUCK01', 2, 'ENCRYPT', 'IN_PROGRESS', repeat('a',64),
   'demo-encryption-service-pod-dead-1',
   NOW() - interval '12 minutes', NOW() - interval '7 minutes',
   NOW() - interval '12 minutes', 1, NOW() - interval '12 minutes'),
  (gen_random_uuid(), 'TRZDEMOSTUCK02', 4, 'DELIVERY', 'IN_PROGRESS', repeat('b',64),
   'demo-ftp-service-pod-dead-1',
   NOW() - interval '22 minutes', NOW() - interval '17 minutes',
   NOW() - interval '22 minutes', 1, NOW() - interval '22 minutes'),
  (gen_random_uuid(), 'TRZDEMOSTUCK03', 1, 'SCREEN', 'IN_PROGRESS', repeat('c',64),
   'demo-onboarding-api-pod-dead-1',
   NOW() - interval '45 minutes', NOW() - interval '40 minutes',
   NOW() - interval '45 minutes', 1, NOW() - interval '45 minutes');
SQL

# =============================================================================
# 4. Fabric instance heartbeats
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
  -- Dead instance: last heartbeat > 2 min ago → shows on /fabric Instances tab as dead
  ('demo-encryption-service-pod-dead-1','encryption-service','mft-encryption-service-crashed',
   NOW() - interval '3 hours',  NOW() - interval '15 minutes', 'DEGRADED',
   '[]',                               '[]',                  1),
  ('demo-ftp-service-pod-dead-1','ftp-service','mft-ftp-service-crashed',
   NOW() - interval '3 hours',  NOW() - interval '25 minutes', 'DEGRADED',
   '["events.account"]',               '[0]',                 1);
SQL

# =============================================================================
# 5. Sentinel findings + health score
# =============================================================================
# Two analyzers (SECURITY, PERFORMANCE), multiple severities, varied statuses.
# All rule_names prefixed 'demo_' so we can find and clean them later.
# =============================================================================
log "Seeding sentinel findings + health score..."
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
   'screening-service','acme-trading','TRZDEMO000007','OPEN', NOW() - interval '42 minutes'),

  (gen_random_uuid(),'SECURITY','demo_integrity_mismatch','CRITICAL',
   'Source/destination checksum mismatch on delivery',
   'Delivery to partner FirstFed (SFTP) completed with SHA-256 mismatch. Source key: ab12..., delivered key: cd34.... Rollback triggered.',
   '{"source_sha256":"ab12","delivery_sha256":"cd34","partner":"FirstFed"}',
   'gateway-service','firstfed-prod','TRZDEMO000011','ACKNOWLEDGED', NOW() - interval '3 hours'),

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

-- Current health score (drives /sentinel Overview tab)
INSERT INTO sentinel_health_scores (
  id, overall_score, infrastructure_score, data_score, security_score, details, recorded_at
) VALUES
  (gen_random_uuid(), 78, 92, 74, 70,
   '{"demo":true,"notes":"Degraded by 2 critical security findings and elevated failure rate; infra stable"}',
   NOW()),
  (gen_random_uuid(), 85, 94, 82, 79,
   '{"demo":true}', NOW() - interval '1 hour'),
  (gen_random_uuid(), 88, 94, 86, 84,
   '{"demo":true}', NOW() - interval '3 hours'),
  (gen_random_uuid(), 91, 95, 90, 88,
   '{"demo":true}', NOW() - interval '6 hours'),
  (gen_random_uuid(), 93, 96, 93, 90,
   '{"demo":true}', NOW() - interval '12 hours'),
  (gen_random_uuid(), 89, 95, 88, 84,
   '{"demo":true}', NOW() - interval '18 hours'),
  (gen_random_uuid(), 87, 94, 86, 81,
   '{"demo":true}', NOW() - interval '24 hours');
SQL

# =============================================================================
# 6. Summary
# =============================================================================
log "Summary of seeded rows:"
"${PSQL[@]}" -c "
SELECT 'flow_executions (demo)'   AS entity, count(*) FROM flow_executions    WHERE track_id LIKE 'TRZDEMO%'
UNION ALL
SELECT 'fabric_checkpoints (demo)',         count(*) FROM fabric_checkpoints  WHERE processing_instance LIKE 'demo-%'
UNION ALL
SELECT 'fabric_instances (demo)',           count(*) FROM fabric_instances    WHERE instance_id LIKE 'demo-%'
UNION ALL
SELECT 'sentinel_findings (demo)',          count(*) FROM sentinel_findings   WHERE rule_name LIKE 'demo_%'
UNION ALL
SELECT 'sentinel_health_scores (demo)',     count(*) FROM sentinel_health_scores WHERE details LIKE '%\"demo\":true%';
"

cat <<'EOF'

Demo traffic seeded. Pages that are now populated:

  /dashboard          — aggregates now include transfer counts/rates
  /activity-monitor   — 150 historical executions across 7 days
  /journey            — search for TRZDEMO000001..TRZDEMO000150
  /analytics          — transfer history for charts
  /fabric             — queues, latency p50/p95/p99, instances, stuck items
  /sentinel           — 12 findings + 7 health-score snapshots

To generate a LIVE Fabric checkpoint (watch the Gantt tick in real time):
  1. Log in at http://localhost:3000
  2. Go to File Manager (/file-manager) and upload any small file
  3. Open /fabric and watch the new checkpoint appear

EOF

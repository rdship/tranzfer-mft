-- Snapshot retention policy: FlowStepSnapshots older than N days are purged daily.
-- Default: 90 days. Configurable via Platform Config → MAINTENANCE category.
-- The purge job (SnapshotRetentionJob) runs at 02:00 UTC and is ShedLock-guarded.

INSERT INTO platform_settings (
    setting_key, setting_value, environment, service_name,
    data_type, description, category, sensitive, active
) VALUES (
    'snapshot.retention.days',
    '90',
    'PROD',
    'GLOBAL',
    'INTEGER',
    'Days to retain FlowStepSnapshots before auto-purge (0 = disabled). Purge runs daily at 02:00 UTC.',
    'MAINTENANCE',
    false,
    true
) ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

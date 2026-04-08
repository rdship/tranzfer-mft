-- Per-user QoS (Quality of Service) fields on transfer_accounts.
-- NULL = inherit from partner SLA tier default; 0 = unlimited.
ALTER TABLE transfer_accounts ADD COLUMN qos_upload_bytes_per_second   BIGINT;
ALTER TABLE transfer_accounts ADD COLUMN qos_download_bytes_per_second BIGINT;
ALTER TABLE transfer_accounts ADD COLUMN qos_max_concurrent_sessions   INT;
ALTER TABLE transfer_accounts ADD COLUMN qos_priority                  INT;
ALTER TABLE transfer_accounts ADD COLUMN qos_burst_allowance_percent   INT;

COMMENT ON COLUMN transfer_accounts.qos_upload_bytes_per_second   IS 'Upload speed limit B/s (NULL=SLA default, 0=unlimited)';
COMMENT ON COLUMN transfer_accounts.qos_download_bytes_per_second IS 'Download speed limit B/s (NULL=SLA default, 0=unlimited)';
COMMENT ON COLUMN transfer_accounts.qos_max_concurrent_sessions   IS 'Max concurrent sessions (NULL=SLA default)';
COMMENT ON COLUMN transfer_accounts.qos_priority                  IS 'QoS priority 1=highest..10=lowest (NULL=SLA default)';
COMMENT ON COLUMN transfer_accounts.qos_burst_allowance_percent   IS 'Burst % above sustained rate (NULL=SLA default)';

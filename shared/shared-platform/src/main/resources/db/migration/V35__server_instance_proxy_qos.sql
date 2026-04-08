-- Proxy QoS policy columns on server_instances
ALTER TABLE server_instances ADD COLUMN IF NOT EXISTS proxy_qos_enabled BOOLEAN DEFAULT false;
ALTER TABLE server_instances ADD COLUMN IF NOT EXISTS proxy_qos_max_bps BIGINT;
ALTER TABLE server_instances ADD COLUMN IF NOT EXISTS proxy_qos_per_conn_max_bps BIGINT;
ALTER TABLE server_instances ADD COLUMN IF NOT EXISTS proxy_qos_priority INTEGER DEFAULT 5;
ALTER TABLE server_instances ADD COLUMN IF NOT EXISTS proxy_qos_burst_pct INTEGER DEFAULT 20;

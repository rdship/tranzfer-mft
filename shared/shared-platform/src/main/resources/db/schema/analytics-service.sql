-- =============================================================================
-- TranzFer MFT — analytics-service schema (self-healing)
-- Only creates tables this service needs. IF NOT EXISTS = safe to re-run.
-- =============================================================================

CREATE TABLE IF NOT EXISTS metric_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    metric_name VARCHAR(255),
    metric_value DOUBLE PRECISION,
    dimensions JSONB,
    recorded_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS alert_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    description TEXT,
    metric_name VARCHAR(255),
    condition VARCHAR(30),
    threshold DOUBLE PRECISION,
    severity VARCHAR(20),
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS file_transfer_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id VARCHAR(64) NOT NULL,
    folder_mapping_id UUID,
    original_filename VARCHAR(512),
    source_file_path VARCHAR(1024),
    destination_file_path VARCHAR(1024),
    archive_file_path VARCHAR(1024),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    file_size_bytes BIGINT,
    source_checksum VARCHAR(128),
    destination_checksum VARCHAR(128),
    retry_count INTEGER DEFAULT 0,
    uploaded_at TIMESTAMPTZ,
    routed_at TIMESTAMPTZ,
    downloaded_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

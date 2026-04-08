-- =============================================================================
-- TranzFer MFT — ai-engine schema (self-healing)
-- Only creates tables this service needs. IF NOT EXISTS = safe to re-run.
-- =============================================================================

CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID,
    track_id VARCHAR(64),
    action VARCHAR(100),
    success BOOLEAN,
    path VARCHAR(1024),
    filename VARCHAR(512),
    file_size_bytes BIGINT,
    sha256checksum VARCHAR(128),
    ip_address VARCHAR(45),
    session_id VARCHAR(128),
    principal VARCHAR(255),
    error_message TEXT,
    metadata JSONB,
    timestamp TIMESTAMPTZ DEFAULT now(),
    integrity_hash VARCHAR(128),
    encrypted BOOLEAN DEFAULT false,
    hmac_key_version INTEGER DEFAULT 1
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

CREATE TABLE IF NOT EXISTS file_flows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    filename_pattern VARCHAR(500),
    source_account_id UUID,
    source_path VARCHAR(512),
    steps JSONB DEFAULT '[]'::jsonb,
    destination_account_id UUID,
    destination_path VARCHAR(512),
    external_destination_id UUID,
    partner_id UUID,
    match_criteria JSONB,
    direction VARCHAR(20),
    priority INTEGER DEFAULT 0,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS transfer_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID,
    protocol VARCHAR(20) NOT NULL,
    username VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    public_key TEXT,
    home_dir VARCHAR(512),
    permissions JSONB DEFAULT '{"read":true,"write":true,"delete":false}'::jsonb,
    server_instance VARCHAR(64),
    partner_id UUID,
    storage_mode VARCHAR(10) DEFAULT 'PHYSICAL',
    inline_max_bytes BIGINT,
    chunk_threshold_bytes BIGINT,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS threat_indicators (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(50),
    value TEXT,
    source VARCHAR(100),
    severity VARCHAR(20),
    confidence DOUBLE PRECISION,
    first_seen TIMESTAMPTZ,
    last_seen TIMESTAMPTZ,
    active BOOLEAN DEFAULT true
);

CREATE TABLE IF NOT EXISTS security_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_type VARCHAR(50),
    severity VARCHAR(20),
    source VARCHAR(100),
    message TEXT,
    details JSONB,
    resolved BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS security_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(50),
    source_ip VARCHAR(45),
    target VARCHAR(255),
    action VARCHAR(50),
    outcome VARCHAR(30),
    details JSONB,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS threat_actors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    type VARCHAR(50),
    origin VARCHAR(100),
    ttps JSONB,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS attack_campaigns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    threat_actor_id UUID,
    start_date TIMESTAMPTZ,
    end_date TIMESTAMPTZ,
    status VARCHAR(30),
    indicators JSONB,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS verdict_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id VARCHAR(64),
    filename VARCHAR(512),
    verdict VARCHAR(30),
    confidence DOUBLE PRECISION,
    engine VARCHAR(50),
    details JSONB,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS security_incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(255),
    severity VARCHAR(20),
    status VARCHAR(30),
    assigned_to VARCHAR(255),
    details JSONB,
    created_at TIMESTAMPTZ DEFAULT now(),
    resolved_at TIMESTAMPTZ
);

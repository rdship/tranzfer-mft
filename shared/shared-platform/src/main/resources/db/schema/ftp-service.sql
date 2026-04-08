-- =============================================================================
-- TranzFer MFT — ftp-service schema (self-healing)
-- Only creates tables this service needs. IF NOT EXISTS = safe to re-run.
-- =============================================================================

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

CREATE TABLE IF NOT EXISTS server_instances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    protocol VARCHAR(16) NOT NULL DEFAULT 'SFTP',
    internal_host VARCHAR(255) NOT NULL,
    internal_port INTEGER NOT NULL DEFAULT 2222,
    external_host VARCHAR(255),
    external_port INTEGER,
    use_proxy BOOLEAN NOT NULL DEFAULT false,
    proxy_host VARCHAR(255),
    proxy_port INTEGER,
    max_connections INTEGER DEFAULT 500,
    folder_template_id UUID,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS folder_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    built_in BOOLEAN NOT NULL DEFAULT false,
    folders JSONB NOT NULL DEFAULT '[]'::jsonb,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(255),
    updated_by VARCHAR(255)
);

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

CREATE TABLE IF NOT EXISTS login_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(255) NOT NULL UNIQUE,
    failure_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS folder_mappings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id UUID,
    source_path VARCHAR(512),
    destination_account_id UUID,
    destination_path VARCHAR(512),
    external_destination_id UUID,
    filename_pattern VARCHAR(500),
    encryption_option VARCHAR(30),
    encryption_key_id UUID,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
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

CREATE TABLE IF NOT EXISTS flow_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id VARCHAR(64),
    flow_id UUID,
    transfer_record_id UUID,
    original_filename VARCHAR(512),
    current_file_path VARCHAR(1024),
    status VARCHAR(30) DEFAULT 'PENDING',
    current_step INTEGER DEFAULT 0,
    step_results JSONB DEFAULT '[]'::jsonb,
    matched_criteria JSONB,
    error_message TEXT,
    started_at TIMESTAMPTZ DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS external_destinations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(30) NOT NULL,
    host VARCHAR(255),
    port INTEGER,
    username VARCHAR(255),
    encrypted_password TEXT,
    remote_path VARCHAR(512),
    kafka_topic VARCHAR(255),
    kafka_bootstrap_servers VARCHAR(512),
    kafka_producer_config TEXT,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS server_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    service_type VARCHAR(50),
    host VARCHAR(255),
    port INT,
    proxy_type VARCHAR(30) DEFAULT 'NONE',
    proxy_host VARCHAR(255),
    proxy_port INT,
    properties JSONB,
    folder_template_id UUID,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS scheduled_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    cron_expression VARCHAR(100) NOT NULL,
    timezone VARCHAR(50) DEFAULT 'UTC',
    task_type VARCHAR(50) NOT NULL,
    reference_id VARCHAR(255),
    config JSONB,
    enabled BOOLEAN DEFAULT true,
    last_run TIMESTAMPTZ,
    next_run TIMESTAMPTZ,
    last_status VARCHAR(30),
    last_error TEXT,
    total_runs INTEGER DEFAULT 0,
    failed_runs INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS partner_agreements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    account_id UUID,
    partner_id UUID,
    expected_delivery_start_hour INTEGER DEFAULT 8,
    expected_delivery_end_hour INTEGER DEFAULT 17,
    expected_days JSONB DEFAULT '["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"]'::jsonb,
    min_files_per_window INTEGER DEFAULT 1,
    max_error_rate DOUBLE PRECISION DEFAULT 0.05,
    grace_period_minutes INTEGER DEFAULT 30,
    breach_action VARCHAR(30) DEFAULT 'ALERT',
    active BOOLEAN DEFAULT true,
    total_breaches INTEGER DEFAULT 0,
    last_breach_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

-- =============================================================================
-- TranzFer MFT — sftp-service schema (self-healing)
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

CREATE TABLE IF NOT EXISTS virtual_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL,
    path VARCHAR(1024) NOT NULL,
    parent_path VARCHAR(1024) NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(10) NOT NULL DEFAULT 'FILE',
    storage_key VARCHAR(64),
    size_bytes BIGINT NOT NULL DEFAULT 0,
    content_type VARCHAR(128),
    track_id VARCHAR(12),
    access_count INTEGER NOT NULL DEFAULT 0,
    last_accessed_at TIMESTAMPTZ,
    deleted BOOLEAN NOT NULL DEFAULT false,
    permissions VARCHAR(10) DEFAULT 'rwxr-xr-x',
    version INTEGER NOT NULL DEFAULT 0,
    inline_content BYTEA,
    storage_bucket VARCHAR(10) DEFAULT 'STANDARD',
    compressed BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
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

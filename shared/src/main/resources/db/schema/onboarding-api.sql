-- =============================================================================
-- TranzFer MFT — onboarding-api schema (self-healing)
-- Auto-executed by ServiceReadinessValidator on every boot if tables are missing.
-- All statements use IF NOT EXISTS — safe to re-run.
-- =============================================================================

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(100) UNIQUE NOT NULL,
    company_name VARCHAR(255) NOT NULL,
    contact_email VARCHAR(255),
    plan VARCHAR(30) DEFAULT 'TRIAL',
    trial_ends_at TIMESTAMPTZ,
    transfers_used BIGINT DEFAULT 0,
    transfer_limit BIGINT,
    branding JSONB DEFAULT '{}'::jsonb,
    custom_domain VARCHAR(255),
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS partners (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    slug VARCHAR(100) UNIQUE NOT NULL,
    industry VARCHAR(100),
    website VARCHAR(500),
    logo_url VARCHAR(1000),
    partner_type VARCHAR(30) NOT NULL DEFAULT 'EXTERNAL',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    onboarding_phase VARCHAR(30) DEFAULT 'SETUP',
    protocols_enabled JSONB DEFAULT '[]'::jsonb,
    sla_tier VARCHAR(30) DEFAULT 'STANDARD',
    max_file_size_bytes BIGINT DEFAULT 536870912,
    max_transfers_per_day INTEGER DEFAULT 1000,
    retention_days INTEGER DEFAULT 90,
    notes TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(255),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS partner_contacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id UUID REFERENCES partners(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(50),
    role VARCHAR(100),
    is_primary BOOLEAN DEFAULT false,
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

CREATE TABLE IF NOT EXISTS transfer_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
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

CREATE TABLE IF NOT EXISTS file_flows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    filename_pattern VARCHAR(500),
    source_account_id UUID REFERENCES transfer_accounts(id),
    source_path VARCHAR(512),
    steps JSONB DEFAULT '[]'::jsonb,
    destination_account_id UUID REFERENCES transfer_accounts(id),
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

CREATE TABLE IF NOT EXISTS folder_mappings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id UUID REFERENCES transfer_accounts(id),
    source_path VARCHAR(512),
    destination_account_id UUID REFERENCES transfer_accounts(id),
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

CREATE TABLE IF NOT EXISTS totp_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(255) NOT NULL UNIQUE,
    secret VARCHAR(255),
    enabled BOOLEAN DEFAULT false,
    enrolled BOOLEAN DEFAULT false,
    backup_codes TEXT,
    method VARCHAR(20) DEFAULT 'TOTP',
    otp_email VARCHAR(255),
    enrolled_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    resource_type VARCHAR(50) NOT NULL,
    action VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS role_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role VARCHAR(30) NOT NULL,
    permission_id UUID NOT NULL REFERENCES permissions(id),
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE (role, permission_id)
);

CREATE TABLE IF NOT EXISTS user_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    permission_id UUID NOT NULL REFERENCES permissions(id),
    resource_id UUID,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dead_letter_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_queue VARCHAR(255),
    original_exchange VARCHAR(255),
    routing_key VARCHAR(255),
    payload TEXT,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMPTZ DEFAULT now(),
    retried_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS blockchain_anchors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id VARCHAR(64),
    filename VARCHAR(512),
    sha256 VARCHAR(128),
    merkle_root VARCHAR(128),
    chain VARCHAR(30) DEFAULT 'INTERNAL',
    tx_hash VARCHAR(128),
    block_number BIGINT,
    proof TEXT,
    anchored_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS cluster_nodes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cluster_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    description TEXT,
    communication_mode VARCHAR(30) DEFAULT 'WITHIN_CLUSTER',
    region VARCHAR(100),
    environment VARCHAR(50),
    api_endpoint VARCHAR(512),
    active BOOLEAN DEFAULT true,
    registered_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS client_presence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(255) NOT NULL,
    host VARCHAR(255),
    port INTEGER,
    protocol VARCHAR(20),
    client_version VARCHAR(50),
    last_seen TIMESTAMPTZ DEFAULT now(),
    online BOOLEAN DEFAULT true
);

CREATE TABLE IF NOT EXISTS transfer_tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id VARCHAR(64) NOT NULL UNIQUE,
    track_id VARCHAR(64),
    sender_account_id UUID,
    receiver_account_id UUID,
    filename VARCHAR(512),
    file_size_bytes BIGINT,
    sha256_checksum VARCHAR(128),
    receiver_host VARCHAR(255),
    receiver_port INTEGER,
    sender_host VARCHAR(255),
    sender_port INTEGER,
    status VARCHAR(30) DEFAULT 'PENDING',
    sender_token VARCHAR(512),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    completed_at TIMESTAMPTZ,
    error_message TEXT
);

CREATE TABLE IF NOT EXISTS auto_onboard_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_ip VARCHAR(45),
    client_version VARCHAR(50),
    capabilities JSONB DEFAULT '{}'::jsonb,
    generated_username VARCHAR(255),
    temp_password VARCHAR(255),
    phase VARCHAR(30) DEFAULT 'DETECTION',
    files_observed INTEGER DEFAULT 0,
    detected_patterns JSONB DEFAULT '[]'::jsonb,
    auto_flow_id VARCHAR(255),
    security_profile_id VARCHAR(255),
    created_at TIMESTAMPTZ DEFAULT now(),
    completed_at TIMESTAMPTZ
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

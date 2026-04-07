-- =============================================================================
-- TranzFer MFT — config-service schema (self-healing)
-- Only creates tables this service needs. IF NOT EXISTS = safe to re-run.
-- =============================================================================

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

CREATE TABLE IF NOT EXISTS platform_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    setting_key VARCHAR(255) NOT NULL UNIQUE,
    setting_value TEXT,
    environment VARCHAR(30),
    service_name VARCHAR(100),
    data_type VARCHAR(30),
    description TEXT,
    category VARCHAR(100),
    sensitive BOOLEAN DEFAULT false,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ,
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

CREATE TABLE IF NOT EXISTS encryption_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID,
    key_name VARCHAR(255),
    algorithm VARCHAR(30),
    public_key TEXT,
    encrypted_private_key TEXT,
    encrypted_symmetric_key TEXT,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ,
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

CREATE TABLE IF NOT EXISTS webhook_connectors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    type VARCHAR(30),
    url TEXT,
    auth_token TEXT,
    username VARCHAR(255),
    password VARCHAR(255),
    trigger_events JSONB DEFAULT '[]'::jsonb,
    min_severity VARCHAR(20),
    custom_headers JSONB DEFAULT '{}'::jsonb,
    snow_instance_id VARCHAR(255),
    snow_assignment_group VARCHAR(255),
    snow_category VARCHAR(100),
    active BOOLEAN DEFAULT true,
    last_triggered TIMESTAMPTZ,
    total_notifications INT DEFAULT 0,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS security_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    type VARCHAR(20),
    ssh_ciphers JSONB,
    ssh_macs JSONB,
    kex_algorithms JSONB,
    host_key_algorithms JSONB,
    tls_min_version VARCHAR(10),
    tls_ciphers JSONB,
    client_auth_required BOOLEAN DEFAULT false,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS listener_security_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    security_tier VARCHAR(20) DEFAULT 'AI',
    server_instance_id UUID,
    external_destination_id UUID,
    ip_whitelist JSONB,
    ip_blacklist JSONB,
    geo_allowed_countries JSONB,
    geo_blocked_countries JSONB,
    rate_limit_per_minute INT DEFAULT 60,
    max_concurrent INT DEFAULT 20,
    max_bytes_per_minute BIGINT DEFAULT 500000000,
    max_auth_attempts INT DEFAULT 5,
    idle_timeout_seconds INT DEFAULT 300,
    require_encryption BOOLEAN DEFAULT false,
    connection_logging BOOLEAN DEFAULT true,
    allowed_file_extensions JSONB,
    blocked_file_extensions JSONB,
    max_file_size_bytes BIGINT DEFAULT 0,
    transfer_windows TEXT,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS as2_partnerships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_name VARCHAR(255),
    partner_as2_id VARCHAR(255),
    our_as2_id VARCHAR(255),
    endpoint_url TEXT,
    partner_certificate TEXT,
    signing_algorithm VARCHAR(50),
    encryption_algorithm VARCHAR(50),
    mdn_required BOOLEAN DEFAULT true,
    mdn_async BOOLEAN DEFAULT false,
    mdn_url TEXT,
    compression_enabled BOOLEAN DEFAULT false,
    protocol VARCHAR(10) DEFAULT 'AS2',
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS legacy_server_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    protocol VARCHAR(20),
    host VARCHAR(255),
    port INT DEFAULT 22,
    health_check_user VARCHAR(255),
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS delivery_endpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    description TEXT,
    protocol VARCHAR(30),
    host VARCHAR(255),
    port INT,
    base_path VARCHAR(512),
    auth_type VARCHAR(30),
    username VARCHAR(255),
    encrypted_password TEXT,
    ssh_private_key TEXT,
    bearer_token TEXT,
    api_key_header VARCHAR(255),
    api_key_value TEXT,
    http_method VARCHAR(10),
    http_headers JSONB,
    content_type VARCHAR(100),
    tls_enabled BOOLEAN DEFAULT true,
    tls_trust_all BOOLEAN DEFAULT false,
    proxy_enabled BOOLEAN DEFAULT false,
    proxy_type VARCHAR(20),
    proxy_host VARCHAR(255),
    proxy_port INT,
    connection_timeout_ms INT DEFAULT 5000,
    read_timeout_ms INT DEFAULT 30000,
    retry_count INT DEFAULT 3,
    retry_delay_ms INT DEFAULT 1000,
    as2_partnership_id UUID,
    partner_id UUID,
    tags VARCHAR(500),
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ,
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

CREATE TABLE IF NOT EXISTS vfs_intents (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL,
    op            VARCHAR(10)  NOT NULL CHECK (op IN ('WRITE','DELETE','MOVE')),
    path          VARCHAR(1024) NOT NULL,
    dest_path     VARCHAR(1024),
    storage_key   VARCHAR(64),
    track_id      VARCHAR(12),
    size_bytes    BIGINT DEFAULT 0,
    content_type  VARCHAR(128),
    status        VARCHAR(12)  NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','COMMITTED','ABORTED','RECOVERING')),
    pod_id        VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS vfs_chunks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id      UUID NOT NULL,
    chunk_index   INTEGER NOT NULL,
    storage_key   VARCHAR(64)  NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    sha256        VARCHAR(64)  NOT NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','STORED','VERIFIED')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

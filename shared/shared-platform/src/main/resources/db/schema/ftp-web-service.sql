-- =============================================================================
-- TranzFer MFT — ftp-web-service schema (self-healing)
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

CREATE TABLE IF NOT EXISTS chunked_uploads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename VARCHAR(500),
    total_size BIGINT,
    total_chunks INT,
    received_chunks INT DEFAULT 0,
    chunk_size BIGINT,
    status VARCHAR(20) DEFAULT 'INITIATED',
    checksum VARCHAR(64),
    account_username VARCHAR(255),
    track_id VARCHAR(64),
    content_type VARCHAR(255),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ DEFAULT now(),
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS chunked_upload_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    upload_id UUID REFERENCES chunked_uploads(id) ON DELETE CASCADE,
    chunk_number INT,
    size BIGINT,
    checksum VARCHAR(64),
    storage_path TEXT,
    received_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(upload_id, chunk_number)
);

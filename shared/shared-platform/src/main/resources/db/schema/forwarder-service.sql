-- =============================================================================
-- TranzFer MFT — forwarder-service schema (self-healing)
-- Only creates tables this service needs. IF NOT EXISTS = safe to re-run.
-- =============================================================================

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

CREATE TABLE IF NOT EXISTS as2_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id VARCHAR(255),
    partnership_id UUID,
    direction VARCHAR(10),
    filename VARCHAR(512),
    file_size BIGINT,
    status VARCHAR(30),
    mdn_received BOOLEAN DEFAULT false,
    mdn_status VARCHAR(30),
    error_message TEXT,
    track_id VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
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

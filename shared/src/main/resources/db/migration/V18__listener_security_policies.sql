-- ============================================================================
-- V18: Per-listener security policies
--
-- Each server listener or external destination can have its own security tier
-- and manual security rules. Policies are pushed to DMZ proxy at runtime.
-- ============================================================================

CREATE TABLE IF NOT EXISTS listener_security_policies (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR(255) NOT NULL,
    description             TEXT,
    security_tier           VARCHAR(10) NOT NULL DEFAULT 'AI',
    server_instance_id      UUID REFERENCES server_instances(id) ON DELETE CASCADE,
    external_destination_id UUID REFERENCES external_destinations(id) ON DELETE CASCADE,
    ip_whitelist            JSONB DEFAULT '[]'::jsonb,
    ip_blacklist            JSONB DEFAULT '[]'::jsonb,
    geo_allowed_countries   JSONB DEFAULT '[]'::jsonb,
    geo_blocked_countries   JSONB DEFAULT '[]'::jsonb,
    rate_limit_per_minute   INTEGER NOT NULL DEFAULT 60,
    max_concurrent          INTEGER NOT NULL DEFAULT 20,
    max_bytes_per_minute    BIGINT NOT NULL DEFAULT 500000000,
    max_auth_attempts       INTEGER NOT NULL DEFAULT 5,
    idle_timeout_seconds    INTEGER NOT NULL DEFAULT 300,
    require_encryption      BOOLEAN NOT NULL DEFAULT false,
    connection_logging      BOOLEAN NOT NULL DEFAULT true,
    allowed_file_extensions JSONB DEFAULT '[]'::jsonb,
    blocked_file_extensions JSONB DEFAULT '[]'::jsonb,
    max_file_size_bytes     BIGINT DEFAULT 0,
    transfer_windows        JSONB DEFAULT '[]'::jsonb,
    active                  BOOLEAN NOT NULL DEFAULT true,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_by              VARCHAR(100),
    updated_by              VARCHAR(100),
    CONSTRAINT uq_policy_server UNIQUE (server_instance_id),
    CONSTRAINT uq_policy_destination UNIQUE (external_destination_id),
    CONSTRAINT chk_policy_link CHECK (
        (server_instance_id IS NOT NULL AND external_destination_id IS NULL) OR
        (server_instance_id IS NULL AND external_destination_id IS NOT NULL)
    ),
    CONSTRAINT chk_security_tier CHECK (security_tier IN ('MANUAL', 'AI', 'AI_LLM'))
);

CREATE INDEX idx_lsp_server ON listener_security_policies(server_instance_id);
CREATE INDEX idx_lsp_destination ON listener_security_policies(external_destination_id);
CREATE INDEX idx_lsp_tier ON listener_security_policies(security_tier);

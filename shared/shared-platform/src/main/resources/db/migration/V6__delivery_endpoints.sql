-- =============================================================================
-- V6: Delivery Endpoints — external client communication configurations
-- =============================================================================
-- Supports SFTP, FTP, FTPS, HTTP, HTTPS, API protocols with rich auth options.
-- Used by FILE_DELIVERY flow steps to deliver files to external systems.
-- =============================================================================

CREATE TABLE IF NOT EXISTS delivery_endpoints (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  VARCHAR(255) NOT NULL UNIQUE,
    description           TEXT,
    protocol              VARCHAR(20)  NOT NULL,  -- SFTP, FTP, FTPS, HTTP, HTTPS, API

    -- Connection
    host                  VARCHAR(500),
    port                  INTEGER,
    base_path             VARCHAR(1000),

    -- Authentication
    auth_type             VARCHAR(30)  NOT NULL DEFAULT 'NONE',
    username              VARCHAR(255),
    encrypted_password    TEXT,
    ssh_private_key       TEXT,
    bearer_token          TEXT,
    api_key_header        VARCHAR(255),
    api_key_value         TEXT,

    -- HTTP-specific
    http_method           VARCHAR(10),
    http_headers          JSONB,
    content_type          VARCHAR(100),

    -- TLS
    tls_enabled           BOOLEAN NOT NULL DEFAULT false,
    tls_trust_all         BOOLEAN NOT NULL DEFAULT false,

    -- Resilience
    connection_timeout_ms INTEGER DEFAULT 30000,
    read_timeout_ms       INTEGER DEFAULT 60000,
    retry_count           INTEGER DEFAULT 3,
    retry_delay_ms        INTEGER DEFAULT 5000,

    -- Metadata
    tags                  VARCHAR(500),
    active                BOOLEAN NOT NULL DEFAULT true,
    created_at            TIMESTAMPTZ DEFAULT now(),
    updated_at            TIMESTAMPTZ DEFAULT now()
);

-- Performance indexes
CREATE INDEX idx_delivery_ep_protocol   ON delivery_endpoints (protocol)      WHERE active = true;
CREATE INDEX idx_delivery_ep_active     ON delivery_endpoints (active);
CREATE INDEX idx_delivery_ep_name       ON delivery_endpoints (name);
CREATE INDEX idx_delivery_ep_tags       ON delivery_endpoints (tags)           WHERE tags IS NOT NULL;

-- Proxy Groups: named collections of DMZ proxy instances sharing a network scope.
-- Examples: "internal" (corporate LAN), "external" (internet-facing), "partner-acme" (dedicated).
--
-- Proxy instances self-register in Redis at startup via PROXY_GROUP_NAME env var.
-- This table stores the group definitions and admin-configured metadata.
-- Live membership comes from Redis scan (TTL-based presence) not from this table.

CREATE TABLE IF NOT EXISTS proxy_groups (
    id                          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name                        VARCHAR(100) NOT NULL UNIQUE,

    -- INTERNAL | EXTERNAL | PARTNER | CLOUD | CUSTOM
    type                        VARCHAR(30)  NOT NULL DEFAULT 'INTERNAL',
    description                 VARCHAR(500),

    -- Protocols allowed through this group's proxies (JSON array)
    allowed_protocols           JSONB        NOT NULL DEFAULT '["SFTP","FTP","AS2","HTTPS"]',

    -- Security posture for this group
    tls_required                BOOLEAN      NOT NULL DEFAULT false,
    -- Comma-separated CIDR ranges for source IP restrictions (empty = any)
    trusted_cidrs               TEXT,
    max_connections_per_instance INTEGER      NOT NULL DEFAULT 1000,

    -- Routing priority — lower = preferred when multiple groups could serve a request
    routing_priority            INTEGER      NOT NULL DEFAULT 100,

    notes                       TEXT,
    active                      BOOLEAN      NOT NULL DEFAULT true,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pg_type   ON proxy_groups(type);
CREATE INDEX IF NOT EXISTS idx_pg_active ON proxy_groups(active);

-- Seed the two default groups every platform starts with
INSERT INTO proxy_groups (name, type, description, allowed_protocols, tls_required, routing_priority) VALUES
(
    'internal',
    'INTERNAL',
    'Corporate / private network proxies — internal partner integrations and on-premise file exchanges.',
    '["SFTP","FTP","AS2","HTTPS","HTTP"]',
    false,
    10
),
(
    'external',
    'EXTERNAL',
    'Internet-facing proxies — public partner integrations, cloud file exchanges, and external AS2 endpoints.',
    '["SFTP","FTP","AS2","HTTPS"]',
    true,
    20
)
ON CONFLICT (name) DO NOTHING;

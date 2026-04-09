-- ─────────────────────────────────────────────────────────────────────────────
-- V44: Server advanced configuration + multi-server account assignments
--
-- Two changes:
--   1. server_instances: per-server advanced config (proxy group, SSH, security)
--   2. server_account_assignments: many-to-many accounts ↔ servers with overrides
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. Advanced per-server configuration ──────────────────────────────────────

ALTER TABLE server_instances
    -- Proxy group routing (links to proxy_groups.name from V43)
    ADD COLUMN IF NOT EXISTS proxy_group_name          VARCHAR(100),

    -- Security tier applied at this server (NONE | RULES | AI | AI_LLM)
    ADD COLUMN IF NOT EXISTS security_tier             VARCHAR(20)  DEFAULT 'RULES',

    -- SSH / FTP connection hardening
    ADD COLUMN IF NOT EXISTS ssh_banner_message        TEXT,
    ADD COLUMN IF NOT EXISTS max_auth_attempts         INTEGER      DEFAULT 3,
    ADD COLUMN IF NOT EXISTS idle_timeout_seconds      INTEGER      DEFAULT 300,
    ADD COLUMN IF NOT EXISTS session_max_duration_sec  INTEGER      DEFAULT 86400,

    -- Cipher/algorithm allowlists (comma-separated; NULL = use server defaults)
    ADD COLUMN IF NOT EXISTS allowed_ciphers           TEXT,
    ADD COLUMN IF NOT EXISTS allowed_macs              TEXT,
    ADD COLUMN IF NOT EXISTS allowed_kex               TEXT,

    -- Maintenance mode: new connections rejected, existing drained gracefully
    ADD COLUMN IF NOT EXISTS maintenance_mode          BOOLEAN      NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS maintenance_message       TEXT;

-- 2. Many-to-many account ↔ server assignments ──────────────────────────────
-- A transfer account can now be active on multiple server instances.
-- Each assignment can override the account's default home folder, permissions,
-- and QoS limits — giving per-server access control granularity.

CREATE TABLE IF NOT EXISTS server_account_assignments (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    server_instance_id      UUID        NOT NULL REFERENCES server_instances(id) ON DELETE CASCADE,
    transfer_account_id     UUID        NOT NULL REFERENCES transfer_accounts(id) ON DELETE CASCADE,

    -- Optional: override the account's default home directory on this server only
    home_folder_override    VARCHAR(500),

    -- Permission overrides (NULL = inherit from account.permissions JSON)
    can_read    BOOLEAN,
    can_write   BOOLEAN,
    can_delete  BOOLEAN,
    can_rename  BOOLEAN,
    can_mkdir   BOOLEAN,

    -- Per-assignment QoS (NULL = use account-level QoS settings)
    max_concurrent_sessions     INTEGER,
    max_upload_bytes_per_sec    BIGINT,
    max_download_bytes_per_sec  BIGINT,

    enabled     BOOLEAN      NOT NULL DEFAULT true,
    notes       TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(255),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_saa_server_account UNIQUE (server_instance_id, transfer_account_id)
);

CREATE INDEX IF NOT EXISTS idx_saa_server  ON server_account_assignments(server_instance_id);
CREATE INDEX IF NOT EXISTS idx_saa_account ON server_account_assignments(transfer_account_id);
CREATE INDEX IF NOT EXISTS idx_saa_enabled ON server_account_assignments(enabled);

-- Back-fill existing single-server account assignments into the new table.
-- Accounts with a non-null serverInstance string: find the matching server row
-- and create an enabled assignment with default permissions.
INSERT INTO server_account_assignments (server_instance_id, transfer_account_id, enabled, created_by)
SELECT si.id, ta.id, true, 'migration-v44'
FROM   transfer_accounts ta
JOIN   server_instances si ON si.instance_id = ta.server_instance
WHERE  ta.server_instance IS NOT NULL
  AND  ta.active = true
ON CONFLICT (server_instance_id, transfer_account_id) DO NOTHING;

-- V2: Multi-SFTP server instance support
-- Adds a server instance registry and per-account server assignment.

-- ===== SFTP Server Instances Registry =====
CREATE TABLE IF NOT EXISTS sftp_server_instances (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id     VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    -- Internal connection (Docker service name / direct host)
    internal_host   VARCHAR(255) NOT NULL,
    internal_port   INTEGER NOT NULL DEFAULT 2222,
    -- External connection (what clients connect to)
    external_host   VARCHAR(255),
    external_port   INTEGER,
    -- Reverse proxy configuration
    use_proxy       BOOLEAN NOT NULL DEFAULT false,
    proxy_host      VARCHAR(255),
    proxy_port      INTEGER,
    -- Limits
    max_connections  INTEGER DEFAULT 500,
    -- State
    active          BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- ===== Per-account server assignment =====
ALTER TABLE transfer_accounts ADD COLUMN IF NOT EXISTS server_instance VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_ta_server_instance ON transfer_accounts(server_instance);

-- Seed default SFTP server instances
INSERT INTO sftp_server_instances (instance_id, name, description, internal_host, internal_port, external_host, external_port, active)
VALUES ('sftp-1', 'Primary SFTP Server', 'Default SFTP server instance', 'sftp-service', 2222, 'localhost', 22222, true)
ON CONFLICT (instance_id) DO NOTHING;

INSERT INTO sftp_server_instances (instance_id, name, description, internal_host, internal_port, external_host, external_port, active)
VALUES ('sftp-2', 'Secondary SFTP Server', 'Secondary SFTP server instance', 'sftp-service-2', 2223, 'localhost', 22223, true)
ON CONFLICT (instance_id) DO NOTHING;

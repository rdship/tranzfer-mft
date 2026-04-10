-- V48: Migration tracking — partner migration lifecycle, connection audit

-- Partner migration fields
ALTER TABLE partners ADD COLUMN IF NOT EXISTS migration_status VARCHAR(20) DEFAULT 'NOT_STARTED';
ALTER TABLE partners ADD COLUMN IF NOT EXISTS migration_source VARCHAR(200);
ALTER TABLE partners ADD COLUMN IF NOT EXISTS migration_started_at TIMESTAMPTZ;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS migration_completed_at TIMESTAMPTZ;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS migration_notes TEXT;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS shadow_mode_enabled BOOLEAN DEFAULT false;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS legacy_host VARCHAR(200);
ALTER TABLE partners ADD COLUMN IF NOT EXISTS legacy_port INTEGER;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS legacy_username VARCHAR(100);
ALTER TABLE partners ADD COLUMN IF NOT EXISTS verification_transfer_count INTEGER DEFAULT 0;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS verification_last_at TIMESTAMPTZ;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS last_legacy_connection_at TIMESTAMPTZ;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS last_platform_connection_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_partner_migration ON partners(migration_status);

-- Migration events (immutable audit trail)
CREATE TABLE IF NOT EXISTS migration_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id UUID NOT NULL,
    partner_name VARCHAR(200),
    event_type VARCHAR(30) NOT NULL,
    details TEXT,
    actor VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_me_partner ON migration_events(partner_id);
CREATE INDEX IF NOT EXISTS idx_me_created ON migration_events(created_at);

-- Connection audit (every inbound connection)
CREATE TABLE IF NOT EXISTS connection_audits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) NOT NULL,
    source_ip VARCHAR(45),
    protocol VARCHAR(16),
    routed_to VARCHAR(16) NOT NULL,
    legacy_host VARCHAR(200),
    partner_id UUID,
    partner_name VARCHAR(200),
    success BOOLEAN DEFAULT true,
    failure_reason VARCHAR(500),
    connected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ca_username ON connection_audits(username);
CREATE INDEX IF NOT EXISTS idx_ca_partner ON connection_audits(partner_id);
CREATE INDEX IF NOT EXISTS idx_ca_routed ON connection_audits(routed_to);
CREATE INDEX IF NOT EXISTS idx_ca_ts ON connection_audits(connected_at);

-- =============================================================================
-- V31: Add all missing columns that JPA entities reference but were never
--      created by prior migrations. Fixes Docker fresh-start failures.
-- =============================================================================

-- transfer_accounts
ALTER TABLE transfer_accounts ADD COLUMN IF NOT EXISTS permissions JSONB DEFAULT '{"read":true,"write":true,"delete":false}'::jsonb;

-- security_profiles (5 JSONB columns for cipher/mac/kex/hostkey/tls lists)
ALTER TABLE security_profiles ADD COLUMN IF NOT EXISTS ssh_ciphers JSONB;
ALTER TABLE security_profiles ADD COLUMN IF NOT EXISTS ssh_macs JSONB;
ALTER TABLE security_profiles ADD COLUMN IF NOT EXISTS kex_algorithms JSONB;
ALTER TABLE security_profiles ADD COLUMN IF NOT EXISTS host_key_algorithms JSONB;
ALTER TABLE security_profiles ADD COLUMN IF NOT EXISTS tls_ciphers JSONB;

-- server_configs
ALTER TABLE server_configs ADD COLUMN IF NOT EXISTS properties JSONB;

-- webhook_connectors
ALTER TABLE webhook_connectors ADD COLUMN IF NOT EXISTS trigger_events JSONB;
ALTER TABLE webhook_connectors ADD COLUMN IF NOT EXISTS custom_headers JSONB;

-- tenants
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS branding JSONB;

-- auto_onboard_sessions
ALTER TABLE auto_onboard_sessions ADD COLUMN IF NOT EXISTS capabilities JSONB;
ALTER TABLE auto_onboard_sessions ADD COLUMN IF NOT EXISTS files_observed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE auto_onboard_sessions ADD COLUMN IF NOT EXISTS detected_patterns JSONB;
ALTER TABLE auto_onboard_sessions ADD COLUMN IF NOT EXISTS auto_flow_id VARCHAR(255);
ALTER TABLE auto_onboard_sessions ADD COLUMN IF NOT EXISTS security_profile_id VARCHAR(255);
ALTER TABLE auto_onboard_sessions ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

-- blockchain_anchors
ALTER TABLE blockchain_anchors ADD COLUMN IF NOT EXISTS proof TEXT;

-- partner_agreements
ALTER TABLE partner_agreements ADD COLUMN IF NOT EXISTS expected_days JSONB;

-- audit_logs (rename snake_case → match Hibernate implicit naming)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='audit_logs' AND column_name='sha256_checksum') THEN
        ALTER TABLE audit_logs RENAME COLUMN sha256_checksum TO sha256checksum;
    END IF;
END $$;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS metadata JSONB;

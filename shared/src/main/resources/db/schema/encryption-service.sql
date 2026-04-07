-- =============================================================================
-- TranzFer MFT — encryption-service schema (self-healing)
-- Only creates tables this service needs. IF NOT EXISTS = safe to re-run.
-- =============================================================================

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

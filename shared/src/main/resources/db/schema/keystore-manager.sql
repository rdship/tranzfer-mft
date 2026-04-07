-- =============================================================================
-- TranzFer MFT — keystore-manager schema (self-healing)
-- Only creates tables this service needs. IF NOT EXISTS = safe to re-run.
-- =============================================================================

CREATE TABLE IF NOT EXISTS managed_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_alias VARCHAR(255) NOT NULL UNIQUE,
    key_type VARCHAR(30),
    algorithm VARCHAR(50),
    key_size INT,
    public_key TEXT,
    encrypted_private_key TEXT,
    certificate TEXT,
    certificate_chain TEXT,
    issuer VARCHAR(500),
    subject VARCHAR(500),
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);

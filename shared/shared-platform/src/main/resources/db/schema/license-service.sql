-- =============================================================================
-- TranzFer MFT — license-service schema (self-healing)
-- Only creates tables this service needs. IF NOT EXISTS = safe to re-run.
-- =============================================================================

CREATE TABLE IF NOT EXISTS license_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    license_key VARCHAR(512),
    license_type VARCHAR(30),
    company VARCHAR(255),
    max_accounts INT,
    max_connections INT,
    features JSONB,
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS license_activations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    license_id UUID,
    machine_id VARCHAR(255),
    activated_at TIMESTAMPTZ DEFAULT now(),
    last_check TIMESTAMPTZ,
    active BOOLEAN DEFAULT true
);

CREATE TABLE IF NOT EXISTS installation_fingerprints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fingerprint VARCHAR(512) NOT NULL UNIQUE,
    hostname VARCHAR(255),
    os_info VARCHAR(255),
    created_at TIMESTAMPTZ DEFAULT now()
);

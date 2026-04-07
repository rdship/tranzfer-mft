-- =============================================================================
-- TranzFer MFT — storage-manager schema (self-healing)
-- Only creates tables this service needs. IF NOT EXISTS = safe to re-run.
-- =============================================================================

CREATE TABLE IF NOT EXISTS storage_objects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sha256 VARCHAR(64) NOT NULL UNIQUE,
    file_size BIGINT,
    content_type VARCHAR(128),
    storage_path VARCHAR(1024),
    ref_count INTEGER DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT now()
);

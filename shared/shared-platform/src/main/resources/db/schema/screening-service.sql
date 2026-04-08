-- =============================================================================
-- TranzFer MFT — screening-service schema (self-healing)
-- Only creates tables this service needs. IF NOT EXISTS = safe to re-run.
-- =============================================================================

CREATE TABLE IF NOT EXISTS sanctions_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(500),
    aliases TEXT,
    entity_type VARCHAR(50),
    source VARCHAR(100),
    source_id VARCHAR(255),
    country VARCHAR(50),
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS screening_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id VARCHAR(64),
    filename VARCHAR(512),
    scan_type VARCHAR(50),
    result VARCHAR(30),
    details TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dlp_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    description TEXT,
    patterns JSONB,
    action VARCHAR(30) DEFAULT 'BLOCK',
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS quarantine_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id VARCHAR(64),
    filename VARCHAR(512),
    account_username VARCHAR(255),
    original_path VARCHAR(1024),
    quarantine_path VARCHAR(1024),
    reason VARCHAR(255),
    detected_threat VARCHAR(255),
    detection_source VARCHAR(100),
    status VARCHAR(30) DEFAULT 'QUARANTINED',
    file_size_bytes BIGINT,
    sha256 VARCHAR(128),
    quarantined_at TIMESTAMPTZ DEFAULT now(),
    reviewed_by VARCHAR(255),
    reviewed_at TIMESTAMPTZ,
    review_notes TEXT
);

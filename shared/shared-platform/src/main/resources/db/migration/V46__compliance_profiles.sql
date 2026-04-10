-- ============================================================================
-- V46: Compliance profiles and violation tracking
-- ============================================================================

-- Compliance profiles
CREATE TABLE IF NOT EXISTS compliance_profiles (
    id UUID PRIMARY KEY,
    name VARCHAR(255) UNIQUE NOT NULL,
    description TEXT,
    severity VARCHAR(20) NOT NULL DEFAULT 'HIGH',
    allow_pci_data BOOLEAN NOT NULL DEFAULT FALSE,
    allow_phi_data BOOLEAN NOT NULL DEFAULT FALSE,
    allow_pii_data BOOLEAN NOT NULL DEFAULT TRUE,
    allow_classified_data BOOLEAN NOT NULL DEFAULT FALSE,
    max_allowed_risk_level VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    max_allowed_risk_score INTEGER NOT NULL DEFAULT 70,
    require_encryption BOOLEAN NOT NULL DEFAULT FALSE,
    require_screening BOOLEAN NOT NULL DEFAULT TRUE,
    require_checksum BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_file_extensions TEXT,
    blocked_file_extensions TEXT,
    max_file_size_bytes BIGINT,
    require_tls BOOLEAN NOT NULL DEFAULT TRUE,
    allow_anonymous_access BOOLEAN NOT NULL DEFAULT FALSE,
    require_mfa BOOLEAN NOT NULL DEFAULT FALSE,
    audit_all_transfers BOOLEAN NOT NULL DEFAULT TRUE,
    notify_on_violation BOOLEAN NOT NULL DEFAULT TRUE,
    violation_action VARCHAR(10) NOT NULL DEFAULT 'BLOCK',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- Compliance violations
CREATE TABLE IF NOT EXISTS compliance_violations (
    id UUID PRIMARY KEY,
    track_id VARCHAR(12) NOT NULL,
    profile_id UUID NOT NULL,
    profile_name VARCHAR(255),
    server_instance_id UUID,
    server_name VARCHAR(255),
    username VARCHAR(255),
    filename VARCHAR(1024),
    file_size_bytes BIGINT,
    violation_type VARCHAR(30) NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'HIGH',
    details TEXT,
    action VARCHAR(10) NOT NULL DEFAULT 'BLOCKED',
    ai_risk_level VARCHAR(20),
    ai_risk_score INTEGER,
    ai_block_reason TEXT,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_by VARCHAR(255),
    resolved_at TIMESTAMP,
    resolution_note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cv_track_id ON compliance_violations(track_id);
CREATE INDEX IF NOT EXISTS idx_cv_profile_id ON compliance_violations(profile_id);
CREATE INDEX IF NOT EXISTS idx_cv_severity ON compliance_violations(severity);
CREATE INDEX IF NOT EXISTS idx_cv_created_at ON compliance_violations(created_at);
CREATE INDEX IF NOT EXISTS idx_cv_resolved ON compliance_violations(resolved);

-- Add compliance_profile_id to server_instances
ALTER TABLE server_instances ADD COLUMN IF NOT EXISTS compliance_profile_id UUID;

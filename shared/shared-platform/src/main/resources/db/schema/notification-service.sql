-- =============================================================================
-- TranzFer MFT — notification-service schema (self-healing)
-- Only creates tables this service needs. IF NOT EXISTS = safe to re-run.
-- =============================================================================

CREATE TABLE IF NOT EXISTS notification_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    channel VARCHAR(30),
    subject_template TEXT,
    body_template TEXT,
    event_type VARCHAR(100),
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS notification_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255),
    event_type_pattern VARCHAR(255),
    channel VARCHAR(30),
    recipients JSONB,
    template_id UUID,
    enabled BOOLEAN DEFAULT true,
    conditions JSONB,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS notification_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(100),
    channel VARCHAR(30),
    recipient VARCHAR(255),
    subject VARCHAR(500),
    status VARCHAR(30),
    sent_at TIMESTAMPTZ,
    error_message TEXT,
    retry_count INT DEFAULT 0,
    rule_id UUID,
    track_id VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Partner-configurable webhooks: each partner/integration can register a URL that
-- receives HTTP POST when flow executions COMPLETE or FAIL for their transfers.
-- The dispatcher fires HMAC-SHA256-signed payloads so receivers can verify authenticity.

CREATE TABLE IF NOT EXISTS partner_webhooks (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_name VARCHAR(255) NOT NULL,
    url          VARCHAR(2048) NOT NULL,
    secret       VARCHAR(255),                          -- HMAC-SHA256 signing secret (optional)
    events       JSONB        NOT NULL DEFAULT '["FLOW_COMPLETED","FLOW_FAILED"]',
    active       BOOLEAN      NOT NULL DEFAULT true,
    description  VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_triggered TIMESTAMPTZ,
    total_calls  INTEGER      NOT NULL DEFAULT 0,
    failed_calls INTEGER      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_pw_active ON partner_webhooks(active);
CREATE INDEX IF NOT EXISTS idx_pw_partner ON partner_webhooks(partner_name);

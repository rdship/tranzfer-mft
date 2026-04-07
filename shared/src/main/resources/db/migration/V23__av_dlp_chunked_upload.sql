-- =============================================================================
-- V21: Antivirus Quarantine + DLP Policies + Chunked Upload Support
-- =============================================================================

-- ===== Quarantine Records =====
CREATE TABLE IF NOT EXISTS quarantine_records (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id          VARCHAR(64),
    filename          VARCHAR(500) NOT NULL,
    account_username  VARCHAR(255),
    original_path     TEXT NOT NULL,
    quarantine_path   TEXT NOT NULL,
    reason            VARCHAR(500) NOT NULL,
    detected_threat   VARCHAR(255),
    detection_source  VARCHAR(20) DEFAULT 'AV',
    status            VARCHAR(20) NOT NULL DEFAULT 'QUARANTINED',
    file_size_bytes   BIGINT,
    sha256            VARCHAR(64),
    quarantined_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    reviewed_by       VARCHAR(255),
    reviewed_at       TIMESTAMP WITH TIME ZONE,
    review_notes      VARCHAR(1000)
);

CREATE INDEX IF NOT EXISTS idx_quarantine_status ON quarantine_records(status);
CREATE INDEX IF NOT EXISTS idx_quarantine_track_id ON quarantine_records(track_id);
CREATE INDEX IF NOT EXISTS idx_quarantine_detected_at ON quarantine_records(quarantined_at);

-- ===== DLP Policies =====
CREATE TABLE IF NOT EXISTS dlp_policies (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(100) NOT NULL UNIQUE,
    description   VARCHAR(500),
    patterns      JSONB,
    action        VARCHAR(10) NOT NULL DEFAULT 'BLOCK',
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_dlp_policy_active ON dlp_policies(active);
CREATE INDEX IF NOT EXISTS idx_dlp_policy_name ON dlp_policies(name);

-- ===== Chunked Uploads =====
CREATE TABLE IF NOT EXISTS chunked_uploads (
    id                UUID PRIMARY KEY,
    filename          VARCHAR(500) NOT NULL,
    total_size        BIGINT NOT NULL,
    total_chunks      INT NOT NULL,
    received_chunks   INT NOT NULL DEFAULT 0,
    chunk_size        BIGINT NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    checksum          VARCHAR(64),
    account_username  VARCHAR(255),
    track_id          VARCHAR(64),
    content_type      VARCHAR(255),
    error_message     VARCHAR(500),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at      TIMESTAMP WITH TIME ZONE,
    expires_at        TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_chunked_upload_status ON chunked_uploads(status);
CREATE INDEX IF NOT EXISTS idx_chunked_upload_created ON chunked_uploads(created_at);

-- ===== Chunked Upload Chunks =====
CREATE TABLE IF NOT EXISTS chunked_upload_chunks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    upload_id     UUID NOT NULL REFERENCES chunked_uploads(id) ON DELETE CASCADE,
    chunk_number  INT NOT NULL,
    size          BIGINT NOT NULL,
    checksum      VARCHAR(64),
    storage_path  TEXT NOT NULL,
    received_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (upload_id, chunk_number)
);

CREATE INDEX IF NOT EXISTS idx_chunk_upload_id ON chunked_upload_chunks(upload_id);

-- ===== Insert default DLP policies =====
INSERT INTO dlp_policies (id, name, description, patterns, action, active) VALUES
(gen_random_uuid(), 'PCI Credit Cards', 'Detect credit card numbers (Visa, Mastercard, Amex, Discover)',
 '[{"type":"PCI_CREDIT_CARD","regex":"\\b4[0-9]{12}(?:[0-9]{3})?\\b","label":"Visa"},{"type":"PCI_CREDIT_CARD","regex":"\\b5[1-5][0-9]{14}\\b","label":"Mastercard"},{"type":"PCI_CREDIT_CARD","regex":"\\b3[47][0-9]{13}\\b","label":"Amex"},{"type":"PCI_CREDIT_CARD","regex":"\\b6(?:011|5[0-9]{2})[0-9]{12}\\b","label":"Discover"}]',
 'BLOCK', true),
(gen_random_uuid(), 'PII Social Security Numbers', 'Detect US Social Security Numbers',
 '[{"type":"PII_SSN","regex":"\\b\\d{3}-\\d{2}-\\d{4}\\b","label":"SSN (XXX-XX-XXXX)"},{"type":"PII_SSN","regex":"\\b\\d{9}\\b","label":"SSN (no dashes)"}]',
 'BLOCK', true),
(gen_random_uuid(), 'PII Email Addresses', 'Detect email addresses in file content',
 '[{"type":"PII_EMAIL","regex":"\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b","label":"Email"}]',
 'FLAG', true),
(gen_random_uuid(), 'PII Phone Numbers', 'Detect international phone numbers',
 '[{"type":"PII_PHONE","regex":"\\b\\+?[1-9]\\d{1,14}\\b","label":"E.164 Phone"},{"type":"PII_PHONE","regex":"\\b\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b","label":"US Phone"}]',
 'LOG', true),
(gen_random_uuid(), 'PCI IBAN Numbers', 'Detect IBAN bank account numbers',
 '[{"type":"PCI_IBAN","regex":"\\b[A-Z]{2}\\d{2}[A-Z0-9]{4,30}\\b","label":"IBAN"}]',
 'FLAG', true)
ON CONFLICT (name) DO NOTHING;

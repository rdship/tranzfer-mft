-- Dead Letter Queue persistence table
CREATE TABLE IF NOT EXISTS dead_letter_messages (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_queue   VARCHAR(255) NOT NULL,
    original_exchange VARCHAR(255) NOT NULL,
    routing_key      VARCHAR(255) NOT NULL,
    payload          TEXT         NOT NULL,
    error_message    TEXT,
    retry_count      INT          NOT NULL DEFAULT 0,
    max_retries      INT          NOT NULL DEFAULT 3,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    retried_at       TIMESTAMPTZ
);

CREATE INDEX idx_dlm_status         ON dead_letter_messages (status);
CREATE INDEX idx_dlm_original_queue ON dead_letter_messages (original_queue);
CREATE INDEX idx_dlm_created_at     ON dead_letter_messages (created_at);

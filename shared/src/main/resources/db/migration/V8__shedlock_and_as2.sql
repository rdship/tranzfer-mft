-- V8: ShedLock table for distributed scheduler locking + AS2/AS4 partnership support

-- ShedLock table (required by net.javacrumbs.shedlock)
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- AS2/AS4 Trading Partner Partnerships
CREATE TABLE IF NOT EXISTS as2_partnerships (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_name            VARCHAR(255) NOT NULL,
    partner_as2_id          VARCHAR(255) NOT NULL UNIQUE,
    our_as2_id              VARCHAR(255) NOT NULL,
    endpoint_url            VARCHAR(2000) NOT NULL,
    partner_certificate     TEXT,
    signing_algorithm       VARCHAR(50) DEFAULT 'SHA256',
    encryption_algorithm    VARCHAR(50) DEFAULT 'AES256',
    mdn_required            BOOLEAN DEFAULT true,
    mdn_async               BOOLEAN DEFAULT false,
    mdn_url                 VARCHAR(2000),
    compression_enabled     BOOLEAN DEFAULT false,
    protocol                VARCHAR(10) NOT NULL DEFAULT 'AS2',
    active                  BOOLEAN DEFAULT true,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- AS2/AS4 Message Tracking
CREATE TABLE IF NOT EXISTS as2_messages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id        VARCHAR(500) NOT NULL UNIQUE,
    partnership_id    UUID NOT NULL REFERENCES as2_partnerships(id),
    direction         VARCHAR(10) NOT NULL DEFAULT 'OUTBOUND',
    filename          VARCHAR(500),
    file_size         BIGINT,
    status            VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    mdn_received      BOOLEAN DEFAULT false,
    mdn_status        VARCHAR(100),
    error_message     TEXT,
    track_id          VARCHAR(50),
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_as2_messages_partnership ON as2_messages(partnership_id);
CREATE INDEX IF NOT EXISTS idx_as2_messages_status ON as2_messages(status);
CREATE INDEX IF NOT EXISTS idx_as2_messages_track ON as2_messages(track_id);

-- Add AS2 and AS4 to delivery_endpoints protocol support
-- (The Java enum already handles this; this comment documents the schema intent)

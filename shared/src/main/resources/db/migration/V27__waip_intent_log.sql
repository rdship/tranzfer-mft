-- V27: Write-Ahead Intent Protocol (WAIP) for VFS crash recovery
-- + Smart storage buckets (INLINE / STANDARD / CHUNKED)

-- ── Intent log: records intent BEFORE mutable VFS operations ───────────
CREATE TABLE IF NOT EXISTS vfs_intents (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL,
    op            VARCHAR(10)  NOT NULL CHECK (op IN ('WRITE','DELETE','MOVE')),
    path          VARCHAR(1024) NOT NULL,
    dest_path     VARCHAR(1024),
    storage_key   VARCHAR(64),
    track_id      VARCHAR(12),
    size_bytes    BIGINT DEFAULT 0,
    content_type  VARCHAR(128),
    status        VARCHAR(12)  NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','COMMITTED','ABORTED','RECOVERING')),
    pod_id        VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    CONSTRAINT fk_vfs_intent_account FOREIGN KEY (account_id)
        REFERENCES transfer_accounts(id)
);

CREATE INDEX IF NOT EXISTS idx_vfs_intents_pending
    ON vfs_intents(status, created_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_vfs_intents_resolved
    ON vfs_intents(status, resolved_at) WHERE status IN ('COMMITTED','ABORTED');

-- ── Optimistic locking on virtual_entries ──────────────────────────────
ALTER TABLE virtual_entries ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

-- ── Inline content for small files (< 64 KB) ──────────────────────────
ALTER TABLE virtual_entries ADD COLUMN IF NOT EXISTS inline_content BYTEA;
ALTER TABLE virtual_entries ADD COLUMN IF NOT EXISTS storage_bucket VARCHAR(10) DEFAULT 'STANDARD';

-- ── Chunk manifest for large files (> 64 MB) ──────────────────────────
CREATE TABLE IF NOT EXISTS vfs_chunks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id      UUID NOT NULL,
    chunk_index   INTEGER NOT NULL,
    storage_key   VARCHAR(64)  NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    sha256        VARCHAR(64)  NOT NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','STORED','VERIFIED')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_vfs_chunk_entry FOREIGN KEY (entry_id)
        REFERENCES virtual_entries(id)
);

CREATE INDEX IF NOT EXISTS idx_vfs_chunks_entry
    ON vfs_chunks(entry_id, chunk_index);

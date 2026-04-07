-- V28: Partition vfs_intents by status (hot/cold) for large-scale performance
--
-- Hot partition  (vfs_intents_active):   PENDING, RECOVERING — small, queried often
-- Cold partition (vfs_intents_resolved): COMMITTED, ABORTED  — large, rarely queried
--
-- Strategy: create new partitioned table, migrate data, swap names — all in one transaction.
-- PostgreSQL partitioned tables require the partition key in the primary key,
-- so PK becomes (id, status).

BEGIN;

-- ── 1. Create the new partitioned table ─────────────────────────────────
CREATE TABLE vfs_intents_partitioned (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    account_id    UUID         NOT NULL,
    op            VARCHAR(10)  NOT NULL CHECK (op IN ('WRITE','DELETE','MOVE')),
    path          VARCHAR(1024) NOT NULL,
    dest_path     VARCHAR(1024),
    storage_key   VARCHAR(64),
    track_id      VARCHAR(12),
    size_bytes    BIGINT       DEFAULT 0,
    content_type  VARCHAR(128),
    status        VARCHAR(12)  NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','COMMITTED','ABORTED','RECOVERING')),
    pod_id        VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    PRIMARY KEY (id, status),
    CONSTRAINT fk_vfs_intent_account_partitioned FOREIGN KEY (account_id)
        REFERENCES transfer_accounts(id)
) PARTITION BY LIST (status);

-- ── 2. Create partitions ────────────────────────────────────────────────
CREATE TABLE vfs_intents_active PARTITION OF vfs_intents_partitioned
    FOR VALUES IN ('PENDING', 'RECOVERING');

CREATE TABLE vfs_intents_resolved PARTITION OF vfs_intents_partitioned
    FOR VALUES IN ('COMMITTED', 'ABORTED');

-- ── 3. Create indexes on partitions (inherited by partitions automatically) ──
CREATE INDEX idx_vfs_intents_part_pending
    ON vfs_intents_partitioned(status, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_vfs_intents_part_recovering
    ON vfs_intents_partitioned(status, created_at)
    WHERE status = 'RECOVERING';

CREATE INDEX idx_vfs_intents_part_resolved
    ON vfs_intents_partitioned(status, resolved_at)
    WHERE status IN ('COMMITTED', 'ABORTED');

CREATE INDEX idx_vfs_intents_part_account
    ON vfs_intents_partitioned(account_id);

CREATE INDEX idx_vfs_intents_part_pod_pending
    ON vfs_intents_partitioned(pod_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_vfs_intents_part_created
    ON vfs_intents_partitioned(created_at DESC);

-- ── 4. Migrate existing data ────────────────────────────────────────────
INSERT INTO vfs_intents_partitioned
    (id, account_id, op, path, dest_path, storage_key, track_id,
     size_bytes, content_type, status, pod_id, created_at, resolved_at)
SELECT id, account_id, op, path, dest_path, storage_key, track_id,
       size_bytes, content_type, status, pod_id, created_at, resolved_at
FROM vfs_intents;

-- ── 5. Swap: rename old table out, new table in ────────────────────────
ALTER TABLE vfs_intents RENAME TO vfs_intents_old;
ALTER TABLE vfs_intents_partitioned RENAME TO vfs_intents;

-- Rename partitions for clarity (they keep their parent reference)
-- Partition names are stable after parent rename

-- Rename the FK constraint on the new table
ALTER TABLE vfs_intents RENAME CONSTRAINT fk_vfs_intent_account_partitioned
    TO fk_vfs_intent_account;

-- ── 6. Drop the old non-partitioned table ───────────────────────────────
DROP TABLE vfs_intents_old;

-- ── 7. Archive table for detached cold partitions (30-day rotation) ─────
CREATE TABLE IF NOT EXISTS vfs_intents_archive (
    id            UUID         NOT NULL,
    account_id    UUID         NOT NULL,
    op            VARCHAR(10)  NOT NULL,
    path          VARCHAR(1024) NOT NULL,
    dest_path     VARCHAR(1024),
    storage_key   VARCHAR(64),
    track_id      VARCHAR(12),
    size_bytes    BIGINT       DEFAULT 0,
    content_type  VARCHAR(128),
    status        VARCHAR(12)  NOT NULL,
    pod_id        VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL,
    resolved_at   TIMESTAMPTZ,
    archived_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id, status)
);

CREATE INDEX idx_vfs_intents_archive_created
    ON vfs_intents_archive(created_at);

CREATE INDEX idx_vfs_intents_archive_account
    ON vfs_intents_archive(account_id);

COMMIT;

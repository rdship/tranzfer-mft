-- V63: Partition file_transfer_records by uploaded_at (monthly partitions).
-- At 1M+ files/day, the table grows by 30M rows/month. Without partitioning:
--   - Activity Monitor date range queries scan entire table
--   - VACUUM runs against the full table (slow, locks)
--   - DELETE old records = row-by-row (slow)
-- With partitioning:
--   - Date range queries only scan relevant month(s) (partition pruning)
--   - VACUUM runs per-partition (fast, no full-table lock)
--   - DROP PARTITION is instant (vs DELETE millions of rows)
--
-- Strategy: RANGE partition by uploaded_at.
-- Default partition catches rows with NULL or out-of-range uploaded_at.
-- New monthly partitions must be created ahead of time (see comment at bottom).
--
-- NOTE: PostgreSQL cannot partition an existing table in-place.
-- This migration creates the partitioned table, migrates data, and swaps.
-- On a large existing table, run during a maintenance window.

-- Step 1: Create partitioned version of the table
CREATE TABLE IF NOT EXISTS file_transfer_records_partitioned (
    id                    UUID NOT NULL DEFAULT gen_random_uuid(),
    folder_mapping_id     UUID,
    track_id              VARCHAR(64) NOT NULL,
    original_filename     VARCHAR(512),
    source_file_path      VARCHAR(1024),
    destination_file_path VARCHAR(1024),
    archive_file_path     VARCHAR(1024),
    status                VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    error_message         TEXT,
    file_size_bytes       BIGINT,
    source_checksum       VARCHAR(128),
    destination_checksum  VARCHAR(128),
    retry_count           INTEGER NOT NULL DEFAULT 0,
    uploaded_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    routed_at             TIMESTAMP WITH TIME ZONE,
    downloaded_at         TIMESTAMP WITH TIME ZONE,
    completed_at          TIMESTAMP WITH TIME ZONE,
    updated_at            TIMESTAMP,
    source_account_id     UUID,
    flow_id               UUID,
    destination_account_id UUID,
    PRIMARY KEY (id, uploaded_at)
) PARTITION BY RANGE (uploaded_at);

-- Step 2: Create monthly partitions (2026-01 through 2027-06)
CREATE TABLE IF NOT EXISTS ftr_2026_01 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE IF NOT EXISTS ftr_2026_02 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE IF NOT EXISTS ftr_2026_03 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE IF NOT EXISTS ftr_2026_04 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE IF NOT EXISTS ftr_2026_05 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE IF NOT EXISTS ftr_2026_06 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE IF NOT EXISTS ftr_2026_07 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE IF NOT EXISTS ftr_2026_08 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE IF NOT EXISTS ftr_2026_09 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE IF NOT EXISTS ftr_2026_10 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE IF NOT EXISTS ftr_2026_11 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE IF NOT EXISTS ftr_2026_12 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS ftr_2027_01 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE IF NOT EXISTS ftr_2027_02 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE IF NOT EXISTS ftr_2027_03 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
CREATE TABLE IF NOT EXISTS ftr_2027_04 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2027-04-01') TO ('2027-05-01');
CREATE TABLE IF NOT EXISTS ftr_2027_05 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2027-05-01') TO ('2027-06-01');
CREATE TABLE IF NOT EXISTS ftr_2027_06 PARTITION OF file_transfer_records_partitioned
    FOR VALUES FROM ('2027-06-01') TO ('2027-07-01');

-- Default partition for rows outside defined ranges
CREATE TABLE IF NOT EXISTS ftr_default PARTITION OF file_transfer_records_partitioned DEFAULT;

-- Step 3: Migrate existing data
INSERT INTO file_transfer_records_partitioned
SELECT id, folder_mapping_id, track_id, original_filename, source_file_path,
       destination_file_path, archive_file_path, status, error_message,
       file_size_bytes, source_checksum, destination_checksum, retry_count,
       COALESCE(uploaded_at, now()), routed_at, downloaded_at, completed_at,
       updated_at, source_account_id, flow_id, destination_account_id
FROM file_transfer_records
ON CONFLICT DO NOTHING;

-- Step 4: Swap tables
ALTER TABLE IF EXISTS file_transfer_records RENAME TO file_transfer_records_old;
ALTER TABLE file_transfer_records_partitioned RENAME TO file_transfer_records;

-- Step 5: Recreate indexes on the partitioned table
-- (PostgreSQL auto-creates partition-local indexes)
CREATE UNIQUE INDEX IF NOT EXISTS idx_ftr_part_track_id ON file_transfer_records(track_id, uploaded_at);
CREATE INDEX IF NOT EXISTS idx_ftr_part_status_uploaded ON file_transfer_records(status, uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_ftr_part_source_account ON file_transfer_records(source_account_id, uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_ftr_part_dest_account ON file_transfer_records(destination_account_id);
CREATE INDEX IF NOT EXISTS idx_ftr_part_flow ON file_transfer_records(flow_id);
CREATE INDEX IF NOT EXISTS idx_ftr_part_folder_mapping ON file_transfer_records(folder_mapping_id);

-- Step 6: Refresh the materialized view to use the new table
REFRESH MATERIALIZED VIEW CONCURRENTLY transfer_activity_view;

-- MAINTENANCE: Create next month's partition BEFORE the month starts.
-- Run monthly: CREATE TABLE ftr_YYYY_MM PARTITION OF file_transfer_records
--   FOR VALUES FROM ('YYYY-MM-01') TO ('YYYY-{MM+1}-01');
-- Or use pg_partman extension for automatic partition management.

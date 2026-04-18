-- Write-ahead intent log for crash recovery
CREATE TABLE IF NOT EXISTS write_intents (
    id UUID PRIMARY KEY,
    temp_path VARCHAR(1024) NOT NULL,
    dest_path VARCHAR(1024),
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    stripe_count INTEGER DEFAULT 0,
    expected_size_bytes BIGINT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_wi_status ON write_intents(status);

-- Add movingTo and moveStartedAt to storage_objects for atomic tier moves
ALTER TABLE storage_objects ADD COLUMN IF NOT EXISTS moving_to VARCHAR(10);
ALTER TABLE storage_objects ADD COLUMN IF NOT EXISTS move_started_at TIMESTAMP;

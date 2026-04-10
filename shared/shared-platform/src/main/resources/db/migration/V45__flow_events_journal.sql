-- Append-only event journal for flow executions (DRP engine)
CREATE TABLE IF NOT EXISTS flow_events (
    id UUID PRIMARY KEY,
    track_id VARCHAR(12) NOT NULL,
    execution_id UUID,
    event_type VARCHAR(40) NOT NULL,
    step_index INTEGER,
    step_type VARCHAR(50),
    storage_key VARCHAR(128),
    virtual_path VARCHAR(1024),
    size_bytes BIGINT,
    duration_ms BIGINT,
    attempt_number INTEGER,
    status VARCHAR(20),
    error_message VARCHAR(2000),
    actor VARCHAR(500),
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fe_track_id ON flow_events(track_id);
CREATE INDEX IF NOT EXISTS idx_fe_execution_id ON flow_events(execution_id);
CREATE INDEX IF NOT EXISTS idx_fe_created_at ON flow_events(created_at);
CREATE INDEX IF NOT EXISTS idx_fe_type_track ON flow_events(event_type, track_id);

-- V59: Activity Monitor V2 — data quality + performance
-- G3: Destination account tracking for VIRTUAL-mode transfers
ALTER TABLE file_transfer_records ADD COLUMN IF NOT EXISTS destination_account_id UUID;
CREATE INDEX IF NOT EXISTS idx_ftr_dest_account ON file_transfer_records(destination_account_id);

-- Performance: FabricCheckpoint indexes (G12 from proposal)
CREATE INDEX IF NOT EXISTS idx_fc_track_id ON fabric_checkpoints(track_id);
CREATE INDEX IF NOT EXISTS idx_fc_status_lease ON fabric_checkpoints(status, lease_expires_at)
    WHERE status = 'IN_PROGRESS';
CREATE INDEX IF NOT EXISTS idx_fc_instance ON fabric_checkpoints(processing_instance)
    WHERE status = 'IN_PROGRESS';

-- Performance: Activity Monitor composite indexes
CREATE INDEX IF NOT EXISTS idx_ftr_uploaded_status ON file_transfer_records(uploaded_at DESC, status);
CREATE INDEX IF NOT EXISTS idx_ftr_source_account_uploaded ON file_transfer_records(source_account_id, uploaded_at DESC);

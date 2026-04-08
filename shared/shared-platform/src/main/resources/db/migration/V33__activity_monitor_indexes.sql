CREATE INDEX IF NOT EXISTS idx_ftr_status_uploaded ON file_transfer_records (status, uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_ftr_folder_mapping ON file_transfer_records (folder_mapping_id);

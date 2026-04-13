-- V56: Support VIRTUAL-mode file transfers in Activity Monitor
-- folder_mapping_id becomes nullable (VIRTUAL transfers have no FolderMapping)
-- source_account_id + flow_id added for direct tracking without FolderMapping

ALTER TABLE file_transfer_records ALTER COLUMN folder_mapping_id DROP NOT NULL;
ALTER TABLE file_transfer_records ALTER COLUMN source_file_path DROP NOT NULL;
ALTER TABLE file_transfer_records ALTER COLUMN destination_file_path DROP NOT NULL;

ALTER TABLE file_transfer_records ADD COLUMN IF NOT EXISTS source_account_id UUID;
ALTER TABLE file_transfer_records ADD COLUMN IF NOT EXISTS flow_id UUID;

CREATE INDEX IF NOT EXISTS idx_ftr_source_account ON file_transfer_records(source_account_id);
CREATE INDEX IF NOT EXISTS idx_ftr_flow ON file_transfer_records(flow_id);

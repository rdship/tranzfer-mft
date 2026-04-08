-- =============================================================================
-- V32: Add default_storage_mode to server_instances.
--      Determines whether accounts on this server use physical disk or VFS.
-- =============================================================================

ALTER TABLE server_instances ADD COLUMN IF NOT EXISTS default_storage_mode VARCHAR(10) DEFAULT 'PHYSICAL';

-- Add default_folder_mappings JSONB for server-level default routing config
ALTER TABLE server_instances ADD COLUMN IF NOT EXISTS default_folder_mappings JSONB;

-- ============================================================================
-- V26: Virtual Filesystem (Phantom Folders)
--
-- Replaces physical per-user directories with a DB-backed virtual catalog.
-- Folders are zero-cost rows. Files reference content-addressed storage (CAS).
-- Enables: zero-cost provisioning, cross-account dedup, sub-ms listings,
--          reference-counted storage, never-lose-a-file guarantee.
-- ============================================================================

-- Virtual filesystem entries (directories and files)
CREATE TABLE IF NOT EXISTS virtual_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL,
    path            VARCHAR(1024) NOT NULL,
    parent_path     VARCHAR(1024) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    type            VARCHAR(10) NOT NULL CHECK (type IN ('DIR', 'FILE')),
    storage_key     VARCHAR(64),
    size_bytes      BIGINT NOT NULL DEFAULT 0,
    content_type    VARCHAR(128),
    track_id        VARCHAR(12),
    access_count    INTEGER NOT NULL DEFAULT 0,
    last_accessed_at TIMESTAMPTZ,
    deleted         BOOLEAN NOT NULL DEFAULT false,
    permissions     VARCHAR(10) DEFAULT 'rwxr-xr-x',
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),

    CONSTRAINT fk_ve_account FOREIGN KEY (account_id) REFERENCES transfer_accounts(id)
);

-- Primary query: list children of a directory for a given account
CREATE INDEX IF NOT EXISTS idx_ve_account_parent ON virtual_entries(account_id, parent_path) WHERE deleted = false;

-- Lookup by full path (stat, exists, read)
CREATE INDEX IF NOT EXISTS idx_ve_account_path ON virtual_entries(account_id, path) WHERE deleted = false;

-- Reference counting for CAS cleanup
CREATE INDEX IF NOT EXISTS idx_ve_storage_key ON virtual_entries(storage_key) WHERE storage_key IS NOT NULL AND deleted = false;

-- Track ID lookup (routing integration)
CREATE INDEX IF NOT EXISTS idx_ve_track_id ON virtual_entries(track_id) WHERE track_id IS NOT NULL;

-- Prevent duplicate paths per account (unique virtual path per account)
CREATE UNIQUE INDEX IF NOT EXISTS idx_ve_unique_path ON virtual_entries(account_id, path) WHERE deleted = false;

-- Add storage mode to transfer_accounts (PHYSICAL = legacy, VIRTUAL = new)
ALTER TABLE transfer_accounts ADD COLUMN IF NOT EXISTS storage_mode VARCHAR(10) DEFAULT 'PHYSICAL';

-- Add reference count to storage_objects for CAS cleanup safety
-- (storage_objects table is in storage-manager's own schema — this is safe if shared DB)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'storage_objects') THEN
        ALTER TABLE storage_objects ADD COLUMN IF NOT EXISTS ref_count INTEGER NOT NULL DEFAULT 0;
    END IF;
END $$;

COMMENT ON TABLE virtual_entries IS 'Phantom Folder virtual filesystem. Folders are zero-cost DB rows. Files reference CAS via storage_key.';
COMMENT ON COLUMN virtual_entries.storage_key IS 'SHA-256 hash pointing to content-addressed storage. NULL for DIR entries.';
COMMENT ON COLUMN virtual_entries.parent_path IS 'Immediate parent directory path. Enables O(1) directory listings via index.';
COMMENT ON COLUMN transfer_accounts.storage_mode IS 'PHYSICAL = legacy filesystem, VIRTUAL = phantom folder VFS. New accounts default PHYSICAL for backward compat; migrate to VIRTUAL.';

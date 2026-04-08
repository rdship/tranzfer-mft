-- ============================================================================
-- V28: Inline Content Compression
--
-- Adds a 'compressed' flag to virtual_entries so INLINE content > 4 KB
-- can be gzip-compressed before storage. Existing rows default to false
-- (uncompressed), ensuring full backward compatibility.
-- ============================================================================

ALTER TABLE virtual_entries ADD COLUMN IF NOT EXISTS compressed BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN virtual_entries.compressed IS 'Whether inline_content is gzip-compressed. False for legacy uncompressed rows.';

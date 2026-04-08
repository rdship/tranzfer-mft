-- V28: Per-account storage bucket thresholds
-- Allows EDI-heavy accounts to use higher inline threshold (e.g. 256KB)
-- and bulk-transfer accounts to use lower thresholds.
-- NULL = use system defaults (vfs.inline-max-bytes / vfs.chunk-threshold-bytes).

ALTER TABLE transfer_accounts
    ADD COLUMN IF NOT EXISTS inline_max_bytes     BIGINT,
    ADD COLUMN IF NOT EXISTS chunk_threshold_bytes BIGINT;

COMMENT ON COLUMN transfer_accounts.inline_max_bytes
    IS 'Per-account override for VFS inline storage threshold in bytes (NULL = system default)';
COMMENT ON COLUMN transfer_accounts.chunk_threshold_bytes
    IS 'Per-account override for VFS chunked storage threshold in bytes (NULL = system default)';

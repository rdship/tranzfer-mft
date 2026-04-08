-- Add updated_at column for retry backoff timing
ALTER TABLE file_transfer_records ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

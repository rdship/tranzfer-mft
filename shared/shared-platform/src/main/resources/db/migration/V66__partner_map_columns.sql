-- V50: Add partner map management columns to edi_conversion_maps
-- parent_map_id: tracks which standard map a partner map was cloned from
-- status: lifecycle state (DRAFT, ACTIVE, INACTIVE, DEPRECATED)

ALTER TABLE edi_conversion_maps ADD COLUMN IF NOT EXISTS parent_map_id VARCHAR(100);
ALTER TABLE edi_conversion_maps ADD COLUMN IF NOT EXISTS status VARCHAR(20);

-- Backfill: existing active maps get status ACTIVE, inactive get INACTIVE
UPDATE edi_conversion_maps SET status = 'ACTIVE' WHERE active = true AND status IS NULL;
UPDATE edi_conversion_maps SET status = 'INACTIVE' WHERE active = false AND status IS NULL;

-- Index for partner + status lookups
CREATE INDEX IF NOT EXISTS idx_ecm_partner_status ON edi_conversion_maps (partner_id, status);
CREATE INDEX IF NOT EXISTS idx_ecm_parent_map ON edi_conversion_maps (parent_map_id);

-- V52: Add avg_confidence column to edi_conversion_maps for incremental learning metrics
ALTER TABLE edi_conversion_maps ADD COLUMN IF NOT EXISTS avg_confidence DOUBLE PRECISION;

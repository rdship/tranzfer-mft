-- V37 — Add hits JSONB column to screening_results (missed in initial schema)
ALTER TABLE screening_results ADD COLUMN IF NOT EXISTS hits jsonb;

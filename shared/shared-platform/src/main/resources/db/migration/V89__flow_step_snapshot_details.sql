-- R105b: step-level semantic detail for Activity Monitor deep-drill.
-- Each FlowStepSnapshot row can now carry a JSON blob describing what the
-- step actually did (e.g. "EDI-X12-850 → JSON, 12 rows", "AES-256-GCM
-- encrypted with key from keystore-manager", "script exited 0 in 340ms").
-- Used by Activity Monitor UI drill-down and by the AI Copilot to explain
-- transfer journeys in plain English.
--
-- All columns are nullable — existing rows remain valid, and step types that
-- don't produce semantic detail simply leave the field null.

ALTER TABLE flow_step_snapshots
    ADD COLUMN IF NOT EXISTS step_details_json TEXT,
    ADD COLUMN IF NOT EXISTS rows_processed    BIGINT,
    ADD COLUMN IF NOT EXISTS processing_instance VARCHAR(120),
    ADD COLUMN IF NOT EXISTS attempt_count     INTEGER,
    ADD COLUMN IF NOT EXISTS step_config_json  TEXT;

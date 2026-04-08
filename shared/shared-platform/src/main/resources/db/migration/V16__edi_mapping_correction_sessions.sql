-- V16: EDI Mapping Correction Sessions
-- Supports natural language mapping correction loop for partners.

CREATE TABLE IF NOT EXISTS edi_mapping_correction_sessions (
    id                          UUID PRIMARY KEY,
    partner_id                  VARCHAR(100)  NOT NULL,
    map_key                     VARCHAR(200)  NOT NULL,
    base_map_id                 UUID,
    base_map_version            INT           DEFAULT 0,
    status                      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    source_format               VARCHAR(30)   NOT NULL,
    source_type                 VARCHAR(30),
    target_format               VARCHAR(30)   NOT NULL,
    target_type                 VARCHAR(30),
    current_field_mappings_json TEXT          NOT NULL DEFAULT '[]',
    correction_history          TEXT          NOT NULL DEFAULT '[]',
    sample_input_content        TEXT          NOT NULL,
    sample_expected_output      TEXT,
    latest_test_output          TEXT,
    latest_test_comparison      TEXT,
    correction_count            INT           NOT NULL DEFAULT 0,
    flow_id                     UUID,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at                  TIMESTAMPTZ   NOT NULL DEFAULT (now() + INTERVAL '24 hours')
);

CREATE INDEX IF NOT EXISTS idx_mcs_partner  ON edi_mapping_correction_sessions (partner_id);
CREATE INDEX IF NOT EXISTS idx_mcs_status   ON edi_mapping_correction_sessions (status);
CREATE INDEX IF NOT EXISTS idx_mcs_map_key  ON edi_mapping_correction_sessions (map_key);
CREATE INDEX IF NOT EXISTS idx_mcs_expires  ON edi_mapping_correction_sessions (status, expires_at);

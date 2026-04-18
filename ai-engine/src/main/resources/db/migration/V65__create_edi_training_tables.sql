-- R125: create the three EDI-training tables if they are missing.
--
-- Problem observed in the R124 audit: every /api/v1/edi/training/** endpoint
-- returned 500 because the backing JPA queries targeted tables that were
-- never created by any Flyway migration. V66 and V67 are ALTER-only on
-- edi_conversion_maps; they assume an earlier CREATE that does not exist in
-- the current codebase. The tables had historically been created by
-- Hibernate ddl-auto=update, but ddl-auto is now "none", so a fresh install
-- could never land the tables and every EDI list endpoint crashed.
--
-- Numbered V65 so it runs BEFORE V66/V67 on a fresh install (V65 < V66 <
-- V67). On the tester's DB (schema=92), this is out of order — requires
-- spring.flyway.out-of-order=true in ai-engine's application.yml, which
-- R125 adds. The tables already exist there so this becomes a no-op via
-- IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS edi_training_samples (
    id                UUID           PRIMARY KEY,
    source_format     VARCHAR(30)    NOT NULL,
    source_type       VARCHAR(30),
    source_version    VARCHAR(20),
    target_format     VARCHAR(30)    NOT NULL,
    target_type       VARCHAR(30),
    partner_id        VARCHAR(100),
    input_content     TEXT           NOT NULL,
    output_content    TEXT           NOT NULL,
    notes             TEXT,
    quality_score     INT            NOT NULL DEFAULT 0,
    validated         BOOLEAN        NOT NULL DEFAULT FALSE,
    usage_count       INT            NOT NULL DEFAULT 0,
    created_at        TIMESTAMP,
    updated_at        TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ets_source_format ON edi_training_samples (source_format, source_type);
CREATE INDEX IF NOT EXISTS idx_ets_target_format ON edi_training_samples (target_format);
CREATE INDEX IF NOT EXISTS idx_ets_partner      ON edi_training_samples (partner_id);
CREATE INDEX IF NOT EXISTS idx_ets_map_key      ON edi_training_samples (source_format, source_type, target_format, partner_id);

CREATE TABLE IF NOT EXISTS edi_conversion_maps (
    id                           UUID              PRIMARY KEY,
    map_key                      VARCHAR(200)      NOT NULL,
    name                         VARCHAR(300),
    source_format                VARCHAR(30)       NOT NULL,
    source_type                  VARCHAR(30),
    target_format                VARCHAR(30)       NOT NULL,
    target_type                  VARCHAR(30),
    partner_id                   VARCHAR(100),
    parent_map_id                VARCHAR(100),
    status                       VARCHAR(20),
    version                      INT               NOT NULL DEFAULT 0,
    active                       BOOLEAN           NOT NULL DEFAULT FALSE,
    confidence                   INT               NOT NULL DEFAULT 0,
    sample_count                 INT               NOT NULL DEFAULT 0,
    field_mapping_count          INT               NOT NULL DEFAULT 0,
    field_mappings_json          TEXT              NOT NULL,
    generated_code               TEXT,
    unmapped_source_fields_json  TEXT,
    unmapped_target_fields_json  TEXT,
    training_session_id          VARCHAR(36),
    test_accuracy                INTEGER,
    usage_count                  BIGINT            NOT NULL DEFAULT 0,
    last_used_at                 TIMESTAMP,
    avg_confidence               DOUBLE PRECISION,
    created_at                   TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ecm_map_key         ON edi_conversion_maps (map_key);
CREATE INDEX IF NOT EXISTS idx_ecm_active          ON edi_conversion_maps (map_key, active);
CREATE INDEX IF NOT EXISTS idx_ecm_partner        ON edi_conversion_maps (partner_id);
CREATE INDEX IF NOT EXISTS idx_ecm_partner_status ON edi_conversion_maps (partner_id, status);
CREATE INDEX IF NOT EXISTS idx_ecm_parent_map     ON edi_conversion_maps (parent_map_id);

CREATE TABLE IF NOT EXISTS edi_training_sessions (
    id                          UUID            PRIMARY KEY,
    map_key                     VARCHAR(200)    NOT NULL,
    status                      VARCHAR(20)     NOT NULL,
    training_sample_count       INT             NOT NULL DEFAULT 0,
    test_sample_count           INT             NOT NULL DEFAULT 0,
    strategies_used             VARCHAR(500),
    produced_map_version        INT             NOT NULL DEFAULT 0,
    produced_map_confidence     INT             NOT NULL DEFAULT 0,
    test_accuracy               INTEGER,
    field_mappings_discovered   INT             NOT NULL DEFAULT 0,
    improvement_delta           INT             NOT NULL DEFAULT 0,
    duration_ms                 BIGINT          NOT NULL DEFAULT 0,
    error_message               TEXT,
    training_report             TEXT,
    triggered_by                VARCHAR(200),
    started_at                  TIMESTAMP,
    completed_at                TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ets_status    ON edi_training_sessions (status);
CREATE INDEX IF NOT EXISTS idx_ets_sess_map_key ON edi_training_sessions (map_key);

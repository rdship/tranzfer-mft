-- =============================================================================
-- V24: Flow Match Criteria Engine
-- Adds composable JSONB match criteria to file_flows.
-- Migrates existing legacy fields into matchCriteria JSON.
-- =============================================================================

-- 1. New columns on file_flows
ALTER TABLE file_flows ADD COLUMN IF NOT EXISTS match_criteria JSONB;
ALTER TABLE file_flows ADD COLUMN IF NOT EXISTS direction VARCHAR(20);

-- 2. Indexes
CREATE INDEX IF NOT EXISTS idx_ff_match_criteria ON file_flows USING GIN (match_criteria) WHERE match_criteria IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ff_direction ON file_flows(direction);

-- 3. Data migration: convert legacy fields to matchCriteria JSON
--    Each non-null legacy field becomes a condition in an AND group.
--    Flows with no legacy fields get an empty AND (matches everything).
UPDATE file_flows SET match_criteria = (
    SELECT jsonb_build_object(
        'operator', 'AND',
        'conditions', COALESCE(
            (SELECT jsonb_agg(cond) FROM (
                SELECT jsonb_build_object('field', 'filename', 'op', 'REGEX', 'value', filename_pattern) AS cond
                WHERE filename_pattern IS NOT NULL AND filename_pattern != ''
                UNION ALL
                SELECT jsonb_build_object('field', 'sourcePath', 'op', 'CONTAINS', 'value', source_path)
                WHERE source_path IS NOT NULL AND source_path != ''
                UNION ALL
                SELECT jsonb_build_object('field', 'sourceAccountId', 'op', 'EQ', 'value', source_account_id::text)
                WHERE source_account_id IS NOT NULL
                UNION ALL
                SELECT jsonb_build_object('field', 'partnerId', 'op', 'EQ', 'value', partner_id::text)
                WHERE partner_id IS NOT NULL
            ) sub),
            '[]'::jsonb
        )
    )
) WHERE match_criteria IS NULL;

-- 4. Allow UNMATCHED flow executions (no flow reference)
ALTER TABLE flow_executions ALTER COLUMN flow_id DROP NOT NULL;

-- 5. Audit column for matched criteria snapshot
ALTER TABLE flow_executions ADD COLUMN IF NOT EXISTS matched_criteria JSONB;

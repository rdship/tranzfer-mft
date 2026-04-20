-- R134 / BUG 14: license_records.services column missing.
--
-- LicenseRecord entity declares `services` as @JdbcTypeCode jsonb, but
-- V1__baseline.sql never added the column (entity/schema drift, same
-- class as R125 V93 EDI and R127 sha256_checksum). Runtime SELECT blew
-- up with "column lr1_0.services does not exist".
--
-- The column is a jsonb array of {componentId, tier} records describing
-- which licensable components the key entitles. NULL is a valid empty
-- license (trial / legacy rows before the services column existed).

ALTER TABLE license_records
    ADD COLUMN IF NOT EXISTS services JSONB NULL;

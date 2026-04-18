-- R110 (P0): refresh_tokens table.
--
-- Originally placed at onboarding-api/src/main/resources/db/migration/V70__refresh_tokens.sql,
-- which the central db-migrate runner never scans — Flyway runs only against
-- shared-platform/.../db/migration/. Services carry spring.flyway.enabled=false
-- for boot-time savings, so V70 was applied in NEITHER place on fresh builds.
-- Result: every login hit a 42P01 "relation refresh_tokens does not exist"
-- and returned 500 (R105-R109 tester acceptance, 2026-04-18).
--
-- V70 remains in onboarding-api as a historical stub (Flyway-gated-off) and is
-- superseded here by V92 under the central pattern. The CREATE TABLE is
-- IF NOT EXISTS so environments that did get V70 applied via a legacy code
-- path won't error on re-apply.

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token VARCHAR(64) NOT NULL UNIQUE,
    user_email VARCHAR(255) NOT NULL,
    user_role VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT false,
    client_ip VARCHAR(45),
    user_agent VARCHAR(512)
);

CREATE INDEX IF NOT EXISTS idx_refresh_token ON refresh_tokens (token) WHERE NOT revoked;
CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_tokens (user_email) WHERE NOT revoked;
CREATE INDEX IF NOT EXISTS idx_refresh_expiry ON refresh_tokens (expires_at);

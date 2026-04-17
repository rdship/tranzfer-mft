-- ─────────────────────────────────────────────────────────────────────────────
-- V88: FTP_WEB per-listener advanced configuration
--
-- Brings FTP_WEB listeners to parity with SFTP's and FTP's per-listener config
-- surface. All columns are optional; NULL means "fall back to service-wide
-- default (ftpweb.* application properties or env vars)".
--
--  ftpweb_session_timeout_seconds
--      Idle HTTP session timeout for the portal. NULL = service default.
--
--  ftpweb_max_upload_bytes
--      Per-request body size cap (0 = unlimited, NULL = service default).
--
--  ftpweb_tls_cert_alias
--      Keystore Manager alias for HTTPS certificate. NULL = service keystore.
--      Takes effect only when multi-listener Tomcat routing lands (tracked
--      separately); included now so the data model is forward-compatible.
--
--  ftpweb_portal_title
--      Branded title shown in the partner portal header. NULL = default.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE server_instances
    ADD COLUMN IF NOT EXISTS ftpweb_session_timeout_seconds INTEGER,
    ADD COLUMN IF NOT EXISTS ftpweb_max_upload_bytes        BIGINT,
    ADD COLUMN IF NOT EXISTS ftpweb_tls_cert_alias          VARCHAR(128),
    ADD COLUMN IF NOT EXISTS ftpweb_portal_title            VARCHAR(255);

-- Sanity constraints.
ALTER TABLE server_instances
    DROP CONSTRAINT IF EXISTS ck_ftpweb_session_timeout;
ALTER TABLE server_instances
    ADD CONSTRAINT ck_ftpweb_session_timeout
    CHECK (ftpweb_session_timeout_seconds IS NULL OR ftpweb_session_timeout_seconds >= 0);

ALTER TABLE server_instances
    DROP CONSTRAINT IF EXISTS ck_ftpweb_max_upload;
ALTER TABLE server_instances
    ADD CONSTRAINT ck_ftpweb_max_upload
    CHECK (ftpweb_max_upload_bytes IS NULL OR ftpweb_max_upload_bytes >= 0);

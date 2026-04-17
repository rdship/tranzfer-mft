-- ─────────────────────────────────────────────────────────────────────────────
-- V87: FTP per-listener advanced configuration
--
-- Brings FTP/FTPS listeners to parity with SFTP's per-listener config surface.
-- All columns are optional; NULL means "fall back to service-wide default
-- (ftp.* application properties)". Existing rows remain valid.
--
--  ftp_passive_port_from / ftp_passive_port_to
--      Per-listener PASV port range. Global ftp.passive-ports still applies
--      when NULL. Non-null requires both ends and from<=to.
--
--  ftp_tls_cert_alias
--      Name of a key in Keystore Manager (type=TLS) to use for this FTPS
--      listener. NULL = fall back to keystore-manager default alias.
--
--  ftp_prot_required (NONE | C | P)
--      PROT level enforcement on data channel. NULL = service-wide default.
--
--  ftp_banner_message
--      Welcome banner (230-like) shown on FTP connect. NULL = no banner.
--
--  ftp_implicit_tls
--      When true, listener runs in implicit FTPS mode (direct TLS, port 990).
--      NULL = service-wide default (ftp.ftps.implicit).
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE server_instances
    ADD COLUMN IF NOT EXISTS ftp_passive_port_from INTEGER,
    ADD COLUMN IF NOT EXISTS ftp_passive_port_to   INTEGER,
    ADD COLUMN IF NOT EXISTS ftp_tls_cert_alias    VARCHAR(128),
    ADD COLUMN IF NOT EXISTS ftp_prot_required     VARCHAR(8),
    ADD COLUMN IF NOT EXISTS ftp_banner_message    TEXT,
    ADD COLUMN IF NOT EXISTS ftp_implicit_tls      BOOLEAN;

-- Sanity: if either passive port bound is set, both must be, and from<=to.
ALTER TABLE server_instances
    DROP CONSTRAINT IF EXISTS ck_ftp_passive_range;
ALTER TABLE server_instances
    ADD CONSTRAINT ck_ftp_passive_range
    CHECK (
        (ftp_passive_port_from IS NULL AND ftp_passive_port_to IS NULL)
        OR
        (ftp_passive_port_from IS NOT NULL
            AND ftp_passive_port_to IS NOT NULL
            AND ftp_passive_port_from >= 1024
            AND ftp_passive_port_to   <= 65535
            AND ftp_passive_port_from <= ftp_passive_port_to)
    );

-- PROT is a fixed enum of 3 values.
ALTER TABLE server_instances
    DROP CONSTRAINT IF EXISTS ck_ftp_prot_required;
ALTER TABLE server_instances
    ADD CONSTRAINT ck_ftp_prot_required
    CHECK (ftp_prot_required IS NULL OR ftp_prot_required IN ('NONE','C','P'));

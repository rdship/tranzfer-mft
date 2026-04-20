-- R134m Phase 3a: HTTPS per-listener configuration on ServerInstance.
--
-- Until now, HTTPS was in the admin protocol picker but had no form
-- section and no entity fields — selecting HTTPS created a row that
-- couldn't bind (R130 UI audit). Adding the three fields that the
-- HTTPS listener service will read to build a working endpoint:
--
--   https_tls_cert_alias     — Keystore Manager alias for the server cert
--   https_client_cert_required — mutual-TLS toggle; when true, clients
--                                must present a valid cert trusted by
--                                the service's trust store
--   https_allowed_ciphers    — comma-separated cipher suite allowlist;
--                              null means "inherit JVM defaults"
--
-- All nullable — pre-existing SFTP/FTP/FTP_WEB rows keep working.

ALTER TABLE server_instances
    ADD COLUMN IF NOT EXISTS https_tls_cert_alias         VARCHAR(100),
    ADD COLUMN IF NOT EXISTS https_client_cert_required   BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS https_allowed_ciphers        TEXT;

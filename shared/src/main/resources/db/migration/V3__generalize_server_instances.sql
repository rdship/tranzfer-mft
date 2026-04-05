-- V3: Generalize server instances to support all protocols (SFTP, FTP, FTP_WEB, HTTPS)
-- Renames sftp_server_instances → server_instances and adds protocol column.
-- Ensures all protocol server configs survive a crash — nothing is lost.

-- ===== Rename table =====
ALTER TABLE sftp_server_instances RENAME TO server_instances;

-- ===== Add protocol column =====
ALTER TABLE server_instances ADD COLUMN IF NOT EXISTS protocol VARCHAR(16) NOT NULL DEFAULT 'SFTP';

-- Tag existing rows as SFTP
UPDATE server_instances SET protocol = 'SFTP' WHERE protocol = 'SFTP';

-- Index for protocol-based lookups
CREATE INDEX IF NOT EXISTS idx_si_protocol ON server_instances(protocol);
CREATE INDEX IF NOT EXISTS idx_si_protocol_active ON server_instances(protocol, active);

-- ===== Seed default FTP server instances =====
INSERT INTO server_instances (instance_id, name, description, internal_host, internal_port, external_host, external_port, protocol, active)
VALUES ('ftp-1', 'Primary FTP Server', 'Default FTP server instance', 'ftp-service', 21, 'localhost', 21, 'FTP', true)
ON CONFLICT (instance_id) DO NOTHING;

INSERT INTO server_instances (instance_id, name, description, internal_host, internal_port, external_host, external_port, protocol, active)
VALUES ('ftp-2', 'Secondary FTP Server', 'Secondary FTP server instance', 'ftp-service-2', 2121, 'localhost', 2121, 'FTP', true)
ON CONFLICT (instance_id) DO NOTHING;

-- ===== Seed default FTP-Web (HTTP/S) server instances =====
INSERT INTO server_instances (instance_id, name, description, internal_host, internal_port, external_host, external_port, protocol, active)
VALUES ('ftpweb-1', 'Primary FTP-Web Server', 'Default HTTP/S file transfer instance', 'ftp-web-service', 8083, 'localhost', 8083, 'FTP_WEB', true)
ON CONFLICT (instance_id) DO NOTHING;

INSERT INTO server_instances (instance_id, name, description, internal_host, internal_port, external_host, external_port, protocol, active)
VALUES ('ftpweb-2', 'Secondary FTP-Web Server', 'Secondary HTTP/S file transfer instance', 'ftp-web-service-2', 8098, 'localhost', 8098, 'FTP_WEB', true)
ON CONFLICT (instance_id) DO NOTHING;

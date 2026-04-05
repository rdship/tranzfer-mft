-- ============================================================================
-- V4: Platform Settings — DB-backed configuration for all microservices
--
-- Gartner CRUD recommendation: every piece of configuration lives in the
-- database so it survives crashes, supports per-environment overrides
-- (DEV / TEST / CERT / STAGING / PROD), and is manageable via Admin UI.
-- ============================================================================

CREATE TABLE IF NOT EXISTS platform_settings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    setting_key     VARCHAR(128)  NOT NULL,
    setting_value   TEXT,
    environment     VARCHAR(16)   NOT NULL DEFAULT 'PROD',
    service_name    VARCHAR(32)   NOT NULL DEFAULT 'GLOBAL',
    data_type       VARCHAR(16)   NOT NULL DEFAULT 'STRING',
    description     TEXT,
    category        VARCHAR(64),
    sensitive       BOOLEAN       NOT NULL DEFAULT FALSE,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    UNIQUE (setting_key, environment, service_name)
);

CREATE INDEX IF NOT EXISTS idx_ps_env ON platform_settings(environment);
CREATE INDEX IF NOT EXISTS idx_ps_service ON platform_settings(service_name);
CREATE INDEX IF NOT EXISTS idx_ps_env_service ON platform_settings(environment, service_name);
CREATE INDEX IF NOT EXISTS idx_ps_category ON platform_settings(category);

-- ============================================================================
-- Seed: PROD environment defaults (mirrors current application.yml values)
-- ============================================================================

-- ── GLOBAL (apply to every microservice) ────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('jwt.expiration',           '900000',                      'PROD', 'GLOBAL', 'INTEGER', 'JWT token expiration in milliseconds (default 15 min)',   'Security',  false),
('platform.track-id.prefix', 'TRZ',                         'PROD', 'GLOBAL', 'STRING',  'Prefix for generated transfer tracking IDs',              'Platform',  false),
('platform.flow.max-concurrent', '50',                      'PROD', 'GLOBAL', 'INTEGER', 'Maximum concurrent file-flow executions',                 'Platform',  false),
('rabbitmq.exchange',        'file-transfer.events',         'PROD', 'GLOBAL', 'STRING',  'RabbitMQ exchange name for platform events',              'Messaging', false),
('server.max-upload-size',   '512MB',                        'PROD', 'GLOBAL', 'STRING',  'Max file upload size (Spring multipart)',                 'Platform',  false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── SFTP Service ────────────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('sftp.port',           '2222',            'PROD', 'SFTP', 'INTEGER', 'SFTP listen port',                          'Network',  false),
('sftp.home-base',      '/data/sftp',      'PROD', 'SFTP', 'STRING',  'Base directory for SFTP user home folders', 'Storage',  false),
('sftp.host-key-path',  './sftp_host_key', 'PROD', 'SFTP', 'STRING',  'Path to SFTP host key file',               'Security', false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── FTP Service ─────────────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('ftp.port',           '21',           'PROD', 'FTP', 'INTEGER', 'FTP listen port',                           'Network',  false),
('ftp.passive-ports',  '21000-21010',  'PROD', 'FTP', 'STRING',  'FTP passive port range',                    'Network',  false),
('ftp.public-host',    '127.0.0.1',    'PROD', 'FTP', 'STRING',  'Public host advertised for passive mode',   'Network',  false),
('ftp.home-base',      '/data/ftp',    'PROD', 'FTP', 'STRING',  'Base directory for FTP user home folders',  'Storage',  false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── FTP-Web Service ─────────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('ftpweb.home-base',   '/data/ftpweb', 'PROD', 'FTP_WEB', 'STRING',  'Base directory for FTP-Web user home folders', 'Storage',  false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── Gateway Service ─────────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('gateway.sftp.port',            '2220',            'PROD', 'GATEWAY', 'INTEGER', 'Gateway external SFTP listen port',     'Network',  false),
('gateway.sftp.host-key-path',   './gateway_host_key', 'PROD', 'GATEWAY', 'STRING', 'Path to gateway SFTP host key',      'Security', false),
('gateway.ftp.port',             '2121',            'PROD', 'GATEWAY', 'INTEGER', 'Gateway external FTP listen port',      'Network',  false),
('gateway.internal-sftp-host',   'sftp-service',    'PROD', 'GATEWAY', 'STRING',  'Internal SFTP backend host',            'Network',  false),
('gateway.internal-sftp-port',   '2222',            'PROD', 'GATEWAY', 'INTEGER', 'Internal SFTP backend port',            'Network',  false),
('gateway.internal-ftp-host',    'ftp-service',     'PROD', 'GATEWAY', 'STRING',  'Internal FTP backend host',             'Network',  false),
('gateway.internal-ftp-port',    '21',              'PROD', 'GATEWAY', 'INTEGER', 'Internal FTP backend port',             'Network',  false),
('gateway.internal-ftpweb-host', 'ftp-web-service', 'PROD', 'GATEWAY', 'STRING',  'Internal FTP-Web backend host',         'Network',  false),
('gateway.internal-ftpweb-port', '8083',            'PROD', 'GATEWAY', 'INTEGER', 'Internal FTP-Web backend port',         'Network',  false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── Analytics Service ───────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('analytics.aggregation-interval-minutes', '60',   'PROD', 'ANALYTICS', 'INTEGER', 'Metrics aggregation interval (minutes)',        'Analytics', false),
('analytics.prediction-window-hours',      '48',   'PROD', 'ANALYTICS', 'INTEGER', 'Prediction lookahead window (hours)',           'Analytics', false),
('analytics.alert-error-rate-threshold',   '0.05', 'PROD', 'ANALYTICS', 'DECIMAL', 'Error rate threshold to trigger alerts (0-1)',  'Analytics', false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── Screening Service ───────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('screening.ofac.sdn-url',      'https://www.treasury.gov/ofac/downloads/sdn.csv',                                  'PROD', 'SCREENING', 'STRING',  'OFAC SDN list download URL',                     'Compliance', false),
('screening.eu-sanctions-url',  'https://webgate.ec.europa.eu/fsd/fsf/public/files/csvFullSanctionsList/content',    'PROD', 'SCREENING', 'STRING',  'EU sanctions list download URL',                  'Compliance', false),
('screening.un-sanctions-url',  'https://scsanctions.un.org/resources/xml/en/consolidated.xml',                      'PROD', 'SCREENING', 'STRING',  'UN sanctions list download URL',                  'Compliance', false),
('screening.match-threshold',   '0.82',  'PROD', 'SCREENING', 'DECIMAL', 'Fuzzy match threshold (0-1) for sanctions screening',  'Compliance', false),
('screening.default-action',    'BLOCK', 'PROD', 'SCREENING', 'STRING',  'Default action on match: BLOCK, FLAG, or ALLOW',       'Compliance', false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── License Service ─────────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('license.trial-days',             '30',                      'PROD', 'LICENSE', 'INTEGER', 'Default trial license duration (days)',        'License',  false),
('license.issuer-name',            'TranzFer MFT Platform',   'PROD', 'LICENSE', 'STRING',  'License issuer name shown in certificates',   'License',  false),
('license.validation-cache-hours', '6',                       'PROD', 'LICENSE', 'INTEGER', 'Hours to cache license validation results',   'License',  false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── Storage Manager ─────────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('storage.hot.path',                    '/data/storage/hot',    'PROD', 'STORAGE', 'STRING',  'Hot tier storage path (fast access)',           'Storage', false),
('storage.warm.path',                   '/data/storage/warm',   'PROD', 'STORAGE', 'STRING',  'Warm tier storage path',                       'Storage', false),
('storage.cold.path',                   '/data/storage/cold',   'PROD', 'STORAGE', 'STRING',  'Cold tier storage path (archival)',             'Storage', false),
('storage.backup.path',                 '/data/storage/backup', 'PROD', 'STORAGE', 'STRING',  'Backup storage path',                          'Storage', false),
('storage.lifecycle.hot-to-warm-hours', '168',                  'PROD', 'STORAGE', 'INTEGER', 'Hours before moving data from hot to warm',    'Storage', false),
('storage.lifecycle.warm-to-cold-days', '30',                   'PROD', 'STORAGE', 'INTEGER', 'Days before moving data from warm to cold',    'Storage', false),
('storage.lifecycle.cold-retention-days','365',                  'PROD', 'STORAGE', 'INTEGER', 'Days to retain data in cold tier',             'Storage', false),
('storage.stripe-size-kb',             '4096',                  'PROD', 'STORAGE', 'INTEGER', 'Stripe size for storage writes (KB)',           'Storage', false),
('storage.io-threads',                 '8',                     'PROD', 'STORAGE', 'INTEGER', 'Number of I/O threads for storage operations', 'Storage', false),
('storage.write-buffer-mb',            '64',                    'PROD', 'STORAGE', 'INTEGER', 'Write buffer size in MB',                      'Storage', false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── Encryption Service ──────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('encryption.master-key', 'CHANGE_ME_0000000000000000000000000000000000000000000000000000000', 'PROD', 'ENCRYPTION', 'STRING', '256-bit hex master encryption key — CHANGE IN PRODUCTION', 'Security', true)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── DMZ Proxy ───────────────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('dmz.mapping.sftp.listen-port',  '2222',             'PROD', 'DMZ', 'INTEGER', 'DMZ SFTP listen port (external-facing)',   'Network', false),
('dmz.mapping.sftp.target-host',  'gateway-service',  'PROD', 'DMZ', 'STRING',  'DMZ SFTP target backend host',            'Network', false),
('dmz.mapping.sftp.target-port',  '2220',             'PROD', 'DMZ', 'INTEGER', 'DMZ SFTP target backend port',            'Network', false),
('dmz.mapping.ftp.listen-port',   '21',               'PROD', 'DMZ', 'INTEGER', 'DMZ FTP listen port (external-facing)',    'Network', false),
('dmz.mapping.ftp.target-host',   'gateway-service',  'PROD', 'DMZ', 'STRING',  'DMZ FTP target backend host',             'Network', false),
('dmz.mapping.ftp.target-port',   '2121',             'PROD', 'DMZ', 'INTEGER', 'DMZ FTP target backend port',             'Network', false),
('dmz.mapping.web.listen-port',   '443',              'PROD', 'DMZ', 'INTEGER', 'DMZ HTTPS listen port (external-facing)',  'Network', false),
('dmz.mapping.web.target-host',   'ftp-web-service',  'PROD', 'DMZ', 'STRING',  'DMZ HTTPS target backend host',           'Network', false),
('dmz.mapping.web.target-port',   '8083',             'PROD', 'DMZ', 'INTEGER', 'DMZ HTTPS target backend port',           'Network', false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── AI Engine ───────────────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('ai.claude.model',                      'claude-sonnet-4-20250514', 'PROD', 'AI_ENGINE', 'STRING',  'Claude model ID for AI features',                   'AI',       false),
('ai.classification.enabled',            'true',                     'PROD', 'AI_ENGINE', 'BOOLEAN', 'Enable file classification on upload',               'AI',       false),
('ai.classification.scan-on-upload',     'true',                     'PROD', 'AI_ENGINE', 'BOOLEAN', 'Auto-scan files on upload',                          'AI',       false),
('ai.classification.max-scan-size-mb',   '100',                      'PROD', 'AI_ENGINE', 'INTEGER', 'Max file size to scan (MB)',                         'AI',       false),
('ai.classification.block-unencrypted-pci','true',                   'PROD', 'AI_ENGINE', 'BOOLEAN', 'Block unencrypted files containing PCI data',        'AI',       false),
('ai.anomaly.enabled',                   'true',                     'PROD', 'AI_ENGINE', 'BOOLEAN', 'Enable anomaly detection',                           'AI',       false),
('ai.anomaly.threshold-sigma',           '3.0',                      'PROD', 'AI_ENGINE', 'DECIMAL', 'Standard deviations for anomaly alert threshold',    'AI',       false),
('ai.anomaly.lookback-days',             '30',                       'PROD', 'AI_ENGINE', 'INTEGER', 'Days of history for anomaly baseline',               'AI',       false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── Onboarding API ──────────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('sftp-control.url', 'http://sftp-service:8081',   'PROD', 'ONBOARDING', 'STRING', 'SFTP service control API URL',  'Network', false),
('ftp-control.url',  'http://ftp-service:8082',    'PROD', 'ONBOARDING', 'STRING', 'FTP service control API URL',   'Network', false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── Keystore Manager ────────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('keystore.storage-path',    '/data/keystores',                 'PROD', 'KEYSTORE', 'STRING', 'Filesystem path for keystore files',   'Storage',  false),
('keystore.master-password', 'CHANGE_ME_keystore_master_pass',  'PROD', 'KEYSTORE', 'STRING', 'Master password for keystores',        'Security', true)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ── EDI Converter ───────────────────────────────────────────────────────────

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('edi.internal-format-version', '1.0', 'PROD', 'CONFIG', 'STRING', 'Internal EDI format version',        'EDI', false),
('edi.internal-format-name',    'TIF', 'PROD', 'CONFIG', 'STRING', 'Internal EDI format name (TranzFer)', 'EDI', false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ============================================================================
-- Seed: TEST environment (different ports, relaxed thresholds for QA)
-- ============================================================================

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('jwt.expiration',                '3600000',     'TEST', 'GLOBAL',    'INTEGER', 'JWT token expiration — longer for testing (1 hour)',        'Security',   false),
('platform.track-id.prefix',     'TST',          'TEST', 'GLOBAL',    'STRING',  'Test environment tracking prefix',                         'Platform',   false),
('platform.flow.max-concurrent', '10',           'TEST', 'GLOBAL',    'INTEGER', 'Limit concurrent flows in test',                           'Platform',   false),
('sftp.port',                    '22222',         'TEST', 'SFTP',      'INTEGER', 'SFTP port for test environment',                           'Network',    false),
('sftp.home-base',               '/tmp/sftp',     'TEST', 'SFTP',      'STRING',  'Test SFTP home directory',                                 'Storage',    false),
('ftp.port',                     '2121',          'TEST', 'FTP',       'INTEGER', 'FTP port for test environment',                            'Network',    false),
('ftp.passive-ports',            '31000-31010',   'TEST', 'FTP',       'STRING',  'FTP passive ports for test',                               'Network',    false),
('ftp.home-base',                '/tmp/ftp',      'TEST', 'FTP',       'STRING',  'Test FTP home directory',                                  'Storage',    false),
('ftpweb.home-base',             '/tmp/ftpweb',   'TEST', 'FTP_WEB',   'STRING',  'Test FTP-Web home directory',                              'Storage',    false),
('gateway.sftp.port',            '22220',         'TEST', 'GATEWAY',   'INTEGER', 'Test gateway SFTP port',                                   'Network',    false),
('gateway.ftp.port',             '21210',         'TEST', 'GATEWAY',   'INTEGER', 'Test gateway FTP port',                                    'Network',    false),
('analytics.aggregation-interval-minutes', '5',   'TEST', 'ANALYTICS', 'INTEGER', 'Faster aggregation for testing',                           'Analytics',  false),
('analytics.alert-error-rate-threshold', '0.20',  'TEST', 'ANALYTICS', 'DECIMAL', 'Higher threshold in test (less noise)',                    'Analytics',  false),
('screening.match-threshold',    '0.70',          'TEST', 'SCREENING', 'DECIMAL', 'Lower screening threshold for test (catch more matches)',  'Compliance', false),
('screening.default-action',     'FLAG',          'TEST', 'SCREENING', 'STRING',  'Flag instead of block in test',                            'Compliance', false),
('license.trial-days',           '90',            'TEST', 'LICENSE',   'INTEGER', 'Longer trial in test',                                     'License',    false),
('ai.anomaly.threshold-sigma',   '2.0',           'TEST', 'AI_ENGINE', 'DECIMAL', 'Lower anomaly threshold for test (more sensitive)',         'AI',         false),
('storage.hot.path',             '/tmp/storage/hot',  'TEST', 'STORAGE', 'STRING', 'Test hot storage path',                                   'Storage',    false),
('storage.warm.path',            '/tmp/storage/warm', 'TEST', 'STORAGE', 'STRING', 'Test warm storage path',                                  'Storage',    false),
('storage.cold.path',            '/tmp/storage/cold', 'TEST', 'STORAGE', 'STRING', 'Test cold storage path',                                  'Storage',    false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ============================================================================
-- Seed: CERT environment (mirrors PROD but with cert-specific hostnames)
-- ============================================================================

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('jwt.expiration',                '900000',       'CERT', 'GLOBAL',    'INTEGER', 'Same as PROD for cert validation',                        'Security',  false),
('platform.track-id.prefix',     'CRT',           'CERT', 'GLOBAL',    'STRING',  'Cert environment tracking prefix',                        'Platform',  false),
('platform.flow.max-concurrent', '25',            'CERT', 'GLOBAL',    'INTEGER', 'Half PROD capacity for cert env',                         'Platform',  false),
('sftp.port',                    '2222',           'CERT', 'SFTP',      'INTEGER', 'Same SFTP port as PROD in cert',                          'Network',   false),
('sftp.home-base',               '/data/sftp',     'CERT', 'SFTP',      'STRING',  'SFTP home base in cert',                                  'Storage',   false),
('ftp.port',                     '21',             'CERT', 'FTP',       'INTEGER', 'Same FTP port as PROD in cert',                           'Network',   false),
('ftp.home-base',                '/data/ftp',      'CERT', 'FTP',       'STRING',  'FTP home base in cert',                                   'Storage',   false),
('ftpweb.home-base',             '/data/ftpweb',   'CERT', 'FTP_WEB',   'STRING',  'FTP-Web home base in cert',                               'Storage',   false),
('screening.match-threshold',    '0.82',           'CERT', 'SCREENING', 'DECIMAL', 'Same as PROD for cert validation',                       'Compliance', false),
('screening.default-action',     'BLOCK',          'CERT', 'SCREENING', 'STRING',  'Block in cert like PROD',                                'Compliance', false),
('license.trial-days',           '30',             'CERT', 'LICENSE',   'INTEGER', 'Standard trial in cert',                                  'License',   false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

-- ============================================================================
-- Seed: DEV environment (developer-friendly defaults)
-- ============================================================================

INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, description, category, sensitive)
VALUES
('jwt.expiration',                '86400000',    'DEV', 'GLOBAL',    'INTEGER', 'JWT lasts 24h in dev for convenience',                     'Security',   false),
('platform.track-id.prefix',     'DEV',          'DEV', 'GLOBAL',    'STRING',  'Dev environment tracking prefix',                          'Platform',   false),
('platform.flow.max-concurrent', '5',            'DEV', 'GLOBAL',    'INTEGER', 'Low concurrency in dev',                                   'Platform',   false),
('sftp.port',                    '2222',          'DEV', 'SFTP',      'INTEGER', 'Dev SFTP port',                                            'Network',    false),
('sftp.home-base',               '/tmp/dev/sftp', 'DEV', 'SFTP',     'STRING',  'Dev SFTP home',                                            'Storage',    false),
('ftp.port',                     '2121',          'DEV', 'FTP',       'INTEGER', 'Dev FTP port',                                             'Network',    false),
('ftp.home-base',                '/tmp/dev/ftp',  'DEV', 'FTP',       'STRING',  'Dev FTP home',                                             'Storage',    false),
('ftpweb.home-base',             '/tmp/dev/ftpweb','DEV', 'FTP_WEB',  'STRING',  'Dev FTP-Web home',                                         'Storage',    false),
('analytics.aggregation-interval-minutes', '1',   'DEV', 'ANALYTICS', 'INTEGER', 'Fast aggregation for dev testing',                        'Analytics',  false),
('analytics.alert-error-rate-threshold', '0.50',  'DEV', 'ANALYTICS', 'DECIMAL', 'Very high threshold in dev (minimal alerts)',             'Analytics',  false),
('screening.match-threshold',    '0.50',          'DEV', 'SCREENING', 'DECIMAL', 'Low threshold in dev (catch everything)',                 'Compliance', false),
('screening.default-action',     'ALLOW',         'DEV', 'SCREENING', 'STRING',  'Allow in dev — no blocking',                             'Compliance', false),
('license.trial-days',           '365',           'DEV', 'LICENSE',   'INTEGER', 'Year-long trial in dev',                                   'License',    false),
('ai.anomaly.threshold-sigma',   '1.5',           'DEV', 'AI_ENGINE', 'DECIMAL', 'Very sensitive anomaly detection for dev testing',          'AI',         false),
('storage.hot.path',             '/tmp/dev/storage/hot',  'DEV', 'STORAGE', 'STRING', 'Dev hot storage',                                     'Storage',    false),
('storage.warm.path',            '/tmp/dev/storage/warm', 'DEV', 'STORAGE', 'STRING', 'Dev warm storage',                                    'Storage',    false),
('storage.cold.path',            '/tmp/dev/storage/cold', 'DEV', 'STORAGE', 'STRING', 'Dev cold storage',                                    'Storage',    false)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

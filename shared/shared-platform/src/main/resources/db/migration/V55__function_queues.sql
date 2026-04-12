-- Function queue configuration — per-step-type queue settings
-- Each step type (SCREEN, ENCRYPT_PGP, CONVERT_EDI, etc.) has its own queue
-- with independently configurable retry, timeout, concurrency, and priority.
-- Works identically on distributed (Kafka) and on-premise (SEDA fallback).

CREATE TABLE IF NOT EXISTS function_queues (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    function_type     VARCHAR(50) NOT NULL UNIQUE,
    display_name      VARCHAR(100) NOT NULL,
    description       TEXT,
    category          VARCHAR(30) DEFAULT 'CUSTOM',
    topic_name        VARCHAR(100) NOT NULL,
    retry_count       INT         DEFAULT 0,
    retry_backoff_ms  BIGINT      DEFAULT 5000,
    timeout_seconds   INT         DEFAULT 60,
    min_concurrency   INT         DEFAULT 2,
    max_concurrency   INT         DEFAULT 8,
    message_ttl_ms    BIGINT      DEFAULT 600000,
    enabled           BOOLEAN     DEFAULT true,
    built_in          BOOLEAN     DEFAULT true,
    custom_config     JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed 15 built-in function queues
INSERT INTO function_queues (function_type, display_name, description, category, topic_name, retry_count, retry_backoff_ms, timeout_seconds, min_concurrency, max_concurrency)
VALUES
  ('SCREEN',           'DLP/Sanctions Screening', 'Scan files against sanctions lists and DLP policies', 'SECURITY', 'flow.step.SCREEN', 1, 5000, 30, 2, 8),
  ('CHECKSUM_VERIFY',  'Checksum Verification',   'Compute and verify file integrity (SHA-256)',         'SECURITY', 'flow.step.CHECKSUM_VERIFY', 0, 5000, 15, 2, 4),
  ('ENCRYPT_PGP',      'PGP Encryption',          'Encrypt file with PGP public key',                   'SECURITY', 'flow.step.ENCRYPT_PGP', 2, 5000, 60, 2, 8),
  ('DECRYPT_PGP',      'PGP Decryption',          'Decrypt PGP-encrypted file with private key',        'SECURITY', 'flow.step.DECRYPT_PGP', 2, 5000, 60, 2, 8),
  ('ENCRYPT_AES',      'AES Encryption',          'Encrypt file with AES-256-GCM symmetric key',        'SECURITY', 'flow.step.ENCRYPT_AES', 2, 5000, 30, 2, 8),
  ('DECRYPT_AES',      'AES Decryption',          'Decrypt AES-256-GCM encrypted file',                 'SECURITY', 'flow.step.DECRYPT_AES', 2, 5000, 30, 2, 8),
  ('COMPRESS_GZIP',    'GZIP Compression',        'Compress file with GZIP',                            'TRANSFORM', 'flow.step.COMPRESS_GZIP', 0, 5000, 30, 2, 4),
  ('DECOMPRESS_GZIP',  'GZIP Decompression',      'Decompress GZIP file',                               'TRANSFORM', 'flow.step.DECOMPRESS_GZIP', 0, 5000, 30, 2, 4),
  ('COMPRESS_ZIP',     'ZIP Compression',          'Create ZIP archive',                                 'TRANSFORM', 'flow.step.COMPRESS_ZIP', 0, 5000, 30, 2, 4),
  ('DECOMPRESS_ZIP',   'ZIP Extraction',           'Extract files from ZIP archive',                     'TRANSFORM', 'flow.step.DECOMPRESS_ZIP', 0, 5000, 30, 2, 4),
  ('CONVERT_EDI',      'EDI Conversion',           'Convert EDI documents (X12/EDIFACT/HL7) to JSON/XML/CSV', 'TRANSFORM', 'flow.step.CONVERT_EDI', 3, 5000, 60, 2, 8),
  ('RENAME',           'File Rename',              'Rename file by pattern (${partner}_${date}_${filename})', 'TRANSFORM', 'flow.step.RENAME', 0, 5000, 5, 2, 4),
  ('MAILBOX',          'Internal Delivery',        'Deliver file to another accounts outbox within platform', 'DELIVERY', 'flow.step.MAILBOX', 2, 5000, 30, 2, 8),
  ('FILE_DELIVERY',    'External Delivery (Generic)', 'Deliver file to external destination — routes to protocol-specific queue', 'DELIVERY', 'flow.step.FILE_DELIVERY', 3, 10000, 120, 2, 8),
  ('DELIVER_SFTP',     'SFTP Delivery',            'Deliver file to external SFTP server',               'DELIVERY', 'flow.step.DELIVER_SFTP', 3, 10000, 120, 2, 8),
  ('DELIVER_FTP',      'FTP/FTPS Delivery',        'Deliver file to external FTP/FTPS server',           'DELIVERY', 'flow.step.DELIVER_FTP', 3, 10000, 120, 2, 8),
  ('DELIVER_HTTP',     'HTTP/API Delivery',         'Deliver file to HTTP/REST endpoint or webhook',      'DELIVERY', 'flow.step.DELIVER_HTTP', 2, 3000, 30, 2, 16),
  ('DELIVER_AS2',      'AS2 Delivery',              'Deliver file via AS2 protocol with MDN receipt',     'DELIVERY', 'flow.step.DELIVER_AS2', 3, 10000, 180, 1, 4),
  ('DELIVER_KAFKA',    'Kafka/Event Delivery',      'Publish file content or reference to Kafka topic',   'DELIVERY', 'flow.step.DELIVER_KAFKA', 1, 2000, 15, 2, 16),
  ('EXECUTE_SCRIPT',   'Script Execution',         'Run custom processing script',                       'CUSTOM', 'flow.step.EXECUTE_SCRIPT', 1, 5000, 300, 1, 4)
ON CONFLICT (function_type) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_fq_category ON function_queues(category);
CREATE INDEX IF NOT EXISTS idx_fq_enabled ON function_queues(enabled);

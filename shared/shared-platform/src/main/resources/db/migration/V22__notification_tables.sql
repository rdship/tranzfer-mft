-- =============================================================================
-- V20: Notification service tables — templates, rules, and delivery logs
-- =============================================================================

CREATE TABLE IF NOT EXISTS notification_templates (
    id                UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name              VARCHAR(255) NOT NULL UNIQUE,
    channel           VARCHAR(20) NOT NULL,
    subject_template  VARCHAR(500),
    body_template     TEXT NOT NULL,
    event_type        VARCHAR(255) NOT NULL,
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(100),
    updated_by        VARCHAR(100)
);

CREATE INDEX idx_notif_template_event_type ON notification_templates(event_type);
CREATE INDEX idx_notif_template_channel ON notification_templates(channel);

CREATE TABLE IF NOT EXISTS notification_rules (
    id                  UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name                VARCHAR(255) NOT NULL UNIQUE,
    event_type_pattern  VARCHAR(255) NOT NULL,
    channel             VARCHAR(20) NOT NULL,
    recipients          JSONB NOT NULL,
    template_id         UUID REFERENCES notification_templates(id),
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    conditions          JSONB,
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100)
);

CREATE INDEX idx_notif_rule_event_pattern ON notification_rules(event_type_pattern);
CREATE INDEX idx_notif_rule_channel ON notification_rules(channel);

CREATE TABLE IF NOT EXISTS notification_logs (
    id            UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    event_type    VARCHAR(255) NOT NULL,
    channel       VARCHAR(20) NOT NULL,
    recipient     VARCHAR(500) NOT NULL,
    subject       VARCHAR(500),
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sent_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    error_message TEXT,
    retry_count   INT NOT NULL DEFAULT 0,
    rule_id       UUID,
    track_id      VARCHAR(50),
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notif_log_event_type ON notification_logs(event_type);
CREATE INDEX idx_notif_log_status ON notification_logs(status);
CREATE INDEX idx_notif_log_sent_at ON notification_logs(sent_at);
CREATE INDEX idx_notif_log_channel ON notification_logs(channel);

-- =============================================================================
-- Default notification templates
-- =============================================================================

INSERT INTO notification_templates (name, channel, subject_template, body_template, event_type, active) VALUES
('transfer-completed-email', 'EMAIL',
 'Transfer Completed: ${filename}',
 'File transfer completed successfully.\n\nTrack ID: ${trackId}\nFilename: ${filename}\nAccount: ${account}\nProtocol: ${protocol}\nSize: ${fileSize}\nTimestamp: ${timestamp}\n\nThis is an automated notification from TranzFer MFT.',
 'transfer.completed', true),

('transfer-failed-email', 'EMAIL',
 'ALERT: Transfer Failed - ${filename}',
 'A file transfer has failed.\n\nTrack ID: ${trackId}\nFilename: ${filename}\nAccount: ${account}\nProtocol: ${protocol}\nError: ${errorMessage}\nTimestamp: ${timestamp}\n\nPlease investigate immediately.\n\nThis is an automated notification from TranzFer MFT.',
 'transfer.failed', true),

('security-threat-email', 'EMAIL',
 'CRITICAL: Security Threat Detected',
 'A security threat has been detected by the AI engine.\n\nTrack ID: ${trackId}\nThreat Type: ${threatType}\nSeverity: ${severity}\nFilename: ${filename}\nAccount: ${account}\nAction Taken: ${action}\nDetails: ${details}\nTimestamp: ${timestamp}\n\nImmediate attention required.\n\nThis is an automated notification from TranzFer MFT.',
 'security.threat.detected', true),

('screening-hit-email', 'EMAIL',
 'ALERT: Sanctions Screening Match - ${filename}',
 'A sanctions screening match has been found.\n\nTrack ID: ${trackId}\nFilename: ${filename}\nAccount: ${account}\nMatch Type: ${matchType}\nMatched Entity: ${matchedEntity}\nConfidence: ${confidence}\nAction: ${action}\nTimestamp: ${timestamp}\n\nPlease review and take appropriate action.\n\nThis is an automated notification from TranzFer MFT.',
 'screening.hit', true),

('system-error-email', 'EMAIL',
 'CRITICAL: System Error - ${service}',
 'A system error has occurred.\n\nService: ${service}\nError: ${errorMessage}\nSeverity: ${severity}\nTimestamp: ${timestamp}\nDetails: ${details}\n\nPlease investigate.\n\nThis is an automated notification from TranzFer MFT.',
 'system.error', true),

('account-created-email', 'EMAIL',
 'New Account Created: ${username}',
 'A new transfer account has been created.\n\nUsername: ${username}\nProtocol: ${protocol}\nHome Directory: ${homeDir}\nCreated By: ${createdBy}\nTimestamp: ${timestamp}\n\nThis is an automated notification from TranzFer MFT.',
 'account.created', true),

('sla-breach-email', 'EMAIL',
 'WARNING: SLA Breach - ${trackId}',
 'An SLA breach warning has been triggered.\n\nTrack ID: ${trackId}\nFilename: ${filename}\nAccount: ${account}\nSLA Threshold: ${slaThreshold}\nActual Duration: ${actualDuration}\nTimestamp: ${timestamp}\n\nPlease investigate the delay.\n\nThis is an automated notification from TranzFer MFT.',
 'sla.breach', true),

('transfer-completed-webhook', 'WEBHOOK',
 NULL,
 '{"event":"transfer.completed","trackId":"${trackId}","filename":"${filename}","account":"${account}","protocol":"${protocol}","fileSize":"${fileSize}","timestamp":"${timestamp}"}',
 'transfer.completed', true),

('transfer-failed-webhook', 'WEBHOOK',
 NULL,
 '{"event":"transfer.failed","trackId":"${trackId}","filename":"${filename}","account":"${account}","protocol":"${protocol}","error":"${errorMessage}","timestamp":"${timestamp}"}',
 'transfer.failed', true),

('security-threat-webhook', 'WEBHOOK',
 NULL,
 '{"event":"security.threat.detected","trackId":"${trackId}","threatType":"${threatType}","severity":"${severity}","filename":"${filename}","account":"${account}","action":"${action}","timestamp":"${timestamp}"}',
 'security.threat.detected', true);

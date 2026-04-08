-- =============================================================================
-- TranzFer MFT — V1 Baseline Migration
-- Creates the complete database schema for all microservices.
-- =============================================================================

-- ===== Core: Users & Accounts =====

CREATE TABLE IF NOT EXISTS users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    role        VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE IF NOT EXISTS transfer_accounts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID REFERENCES users(id),
    protocol      VARCHAR(20) NOT NULL,
    username      VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    public_key    TEXT,
    home_dir      VARCHAR(512),
    active        BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE IF NOT EXISTS external_destinations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR(255),
    type                    VARCHAR(20),
    host                    VARCHAR(255),
    port                    INTEGER,
    username                VARCHAR(255),
    encrypted_password      VARCHAR(512),
    remote_path             VARCHAR(512),
    kafka_topic             VARCHAR(255),
    kafka_bootstrap_servers VARCHAR(512),
    kafka_producer_config   TEXT,
    active                  BOOLEAN NOT NULL DEFAULT true,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE IF NOT EXISTS encryption_keys (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id             UUID REFERENCES transfer_accounts(id),
    key_name               VARCHAR(255),
    algorithm              VARCHAR(20),
    public_key             TEXT,
    encrypted_private_key  TEXT,
    encrypted_symmetric_key TEXT,
    active                 BOOLEAN NOT NULL DEFAULT true,
    created_at             TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- ===== Core: Folder Mappings & Transfers =====

CREATE TABLE IF NOT EXISTS folder_mappings (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id       UUID REFERENCES transfer_accounts(id),
    destination_account_id  UUID REFERENCES transfer_accounts(id),
    external_destination_id UUID REFERENCES external_destinations(id),
    encryption_key_id       UUID REFERENCES encryption_keys(id),
    source_path             VARCHAR(512),
    destination_path        VARCHAR(512),
    filename_pattern        VARCHAR(255),
    encryption_option       VARCHAR(30) DEFAULT 'NONE',
    active                  BOOLEAN NOT NULL DEFAULT true,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE IF NOT EXISTS file_transfer_records (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    folder_mapping_id     UUID REFERENCES folder_mappings(id),
    track_id              VARCHAR(64) NOT NULL UNIQUE,
    original_filename     VARCHAR(512),
    source_file_path      VARCHAR(1024),
    destination_file_path VARCHAR(1024),
    archive_file_path     VARCHAR(1024),
    status                VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    error_message         TEXT,
    file_size_bytes       BIGINT,
    source_checksum       VARCHAR(128),
    destination_checksum  VARCHAR(128),
    retry_count           INTEGER NOT NULL DEFAULT 0,
    uploaded_at           TIMESTAMP WITH TIME ZONE,
    routed_at             TIMESTAMP WITH TIME ZONE,
    downloaded_at         TIMESTAMP WITH TIME ZONE,
    completed_at          TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_ftr_track_id ON file_transfer_records(track_id);

-- ===== Core: Audit =====

CREATE TABLE IF NOT EXISTS audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID REFERENCES transfer_accounts(id),
    track_id        VARCHAR(64),
    action          VARCHAR(100),
    success         BOOLEAN,
    path            VARCHAR(1024),
    filename        VARCHAR(512),
    file_size_bytes BIGINT,
    sha256_checksum VARCHAR(128),
    ip_address      VARCHAR(45),
    session_id      VARCHAR(128),
    principal       VARCHAR(255),
    error_message   TEXT,
    timestamp       TIMESTAMP WITH TIME ZONE DEFAULT now(),
    integrity_hash  VARCHAR(128)
);

CREATE INDEX IF NOT EXISTS idx_audit_track_id ON audit_logs(track_id);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_logs(timestamp);

-- ===== Flow Engine =====

CREATE TABLE IF NOT EXISTS file_flows (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id       UUID REFERENCES transfer_accounts(id),
    destination_account_id  UUID REFERENCES transfer_accounts(id),
    external_destination_id UUID REFERENCES external_destinations(id),
    name                    VARCHAR(255) NOT NULL UNIQUE,
    description             TEXT,
    filename_pattern        VARCHAR(255),
    source_path             VARCHAR(512),
    destination_path        VARCHAR(512),
    priority                INTEGER NOT NULL DEFAULT 0,
    active                  BOOLEAN NOT NULL DEFAULT true,
    steps                   JSONB,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE IF NOT EXISTS flow_executions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    flow_id           UUID REFERENCES file_flows(id),
    transfer_record_id UUID REFERENCES file_transfer_records(id),
    track_id          VARCHAR(64) NOT NULL UNIQUE,
    original_filename VARCHAR(512),
    current_file_path VARCHAR(1024),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    current_step      INTEGER NOT NULL DEFAULT 0,
    error_message     TEXT,
    step_results      JSONB,
    started_at        TIMESTAMP WITH TIME ZONE DEFAULT now(),
    completed_at      TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_fe_track_id ON flow_executions(track_id);

-- ===== Config: Servers, Profiles, Scheduler =====

CREATE TABLE IF NOT EXISTS server_configs (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(255),
    service_type VARCHAR(30),
    host         VARCHAR(255),
    port         INTEGER,
    proxy_type   VARCHAR(20),
    proxy_host   VARCHAR(255),
    proxy_port   INTEGER,
    active       BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE IF NOT EXISTS legacy_server_configs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(255),
    protocol         VARCHAR(20),
    host             VARCHAR(255),
    port             INTEGER,
    health_check_user VARCHAR(255),
    active           BOOLEAN NOT NULL DEFAULT true,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE IF NOT EXISTS security_profiles (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(255) NOT NULL UNIQUE,
    description          TEXT,
    type                 VARCHAR(30),
    tls_min_version      VARCHAR(10),
    client_auth_required BOOLEAN NOT NULL DEFAULT false,
    active               BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at           TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE IF NOT EXISTS scheduled_tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL UNIQUE,
    description     TEXT,
    cron_expression VARCHAR(50),
    timezone        VARCHAR(50) DEFAULT 'UTC',
    task_type       VARCHAR(30),
    reference_id    VARCHAR(255),
    config          JSONB,
    enabled         BOOLEAN NOT NULL DEFAULT true,
    last_run        TIMESTAMP WITH TIME ZONE,
    next_run        TIMESTAMP WITH TIME ZONE,
    last_status     VARCHAR(20),
    last_error      TEXT,
    total_runs      INTEGER NOT NULL DEFAULT 0,
    failed_runs     INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- ===== Service Registry =====

CREATE TABLE IF NOT EXISTS service_registrations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_instance_id VARCHAR(255) NOT NULL UNIQUE,
    cluster_id          VARCHAR(100),
    service_type        VARCHAR(30),
    host                VARCHAR(255),
    control_port        INTEGER,
    active              BOOLEAN NOT NULL DEFAULT true,
    last_heartbeat      TIMESTAMP WITH TIME ZONE,
    registered_at       TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- ===== Integrations: Webhooks, Connectors =====

CREATE TABLE IF NOT EXISTS webhook_connectors (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  VARCHAR(255) NOT NULL UNIQUE,
    type                  VARCHAR(30),
    url                   VARCHAR(1024),
    auth_token            VARCHAR(512),
    username              VARCHAR(255),
    password              VARCHAR(255),
    min_severity          VARCHAR(20),
    snow_instance_id      VARCHAR(255),
    snow_assignment_group VARCHAR(255),
    snow_category         VARCHAR(100),
    active                BOOLEAN NOT NULL DEFAULT true,
    last_triggered        TIMESTAMP WITH TIME ZONE,
    total_notifications   INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- ===== Multi-tenancy =====

CREATE TABLE IF NOT EXISTS tenants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            VARCHAR(100) NOT NULL UNIQUE,
    company_name    VARCHAR(255),
    contact_email   VARCHAR(255),
    plan            VARCHAR(30),
    trial_ends_at   TIMESTAMP WITH TIME ZONE,
    transfers_used  BIGINT NOT NULL DEFAULT 0,
    transfer_limit  BIGINT,
    custom_domain   VARCHAR(255),
    active          BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- ===== Security: TOTP, Auto-Onboard =====

CREATE TABLE IF NOT EXISTS totp_configs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username    VARCHAR(255) NOT NULL UNIQUE,
    secret      VARCHAR(255),
    enabled     BOOLEAN NOT NULL DEFAULT false,
    enrolled    BOOLEAN NOT NULL DEFAULT false,
    backup_codes VARCHAR(1024),
    method      VARCHAR(20),
    otp_email   VARCHAR(255),
    enrolled_at TIMESTAMP WITH TIME ZONE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE IF NOT EXISTS auto_onboard_sessions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_ip          VARCHAR(45),
    client_version     VARCHAR(50),
    generated_username VARCHAR(255),
    temp_password      VARCHAR(255),
    phase              VARCHAR(30),
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- ===== P2P Transfers =====

CREATE TABLE IF NOT EXISTS transfer_tickets (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id         VARCHAR(64) NOT NULL UNIQUE,
    sender_account_id UUID REFERENCES transfer_accounts(id),
    receiver_account_id UUID REFERENCES transfer_accounts(id),
    track_id          VARCHAR(64),
    filename          VARCHAR(512),
    file_size_bytes   BIGINT,
    sha256_checksum   VARCHAR(128),
    receiver_host     VARCHAR(255),
    receiver_port     INTEGER,
    sender_host       VARCHAR(255),
    sender_port       INTEGER,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sender_token      VARCHAR(512),
    expires_at        TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT now(),
    completed_at      TIMESTAMP WITH TIME ZONE,
    error_message     TEXT
);

CREATE INDEX IF NOT EXISTS idx_ticket_track_id ON transfer_tickets(track_id);

-- ===== Blockchain Anchoring =====

CREATE TABLE IF NOT EXISTS blockchain_anchors (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id     VARCHAR(64),
    filename     VARCHAR(512),
    sha256       VARCHAR(128),
    merkle_root  VARCHAR(128),
    chain        VARCHAR(30),
    tx_hash      VARCHAR(128),
    block_number BIGINT,
    anchored_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_anchor_track_id ON blockchain_anchors(track_id);

-- ===== Client Presence =====

CREATE TABLE IF NOT EXISTS client_presence (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username       VARCHAR(255) NOT NULL UNIQUE,
    host           VARCHAR(255),
    port           INTEGER,
    protocol       VARCHAR(20),
    client_version VARCHAR(50),
    last_seen      TIMESTAMP WITH TIME ZONE,
    online         BOOLEAN NOT NULL DEFAULT false
);

-- ===== Partner SLA Agreements =====

CREATE TABLE IF NOT EXISTS partner_agreements (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id                 UUID REFERENCES transfer_accounts(id),
    name                       VARCHAR(255) NOT NULL UNIQUE,
    description                TEXT,
    expected_delivery_start_hour INTEGER DEFAULT 0,
    expected_delivery_end_hour   INTEGER DEFAULT 23,
    min_files_per_window       INTEGER DEFAULT 0,
    max_error_rate             DOUBLE PRECISION DEFAULT 5.0,
    grace_period_minutes       INTEGER DEFAULT 30,
    breach_action              VARCHAR(30) DEFAULT 'NOTIFY',
    active                     BOOLEAN NOT NULL DEFAULT true,
    total_breaches             INTEGER NOT NULL DEFAULT 0,
    last_breach_at             TIMESTAMP WITH TIME ZONE,
    created_at                 TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- ===== Analytics Service =====

CREATE TABLE IF NOT EXISTS metric_snapshots (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_time            TIMESTAMP WITH TIME ZONE NOT NULL,
    service_type             VARCHAR(30),
    protocol                 VARCHAR(20),
    total_transfers          BIGINT DEFAULT 0,
    successful_transfers     BIGINT DEFAULT 0,
    failed_transfers         BIGINT DEFAULT 0,
    total_bytes_transferred  BIGINT DEFAULT 0,
    avg_latency_ms           DOUBLE PRECISION,
    p95_latency_ms           DOUBLE PRECISION,
    p99_latency_ms           DOUBLE PRECISION,
    active_sessions          INTEGER DEFAULT 0,
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT now(),
    UNIQUE(snapshot_time, service_type)
);

CREATE TABLE IF NOT EXISTS alert_rules (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(255),
    service_type   VARCHAR(30),
    metric         VARCHAR(100),
    operator       VARCHAR(10),
    threshold      DOUBLE PRECISION,
    window_minutes INTEGER DEFAULT 15,
    enabled        BOOLEAN NOT NULL DEFAULT true,
    last_triggered TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- ===== License Service =====

CREATE TABLE IF NOT EXISTS license_records (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    license_id               VARCHAR(128) NOT NULL UNIQUE,
    customer_id              VARCHAR(128),
    customer_name            VARCHAR(255),
    edition                  VARCHAR(30),
    issued_at                TIMESTAMP WITH TIME ZONE,
    expires_at               TIMESTAMP WITH TIME ZONE,
    installation_fingerprint VARCHAR(512),
    active                   BOOLEAN NOT NULL DEFAULT true,
    notes                    TEXT,
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at               TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE IF NOT EXISTS license_activations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    license_record_id UUID REFERENCES license_records(id),
    service_type      VARCHAR(30),
    host_id           VARCHAR(255),
    activated_at      TIMESTAMP WITH TIME ZONE DEFAULT now(),
    last_check_in     TIMESTAMP WITH TIME ZONE,
    active            BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS installation_fingerprints (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fingerprint   VARCHAR(512) NOT NULL UNIQUE,
    trial_started TIMESTAMP WITH TIME ZONE,
    trial_expires TIMESTAMP WITH TIME ZONE,
    customer_id   VARCHAR(128),
    customer_name VARCHAR(255),
    active        BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- ===== Screening Service =====

CREATE TABLE IF NOT EXISTS screening_results (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id         VARCHAR(64),
    filename         VARCHAR(512),
    account_username VARCHAR(255),
    outcome          VARCHAR(20),
    records_scanned  INTEGER DEFAULT 0,
    hits_found       INTEGER DEFAULT 0,
    duration_ms      BIGINT DEFAULT 0,
    action_taken     VARCHAR(20),
    screened_at      TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_screening_track_id ON screening_results(track_id);

CREATE TABLE IF NOT EXISTS sanctions_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(512),
    name_lower  VARCHAR(512),
    source      VARCHAR(50),
    entity_type VARCHAR(30),
    program     VARCHAR(255),
    aliases     TEXT,
    identifiers TEXT,
    loaded_at   TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sanctions_name ON sanctions_entries(name_lower);
CREATE INDEX IF NOT EXISTS idx_sanctions_source ON sanctions_entries(source);

-- ===== Keystore Manager =====

CREATE TABLE IF NOT EXISTS managed_keys (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alias              VARCHAR(255) NOT NULL UNIQUE,
    key_type           VARCHAR(30),
    algorithm          VARCHAR(30),
    key_material       TEXT,
    public_key_material TEXT,
    fingerprint        VARCHAR(128),
    owner_service      VARCHAR(30),
    partner_account    VARCHAR(255),
    description        TEXT,
    key_size_bits      INTEGER,
    subject_dn         VARCHAR(512),
    issuer_dn          VARCHAR(512),
    valid_from         TIMESTAMP WITH TIME ZONE,
    expires_at         TIMESTAMP WITH TIME ZONE,
    active             BOOLEAN NOT NULL DEFAULT true,
    rotated_to_alias   VARCHAR(255),
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mk_owner ON managed_keys(owner_service);

-- ===== Storage Manager =====

CREATE TABLE IF NOT EXISTS storage_objects (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id          VARCHAR(64),
    filename          VARCHAR(512),
    physical_path     VARCHAR(1024),
    logical_path      VARCHAR(1024),
    tier              VARCHAR(20) DEFAULT 'HOT',
    size_bytes        BIGINT DEFAULT 0,
    sha256            VARCHAR(128),
    content_type      VARCHAR(100),
    account_username  VARCHAR(255),
    access_count      INTEGER NOT NULL DEFAULT 0,
    last_accessed_at  TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT now(),
    tier_changed_at   TIMESTAMP WITH TIME ZONE,
    backup_status     VARCHAR(20),
    last_backup_at    TIMESTAMP WITH TIME ZONE,
    striped           BOOLEAN NOT NULL DEFAULT false,
    stripe_count      INTEGER DEFAULT 1,
    compression_ratio DOUBLE PRECISION,
    deleted           BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS idx_so_track_id ON storage_objects(track_id);
CREATE INDEX IF NOT EXISTS idx_so_sha256 ON storage_objects(sha256);
CREATE INDEX IF NOT EXISTS idx_so_account ON storage_objects(account_username);

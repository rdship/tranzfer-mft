-- V15: AI Engine threat intelligence tables
-- Adds persistent storage for threat indicators, security alerts, events,
-- attack campaigns, threat actors, and verdict audit trail.

-- ============================================================
-- Threat Indicators (IOCs from OSINT feeds, manual entry, etc.)
-- ============================================================
CREATE TABLE IF NOT EXISTS threat_indicators (
    ioc_id              UUID PRIMARY KEY,
    type                VARCHAR(30)   NOT NULL,  -- IP, DOMAIN, URL, HASH_MD5, HASH_SHA256, EMAIL, CVE, JA3, etc.
    value               VARCHAR(2048) NOT NULL,
    threat_level        VARCHAR(20)   NOT NULL DEFAULT 'UNKNOWN',  -- UNKNOWN, LOW, MEDIUM, HIGH, CRITICAL
    confidence          DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    sources             TEXT,                    -- comma-separated source names
    first_seen          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_seen           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    sightings           INTEGER       NOT NULL DEFAULT 1,
    false_positive_count INTEGER      NOT NULL DEFAULT 0,
    tags                TEXT,                    -- comma-separated
    mitre_techniques    TEXT,                    -- comma-separated technique IDs
    context_json        TEXT,                    -- JSON blob for extra context
    CONSTRAINT uq_threat_indicator_type_value UNIQUE (type, value)
);

CREATE INDEX idx_threat_indicators_type ON threat_indicators (type);
CREATE INDEX idx_threat_indicators_threat_level ON threat_indicators (threat_level);
CREATE INDEX idx_threat_indicators_last_seen ON threat_indicators (last_seen);
CREATE INDEX idx_threat_indicators_value ON threat_indicators (value);

-- ============================================================
-- Security Alerts
-- ============================================================
CREATE TABLE IF NOT EXISTS security_alerts (
    alert_id            UUID PRIMARY KEY,
    timestamp           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    title               VARCHAR(500)  NOT NULL,
    description         TEXT,
    severity            VARCHAR(20)   NOT NULL DEFAULT 'MEDIUM',  -- LOW, MEDIUM, HIGH, CRITICAL
    status              VARCHAR(30)   NOT NULL DEFAULT 'NEW',     -- NEW, INVESTIGATING, RESOLVED, FALSE_POSITIVE, ESCALATED
    confidence          DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    risk_score          DOUBLE PRECISION NOT NULL DEFAULT 0,
    source_event_ids    TEXT,                    -- comma-separated UUIDs
    mitre_tactics       TEXT,
    mitre_techniques    TEXT,
    explanation         TEXT,                    -- AI-generated
    recommended_actions TEXT,                    -- JSON array
    assigned_to         VARCHAR(255),
    resolved_at         TIMESTAMP WITH TIME ZONE,
    verdict             VARCHAR(30),             -- true_positive, false_positive, benign
    playbook_id         VARCHAR(100),
    related_alert_ids   TEXT,
    enrichments_json    TEXT
);

CREATE INDEX idx_security_alerts_timestamp ON security_alerts (timestamp);
CREATE INDEX idx_security_alerts_severity ON security_alerts (severity);
CREATE INDEX idx_security_alerts_status ON security_alerts (status);

-- ============================================================
-- Security Events (unified event schema)
-- ============================================================
CREATE TABLE IF NOT EXISTS security_events (
    event_id            UUID PRIMARY KEY,
    timestamp           TIMESTAMP WITH TIME ZONE NOT NULL,
    ingestion_time      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    source_type         VARCHAR(30)   NOT NULL,  -- NETWORK, ENDPOINT, AUTH, DNS, HTTP, CLOUD, FILE, THREAT_INTEL, CUSTOM
    source              VARCHAR(255),
    severity            DOUBLE PRECISION NOT NULL DEFAULT 0,
    confidence          DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    raw_log             TEXT,

    -- Network fields
    src_ip              VARCHAR(45),
    dst_ip              VARCHAR(45),
    src_port            INTEGER,
    dst_port            INTEGER,
    protocol            VARCHAR(20),
    bytes_in            BIGINT,
    bytes_out           BIGINT,
    packets_in          BIGINT,
    packets_out         BIGINT,
    flow_id             VARCHAR(100),
    flow_duration_ms    BIGINT,
    ja3_hash            VARCHAR(64),
    tls_version         VARCHAR(20),
    tls_sni             VARCHAR(255),

    -- DNS fields
    dns_query           VARCHAR(512),
    dns_record_type     VARCHAR(20),
    dns_response_code   VARCHAR(20),

    -- HTTP fields
    http_method         VARCHAR(10),
    http_url            TEXT,
    http_status         INTEGER,
    http_user_agent     VARCHAR(1024),

    -- Auth fields
    auth_user           VARCHAR(255),
    auth_domain         VARCHAR(255),
    auth_type           VARCHAR(30),
    auth_result         VARCHAR(30),
    auth_mfa_used       BOOLEAN,

    -- Endpoint fields
    host_name           VARCHAR(255),
    host_ip             VARCHAR(45),
    process_name        VARCHAR(255),
    process_id          INTEGER,
    parent_process_name VARCHAR(255),
    command_line        TEXT,
    file_path           TEXT,
    file_hash_sha256    VARCHAR(64),

    -- Cloud fields
    cloud_provider      VARCHAR(20),
    cloud_service       VARCHAR(100),
    cloud_action        VARCHAR(255),
    cloud_resource_id   VARCHAR(512),
    cloud_region        VARCHAR(50),

    -- MITRE
    mitre_tactics       TEXT,
    mitre_techniques    TEXT,

    -- Tags and metadata
    tags                TEXT,
    enrichments_json    TEXT,

    -- Geo
    geo_country_code    VARCHAR(2),
    geo_city            VARCHAR(255),
    geo_latitude        DOUBLE PRECISION,
    geo_longitude       DOUBLE PRECISION,
    geo_asn             INTEGER,
    geo_as_org          VARCHAR(255),
    geo_is_tor          BOOLEAN DEFAULT FALSE,
    geo_is_vpn          BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_security_events_timestamp ON security_events (timestamp);
CREATE INDEX idx_security_events_src_ip ON security_events (src_ip);
CREATE INDEX idx_security_events_dst_ip ON security_events (dst_ip);
CREATE INDEX idx_security_events_source_type ON security_events (source_type);
CREATE INDEX idx_security_events_severity ON security_events (severity);

-- ============================================================
-- Threat Actors
-- ============================================================
CREATE TABLE IF NOT EXISTS threat_actors (
    actor_id            UUID PRIMARY KEY,
    name                VARCHAR(255)  NOT NULL,
    aliases             TEXT,
    description         TEXT,
    country             VARCHAR(100),
    motivation          VARCHAR(50),     -- financial, espionage, hacktivism, destruction
    sophistication      VARCHAR(20),     -- low, medium, high, apt
    active_since        TIMESTAMP WITH TIME ZONE,
    last_activity       TIMESTAMP WITH TIME ZONE,
    ttps                TEXT,            -- MITRE technique IDs, comma-separated
    known_tools         TEXT,
    target_sectors      TEXT,
    target_countries    TEXT,
    confidence          DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    sources             TEXT
);

CREATE INDEX idx_threat_actors_name ON threat_actors (name);

-- ============================================================
-- Attack Campaigns
-- ============================================================
CREATE TABLE IF NOT EXISTS attack_campaigns (
    campaign_id         UUID PRIMARY KEY,
    name                VARCHAR(255)  NOT NULL,
    description         TEXT,
    first_seen          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_seen           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    status              VARCHAR(20)   NOT NULL DEFAULT 'active',  -- active, dormant, concluded
    actor_id            UUID,
    ttps                TEXT,
    target_sectors      TEXT,
    ioc_ids             TEXT,
    alert_ids           TEXT,
    event_count         INTEGER       NOT NULL DEFAULT 0,
    confidence          DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    CONSTRAINT fk_campaign_actor FOREIGN KEY (actor_id) REFERENCES threat_actors(actor_id) ON DELETE SET NULL
);

CREATE INDEX idx_attack_campaigns_status ON attack_campaigns (status);
CREATE INDEX idx_attack_campaigns_last_seen ON attack_campaigns (last_seen);

-- ============================================================
-- Verdict Records (audit trail for proxy verdicts)
-- ============================================================
CREATE TABLE IF NOT EXISTS verdict_records (
    verdict_id          UUID PRIMARY KEY,
    timestamp           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    source_ip           VARCHAR(45)   NOT NULL,
    target_port         INTEGER       NOT NULL,
    protocol            VARCHAR(20),
    action              VARCHAR(20)   NOT NULL,  -- ALLOW, THROTTLE, CHALLENGE, BLOCK, BLACKHOLE
    risk_score          INTEGER       NOT NULL,
    reason              TEXT,
    signals_json        TEXT,
    ttl_seconds         INTEGER,
    cached              BOOLEAN       DEFAULT FALSE
);

CREATE INDEX idx_verdict_records_timestamp ON verdict_records (timestamp);
CREATE INDEX idx_verdict_records_source_ip ON verdict_records (source_ip);
CREATE INDEX idx_verdict_records_action ON verdict_records (action);

-- ============================================================
-- Security Incidents
-- ============================================================
CREATE TABLE IF NOT EXISTS security_incidents (
    incident_id         UUID PRIMARY KEY,
    title               VARCHAR(500)  NOT NULL,
    description         TEXT,
    severity            VARCHAR(20)   NOT NULL DEFAULT 'MEDIUM',
    status              VARCHAR(30)   NOT NULL DEFAULT 'OPEN',  -- OPEN, INVESTIGATING, CONTAINED, RESOLVED, CLOSED
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    resolved_at         TIMESTAMP WITH TIME ZONE,
    assigned_to         VARCHAR(255),
    alert_ids           TEXT,
    ioc_values          TEXT,
    affected_ips        TEXT,
    mitre_techniques    TEXT,
    playbook_execution_ids TEXT,
    timeline_json       TEXT,            -- JSON array of timeline entries
    root_cause          TEXT,
    resolution          TEXT,
    metadata_json       TEXT
);

CREATE INDEX idx_security_incidents_status ON security_incidents (status);
CREATE INDEX idx_security_incidents_severity ON security_incidents (severity);
CREATE INDEX idx_security_incidents_created_at ON security_incidents (created_at);

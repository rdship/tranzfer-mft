-- ============================================================================
-- V5: Cluster Awareness
--
-- Adds cluster topology tracking and cross-cluster communication settings.
-- Allows administrators to configure whether services communicate within
-- their own cluster only, or across all clusters (federated mode).
-- ============================================================================

-- Index on service_registrations for cluster-scoped queries
CREATE INDEX IF NOT EXISTS idx_service_reg_cluster_type_active
    ON service_registrations (cluster_id, service_type, active);

CREATE INDEX IF NOT EXISTS idx_service_reg_cluster_active
    ON service_registrations (cluster_id, active);

-- Cluster nodes table: tracks known clusters and their communication policies
CREATE TABLE IF NOT EXISTS cluster_nodes (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cluster_id              VARCHAR(100) NOT NULL UNIQUE,
    display_name            VARCHAR(255),
    description             TEXT,
    communication_mode      VARCHAR(30) NOT NULL DEFAULT 'WITHIN_CLUSTER',
    region                  VARCHAR(100),
    environment             VARCHAR(30),
    api_endpoint            VARCHAR(500),
    active                  BOOLEAN NOT NULL DEFAULT true,
    registered_at           TIMESTAMP WITH TIME ZONE DEFAULT now(),
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT now()
);

-- Seed the default cluster
INSERT INTO cluster_nodes (cluster_id, display_name, description, communication_mode, environment)
VALUES ('default-cluster', 'Default Cluster', 'Primary cluster — auto-created on first startup', 'WITHIN_CLUSTER', 'PROD')
ON CONFLICT (cluster_id) DO NOTHING;

-- Add cluster communication mode to platform_settings
INSERT INTO platform_settings (setting_key, setting_value, environment, service_name, data_type, category, description, sensitive, active)
VALUES
    ('cluster.communication-mode', 'WITHIN_CLUSTER', 'PROD', 'GLOBAL', 'STRING', 'Cluster',
     'WITHIN_CLUSTER = services only communicate within their own cluster. CROSS_CLUSTER = services can discover and route to services in any cluster.',
     false, true),
    ('cluster.communication-mode', 'WITHIN_CLUSTER', 'DEV', 'GLOBAL', 'STRING', 'Cluster',
     'WITHIN_CLUSTER = services only communicate within their own cluster. CROSS_CLUSTER = services can discover and route to services in any cluster.',
     false, true),
    ('cluster.communication-mode', 'WITHIN_CLUSTER', 'TEST', 'GLOBAL', 'STRING', 'Cluster',
     'WITHIN_CLUSTER = services only communicate within their own cluster. CROSS_CLUSTER = services can discover and route to services in any cluster.',
     false, true),
    ('cluster.communication-mode', 'WITHIN_CLUSTER', 'CERT', 'GLOBAL', 'STRING', 'Cluster',
     'WITHIN_CLUSTER = services only communicate within their own cluster. CROSS_CLUSTER = services can discover and route to services in any cluster.',
     false, true)
ON CONFLICT (setting_key, environment, service_name) DO NOTHING;

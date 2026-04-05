-- V14: Add audit columns to remaining admin-managed tables
-- Phase 2 of JPA auditing migration (extends V13)

-- Scheduled tasks
ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Webhook connectors
ALTER TABLE webhook_connectors ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE webhook_connectors ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE webhook_connectors ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE webhook_connectors ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- AS2 partnerships
ALTER TABLE as2_partnerships ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE as2_partnerships ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE as2_partnerships ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE as2_partnerships ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Legacy server configs
ALTER TABLE legacy_server_configs ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE legacy_server_configs ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE legacy_server_configs ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE legacy_server_configs ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Platform settings
ALTER TABLE platform_settings ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE platform_settings ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE platform_settings ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE platform_settings ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Encryption keys
ALTER TABLE encryption_keys ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE encryption_keys ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE encryption_keys ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Tenants
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Partner agreements
ALTER TABLE partner_agreements ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE partner_agreements ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE partner_agreements ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE partner_agreements ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Partner contacts
ALTER TABLE partner_contacts ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE partner_contacts ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE partner_contacts ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE partner_contacts ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

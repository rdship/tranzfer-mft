-- V13: Add audit columns to key tables for JPA auditing support
-- These columns are populated automatically by Spring Data JPA (@CreatedDate, @CreatedBy, etc.)

-- Users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Transfer accounts
ALTER TABLE transfer_accounts ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE transfer_accounts ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE transfer_accounts ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE transfer_accounts ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Folder mappings
ALTER TABLE folder_mappings ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE folder_mappings ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE folder_mappings ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE folder_mappings ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Server configs
ALTER TABLE server_configs ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE server_configs ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE server_configs ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE server_configs ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- File flows
ALTER TABLE file_flows ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE file_flows ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE file_flows ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Security profiles
ALTER TABLE security_profiles ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE security_profiles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE security_profiles ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE security_profiles ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Partners
ALTER TABLE partners ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE partners ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- External destinations
ALTER TABLE external_destinations ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE external_destinations ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE external_destinations ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE external_destinations ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Delivery endpoints
ALTER TABLE delivery_endpoints ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();
ALTER TABLE delivery_endpoints ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE delivery_endpoints ADD COLUMN IF NOT EXISTS created_by VARCHAR(100);
ALTER TABLE delivery_endpoints ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

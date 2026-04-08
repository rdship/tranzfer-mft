-- =============================================================================
-- V20: Fine-Grained RBAC + Audit Log Encryption + HMAC Key Rotation
-- =============================================================================

-- ===== Task 1: RBAC Permission Tables =====

CREATE TABLE IF NOT EXISTS permissions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(100) NOT NULL UNIQUE,
    description   TEXT,
    resource_type VARCHAR(50) NOT NULL,
    action        VARCHAR(20) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE IF NOT EXISTS role_permissions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role          VARCHAR(20) NOT NULL,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT now(),
    UNIQUE(role, permission_id)
);

CREATE TABLE IF NOT EXISTS user_permissions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    resource_id   UUID,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_role_permissions_role ON role_permissions(role);
CREATE INDEX IF NOT EXISTS idx_user_permissions_user ON user_permissions(user_id);
CREATE INDEX IF NOT EXISTS idx_user_permissions_resource ON user_permissions(resource_id);

-- Seed permissions
INSERT INTO permissions (name, description, resource_type, action) VALUES
    ('PARTNER_READ',   'View partner details',            'PARTNER',  'READ'),
    ('PARTNER_WRITE',  'Create/update partners',          'PARTNER',  'WRITE'),
    ('PARTNER_DELETE', 'Delete partners',                  'PARTNER',  'DELETE'),
    ('SERVER_READ',    'View server instances',            'SERVER',   'READ'),
    ('SERVER_WRITE',   'Create/update server instances',   'SERVER',   'WRITE'),
    ('FLOW_READ',      'View file flows',                  'FLOW',     'READ'),
    ('FLOW_WRITE',     'Create/update file flows',         'FLOW',     'WRITE'),
    ('FLOW_EXECUTE',   'Execute file flows',               'FLOW',     'EXECUTE'),
    ('USER_READ',      'View user details',                'USER',     'READ'),
    ('USER_WRITE',     'Create/update users',              'USER',     'WRITE'),
    ('USER_DELETE',    'Delete users',                     'USER',     'DELETE'),
    ('AUDIT_READ',     'View audit logs',                  'AUDIT',    'READ'),
    ('CONFIG_READ',    'View platform configuration',      'CONFIG',   'READ'),
    ('CONFIG_WRITE',   'Modify platform configuration',    'CONFIG',   'WRITE'),
    ('TRANSFER_READ',  'View file transfer records',       'TRANSFER', 'READ'),
    ('TRANSFER_WRITE', 'Create/manage file transfers',     'TRANSFER', 'WRITE')
ON CONFLICT (name) DO NOTHING;

-- Seed role_permissions: ADMIN gets all
INSERT INTO role_permissions (role, permission_id)
SELECT 'ADMIN', id FROM permissions
ON CONFLICT (role, permission_id) DO NOTHING;

-- OPERATOR gets most except USER_DELETE
INSERT INTO role_permissions (role, permission_id)
SELECT 'OPERATOR', id FROM permissions WHERE name NOT IN ('USER_DELETE')
ON CONFLICT (role, permission_id) DO NOTHING;

-- USER gets READ-only + TRANSFER_WRITE
INSERT INTO role_permissions (role, permission_id)
SELECT 'USER', id FROM permissions WHERE action = 'READ' OR name = 'TRANSFER_WRITE'
ON CONFLICT (role, permission_id) DO NOTHING;

-- VIEWER gets READ-only
INSERT INTO role_permissions (role, permission_id)
SELECT 'VIEWER', id FROM permissions WHERE action = 'READ'
ON CONFLICT (role, permission_id) DO NOTHING;

-- PARTNER gets PARTNER_READ + TRANSFER_READ
INSERT INTO role_permissions (role, permission_id)
SELECT 'PARTNER', id FROM permissions WHERE name IN ('PARTNER_READ', 'TRANSFER_READ')
ON CONFLICT (role, permission_id) DO NOTHING;

-- ===== Task 3: Audit Log Encryption =====

ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS encrypted BOOLEAN NOT NULL DEFAULT false;

-- ===== Task 4: HMAC Key Rotation =====

ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS hmac_key_version INTEGER NOT NULL DEFAULT 1;

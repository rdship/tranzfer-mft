-- Partner management tables for TranzFer MFT
-- Provides unified partner lifecycle management

CREATE TABLE partners (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    slug VARCHAR(100) UNIQUE NOT NULL,
    industry VARCHAR(100),
    website VARCHAR(500),
    logo_url VARCHAR(1000),

    -- Partner classification
    partner_type VARCHAR(30) NOT NULL DEFAULT 'EXTERNAL',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    onboarding_phase VARCHAR(30) DEFAULT 'SETUP',

    -- Protocols enabled (JSONB array of strings)
    protocols_enabled JSONB DEFAULT '[]'::jsonb,

    -- SLA configuration
    sla_tier VARCHAR(30) DEFAULT 'STANDARD',
    max_file_size_bytes BIGINT DEFAULT 536870912,
    max_transfers_per_day INTEGER DEFAULT 1000,
    retention_days INTEGER DEFAULT 90,

    -- Notes
    notes TEXT,

    -- Audit
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE partner_contacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_id UUID NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(50),
    role VARCHAR(100) NOT NULL DEFAULT 'Technical',
    is_primary BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Link transfer accounts to partners
ALTER TABLE transfer_accounts ADD COLUMN IF NOT EXISTS partner_id UUID REFERENCES partners(id);

-- Link delivery endpoints to partners
ALTER TABLE delivery_endpoints ADD COLUMN IF NOT EXISTS partner_id UUID REFERENCES partners(id);

-- Link file flows to partners
ALTER TABLE file_flows ADD COLUMN IF NOT EXISTS partner_id UUID REFERENCES partners(id);

-- Link partner agreements to partners table
ALTER TABLE partner_agreements ADD COLUMN IF NOT EXISTS partner_id UUID REFERENCES partners(id);

-- Indexes
CREATE INDEX idx_partners_status ON partners(status);
CREATE INDEX idx_partners_type ON partners(partner_type);
CREATE INDEX idx_partners_slug ON partners(slug);
CREATE INDEX idx_partner_contacts_partner ON partner_contacts(partner_id);
CREATE INDEX idx_ta_partner ON transfer_accounts(partner_id) WHERE partner_id IS NOT NULL;
CREATE INDEX idx_de_partner ON delivery_endpoints(partner_id) WHERE partner_id IS NOT NULL;
CREATE INDEX idx_ff_partner ON file_flows(partner_id) WHERE partner_id IS NOT NULL;
CREATE INDEX idx_pa_partner ON partner_agreements(partner_id) WHERE partner_id IS NOT NULL;

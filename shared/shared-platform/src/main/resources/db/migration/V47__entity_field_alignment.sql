-- V47: Align entity fields with UI form capabilities

-- PartnerAgreement (SLA) — tier, protocol scoping, latency target
ALTER TABLE partner_agreements ADD COLUMN IF NOT EXISTS partner_name VARCHAR(200);
ALTER TABLE partner_agreements ADD COLUMN IF NOT EXISTS tier VARCHAR(20);
ALTER TABLE partner_agreements ADD COLUMN IF NOT EXISTS protocol VARCHAR(20);
ALTER TABLE partner_agreements ADD COLUMN IF NOT EXISTS server_id UUID;
ALTER TABLE partner_agreements ADD COLUMN IF NOT EXISTS max_latency_seconds INTEGER;

-- ServerInstance — security profile binding
ALTER TABLE server_instances ADD COLUMN IF NOT EXISTS security_profile_id UUID;

-- ExternalDestination — protocol-specific config
ALTER TABLE external_destinations ADD COLUMN IF NOT EXISTS auth_type VARCHAR(20);
ALTER TABLE external_destinations ADD COLUMN IF NOT EXISTS ssh_key_alias VARCHAR(100);
ALTER TABLE external_destinations ADD COLUMN IF NOT EXISTS cert_alias VARCHAR(100);
ALTER TABLE external_destinations ADD COLUMN IF NOT EXISTS passive_mode BOOLEAN DEFAULT false;
ALTER TABLE external_destinations ADD COLUMN IF NOT EXISTS bearer_token VARCHAR(500);
ALTER TABLE external_destinations ADD COLUMN IF NOT EXISTS protocol_config TEXT;

-- WebhookConnector — type-specific fields
ALTER TABLE webhook_connectors ADD COLUMN IF NOT EXISTS channel VARCHAR(100);
ALTER TABLE webhook_connectors ADD COLUMN IF NOT EXISTS api_key VARCHAR(500);
ALTER TABLE webhook_connectors ADD COLUMN IF NOT EXISTS region VARCHAR(10);
ALTER TABLE webhook_connectors ADD COLUMN IF NOT EXISTS priority VARCHAR(10);
ALTER TABLE webhook_connectors ADD COLUMN IF NOT EXISTS auth_type VARCHAR(20);

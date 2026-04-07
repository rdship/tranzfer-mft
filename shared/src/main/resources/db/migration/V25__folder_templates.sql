-- Folder Templates: configurable directory structures for server instances
CREATE TABLE IF NOT EXISTS folder_templates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL UNIQUE,
    description     TEXT,
    built_in        BOOLEAN NOT NULL DEFAULT FALSE,
    folders         JSONB NOT NULL DEFAULT '[]'::jsonb,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    created_by      VARCHAR(255),
    updated_by      VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_ft_name ON folder_templates(name);
CREATE INDEX IF NOT EXISTS idx_ft_active ON folder_templates(active);

-- 7 built-in templates
INSERT INTO folder_templates (name, description, built_in, folders) VALUES
('Standard', 'Default structure with inbox, outbox, archive, and sent folders', TRUE,
 '[{"path":"inbox","description":"Inbound file drop zone"},{"path":"outbox","description":"Files ready for partner download"},{"path":"archive","description":"Archived copies of processed files"},{"path":"sent","description":"Files moved here after partner downloads"}]'::jsonb),

('EDI Trading', 'Extended structure for EDI document exchange with dedicated EDI folders', TRUE,
 '[{"path":"inbox","description":"Inbound files"},{"path":"outbox","description":"Outbound files"},{"path":"archive","description":"Archived files"},{"path":"sent","description":"Downloaded files"},{"path":"edi/inbound","description":"Inbound EDI documents"},{"path":"edi/outbound","description":"Outbound EDI documents"},{"path":"edi/acknowledgments","description":"EDI functional acknowledgments (997/CONTRL)"}]'::jsonb),

('Simple Drop', 'Minimal upload/download structure', TRUE,
 '[{"path":"upload","description":"Drop files here for processing"},{"path":"download","description":"Processed files ready for retrieval"}]'::jsonb),

('Healthcare', 'Structure for healthcare data exchange (HL7/FHIR)', TRUE,
 '[{"path":"inbox","description":"Inbound files"},{"path":"outbox","description":"Outbound files"},{"path":"archive","description":"Archived files"},{"path":"sent","description":"Downloaded files"},{"path":"hl7/inbound","description":"Inbound HL7 messages"},{"path":"hl7/outbound","description":"Outbound HL7 messages"},{"path":"hl7/errors","description":"Failed HL7 message processing"}]'::jsonb),

('Financial', 'Structure for financial document exchange with reporting folders', TRUE,
 '[{"path":"inbox","description":"Inbound files"},{"path":"outbox","description":"Outbound files"},{"path":"archive","description":"Archived files"},{"path":"sent","description":"Downloaded files"},{"path":"reports","description":"Generated reports"},{"path":"statements","description":"Account statements"},{"path":"confirmations","description":"Transaction confirmations"}]'::jsonb),

('Minimal', 'Bare minimum inbox/outbox only', TRUE,
 '[{"path":"inbox","description":"Inbound files"},{"path":"outbox","description":"Outbound files"}]'::jsonb),

('Full Audit', 'Comprehensive structure with quarantine and approval workflow folders', TRUE,
 '[{"path":"inbox","description":"Inbound files"},{"path":"outbox","description":"Outbound files"},{"path":"archive","description":"Archived files"},{"path":"sent","description":"Downloaded files"},{"path":"quarantine","description":"Files held for security review"},{"path":"review","description":"Files pending manual review"},{"path":"approved","description":"Approved files ready for processing"},{"path":"rejected","description":"Rejected files"}]'::jsonb);

-- Add folder template FK to server_instances
ALTER TABLE server_instances ADD COLUMN IF NOT EXISTS folder_template_id UUID REFERENCES folder_templates(id);
CREATE INDEX IF NOT EXISTS idx_si_folder_template ON server_instances(folder_template_id);

-- Add folder template FK to server_configs
ALTER TABLE server_configs ADD COLUMN IF NOT EXISTS folder_template_id UUID REFERENCES folder_templates(id);
CREATE INDEX IF NOT EXISTS idx_sc_folder_template ON server_configs(folder_template_id);

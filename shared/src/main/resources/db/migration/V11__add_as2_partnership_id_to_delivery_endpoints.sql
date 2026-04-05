ALTER TABLE delivery_endpoints ADD COLUMN IF NOT EXISTS as2_partnership_id UUID;

-- FK to as2_partnerships (nullable — only set when protocol is AS2 or AS4)
ALTER TABLE delivery_endpoints
    ADD CONSTRAINT fk_delivery_endpoint_as2_partnership
    FOREIGN KEY (as2_partnership_id) REFERENCES as2_partnerships(id);

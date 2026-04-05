-- V9: Add AS2/AS4 protocol support to transfer_accounts
-- Allows the AS2 service to auto-provision TransferAccounts for inbound AS2/AS4 partners,
-- bridging them into the standard RoutingEngine file flow.

-- The Protocol enum in Java now includes AS2 and AS4.
-- PostgreSQL enum types need to be extended to accept these new values.
-- Since transfer_accounts.protocol is stored as VARCHAR (EnumType.STRING), no ALTER TYPE needed.

-- Add index for AS2 account lookups (used by As2AccountService)
CREATE INDEX IF NOT EXISTS idx_transfer_accounts_protocol
    ON transfer_accounts (protocol) WHERE active = true;

-- Add index for AS2 message direction queries (inbound message listing)
CREATE INDEX IF NOT EXISTS idx_as2_messages_direction
    ON as2_messages (direction, status);

-- Add index for async MDN processing (find pending async MDNs)
CREATE INDEX IF NOT EXISTS idx_as2_messages_mdn_status
    ON as2_messages (mdn_status) WHERE mdn_status LIKE 'ASYNC_PENDING%';

-- Add track_id to as2_messages for linking to platform transfers
CREATE INDEX IF NOT EXISTS idx_as2_messages_track_id
    ON as2_messages (track_id) WHERE track_id IS NOT NULL;

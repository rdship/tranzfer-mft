-- R109 (F4): opt-in sender notification on partner pickup.
--
-- Tester R100 report asked for "notify sender when partner downloads their
-- file". Implementation: a per-mapping flag. When set to TRUE and the
-- mapping's transfer transitions to MOVED_TO_SENT (partner has pulled it
-- from their outbox), RoutingEngine dispatches a PARTNER_PICKUP lifecycle
-- event. notification-service subscribes to these on the shared event bus
-- and emails / webhooks the source account owner.
--
-- Default FALSE — behaviour unchanged for existing mappings. Admins opt in
-- from the FolderMapping edit UI per relationship.

ALTER TABLE folder_mappings
    ADD COLUMN IF NOT EXISTS notify_on_pickup BOOLEAN NOT NULL DEFAULT FALSE;

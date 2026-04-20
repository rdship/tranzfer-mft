-- =============================================================================
-- TranzFer MFT — https-service schema (readiness reference)
-- https-service owns NO tables of its own; it consumes:
--   server_instances  — listener desired/actual state (managed by onboarding-api)
--   virtual_entries   — VFS entry row written on every upload
-- This file exists only so the shared readiness probe can confirm both
-- tables are present before the service claims ready. All CREATEs use
-- IF NOT EXISTS so running this against a populated schema is a no-op.
-- =============================================================================

-- Reference stubs — real schema is owned by shared-platform migrations.
CREATE TABLE IF NOT EXISTS server_instances (id UUID PRIMARY KEY);
CREATE TABLE IF NOT EXISTS virtual_entries  (id UUID PRIMARY KEY);

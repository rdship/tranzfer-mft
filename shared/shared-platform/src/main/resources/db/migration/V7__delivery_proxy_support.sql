-- =============================================================================
-- V7: Add proxy support to delivery endpoints
-- =============================================================================
-- Users can optionally route external deliveries through a proxy (DMZ, HTTP, SOCKS5).
-- When proxy_enabled = false (default), the forwarder connects directly.
-- =============================================================================

ALTER TABLE delivery_endpoints ADD COLUMN proxy_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE delivery_endpoints ADD COLUMN proxy_type    VARCHAR(20);   -- DMZ, HTTP, SOCKS5
ALTER TABLE delivery_endpoints ADD COLUMN proxy_host    VARCHAR(500);
ALTER TABLE delivery_endpoints ADD COLUMN proxy_port    INTEGER;

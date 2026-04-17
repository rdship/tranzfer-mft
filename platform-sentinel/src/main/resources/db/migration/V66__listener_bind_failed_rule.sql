-- =============================================================================
-- V66 — Sentinel rule for dynamic listener bind failures
--
-- Fires when one or more active ServerInstances have bind_state='BIND_FAILED'.
-- Points admins at the specific listener that is down so they can trigger
-- /api/servers/{id}/rebind or pick a free port via port-suggestions.
-- =============================================================================

INSERT INTO sentinel_rules (name, analyzer, severity, threshold_value, cooldown_minutes, enabled, builtin, description)
VALUES (
    'listener_bind_failed',
    'PERFORMANCE',
    'HIGH',
    NULL,
    15,
    TRUE,
    TRUE,
    'Active ServerInstance listeners that failed to bind their configured port. '
    || 'Check docker-compose port conflicts or use /api/servers/port-suggestions to pick a free port.'
)
ON CONFLICT (name) DO NOTHING;

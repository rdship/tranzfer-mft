-- ============================================================================
-- V17: AI LLM opt-in platform settings
--
-- External LLM usage is opt-in. The ai.llm.enabled flag defaults to false;
-- administrators explicitly enable it and provide an API key via the UI.
-- ============================================================================

INSERT INTO platform_settings (id, setting_key, setting_value, environment, service_name, data_type, category, description, sensitive, active, created_at, updated_at)
VALUES
  (gen_random_uuid(), 'ai.llm.enabled', 'false', 'PROD', 'GLOBAL', 'BOOLEAN', 'AI', 'Enable external LLM (Claude) for enhanced AI features. When disabled, all AI features use built-in pattern matching.', false, true, NOW(), NOW()),
  (gen_random_uuid(), 'ai.llm.api-key', '', 'PROD', 'GLOBAL', 'STRING', 'AI', 'Anthropic API key for Claude integration. Only used when ai.llm.enabled is true.', true, true, NOW(), NOW()),
  (gen_random_uuid(), 'ai.llm.model', 'claude-sonnet-4-20250514', 'PROD', 'GLOBAL', 'STRING', 'AI', 'Claude model to use for LLM features.', false, true, NOW(), NOW()),
  (gen_random_uuid(), 'ai.llm.base-url', 'https://api.anthropic.com', 'PROD', 'GLOBAL', 'STRING', 'AI', 'LLM API endpoint URL. Use HTTPS for production. Supports custom/proxied endpoints.', false, true, NOW(), NOW())
ON CONFLICT DO NOTHING;

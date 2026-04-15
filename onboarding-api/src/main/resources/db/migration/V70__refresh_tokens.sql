-- V70: Refresh token table for JWT token rotation.
-- Access tokens expire in 15 minutes. Refresh tokens last 7 days.
-- Each refresh rotates the token (old one revoked).
-- Logout revokes all refresh tokens for the user.

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token VARCHAR(64) NOT NULL UNIQUE,
    user_email VARCHAR(255) NOT NULL,
    user_role VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT false,
    client_ip VARCHAR(45),
    user_agent VARCHAR(512)
);

CREATE INDEX IF NOT EXISTS idx_refresh_token ON refresh_tokens (token) WHERE NOT revoked;
CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_tokens (user_email) WHERE NOT revoked;
CREATE INDEX IF NOT EXISTS idx_refresh_expiry ON refresh_tokens (expires_at);

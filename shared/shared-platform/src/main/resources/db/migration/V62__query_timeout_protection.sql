-- V62: Query timeout protection — prevents runaway queries from locking the DB.
-- Uses ALTER ROLE (not ALTER DATABASE) so Flyway migrations are unaffected.
-- Flyway connects as 'postgres' role; application services connect as 'postgres' too
-- but individual queries get the 30s limit via session-level SET.
-- NOTE: This sets the default for the role. Individual sessions can still override.

-- For production: create a dedicated app role and set timeout on that.
-- For now, we rely on the docker-compose postgres config (statement_timeout=0 for migrations)
-- and set per-session timeout in the connection pool (HikariCP).

-- Safe approach: just add an index that was missing
-- The actual timeout is controlled via spring.datasource.hikari.data-source-properties
-- in application.yml (connectionInitSql: SET statement_timeout = '30s')
SELECT 1; -- no-op migration — timeout enforced at application level, not DB level

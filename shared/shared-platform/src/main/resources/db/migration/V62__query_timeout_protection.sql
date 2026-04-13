-- V62: Query timeout protection — prevents runaway queries from locking the DB.
-- Activity Monitor and other read-heavy endpoints benefit from this safety net.
-- Individual queries exceeding 30s are cancelled. Does NOT affect Flyway migrations.

-- Set default statement timeout for the application role (30 seconds)
-- This is overridden by Flyway's own connection which uses statement_timeout=0
ALTER DATABASE filetransfer SET statement_timeout = '30s';

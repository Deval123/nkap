-- Hand-written inverse of db/migration/V10__api_key_revocation.sql.
--
-- Flyway never runs this -- its undo is a paid feature -- MigrationRollbackIT does: it
-- migrates a fresh database through V10, runs this, and asserts the schema is back to
-- exactly what V9 left.

ALTER TABLE api_key DROP COLUMN IF EXISTS revoked_at;

DELETE FROM flyway_schema_history WHERE version = '10';

-- Hand-written inverse of db/migration/V6__api_keys.sql.
--
-- Flyway never runs this -- its undo is a paid feature -- MigrationRollbackIT does: it
-- migrates a fresh database through V6, runs this, and asserts the schema is back to
-- exactly what V5 left.
--
-- The index goes with its table. Then V6's history row is removed so the database is
-- re-migratable.

DROP TABLE IF EXISTS api_key;

DELETE FROM flyway_schema_history WHERE version = '6';

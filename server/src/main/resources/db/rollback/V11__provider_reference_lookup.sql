-- Hand-written inverse of db/migration/V11__provider_reference_lookup.sql.
--
-- Flyway never runs this -- its undo is a paid feature -- MigrationRollbackIT does: it
-- migrates a fresh database through V11, runs this, and asserts the schema is back to
-- exactly what V10 left.

DROP INDEX IF EXISTS payment_provider_reference_idx;

DELETE FROM flyway_schema_history WHERE version = '11';

-- Hand-written inverse of db/migration/V7__webhooks_and_outbox.sql.
--
-- Flyway never runs this -- its undo is a paid feature -- MigrationRollbackIT does: it
-- migrates a fresh database through V7, runs this, and asserts the schema is back to
-- exactly what V6 left.
--
-- Indexes go with their table. Then V7's history row is removed so the database is
-- re-migratable.

DROP TABLE IF EXISTS outbox_event;
DROP TABLE IF EXISTS webhook_endpoint;

DELETE FROM flyway_schema_history WHERE version = '7';

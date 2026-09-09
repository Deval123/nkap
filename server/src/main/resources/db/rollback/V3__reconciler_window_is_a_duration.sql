-- Hand-written inverse of db/migration/V3__reconciler_window_is_a_duration.sql.
--
-- "Reversible" for this project means a tested inverse script, kept beside the migration
-- under the same file name in db/rollback/. Flyway never runs this -- its undo is a paid
-- feature -- MigrationRollbackIT does: it migrates a fresh database through V3, runs this,
-- and asserts the schema is back to exactly what V2 left.
--
-- Drops exactly what V3 added (the backfill UPDATE leaves nothing to undo -- the column it
-- wrote goes with the column), then removes V3's row from flyway_schema_history so the
-- database is re-migratable.

ALTER TABLE payment DROP COLUMN IF EXISTS unknown_since;

DELETE FROM flyway_schema_history WHERE version = '3';

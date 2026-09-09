-- Hand-written inverse of db/migration/V2__reconciler_schedule.sql.
--
-- "Reversible" for this project means a tested inverse script, kept beside the migration
-- under the same file name in db/rollback/. Flyway never runs this — its undo is a paid
-- feature — MigrationRollbackIT does: it migrates a fresh database through V2, runs this,
-- and asserts the schema is back to exactly what V1 left.
--
-- Drops exactly what V2 added, then removes V2's row from flyway_schema_history so the
-- database is re-migratable.

DROP INDEX IF EXISTS payment_reconcile_due_idx;

ALTER TABLE payment
    DROP COLUMN IF EXISTS escalated_at,
    DROP COLUMN IF EXISTS reconcile_due_at,
    DROP COLUMN IF EXISTS reconcile_attempts;

DELETE FROM flyway_schema_history WHERE version = '2';

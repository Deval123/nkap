-- Hand-written inverse of db/migration/V4__reconciler_chases_unresolved.sql.
--
-- "Reversible" for this project means a tested inverse script, kept beside the migration
-- under the same file name in db/rollback/. Flyway never runs this -- its undo is a paid
-- feature -- MigrationRollbackIT does: it migrates a fresh database through V4, runs this,
-- and asserts the schema is back to exactly what V3 left.
--
-- Undoes exactly what V4 did, in reverse: the index predicate back to state = 'UNKNOWN',
-- the column back to unknown_since. The backfill leaves nothing to undo -- the values it
-- wrote stay under the old name, and V3's inverse drops the column whole -- then V4's
-- history row is removed so the database is re-migratable.

DROP INDEX IF EXISTS payment_reconcile_due_idx;
CREATE INDEX payment_reconcile_due_idx
    ON payment (reconcile_due_at)
    WHERE state = 'UNKNOWN' AND escalated_at IS NULL;

ALTER TABLE payment RENAME COLUMN unresolved_since TO unknown_since;

DELETE FROM flyway_schema_history WHERE version = '4';

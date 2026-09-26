-- Hand-written inverse of db/migration/V12__escalation_reason.sql.
--
-- Flyway never runs this -- its undo is a paid feature -- MigrationRollbackIT does: it
-- migrates a fresh database through V12, runs this, and asserts the schema is back to
-- exactly what V11 left. The reasons recorded since V12 are lost with the column; the
-- endpoint falls back to deriving them, as it did before V12.

ALTER TABLE payment
    DROP COLUMN IF EXISTS escalation_reason;

DELETE FROM flyway_schema_history WHERE version = '12';

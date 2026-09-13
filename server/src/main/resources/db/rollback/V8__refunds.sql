-- Hand-written inverse of db/migration/V8__refunds.sql.
--
-- Flyway never runs this -- its undo is a paid feature -- MigrationRollbackIT does: it
-- migrates a fresh database through V8, runs this, and asserts the schema is back to
-- exactly what V7 left.

DROP INDEX IF EXISTS payment_refund_of_idx;

ALTER TABLE payment
    DROP CONSTRAINT IF EXISTS payment_refunded_within_amount,
    DROP CONSTRAINT IF EXISTS payment_refund_of_is_a_disbursement,
    DROP CONSTRAINT IF EXISTS payment_refund_not_of_itself,
    DROP COLUMN IF EXISTS refunded_minor,
    DROP COLUMN IF EXISTS refund_of;

DELETE FROM flyway_schema_history WHERE version = '8';

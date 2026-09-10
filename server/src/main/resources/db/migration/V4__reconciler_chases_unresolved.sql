-- unknown_since was named for the one state the reconciler used to chase. It now chases
-- every payment that has left CREATED and not reached a terminal verdict -- SUBMITTED,
-- PENDING and UNKNOWN. An acknowledged submission that then goes silent, or a PENDING the
-- payer never approves, is exactly as unresolved as a timeout, and exactly as capable of
-- sitting forever with no ledger entry and nobody paged. CREATED stays out: the submission
-- is still in flight, or has not been sent, and that moment is PaymentService's to own.
--
-- Rename the column to what it holds -- the moment the payment became unresolved -- and
-- widen the partial index so the claim query still reads it as an index range scan.
--
-- Renaming now, two migrations in and nothing deployed: a name that lies is how the last
-- window bug got past review. A V5 that renamed it later would cost this same work plus the
-- confusion in between.
--
-- Backfill: a SUBMITTED or PENDING payment that predates this migration has a NULL
-- unresolved_since, which the policy reads as "already past the window" and would escalate
-- on its next pass. updated_at is the same best-evidence stand-in V3 used for UNKNOWN --
-- inexact, and this comment is the honest record of that.
--
-- Hand-written inverse: db/rollback/V4__reconciler_chases_unresolved.sql, exercised by
-- MigrationRollbackIT.

ALTER TABLE payment RENAME COLUMN unknown_since TO unresolved_since;

UPDATE payment SET unresolved_since = updated_at
 WHERE state IN ('SUBMITTED', 'PENDING') AND unresolved_since IS NULL;

-- The predicate must stay in step with PaymentState.isUnresolved (and with
-- PostgresReconciliationStore.UNRESOLVED_STATES, which the claim query uses): if a fourth
-- non-terminal state is ever added, widen this index in a new migration at the same time.
-- Left behind, the claim still returns the right rows but stops being an index range scan,
-- and that only shows up as unexplained slowness once the table is large.
DROP INDEX payment_reconcile_due_idx;
CREATE INDEX payment_reconcile_due_idx
    ON payment (reconcile_due_at)
    WHERE state IN ('SUBMITTED', 'PENDING', 'UNKNOWN') AND escalated_at IS NULL;

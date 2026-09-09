-- The reconciler's schedule, carried on the payment row.
--
-- A payment that reaches UNKNOWN is not finished: the reconciler queries the operator
-- again, on an exponential backoff, until either an answer arrives or the window is spent
-- and a human is paged. That loop needs three facts per payment — how many attempts have
-- been made, when the next one is due, and whether it has been escalated — and one index
-- so the "what is due now" query is not a full scan.
--
-- Escalation is a timestamp, not a state. An escalated payment is still UNKNOWN and still
-- resolvable by a later callback or query; escalated_at only records that the automatic
-- retries stopped and someone was asked to look. Nothing here can move a payment to
-- FAILED — that remains something only the operator can say.
--
-- The hand-written inverse is db/rollback/V2__reconciler_schedule.sql, exercised by
-- MigrationRollbackIT.

ALTER TABLE payment
    ADD COLUMN reconcile_attempts integer     NOT NULL DEFAULT 0,
    ADD COLUMN reconcile_due_at   timestamptz,
    ADD COLUMN escalated_at       timestamptz;

-- Partial index: the claim query only ever wants UNKNOWN, not-yet-escalated payments whose
-- next attempt is due. Keeping the predicate in the index keeps it small — a settled
-- payment is not in it at all — and lets the claim be an index range scan on the due time.
CREATE INDEX payment_reconcile_due_idx
    ON payment (reconcile_due_at)
    WHERE state = 'UNKNOWN' AND escalated_at IS NULL;

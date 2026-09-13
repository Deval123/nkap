-- Refunds (issue #84, ADR 0010): a refund is a DISBURSE payment caused by a collection,
-- never a ledger reversal -- so it needs no new state machine and no new ledger mechanism,
-- just two columns on the table `payment` already has.
--
-- refund_of: which SUCCEEDED collection this DISBURSE row sends money back for, NULL for
-- every payment that is not a refund. A third Capability.Operation was rejected for this --
-- see ADR 0010 -- so this column, not the operation, is what distinguishes a refund from an
-- ordinary disbursement.
--
-- refunded_minor: the running total reserved or already paid out against a COLLECT payment
-- by refunds of it. Incremented under the row's own SELECT ... FOR UPDATE lock the moment a
-- refund is created -- see Payment.reserveRefund -- before the operator is ever asked, and
-- released only if that refund ends FAILED or EXPIRED (Payment.releaseRefundReservation).
-- The CHECK below is the actual guarantee that a merchant is never refunded past what they
-- were ever paid: the application re-derives and checks this same number under the same
-- lock, but two concurrent writers that both slipped past that check -- the exact bug this
-- column exists to make impossible -- would still be caught here, at commit.
--
-- Hand-written inverse: db/rollback/V8__refunds.sql, exercised by MigrationRollbackIT.

ALTER TABLE payment
    ADD COLUMN refund_of      uuid   REFERENCES payment (reference),
    ADD COLUMN refunded_minor bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT payment_refund_not_of_itself CHECK (refund_of IS NULL OR refund_of <> reference),
    ADD CONSTRAINT payment_refund_of_is_a_disbursement CHECK (refund_of IS NULL OR operation = 'DISBURSE'),
    ADD CONSTRAINT payment_refunded_within_amount CHECK (refunded_minor >= 0 AND refunded_minor <= amount_minor);

-- Refunds of one collection are looked up by refund_of; the partial index keeps that an
-- index scan instead of a table scan as unrelated payments (refund_of IS NULL) pile up.
CREATE INDEX payment_refund_of_idx ON payment (refund_of) WHERE refund_of IS NOT NULL;

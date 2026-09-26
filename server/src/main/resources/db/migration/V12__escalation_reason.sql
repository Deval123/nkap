-- Why a payment was escalated, stored when it is escalated (ADR 0016, issue #252).
--
-- Until now the reason was recomputed on read by GET /actuator/escalatedPayments. That worked
-- for no reason added since: reason=no_adapter means no adapter was configured for the
-- payment's provider when its window was spent, and the moment someone reads the list is
-- usually after that adapter has been restored, when a recomputation would say something
-- else. A reason is a fact about the escalation, so it is recorded with it.
--
-- Written in the same statement as escalated_at and meaningful only where escalated_at is set.
-- NULL on every row escalated before this migration: their reason was never recorded, and
-- the endpoint keeps deriving it for them as it did before. No CHECK on the values: the codes
-- are listed once, in EscalationReason, and a new one should not need a migration.
--
-- The hand-written inverse is db/rollback/V12__escalation_reason.sql, exercised by
-- MigrationRollbackIT.

ALTER TABLE payment
    ADD COLUMN escalation_reason text;

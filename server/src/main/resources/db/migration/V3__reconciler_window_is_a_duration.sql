-- nkap.reconciler.window is a Duration, and an operator reads it as wall-clock time: "a
-- payment unresolved for this long is escalated to a human". The reconciler did not measure
-- that. ReconciliationPolicy summed the theoretical backoff intervals of the attempts made
-- so far and compared the total to the window -- which only equals elapsed time while every
-- pass runs on schedule. A day-long outage escalates late; a restart loop escalates early.
--
-- unknown_since records when the payment entered UNKNOWN, so the policy can measure the
-- real time that has passed instead of inferring it from a counter.
--
-- Backfill: for payments that are UNKNOWN right now, updated_at is the best evidence of
-- when they got there -- the transition into UNKNOWN was a write, and any write since was a
-- reconciler pass that did not resolve them, which is close enough. It is not exact, and
-- this comment is the honest record of that. It is still far better than a NULL, which the
-- policy reads as "we have already waited long enough" and escalates on the next pass.
--
-- The partial index is unchanged: unknown_since is read from the claimed row, never
-- filtered on.
--
-- Hand-written inverse: db/rollback/V3__reconciler_window_is_a_duration.sql, exercised by
-- MigrationRollbackIT.

ALTER TABLE payment ADD COLUMN unknown_since timestamptz;

UPDATE payment SET unknown_since = updated_at WHERE state = 'UNKNOWN';

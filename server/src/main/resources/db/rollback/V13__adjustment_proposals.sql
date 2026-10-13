-- Hand-written inverse of db/migration/V13__adjustment_proposals.sql.
--
-- Flyway never runs this -- its undo is a paid feature -- MigrationRollbackIT does: it
-- migrates a fresh database through V13, runs this, and asserts the schema is back to
-- exactly what V12 left. Dropping the tables drops their triggers and indexes with them.
-- Every proposal and decision recorded since V13 is lost; any ledger entry an approval wrote
-- stays, because the ledger is append-only and this does not touch it.

DROP TABLE IF EXISTS adjustment_decision;
DROP TABLE IF EXISTS adjustment_proposal;

DELETE FROM flyway_schema_history WHERE version = '13';

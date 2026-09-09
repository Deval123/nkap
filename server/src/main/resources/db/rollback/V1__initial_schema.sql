-- Hand-written inverse of db/migration/V1__initial_schema.sql.
--
-- "Reversible" for this project means a tested inverse script, kept beside the migration
-- under the same file name in db/rollback/. Flyway never runs this — its undo is a paid
-- feature — MigrationRollbackIT does: it migrates a fresh database, runs this, and asserts
-- the schema is byte-for-byte back to empty.
--
-- Drops exactly what V1 created, in reverse dependency order, then removes V1's row from
-- flyway_schema_history so the database is re-migratable.

DROP TRIGGER IF EXISTS posting_entry_balances ON posting;
DROP TRIGGER IF EXISTS posting_append_only ON posting;
DROP TRIGGER IF EXISTS ledger_entry_append_only ON ledger_entry;
DROP TRIGGER IF EXISTS payment_transition_append_only ON payment_transition;

DROP TABLE IF EXISTS posting;
DROP TABLE IF EXISTS ledger_entry;
DROP TABLE IF EXISTS payment_transition;
DROP TABLE IF EXISTS payment;
DROP TABLE IF EXISTS idempotency_record;

DROP FUNCTION IF EXISTS nkap_ledger_entry_balances();
DROP FUNCTION IF EXISTS nkap_forbid_mutation();

DELETE FROM flyway_schema_history WHERE version = '1';

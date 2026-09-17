-- Hand-written inverse of db/migration/V9__settlement_provenance.sql.
--
-- Flyway never runs this -- its undo is a paid feature -- MigrationRollbackIT does: it
-- migrates a fresh database through V9, runs this, and asserts the schema is back to
-- exactly what V8 left.

DROP TRIGGER IF EXISTS payment_provider_base_url_is_final ON payment;
DROP FUNCTION IF EXISTS nkap_payment_provider_base_url_is_final();
ALTER TABLE payment DROP COLUMN IF EXISTS provider_base_url;

DELETE FROM flyway_schema_history WHERE version = '9';

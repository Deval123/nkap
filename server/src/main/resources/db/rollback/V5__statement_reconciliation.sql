-- Hand-written inverse of db/migration/V5__statement_reconciliation.sql.
--
-- Flyway never runs this -- its undo is a paid feature -- MigrationRollbackIT does: it
-- migrates a fresh database through V5, runs this, and asserts the schema is back to exactly
-- what V4 left.
--
-- Undoes exactly what V5 did, in reverse: the three append-only triggers, then the three
-- tables (findings and lines first -- they reference statement_import), then the index goes
-- with its table. nkap_forbid_mutation() itself is V1's and stays. Then V5's history row is
-- removed so the database is re-migratable.

DROP TRIGGER IF EXISTS statement_finding_append_only ON statement_finding;
DROP TRIGGER IF EXISTS statement_line_append_only ON statement_line;
DROP TRIGGER IF EXISTS statement_import_append_only ON statement_import;

DROP TABLE IF EXISTS statement_finding;
DROP TABLE IF EXISTS statement_line;
DROP TABLE IF EXISTS statement_import;

DELETE FROM flyway_schema_history WHERE version = '5';

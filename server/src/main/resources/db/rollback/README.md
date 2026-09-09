# db/rollback

The hand-written inverse of every Flyway migration in `../migration`, one file per
migration, **same file name**.

Flyway's automatic undo (`flyway undo`) is a paid feature, so this project does not rely on
it. "Reversible", on the roadmap, means: for every `V<n>__name.sql` in `db/migration` there
is a `V<n>__name.sql` here that returns the schema to the state before that migration ran,
and a test proves it.

The test is `MigrationRollbackIT`: against a throwaway PostgreSQL it migrates a fresh
database, runs the matching inverse, and asserts the schema — tables, functions, triggers —
is back to empty. A rollback nobody has ever run is not a rollback.

An inverse script also removes its migration's row from `flyway_schema_history`, so the
database can be migrated forward again afterwards.

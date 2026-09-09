package dev.nkap.server.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * "Reversible" means a hand-written, tested inverse — Flyway's own undo is a paid feature.
 * Each test here migrates a throwaway database, runs the matching inverse(s) in
 * {@code db/rollback}, and checks the schema went back to exactly where it was. A rollback
 * nobody has ever run is not a rollback.
 *
 * <p>Its own container, because it needs a database it can migrate and un-migrate without
 * disturbing the one every other {@code *IT} shares.
 */
@ExtendWith(DockerAvailable.class)
class MigrationRollbackIT {

    /** The migrations that have a hand-written inverse, oldest first. */
    private static final List<String> MIGRATIONS = List.of("V1__initial_schema", "V2__reconciler_schedule");

    @Test
    @DisplayName("running every inverse newest-first returns the schema to empty, and the database is migratable again")
    void every_inverse_returns_the_schema_to_empty() throws IOException {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresDatabase.IMAGE)) {
            postgres.start();
            JdbcTemplate jdbc = jdbcFor(postgres);

            Set<String> beforeMigration = schemaObjects(jdbc);
            assertThat(beforeMigration).isEmpty();

            migrate(postgres);
            assertThat(userTables(jdbc)).contains(
                    "idempotency_record", "payment", "payment_transition", "ledger_entry", "posting");

            for (String migration : MIGRATIONS.reversed()) {
                jdbc.execute(inverseOf(migration));
            }

            assertThat(schemaObjects(jdbc)).isEqualTo(beforeMigration);

            // And forward again — each inverse removed its row from flyway_schema_history.
            migrate(postgres);
            assertThat(userTables(jdbc)).contains("payment");
        }
    }

    @Test
    @DisplayName("the inverse of V2 removes exactly the reconciler columns and index, leaving the V1 schema intact")
    void the_inverse_of_v2_returns_the_schema_to_v1() throws IOException {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresDatabase.IMAGE)) {
            postgres.start();
            JdbcTemplate jdbc = jdbcFor(postgres);

            migrateTo(postgres, "1");
            Set<String> afterV1 = schemaObjects(jdbc);
            Set<String> paymentColumnsAfterV1 = columnsOf(jdbc, "payment");

            migrate(postgres);
            assertThat(columnsOf(jdbc, "payment"))
                    .contains("reconcile_attempts", "reconcile_due_at", "escalated_at");
            assertThat(indexesOf(jdbc, "payment")).contains("payment_reconcile_due_idx");

            jdbc.execute(inverseOf("V2__reconciler_schedule"));

            assertThat(schemaObjects(jdbc)).isEqualTo(afterV1);
            assertThat(columnsOf(jdbc, "payment")).isEqualTo(paymentColumnsAfterV1);
            assertThat(indexesOf(jdbc, "payment")).doesNotContain("payment_reconcile_due_idx");

            // V2 forward again — the inverse removed its history row.
            migrate(postgres);
            assertThat(columnsOf(jdbc, "payment")).contains("reconcile_due_at");
        }
    }

    private static JdbcTemplate jdbcFor(PostgreSQLContainer<?> postgres) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        return new JdbcTemplate(dataSource);
    }

    private static void migrate(PostgreSQLContainer<?> postgres) {
        flyway(postgres).migrate();
    }

    private static void migrateTo(PostgreSQLContainer<?> postgres, String target) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    private static String inverseOf(String migration) throws IOException {
        String path = "/db/rollback/" + migration + ".sql";
        try (InputStream in = MigrationRollbackIT.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " is missing from the classpath");
            }
            return new String(in.readAllBytes(), UTF_8);
        }
    }

    /** Tables (except Flyway's own), functions and triggers in the public schema. */
    private static Set<String> schemaObjects(JdbcTemplate jdbc) {
        Set<String> objects = new TreeSet<>(userTables(jdbc));
        objects.addAll(jdbc.queryForList(
                "SELECT routine_name FROM information_schema.routines WHERE routine_schema = 'public'", String.class));
        objects.addAll(jdbc.queryForList(
                "SELECT DISTINCT trigger_name FROM information_schema.triggers WHERE trigger_schema = 'public'",
                String.class));
        return objects;
    }

    private static Set<String> userTables(JdbcTemplate jdbc) {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                        + "AND table_name <> 'flyway_schema_history'",
                String.class));
    }

    private static Set<String> columnsOf(JdbcTemplate jdbc, String table) {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                String.class, table));
    }

    private static Set<String> indexesOf(JdbcTemplate jdbc, String table) {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?",
                String.class, table));
    }
}

package dev.nkap.server.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import java.io.IOException;
import java.io.InputStream;
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
 * This migrates a throwaway database, runs the matching inverse in {@code db/rollback}, and
 * checks the schema is back to empty. A rollback nobody has ever run is not a rollback.
 *
 * <p>Its own container, because it needs a database it can migrate and un-migrate without
 * disturbing the one every other {@code *IT} shares.
 */
@ExtendWith(DockerAvailable.class)
class MigrationRollbackIT {

    @Test
    @DisplayName("the inverse of V1 returns the schema to empty, and the database is migratable again afterwards")
    void the_inverse_of_v1_returns_the_schema_to_empty() throws IOException {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresDatabase.IMAGE)) {
            postgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            dataSource.setDriverClassName("org.postgresql.Driver");
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            Set<String> beforeMigration = schemaObjects(jdbc);
            assertThat(beforeMigration).isEmpty();

            migrate(postgres);
            assertThat(userTables(jdbc)).contains(
                    "idempotency_record", "payment", "payment_transition", "ledger_entry", "posting");

            jdbc.execute(inverseScript());

            assertThat(schemaObjects(jdbc)).isEqualTo(beforeMigration);

            // And forward again — the inverse removed V1's row from flyway_schema_history.
            migrate(postgres);
            assertThat(userTables(jdbc)).contains("payment");
        }
    }

    private static void migrate(PostgreSQLContainer<?> postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static String inverseScript() throws IOException {
        try (InputStream in = MigrationRollbackIT.class.getResourceAsStream("/db/rollback/V1__initial_schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("db/rollback/V1__initial_schema.sql is missing from the classpath");
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
}

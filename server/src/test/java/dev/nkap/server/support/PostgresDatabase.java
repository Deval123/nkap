package dev.nkap.server.support;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One PostgreSQL container for the whole {@code server} test run, migrated to the current
 * schema. Started on first use and left running until the JVM exits — a container per test
 * class would dominate the build time.
 *
 * <p>Only reached from tests already gated on {@link DockerAvailable}, so the container is
 * always startable here. {@code MigrationRollbackIT} does not use this — it needs a
 * database it can migrate and un-migrate in isolation.
 */
public final class PostgresDatabase {

    public static final String IMAGE = "postgres:16-alpine";

    private static volatile PostgresDatabase instance;

    private final PostgreSQLContainer<?> container;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    private PostgresDatabase() {
        this.container = new PostgreSQLContainer<>(IMAGE);
        this.container.start();

        DriverManagerDataSource ds = new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        this.dataSource = ds;
        this.jdbcTemplate = new JdbcTemplate(ds);
        this.transactionManager = new DataSourceTransactionManager(ds);

        Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    public static PostgresDatabase shared() {
        PostgresDatabase local = instance;
        if (local == null) {
            synchronized (PostgresDatabase.class) {
                local = instance;
                if (local == null) {
                    local = new PostgresDatabase();
                    instance = local;
                }
            }
        }
        return local;
    }

    public String jdbcUrl() {
        return container.getJdbcUrl();
    }

    public String username() {
        return container.getUsername();
    }

    public String password() {
        return container.getPassword();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    public PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    // --- for a test that sweeps a whole table (issue #206) --------------------------------
    //
    // Nothing empties this database between test classes, and a few tests call something
    // that claims every eligible row in a table, not only their own: OutboxRelay.runOnce()
    // and Reconciler.runOnce(). Whatever an earlier class left eligible is swept up with
    // them, so whether such a test passes depends on which classes happened to run first.
    // Each of those tests calls one of these before it starts, so that nothing it did not
    // create itself is claimable. They set rows aside rather than resolving them, and touch
    // nothing a sweep would not have claimed.

    /**
     * Takes every unresolved payment off the reconciler's schedule, so that
     * {@code Reconciler.runOnce()} claims only the payments the calling test creates after
     * this. {@code payment_transition} is append-only and its foreign key keeps the payment
     * row too, so nothing is deleted and no state is changed: {@code reconcile_due_at} is
     * cleared, which the claim query requires to be set.
     *
     * <p>The state list mirrors {@code PostgresReconciliationStore}'s {@code UNRESOLVED_STATES},
     * which the claim query and the partial index in {@code V4__reconciler_chases_unresolved.sql}
     * also depend on. If that list changes, this one must change with it. Forgetting is silent:
     * this stops setting aside payments a sweep can claim, and the tests that sweep a whole
     * table become order-dependent again.
     */
    public void setAsideUnresolvedPayments() {
        jdbcTemplate.update("""
                UPDATE payment SET reconcile_due_at = NULL
                 WHERE state IN ('SUBMITTED', 'PENDING', 'UNKNOWN')
                   AND reconcile_due_at IS NOT NULL""");
    }

    /**
     * Deletes every outbox event still waiting for delivery, so that
     * {@code OutboxRelay.runOnce()} claims only the events the calling test appends after
     * this. Delivered and dead-lettered events are left alone: no sweep claims them, and
     * the tests that read them back assert on what they are. Deleting rather than
     * dead-lettering is what {@code DeadLetteredEventsApiIT} already does with its own
     * fixture: a dead-lettered event is something a test can read back, and this one would
     * not be that test's.
     */
    public void setAsidePendingOutboxEvents() {
        jdbcTemplate.update("DELETE FROM outbox_event WHERE delivered_at IS NULL AND dead_lettered_at IS NULL");
    }
}

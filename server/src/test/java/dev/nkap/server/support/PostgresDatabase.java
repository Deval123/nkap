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
}

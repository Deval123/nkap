package dev.nkap.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The Nkap gateway.
 *
 * <p>A payment is created over HTTP, handed to an operator through an adapter, and carried
 * to {@code SUCCEEDED} by a callback that {@code query()} confirms; settlement posts to a
 * double-entry ledger. The ledger, the payments and their history, and the idempotency
 * store live in <strong>PostgreSQL</strong>, with the invariants as schema constraints —
 * see {@code db/migration}. There is no in-memory mode.
 *
 * <p>The same jar also runs one off-line command: {@code --nkap.statement.import=<path>}
 * reconciles an operator statement and exits (see {@code StatementImportRunner}). Given that
 * argument the context starts with no web server — it is a command, not a request — and the
 * process exit code carries whether the report was clean.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NkapServerApplication {

    private static final String STATEMENT_IMPORT_ARG = "--nkap.statement.import";

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(NkapServerApplication.class);
        if (isStatementImport(args)) {
            app.setWebApplicationType(WebApplicationType.NONE);
            try (ConfigurableApplicationContext context = app.run(args)) {
                System.exit(SpringApplication.exit(context));
            }
        } else {
            app.run(args);
        }
    }

    private static boolean isStatementImport(String[] args) {
        for (String arg : args) {
            if (arg.equals(STATEMENT_IMPORT_ARG) || arg.startsWith(STATEMENT_IMPORT_ARG + "=")) {
                return true;
            }
        }
        return false;
    }
}

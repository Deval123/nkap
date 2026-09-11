package dev.nkap.server;

import java.util.List;
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
 * <p>The same jar also runs off-line commands: {@code --nkap.statement.import=<path>}
 * reconciles an operator statement (see {@code StatementImportRunner}),
 * {@code --nkap.apikey.create} provisions an API key (see {@code ApiKeyProvisioningRunner}),
 * and {@code --nkap.webhook.create} registers a merchant's webhook endpoint (see
 * {@code WebhookEndpointProvisioningRunner}). Given one of those the context starts with no
 * web server — it is a command, not a request — runs, and exits with a code that carries the
 * outcome.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NkapServerApplication {

    /** Arguments that select an off-line command instead of starting the server. */
    private static final List<String> COMMAND_ARGS =
            List.of("--nkap.statement.import", "--nkap.apikey.create", "--nkap.webhook.create");

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(NkapServerApplication.class);
        if (isOfflineCommand(args)) {
            app.setWebApplicationType(WebApplicationType.NONE);
            try (ConfigurableApplicationContext context = app.run(args)) {
                System.exit(SpringApplication.exit(context));
            }
        } else {
            app.run(args);
        }
    }

    private static boolean isOfflineCommand(String[] args) {
        for (String arg : args) {
            for (String command : COMMAND_ARGS) {
                if (arg.equals(command) || arg.startsWith(command + "=")) {
                    return true;
                }
            }
        }
        return false;
    }
}

package dev.nkap.server.webhook;

import java.io.PrintStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Registering a merchant's webhook endpoint is a command, never a route — the same reasoning
 * as {@code ApiKeyProvisioningRunner}, and for the same reason: a route that lets a caller
 * point a signing secret at an arbitrary URL is worse than one that writes ledger entries.
 * Run the application with:
 *
 * <pre>
 *   --nkap.webhook.create --nkap.webhook.merchant=&lt;id&gt; --nkap.webhook.url=&lt;url&gt;
 * </pre>
 *
 * <p>It prints the secret <strong>once</strong>, with a line saying it will not be shown
 * again, and exits. The secret is stored readable — {@link WebhookEndpoint} says why — but
 * there is still no path back to it once this line has scrolled past.
 *
 * <p>{@code --nkap.webhook.secret=&lt;value&gt;} sets the secret explicitly instead of
 * generating one, for the demo and for tests that need a known value. Re-running the command
 * for a merchant that already has an endpoint replaces it — one endpoint per merchant, so
 * "provision" and "rotate" are the same operation.
 */
@Component
class WebhookEndpointProvisioningRunner implements ApplicationRunner, ExitCodeGenerator {

    static final String CREATE_OPTION = "nkap.webhook.create";
    static final String MERCHANT_OPTION = "nkap.webhook.merchant";
    static final String URL_OPTION = "nkap.webhook.url";
    static final String SECRET_OPTION = "nkap.webhook.secret";

    private static final Logger log = LoggerFactory.getLogger(WebhookEndpointProvisioningRunner.class);

    private final WebhookEndpointStore endpoints;
    private final PrintStream out;
    private volatile int exitCode = 0;

    @Autowired
    WebhookEndpointProvisioningRunner(WebhookEndpointStore endpoints) {
        this(endpoints, System.out);
    }

    WebhookEndpointProvisioningRunner(WebhookEndpointStore endpoints, PrintStream out) {
        this.endpoints = endpoints;
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(CREATE_OPTION)) {
            return;
        }
        this.exitCode = execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    int execute(ApplicationArguments args) {
        String merchant = optional(args, MERCHANT_OPTION);
        String url = optional(args, URL_OPTION);
        if (merchant == null || url == null) {
            out.println("--" + CREATE_OPTION + " needs --" + MERCHANT_OPTION + "=<id> --" + URL_OPTION + "=<url>");
            return 2;
        }
        String secret = optional(args, SECRET_OPTION);

        WebhookEndpoint endpoint = secret == null
                ? endpoints.provision(merchant, url)
                : endpoints.provisionWithSecret(secret, merchant, url);

        log.info("registered webhook endpoint for merchant '{}'", merchant);
        out.println("Webhook endpoint for merchant '" + merchant + "' registered.");
        out.println("  url:    " + endpoint.url());
        out.println("  secret: " + endpoint.secret());
        out.println("This is the only time the secret is shown here. Store it now — it signs every event this merchant receives.");
        return 0;
    }

    private static String optional(ApplicationArguments args, String option) {
        List<String> values = args.getOptionValues(option);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0);
        return value == null || value.isBlank() ? null : value.strip();
    }
}

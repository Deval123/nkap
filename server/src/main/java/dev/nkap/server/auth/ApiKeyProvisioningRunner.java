package dev.nkap.server.auth;

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
 * Provisioning an API key is a command, never a route: a route that mints credentials is
 * worse than one that writes ledger entries. Run the application with:
 *
 * <pre>
 *   --nkap.apikey.create --nkap.apikey.merchant=&lt;id&gt; [--nkap.apikey.admin]
 *                        [--nkap.apikey.label=&lt;text&gt;]
 * </pre>
 *
 * <p>It prints the key <strong>once</strong>, with a line saying it will not be shown again,
 * and exits. Only the key's SHA-256 is stored; there is no path — command or route — that
 * reads a key back.
 *
 * <p>{@code --nkap.apikey.token=&lt;value&gt;} sets the key explicitly instead of generating
 * one. That is for the demo, which needs a known credential; the value it uses is obviously
 * a demo one and the compose file says so. Do not use it in a real deployment.
 *
 * <p>Without {@code --nkap.apikey.create} this runner does nothing. The key is never logged,
 * not even a prefix.
 */
@Component
class ApiKeyProvisioningRunner implements ApplicationRunner, ExitCodeGenerator {

    static final String CREATE_OPTION = "nkap.apikey.create";
    static final String MERCHANT_OPTION = "nkap.apikey.merchant";
    static final String ADMIN_OPTION = "nkap.apikey.admin";
    static final String LABEL_OPTION = "nkap.apikey.label";
    static final String TOKEN_OPTION = "nkap.apikey.token";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyProvisioningRunner.class);

    private final ApiKeyStore keys;
    private final PrintStream out;
    private volatile int exitCode = 0;

    @Autowired
    ApiKeyProvisioningRunner(ApiKeyStore keys) {
        this(keys, System.out);
    }

    ApiKeyProvisioningRunner(ApiKeyStore keys, PrintStream out) {
        this.keys = keys;
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
        if (merchant == null) {
            out.println("--" + CREATE_OPTION + " needs --" + MERCHANT_OPTION + "=<id>");
            return 2;
        }
        boolean admin = args.containsOption(ADMIN_OPTION);
        String label = optionalOrEmpty(args, LABEL_OPTION);
        String token = optional(args, TOKEN_OPTION);

        ApiKeyStore.Provisioned provisioned = token == null
                ? keys.provision(merchant, admin, label)
                : keys.provisionWithToken(token, merchant, admin, label);

        log.info("provisioned api key {} for merchant '{}'{}", provisioned.credential().keyId(), merchant,
                admin ? " (admin)" : "");
        out.println("API key for merchant '" + merchant + "'" + (admin ? " (admin)" : "") + " created.");
        out.println("  key id: " + provisioned.credential().keyId());
        out.println("  key:    " + provisioned.token());
        out.println("This is the only time the key is shown. Store it now; it cannot be recovered.");
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

    private static String optionalOrEmpty(ApplicationArguments args, String option) {
        String value = optional(args, option);
        return value == null ? "" : value;
    }
}

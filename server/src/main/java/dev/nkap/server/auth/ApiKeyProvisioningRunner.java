package dev.nkap.server.auth;

import java.io.PrintStream;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Provisioning an API key is a command, never a route: a route that mints credentials is
 * worse than one that writes ledger entries. Revoking one is the same argument and then
 * some — a revocation route reachable with a stolen key would let its holder revoke
 * everyone else's. Run the application with:
 *
 * <pre>
 *   --nkap.apikey.create --nkap.apikey.merchant=&lt;id&gt; [--nkap.apikey.admin]
 *                        [--nkap.apikey.label=&lt;text&gt;]
 *
 *   --nkap.apikey.revoke --nkap.apikey.id=&lt;key id&gt;
 * </pre>
 *
 * <p>{@code create} prints the key <strong>once</strong>, with a line saying it will not be
 * shown again, and exits. Only the key's SHA-256 is stored; there is no path — command or
 * route — that reads a key back.
 *
 * <p>{@code --nkap.apikey.token=&lt;value&gt;} sets the key explicitly instead of generating
 * one. That is for the demo, which needs a known credential; the value it uses is obviously
 * a demo one and the compose file says so. Do not use it in a real deployment.
 *
 * <p>{@code revoke} marks the key by id — the id {@code create} already printed — rather
 * than deleting its row, so the merchant, the label and {@code last_used_at} survive for
 * whoever asks later why a caller stopped working (issue #112). It takes effect on the very
 * next authenticated request: there is no cache in front of {@link ApiKeyStore#authenticate}
 * to invalidate. Revoking a merchant's only key locks that merchant out of the API until
 * another is provisioned — correct, and not guarded against, but worth knowing before you do
 * it at 3 a.m.
 *
 * <p>Without {@code --nkap.apikey.create} or {@code --nkap.apikey.revoke} this runner does
 * nothing. Given both at once, neither runs: an operator asking to create and revoke in the
 * same invocation gets a rejection naming both options and a non-zero exit, never one option
 * winning silently while the other is dropped. A key is never logged, not even a prefix.
 */
@Component
class ApiKeyProvisioningRunner implements ApplicationRunner, ExitCodeGenerator {

    static final String CREATE_OPTION = "nkap.apikey.create";
    static final String MERCHANT_OPTION = "nkap.apikey.merchant";
    static final String ADMIN_OPTION = "nkap.apikey.admin";
    static final String LABEL_OPTION = "nkap.apikey.label";
    static final String TOKEN_OPTION = "nkap.apikey.token";
    static final String REVOKE_OPTION = "nkap.apikey.revoke";
    static final String ID_OPTION = "nkap.apikey.id";

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
        boolean create = args.containsOption(CREATE_OPTION);
        boolean revoke = args.containsOption(REVOKE_OPTION);
        if (create && revoke) {
            // Neither wins silently: a security command whose purpose is the 3 a.m. case
            // must not let an operator believe a key was revoked when it was actually
            // (re)created, or the reverse. Reject the combination outright.
            out.println("--" + CREATE_OPTION + " and --" + REVOKE_OPTION
                    + " cannot be combined -- run one at a time.");
            this.exitCode = 2;
        } else if (create) {
            this.exitCode = executeCreate(args);
        } else if (revoke) {
            this.exitCode = executeRevoke(args);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    int executeCreate(ApplicationArguments args) {
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

    int executeRevoke(ApplicationArguments args) {
        String idText = optional(args, ID_OPTION);
        if (idText == null) {
            out.println("--" + REVOKE_OPTION + " needs --" + ID_OPTION + "=<key id>");
            return 2;
        }
        UUID id;
        try {
            id = UUID.fromString(idText);
        } catch (IllegalArgumentException notAUuid) {
            out.println("--" + ID_OPTION + "=" + idText + " is not a key id (expected a UUID)");
            return 2;
        }

        boolean revoked = keys.revoke(id);
        if (!revoked) {
            out.println("No active API key with id " + id + " — already revoked, or no such key.");
            return 1;
        }

        log.info("revoked api key {}", id);
        out.println("API key " + id + " revoked. It stops authenticating immediately.");
        out.println("If this was a merchant's only key, that merchant has none until you provision another.");
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

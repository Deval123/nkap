package dev.nkap.server.provider;

import dev.nkap.core.money.Currency;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One MTN installation, bound from {@code nkap.provider.mtn.*}.
 *
 * <p>The credentials come from the environment
 * ({@code NKAP_PROVIDER_MTN_SUBSCRIPTION_KEY} and the rest), never from a file in this
 * repository. A missing value leaves the field blank, and {@code MtnProfile} refuses to be
 * constructed from a blank — so the context fails to start with a clear message rather
 * than the first payment failing.
 *
 * <p>Disbursements is a separate MTN product with its own subscription key and API
 * user/key — {@code nkap.provider.mtn.disbursement.*}. The base URL, target environment,
 * currency and country are the installation's and are shared. A deployment that never
 * disburses can leave the disbursement block unset; the context still starts, and only a
 * {@code DISBURSE} payment then fails with a clear "not configured" message.
 */
@ConfigurationProperties("nkap.provider.mtn")
public record MtnProperties(
        URI baseUrl,
        String targetEnvironment,
        String subscriptionKey,
        String apiUser,
        String apiKey,
        Currency currency,
        String country,
        @DefaultValue("PT20S") Duration requestTimeout,
        @DefaultValue Disbursement disbursement) {

    /** The Disbursements product's own credentials. Blank when the deployment does not disburse. */
    public record Disbursement(
            @DefaultValue("") String subscriptionKey,
            @DefaultValue("") String apiUser,
            @DefaultValue("") String apiKey) {

        boolean isConfigured() {
            return !subscriptionKey.isBlank() && !apiUser.isBlank() && !apiKey.isBlank();
        }
    }
}

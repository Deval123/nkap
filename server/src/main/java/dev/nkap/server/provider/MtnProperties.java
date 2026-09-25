package dev.nkap.server.provider;

import dev.nkap.core.money.Currency;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every MTN installation this deployment serves, bound from {@code nkap.provider.mtn.*}
 * (issue #82). One deployment, several countries: each entry is a whole installation with
 * its own base URL, target environment, currency and credentials, the same as a single
 * installation was before this issue — there are just several of them now.
 *
 * <p>The credentials come from the environment, never from a file in this repository. A
 * missing value on a slot whose {@code country} is set leaves the field blank, and
 * {@code MtnProfile} refuses to be constructed from a blank — so the context fails to start
 * with a clear message rather than the first payment failing. A slot whose {@code country}
 * is itself blank is not built at all — see {@link Installation#isConfigured()}.
 *
 * <p>Disbursements is a separate MTN product with its own subscription key and API
 * user/key — {@code nkap.provider.mtn.installations[n].disbursement.*}. The base URL,
 * target environment, currency and country are the installation's and are shared. An
 * installation that never disburses can leave the disbursement block unset; the context
 * still starts, and only a {@code DISBURSE} payment to that installation then fails with a
 * clear "not configured" message.
 */
@ConfigurationProperties("nkap.provider.mtn")
public record MtnProperties(List<Installation> installations) {

    /**
     * What an {@link Installation}'s and a {@link Disbursement}'s {@code toString()} print in
     * place of a credential.
     */
    static final String MASKED = "***";

    /**
     * One country installation.
     *
     * <p>{@code country} is deliberately the only thing that names this installation.
     * {@link MtnConfiguration} derives its {@link dev.nkap.provider.ProviderId} as
     * {@code "mtn-" + country} rather than taking a separately configured id: two fields
     * that must always agree — the country and the id it names — are one field with a
     * rule, not two with a chance to disagree.
     *
     * <p>A blank {@code country} means this slot is not in use — {@link #isConfigured()} —
     * the same convention {@link Disbursement#isConfigured()} already uses one level down.
     * A deployment declares as many slots as {@code docs/providers/mtn.md} documents as
     * "possible" and leaves the ones it does not serve with a blank country; only the
     * configured ones become adapters.
     */
    public record Installation(
            URI baseUrl,
            String targetEnvironment,
            String subscriptionKey,
            String apiUser,
            String apiKey,
            Currency currency,
            String country,
            @DefaultValue("PT20S") Duration requestTimeout,
            @DefaultValue Disbursement disbursement) {

        boolean isConfigured() {
            return !country.isBlank();
        }

        /**
         * Every component, with the Collections credentials replaced by a constant marker,
         * present or not, and the {@link Disbursement} printed by its own {@code toString()},
         * which masks its credentials the same way. The generated {@code toString()} would print
         * them all to any log line, error message or failing AssertJ assertion that printed this
         * installation.
         */
        @Override
        public String toString() {
            return "Installation[baseUrl=" + baseUrl
                    + ", targetEnvironment=" + targetEnvironment
                    + ", subscriptionKey=" + MASKED
                    + ", apiUser=" + MASKED
                    + ", apiKey=" + MASKED
                    + ", currency=" + currency
                    + ", country=" + country
                    + ", requestTimeout=" + requestTimeout
                    + ", disbursement=" + disbursement + "]";
        }
    }

    /** The Disbursements product's own credentials. Blank when the installation does not disburse. */
    public record Disbursement(
            @DefaultValue("") String subscriptionKey,
            @DefaultValue("") String apiUser,
            @DefaultValue("") String apiKey) {

        boolean isConfigured() {
            return !subscriptionKey.isBlank() && !apiUser.isBlank() && !apiKey.isBlank();
        }

        /**
         * A constant marker for each credential, present or not: every component here is one.
         * Whether disbursement is configured is deliberately not printed either; it would say
         * whether each value is blank.
         */
        @Override
        public String toString() {
            return "Disbursement[subscriptionKey=" + MASKED + ", apiUser=" + MASKED + ", apiKey=" + MASKED + "]";
        }
    }
}

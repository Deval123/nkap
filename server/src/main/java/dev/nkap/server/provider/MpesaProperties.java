package dev.nkap.server.provider;

import dev.nkap.core.money.Currency;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every M-Pesa installation this deployment serves, bound from {@code nkap.provider.mpesa.*}
 * (issue #215). The same shape as {@link MtnProperties} — a list of installations, each
 * registered as {@code mpesa-<country>}, credentials from the environment, a slot with a blank
 * {@code country} skipped — with no product sub-block, because the adapter collects and does
 * nothing else.
 *
 * <p>The list shape is kept even though {@code application.yml} declares one slot: the binding
 * is not where the one-slot decision lives, and a second slot, once one is earned, should cost
 * a YAML entry rather than a type change. Why there is one slot is written beside it, in
 * {@code application.yml}.
 *
 * <p>Nothing here is validated by the binding itself. Every credential is a plain
 * {@code String}, which cannot fail to bind, so Spring's binding failures — which quote the
 * value they failed to convert — can only ever quote {@code base-url}, {@code currency} or
 * {@code request-timeout}, none of them secret. The checks that a configured slot is complete
 * are {@link MpesaConfiguration}'s, and they name properties, never values. What the binding
 * cannot quote, a {@code toString()} could still print: an {@link Installation} that reaches a
 * log line, an error message or a failing assertion as a whole object. So it masks its
 * credentials too.
 */
@ConfigurationProperties("nkap.provider.mpesa")
public record MpesaProperties(@DefaultValue List<Installation> installations) {

    /** What an {@link Installation}'s {@code toString()} prints in place of a credential. */
    static final String MASKED = "***";

    /**
     * One country installation. {@code country} is the only thing that names it, for the same
     * reason as {@link MtnProperties.Installation}: the {@link dev.nkap.provider.ProviderId} is
     * derived from it, not configured beside it.
     */
    public record Installation(
            URI baseUrl,
            String businessShortCode,
            String passkey,
            String consumerKey,
            String consumerSecret,
            Currency currency,
            String country,
            @DefaultValue("PT20S") Duration requestTimeout) {

        boolean isConfigured() {
            return country != null && !country.isBlank();
        }

        /**
         * Every component, with the passkey, the Consumer Key and the Consumer Secret replaced
         * by a constant marker, present or not. The generated {@code toString()} would print
         * all three to any log line, error message or failing AssertJ assertion that printed
         * this installation. A leaked passkey cannot simply be rotated; see
         * {@code docs/security-notes.md} §1.
         */
        @Override
        public String toString() {
            return "Installation[baseUrl=" + baseUrl
                    + ", businessShortCode=" + businessShortCode
                    + ", passkey=" + MASKED
                    + ", consumerKey=" + MASKED
                    + ", consumerSecret=" + MASKED
                    + ", currency=" + currency
                    + ", country=" + country
                    + ", requestTimeout=" + requestTimeout + "]";
        }
    }
}

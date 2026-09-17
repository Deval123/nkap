package dev.nkap.server.provider;

import dev.nkap.provider.ProviderId;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fills the one thing {@code PaymentIntent.providerOptions()} carries today: the URL an
 * operator should call back on. Both MTN adapters read it as
 * {@code intent.providerOptions().get("callbackUrl")} and send it as {@code X-Callback-Url};
 * nothing filled that map before issue #116, so no deployment could ever receive a callback.
 *
 * <p>One deployment-level property, {@code nkap.public-base-url} — not a field on
 * {@code CreatePaymentRequest} (a merchant has no business naming where an operator calls
 * <em>this gateway</em>) and not a URL per MTN installation (the path is Nkap's own
 * contract, {@code docs/openapi.yaml}'s {@code /callbacks/{providerId}}, not a deployer's
 * spelling — and one property composes for every installation and every future provider,
 * where a per-installation URL is the same value copied with a chance to diverge each time).
 * Nkap composes {@code <base>/callbacks/<providerId>} itself.
 *
 * <p>Unset (the default) means {@link #providerOptionsFor} returns an empty map and no
 * header is ever sent — the same convention an unconfigured disbursement product uses
 * elsewhere in this deployment's configuration: a deployment that cannot be reached from the
 * internet should not be pretending to ask for callbacks. Set but not an absolute URL fails
 * the application at startup, the same way {@code MtnProfile} refuses a malformed
 * installation {@code base-url}, rather than failing every payment.
 *
 * <p>Setting this property is an operational commitment, not just a code path: MTN's
 * {@code providerCallbackHost} is an allow-list fixed at API-user creation
 * (docs/providers/mtn.md), and it must name this same host or every submission fails with
 * {@code INVALID_CALLBACK_URL_HOST}.
 */
@Component
public class PublicBaseUrl {

    private final URI base;

    public PublicBaseUrl(@Value("${nkap.public-base-url:}") String raw) {
        if (raw == null || raw.isBlank()) {
            this.base = null;
            return;
        }
        URI uri = URI.create(raw);
        if (uri.getHost() == null || uri.getScheme() == null) {
            throw new IllegalArgumentException(
                    "nkap.public-base-url must be an absolute URL, was '" + raw + "'");
        }
        this.base = uri;
    }

    /**
     * {@code {"callbackUrl": "<base>/callbacks/<providerId>"}} for a real submission to
     * {@code providerId}, or an empty map when no public base URL is configured.
     */
    public Map<String, String> providerOptionsFor(ProviderId providerId) {
        if (base == null) {
            return Map.of();
        }
        String stripped = base.toString();
        if (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return Map.of("callbackUrl", stripped + "/callbacks/" + providerId);
    }
}

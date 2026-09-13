package dev.nkap.server.webhook;

import java.util.Locale;

/**
 * Whether a webhook URL is allowed at provisioning. Requires {@code https} by default: this
 * is a signed notification about money, and the signature protects integrity, not
 * confidentiality — a typo giving {@code http://} would make the amount, the reference and
 * the merchant readable to anything on the path. {@code allowInsecureUrl} exists only for a
 * demo or a test with no TLS in front of it; a real deployment never sets it.
 *
 * <p>Deliberately does not refuse loopback or link-local addresses (a URL pointing at
 * {@code 169.254.169.254}, {@code localhost}, or a management port): the exposure from such
 * a request is real but bounded — provisioning is already a host-side command, so whoever
 * calls it is already trusted, and the response body goes nowhere. Refusing by address shape
 * would also refuse legitimate local and container-network endpoints used in exactly the
 * demo and test setups {@code allowInsecureUrl} exists for. The scheme check stands alone.
 */
final class WebhookUrlPolicy {

    private WebhookUrlPolicy() {
    }

    static void requireAllowed(String url, boolean allowInsecureUrl) {
        if (allowInsecureUrl || url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return;
        }
        throw new IllegalArgumentException(
                "webhook url must use https, was '" + url + "'. This is a signed notification about "
                        + "money: http leaves the amount, reference and merchant readable to anything on "
                        + "the path. Set nkap.webhooks.allow-insecure-endpoint-url=true only for a local "
                        + "demo or test with no TLS in front of it.");
    }
}

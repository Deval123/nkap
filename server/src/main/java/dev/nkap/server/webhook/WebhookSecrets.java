package dev.nkap.server.webhook;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates webhook signing secrets. Unlike {@code ApiKeys}, there is no {@code hash} here:
 * the secret is stored as generated (see {@link WebhookEndpoint}), so there is nothing to
 * hash it into.
 */
final class WebhookSecrets {

    static final String PREFIX = "whsec_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSecrets() {
    }

    /** A fresh secret: {@value #PREFIX} + 32 random bytes, base64url without padding. */
    static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

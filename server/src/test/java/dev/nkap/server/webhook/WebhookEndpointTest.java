package dev.nkap.server.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.testsupport.RecordToString;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WebhookEndpoint}'s {@code toString()}: whoever reads the signing secret can forge a
 * notification to the merchant, so a printed record must not carry it.
 */
class WebhookEndpointTest {

    // --- Endpoint ---------------------------------------------------------------------------

    private static final Map<String, String> ENDPOINT_SECRETS = Map.of(
            "secret", "canary-webhook-secret-a4c9");

    private static WebhookEndpoint endpoint() {
        Map<String, String> s = ENDPOINT_SECRETS;
        return new WebhookEndpoint(UUID.fromString("5b0c1c1e-3f4a-4d2b-9a57-2f1f3c6d7e80"), "merchant-1",
                "https://merchant.example/hooks", s.get("secret"), Instant.parse("2026-09-25T12:00:00Z"));
    }

    @Test
    @DisplayName("Endpoint.toString() prints none of its credentials: whoever reads the secret can forge a notification to the merchant")
    void endpoint_prints_no_credential() {
        String text = endpoint().toString();
        ENDPOINT_SECRETS.forEach((field, secret) -> assertThat(text).as(field).doesNotContain(secret));
    }

    @Test
    @DisplayName("Endpoint.toString() still prints the merchant and the URL")
    void endpoint_still_prints_what_is_not_secret() {
        assertThat(endpoint().toString()).contains("merchantId=merchant-1")
                .contains("url=https://merchant.example/hooks");
    }

    @Test
    @DisplayName("every Endpoint component is accounted for in toString(): printed in clear or masked by a constant")
    void every_endpoint_component_is_printed_or_masked() {
        RecordToString.assertEveryComponentPrintedOrMasked(endpoint(), Set.of("id", "merchantId", "url", "createdAt"),
                ENDPOINT_SECRETS.keySet(), WebhookEndpoint.MASKED);
    }
}

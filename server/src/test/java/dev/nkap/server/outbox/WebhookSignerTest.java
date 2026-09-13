package dev.nkap.server.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test 3 of issue #77's plan: a signature verifies with the merchant's own secret, fails with
 * another, and a request replayed later than the tolerance fails on its timestamp — even
 * with the right secret. {@link WebhookSigner#sign} and {@link WebhookSigner#verify} are
 * tested as a pair, the way a merchant's own implementation has to agree with this gateway's.
 */
class WebhookSignerTest {

    private static final String SECRET = "whsec_correct-secret";
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"payment.succeeded\"}";
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @Test
    @DisplayName("a signature verifies with the secret it was signed with")
    void a_signature_verifies_with_its_own_secret() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String header = WebhookSigner.sign(SECRET, now, BODY);

        assertThat(WebhookSigner.verify(SECRET, header, BODY, now, TOLERANCE)).isTrue();
    }

    @Test
    @DisplayName("a signature fails to verify with a different secret")
    void a_signature_fails_with_the_wrong_secret() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String header = WebhookSigner.sign(SECRET, now, BODY);

        assertThat(WebhookSigner.verify("whsec_a-different-secret", header, BODY, now, TOLERANCE)).isFalse();
    }

    @Test
    @DisplayName("a signature fails to verify if the body was tampered with")
    void a_signature_fails_if_the_body_changed() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String header = WebhookSigner.sign(SECRET, now, BODY);

        assertThat(WebhookSigner.verify(SECRET, header, BODY + "x", now, TOLERANCE)).isFalse();
    }

    @Test
    @DisplayName("a request replayed later than the tolerance fails on its timestamp, even with the right secret")
    void a_replayed_request_fails_on_its_timestamp() {
        Instant signedAt = Instant.parse("2026-01-01T00:00:00Z");
        String header = WebhookSigner.sign(SECRET, signedAt, BODY);

        Instant anHourLater = signedAt.plus(Duration.ofHours(1));
        assertThat(WebhookSigner.verify(SECRET, header, BODY, anHourLater, TOLERANCE)).isFalse();
    }

    @Test
    @DisplayName("a request verified just within the tolerance still passes")
    void a_request_within_tolerance_passes() {
        Instant signedAt = Instant.parse("2026-01-01T00:00:00Z");
        String header = WebhookSigner.sign(SECRET, signedAt, BODY);

        Instant justInside = signedAt.plus(Duration.ofMinutes(4)).plusSeconds(59);
        assertThat(WebhookSigner.verify(SECRET, header, BODY, justInside, TOLERANCE)).isTrue();
    }

    @Test
    @DisplayName("a malformed header never verifies")
    void a_malformed_header_never_verifies() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(WebhookSigner.verify(SECRET, "not-a-signature", BODY, now, TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, null, BODY, now, TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, "t=notanumber,v1=abcd", BODY, now, TOLERANCE)).isFalse();
    }
}

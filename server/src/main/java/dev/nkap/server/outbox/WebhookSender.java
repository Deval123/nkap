package dev.nkap.server.outbox;

import dev.nkap.server.webhook.WebhookEndpoint;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Delivers one outbox event over HTTP, signed. Plain {@link HttpClient} — the JDK's own,
 * like the MTN adapters — with a bounded {@code requestTimeout}: this runs outside any
 * database transaction ({@link OutboxRelay}), so a slow receiver costs a delayed retry, never
 * a held connection.
 *
 * <p>Never logs, and never puts into the returned {@link DeliveryResult}, anything but the
 * endpoint's URL and an HTTP status or exception message — the secret used to sign the
 * request is not in either.
 */
public final class WebhookSender {

    private final HttpClient http;
    private final Duration requestTimeout;
    private final Clock clock;

    public WebhookSender(Duration requestTimeout, Clock clock) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), requestTimeout, clock);
    }

    WebhookSender(HttpClient http, Duration requestTimeout, Clock clock) {
        this.http = http;
        this.requestTimeout = requestTimeout;
        this.clock = clock;
    }

    /**
     * Sends one event to {@code endpoint}, signed. Takes the event's id, type and payload as
     * plain values rather than {@link OutboxRelayStore.Claim} — a replay ({@code
     * WebhookReplayController}) has exactly these three and no claim, no attempt count, no
     * schedule to advance, and should not need one to call this.
     */
    public DeliveryResult send(WebhookEndpoint endpoint, UUID eventId, String eventType, String payload) {
        String signature = WebhookSigner.sign(endpoint.secret(), clock.instant(), payload);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint.url()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Nkap-Event-Id", eventId.toString())
                    .header("Nkap-Event-Type", eventType)
                    .header(WebhookSigner.HEADER, signature)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
        } catch (IllegalArgumentException badUrl) {
            return DeliveryResult.fail("endpoint URL is not valid: " + badUrl.getMessage());
        }

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            return status >= 200 && status < 300
                    ? DeliveryResult.ok()
                    : DeliveryResult.fail("receiver answered HTTP " + status);
        } catch (IOException failed) {
            return DeliveryResult.fail(failed.getClass().getSimpleName() + ": " + failed.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return DeliveryResult.fail("interrupted while waiting for the receiver");
        }
    }

    public record DeliveryResult(boolean delivered, String error) {

        public DeliveryResult {
            Objects.requireNonNull(error, "error");
        }

        static DeliveryResult ok() {
            return new DeliveryResult(true, "");
        }

        static DeliveryResult fail(String error) {
            return new DeliveryResult(false, error);
        }
    }
}

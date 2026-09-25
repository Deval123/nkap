package dev.nkap.provider.mpesa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.provider.ProviderUnavailableException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The bearer token for one profile, from {@code GET /oauth/v1/generate?grant_type=client_credentials}
 * with HTTP Basic over the Consumer Key and Secret — <strong>observed</strong>, 2026-09-18.
 * Held with its expiry, refreshed on a margin <em>before</em> it expires, and refreshed once by a
 * caller that still sees a {@code 401} despite a live token.
 *
 * <p>{@code expires_in} was observed as {@code 3599}, but whether Safaricom sends it as a JSON
 * number or a string is not recorded, so both are read. Concurrent callers share one in-flight
 * refresh, as {@code provider-mtn}'s token cache does, for the same reason.
 */
final class MpesaTokenCache {

    /** Refresh this long before the token expires. Observed tokens live 3599 seconds. */
    static final Duration DEFAULT_REFRESH_MARGIN = Duration.ofSeconds(30);

    static final String TOKEN_PATH = "/oauth/v1/generate?grant_type=client_credentials";

    private final MpesaProfile profile;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final ObjectMapper json;
    private final Duration refreshMargin;

    private final Object lock = new Object();
    private volatile Token current;
    private CompletableFuture<Token> inFlight;

    MpesaTokenCache(MpesaProfile profile, HttpClient http, Duration requestTimeout, ObjectMapper json,
                    Duration refreshMargin) {
        this.profile = profile;
        this.http = http;
        this.requestTimeout = requestTimeout;
        this.json = json;
        this.refreshMargin = refreshMargin;
    }

    record Token(String value, Instant expiresAt) {

        /** What {@link #toString()} prints in place of the token. */
        static final String MASKED = "***";

        boolean isLive(Instant now, Duration margin) {
            return now.isBefore(expiresAt.minus(margin));
        }

        /**
         * The expiry, and a constant marker in place of the token. Until it expires, the token
         * is enough to call Safaricom as this installation, and the generated {@code toString()}
         * would print it to any log line or failing AssertJ assertion that printed the record.
         */
        @Override
        public String toString() {
            return "Token[value=" + MASKED + ", expiresAt=" + expiresAt + "]";
        }
    }

    /** A live bearer token, refreshing first if the current one is missing or near expiry. */
    String bearer() throws ProviderUnavailableException {
        Token c = current;
        if (c != null && c.isLive(Instant.now(), refreshMargin)) {
            return c.value();
        }
        return refresh().value();
    }

    /** Drop the current token so the next {@link #bearer()} fetches a new one. */
    void invalidate() {
        current = null;
    }

    private Token refresh() throws ProviderUnavailableException {
        CompletableFuture<Token> future;
        synchronized (lock) {
            Token c = current;
            if (c != null && c.isLive(Instant.now(), refreshMargin)) {
                return c;
            }
            if (inFlight == null) {
                inFlight = CompletableFuture.supplyAsync(this::fetch);
                inFlight.whenComplete((token, error) -> {
                    synchronized (lock) {
                        if (token != null) {
                            current = token;
                        }
                        inFlight = null;
                    }
                });
            }
            future = inFlight;
        }
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ProviderUnavailableException pue) {
                throw pue;
            }
            throw new ProviderUnavailableException("M-Pesa token refresh failed: " + cause.getMessage(), cause);
        }
    }

    private Token fetch() {
        String credentials = profile.consumerKey() + ":" + profile.consumerSecret();
        HttpRequest request = HttpRequest.newBuilder(profile.endpoint(TOKEN_PATH))
                .timeout(requestTimeout)
                .header("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw unavailable("token call did not complete: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("interrupted during token call", e);
        }

        if (response.statusCode() != 200) {
            throw unavailable("token call returned HTTP " + response.statusCode(), null);
        }
        try {
            JsonNode body = json.readTree(response.body());
            String value = body.path("access_token").asText("");
            long expiresIn = body.path("expires_in").asLong(0L);
            if (value.isBlank() || expiresIn <= 0) {
                throw unavailable("token response missing access_token or expires_in", null);
            }
            return new Token(value, Instant.now().plusSeconds(expiresIn));
        } catch (IOException e) {
            throw unavailable("token response was not readable JSON", e);
        }
    }

    private static CompletionException unavailable(String message, Throwable cause) {
        return new CompletionException(cause == null
                ? new ProviderUnavailableException(message)
                : new ProviderUnavailableException(message, cause));
    }
}

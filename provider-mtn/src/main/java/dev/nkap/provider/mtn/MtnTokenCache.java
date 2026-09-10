package dev.nkap.provider.mtn;

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
 * The bearer token for one profile: held with its expiry, refreshed on a margin
 * <em>before</em> it expires rather than after a 401, and refreshed once by a caller that
 * still sees a 401 despite a live token.
 *
 * <p>Concurrent callers share a single in-flight refresh instead of stampeding: the HTTP
 * call runs on the common pool as a {@link CompletableFuture}, and only the bookkeeping —
 * which future is current — is guarded by a lock. The lock is never held across the call.
 */
final class MtnTokenCache {

    /** Refresh this long before the token actually expires. Production MTN tokens live an hour. */
    static final Duration DEFAULT_REFRESH_MARGIN = Duration.ofSeconds(30);

    /** MTN's token endpoint for Collections. Disbursements is a separate product with its own. */
    static final String COLLECTION_TOKEN_PATH = "/collection/token/";

    private final MtnProfile profile;
    private final String tokenPath;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final ObjectMapper json;
    private final Duration refreshMargin;

    private final Object lock = new Object();
    private volatile Token current;
    private CompletableFuture<Token> inFlight;

    MtnTokenCache(MtnProfile profile, HttpClient http, Duration requestTimeout, ObjectMapper json) {
        this(profile, COLLECTION_TOKEN_PATH, http, requestTimeout, json, DEFAULT_REFRESH_MARGIN);
    }

    MtnTokenCache(MtnProfile profile, HttpClient http, Duration requestTimeout, ObjectMapper json, Duration refreshMargin) {
        this(profile, COLLECTION_TOKEN_PATH, http, requestTimeout, json, refreshMargin);
    }

    MtnTokenCache(MtnProfile profile, String tokenPath, HttpClient http, Duration requestTimeout, ObjectMapper json) {
        this(profile, tokenPath, http, requestTimeout, json, DEFAULT_REFRESH_MARGIN);
    }

    MtnTokenCache(MtnProfile profile, String tokenPath, HttpClient http, Duration requestTimeout, ObjectMapper json,
                  Duration refreshMargin) {
        this.profile = profile;
        this.tokenPath = tokenPath;
        this.http = http;
        this.requestTimeout = requestTimeout;
        this.json = json;
        this.refreshMargin = refreshMargin;
    }

    record Token(String value, Instant expiresAt) {
        boolean isLive(Instant now, Duration margin) {
            return now.isBefore(expiresAt.minus(margin));
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
            throw new ProviderUnavailableException("MTN token refresh failed: " + cause.getMessage(), cause);
        }
    }

    /** Runs on the common pool; failures come back as an unchecked wrapper that {@link #refresh()} unwraps. */
    private Token fetch() {
        HttpRequest request = HttpRequest.newBuilder(profile.endpoint(tokenPath))
                .timeout(requestTimeout)
                .header("Authorization", basicAuth(profile.apiUser(), profile.apiKey()))
                .header("Ocp-Apim-Subscription-Key", profile.subscriptionKey())
                // The token call carries no body. MTN answers 411 Length Required — with an
                // HTML page, not JSON — unless an explicit Content-Length: 0 is sent; a client
                // that sends Transfer-Encoding: chunked instead is refused the same way.
                // BodyPublishers.noBody() is what makes the JDK client send Content-Length: 0.
                // MtnTokenCacheTest asserts the wire header, so swapping HTTP clients cannot
                // break this silently. It is the single most time-consuming trap in docs/providers/mtn.md.
                .POST(HttpRequest.BodyPublishers.noBody())
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

    private static String basicAuth(String user, String key) {
        String raw = user + ":" + key;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}

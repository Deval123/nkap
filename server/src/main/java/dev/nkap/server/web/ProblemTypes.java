package dev.nkap.server.web;

import java.net.URI;

/**
 * Stable {@code type} URIs for {@code application/problem+json} responses, one per failure
 * a client might reasonably branch on. The URIs need not resolve; they need to be stable.
 */
public final class ProblemTypes {

    private static final String BASE = "https://nkap.dev/problems/";

    /** The {@code Idempotency-Key} header was absent or blank. */
    public static final URI MISSING_IDEMPOTENCY_KEY = URI.create(BASE + "missing-idempotency-key");

    /** The request body could not be read: not JSON, or a number where an integer was required. */
    public static final URI MALFORMED_REQUEST = URI.create(BASE + "malformed-request");

    /** A field was missing, blank, or not a value the domain accepts. */
    public static final URI INVALID_REQUEST = URI.create(BASE + "invalid-request");

    /** The same {@code Idempotency-Key} was reused with a different body. */
    public static final URI IDEMPOTENCY_KEY_REUSE = URI.create(BASE + "idempotency-key-reuse");

    /** The same {@code Idempotency-Key} is still being processed by an earlier request. */
    public static final URI REQUEST_IN_PROGRESS = URI.create(BASE + "request-in-progress");

    /** No payment exists for the reference in the path. */
    public static final URI PAYMENT_NOT_FOUND = URI.create(BASE + "payment-not-found");

    /** The reference in the path is not a well-formed identifier. */
    public static final URI MALFORMED_REFERENCE = URI.create(BASE + "malformed-reference");

    /** No adapter is configured for the provider the request routes to. */
    public static final URI PROVIDER_NOT_CONFIGURED = URI.create(BASE + "provider-not-configured");

    /** The addressed deployment does not settle the currency the request asked for. */
    public static final URI UNSERVED_CURRENCY = URI.create(BASE + "unserved-currency");

    private ProblemTypes() {
    }
}

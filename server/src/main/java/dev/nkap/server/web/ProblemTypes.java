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

    /**
     * {@code POST /payments}'s {@code country} names an installation this deployment has
     * not configured. Unlike {@link #PROVIDER_NOT_CONFIGURED}, this is a client mistake —
     * the request named the country, not the server's own default — so it is a {@code 400},
     * and the message says which countries are configured.
     */
    public static final URI UNCONFIGURED_COUNTRY = URI.create(BASE + "unconfigured-country");

    /** The path segment of {@code POST /callbacks/{providerId}} names a provider this server has no adapter for. */
    public static final URI UNKNOWN_CALLBACK_PROVIDER = URI.create(BASE + "unknown-callback-provider");

    /** A callback body could not be parsed as the named provider's callback. */
    public static final URI UNPARSEABLE_CALLBACK = URI.create(BASE + "unparseable-callback");

    /** No statement import exists for the id in the path. */
    public static final URI IMPORT_NOT_FOUND = URI.create(BASE + "statement-import-not-found");

    /** No API key was presented, or the one presented is not recognised. Says nothing about which. */
    public static final URI UNAUTHENTICATED = URI.create(BASE + "unauthenticated");

    /** The caller is authenticated but the key is not an admin key, and this resource is operator-wide. */
    public static final URI ADMIN_REQUIRED = URI.create(BASE + "admin-key-required");

    /**
     * A live operator read ({@code GET /balance}, {@code GET /account-holders/{msisdn}})
     * did not answer. Not known, not failed — the same "I do not know" a submission that
     * does not answer carries, given its own status and type instead of a bare 500.
     */
    public static final URI OPERATOR_DID_NOT_ANSWER = URI.create(BASE + "operator-did-not-answer");

    /** No outbox event exists for the id in the path. */
    public static final URI EVENT_NOT_FOUND = URI.create(BASE + "event-not-found");

    /** A replay was asked for an event whose merchant has no registered webhook endpoint. */
    public static final URI NO_WEBHOOK_ENDPOINT = URI.create(BASE + "no-webhook-endpoint");

    /**
     * {@code POST /payments/{reference}/refunds} named a payment that is not a
     * {@code COLLECT}. Sending money back to someone Nkap paid is a new collection, with a
     * different consent story, not a refund.
     */
    public static final URI CANNOT_REFUND_A_DISBURSEMENT = URI.create(BASE + "cannot-refund-a-disbursement");

    /**
     * {@code POST /payments/{reference}/refunds} named a collection that is not
     * {@code SUCCEEDED}. In particular, {@code UNKNOWN} is refused, not just {@code PENDING}
     * or {@code FAILED}: whether the payer's money was ever taken is not known, and sending
     * money back on that guess is a real, unrecoverable loss.
     */
    public static final URI ORIGINAL_NOT_REFUNDABLE = URI.create(BASE + "original-not-refundable");

    /**
     * {@code POST /payments/{reference}/refunds} carried a {@code counterpartyMsisdn}. A
     * refund's destination is always the original collection's payer, never one the caller
     * supplies — see {@code docs/positioning.md}. Refused explicitly rather than ignored, so
     * a caller does not learn the field silently works.
     */
    public static final URI REFUND_DESTINATION_NOT_ALLOWED = URI.create(BASE + "refund-destination-not-allowed");

    /**
     * {@code POST /payments/{reference}/refunds} asked for more than the collection has left
     * to refund. Partial refunds are allowed; their total may never exceed the original.
     */
    public static final URI REFUND_EXCEEDS_REMAINING = URI.create(BASE + "refund-exceeds-remaining");

    private ProblemTypes() {
    }
}

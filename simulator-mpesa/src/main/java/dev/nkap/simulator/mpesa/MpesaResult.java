package dev.nkap.simulator.mpesa;

/**
 * The {@code ResultCode}s this face answers a status query and reports in a callback with.
 * {@code ResultCode} is the contract and {@code ResultDesc} is not: the same code has been
 * observed with different prose on the two channels, and on the query channel across days
 * (see {@code docs/providers/m-pesa.md}, "ResultCode is the contract; ResultDesc is not").
 *
 * <p>Only codes the page records are here.
 */
public enum MpesaResult {

    /**
     * {@code 4999}, "The transaction is still under processing": a payment in flight,
     * answered as a state with {@code HTTP 200}, not as an error. <strong>Observed</strong> on
     * the query, 2026-09-23. Never observed in a callback; a scenario that declares it for
     * one gets the query's prose.
     */
    STILL_PROCESSING(4999, "The transaction is still under processing", "The transaction is still under processing"),

    /**
     * {@code 1037}: the payer never answered the prompt. <strong>Observed</strong> on both
     * channels: the callback says "No response from user." (2026-09-18 and 2026-09-22); the
     * query said the same on 2026-09-18 and "DS timeout user cannot be reached." on
     * 2026-09-22 and 2026-09-23 — this face answers the query with the latter, the most
     * recent.
     */
    NO_RESPONSE_FROM_USER(1037, "DS timeout user cannot be reached.", "No response from user."),

    /**
     * {@code 1032}, "Request Cancelled by user.": the payer dismissed the prompt.
     * <strong>Observed</strong> on the query, 2026-10-02. Never observed in a callback; a
     * scenario that declares it for one gets the query's prose, as {@link #STILL_PROCESSING}
     * does.
     */
    CANCELLED_BY_USER(1032, "Request Cancelled by user.", "Request Cancelled by user."),

    /**
     * {@code 0}: the payment succeeded. Half observed. The code and the <strong>query</strong>
     * description are Safaricom's own, <strong>observed</strong> 2026-10-02
     * ({@code ws_CO_021020261810465181954437}, 17 seconds after submission). The
     * <strong>callback</strong> description is <strong>modelled</strong>: no successful callback
     * has been seen, so it is this simulator's choice, made so that a happy path can be played
     * at all, and nothing downstream should treat it as Safaricom's.
     */
    SUCCESS(0, "The service request is processed successfully.", "The service request is processed successfully.");

    private final int code;
    private final String queryDescription;
    private final String callbackDescription;

    MpesaResult(int code, String queryDescription, String callbackDescription) {
        this.code = code;
        this.queryDescription = queryDescription;
        this.callbackDescription = callbackDescription;
    }

    public int code() {
        return code;
    }

    /** The {@code ResultDesc} a status query answers with. */
    public String queryDescription() {
        return queryDescription;
    }

    /** The {@code ResultDesc} a callback carries. */
    public String callbackDescription() {
        return callbackDescription;
    }
}

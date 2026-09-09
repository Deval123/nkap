package dev.nkap.server.web;

import java.math.BigInteger;

/**
 * The body of {@code POST /payments}.
 *
 * <p>{@code amount} is an integer count of the currency's minor units — {@code 5000} XAF
 * is 5000 francs, {@code 5000} EUR is 50.00. It binds as {@link BigInteger} and Jackson is
 * configured to refuse a fractional number, so "50.5" is a 400 before anything is
 * persisted.
 *
 * <p>Optional text fields are normalised to {@code ""} so that omitting one and sending it
 * empty fingerprint the same — otherwise an idempotent retry that dropped a blank field
 * would look like a different request.
 */
public record CreatePaymentRequest(
        String merchantId,
        String operation,
        BigInteger amount,
        String currency,
        String counterpartyMsisdn,
        String payerMessage,
        String payeeNote) {

    public CreatePaymentRequest {
        payerMessage = payerMessage == null ? "" : payerMessage;
        payeeNote = payeeNote == null ? "" : payeeNote;
    }
}

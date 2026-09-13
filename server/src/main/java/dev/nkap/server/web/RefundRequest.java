package dev.nkap.server.web;

import java.math.BigInteger;

/**
 * The body of {@code POST /payments/{reference}/refunds}.
 *
 * <p>{@code amount} is an integer count of the original collection's minor units, same rule
 * as {@link CreatePaymentRequest#amount()}. Omitted, it defaults to the full amount the
 * collection has left to refund — a bare {@code POST} with no body refunds it in full.
 *
 * <p><strong>{@code counterpartyMsisdn} exists only to be refused.</strong> A refund's
 * destination is always the original collection's payer; there is no field that lets a
 * caller choose one, and this one is not read for anything but the check. It is here, and
 * bindable, specifically so a caller who copies {@link CreatePaymentRequest}'s shape out of
 * habit and includes a destination gets a {@code 400} naming why, instead of the field being
 * silently ignored and teaching them it works (issue #84, {@code docs/positioning.md}).
 */
public record RefundRequest(BigInteger amount, String counterpartyMsisdn) {

    public RefundRequest {
        counterpartyMsisdn = counterpartyMsisdn == null ? "" : counterpartyMsisdn;
    }
}

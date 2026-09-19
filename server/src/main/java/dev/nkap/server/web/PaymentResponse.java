package dev.nkap.server.web;

import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentTransition;
import java.time.Instant;
import java.util.List;

/**
 * What {@code POST /payments}, {@code POST /payments/{reference}/refunds} and
 * {@code GET /payments/{reference}} return — a refund is a payment, polled the same way
 * (issue #84).
 *
 * <p>{@code detail} is empty unless the state is {@code UNKNOWN}, where it tells the caller
 * the outcome is not settled and must be polled.
 *
 * <p>{@code refundOf} is the original collection's reference for a refund, else {@code ""}
 * — how a caller tells "your refund went through" apart from "your disbursement went
 * through" without any other signal.
 *
 * <p>{@code escalatedAt} and {@code unresolvedSince} (issue #113) are what let a caller
 * tell a payment nobody has looked at apart from one this gateway is actively chasing, and
 * both apart from one it has given up chasing automatically and handed to a human —
 * otherwise all three are byte-identical. Neither is a {@link dev.nkap.core.payment.PaymentState}:
 * escalation describes what this gateway did about the operator's silence, not what the
 * operator said, and both are {@code ""} when they do not apply, the same convention as
 * {@code refundOf} and {@code providerTransactionId}. {@code reconcile_attempts} is
 * deliberately not here — it describes this gateway's own backoff policy, not the payment,
 * and changing the reconciler's schedule would change the number for an identical payment.
 */
public record PaymentResponse(
        String reference,
        String provider,
        String merchantId,
        String state,
        String operation,
        long amountMinorUnits,
        String currency,
        String counterpartyMsisdn,
        String providerReference,
        String providerTransactionId,
        Instant createdAt,
        Instant updatedAt,
        String detail,
        String refundOf,
        String escalatedAt,
        String unresolvedSince,
        List<Transition> history) {

    public record Transition(
            String from,
            String to,
            Instant at,
            String cause,
            String operatorCode,
            String note) {
    }

    public static PaymentResponse of(Payment payment) {
        List<Transition> history = payment.history().stream()
                .map(PaymentResponse::toView)
                .toList();
        return new PaymentResponse(
                payment.reference().toString(),
                payment.provider().toString(),
                payment.merchantId(),
                payment.state().name(),
                payment.intent().operation().name(),
                payment.intent().amount().amount(),
                payment.intent().amount().currency().name(),
                payment.intent().counterpartyMsisdn(),
                payment.providerReference(),
                payment.providerTransactionId(),
                payment.createdAt(),
                payment.updatedAt(),
                detailFor(payment),
                payment.refundOf().map(Object::toString).orElse(""),
                orEmpty(payment.escalatedAt()),
                orEmpty(payment.unresolvedSince()),
                history);
    }

    private static Transition toView(PaymentTransition transition) {
        return new Transition(
                transition.from().name(),
                transition.to().name(),
                transition.at(),
                transition.cause().name(),
                transition.operatorCode(),
                transition.note());
    }

    private static String detailFor(Payment payment) {
        if (payment.state().needsResolution()) {
            return "The operator did not answer. The outcome is not yet known — poll "
                    + "GET /payments/" + payment.reference() + " until it resolves.";
        }
        return "";
    }

    private static String orEmpty(Instant instant) {
        return instant == null ? "" : instant.toString();
    }
}

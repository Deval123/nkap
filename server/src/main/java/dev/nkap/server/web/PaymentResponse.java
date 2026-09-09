package dev.nkap.server.web;

import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentTransition;
import java.time.Instant;
import java.util.List;

/**
 * What {@code POST /payments} and {@code GET /payments/{reference}} return.
 *
 * <p>{@code detail} is empty unless the state is {@code UNKNOWN}, where it tells the caller
 * the outcome is not settled and must be polled.
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
}

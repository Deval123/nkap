package dev.nkap.server.management;

import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * {@code GET /actuator/escalatedPayments} — the route issue #113 asks for.
 * {@code nkap_payment_escalated_total} rises, and until this endpoint nothing told an
 * operator which payments that was: {@link PaymentRepository#findEscalated} has existed
 * since the reconciler shipped, and nothing in production ever called it (confirmed by grep
 * before this was written). This is that call.
 *
 * <p>A custom Actuator endpoint, added by name to
 * {@code management.endpoints.web.exposure.include} the same way {@code health} and
 * {@code prometheus} already are — never a wildcard, for the reason {@code application.yml}'s
 * own comment gives: a wildcard here is how a metrics endpoint accidentally becomes an
 * environment-variable leak. Reachable only on the management port, unauthenticated by the
 * same convention as every other endpoint there (an internal scraper is assumed to be the
 * only thing that can reach it), never on the API port — the audience is the operator running
 * the gateway across every merchant, not a merchant holding an API key.
 *
 * <p><strong>Deliberately narrow.</strong> {@code counterpartyMsisdn}, {@code
 * amountMinorUnits}, {@code currency}, {@code payerMessage} and {@code payeeNote} are left
 * out. This is the first row-level, rather than aggregate, data the management port carries —
 * everything else there is counts, gauges and a suspense balance — and adding customer PII or
 * per-payment business volume to an unauthenticated port would contradict the exact reasoning
 * {@code application.yml} already gives for keeping that port off the public network. What is
 * returned answers only which payments need a human, for which merchant, and since when.
 *
 * <p><strong>Bounded</strong>, not because the list is expected to be large but because the
 * moment an operator reads this is the moment escalations are rising — precisely when it
 * would be longest. {@link PaymentRepository#findEscalated} enforces the cap in the query
 * itself, so asking one row past it costs one row, not one avoided {@code load()} for every
 * reference beyond it. {@link EscalatedPaymentsResponse#truncated} says when that happened,
 * so a reader can tell "this is all of them" from "these are the oldest {@value #LIMIT}".
 */
@Component
@Endpoint(id = "escalatedPayments")
public class EscalatedPaymentsEndpoint {

    /**
     * {@code docs/configuration-reference.md}'s own precedent for "a sensible batch cap" —
     * {@code nkap.reconciler.batch-size} and {@code nkap.webhooks.batch-size} both default to
     * the same number, for the same reason: large enough that a real backlog is still useful
     * to read in one request, small enough that reading it is never itself the expensive
     * part. Not a {@code @ConfigurationProperties} field — nothing so far has asked this to
     * be tuned per deployment, and a constant that turns out to be wrong is a one-line change.
     */
    public static final int LIMIT = 100;

    private final PaymentRepository payments;

    EscalatedPaymentsEndpoint(PaymentRepository payments) {
        this.payments = payments;
    }

    @ReadOperation
    public EscalatedPaymentsResponse escalatedPayments() {
        List<Payment> found = payments.findEscalated(LIMIT + 1);
        boolean truncated = found.size() > LIMIT;
        List<Payment> page = truncated ? found.subList(0, LIMIT) : found;
        return new EscalatedPaymentsResponse(page.stream().map(EscalatedPaymentsEndpoint::toItem).toList(), truncated);
    }

    private static EscalatedPaymentsResponse.Item toItem(Payment payment) {
        return new EscalatedPaymentsResponse.Item(
                payment.reference().toString(),
                payment.provider().toString(),
                payment.merchantId(),
                payment.state().name(),
                orEmpty(payment.escalatedAt()),
                orEmpty(payment.unresolvedSince()),
                payment.reconcileAttempts());
    }

    private static String orEmpty(Instant instant) {
        return instant == null ? "" : instant.toString();
    }
}

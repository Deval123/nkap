package dev.nkap.server.statement;

import dev.nkap.core.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * One line of an operator statement, in a provider-neutral shape.
 *
 * <p><strong>Invented, not observed.</strong> Nkap has never seen a real MTN statement — see
 * {@code docs/providers/mtn.md}, <em>Still unknown</em>. This record holds only what a
 * statement line must carry to be reconcilable at all:
 *
 * <ul>
 *   <li>{@code operatorTransactionId} — the operator's own id for the movement, the key
 *       reconciliation matches on. Settlement records it as
 *       {@code Payment.providerTransactionId} from {@code ProviderStatus.transactionId}.</li>
 *   <li>{@code amount} — what the operator says moved, gross, in minor units.</li>
 *   <li>{@code fee} — what the operator kept. {@link Money#zero} when the line carries no
 *       fee, which is not an anomaly. Same currency as {@code amount}.</li>
 *   <li>{@code occurredAt} — when the operator booked it.</li>
 *   <li>{@code status} — whether the line represents money that moved or a failed attempt.
 *       Only {@link Status#SETTLED} lines are reconciled against settled payments.</li>
 * </ul>
 *
 * <p>Nothing here is MTN-specific, and the column layout that produces it (see
 * {@link CsvStatementParser}) is equally ours. When a real statement is finally seen, this
 * is one of the two places that changes.
 */
public record StatementLine(
        String operatorTransactionId,
        Money amount,
        Money fee,
        Instant occurredAt,
        Status status) {

    /** What a statement line represents. Invented alongside the rest of the model. */
    public enum Status {
        /** Money moved. Reconciled against a {@code SUCCEEDED} payment. */
        SETTLED,
        /** The operator lists the attempt but no money moved. Not reconciled against a payment. */
        FAILED
    }

    public StatementLine {
        operatorTransactionId = requireText(operatorTransactionId, "operatorTransactionId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(status, "status");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("statement line " + operatorTransactionId + " has non-positive amount " + amount);
        }
        if (fee.isNegative()) {
            throw new IllegalArgumentException("statement line " + operatorTransactionId + " has negative fee " + fee);
        }
        if (fee.currency() != amount.currency()) {
            throw new IllegalArgumentException(
                    "statement line " + operatorTransactionId + " mixes " + amount.currency() + " amount and " + fee.currency() + " fee");
        }
    }

    /** Whether this line carries a fee to post. A zero fee is not an anomaly, just nothing to write. */
    public boolean hasFee() {
        return fee.isPositive();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

package dev.nkap.server.statement;

import dev.nkap.provider.ProviderId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The reads and writes statement reconciliation needs that are not the ledger.
 *
 * <p>Kept separate from {@code PaymentRepository} on purpose: reconciliation only ever reads
 * a projection of settled payments ({@link SettledPayment}) and never drives one through its
 * state machine, the same way the reconciler has its own {@code ReconciliationStore}.
 *
 * <p>The import, its lines and its findings are <strong>evidence</strong>: written once, read
 * back later. There is no update and no delete.
 */
public interface StatementReconciliationStore {

    /** The settled payment carrying this operator transaction id for this provider, if any. */
    Optional<SettledPayment> findSettledByOperatorTransactionId(ProviderId provider, String operatorTransactionId);

    /** Every settled payment for this provider — the set the "missing from the statement" scan runs over. */
    List<SettledPayment> settledPayments(ProviderId provider);

    /**
     * Records an import and everything it produced, in one transaction: the summary row, the
     * statement lines exactly as parsed, and the report's findings. Returns nothing new — the
     * {@code importId} on the report was chosen by the caller and is the handle to read it
     * back with {@link #findReport(UUID)}.
     */
    void record(ReconciliationReport report, List<StatementLine> lines);

    /** A previously recorded report, rebuilt from its rows. */
    Optional<ReconciliationReport> findReport(UUID importId);

    /** The statement lines of a previously recorded import, in file order. */
    List<StatementLine> findLines(UUID importId);
}

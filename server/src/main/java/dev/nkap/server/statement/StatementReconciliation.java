package dev.nkap.server.statement;

import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.DuplicateLedgerEntryException;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.ledger.Posting;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.provider.ProviderId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Compares an operator statement to what Nkap recorded, and produces a report.
 *
 * <p>Four outcomes per statement line, and <strong>only the first two write to the
 * ledger</strong> (ADR 0006):
 *
 * <ol>
 *   <li><strong>Matched, amounts agree.</strong> If the line carries a fee not yet posted for
 *       this payment, post it: {@code DR fees:<provider>:<CCY>} / {@code CR
 *       provider:<provider>:float:<CCY>}. No fee is nothing to write, and not an anomaly.</li>
 *   <li><strong>Unattributable.</strong> No settled payment holds the line's transaction id:
 *       {@code DR provider:<provider>:float:<CCY>} / {@code CR suspense:<provider>:<CCY>}. The
 *       money is real, the attribution is not, and a human resolves it.</li>
 *   <li><strong>A {@code SUCCEEDED} payment the statement does not mention.</strong> Reported.
 *       Nothing written — an automatic correction here would reverse a settlement on the
 *       strength of a file, the same class of inference as calling a timeout a failure.</li>
 *   <li><strong>Amounts disagree.</strong> Reported, nothing written, same reason.</li>
 * </ol>
 *
 * <p>Re-importing the same file is harmless <em>structurally</em>: each fee and suspense
 * entry's id is derived from the statement line's identity, so a second import is refused by
 * the ledger's own duplicate rule, not by a check here. This class catches that refusal and
 * records it as {@link ReconciliationReport.Finding.Kind#FEE_ALREADY_POSTED} rather than
 * failing the import.
 *
 * <p>Each ledger write is its own transaction (via {@link Ledger#append}); the import summary,
 * its lines and its findings are written together at the end. The report is not wrapped in one
 * transaction with the ledger writes on purpose — a duplicate on the fifth line must not roll
 * back the four entries and the record of the first four.
 *
 * <p>Every log line one {@link #reconcile} call emits carries that run's import id as a
 * structured field ({@code MDC}) — "which import wrote this" is answerable the same way
 * "which reconciler pass wrote this" is (issue #75).
 */
@Service
public class StatementReconciliation {

    private static final Logger log = LoggerFactory.getLogger(StatementReconciliation.class);

    private final Ledger ledger;
    private final StatementReconciliationStore store;

    public StatementReconciliation(Ledger ledger, StatementReconciliationStore store) {
        this.ledger = ledger;
        this.store = store;
    }

    /**
     * Reconciles {@code lines} for {@code provider} against the settled payments, writing the
     * fee and suspense entries the first two outcomes call for and reporting the rest.
     *
     * @param sourceName a label for the file, recorded on the import so the report can be
     *                   tied back to it
     */
    public ReconciliationReport reconcile(ProviderId provider, String sourceName, List<StatementLine> lines) {
        UUID importId = UUID.randomUUID();
        Instant importedAt = Instant.now();
        List<ReconciliationReport.Finding> findings = new ArrayList<>();
        Set<String> transactionIdsSeen = new LinkedHashSet<>();

        // The import id as a structured field on every line this run logs — "which import
        // wrote this" is the question asked right after "what happened to this payment",
        // and it needs its own identifier for the same reason a reconciler pass does
        // (issue #75).
        try (var ignored = MDC.putCloseable("importId", importId.toString())) {
            for (StatementLine line : lines) {
                if (line.status() != StatementLine.Status.SETTLED) {
                    // A failed attempt on the statement moves no money and matches no settled
                    // payment. Nothing to write, nothing to report.
                    continue;
                }
                transactionIdsSeen.add(line.operatorTransactionId());
                Optional<SettledPayment> match =
                        store.findSettledByOperatorTransactionId(provider, line.operatorTransactionId());
                if (match.isEmpty()) {
                    findings.add(postToSuspense(provider, line));
                    continue;
                }
                findings.add(reconcileMatched(provider, line, match.get()));
            }

            for (SettledPayment settled : store.settledPayments(provider)) {
                if (!transactionIdsSeen.contains(settled.operatorTransactionId())) {
                    findings.add(ReconciliationReport.Finding.missingFromStatement(
                            settled.reference(),
                            "recorded SUCCEEDED (" + settled.amount() + ", operator txn " + settled.operatorTransactionId()
                                    + ") but no line in " + sourceName + " mentions it — nothing written"));
                }
            }

            ReconciliationReport report = new ReconciliationReport(
                    importId, provider, sourceName, importedAt, lines.size(), findings);
            store.record(report, lines);
            log.info("statement import {} ({}, {} line(s)): {} fee, {} suspense, {} missing, {} mismatch",
                    importId, sourceName, lines.size(),
                    report.count(ReconciliationReport.Finding.Kind.FEE_POSTED),
                    report.count(ReconciliationReport.Finding.Kind.SUSPENSE_POSTED),
                    report.count(ReconciliationReport.Finding.Kind.MISSING_FROM_STATEMENT),
                    report.count(ReconciliationReport.Finding.Kind.AMOUNT_MISMATCH));
            return report;
        }
    }

    private ReconciliationReport.Finding reconcileMatched(ProviderId provider, StatementLine line, SettledPayment settled) {
        if (!line.amount().equals(settled.amount())) {
            return ReconciliationReport.Finding.amountMismatch(
                    line.operatorTransactionId(), settled.reference(),
                    "statement says " + line.amount() + ", payment recorded " + settled.amount() + " — nothing written");
        }
        if (!line.hasFee()) {
            return ReconciliationReport.Finding.matched(line.operatorTransactionId(), settled.reference());
        }
        Currency currency = line.fee().currency();
        AccountId fees = AccountId.fees(provider.toString(), currency);
        AccountId providerFloat = AccountId.providerFloat(provider.toString(), currency);
        LedgerEntry entry = new LedgerEntry(
                feeEntryId(provider, line.operatorTransactionId()),
                line.occurredAt(),
                settled.reference().toString(),
                "Operator fee from statement: " + line.fee() + " on " + line.operatorTransactionId(),
                List.of(Posting.debit(fees, line.fee()), Posting.credit(providerFloat, line.fee())));
        try {
            ledger.append(entry);
            return ReconciliationReport.Finding.feePosted(
                    line.operatorTransactionId(), settled.reference(),
                    "posted " + line.fee() + ": DR " + fees + " / CR " + providerFloat);
        } catch (DuplicateLedgerEntryException alreadyPosted) {
            return ReconciliationReport.Finding.feeAlreadyPosted(
                    line.operatorTransactionId(), settled.reference(),
                    "fee entry " + entry.id() + " was already recorded — re-import, nothing written");
        }
    }

    private ReconciliationReport.Finding postToSuspense(ProviderId provider, StatementLine line) {
        Currency currency = line.amount().currency();
        AccountId providerFloat = AccountId.providerFloat(provider.toString(), currency);
        AccountId suspense = AccountId.suspense(provider.toString(), currency);
        LedgerEntry entry = new LedgerEntry(
                suspenseEntryId(provider, line.operatorTransactionId()),
                line.occurredAt(),
                "statement:" + line.operatorTransactionId(),
                "Unattributable statement line: " + line.amount() + " on " + line.operatorTransactionId(),
                List.of(Posting.debit(providerFloat, line.amount()), Posting.credit(suspense, line.amount())));
        try {
            ledger.append(entry);
            return ReconciliationReport.Finding.suspensePosted(
                    line.operatorTransactionId(),
                    "no payment holds " + line.operatorTransactionId() + "; posted " + line.amount()
                            + ": DR " + providerFloat + " / CR " + suspense);
        } catch (DuplicateLedgerEntryException alreadyPosted) {
            return ReconciliationReport.Finding.suspenseAlreadyPosted(
                    line.operatorTransactionId(),
                    "suspense entry " + entry.id() + " was already recorded — re-import, nothing written");
        }
    }

    /** Derived from the line's identity so a re-import is refused by the ledger, not by a check here. */
    static String feeEntryId(ProviderId provider, String operatorTransactionId) {
        return "fee:" + provider + ":" + operatorTransactionId;
    }

    static String suspenseEntryId(ProviderId provider, String operatorTransactionId) {
        return "suspense:" + provider + ":" + operatorTransactionId;
    }
}

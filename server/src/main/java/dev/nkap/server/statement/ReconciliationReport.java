package dev.nkap.server.statement;

import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one statement import decided, line by line — read after the fact, not watched.
 *
 * <p>Every import produces one of these and it is persisted whole (see
 * {@link StatementReconciliationStore}), so a discrepancy can always be tied back to the
 * file and the moment that produced it. {@link #findings()} carries one entry per statement
 * line, plus one per settled payment the statement did not mention.
 */
public record ReconciliationReport(
        UUID importId,
        ProviderId provider,
        String sourceName,
        Instant importedAt,
        int lineCount,
        List<Finding> findings) {

    public ReconciliationReport {
        findings = List.copyOf(findings);
    }

    /**
     * One decision. {@link Kind} says which of the four outcomes it was; the two write
     * outcomes ({@link Kind#FEE_POSTED}, {@link Kind#SUSPENSE_POSTED}) name the ledger entry
     * that was written, the rest wrote nothing.
     */
    public record Finding(Kind kind, String operatorTransactionId, ReferenceId paymentReference, String detail) {

        public enum Kind {
            /** Matched, amounts agree, the line carried a fee, and it was posted (DR fees / CR float). */
            FEE_POSTED,
            /** Matched, the line carried a fee, and the ledger refused it as already recorded — a re-import. */
            FEE_ALREADY_POSTED,
            /** Matched, amounts agree, no fee on the line. Nothing to write, and not an anomaly. */
            MATCHED,
            /** No payment holds this transaction id: DR float / CR suspense, and a human resolves it. */
            SUSPENSE_POSTED,
            /** Unattributable, and the suspense entry was already recorded — a re-import, nothing written. */
            SUSPENSE_ALREADY_POSTED,
            /** A SUCCEEDED payment the statement does not mention. Reported; nothing written. */
            MISSING_FROM_STATEMENT,
            /** The line and the payment name different amounts. Reported; nothing written. */
            AMOUNT_MISMATCH;

            /** Whether a finding of this kind is one a human must look at. */
            public boolean isAnomaly() {
                return this == SUSPENSE_POSTED || this == SUSPENSE_ALREADY_POSTED
                        || this == MISSING_FROM_STATEMENT || this == AMOUNT_MISMATCH;
            }
        }

        static Finding feePosted(String txnId, ReferenceId reference, String detail) {
            return new Finding(Kind.FEE_POSTED, txnId, reference, detail);
        }

        static Finding feeAlreadyPosted(String txnId, ReferenceId reference, String detail) {
            return new Finding(Kind.FEE_ALREADY_POSTED, txnId, reference, detail);
        }

        static Finding matched(String txnId, ReferenceId reference) {
            return new Finding(Kind.MATCHED, txnId, reference, "amounts agree, no fee on the line");
        }

        static Finding suspensePosted(String txnId, String detail) {
            return new Finding(Kind.SUSPENSE_POSTED, txnId, null, detail);
        }

        static Finding suspenseAlreadyPosted(String txnId, String detail) {
            return new Finding(Kind.SUSPENSE_ALREADY_POSTED, txnId, null, detail);
        }

        static Finding missingFromStatement(ReferenceId reference, String detail) {
            return new Finding(Kind.MISSING_FROM_STATEMENT, null, reference, detail);
        }

        static Finding amountMismatch(String txnId, ReferenceId reference, String detail) {
            return new Finding(Kind.AMOUNT_MISMATCH, txnId, reference, detail);
        }
    }

    public long count(Finding.Kind kind) {
        return findings.stream().filter(f -> f.kind() == kind).count();
    }

    /** The findings a human must act on: suspense postings, missing settlements, amount mismatches. */
    public List<Finding> anomalies() {
        return findings.stream().filter(f -> f.kind().isAnomaly()).toList();
    }
}

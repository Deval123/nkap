package dev.nkap.server.web;

import dev.nkap.server.adjustment.AdjustmentPosting;
import dev.nkap.server.adjustment.NewAdjustment;
import java.util.List;
import java.util.Map;

/**
 * The body of {@code POST /adjustments}. Everything in it is the proposer's claim, checked by
 * {@code AdjustmentService}. What it cannot say is as much of the design as what it can: no
 * ledger entry id, no timestamp, no currency of its own (it must be the payment's), and no
 * proposing key, which is the caller's.
 *
 * <p>{@code postings} carry signed minor-unit amounts, debit positive and credit negative, as
 * the ledger does. {@code cites} names the keys of {@code evidence} the proposal relies on.
 */
public record AdjustmentRequest(
        String paymentReference,
        String hypothesis,
        List<AdjustmentPosting> postings,
        Map<String, String> evidence,
        List<String> cites,
        String explanation) {

    NewAdjustment toNewAdjustment() {
        return new NewAdjustment(paymentReference, hypothesis, postings, evidence, cites, explanation);
    }
}

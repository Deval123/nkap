package dev.nkap.server.adjustment;

import java.util.List;
import java.util.Map;

/**
 * What a caller may say when proposing. The entry's id, timestamp and the proposing key are
 * not here: the server derives them.
 *
 * <p>{@code cites} names the entries of {@code evidence} the proposal relies on. Each must be
 * present, with a value, or the proposal is refused.
 */
public record NewAdjustment(
        String paymentReference,
        String hypothesis,
        List<AdjustmentPosting> postings,
        Map<String, String> evidence,
        List<String> cites,
        String explanation) {
}

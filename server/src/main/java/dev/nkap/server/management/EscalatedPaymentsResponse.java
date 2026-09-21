package dev.nkap.server.management;

import java.util.List;

/**
 * {@code GET /actuator/escalatedPayments}'s shape. See {@link EscalatedPaymentsEndpoint}'s
 * own javadoc for why {@link Item} carries exactly these eight fields and no others, and why
 * {@link #truncated} exists at all.
 */
public record EscalatedPaymentsResponse(List<Item> payments, boolean truncated) {

    public record Item(
            String reference,
            String provider,
            String merchantId,
            String state,
            String escalatedAt,
            String unresolvedSince,
            int reconcileAttempts,
            String reason) {
    }
}

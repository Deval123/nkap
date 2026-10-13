package dev.nkap.server.web;

/**
 * The optional body of {@code POST /adjustments/{id}/approve} and {@code .../reject}. A bare
 * {@code POST} decides without a reason; a reason is worth giving, since it is what the next
 * reader of the record sees.
 */
public record AdjustmentDecisionRequest(String reason) {

    public AdjustmentDecisionRequest {
        reason = reason == null ? "" : reason;
    }
}

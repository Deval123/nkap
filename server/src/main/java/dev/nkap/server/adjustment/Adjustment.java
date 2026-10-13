package dev.nkap.server.adjustment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A stored proposal, the merchant of the payment it concerns (what scopes a read), and the
 * decision on it if there is one. There is no status field: a proposal with no decision is
 * pending, one with a decision is what that decision says.
 */
public record Adjustment(
        UUID id,
        String paymentReference,
        String merchantId,
        Hypothesis hypothesis,
        List<AdjustmentPosting> postings,
        Map<String, String> evidence,
        List<String> cites,
        String explanation,
        UUID proposedBy,
        Instant proposedAt,
        Optional<Decision> decision) {

    /** The one decision a proposal can have. {@code approved} false covers refusals as well as rejections. */
    public record Decision(UUID id, boolean approved, UUID decidedBy, Instant decidedAt, String reason) {
    }
}

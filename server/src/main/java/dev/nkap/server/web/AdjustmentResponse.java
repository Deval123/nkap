package dev.nkap.server.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.nkap.server.adjustment.Adjustment;
import dev.nkap.server.adjustment.AdjustmentPosting;
import dev.nkap.server.adjustment.AdjustmentService;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An adjustment as the API shows it. {@code status} is derived: {@code pending} until a
 * decision exists, then {@code approved} or {@code rejected}. A proposal refused at approval
 * time is {@code rejected} with the reason it was refused for. {@code ledgerEntryId} is present
 * only once approved, and is the id of the entry the server wrote.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdjustmentResponse(
        String id,
        String status,
        String paymentReference,
        String hypothesis,
        List<AdjustmentPosting> postings,
        Map<String, String> evidence,
        List<String> cites,
        String explanation,
        String proposedBy,
        Instant proposedAt,
        Decision decision,
        String ledgerEntryId) {

    public record Decision(boolean approved, String decidedBy, Instant decidedAt, String reason) {
    }

    static AdjustmentResponse of(Adjustment adjustment) {
        Decision decision = adjustment.decision()
                .map(d -> new Decision(d.approved(), d.decidedBy().toString(), d.decidedAt(), d.reason()))
                .orElse(null);
        String status = adjustment.decision().map(d -> d.approved() ? "approved" : "rejected").orElse("pending");
        boolean approved = adjustment.decision().map(Adjustment.Decision::approved).orElse(false);
        return new AdjustmentResponse(
                adjustment.id().toString(), status, adjustment.paymentReference(), adjustment.hypothesis().code(),
                adjustment.postings(), adjustment.evidence(), adjustment.cites(), adjustment.explanation(),
                adjustment.proposedBy().toString(), adjustment.proposedAt(), decision,
                approved ? AdjustmentService.entryId(adjustment.id()) : null);
    }
}

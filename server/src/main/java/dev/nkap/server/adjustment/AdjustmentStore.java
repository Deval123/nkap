package dev.nkap.server.adjustment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Proposals and decisions. Append-only: there is no update and no delete here, and the tables
 * refuse both (V13).
 */
public interface AdjustmentStore {

    void insertProposal(UUID id, UUID paymentReference, Hypothesis hypothesis,
                        List<AdjustmentPosting> postings, Map<String, String> evidence,
                        List<String> cites, String explanation, UUID proposedBy, Instant proposedAt);

    /**
     * Records the decision. Throws {@link org.springframework.dao.DuplicateKeyException} when the
     * proposal already has one: the unique constraint on the proposal id is the rule, not a check
     * made before this call.
     */
    void insertDecision(UUID id, UUID proposalId, boolean approved, UUID decidedBy, Instant decidedAt,
                        String reason);

    Optional<Adjustment> find(UUID id);

    /** Every proposal, newest first, or only those about payments of one merchant. */
    List<Adjustment> list(Optional<String> merchantId);
}

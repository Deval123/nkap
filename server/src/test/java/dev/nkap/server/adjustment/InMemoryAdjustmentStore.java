package dev.nkap.server.adjustment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

/**
 * The rules {@link AdjustmentService} is written against, without a database: one decision per
 * proposal, and no update. It cannot prove the database refuses what this refuses, which is
 * {@code AdjustmentPersistenceIT}'s job.
 */
final class InMemoryAdjustmentStore implements AdjustmentStore {

    private final Map<UUID, Adjustment> proposals = new LinkedHashMap<>();
    private final Map<UUID, String> merchantByPayment = new LinkedHashMap<>();

    /** Tells the store whose payment a reference is, as the join with {@code payment} does in SQL. */
    void paymentBelongsTo(UUID paymentReference, String merchantId) {
        merchantByPayment.put(paymentReference, merchantId);
    }

    @Override
    public void insertProposal(UUID id, UUID paymentReference, Hypothesis hypothesis,
                               List<AdjustmentPosting> postings, Map<String, String> evidence,
                               List<String> cites, String explanation, UUID proposedBy, Instant proposedAt) {
        proposals.put(id, new Adjustment(id, paymentReference.toString(), merchantByPayment.get(paymentReference),
                hypothesis, postings, evidence, cites, explanation, proposedBy, proposedAt, Optional.empty()));
    }

    @Override
    public void insertDecision(UUID id, UUID proposalId, boolean approved, UUID decidedBy, Instant decidedAt,
                               String reason) {
        Adjustment proposal = proposals.get(proposalId);
        if (proposal.decision().isPresent()) {
            throw new DuplicateKeyException("adjustment_decision_one_per_proposal");
        }
        proposals.put(proposalId, new Adjustment(proposal.id(), proposal.paymentReference(), proposal.merchantId(),
                proposal.hypothesis(), proposal.postings(), proposal.evidence(), proposal.cites(),
                proposal.explanation(), proposal.proposedBy(), proposal.proposedAt(),
                Optional.of(new Adjustment.Decision(id, approved, decidedBy, decidedAt, reason))));
    }

    @Override
    public Optional<Adjustment> find(UUID id) {
        return Optional.ofNullable(proposals.get(id));
    }

    @Override
    public List<Adjustment> list(Optional<String> merchantId) {
        return new ArrayList<>(proposals.values().stream()
                .filter(a -> merchantId.isEmpty() || merchantId.get().equals(a.merchantId()))
                .toList());
    }
}

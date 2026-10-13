package dev.nkap.server.adjustment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plain SQL over {@code adjustment_proposal} and {@code adjustment_decision} (V13). The
 * evidence column holds {@code {"cites": [...], "values": {...}}}: what the proposal relies on
 * and what it carried, together, so a stored proposal can be re-checked from its own row.
 */
public final class PostgresAdjustmentStore implements AdjustmentStore {

    private static final String SELECT = """
            SELECT p.id, p.payment_reference, pay.merchant_id, p.hypothesis, p.postings::text AS postings,
                   p.evidence::text AS evidence, p.explanation, p.key_id, p.created_at,
                   d.id AS decision_id, d.approved, d.key_id AS decision_key_id, d.decided_at, d.reason
              FROM adjustment_proposal p
              JOIN payment pay ON pay.reference = p.payment_reference
              LEFT JOIN adjustment_decision d ON d.proposal_id = p.id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresAdjustmentStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public void insertProposal(UUID id, UUID paymentReference, Hypothesis hypothesis,
                               List<AdjustmentPosting> postings, Map<String, String> evidence,
                               List<String> cites, String explanation, UUID proposedBy, Instant proposedAt) {
        Map<String, Object> evidenceDocument = new LinkedHashMap<>();
        evidenceDocument.put("cites", cites);
        evidenceDocument.put("values", evidence);
        jdbc.update("INSERT INTO adjustment_proposal "
                        + "(id, payment_reference, hypothesis, postings, evidence, explanation, key_id, created_at) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)",
                id, paymentReference, hypothesis.code(), write(postings), write(evidenceDocument), explanation,
                proposedBy, OffsetDateTime.ofInstant(proposedAt, ZoneOffset.UTC));
    }

    @Override
    public void insertDecision(UUID id, UUID proposalId, boolean approved, UUID decidedBy,
                               Instant decidedAt, String reason) {
        jdbc.update("INSERT INTO adjustment_decision (id, proposal_id, approved, key_id, decided_at, reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, proposalId, approved, decidedBy, OffsetDateTime.ofInstant(decidedAt, ZoneOffset.UTC), reason);
    }

    @Override
    public Optional<Adjustment> find(UUID id) {
        return jdbc.query(SELECT + " WHERE p.id = ?", (rs, row) -> read(rs), id).stream().findFirst();
    }

    @Override
    public List<Adjustment> list(Optional<String> merchantId) {
        return merchantId
                .map(merchant -> jdbc.query(SELECT + " WHERE pay.merchant_id = ? ORDER BY p.created_at DESC, p.id",
                        (rs, row) -> read(rs), merchant))
                .orElseGet(() -> jdbc.query(SELECT + " ORDER BY p.created_at DESC, p.id", (rs, row) -> read(rs)));
    }

    private Adjustment read(ResultSet rs) throws SQLException {
        JsonNode evidence = readTree(rs.getString("evidence"));
        List<String> cites = new ArrayList<>();
        evidence.get("cites").forEach(node -> cites.add(node.asText()));
        Map<String, String> values = new LinkedHashMap<>();
        evidence.get("values").fields().forEachRemaining(field -> values.put(field.getKey(), field.getValue().asText()));

        Optional<Adjustment.Decision> decision = Optional.empty();
        UUID decisionId = rs.getObject("decision_id", UUID.class);
        if (decisionId != null) {
            decision = Optional.of(new Adjustment.Decision(
                    decisionId,
                    rs.getBoolean("approved"),
                    rs.getObject("decision_key_id", UUID.class),
                    rs.getObject("decided_at", OffsetDateTime.class).toInstant(),
                    rs.getString("reason")));
        }
        return new Adjustment(
                rs.getObject("id", UUID.class),
                rs.getObject("payment_reference", UUID.class).toString(),
                rs.getString("merchant_id"),
                Hypothesis.fromCode(rs.getString("hypothesis")).orElseThrow(),
                readPostings(rs.getString("postings")),
                values,
                cites,
                rs.getString("explanation"),
                rs.getObject("key_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                decision);
    }

    private List<AdjustmentPosting> readPostings(String text) {
        try {
            return json.readValue(text, new TypeReference<List<AdjustmentPosting>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored proposal's postings could not be read", e);
        }
    }

    private JsonNode readTree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored proposal's evidence could not be read", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A proposal could not be written as JSON", e);
        }
    }
}

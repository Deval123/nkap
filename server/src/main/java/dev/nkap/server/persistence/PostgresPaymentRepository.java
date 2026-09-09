package dev.nkap.server.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentRepository;
import dev.nkap.server.payment.PaymentTransition;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@link PaymentRepository} in PostgreSQL: the payment row, upserted, and its history rows,
 * inserted once each and never touched again (a {@code BEFORE UPDATE OR DELETE} trigger
 * makes {@code payment_transition} append-only).
 *
 * <p>{@code save} is idempotent: the payment row is an {@code ON CONFLICT DO UPDATE}, and
 * every history row is an {@code INSERT … ON CONFLICT (payment_reference, seq) DO NOTHING},
 * so re-saving a payment writes only the transitions that are new.
 *
 * <p>{@code findByReferenceForUpdate} adds {@code FOR UPDATE}: inside the transaction that
 * does a read-decide-write, it holds the payment's row until commit, which is the
 * serialisation the submit and callback paths share.
 */
public final class PostgresPaymentRepository implements PaymentRepository {

    private static final String UPSERT_PAYMENT = """
            INSERT INTO payment (reference, provider, merchant_id, operation, amount_minor, currency,
                                 counterparty_msisdn, payer_message, payee_note, provider_options,
                                 state, provider_reference, provider_transaction_id, created_at, updated_at,
                                 reconcile_attempts, reconcile_due_at, escalated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (reference) DO UPDATE SET
                state = EXCLUDED.state,
                provider_reference = EXCLUDED.provider_reference,
                provider_transaction_id = EXCLUDED.provider_transaction_id,
                updated_at = EXCLUDED.updated_at,
                reconcile_attempts = EXCLUDED.reconcile_attempts,
                reconcile_due_at = EXCLUDED.reconcile_due_at,
                escalated_at = EXCLUDED.escalated_at
            """;

    private static final String INSERT_TRANSITION = """
            INSERT INTO payment_transition (payment_reference, seq, from_state, to_state, occurred_at,
                                            cause, operator_code, note, raw_response)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (payment_reference, seq) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresPaymentRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void save(Payment payment) {
        Objects.requireNonNull(payment, "payment");
        PaymentIntent intent = payment.intent();
        jdbc.update(UPSERT_PAYMENT,
                payment.reference().value(),
                payment.provider().toString(),
                payment.merchantId(),
                intent.operation().name(),
                intent.amount().amount(),
                intent.amount().currency().name(),
                intent.counterpartyMsisdn(),
                intent.payerMessage(),
                intent.payeeNote(),
                writeOptions(intent.providerOptions()),
                payment.state().name(),
                payment.providerReference(),
                payment.providerTransactionId(),
                OffsetDateTime.ofInstant(payment.createdAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(payment.updatedAt(), ZoneOffset.UTC),
                payment.reconcileAttempts(),
                atUtc(payment.reconcileDueAt()),
                atUtc(payment.escalatedAt()));

        List<PaymentTransition> history = payment.history();
        for (int seq = 0; seq < history.size(); seq++) {
            PaymentTransition t = history.get(seq);
            jdbc.update(INSERT_TRANSITION,
                    payment.reference().value(),
                    seq,
                    t.from().name(),
                    t.to().name(),
                    OffsetDateTime.ofInstant(t.at(), ZoneOffset.UTC),
                    t.cause().name(),
                    t.operatorCode(),
                    t.note(),
                    t.rawResponse());
        }
    }

    @Override
    public Optional<Payment> findByReference(ReferenceId reference) {
        return load(reference, false);
    }

    @Override
    public Optional<Payment> findByReferenceForUpdate(ReferenceId reference) {
        return load(reference, true);
    }

    @Override
    public List<Payment> findEscalated() {
        List<UUID> references = jdbc.queryForList(
                "SELECT reference FROM payment WHERE escalated_at IS NOT NULL AND state = 'UNKNOWN' "
                        + "ORDER BY escalated_at",
                UUID.class);
        List<Payment> escalated = new ArrayList<>(references.size());
        for (UUID reference : references) {
            load(new ReferenceId(reference), false).ifPresent(escalated::add);
        }
        return escalated;
    }

    private Optional<Payment> load(ReferenceId reference, boolean forUpdate) {
        Objects.requireNonNull(reference, "reference");
        String sql = "SELECT * FROM payment WHERE reference = ?" + (forUpdate ? " FOR UPDATE" : "");
        List<Row> rows = jdbc.query(sql, ROW_MAPPER, reference.value());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row row = rows.get(0);
        List<PaymentTransition> history = jdbc.query(
                "SELECT from_state, to_state, occurred_at, cause, operator_code, note, raw_response "
                        + "FROM payment_transition WHERE payment_reference = ? ORDER BY seq",
                TRANSITION_MAPPER, reference.value());

        PaymentIntent intent = new PaymentIntent(
                Capability.valueOf(row.operation),
                Money.of(row.amountMinor, Currency.valueOf(row.currency)),
                row.counterpartyMsisdn,
                row.payerMessage,
                row.payeeNote,
                readOptions(row.providerOptions));

        return Optional.of(Payment.rehydrate(
                reference,
                ProviderId.of(row.provider),
                row.merchantId,
                intent,
                PaymentState.valueOf(row.state),
                row.providerReference,
                row.providerTransactionId,
                row.createdAt,
                row.updatedAt,
                history,
                row.reconcileAttempts,
                row.reconcileDueAt,
                row.escalatedAt));
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String writeOptions(Map<String, String> options) {
        try {
            return json.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise provider options", e);
        }
    }

    private Map<String, String> readOptions(String stored) {
        try {
            return json.readValue(stored, new TypeReference<Map<String, String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored provider options are not readable: " + stored, e);
        }
    }

    private record Row(
            String provider, String merchantId, String operation, long amountMinor, String currency,
            String counterpartyMsisdn, String payerMessage, String payeeNote, String providerOptions,
            String state, String providerReference, String providerTransactionId,
            Instant createdAt, Instant updatedAt,
            int reconcileAttempts, Instant reconcileDueAt, Instant escalatedAt) {
    }

    private static final RowMapper<Row> ROW_MAPPER = (ResultSet rs, int rowNum) -> new Row(
            rs.getString("provider"),
            rs.getString("merchant_id"),
            rs.getString("operation"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("counterparty_msisdn"),
            rs.getString("payer_message"),
            rs.getString("payee_note"),
            rs.getString("provider_options"),
            rs.getString("state"),
            rs.getString("provider_reference"),
            rs.getString("provider_transaction_id"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
            rs.getInt("reconcile_attempts"),
            instantOrNull(rs.getObject("reconcile_due_at", OffsetDateTime.class)),
            instantOrNull(rs.getObject("escalated_at", OffsetDateTime.class)));

    private static Instant instantOrNull(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<PaymentTransition> TRANSITION_MAPPER = (ResultSet rs, int rowNum) -> new PaymentTransition(
            PaymentState.valueOf(rs.getString("from_state")),
            PaymentState.valueOf(rs.getString("to_state")),
            rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
            PaymentTransition.Cause.valueOf(rs.getString("cause")),
            rs.getString("operator_code"),
            rs.getString("note"),
            rs.getString("raw_response"));
}

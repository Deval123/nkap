package dev.nkap.server.adjustment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.DuplicateLedgerEntryException;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.auth.ApiCredential;
import dev.nkap.server.auth.PostgresApiKeyStore;
import dev.nkap.server.persistence.PostgresLedger;
import dev.nkap.server.persistence.PostgresPaymentRepository;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import dev.nkap.server.web.ApiException;
import dev.nkap.server.web.ProblemTypes;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The adjustment path against PostgreSQL, because the guarantees ADR 0017 leans on are the
 * database's: the {@code ledger_entry} primary key refuses a second approval, the deferred
 * trigger refuses an unbalanced entry, the unique constraint allows one decision per proposal,
 * and the append-only trigger refuses an edit of either table. An in-memory ledger would only
 * show that the stub agrees with itself.
 */
@ExtendWith(DockerAvailable.class)
class AdjustmentPersistenceIT {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static PostgresPaymentRepository payments;
    private static PostgresAdjustmentStore store;
    private static PostgresLedger postgresLedger;
    private static PostgresApiKeyStore keys;
    private static RecordingLedger ledger;
    private static AdjustmentService service;

    @BeforeAll
    static void connect() {
        PostgresDatabase db = PostgresDatabase.shared();
        dataSource = db.dataSource();
        jdbc = db.jdbcTemplate();
        payments = new PostgresPaymentRepository(jdbc, new ObjectMapper());
        store = new PostgresAdjustmentStore(jdbc, new ObjectMapper());
        postgresLedger = new PostgresLedger(jdbc, db.transactionManager());
        keys = new PostgresApiKeyStore(jdbc);
        ledger = new RecordingLedger(postgresLedger);
        service = new AdjustmentService(new AdjustmentPolicy(true, 100_000), store, payments, ledger,
                db.transactionManager(), Clock.systemUTC());
    }

    /** A merchant, two keys of it, and a payment of its own, so tests never share a ledger account's history by accident. */
    private record Scene(String merchant, ApiCredential first, ApiCredential second, Payment payment) {
        String provider() {
            return "p" + merchant.substring(merchant.length() - 8);
        }
    }

    private static Scene scene() {
        String merchant = "acme-" + UUID.randomUUID().toString().substring(0, 8);
        ApiCredential first = keys.provision(merchant, false, "AdjustmentPersistenceIT").credential();
        ApiCredential second = keys.provision(merchant, false, "AdjustmentPersistenceIT").credential();
        Payment payment = Payment.create(ReferenceId.newReference(), ProviderId.of("mtn-cm"), merchant,
                new PaymentIntent(Capability.Operation.COLLECT, Money.of(5_000, Currency.XAF), "237670000000",
                        "", "", Map.of()));
        payments.save(payment);
        return new Scene(merchant, first, second, payment);
    }

    private static NewAdjustment feeProposal(Scene scene, long amount) {
        return new NewAdjustment(scene.payment().reference().toString(), "unrecorded_fee", List.of(
                new AdjustmentPosting("fees:" + scene.provider() + ":XAF", amount, "XAF"),
                new AdjustmentPosting("provider:" + scene.provider() + ":float:XAF", -amount, "XAF")),
                Map.of("statement-line", "line 42: fee " + amount), List.of("statement-line"),
                "The operator charged a fee the ledger never recorded.");
    }

    @Test
    @DisplayName("propose with one key, approve with another: the entry appears, the balance moves, "
            + "and a second approval is refused by the ledger's own duplicate-id error")
    void the_whole_path_and_a_second_approval_refused_by_the_ledger() {
        Scene scene = scene();
        Adjustment proposed = service.propose(scene.first(), feeProposal(scene, 150));

        Adjustment approved = service.approve(scene.second(), proposed.id(), "matches the statement");

        String entryId = AdjustmentService.entryId(proposed.id());
        assertThat(approved.decision()).get().satisfies(d -> {
            assertThat(d.approved()).isTrue();
            assertThat(d.decidedBy()).isEqualTo(scene.second().keyId());
        });
        assertThat(postgresLedger.entriesForReference(scene.payment().reference().toString()))
                .extracting(LedgerEntry::id).containsExactly(entryId);
        assertThat(postgresLedger.balance(AccountId.fees(scene.provider(), Currency.XAF), Currency.XAF))
                .isEqualTo(Money.of(150, Currency.XAF));
        assertThat(postgresLedger.balance(AccountId.providerFloat(scene.provider(), Currency.XAF), Currency.XAF))
                .isEqualTo(Money.of(-150, Currency.XAF));

        ledger.forget();
        ApiException second = (ApiException) catchThrowable(
                () -> service.approve(scene.second(), proposed.id(), "approving again"));

        assertThat(second.type()).isEqualTo(ProblemTypes.ADJUSTMENT_ALREADY_DECIDED);
        assertThat(ledger.lastFailure())
                .as("the refusal came from the ledger's primary key, not from a read made first")
                .isInstanceOf(DuplicateLedgerEntryException.class)
                .hasMessageContaining(entryId);
        assertThat(postgresLedger.entriesForReference(scene.payment().reference().toString())).hasSize(1);
        assertThat(postgresLedger.balance(AccountId.fees(scene.provider(), Currency.XAF), Currency.XAF))
                .isEqualTo(Money.of(150, Currency.XAF));
    }

    @Test
    @DisplayName("two keys approving the same proposal at once write exactly one entry")
    void two_concurrent_approvals_write_one_entry() throws Exception {
        Scene scene = scene();
        ApiCredential third = keys.provision(scene.merchant(), false, "AdjustmentPersistenceIT").credential();
        Adjustment proposed = service.propose(scene.first(), feeProposal(scene, 200));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (ApiCredential approver : List.of(scene.second(), third)) {
                Callable<Object> approve = () -> {
                    start.await();
                    try {
                        return service.approve(approver, proposed.id(), "");
                    } catch (ApiException refused) {
                        return refused;
                    }
                };
                results.add(pool.submit(approve));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> result : results) {
                outcomes.add(result.get());
            }
            assertThat(outcomes).filteredOn(o -> o instanceof Adjustment).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o instanceof ApiException)
                    .allSatisfy(o -> assertThat(((ApiException) o).type())
                            .isEqualTo(ProblemTypes.ADJUSTMENT_ALREADY_DECIDED));
        } finally {
            pool.shutdownNow();
        }
        assertThat(postgresLedger.entriesForReference(scene.payment().reference().toString())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM adjustment_decision WHERE proposal_id = ?",
                Integer.class, proposed.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("a proposal already rejected cannot then be approved: the entry is rolled back with the refused decision")
    void a_rejected_proposal_cannot_be_approved() {
        Scene scene = scene();
        Adjustment proposed = service.propose(scene.first(), feeProposal(scene, 150));
        service.reject(scene.second(), proposed.id(), "no such fee on the statement");

        ApiException refusal = (ApiException) catchThrowable(
                () -> service.approve(scene.second(), proposed.id(), "after all"));

        assertThat(refusal.type()).isEqualTo(ProblemTypes.ADJUSTMENT_ALREADY_DECIDED);
        assertThat(postgresLedger.entriesForReference(scene.payment().reference().toString())).isEmpty();
        assertThat(store.find(proposed.id()).orElseThrow().decision()).get()
                .satisfies(d -> assertThat(d.approved()).isFalse());
    }

    @Test
    @DisplayName("approval by the proposing key is refused with its problem type, against a real database")
    void approval_by_the_proposing_key_is_refused() {
        Scene scene = scene();
        Adjustment proposed = service.propose(scene.first(), feeProposal(scene, 150));

        ApiException refusal = (ApiException) catchThrowable(
                () -> service.approve(scene.first(), proposed.id(), "mine, so I approve it"));

        assertThat(refusal.type()).isEqualTo(ProblemTypes.ADJUSTMENT_SELF_APPROVAL);
        assertThat(postgresLedger.entriesForReference(scene.payment().reference().toString())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM adjustment_decision WHERE proposal_id = ?",
                Integer.class, proposed.id())).isZero();
    }

    @Test
    @DisplayName("an unbalanced entry under an adjustment's own id is refused by the database at commit, "
            + "with the trigger's message, whatever the application checked")
    void an_unbalanced_adjustment_entry_is_refused_by_the_database() throws SQLException {
        Scene scene = scene();
        Adjustment proposed = service.propose(scene.first(), feeProposal(scene, 150));
        String entryId = AdjustmentService.entryId(proposed.id());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var entry = connection.prepareStatement(
                    "INSERT INTO ledger_entry (id, occurred_at, reference, description, currency, posting_count) "
                            + "VALUES (?, now(), ?, 'adjustment', 'XAF', 2)")) {
                entry.setString(1, entryId);
                entry.setString(2, scene.payment().reference().toString());
                entry.executeUpdate();
            }
            insertPosting(connection, entryId, 0, "fees:" + scene.provider() + ":XAF", 150);
            insertPosting(connection, entryId, 1, "provider:" + scene.provider() + ":float:XAF", -100);

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("does not balance")
                    .hasMessageContaining(entryId)
                    .hasMessageContaining("sum to 50");
        }
        assertThat(postgresLedger.entriesForReference(scene.payment().reference().toString())).isEmpty();
    }

    @Test
    @DisplayName("a proposal and a decision, once recorded, cannot be updated or deleted from the application's connection")
    void proposals_and_decisions_are_append_only() {
        Scene scene = scene();
        Adjustment proposed = service.propose(scene.first(), feeProposal(scene, 150));
        service.reject(scene.second(), proposed.id(), "no");

        for (String statement : List.of(
                "UPDATE adjustment_proposal SET explanation = 'rewritten' WHERE id = ?",
                "DELETE FROM adjustment_proposal WHERE id = ?")) {
            assertThatThrownBy(() -> jdbc.update(statement, proposed.id()))
                    .as(statement).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        }
        for (String statement : List.of(
                "UPDATE adjustment_decision SET approved = true WHERE proposal_id = ?",
                "DELETE FROM adjustment_decision WHERE proposal_id = ?")) {
            assertThatThrownBy(() -> jdbc.update(statement, proposed.id()))
                    .as(statement).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        }
    }

    @Test
    @DisplayName("a second decision for one proposal is refused by the database's unique constraint, not by the caller")
    void one_decision_per_proposal_is_a_database_constraint() {
        Scene scene = scene();
        Adjustment proposed = service.propose(scene.first(), feeProposal(scene, 150));
        store.insertDecision(UUID.randomUUID(), proposed.id(), false, scene.second().keyId(), Instant.now(), "first");

        assertThatThrownBy(() -> store.insertDecision(
                UUID.randomUUID(), proposed.id(), true, scene.second().keyId(), Instant.now(), "second"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("adjustment_decision_one_per_proposal");
    }

    @Test
    @DisplayName("every hypothesis the API accepts is accepted by the database, and any other value is refused by it")
    void the_hypothesis_check_constraint_and_the_enum_agree() {
        Scene scene = scene();
        for (Hypothesis hypothesis : Hypothesis.values()) {
            store.insertProposal(UUID.randomUUID(), scene.payment().reference().value(), hypothesis,
                    feeProposal(scene, 150).postings(), Map.of("k", "v"), List.of("k"), "why",
                    scene.first().keyId(), Instant.now());
        }

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO adjustment_proposal (id, payment_reference, hypothesis, postings, evidence, explanation, "
                        + "key_id, created_at) VALUES (?, ?, 'gut_feeling', '[]'::jsonb, '{}'::jsonb, 'why', ?, now())",
                UUID.randomUUID(), scene.payment().reference().value(), scene.first().keyId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("adjustment_proposal_hypothesis_known");
    }

    private static void insertPosting(Connection connection, String entryId, int seq, String account, long amount)
            throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT INTO posting (entry_id, seq, account, amount_minor) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, entryId);
            ps.setInt(2, seq);
            ps.setString(3, account);
            ps.setLong(4, amount);
            ps.executeUpdate();
        }
    }

    /** Delegates to the real ledger and remembers what {@code append} last threw, so a test can name the cause of a refusal. */
    private static final class RecordingLedger implements Ledger {
        private final Ledger delegate;
        private volatile RuntimeException lastFailure;

        RecordingLedger(Ledger delegate) {
            this.delegate = delegate;
        }

        void forget() {
            lastFailure = null;
        }

        RuntimeException lastFailure() {
            return lastFailure;
        }

        @Override
        public void append(LedgerEntry entry) {
            try {
                delegate.append(entry);
            } catch (RuntimeException failure) {
                lastFailure = failure;
                throw failure;
            }
        }

        @Override
        public Money balance(AccountId account, Currency currency) {
            return delegate.balance(account, currency);
        }

        @Override
        public List<LedgerEntry> entries() {
            return delegate.entries();
        }

        @Override
        public List<LedgerEntry> entriesForReference(String reference) {
            return delegate.entriesForReference(reference);
        }
    }
}

package dev.nkap.server.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.payment.SettlementService;
import dev.nkap.server.persistence.PostgresLedger;
import dev.nkap.server.persistence.PostgresPaymentRepository;
import dev.nkap.server.provider.AdapterRegistry;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The reconciler, against a real PostgreSQL.
 *
 * <p>The reconciler chases every unresolved payment — {@code SUBMITTED}, {@code PENDING}
 * and {@code UNKNOWN} — not only {@code UNKNOWN}: a payment answered {@code PENDING} that
 * then sits forever is the same hole reached through a different door.
 *
 * <p>The one test that has to be right before any other is
 * {@link #an_expired_window_escalates_and_never_yields_failed()}: a reconciler that turns
 * "we gave up waiting" into {@code FAILED} manufactures the exact false verdict this whole
 * project exists to refuse, and is worse than no reconciler. When the retry window is
 * spent, the payment is <strong>escalated</strong> — flagged for a human — and stays
 * non-terminal, resolvable later by a callback or a query. It never becomes {@code FAILED}
 * on the strength of silence.
 */
@ExtendWith(DockerAvailable.class)
class ReconcilerIT {

    private static final ProviderId MTN = ProviderId.of("mtn");

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;
    private static PostgresPaymentRepository payments;
    private static PostgresLedger ledger;

    @BeforeAll
    static void connect() {
        PostgresDatabase db = PostgresDatabase.shared();
        jdbc = db.jdbcTemplate();
        txManager = db.transactionManager();
        payments = new PostgresPaymentRepository(jdbc, new ObjectMapper());
        ledger = new PostgresLedger(jdbc, txManager);
    }

    // === the reconciler chases every unresolved payment, not only UNKNOWN ==========

    @Test
    @DisplayName("a payment the reconciler moves from UNKNOWN to PENDING is claimed again on the next pass")
    void a_payment_moved_to_pending_is_still_chased() throws Exception {
        ProviderAdapter operator = mock(ProviderAdapter.class);
        when(operator.query(any())).thenReturn(new ProviderStatus(
                PaymentState.PENDING, "PENDING", "", null, "", "{\"status\":\"PENDING\"}"));
        Reconciler reconciler = reconcilerWith(defaults(), registryFor(operator));

        ReferenceId reference = anUnknownPaymentDueForReconciliation();

        assertThat(reconciler.runOnce()).as("the UNKNOWN payment is claimed").isEqualTo(1);
        assertThat(payments.findByReference(reference).orElseThrow().state())
                .as("the operator answered PENDING, and the reconciler applied it")
                .isEqualTo(PaymentState.PENDING);

        // The claim pushed the next attempt into the future; make it due again.
        jdbc.update("UPDATE payment SET reconcile_due_at = now() - interval '1 hour' WHERE reference = ?",
                reference.value());

        assertThat(reconciler.runOnce())
                .as("PENDING is not resolved — nothing has settled — so the reconciler must ask again")
                .isEqualTo(1);
        verify(operator, times(2)).query(any());
        assertThat(payments.findByReference(reference).orElseThrow().state()).isEqualTo(PaymentState.PENDING);
    }

    @Test
    @DisplayName("a SUBMITTED payment whose operator has gone silent is claimed and re-queried")
    void a_silent_submitted_payment_is_chased() throws Exception {
        ProviderAdapter operator = mock(ProviderAdapter.class);
        when(operator.query(any())).thenThrow(new ProviderUnavailableException("still nothing"));
        Reconciler reconciler = reconcilerWith(defaults(), registryFor(operator));

        ReferenceId reference = aSubmittedPaymentDueForReconciliation();

        assertThat(reconciler.runOnce()).as("a SUBMITTED payment is claimed").isEqualTo(1);
        verify(operator, times(1)).query(any());

        Map<String, Object> row = paymentRow(reference);
        assertThat(row.get("state")).as("a silent query changes nothing").isEqualTo(PaymentState.SUBMITTED.name());
        assertThat(((Number) row.get("reconcile_attempts")).intValue()).as("the attempt was counted").isEqualTo(1);
        assertThat(row.get("escalated_at")).as("the default 24h window is nowhere near spent").isNull();
    }

    // === the window is wall-clock time since the payment became UNKNOWN, not a sum of attempts ===

    @Test
    @DisplayName("a payment UNKNOWN for less than the window is retried and not escalated, however many attempts it has")
    void within_the_window_it_is_retried_not_escalated_whatever_the_attempt_count() {
        // window: one hour. The clock is ten minutes past the moment the payment became
        // UNKNOWN — well inside — but fifty passes have been recorded, as a crash loop
        // that claims and advances the count without any real waiting would leave it.
        ReconcilerProperties properties = new ReconcilerProperties(
                Duration.ofSeconds(30), 50,
                Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofHours(1));
        Instant unresolvedSince = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AdjustableClock clock = new AdjustableClock(unresolvedSince.plus(Duration.ofMinutes(10)));
        Reconciler reconciler = reconcilerWith(properties, operatorThatIsSilent(), clock);

        ReferenceId reference = anUnknownPaymentDueForReconciliation();
        jdbc.update("UPDATE payment SET reconcile_attempts = 50, unresolved_since = ?, reconcile_due_at = ? "
                        + "WHERE reference = ?",
                OffsetDateTime.ofInstant(unresolvedSince, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(unresolvedSince.minus(Duration.ofMinutes(1)), ZoneOffset.UTC),
                reference.value());

        reconciler.runOnce();

        Map<String, Object> row = paymentRow(reference);
        assertThat(row.get("escalated_at"))
                .as("ten minutes into a one-hour window, no attempt count escalates the payment")
                .isNull();
        assertThat(row.get("state")).isEqualTo(PaymentState.UNKNOWN.name());
        assertThat(((Number) row.get("reconcile_attempts")).intValue()).as("it was retried").isEqualTo(51);
        assertThat(reconcileDueAt(reference)).as("the next attempt is scheduled").isAfter(clock.instant());
    }

    @Test
    @DisplayName("a payment UNKNOWN for longer than the window is escalated on its next unresolved attempt, and stays UNKNOWN")
    void past_the_window_it_is_escalated_on_the_next_unresolved_attempt() {
        // window: one hour. Only one pass has been recorded, but the clock is two hours
        // past the moment the payment became UNKNOWN. The elapsed time escalates it, not
        // the count.
        ReconcilerProperties properties = new ReconcilerProperties(
                Duration.ofSeconds(30), 50,
                Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofHours(1));
        Instant unresolvedSince = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AdjustableClock clock = new AdjustableClock(unresolvedSince.plus(Duration.ofHours(2)));
        Reconciler reconciler = reconcilerWith(properties, operatorThatIsSilent(), clock);

        ReferenceId reference = anUnknownPaymentDueForReconciliation();
        jdbc.update("UPDATE payment SET reconcile_attempts = 1, unresolved_since = ?, reconcile_due_at = ? "
                        + "WHERE reference = ?",
                OffsetDateTime.ofInstant(unresolvedSince, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(unresolvedSince.plus(Duration.ofMinutes(1)), ZoneOffset.UTC),
                reference.value());

        reconciler.runOnce();

        Map<String, Object> row = paymentRow(reference);
        assertThat(row.get("escalated_at")).as("two hours into a one-hour window, it is escalated").isNotNull();
        assertThat(row.get("state")).isEqualTo(PaymentState.UNKNOWN.name());
        assertThat(statesEverReached(reference))
                .as("escalation is not a verdict — never FAILED")
                .doesNotContain(PaymentState.FAILED.name());
    }

    @Test
    @DisplayName("past the window, the confirming call still happens first: a resolving answer settles the payment instead of escalating")
    void a_confirming_call_past_the_window_can_still_resolve_instead_of_escalate() {
        ReconcilerProperties properties = new ReconcilerProperties(
                Duration.ofSeconds(30), 50, Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofSeconds(1));
        Reconciler reconciler = reconcilerWith(properties, operatorAnswering(new ProviderStatus(
                PaymentState.SUCCEEDED, "SUCCESSFUL", "txn-last", null, "", "{\"status\":\"SUCCESSFUL\"}")));

        ReferenceId reference = anUnknownPaymentDueForReconciliation();
        jdbc.update("UPDATE payment SET unresolved_since = now() - interval '1 hour' WHERE reference = ?",
                reference.value());

        reconciler.runOnce();

        Map<String, Object> row = paymentRow(reference);
        assertThat(row.get("state")).as("the last try resolved it").isEqualTo(PaymentState.SUCCEEDED.name());
        assertThat(row.get("escalated_at")).as("a resolved payment is never escalated, window spent or not").isNull();
        assertThat(ledger.entriesForReference(reference.toString())).hasSize(1);
    }

    @Test
    @DisplayName("hopping between non-terminal states does not restart the window: a payment past the window is still escalated")
    void hopping_non_terminal_states_does_not_restart_the_window() {
        ReconcilerProperties properties = new ReconcilerProperties(
                Duration.ofSeconds(30), 50, Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofHours(1));
        // The operator only ever says PENDING — a legal, non-terminal answer, never a verdict.
        Reconciler reconciler = reconcilerWith(properties, operatorAnswering(new ProviderStatus(
                PaymentState.PENDING, "PENDING", "", null, "", "{\"status\":\"PENDING\"}")));

        ReferenceId reference = anUnknownPaymentDueForReconciliation();
        // It has been unresolved for two hours — past the one-hour window.
        jdbc.update("UPDATE payment SET unresolved_since = now() - interval '2 hours' WHERE reference = ?",
                reference.value());

        // Pass 1 moves it UNKNOWN -> PENDING; a later pass finds it still PENDING and escalates.
        for (int pass = 0; pass < 4 && paymentRow(reference).get("escalated_at") == null; pass++) {
            reconciler.runOnce();
            jdbc.update("UPDATE payment SET reconcile_due_at = now() - interval '1 hour' WHERE reference = ?",
                    reference.value());
        }

        Map<String, Object> row = paymentRow(reference);
        assertThat(row.get("escalated_at"))
                .as("two hours unresolved is past the window — moving UNKNOWN -> PENDING did not reset it")
                .isNotNull();
        assertThat(row.get("state")).isEqualTo(PaymentState.PENDING.name());
        assertThat(unresolvedSince(reference))
                .as("unresolved_since is still the original moment, not a hop")
                .isBefore(Instant.now().minus(Duration.ofMinutes(90)));
        assertThat(statesEverReached(reference))
                .as("escalation is never a verdict — never FAILED")
                .doesNotContain(PaymentState.FAILED.name());
    }

    // === 4. the one that matters most, written first =================================

    @Test
    @DisplayName("when the reconciliation window expires the payment is escalated, stays UNKNOWN, and is never FAILED")
    void an_expired_window_escalates_and_never_yields_failed() {
        // The window is one second and the payment has been UNKNOWN for an hour, so this
        // pass is the one that escalates.
        ReconcilerProperties properties = new ReconcilerProperties(
                Duration.ofSeconds(30), 50,
                Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofSeconds(1));
        Reconciler reconciler = reconcilerWith(properties, operatorThatIsSilent());

        ReferenceId reference = anUnknownPaymentDueForReconciliation();
        jdbc.update("UPDATE payment SET unresolved_since = now() - interval '1 hour' WHERE reference = ?",
                reference.value());

        reconciler.runOnce();

        Map<String, Object> row = paymentRow(reference);
        assertThat(row.get("state")).isEqualTo(PaymentState.UNKNOWN.name());
        assertThat(row.get("escalated_at")).as("escalated_at is stamped").isNotNull();
        assertThat(statesEverReached(reference))
                .as("no transition to FAILED — giving up is not evidence")
                .doesNotContain(PaymentState.FAILED.name());
    }

    // === 1. a resolving query settles, once, attributed to the reconciler ============

    @Test
    @DisplayName("a reconciler query that now answers SUCCEEDED settles the payment once, with cause RECONCILER")
    void a_resolving_query_settles_once_with_cause_reconciler() {
        Reconciler reconciler = reconcilerWith(defaults(), operatorAnswering(
                new ProviderStatus(PaymentState.SUCCEEDED, "SUCCESSFUL", "txn-9", null, "", "{\"status\":\"SUCCESSFUL\"}")));

        ReferenceId reference = anUnknownPaymentDueForReconciliation();

        reconciler.runOnce();

        Payment settled = payments.findByReference(reference).orElseThrow();
        assertThat(settled.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(ledger.entriesForReference(reference.toString())).hasSize(1);
        assertThat(settled.history()).last().satisfies(transition ->
                assertThat(transition.cause()).isEqualTo(PaymentTransition.Cause.RECONCILER));
    }

    // === 2. a silent query records the attempt and pushes the next one out ===========

    @Test
    @DisplayName("a reconciler query that still does not answer leaves the payment UNKNOWN, counts the attempt, and defers the next")
    void a_silent_query_defers_the_next_attempt() {
        ReconcilerProperties properties = new ReconcilerProperties(
                Duration.ofSeconds(30), 50, Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofHours(24));
        Reconciler reconciler = reconcilerWith(properties, operatorThatIsSilent());

        ReferenceId reference = anUnknownPaymentDueForReconciliation();
        Instant before = Instant.now();

        reconciler.runOnce();

        Map<String, Object> row = paymentRow(reference);
        assertThat(row.get("state")).isEqualTo(PaymentState.UNKNOWN.name());
        assertThat(row.get("escalated_at")).isNull();
        assertThat(((Number) row.get("reconcile_attempts")).intValue()).isEqualTo(1);
        assertThat(reconcileDueAt(reference)).as("next attempt is roughly one backoff-base in the future")
                .isBetween(before.plus(Duration.ofMinutes(4)), before.plus(Duration.ofMinutes(6)));
    }

    // === 3. the interval doubles, then holds at the configured ceiling ==============

    @Test
    @DisplayName("the reconciler interval doubles each attempt and then stops growing at the configured maximum")
    void the_interval_doubles_then_holds_at_the_maximum() {
        ReconcilerProperties properties = new ReconcilerProperties(
                Duration.ofSeconds(30), 50,
                Duration.ofSeconds(1), Duration.ofSeconds(4), Duration.ofHours(1));
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AdjustableClock clock = new AdjustableClock(t0);
        Reconciler reconciler = reconcilerWith(properties, operatorThatIsSilent(), clock);

        ReferenceId reference = anUnknownPaymentDueForReconciliation();
        jdbc.update("UPDATE payment SET reconcile_due_at = ? WHERE reference = ?",
                OffsetDateTime.ofInstant(t0, ZoneOffset.UTC), reference.value());

        List<Duration> gaps = new ArrayList<>();
        Instant now = t0;
        for (int pass = 1; pass <= 5; pass++) {
            clock.set(now);
            reconciler.runOnce();
            Instant nextDue = reconcileDueAt(reference);
            gaps.add(Duration.between(now, nextDue));
            now = nextDue;
        }

        assertThat(gaps).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                Duration.ofSeconds(4), Duration.ofSeconds(4));
        assertThat(paymentRow(reference).get("escalated_at")).as("15s of attempts is well inside a one-hour window").isNull();
    }

    // === 5. an escalated payment is left alone, but a later callback still resolves it =

    @Test
    @DisplayName("an escalated payment is not queried again by the reconciler, but a callback arriving later still resolves it")
    void an_escalated_payment_is_not_retried_but_a_later_callback_resolves_it() throws Exception {
        ReconcilerProperties properties = new ReconcilerProperties(
                Duration.ofSeconds(30), 50, Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofSeconds(1));
        ProviderAdapter silentOperator = mock(ProviderAdapter.class);
        when(silentOperator.query(any())).thenThrow(new ProviderUnavailableException("silent"));
        Reconciler reconciler = reconcilerWith(properties, registryFor(silentOperator));

        ReferenceId reference = anUnknownPaymentDueForReconciliation();
        jdbc.update("UPDATE payment SET unresolved_since = now() - interval '1 hour' WHERE reference = ?",
                reference.value());

        reconciler.runOnce();                       // one query, then escalates
        assertThat(paymentRow(reference).get("escalated_at")).isNotNull();

        // Make it due again on paper — the reconciler must still not touch it.
        jdbc.update("UPDATE payment SET reconcile_due_at = now() - interval '1 hour' WHERE reference = ?",
                reference.value());
        int claimed = reconciler.runOnce();

        assertThat(claimed).as("escalated payments are not claimed").isZero();
        verify(silentOperator, times(1)).query(any());   // never asked a second time
        assertThat(payments.findByReference(reference).orElseThrow().state()).isEqualTo(PaymentState.UNKNOWN);

        // A callback for the same reference, later, goes through the shared path and resolves it.
        AdapterRegistry callbackAdapters = operatorAnswering(new ProviderStatus(
                PaymentState.SUCCEEDED, "SUCCESSFUL", "txn-late", null, "", "{\"status\":\"SUCCESSFUL\"}"));
        new SettlementService(payments, callbackAdapters, ledger, txManager)
                .confirm(MTN, reference, PaymentTransition.Cause.CALLBACK);

        assertThat(payments.findByReference(reference).orElseThrow().state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(ledger.entriesForReference(reference.toString())).hasSize(1);
    }

    // === 6. two reconcilers, real SKIP LOCKED, never the same payment ================

    @Test
    @DisplayName("two reconcilers claiming at the same moment never claim the same payment (real SELECT FOR UPDATE SKIP LOCKED)")
    void two_reconcilers_never_claim_the_same_payment() throws Exception {
        ReconciliationPolicy policy = new ReconciliationPolicy(defaults());
        PostgresReconciliationStore storeA = new PostgresReconciliationStore(jdbc, txManager, policy);
        PostgresReconciliationStore storeB = new PostgresReconciliationStore(jdbc, txManager, policy);

        List<ReferenceId> seeded = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            seeded.add(anUnknownPaymentDueForReconciliation());
        }

        Instant now = Instant.now();
        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<ReconciliationStore.Claim>> a = pool.submit(() -> {
                bothReady.await();
                return storeA.claimDue(10, now);
            });
            Future<List<ReconciliationStore.Claim>> b = pool.submit(() -> {
                bothReady.await();
                return storeB.claimDue(10, now);
            });

            List<UUID> claimedByA = a.get().stream().map(c -> c.reference().value()).toList();
            List<UUID> claimedByB = b.get().stream().map(c -> c.reference().value()).toList();

            List<UUID> both = new ArrayList<>(claimedByA);
            both.retainAll(claimedByB);
            assertThat(both).as("no payment claimed by both reconcilers").isEmpty();

            List<UUID> all = new ArrayList<>(claimedByA);
            all.addAll(claimedByB);
            assertThat(all).as("no payment claimed twice overall").doesNotHaveDuplicates();
            assertThat(Set.copyOf(all)).as("every due payment was claimed exactly once")
                    .isEqualTo(seeded.stream().map(ReferenceId::value).collect(Collectors.toSet()));
        } finally {
            pool.shutdownNow();
        }
    }

    // === 7. a terminal payment is never claimed, whatever its columns say ===========

    @Test
    @DisplayName("a terminal payment is never claimed by the reconciler, even with a due date and no escalation on its row")
    void a_terminal_payment_is_never_claimed() {
        Reconciler reconciler = reconcilerWith(defaults(), operatorAnswering(
                new ProviderStatus(PaymentState.SUCCEEDED, "SUCCESSFUL", "txn-x", null, "", "{}")));

        ReferenceId reference = ReferenceId.newReference();
        Payment payment = Payment.create(reference, MTN, "merchant-1", intent());
        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payment.applyTransition(PaymentState.FAILED, PaymentTransition.Cause.CALLBACK, "NOT_ENOUGH_FUNDS", "", "");
        payments.save(payment);
        // Its reconciler columns say "due, not escalated" — the state must still win.
        jdbc.update("UPDATE payment SET reconcile_attempts = 0, reconcile_due_at = now() - interval '1 hour', "
                + "escalated_at = NULL WHERE reference = ?", reference.value());

        int claimed = reconciler.runOnce();

        assertThat(claimed).isZero();
        assertThat(payments.findByReference(reference).orElseThrow().state()).isEqualTo(PaymentState.FAILED);
    }

    // === helpers ===================================================================

    private ReconcilerProperties defaults() {
        return new ReconcilerProperties(Duration.ofSeconds(30), 50,
                Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofHours(24));
    }

    private Reconciler reconcilerWith(ReconcilerProperties properties, AdapterRegistry adapters) {
        return reconcilerWith(properties, adapters, Clock.systemUTC());
    }

    private Reconciler reconcilerWith(ReconcilerProperties properties, AdapterRegistry adapters, Clock clock) {
        ReconciliationPolicy policy = new ReconciliationPolicy(properties);
        ReconciliationStore store = new PostgresReconciliationStore(jdbc, txManager, policy);
        SettlementService settlement = new SettlementService(payments, adapters, ledger, txManager);
        return new Reconciler(store, settlement, policy, properties, clock);
    }

    private static AdapterRegistry operatorThatIsSilent() {
        ProviderAdapter operator = mock(ProviderAdapter.class);
        try {
            when(operator.query(any())).thenThrow(new ProviderUnavailableException("the operator is silent"));
        } catch (ProviderUnavailableException impossible) {
            throw new AssertionError(impossible);
        }
        return registryFor(operator);
    }

    private static AdapterRegistry operatorAnswering(ProviderStatus status) {
        ProviderAdapter operator = mock(ProviderAdapter.class);
        try {
            when(operator.query(any())).thenReturn(status);
        } catch (ProviderUnavailableException impossible) {
            throw new AssertionError(impossible);
        }
        return registryFor(operator);
    }

    private static AdapterRegistry registryFor(ProviderAdapter operator) {
        AdapterRegistry adapters = mock(AdapterRegistry.class);
        when(adapters.require(any())).thenReturn(operator);
        return adapters;
    }

    private static PaymentIntent intent() {
        return new PaymentIntent(Capability.COLLECT, Money.of(5_000, Currency.EUR),
                "46733123453", "rent", "march", Map.of());
    }

    /** A persisted payment sitting in {@code UNKNOWN} with its next reconciler attempt already due. */
    private ReferenceId anUnknownPaymentDueForReconciliation() {
        ReferenceId reference = ReferenceId.newReference();
        Payment payment = Payment.create(reference, MTN, "merchant-1", intent());
        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payment.applyTransition(PaymentState.UNKNOWN, PaymentTransition.Cause.SUBMIT_RESPONSE,
                "", "the submit call did not answer", "");
        payments.save(payment);
        // Make it unambiguously due, whatever the clocks are doing.
        jdbc.update("UPDATE payment SET reconcile_due_at = now() - interval '1 hour' WHERE reference = ?",
                reference.value());
        return reference;
    }

    /** A persisted payment the operator acknowledged ({@code SUBMITTED}) and then went silent on, due now. */
    private ReferenceId aSubmittedPaymentDueForReconciliation() {
        ReferenceId reference = ReferenceId.newReference();
        Payment payment = Payment.create(reference, MTN, "merchant-1", intent());
        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "202 accepted", "");
        payments.save(payment);
        jdbc.update("UPDATE payment SET reconcile_due_at = now() - interval '1 hour' WHERE reference = ?",
                reference.value());
        return reference;
    }

    private static Map<String, Object> paymentRow(ReferenceId reference) {
        return jdbc.queryForMap("SELECT * FROM payment WHERE reference = ?", reference.value());
    }

    private static Instant reconcileDueAt(ReferenceId reference) {
        return jdbc.queryForObject("SELECT reconcile_due_at FROM payment WHERE reference = ?",
                OffsetDateTime.class, reference.value()).toInstant();
    }

    private static Instant unresolvedSince(ReferenceId reference) {
        return jdbc.queryForObject("SELECT unresolved_since FROM payment WHERE reference = ?",
                OffsetDateTime.class, reference.value()).toInstant();
    }

    private static List<String> statesEverReached(ReferenceId reference) {
        return jdbc.queryForList(
                "SELECT to_state FROM payment_transition WHERE payment_reference = ? ORDER BY seq",
                String.class, reference.value());
    }

    /** A clock the test moves by hand, so backoff maths is checked without waiting for real time. */
    private static final class AdjustableClock extends Clock {

        private volatile Instant now;

        AdjustableClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

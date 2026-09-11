package dev.nkap.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.InMemoryLedger;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.provider.ProviderRouting;
import dev.nkap.server.statement.ReconciliationReport;
import dev.nkap.server.statement.SettledPayment;
import dev.nkap.server.statement.StatementLine;
import dev.nkap.server.statement.StatementReconciliation;
import dev.nkap.server.statement.StatementReconciliationStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The suspense gauge is why issue #75 exists: the roadmap calls it the single most useful
 * alert in the system, and until this class existed, nothing watched it. Written first, per
 * the plan — and against a real {@link StatementReconciliation} run and a real reversing
 * entry, not a hand-rolled ledger fixture that assumes what "posting to suspense" and
 * "resolving it" mean.
 */
class SuspenseBalanceMetricsTest {

    private static final ProviderId MTN = ProviderId.of("mtn");

    private final Ledger ledger = new InMemoryLedger();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    @DisplayName("the suspense gauge is non-zero after a statement import posts to suspense, and returns to zero once the entry is resolved")
    void the_gauge_tracks_the_live_suspense_balance() {
        new SuspenseBalanceMetrics()
                .suspenseBalanceGauges(ledger, List.of(new ProviderRouting(MTN, Currency.EUR)))
                .bindTo(registry);
        Gauge gauge = registry.get("nkap.suspense.balance").gauge();

        assertThat(gauge.value()).as("nothing has happened yet").isZero();

        StatementReconciliation reconciliation = new StatementReconciliation(ledger, new NoSettledPayments());
        reconciliation.reconcile(MTN, "unattributed.csv", List.of(new StatementLine(
                "txn-1", Money.of(5000, Currency.EUR), Money.zero(Currency.EUR),
                Instant.parse("2026-09-11T10:00:00Z"), StatementLine.Status.SETTLED)));

        assertThat(gauge.value())
                // Raw ledger sign, unmodified: postToSuspense credits the account, and a
                // credit is a negative amount everywhere else in this ledger too (Posting's
                // own convention). The gauge exposes exactly what Ledger.balance already
                // returns, the same as every other reader of an account's balance.
                .as("the operator says 5000 EUR moved and no settled payment claims it")
                .isEqualTo(-5000.0);

        // "Resolved" is CLAUDE.md's own rule, applied: the ledger is append-only, so a
        // mistake is corrected by a reversing entry, never by editing the original. The
        // gauge must reflect that reversal live, the same way it reflected the original post.
        LedgerEntry posted = ledger.entriesForReference("statement:txn-1").stream().findFirst().orElseThrow();
        ledger.append(LedgerEntry.reversalOf(posted, "suspense-resolved:mtn:txn-1", Instant.now(),
                "attributed to payment after manual investigation"));

        assertThat(gauge.value()).as("the reversal brings the account back to zero").isZero();
    }

    @Test
    @DisplayName("a failure to compute the gauge is distinguishable from a zero balance — it reports NaN, not 0")
    void a_computation_failure_is_never_reported_as_a_healthy_zero() {
        Ledger brokenLedger = mock(Ledger.class);
        when(brokenLedger.balance(AccountId.suspense("mtn", Currency.EUR), Currency.EUR))
                .thenThrow(new RuntimeException("the database is unreachable"));

        new SuspenseBalanceMetrics()
                .suspenseBalanceGauges(brokenLedger, List.of(new ProviderRouting(MTN, Currency.EUR)))
                .bindTo(registry);
        double value = registry.get("nkap.suspense.balance").gauge().value();

        // The point is not merely "not zero" -- 0 itself is a value an alert could be
        // written to treat as healthy by mistake. NaN is a value no ordinary "> 0" or
        // "== 0" comparison can ever satisfy, healthy or not, which is what keeps a query
        // failure from being mistaken for either answer.
        assertThat(Double.isNaN(value)).as("a computation failure is NaN, not a number at all").isTrue();
        assertThat(value).as("NaN is never equal to zero, by definition").isNotEqualTo(0.0);
    }

    /** No settled payment ever matches, so every {@code SETTLED} line posts to suspense. */
    private static final class NoSettledPayments implements StatementReconciliationStore {
        @Override
        public Optional<SettledPayment> findSettledByOperatorTransactionId(ProviderId provider, String operatorTransactionId) {
            return Optional.empty();
        }

        @Override
        public List<SettledPayment> settledPayments(ProviderId provider) {
            return List.of();
        }

        @Override
        public void record(ReconciliationReport report, List<StatementLine> lines) {
            // Evidence storage is not this test's concern.
        }

        @Override
        public Optional<ReconciliationReport> findReport(UUID importId) {
            return Optional.empty();
        }

        @Override
        public List<StatementLine> findLines(UUID importId) {
            return List.of();
        }
    }
}

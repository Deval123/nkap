package dev.nkap.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentRepository;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.payment.SettlementService;
import dev.nkap.server.provider.AdapterRegistry;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The reason the ledger, the payments and the idempotency store had to ship in one pull
 * request: settlement writes the ledger entry and the payment's new state, and a failure
 * between the two must leave <strong>neither</strong>. Against PostgreSQL, that is one
 * transaction — a boundary that cannot be opened between a table and a {@code HashMap}.
 */
@ExtendWith(DockerAvailable.class)
class SettlementTransactionIT {

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;

    @BeforeAll
    static void connect() {
        jdbc = PostgresDatabase.shared().jdbcTemplate();
        txManager = PostgresDatabase.shared().transactionManager();
    }

    private static PaymentIntent intent() {
        return new PaymentIntent(Capability.COLLECT, Money.of(5_000, Currency.EUR),
                "46733123453", "rent", "march", Map.of());
    }

    @Test
    @DisplayName("a settlement that fails after the ledger write leaves neither the entry nor the SUCCEEDED transition")
    void a_failure_after_the_ledger_write_rolls_back_everything() {
        PostgresLedger ledger = new PostgresLedger(jdbc, txManager);
        PostgresPaymentRepository realRepo = new PostgresPaymentRepository(jdbc, new ObjectMapper());

        ReferenceId reference = ReferenceId.newReference();
        Payment submitted = Payment.create(reference, ProviderId.of("mtn"), "merchant-1", intent());
        submitted.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        realRepo.save(submitted);

        // Persists normally until the payment reaches SUCCEEDED — i.e. until the moment
        // after settle() has already appended the ledger entry. Then it throws, as a crash
        // between the two writes would.
        PaymentRepository failsRightAfterTheLedgerWrite = new PaymentRepository() {
            @Override
            public void save(Payment payment) {
                if (payment.state() == PaymentState.SUCCEEDED) {
                    throw new IllegalStateException("crash between the ledger write and the payment write");
                }
                realRepo.save(payment);
            }

            @Override
            public Optional<Payment> findByReference(ReferenceId ref) {
                return realRepo.findByReference(ref);
            }

            @Override
            public Optional<Payment> findByReferenceForUpdate(ReferenceId ref) {
                return realRepo.findByReferenceForUpdate(ref);
            }

            @Override
            public java.util.List<Payment> findEscalated() {
                return realRepo.findEscalated();
            }
        };

        ProviderAdapter adapter = mock(ProviderAdapter.class);
        AdapterRegistry adapters = mock(AdapterRegistry.class);
        when(adapters.require(any())).thenReturn(adapter);
        try {
            when(adapter.query(any())).thenReturn(new ProviderStatus(
                    PaymentState.SUCCEEDED, "SUCCESSFUL", "txn-1", null, "", "{\"status\":\"SUCCESSFUL\"}"));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }

        SettlementService settlement = new SettlementService(failsRightAfterTheLedgerWrite, adapters, ledger, txManager);

        assertThatThrownBy(() -> settlement.confirm(ProviderId.of("mtn"), reference, PaymentTransition.Cause.CALLBACK))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ledger.entriesForReference(reference.toString())).isEmpty();

        Payment afterwards = realRepo.findByReference(reference).orElseThrow();
        assertThat(afterwards.state()).isEqualTo(PaymentState.SUBMITTED);
        assertThat(afterwards.history()).extracting(t -> t.to().name()).containsExactly("SUBMITTED");
    }

    @Test
    @DisplayName("a settlement that succeeds writes the entry and the SUCCEEDED transition together")
    void a_successful_settlement_writes_both() {
        PostgresLedger ledger = new PostgresLedger(jdbc, txManager);
        PostgresPaymentRepository repo = new PostgresPaymentRepository(jdbc, new ObjectMapper());

        ReferenceId reference = ReferenceId.newReference();
        Payment submitted = Payment.create(reference, ProviderId.of("mtn"), "merchant-1", intent());
        submitted.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        repo.save(submitted);

        ProviderAdapter adapter = mock(ProviderAdapter.class);
        AdapterRegistry adapters = mock(AdapterRegistry.class);
        when(adapters.require(any())).thenReturn(adapter);
        try {
            when(adapter.query(any())).thenReturn(new ProviderStatus(
                    PaymentState.SUCCEEDED, "SUCCESSFUL", "txn-1", null, "", "{\"status\":\"SUCCESSFUL\"}"));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }

        new SettlementService(repo, adapters, ledger, txManager)
                .confirm(ProviderId.of("mtn"), reference, PaymentTransition.Cause.CALLBACK);

        assertThat(ledger.entriesForReference(reference.toString())).hasSize(1);
        Payment settled = repo.findByReference(reference).orElseThrow();
        assertThat(settled.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(settled.providerTransactionId()).isEqualTo("txn-1");
    }
}

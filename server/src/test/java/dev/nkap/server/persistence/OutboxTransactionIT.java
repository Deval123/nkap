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
import dev.nkap.server.outbox.Outbox;
import dev.nkap.server.outbox.OutboxEvent;
import dev.nkap.server.outbox.OutboxNotifier;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentRepository;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.payment.SettlementService;
import dev.nkap.server.provider.AdapterRegistry;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import dev.nkap.server.webhook.WebhookEndpoint;
import dev.nkap.server.webhook.WebhookEndpointStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The property the whole slice exists for (ADR 0003, issue #77), and the first test this
 * slice's plan asked to be written: <strong>the outbox row and the payment's new state
 * commit together, or neither does.</strong> A settlement that succeeded but whose event was
 * lost is a merchant never told they were paid; forcing the outbox insert to fail and
 * finding the payment still {@code SUBMITTED} afterwards is what proves that cannot happen.
 *
 * <p>Same shape as {@code SettlementTransactionIT} — a real PostgreSQL transaction is the
 * only boundary that can actually demonstrate this; a {@code HashMap} commits nothing to roll
 * back. There, the ledger write was made to fail after the fact. Here, {@link Outbox#append}
 * is made to fail instead, to prove the coupling holds from the other side too: it is not
 * only the ledger that the payment's state depends on now, it is the outbox as well.
 */
@ExtendWith(DockerAvailable.class)
class OutboxTransactionIT {

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;

    @BeforeAll
    static void connect() {
        jdbc = PostgresDatabase.shared().jdbcTemplate();
        txManager = PostgresDatabase.shared().transactionManager();
    }

    private static PaymentIntent intent() {
        return new PaymentIntent(Capability.Operation.COLLECT, Money.of(5_000, Currency.EUR),
                "46733123453", "rent", "march", Map.of());
    }

    @Test
    @DisplayName("a settlement whose outbox insert fails leaves neither the event nor the SUCCEEDED transition")
    void a_failed_outbox_insert_rolls_back_the_settlement_too() {
        PostgresLedger ledger = new PostgresLedger(jdbc, txManager);
        PostgresPaymentRepository payments = new PostgresPaymentRepository(jdbc, new ObjectMapper());

        ReferenceId reference = ReferenceId.newReference();
        Payment submitted = Payment.create(reference, ProviderId.of("mtn"), "merchant-1", intent());
        submitted.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payments.save(submitted);

        // A registered endpoint is what makes OutboxNotifier attempt the write at all — a
        // merchant with none is the "nothing to notify" case OutboxNotifierTest covers, and
        // proves nothing about the transaction.
        WebhookEndpointStore endpoints = mock(WebhookEndpointStore.class);
        when(endpoints.find("merchant-1")).thenReturn(Optional.of(
                new WebhookEndpoint(UUID.randomUUID(), "merchant-1",
                        "https://merchant.example/hooks", "whsec_test", Instant.now())));

        Outbox alwaysFails = event -> {
            throw new IllegalStateException("the outbox is unreachable, as it would be on a disk-full node");
        };
        OutboxNotifier notifier = new OutboxNotifier(alwaysFails, endpoints, new ObjectMapper());

        ProviderAdapter adapter = mock(ProviderAdapter.class);
        AdapterRegistry adapters = mock(AdapterRegistry.class);
        when(adapters.require(any())).thenReturn(adapter);
        try {
            when(adapter.query(any(), any())).thenReturn(new ProviderStatus(
                    PaymentState.SUCCEEDED, "SUCCESSFUL", "txn-1", null, "", "{\"status\":\"SUCCESSFUL\"}"));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }

        SettlementService settlement = new SettlementService(payments, adapters, ledger, notifier, txManager);

        assertThatThrownBy(() -> settlement.confirm(ProviderId.of("mtn"), reference, PaymentTransition.Cause.CALLBACK))
                .isInstanceOf(IllegalStateException.class);

        // The ledger entry rolled back too — settle() ran inside the same transaction as
        // the outbox write that failed after it.
        assertThat(ledger.entriesForReference(reference.toString())).isEmpty();

        Payment afterwards = payments.findByReference(reference).orElseThrow();
        assertThat(afterwards.state()).isEqualTo(PaymentState.SUBMITTED);
        assertThat(afterwards.history()).extracting(t -> t.to().name()).containsExactly("SUBMITTED");
    }

    @Test
    @DisplayName("a settlement that succeeds writes the ledger entry, the outbox event and the SUCCEEDED transition together")
    void a_successful_settlement_writes_all_three() {
        PostgresLedger ledger = new PostgresLedger(jdbc, txManager);
        PostgresPaymentRepository payments = new PostgresPaymentRepository(jdbc, new ObjectMapper());

        ReferenceId reference = ReferenceId.newReference();
        Payment submitted = Payment.create(reference, ProviderId.of("mtn"), "merchant-1", intent());
        submitted.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payments.save(submitted);

        WebhookEndpointStore endpoints = mock(WebhookEndpointStore.class);
        when(endpoints.find("merchant-1")).thenReturn(Optional.of(
                new WebhookEndpoint(UUID.randomUUID(), "merchant-1",
                        "https://merchant.example/hooks", "whsec_test", Instant.now())));

        List<OutboxEvent> written = new CopyOnWriteArrayList<>();
        Outbox recording = written::add;
        OutboxNotifier notifier = new OutboxNotifier(recording, endpoints, new ObjectMapper());

        ProviderAdapter adapter = mock(ProviderAdapter.class);
        AdapterRegistry adapters = mock(AdapterRegistry.class);
        when(adapters.require(any())).thenReturn(adapter);
        try {
            when(adapter.query(any(), any())).thenReturn(new ProviderStatus(
                    PaymentState.SUCCEEDED, "SUCCESSFUL", "txn-1", null, "", "{\"status\":\"SUCCESSFUL\"}"));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }

        new SettlementService(payments, adapters, ledger, notifier, txManager)
                .confirm(ProviderId.of("mtn"), reference, PaymentTransition.Cause.CALLBACK);

        assertThat(ledger.entriesForReference(reference.toString())).hasSize(1);
        assertThat(written).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("payment.succeeded");
            assertThat(event.merchantId()).isEqualTo("merchant-1");
            assertThat(event.payload()).contains(reference.toString());
        });
        Payment settled = payments.findByReference(reference).orElseThrow();
        assertThat(settled.state()).isEqualTo(PaymentState.SUCCEEDED);
    }
}

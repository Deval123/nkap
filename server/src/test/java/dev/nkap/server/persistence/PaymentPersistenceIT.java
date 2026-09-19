package dev.nkap.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.idempotency.IdempotencyKey;
import dev.nkap.core.idempotency.IdempotentOutcome;
import dev.nkap.core.idempotency.RequestFingerprint;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A payment, its history and its idempotency record are written to PostgreSQL and read
 * back — by fresh objects, the way a restart would — identical. And the payment history is
 * append-only from the application's own connection, like the ledger.
 */
@ExtendWith(DockerAvailable.class)
class PaymentPersistenceIT {

    private static JdbcTemplate jdbc;
    private static PostgresPaymentRepository payments;
    private static PostgresIdempotencyStore idempotency;

    @BeforeAll
    static void connect() {
        jdbc = PostgresDatabase.shared().jdbcTemplate();
        payments = new PostgresPaymentRepository(jdbc, new ObjectMapper());
        idempotency = new PostgresIdempotencyStore(jdbc);
    }

    private static PaymentIntent intent() {
        return new PaymentIntent(Capability.Operation.COLLECT, Money.of(5_000, Currency.EUR),
                "46733123453", "rent", "march", Map.of());
    }

    @Test
    @DisplayName("a payment, its history and its idempotency record survive a restart, read back identical")
    void state_survives_a_restart() {
        ReferenceId reference = ReferenceId.newReference();
        Payment written = Payment.create(reference, ProviderId.of("mtn"), "merchant-1", intent());
        written.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "202 accepted");
        written.applyTransition(PaymentState.SUCCEEDED, PaymentTransition.Cause.CALLBACK, "SUCCESSFUL", "", "{\"status\":\"SUCCESSFUL\"}");
        written.recordProviderReference("op-ref");
        written.recordProviderTransactionId("txn-1");
        payments.save(written);

        IdempotencyKey key = new IdempotencyKey("merchant-1", UUID.randomUUID().toString());
        idempotency.begin(key, RequestFingerprint.of("the-body"));
        idempotency.complete(key, "the-stored-response");

        // "restart": brand-new objects over the same database.
        Payment read = new PostgresPaymentRepository(jdbc, new ObjectMapper()).findByReference(reference).orElseThrow();

        assertThat(read.reference()).isEqualTo(reference);
        assertThat(read.provider().toString()).isEqualTo("mtn");
        assertThat(read.merchantId()).isEqualTo("merchant-1");
        assertThat(read.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(read.providerReference()).isEqualTo("op-ref");
        assertThat(read.providerTransactionId()).isEqualTo("txn-1");
        assertThat(read.intent().operation()).isEqualTo(Capability.Operation.COLLECT);
        assertThat(read.intent().amount()).isEqualTo(Money.of(5_000, Currency.EUR));
        assertThat(read.intent().counterpartyMsisdn()).isEqualTo("46733123453");
        // Instant.now() carries nanoseconds; timestamptz keeps microseconds and rounds to the
        // nearest, so the round-trip can differ from the original by up to half a microsecond.
        // What "read back identical" means for a timestamp is "to the precision the column has".
        assertThat(read.createdAt()).isCloseTo(written.createdAt(), within(1, ChronoUnit.MICROS));
        assertThat(read.history()).hasSize(2);
        assertThat(read.history().get(0).from()).isEqualTo(PaymentState.CREATED);
        assertThat(read.history().get(0).to()).isEqualTo(PaymentState.SUBMITTED);
        assertThat(read.history().get(1).to()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(read.history().get(1).cause()).isEqualTo(PaymentTransition.Cause.CALLBACK);
        assertThat(read.history().get(1).operatorCode()).isEqualTo("SUCCESSFUL");

        IdempotentOutcome replayed = new PostgresIdempotencyStore(jdbc).begin(key, RequestFingerprint.of("the-body"));
        assertThat(replayed).isInstanceOfSatisfying(IdempotentOutcome.Replay.class,
                r -> assertThat(r.storedResponse()).isEqualTo("the-stored-response"));
    }

    @Test
    @DisplayName("re-saving a payment inserts only the history rows that are new")
    void save_is_idempotent_on_history() {
        ReferenceId reference = ReferenceId.newReference();
        Payment payment = Payment.create(reference, ProviderId.of("mtn"), "merchant-1", intent());
        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payments.save(payment);
        payments.save(payment);
        payment.applyTransition(PaymentState.SUCCEEDED, PaymentTransition.Cause.CALLBACK, "SUCCESSFUL", "", "");
        payments.save(payment);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transition WHERE payment_reference = ?",
                Integer.class, reference.value())).isEqualTo(2);
    }

    @Test
    @DisplayName("a recorded payment transition cannot be updated or deleted from the application's own connection")
    void payment_history_is_append_only() {
        ReferenceId reference = ReferenceId.newReference();
        Payment payment = Payment.create(reference, ProviderId.of("mtn"), "merchant-1", intent());
        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payments.save(payment);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payment_transition SET note = 'tampered' WHERE payment_reference = ?", reference.value()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM payment_transition WHERE payment_reference = ?", reference.value()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("findByProviderReference resolves the one payment recorded under a provider reference")
    void findByProviderReference_resolves_the_one_match() {
        ReferenceId reference = ReferenceId.newReference();
        Payment payment = Payment.create(reference, ProviderId.of("mtn"), "merchant-1", intent());
        payment.recordProviderReference("ws_CO_180920261803512708374149");
        payments.save(payment);

        assertThat(payments.findByProviderReference(ProviderId.of("mtn"), "ws_CO_180920261803512708374149"))
                .isPresent().get().extracting(Payment::reference).isEqualTo(reference);
    }

    @Test
    @DisplayName("findByProviderReference is empty for a provider reference no payment carries")
    void findByProviderReference_is_empty_for_no_match() {
        assertThat(payments.findByProviderReference(ProviderId.of("mtn"), "never-recorded-by-anything"))
                .isEmpty();
    }

    @Test
    @DisplayName("findByProviderReference refuses to guess when more than one payment shares one provider reference")
    void findByProviderReference_refuses_to_guess_between_more_than_one_match() {
        String sharedProviderReference = "shared-somehow-" + UUID.randomUUID();
        Payment first = Payment.create(ReferenceId.newReference(), ProviderId.of("mtn"), "merchant-1", intent());
        first.recordProviderReference(sharedProviderReference);
        payments.save(first);
        Payment second = Payment.create(ReferenceId.newReference(), ProviderId.of("mtn"), "merchant-1", intent());
        second.recordProviderReference(sharedProviderReference);
        payments.save(second);

        // provider_reference carries no uniqueness constraint -- this should not happen, and
        // when it does, the rule is refuse rather than pick one, exactly as when it does not
        // happen at all. Not IllegalArgumentException, not a guess: empty, same as no match.
        assertThat(payments.findByProviderReference(ProviderId.of("mtn"), sharedProviderReference))
                .isEmpty();
    }

    @Test
    @DisplayName("findByProviderReference is scoped to the provider: a match for another operator does not resolve")
    void findByProviderReference_is_scoped_to_provider() {
        Payment payment = Payment.create(ReferenceId.newReference(), ProviderId.of("mtn"), "merchant-1", intent());
        payment.recordProviderReference("op-ref-under-mtn");
        payments.save(payment);

        assertThat(payments.findByProviderReference(ProviderId.of("mtn-cg"), "op-ref-under-mtn"))
                .isEmpty();
    }
}

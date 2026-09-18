package dev.nkap.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Issue #122: nothing in the database used to say whether a payment was settled by a real
 * operator or by the simulator standing in for one. {@code payment.provider_base_url} (V9)
 * is the record; this is what would catch it being wrong.
 *
 * <p>The last test needs its own throwaway container, the way {@code MigrationRollbackIT}
 * does — {@link PostgresDatabase#shared()} is already migrated past V9 for the rest of the
 * suite, so it cannot show what a row created before V9 looks like once V9 has run.
 */
@ExtendWith(DockerAvailable.class)
class SettlementProvenanceIT {

    private static PaymentIntent intent() {
        return new PaymentIntent(Capability.Operation.COLLECT, Money.of(5_000, Currency.EUR),
                "46733123453", "", "", Map.of());
    }

    @Test
    @DisplayName("two payments settled through different installations carry different provenance, in the same database")
    void two_installations_are_distinguishable() {
        JdbcTemplate jdbc = PostgresDatabase.shared().jdbcTemplate();
        PostgresPaymentRepository payments = new PostgresPaymentRepository(jdbc, new ObjectMapper());

        Payment simulated = Payment.create(ReferenceId.newReference(), ProviderId.of("mtn-cm"), "merchant-1", intent());
        simulated.recordProviderBaseUrl("http://simulator:8081");
        payments.save(simulated);

        Payment real = Payment.create(ReferenceId.newReference(), ProviderId.of("mtn-cm"), "merchant-1", intent());
        real.recordProviderBaseUrl("https://sandbox.momodeveloper.mtn.com");
        payments.save(real);

        assertThat(payments.findByReference(simulated.reference()).orElseThrow().providerBaseUrl())
                .isEqualTo("http://simulator:8081");
        assertThat(payments.findByReference(real.reference()).orElseThrow().providerBaseUrl())
                .isEqualTo("https://sandbox.momodeveloper.mtn.com");
    }

    @Test
    @DisplayName("provider_base_url cannot be changed once a payment row exists")
    void provider_base_url_is_immutable() {
        JdbcTemplate jdbc = PostgresDatabase.shared().jdbcTemplate();
        PostgresPaymentRepository payments = new PostgresPaymentRepository(jdbc, new ObjectMapper());

        Payment payment = Payment.create(ReferenceId.newReference(), ProviderId.of("mtn-cm"), "merchant-1", intent());
        payment.recordProviderBaseUrl("http://simulator:8081");
        payments.save(payment);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payment SET provider_base_url = 'https://sandbox.momodeveloper.mtn.com' WHERE reference = ?",
                payment.reference().value()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("provider_base_url");

        // Unaffected by the refusal above -- an ordinary field on the same row still moves.
        payment.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payments.save(payment);
        assertThat(payments.findByReference(payment.reference()).orElseThrow().state()).isEqualTo(PaymentState.SUBMITTED);
    }

    @Test
    @DisplayName("a payment created before this migration keeps no provenance, and nothing invents one for it")
    void rows_that_predate_the_migration_carry_none() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresDatabase.IMAGE)) {
            postgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            dataSource.setDriverClassName("org.postgresql.Driver");
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target("8")
                    .load()
                    .migrate();

            UUID reference = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO payment (reference, provider, merchant_id, operation, amount_minor, currency,
                                         counterparty_msisdn, provider_options, state, created_at, updated_at)
                    VALUES (?, 'mtn-cm', 'merchant-1', 'COLLECT', 5000, 'EUR', '46733123453', '{}', 'SUCCEEDED',
                            now(), now())
                    """, reference);

            // V9 runs against a database that already has this row -- exactly the situation
            // the migration's own comment describes, not a fresh schema.
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            Payment rehydrated = new PostgresPaymentRepository(jdbc, new ObjectMapper())
                    .findByReference(new ReferenceId(reference)).orElseThrow();

            assertThat(rehydrated.providerBaseUrl()).as("nothing backfills a value for a pre-migration row").isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT provider_base_url FROM payment WHERE reference = ?", String.class, reference))
                    .as("the column itself is NULL, not a guessed default")
                    .isNull();
        }
    }
}

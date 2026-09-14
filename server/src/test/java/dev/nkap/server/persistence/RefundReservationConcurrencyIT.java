package dev.nkap.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
import dev.nkap.server.payment.RefundExceedsRemainingException;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Issue #84's first correction, proved rather than asserted in prose: {@code reserveRefund}'s
 * own atomic {@code UPDATE}, backed by the database {@code CHECK} (V8), is what refuses a
 * double refund — not a row lock taken somewhere above it. This test calls
 * {@link PostgresPaymentRepository#reserveRefund} directly, from several threads at once, and
 * takes <strong>no lock of its own</strong> — no {@code SELECT … FOR UPDATE}, no
 * {@code findByReferenceForUpdate}, nothing serialising the calls but the database itself. If
 * removing every application-level lock still left the total reserved exactly at the
 * collection's amount, the database was always the actual guarantee; if it did not, the
 * migration comment's claim was never true, which is exactly the defect this correction
 * exists to fix.
 *
 * <p>{@code RefundServiceTest} and {@code RefundApiIT} additionally prove the same property
 * through the ordinary application path, which also takes no explicit lock any more — this
 * class isolates the claim to the one method it is actually about.
 */
@ExtendWith(DockerAvailable.class)
class RefundReservationConcurrencyIT {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connect() {
        jdbc = PostgresDatabase.shared().jdbcTemplate();
    }

    @Test
    @DisplayName("reserveRefund's atomic UPDATE refuses an over-refund under real concurrency, with no lock taken anywhere in this test")
    void the_atomic_update_alone_prevents_a_double_refund() throws Exception {
        PostgresPaymentRepository repository = new PostgresPaymentRepository(jdbc, new ObjectMapper());
        ReferenceId original = ReferenceId.newReference();
        PaymentIntent intent = new PaymentIntent(Capability.Operation.COLLECT, Money.of(10_000, Currency.EUR),
                "46733123453", "rent", "march", Map.of());
        Payment collection = Payment.create(original, ProviderId.of("mtn"), "merchant-1", intent);
        collection.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        collection.applyTransition(PaymentState.SUCCEEDED, PaymentTransition.Cause.CALLBACK, "SUCCESSFUL", "", "");
        repository.save(collection);

        int attempts = 8;
        long each = 2_000; // 8 x 2000 = 16000 against a 10000 collection: at most 5 can fit
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    // No SELECT ... FOR UPDATE, no findByReferenceForUpdate -- reserveRefund
                    // is called bare, exactly as it would be if a future caller forgot to
                    // take a lock this method no longer depends on.
                    repository.reserveRefund(original, Money.of(each, Currency.EUR));
                    return true;
                } catch (RefundExceedsRemainingException refused) {
                    return false;
                }
            }));
        }
        start.countDown();

        long succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).as("10000 / 2000 fits exactly five times, never six").isEqualTo(5);
        Payment reloaded = repository.findByReference(original).orElseThrow();
        assertThat(reloaded.refundedMinor())
                .as("the running total never exceeds what was ever collected, with no application lock involved")
                .isEqualTo(10_000L);
    }
}

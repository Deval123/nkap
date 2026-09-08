package dev.nkap.conformance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules every {@link ProviderAdapter} has to satisfy, whatever operator it speaks to.
 *
 * <p>A provider module extends this class from its own test code and returns a
 * {@link ConformanceHarness}. Every rule here was extracted from a test that already passes
 * against MTN — the kit is not a wishlist. Rules that are real but not yet drivable through
 * a harness (a code the adapter does not recognise mapping to {@code UNKNOWN}, which needs
 * an operator code a harness cannot express until issue #26) are left out entirely rather
 * than made optional.
 */
public abstract class ProviderAdapterConformanceTest {

    /** A fresh harness at the happy path. Called once per test; {@link #close()} at the end. */
    protected abstract ConformanceHarness newHarness();

    private ConformanceHarness harness;

    @BeforeEach
    void openHarness() {
        harness = newHarness();
    }

    @AfterEach
    void closeHarness() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("the same reference submitted twice produces one payment, not two")
    void a_reference_submitted_twice_is_idempotent() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        ReferenceId reference = ReferenceId.newReference();

        SubmitResult first = adapter.submit(harness.anIntent(), reference);
        SubmitResult again = adapter.submit(harness.anIntent(), reference);

        assertInstanceOf(SubmitResult.Acknowledged.class, first, "first submission");
        assertInstanceOf(SubmitResult.Acknowledged.class, again,
                "the same reference again is acknowledged, not rejected and not an error");
        assertSame(PaymentState.SUCCEEDED, adapter.query(reference).state(),
                "the reused reference resolves to a single outcome");
    }

    @Test
    @DisplayName("a call that does not answer yields UNKNOWN, never a failure — and a later query may resolve it")
    void a_call_that_does_not_answer_is_never_a_failure() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        harness.makeNextQuerySucceed();
        harness.makeSubmitNeverAnswer();
        ReferenceId reference = ReferenceId.newReference();

        // A submission that never answers is "I do not know" — the gateway maps this to UNKNOWN.
        assertThrows(ProviderUnavailableException.class,
                () -> adapter.submit(harness.anIntent(), reference));

        // The payment may still exist at the operator: a later query can resolve it.
        assertSame(PaymentState.SUCCEEDED, adapter.query(reference).state());
    }

    @Test
    @DisplayName("an outright refusal yields SubmitResult.Rejected, not an exception and not an acknowledgement")
    void an_outright_refusal_is_a_rejected_result() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        harness.makeSubmitRejected();

        SubmitResult result = adapter.submit(harness.anIntent(), ReferenceId.newReference());

        assertInstanceOf(SubmitResult.Rejected.class, result);
        // That it carries the operator's code is proven at unit level for MTN; it becomes a
        // kit assertion once a harness can express an operator code (issue #26).
    }

    @Test
    @DisplayName("a status that flaps is reported faithfully and cannot reopen a terminal payment")
    void a_flapping_status_never_reopens_a_terminal_payment() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        harness.makeStatusFlap();
        ReferenceId reference = ReferenceId.newReference();
        adapter.submit(harness.anIntent(), reference);

        ProviderStatus firstAnswer = adapter.query(reference);
        ProviderStatus secondAnswer = adapter.query(reference);

        // The adapter reports what it is told on each query and decides nothing.
        assertSame(PaymentState.SUCCEEDED, firstAnswer.state());
        assertSame(PaymentState.FAILED, secondAnswer.state());
        // The first answer is terminal, and the state machine forbids moving off it —
        // so the flap cannot reopen the payment, whatever the adapter is told next.
        assertTrue(firstAnswer.state().isTerminal());
        assertFalse(firstAnswer.state().canTransitionTo(secondAnswer.state()));
    }

    @Test
    @DisplayName("an expired credential mid-flight is renewed and the call retried with the same reference")
    void an_expired_credential_is_renewed_without_changing_the_reference() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        harness.expireCredentialsMidFlight();
        ReferenceId reference = ReferenceId.newReference();

        assertInstanceOf(SubmitResult.Acknowledged.class,
                adapter.submit(harness.anIntent(), reference));

        // Well past the credential's lifetime, a query on the SAME reference keeps
        // resolving. An adapter that generated a new reference on renewal would query the
        // wrong one; an adapter that failed to renew would throw.
        Instant deadline = Instant.now().plus(Duration.ofSeconds(6));
        int checks = 0;
        while (Instant.now().isBefore(deadline)) {
            assertSame(PaymentState.SUCCEEDED, adapter.query(reference).state(),
                    "a query must keep resolving after the credential lifetime");
            checks++;
            Thread.sleep(300);
        }
        assertTrue(checks >= 5, "expected repeated checks across the window, got " + checks);
    }

    @Test
    @DisplayName("a callback for a reference the gateway never issued is rejected, writing nothing")
    void an_untrusted_callback_is_rejected() {
        ProviderAdapter adapter = harness.adapter();

        assertThrows(UntrustedCallbackException.class,
                () -> adapter.parseCallback(harness.anUntrustedCallback()));
    }
}

package dev.nkap.conformance;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
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
 *
 * <p><strong>Not here, on purpose: routing by capability.</strong> Issue #67 considered a
 * rule that a reference submitted under one capability is answered under that capability.
 * It is a real rule and MTN now genuinely has two products to confuse, but it is not
 * extractable yet, for two reasons that are worth stating so the next contributor does not
 * rediscover them. First, {@link Capability} mixes operations ({@code COLLECT},
 * {@code DISBURSE}) with features ({@code BALANCE}, {@code STATEMENT}), so the kit cannot
 * say "for each capability the adapter declares" without hardcoding which of them can be
 * submitted — a second home for a distinction that belongs in {@code provider-api}.
 * Second, and decisive: the simulator keeps one reference store across both products, so a
 * reference submitted on the collections path is answered on the disbursements path too.
 * The rule would pass whether or not an adapter routed correctly, and a green test that
 * cannot fail is worse than an absent one. Partitioning the simulator per product is what
 * unblocks it.
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

    /**
     * The capability the rules exercise: the one {@link ConformanceHarness#anIntent()}
     * submits under. It is passed to {@code query} because the contract now carries it
     * (ADR 0008) — a reference is answered under the capability it was submitted under.
     */
    private Capability capabilityUnderTest() {
        return harness.anIntent().operation();
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
        assertSame(PaymentState.SUCCEEDED, adapter.query(reference, capabilityUnderTest()).state(),
                "the reused reference resolves to a single outcome");
    }

    @Test
    @DisplayName("a call that does not answer yields UNKNOWN, never a failure — and a later query may resolve it")
    void a_call_that_does_not_answer_is_never_a_failure() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        harness.makeSubmitNeverAnswer();
        ReferenceId reference = ReferenceId.newReference();

        // A submission that never answers is "I do not know" — the gateway maps this to UNKNOWN.
        assertThrows(ProviderUnavailableException.class,
                () -> adapter.submit(harness.anIntent(), reference));

        // The payment may still exist at the operator: a later query can resolve it.
        assertSame(PaymentState.SUCCEEDED, adapter.query(reference, capabilityUnderTest()).state());
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

        ProviderStatus firstAnswer = adapter.query(reference, capabilityUnderTest());
        ProviderStatus secondAnswer = adapter.query(reference, capabilityUnderTest());

        // The rule this kit can check is the adapter's: it reports what it is told on each
        // query, faithfully, and decides nothing. That the second answer cannot reopen the
        // payment is the state machine's doing, not the adapter's, and is proven in
        // PaymentStateTest — asserting it here would pass with any adapter at all.
        assertSame(PaymentState.SUCCEEDED, firstAnswer.state());
        assertSame(PaymentState.FAILED, secondAnswer.state());
    }

    @Test
    @DisplayName("an expired credential mid-flight is renewed and the call retried with the same reference")
    void an_expired_credential_is_renewed_without_changing_the_reference() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        harness.expireCredentialsMidFlight();
        ReferenceId reference = ReferenceId.newReference();

        assertInstanceOf(SubmitResult.Acknowledged.class,
                adapter.submit(harness.anIntent(), reference));

        // Wait past the lifetime the harness declared, so the credential is certainly stale,
        // then query the SAME reference. An adapter that generated a new reference on renewal
        // would query the wrong one; an adapter that failed to renew would throw. Three queries,
        // not a timed loop: the wait is the harness's business, the count is the kit's.
        Thread.sleep(harness.credentialLifetime().plusMillis(500).toMillis());
        for (int query = 1; query <= 3; query++) {
            assertSame(PaymentState.SUCCEEDED, adapter.query(reference, capabilityUnderTest()).state(),
                    "query " + query + " after the credential expired");
        }
    }

    @Test
    @DisplayName("a callback for a reference the gateway never issued is rejected, writing nothing")
    void an_untrusted_callback_is_rejected() {
        ProviderAdapter adapter = harness.adapter();

        assertThrows(UntrustedCallbackException.class,
                () -> adapter.parseCallback(harness.anUntrustedCallback()));
    }
}

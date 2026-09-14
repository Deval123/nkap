package dev.nkap.conformance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nkap.core.money.Currency;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.QuerySubject;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules every {@link ProviderAdapter} has to satisfy, whatever operator it speaks to.
 *
 * <p>A provider module extends this class from its own test code and returns a
 * {@link ConformanceHarness}. Every rule here was extracted from a test that already passes
 * against MTN — the kit is not a wishlist.
 *
 * <p><strong>Not here, on purpose: routing by capability.</strong> Issue #67 considered a
 * rule that a reference submitted under one capability is answered under that capability.
 * It is a real rule and MTN now genuinely has two products to confuse, but it is not
 * extractable yet. At the time, saying "for each capability the adapter declares" meant
 * hardcoding which members were submittable, because {@link Capability} mixed operations
 * with features — issue #70 closed that gap; {@link ProviderAdapter#operations()} is exactly
 * that iteration, with nothing to hardcode. What still blocks the rule is the simulator: it
 * keeps one reference store across both products, so a reference submitted on the
 * collections path is answered on the disbursements path too. The rule would pass whether or
 * not an adapter routed correctly, and a green test that cannot fail is worse than an absent
 * one. Partitioning the simulator per product is what unblocks it.
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
     * The operation the rules exercise: the one {@link ConformanceHarness#anIntent()}
     * submits under. It is passed to {@code query} because the contract now carries it
     * (ADR 0008) — a reference is answered under the operation it was submitted under.
     */
    private Capability.Operation operationUnderTest() {
        return harness.anIntent().operation();
    }

    /**
     * The provider reference a real caller would have on hand after {@code result} —
     * exactly what {@code submit} returned, blank if it never acknowledged. Used everywhere
     * a test submits before querying, so the kit passes what {@code SettlementService} would
     * have: never a synthetic blank standing in for a value that was actually returned.
     */
    private static String providerReferenceFrom(SubmitResult result) {
        return result instanceof SubmitResult.Acknowledged acknowledged ? acknowledged.providerReference() : "";
    }

    @Test
    @DisplayName("operations() declares the operation the harness submits under — asked by type, not by a switch on members")
    void operations_declares_what_the_harness_submits_under() {
        ProviderAdapter adapter = harness.adapter();

        assertTrue(adapter.capabilities().containsAll(adapter.operations()),
                "every declared operation is also a declared capability");
        assertTrue(adapter.operations().contains(operationUnderTest()),
                "the operation the harness submits under is among the declared operations");
    }

    /**
     * Declaring a feature and supporting it must be the same thing (issue #72): a capability
     * this adapter's own {@link ProviderAdapter#capabilities()} lists must be answered, and
     * one it does not list must be refused — never attempted and never guessed at. Both
     * directions are worth asserting in one rule, because either can fail independently: an
     * adapter that declares {@code BALANCE} but still throws is as much a bug as one that
     * does not declare it and answers anyway.
     *
     * <p>This is deliberately hardcoded to {@code BALANCE} and {@code HOLDER_VALIDATION} —
     * the two {@link Capability.Feature} members with a method on the contract to call — and
     * not written generically over every {@link Capability.Feature}. {@code STATEMENT} has no
     * such method (statement reconciliation is a host-side command, not an adapter call), so
     * there is nothing to tie it to without a second way to express "this feature means this
     * method" — a marker interface, a lookup table — which is exactly the kind of second
     * mechanism issue #70 spent a slice removing. Two explicit branches cost less than that.
     */
    @Test
    @DisplayName("declaring BALANCE or HOLDER_VALIDATION means answering it; not declaring either means refusing, never guessing")
    void feature_declaration_and_support_agree() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        Capability.Operation operation = operationUnderTest();
        Currency currency = harness.anIntent().amount().currency();
        String msisdn = harness.anIntent().counterpartyMsisdn();
        Set<Capability> declared = adapter.capabilities();

        if (declared.contains(Capability.Feature.BALANCE)) {
            assertDoesNotThrow(() -> adapter.balance(operation, currency),
                    "BALANCE is declared, so balance() must answer, not refuse");
        } else {
            assertThrows(UnsupportedOperationException.class, () -> adapter.balance(operation, currency),
                    "BALANCE is not declared, so balance() must refuse, not guess");
        }

        if (declared.contains(Capability.Feature.HOLDER_VALIDATION)) {
            assertDoesNotThrow(() -> adapter.validateHolder(operation, msisdn),
                    "HOLDER_VALIDATION is declared, so validateHolder() must answer, not refuse");
        } else {
            assertThrows(UnsupportedOperationException.class, () -> adapter.validateHolder(operation, msisdn),
                    "HOLDER_VALIDATION is not declared, so validateHolder() must refuse, not guess");
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
        assertSame(PaymentState.SUCCEEDED,
                adapter.query(new QuerySubject(reference, providerReferenceFrom(again)), operationUnderTest()).state(),
                "the reused reference resolves to a single outcome");
    }

    /**
     * ADR 0008, amendment (issue #96): {@code query} is handed whatever {@code submit}
     * recorded as the provider's own reference, not a synthetic blank one. No new harness
     * method is needed — the kit already submits and can keep what the acknowledgement
     * returned, exactly as {@code SettlementService} keeps what it persisted.
     *
     * <p>This is assertable even for an adapter whose operator returns no reference at all,
     * and asserting it for MTN is worth it precisely because MTN's own {@code providerReference}
     * is always blank: this rule is the one a future adapter — one that actually needs the
     * value to answer a query — would get wrong if the plumbing were missing, and MTN alone
     * would never fail it either way.
     */
    @Test
    @DisplayName("query is handed whatever submit recorded as the provider's own reference, blank or not")
    void query_is_handed_the_provider_reference_submit_recorded() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        ReferenceId reference = ReferenceId.newReference();

        SubmitResult result = adapter.submit(harness.anIntent(), reference);

        assertSame(PaymentState.SUCCEEDED,
                adapter.query(new QuerySubject(reference, providerReferenceFrom(result)), operationUnderTest()).state(),
                "a query built from exactly what submit returned must still resolve normally");
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
        // QuerySubject.of(reference) is correct here, not a shortcut: submit() threw, so
        // there is no SubmitResult and nothing a real caller could have kept.
        assertSame(PaymentState.SUCCEEDED, adapter.query(QuerySubject.of(reference), operationUnderTest()).state());
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
        SubmitResult submitted = adapter.submit(harness.anIntent(), reference);
        QuerySubject subject = new QuerySubject(reference, providerReferenceFrom(submitted));

        ProviderStatus firstAnswer = adapter.query(subject, operationUnderTest());
        ProviderStatus secondAnswer = adapter.query(subject, operationUnderTest());

        // The rule this kit can check is the adapter's: it reports what it is told on each
        // query, faithfully, and decides nothing. That the second answer cannot reopen the
        // payment is the state machine's doing, not the adapter's, and is proven in
        // PaymentStateTest — asserting it here would pass with any adapter at all.
        assertSame(PaymentState.SUCCEEDED, firstAnswer.state());
        assertSame(PaymentState.FAILED, secondAnswer.state());
    }

    @Test
    @DisplayName("an operator answer the adapter cannot map is UNKNOWN, never FAILED")
    void an_unrecognised_answer_is_unknown() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        harness.makeStatusUnrecognised();
        ReferenceId reference = ReferenceId.newReference();
        SubmitResult submitted = adapter.submit(harness.anIntent(), reference);

        // The sentence this whole project rests on: a token the adapter has never seen is
        // not a failure it can assert, so it is UNKNOWN — never a guess dressed up as FAILED.
        assertSame(PaymentState.UNKNOWN,
                adapter.query(new QuerySubject(reference, providerReferenceFrom(submitted)), operationUnderTest()).state());
    }

    @Test
    @DisplayName("an expired credential mid-flight is renewed and the call retried with the same reference")
    void an_expired_credential_is_renewed_without_changing_the_reference() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        harness.expireCredentialsMidFlight();
        ReferenceId reference = ReferenceId.newReference();

        SubmitResult submitted = adapter.submit(harness.anIntent(), reference);
        assertInstanceOf(SubmitResult.Acknowledged.class, submitted);

        // Wait past the lifetime the harness declared, so the credential is certainly stale,
        // then query the SAME reference. An adapter that generated a new reference on renewal
        // would query the wrong one; an adapter that failed to renew would throw. Three queries,
        // not a timed loop: the wait is the harness's business, the count is the kit's.
        Thread.sleep(harness.credentialLifetime().plusMillis(500).toMillis());
        QuerySubject subject = new QuerySubject(reference, providerReferenceFrom(submitted));
        for (int query = 1; query <= 3; query++) {
            assertSame(PaymentState.SUCCEEDED, adapter.query(subject, operationUnderTest()).state(),
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

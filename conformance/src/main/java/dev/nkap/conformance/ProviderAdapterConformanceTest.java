package dev.nkap.conformance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.QuerySubject;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

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
 * that iteration, with nothing to hardcode. What blocked the rule after that was the
 * simulator: it kept one reference store across both products, so a reference submitted on
 * the collections path was answered on the disbursements path too, and the rule would pass
 * whether or not an adapter routed correctly — a green test that cannot fail is worse than an
 * absent one. Issue #69 partitioned the simulator per product, so that is no longer true.
 * <strong>The rule itself is still to be written</strong> — closing that gap made it
 * extractable, not extracted; whoever picks it up next should start from #67, not from here.
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
     * <p>Written over {@link Capability.Feature#values()} rather than a member-by-member
     * {@code if}, on purpose (issue #74): every {@link Capability.Feature} member now has a
     * method on the contract to call — {@code STATEMENT} was the one exception, and it is
     * gone rather than given a method it does not need. The first assertion below is the
     * reason this is safe to write generically at all: it checks that the {@code checks} map
     * names every current member before the loop that follows ever runs, so a member added
     * to the enum without a matching entry there fails loudly, on that one line, instead of
     * silently going untested the way {@code STATEMENT} did the first time.
     */
    @Test
    @DisplayName("declaring a Feature means answering it; not declaring one means refusing, never guessing — and every Feature has a check")
    void feature_declaration_and_support_agree() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        Capability.Operation operation = operationUnderTest();
        Currency currency = harness.anIntent().amount().currency();
        String msisdn = harness.anIntent().counterpartyMsisdn();
        Set<Capability> declared = adapter.capabilities();

        Map<Capability.Feature, Executable> checks = Map.of(
                Capability.Feature.BALANCE, () -> adapter.balance(operation, currency),
                Capability.Feature.HOLDER_VALIDATION, () -> adapter.validateHolder(operation, msisdn));

        assertEquals(Set.of(Capability.Feature.values()), checks.keySet(),
                "every Capability.Feature member must have a check registered in the map above -- "
                        + "a member with none is issue #74 happening again");

        for (Capability.Feature feature : Capability.Feature.values()) {
            Executable check = checks.get(feature);
            if (declared.contains(feature)) {
                assertDoesNotThrow(check, feature + " is declared, so its method must answer, not refuse");
            } else {
                assertThrows(UnsupportedOperationException.class, check,
                        feature + " is not declared, so its method must refuse, not guess");
            }
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

    /**
     * ADR 0013: a currency the adapter's own profile does not settle in is something only
     * the adapter can know, so it must be refused as data ({@link SubmitResult.NotAttempted}),
     * not by calling the operator and letting it refuse instead.
     *
     * <p>Both halves matter, and only one is a return value: a rule that checked just the
     * result would pass an adapter that called the operator first and only then decided to
     * report {@code NotAttempted} — exactly the defect ADR 0013 closes. {@link
     * ConformanceHarness} has no hook built for observing "no HTTP call happened," so this
     * kit proves it the way every adapter it drives already commits to: an operator that was
     * genuinely never told about a reference answers a query on it with
     * {@link PaymentState#UNKNOWN} — never the happy path this harness starts every test
     * from, which is what a real submission would have produced. If a future adapter needs a
     * stronger check than that, it is a harness capability worth adding then, not invented
     * here on no adapter's real need.
     */
    @Test
    @DisplayName("an intent in a currency the adapter does not settle in is not attempted, and the operator is never asked")
    void a_currency_mismatch_is_not_attempted_and_never_reaches_the_operator() throws Exception {
        ProviderAdapter adapter = harness.adapter();
        PaymentIntent template = harness.anIntent();
        Currency unsettled = template.amount().currency() == Currency.XOF ? Currency.EUR : Currency.XOF;
        PaymentIntent mismatched = new PaymentIntent(template.operation(), Money.of(1000, unsettled),
                template.counterpartyMsisdn(), template.payerMessage(), template.payeeNote(), template.providerOptions());
        ReferenceId reference = ReferenceId.newReference();

        SubmitResult result = adapter.submit(mismatched, reference);

        assertInstanceOf(SubmitResult.NotAttempted.class, result);
        assertSame(PaymentState.UNKNOWN, adapter.query(QuerySubject.of(reference), operationUnderTest()).state(),
                "the operator must never have heard of this reference at all");
    }

    @Test
    @DisplayName("a callback the adapter cannot extract a usable reference from is rejected as unparseable")
    void an_untrusted_callback_is_rejected() {
        ProviderAdapter adapter = harness.adapter();

        // Only one of UntrustedCallbackException's meanings, deliberately (see
        // ConformanceHarness#anUntrustedCallback's own javadoc): recognizing a well-formed
        // but unfamiliar reference is CallbackController's job, not an adapter's, and this
        // kit has no way to drive an adapter that only ever names the operator's own
        // reference (ADR 0011 §2, issue #149) — no adapter here is one.
        assertThrows(UntrustedCallbackException.class,
                () -> adapter.parseCallback(harness.anUntrustedCallback()));
    }
}

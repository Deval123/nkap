package dev.nkap.conformance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.CallbackEvent;
import dev.nkap.provider.Capability;
import dev.nkap.provider.HolderStatus;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.QuerySubject;
import dev.nkap.provider.RawCallback;
import dev.nkap.provider.Resolution;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.UntrustedCallbackException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * Issue #199's own requirement on itself: the {@code CALLBACK} half of
 * {@link ProviderAdapterConformanceTest#a_call_that_does_not_answer_is_never_a_failure()} must
 * be able to fail, or it certifies nothing. This drives that rule against a stub adapter that
 * declares only {@link Resolution#CALLBACK} and whose callback names neither the reference Nkap
 * chose nor a provider reference {@code query} can do anything with — the one shape the rule
 * names as unresolved — and checks the rule reports exactly that, instead of passing because
 * something merely came back from {@code parseCallback} without throwing.
 */
class UnresolvableCallbackOperatorTest {

    @Test
    void a_callback_naming_neither_a_usable_reference_fails_the_rule() throws Throwable {
        ProviderAdapterConformanceTest rule = new ProviderAdapterConformanceTest() {
            @Override
            protected ConformanceHarness newHarness() {
                return new UnresolvableCallbackHarness();
            }
        };

        rule.openHarness();
        try {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    rule::a_call_that_does_not_answer_is_never_a_failure);
            assertTrue(failure.getMessage().contains("neither the reference nor the provider reference resolved the payment"),
                    () -> "expected the rule's own failure message, got: " + failure.getMessage());
        } finally {
            rule.closeHarness();
        }
    }

    /** A submission that never answers, resolved only by a callback that never resolves. */
    private static final class UnresolvableCallbackHarness implements ConformanceHarness {

        private final UnresolvableCallbackOperator adapter = new UnresolvableCallbackOperator();

        @Override
        public ProviderAdapter adapter() {
            return adapter;
        }

        @Override
        public PaymentIntent anIntent() {
            return new PaymentIntent(Capability.Operation.COLLECT, Money.of(1_000, Currency.XAF),
                    "670000000", "pay", "note", Map.of());
        }

        @Override
        public void makeSubmitNeverAnswer() {
            // already how the stub adapter always behaves below
        }

        @Override
        public void makeSubmitRejected() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void makeNextQuerySucceed() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void makeStatusFlap() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void makeStatusUnrecognised() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void expireCredentialsMidFlight() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public Duration credentialLifetime() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public RawCallback anUntrustedCallback() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public RawCallback aDeliveredCallback() {
            // Names an operator reference, but one the stub's own query() below can never
            // resolve -- the shape the rule must catch, not the untrusted-body shape
            // an_untrusted_callback_is_rejected already covers.
            return new RawCallback(Map.of(), "{\"providerReference\":\"opaque-1\"}");
        }

        @Override
        public int submissionsReceived() {
            return adapter.processed.get();
        }

        @Override
        public void close() {
            // nothing to release
        }
    }

    /** Declares CALLBACK alone; its callback names only a provider reference query never resolves. */
    private static final class UnresolvableCallbackOperator implements ProviderAdapter {

        private final AtomicInteger processed = new AtomicInteger();

        @Override
        public ProviderId id() {
            return ProviderId.of("unresolvable-callback-stub");
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.Operation.COLLECT);
        }

        @Override
        public Set<Resolution> resolves() {
            return Resolution.of(Resolution.CALLBACK);
        }

        @Override
        public SubmitResult submit(PaymentIntent intent, ReferenceId reference) throws ProviderUnavailableException {
            processed.incrementAndGet();
            throw new ProviderUnavailableException("simulated: this submission never answers");
        }

        @Override
        public ProviderStatus query(QuerySubject subject, Capability.Operation capability) {
            // Never resolves anything, whatever it is asked about -- the property this stub
            // exists to exercise.
            return ProviderStatus.unknown("", "");
        }

        @Override
        public CallbackEvent parseCallback(RawCallback callback) throws UntrustedCallbackException {
            return CallbackEvent.unattributed("opaque-1", new ProviderStatus(PaymentState.SUCCEEDED, "", "", null, "", ""));
        }

        @Override
        public Money balance(Capability.Operation capability, Currency currency) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public HolderStatus validateHolder(Capability.Operation capability, String msisdn) {
            throw new UnsupportedOperationException("not exercised by this test");
        }
    }
}

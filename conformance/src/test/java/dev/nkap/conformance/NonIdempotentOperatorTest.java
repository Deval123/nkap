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
 * Issue #186: no adapter in this repository behaves this way — MTN is idempotent, so
 * {@code MtnConformanceTest} passing proves only that MTN does not trip the new assertion in
 * {@link ProviderAdapterConformanceTest#a_reused_reference_is_safe_and_a_disagreeing_provider_reference_is_caught()}.
 * This drives that rule directly against a stub adapter shaped the way Safaricom's STK Push is
 * documented to behave (two submissions of one gateway reference, two different, non-blank
 * provider references — proof the operator created a second payment) and checks the rule now
 * fails instead of reporting a single, safe outcome.
 */
class NonIdempotentOperatorTest {

    @Test
    void a_second_provider_reference_that_differs_from_the_first_fails_the_rule() throws Throwable {
        ProviderAdapterConformanceTest rule = new ProviderAdapterConformanceTest() {
            @Override
            protected ConformanceHarness newHarness() {
                return new NonIdempotentOperatorHarness();
            }
        };

        rule.openHarness();
        try {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    rule::a_reused_reference_is_safe_and_a_disagreeing_provider_reference_is_caught);
            String message = failure.getMessage();
            assertTrue(message.contains("proof of two payments"),
                    () -> "expected the new assertion's message, got: " + message);
            assertTrue(message.contains("checkout-request-1") && message.contains("checkout-request-2"),
                    () -> "expected both provider references in the failure, got: " + message);
        } finally {
            rule.closeHarness();
        }
    }

    /** Two submissions of one reference each acknowledge, each with a different, non-blank id. */
    private static final class NonIdempotentOperatorHarness implements ConformanceHarness {

        private final NonIdempotentOperator adapter = new NonIdempotentOperator();

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
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void makeSubmitRejected() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void makeNextQuerySucceed() {
            // already the default below
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
        public void close() {
            // nothing to release
        }
    }

    /** Acknowledges every submission with a fresh, non-blank provider reference. */
    private static final class NonIdempotentOperator implements ProviderAdapter {

        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public ProviderId id() {
            return ProviderId.of("non-idempotent-stub");
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.Operation.COLLECT);
        }

        @Override
        public Set<Resolution> resolves() {
            return Resolution.of(Resolution.QUERY);
        }

        @Override
        public SubmitResult submit(PaymentIntent intent, ReferenceId reference) {
            String providerReference = "checkout-request-" + submissions.incrementAndGet();
            return new SubmitResult.Acknowledged(PaymentState.SUBMITTED, providerReference, "");
        }

        @Override
        public ProviderStatus query(QuerySubject subject, Capability.Operation capability) {
            return new ProviderStatus(PaymentState.SUCCEEDED, "", subject.providerReference(), null, "", "");
        }

        @Override
        public CallbackEvent parseCallback(RawCallback callback) throws UntrustedCallbackException {
            throw new UnsupportedOperationException("not exercised by this test");
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

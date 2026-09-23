package dev.nkap.conformance;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
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
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A harness over an in-memory operator, for the kit's own tests of whether a rule can fail.
 * The operator counts every submission it processes, the way the simulator does, and mints a
 * fresh provider reference for each one — it does not deduplicate. Each test's stub adapter
 * decides how it talks to it: faithfully, or making the mistake the rule under test exists
 * to catch.
 */
class StubOperatorHarness implements ConformanceHarness {

    /** The operator: counts what it processes, and never deduplicates. */
    static final class Operator {

        final AtomicInteger processed = new AtomicInteger();
        volatile boolean silent;

        /** Processes one submission and answers it — or, when silent, processes it and never answers. */
        SubmitResult process() throws ProviderUnavailableException {
            int n = processed.incrementAndGet();
            if (silent) {
                throw new ProviderUnavailableException("simulated: processed, then no answer");
            }
            return new SubmitResult.Acknowledged(PaymentState.SUBMITTED, "checkout-request-" + n, "");
        }
    }

    /** A stub adapter that settles only XAF and queries by the operator's reference. */
    abstract static class StubAdapter implements ProviderAdapter {

        final Operator operator;

        StubAdapter(Operator operator) {
            this.operator = operator;
        }

        @Override
        public ProviderId id() {
            return ProviderId.of("stub");
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
        public ProviderStatus query(QuerySubject subject, Capability.Operation capability)
                throws ProviderUnavailableException {
            return ProviderStatus.unknown("", "");
        }

        @Override
        public CallbackEvent parseCallback(RawCallback callback) {
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

    private final Operator operator;
    private final ProviderAdapter adapter;

    StubOperatorHarness(Operator operator, ProviderAdapter adapter) {
        this.operator = operator;
        this.adapter = adapter;
    }

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
        operator.silent = true;
    }

    @Override
    public int submissionsReceived() {
        return operator.processed.get();
    }

    @Override
    public void makeSubmitRejected() {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void makeNextQuerySucceed() {
        // nothing: no rule these stubs run depends on the query's answer
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
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public void close() {
        // nothing to release
    }

    /** Runs {@code rule} against a fresh harness over {@code harness}, and closes it. */
    static void run(ConformanceHarness harness, ThrowingRule rule) throws Throwable {
        ProviderAdapterConformanceTest kit = new ProviderAdapterConformanceTest() {
            @Override
            protected ConformanceHarness newHarness() {
                return harness;
            }
        };
        kit.openHarness();
        try {
            rule.run(kit);
        } finally {
            kit.closeHarness();
        }
    }

    @FunctionalInterface
    interface ThrowingRule {
        void run(ProviderAdapterConformanceTest kit) throws Throwable;
    }
}

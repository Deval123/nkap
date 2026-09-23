package dev.nkap.conformance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nkap.core.money.Currency;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.QuerySubject;
import dev.nkap.provider.Resolution;
import dev.nkap.provider.SubmitResult;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * {@link ProviderAdapterConformanceTest#a_currency_mismatch_is_not_attempted_and_never_reaches_the_operator()}
 * must be able to fail on each of the things it now asserts (issue #175): that the operator
 * never heard of the submission, and that an adapter which cannot form a query from Nkap's
 * reference alone says so rather than answering with a status.
 */
class CurrencyGuardTest {

    @Test
    @DisplayName("an adapter that refuses the currency itself passes, with or without QUERY")
    void a_faithful_guard_passes() {
        assertDoesNotThrow(() -> run(new GuardsCurrency(Resolution.QUERY)));
        assertDoesNotThrow(() -> run(new GuardsCurrency(Resolution.CALLBACK)));
    }

    @Test
    @DisplayName("an adapter that calls the operator anyway and then reports NotAttempted fails, for the call")
    void an_adapter_calling_the_operator_anyway_fails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> run(new CallsAnyway()));

        String message = failure.getMessage();
        assertTrue(message.contains("must never reach the operator"), () -> "expected the rule's own message, got: " + message);
    }

    @Test
    @DisplayName("an adapter that cannot query by Nkap's reference but answers a status anyway fails")
    void a_callback_only_adapter_answering_a_status_fails() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> run(new AnswersAStatusAnyway()));

        String message = failure.getMessage();
        assertTrue(message.contains("not return a status"), () -> "expected the rule's own message, got: " + message);
    }

    private static void run(StubOperatorHarness.StubAdapter adapter) throws Throwable {
        StubOperatorHarness.run(new StubOperatorHarness(adapter.operator, adapter),
                ProviderAdapterConformanceTest::a_currency_mismatch_is_not_attempted_and_never_reaches_the_operator);
    }

    /** Refuses a currency other than XAF without calling the operator. */
    private static class GuardsCurrency extends StubOperatorHarness.StubAdapter {

        private final Resolution resolution;

        GuardsCurrency(Resolution resolution) {
            super(new StubOperatorHarness.Operator());
            this.resolution = resolution;
        }

        @Override
        public Set<Resolution> resolves() {
            return Resolution.of(resolution);
        }

        @Override
        public SubmitResult submit(PaymentIntent intent, ReferenceId reference) throws ProviderUnavailableException {
            if (intent.amount().currency() != Currency.XAF) {
                return new SubmitResult.NotAttempted("this profile settles XAF only");
            }
            return operator.process();
        }

        /** Only an operator reference can be asked about; Nkap's alone cannot. */
        @Override
        public ProviderStatus query(QuerySubject subject, Capability.Operation capability)
                throws ProviderUnavailableException {
            if (resolution != Resolution.QUERY && subject.providerReference().isBlank()) {
                throw new ProviderUnavailableException("no operator reference to ask with");
            }
            return ProviderStatus.unknown("", "");
        }
    }

    /** Sends the submission, lets the operator process it, then reports that it never tried. */
    private static final class CallsAnyway extends GuardsCurrency {

        CallsAnyway() {
            super(Resolution.QUERY);
        }

        @Override
        public SubmitResult submit(PaymentIntent intent, ReferenceId reference) throws ProviderUnavailableException {
            operator.process();
            return new SubmitResult.NotAttempted("this profile settles XAF only");
        }
    }

    /** Declares only CALLBACK, yet answers UNKNOWN to a query it had nothing to ask with. */
    private static final class AnswersAStatusAnyway extends GuardsCurrency {

        AnswersAStatusAnyway() {
            super(Resolution.CALLBACK);
        }

        @Override
        public ProviderStatus query(QuerySubject subject, Capability.Operation capability) {
            return ProviderStatus.unknown("", "");
        }
    }
}

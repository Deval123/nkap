package dev.nkap.conformance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.SubmitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * The no-resend rules ({@link ProviderAdapterConformanceTest#an_adapter_never_resends_an_accepted_submission()}
 * and its siblings, ADR 0014 decision 4, issue #175) against an operator shaped the way
 * Safaricom's STK Push is observed to behave: it does not deduplicate, and mints a fresh
 * provider reference for every submission it processes.
 *
 * <p>This file once proved the opposite: that such an operator <em>failed</em> the kit's
 * idempotency rule, because that rule submitted one reference twice and blamed the adapter for
 * the second payment it had caused itself. Now the kit submits once and counts what the
 * operator processed, so an operator that does not deduplicate passes with an adapter that
 * behaves — and an adapter that resends fails, whatever its operator does.
 */
class NonIdempotentOperatorTest {

    @Test
    @DisplayName("an operator that does not deduplicate passes the no-resend rules with an adapter that submits once")
    void a_non_deduplicating_operator_passes_with_a_faithful_adapter() {
        assertDoesNotThrow(() -> StubOperatorHarness.run(harnessOver(SubmitsOnce::new),
                ProviderAdapterConformanceTest::an_adapter_never_resends_an_accepted_submission));
        assertDoesNotThrow(() -> StubOperatorHarness.run(harnessOver(SubmitsOnce::new),
                ProviderAdapterConformanceTest::an_adapter_never_resends_a_submission_that_never_answered));
    }

    @Test
    @DisplayName("an adapter that resends a submission that never answered fails the rule, for the resend")
    void an_adapter_resending_on_timeout_fails_the_rule() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> StubOperatorHarness.run(harnessOver(ResendsOnTimeout::new),
                        ProviderAdapterConformanceTest::an_adapter_never_resends_a_submission_that_never_answered));

        String message = failure.getMessage();
        assertTrue(message.contains("must not be resent"), () -> "expected the rule's own message, got: " + message);
        assertTrue(message.contains("expected: <1> but was: <2>"),
                () -> "expected the operator to have processed it twice, got: " + message);
    }

    private static StubOperatorHarness harnessOver(
            java.util.function.Function<StubOperatorHarness.Operator, StubOperatorHarness.StubAdapter> adapter) {
        StubOperatorHarness.Operator operator = new StubOperatorHarness.Operator();
        return new StubOperatorHarness(operator, adapter.apply(operator));
    }

    /** Hands each submission to the operator exactly once, whatever comes back. */
    private static final class SubmitsOnce extends StubOperatorHarness.StubAdapter {

        SubmitsOnce(StubOperatorHarness.Operator operator) {
            super(operator);
        }

        @Override
        public SubmitResult submit(PaymentIntent intent, ReferenceId reference) throws ProviderUnavailableException {
            return operator.process();
        }
    }

    /**
     * The mistake the rule exists to catch: when the operator does not answer, it tries again.
     * The operator had already processed the first attempt, so this is a second payment.
     */
    private static final class ResendsOnTimeout extends StubOperatorHarness.StubAdapter {

        ResendsOnTimeout(StubOperatorHarness.Operator operator) {
            super(operator);
        }

        @Override
        public SubmitResult submit(PaymentIntent intent, ReferenceId reference) throws ProviderUnavailableException {
            try {
                return operator.process();
            } catch (ProviderUnavailableException noAnswer) {
                return operator.process();
            }
        }
    }
}

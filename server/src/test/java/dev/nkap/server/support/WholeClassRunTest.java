package dev.nkap.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Both branches of {@link WholeClassRun}: a whole run runs the check, a partial run skips it and
 * says so. The callbacks are driven directly, as JUnit would call them, against fixture classes
 * whose tests nothing runs.
 */
class WholeClassRunTest {

    /** Three declared tests, one of them inherited. */
    static class Base {
        @Test
        void inherited() {
        }
    }

    static class ThreeTests extends Base {
        @Test
        void one() {
        }

        @Test
        void two() {
        }

        void notATest() {
        }
    }

    static class WithARepeatedTest {
        @RepeatedTest(3)
        void repeated() {
        }
    }

    private final AtomicInteger checks = new AtomicInteger();
    private final WholeClassRun run = new WholeClassRun("the fixture's coverage", checks::incrementAndGet);

    private static ExtensionContext contextFor(Class<?> testClass) {
        ExtensionContext context = mock(ExtensionContext.class);
        when(context.getRequiredTestClass()).thenAnswer(invocation -> testClass);
        return context;
    }

    @Test
    @DisplayName("declared tests are counted by reflection, inherited ones included, and nothing else")
    void declared_tests_are_counted() {
        assertThat(WholeClassRun.declaredTests(ThreeTests.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("when every declared test ran and passed, the check runs, once")
    void a_whole_run_runs_the_check() {
        ExtensionContext context = contextFor(ThreeTests.class);
        run.testSuccessful(context);
        run.testSuccessful(context);
        run.testDisabled(context, Optional.of("counted: it was selected"));

        try (LogCapture log = new LogCapture(WholeClassRun.class)) {
            run.afterAll(context);
            assertThat(log.events()).isEmpty();
        }
        assertThat(checks).hasValue(1);
    }

    @Test
    @DisplayName("when fewer tests ran than are declared, the check is skipped and one line says so, naming the check")
    void a_partial_run_skips_the_check_and_says_so() {
        ExtensionContext context = contextFor(ThreeTests.class);
        run.testSuccessful(context);

        try (LogCapture log = new LogCapture(WholeClassRun.class)) {
            run.afterAll(context);
            assertThat(log.events()).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                    .contains("ThreeTests: 1 of 3 tests ran, a partial run, so the fixture's coverage was not checked");
        }
        assertThat(checks).hasValue(0);
    }

    @Test
    @DisplayName("when the whole class ran but a test failed, the check is skipped and one line says why")
    void a_failed_test_skips_the_check_and_says_so() {
        ExtensionContext context = contextFor(ThreeTests.class);
        run.testSuccessful(context);
        run.testSuccessful(context);
        run.testFailed(context, new AssertionError("a failure"));

        try (LogCapture log = new LogCapture(WholeClassRun.class)) {
            run.afterAll(context);
            assertThat(log.events()).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                    .contains("1 of 3 tests failed, so the fixture's coverage was not checked");
        }
        assertThat(checks).hasValue(0);
    }

    @Test
    @DisplayName("a check that fails on a whole run fails the class: the skip is for partial runs only")
    void a_failing_check_fails_on_a_whole_run() {
        WholeClassRun failing = new WholeClassRun("a check", () -> {
            throw new AssertionError("gap found");
        });
        ExtensionContext context = contextFor(ThreeTests.class);
        for (int i = 0; i < 3; i++) {
            failing.testSuccessful(context);
        }

        assertThatThrownBy(() -> failing.afterAll(context)).isInstanceOf(AssertionError.class).hasMessage("gap found");
    }

    @Test
    @DisplayName("a class with a test it cannot count from its declaration is refused, not miscounted")
    void an_uncountable_test_is_refused() {
        assertThatThrownBy(() -> WholeClassRun.declaredTests(WithARepeatedTest.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WithARepeatedTest.repeated");
    }
}

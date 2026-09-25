package dev.nkap.server.support;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a check after a test class only when the whole class ran, and says so when it did not.
 *
 * <p>Some checks are about a class's tests taken together: "every documented status was produced
 * by one of them". Such a check is only true of a whole run. Run one test from an IDE and it fails
 * with nothing actually wrong, and a check that fails for nothing gets switched off. So this counts
 * the class's tests that finished, whether passed, failed, aborted or disabled, and compares that
 * with the number of {@link Test} methods the class and its superclasses declare, by reflection.
 *
 * <ul>
 *   <li>Every declared test finished, and none failed: the check runs, and a failure fails the
 *       class.</li>
 *   <li>Fewer finished, a partial run: the check is skipped, and one line is logged saying so.
 *       A silent skip would read as a pass.</li>
 *   <li>The whole class ran but a test failed or was aborted: the check is skipped too, and a
 *       warning says why. Such a test may have stopped before recording what the check reads, so
 *       the check would report a gap that is only that test's echo.</li>
 * </ul>
 *
 * <p><strong>An aborted test switches the check off while the build stays green.</strong> A test
 * aborted by an assumption ({@code assumeTrue} and the like) records nothing, so the check is
 * skipped as for a failure. But Surefire and Failsafe report an abort as a skip, not a failure, so
 * nothing else goes red, and the warning is the only trace that the check did not run. An
 * assumption in a class that uses this is therefore not free: it can turn the check off unseen.
 *
 * <p>A {@code @Disabled} test counts as finished, not failed. The run still counts as whole and
 * the check runs, so it reports whatever coverage the disabled test would have given. That is
 * the correct outcome: disabling a test must not hide what it proved.
 *
 * <p>Only {@code @Test} methods are counted. A class with parameterized, repeated or dynamic tests
 * would run more tests than it declares {@code @Test} methods, so it is refused rather than
 * miscounted.
 *
 * <p>Rejected, so they are not proposed again: gating the check on a system property only the
 * build sets makes it a CI-only check, weaker than it looks, since nobody sees it fail locally.
 * Asserting unconditionally produces a false failure every time someone runs one test.
 *
 * <p>Register it as a static field, so one instance sees the whole class:
 * {@code @RegisterExtension static final WholeClassRun WHOLE = new WholeClassRun("...", Check::run);}
 */
public final class WholeClassRun implements TestWatcher, AfterAllCallback {

    private static final Logger log = LoggerFactory.getLogger(WholeClassRun.class);

    private final String checkName;
    private final Runnable check;
    private final AtomicInteger finished = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    /**
     * @param checkName what the check establishes, as the skip line should name it
     * @param check     run after the class only when the whole class ran and passed
     */
    public WholeClassRun(String checkName, Runnable check) {
        this.checkName = Objects.requireNonNull(checkName, "checkName");
        this.check = Objects.requireNonNull(check, "check");
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        finished.incrementAndGet();
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        finished.incrementAndGet();
        failed.incrementAndGet();
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        finished.incrementAndGet();
        failed.incrementAndGet();
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        finished.incrementAndGet();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        int declared = declaredTests(testClass);
        int ran = finished.get();
        if (ran < declared) {
            log.info("{}: {} of {} tests ran, a partial run, so {} was not checked. Run the whole class to"
                    + " check it.", testClass.getSimpleName(), ran, declared, checkName);
            return;
        }
        if (failed.get() > 0) {
            log.warn("{}: {} of {} tests failed or were aborted, so {} was not checked; fix them first.",
                    testClass.getSimpleName(), failed.get(), declared, checkName);
            return;
        }
        check.run();
    }

    /** The {@link Test} methods {@code testClass} and its superclasses declare. */
    static int declaredTests(Class<?> testClass) {
        int count = 0;
        for (Class<?> type = testClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Test.class)) {
                    count++;
                } else if (isOtherTestTemplate(method)) {
                    throw new IllegalStateException(type.getSimpleName() + "." + method.getName()
                            + " is a test WholeClassRun cannot count from its declaration; it counts @Test only");
                }
            }
        }
        return count;
    }

    private static boolean isOtherTestTemplate(Method method) {
        for (var annotation : method.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.equals("org.junit.jupiter.params.ParameterizedTest")
                    || name.equals("org.junit.jupiter.api.RepeatedTest")
                    || name.equals("org.junit.jupiter.api.TestFactory")
                    || name.equals("org.junit.jupiter.api.TestTemplate")) {
                return true;
            }
        }
        return false;
    }
}

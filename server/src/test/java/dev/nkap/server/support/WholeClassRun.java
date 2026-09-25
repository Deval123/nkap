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
 *   <li>The whole class ran but a test failed: the check is skipped too, and the line says why. A
 *       failed test may have stopped before recording what the check reads, so the check would
 *       report a gap that is only that failure's echo. The failure itself already fails the
 *       build.</li>
 * </ul>
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
            log.info("{}: {} of {} tests failed, so {} was not checked; fix them first.",
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

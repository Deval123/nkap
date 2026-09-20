package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

/**
 * {@code RefundApiIT} and {@code DisbursementApiIT} declare identical
 * {@code @DynamicPropertySource} values, so they share one cached Spring context with a
 * live, scheduled {@code Reconciler}. {@code @DirtiesContext(classMode = AFTER_CLASS)} on
 * both is what closes that context -- and with it the scheduler -- once whichever of the two
 * runs last is done, which is what stops the reconciler from outliving the test class that
 * asked for it and sweeping later, unrelated fixtures (issue #162).
 * {@code ReconcilerWiringTest} proves that closing a context actually cancels its scheduled
 * pass; this test protects the other half of that fix -- that the annotation asking for the
 * close is still on both classes -- since nothing else here would fail if it were quietly
 * removed as apparent noise.
 */
class ReconcilerContextLifecycleTest {

    @Test
    @DisplayName("RefundApiIT and DisbursementApiIT close their shared context after the "
            + "class, so their scheduler cannot outlive it")
    void refund_and_disbursement_close_their_context_after_the_class() {
        assertClosesAfterClass(RefundApiIT.class);
        assertClosesAfterClass(DisbursementApiIT.class);
    }

    private void assertClosesAfterClass(Class<?> testClass) {
        DirtiesContext dirtiesContext = testClass.getAnnotation(DirtiesContext.class);
        assertThat(dirtiesContext)
                .as("%s must carry @DirtiesContext -- without it, its shared, scheduled "
                        + "Reconciler outlives the class (issue #162)", testClass.getSimpleName())
                .isNotNull();
        assertThat(dirtiesContext.classMode())
                .as("%s's @DirtiesContext must close the context once the class finishes, "
                        + "not per method (which would defeat the sharing it is meant to "
                        + "allow) and not never", testClass.getSimpleName())
                .isEqualTo(ClassMode.AFTER_CLASS);
    }
}

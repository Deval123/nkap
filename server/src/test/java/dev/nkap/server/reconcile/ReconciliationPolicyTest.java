package dev.nkap.server.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The policy on its own, without a database: the interval doubles from the base and holds
 * at the ceiling, and the window is spent once that much wall-clock time has passed since
 * the payment became unresolved — not once the attempts add up to it.
 */
class ReconciliationPolicyTest {

    private final ReconciliationPolicy policy = new ReconciliationPolicy(
            Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofHours(2));

    @Test
    @DisplayName("the interval doubles from the base each attempt and then holds at the maximum")
    void interval_doubles_then_holds_at_the_maximum() {
        assertThat(policy.intervalForAttempt(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.intervalForAttempt(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.intervalForAttempt(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(policy.intervalForAttempt(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(policy.intervalForAttempt(5)).isEqualTo(Duration.ofMinutes(16));
        assertThat(policy.intervalForAttempt(6)).isEqualTo(Duration.ofMinutes(30));   // 32 -> capped
        assertThat(policy.intervalForAttempt(7)).isEqualTo(Duration.ofMinutes(30));
        assertThat(policy.intervalForAttempt(50)).isEqualTo(Duration.ofMinutes(30));  // no overflow
    }

    @Test
    @DisplayName("the window is spent once that much wall-clock time has passed since the payment became unresolved")
    void window_is_spent_when_that_much_time_has_elapsed() {
        Instant unresolvedSince = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(policy.windowExhausted(unresolvedSince, unresolvedSince.plus(Duration.ofMinutes(119)))).isFalse();
        assertThat(policy.windowExhausted(unresolvedSince, unresolvedSince.plus(Duration.ofHours(2)))).isTrue();
        assertThat(policy.windowExhausted(unresolvedSince, unresolvedSince.plus(Duration.ofDays(1)))).isTrue();
        // Time, not passes: a hundred attempts inside the window is still inside the window.
        assertThat(policy.windowExhausted(unresolvedSince, unresolvedSince.plus(Duration.ofMinutes(1)))).isFalse();
    }

    @Test
    @DisplayName("a payment with no unresolved-since — one that predates the column — is treated as due for escalation")
    void a_missing_unresolved_since_is_treated_as_exhausted() {
        assertThat(policy.windowExhausted(null, Instant.now())).isTrue();
    }

    @Test
    @DisplayName("attempts are counted from one")
    void attempts_are_counted_from_one() {
        assertThatThrownBy(() -> policy.intervalForAttempt(0)).isInstanceOf(IllegalArgumentException.class);
    }
}

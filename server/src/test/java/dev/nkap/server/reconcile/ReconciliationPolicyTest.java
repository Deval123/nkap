package dev.nkap.server.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The backoff arithmetic on its own, without a database: the interval doubles from the
 * base, holds at the ceiling, and the window is spent as a pure function of the attempt
 * count so escalation needs no "first seen" timestamp.
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
    @DisplayName("the window is spent once the intervals scheduled so far add up to it")
    void window_is_spent_when_cumulative_intervals_reach_it() {
        // cumulative minutes: 1, 3, 7, 15, 31, 61, 91, 121 (attempt 6 onward each add the 30 min cap).
        assertThat(policy.windowExhausted(5)).isFalse();   // 31 min
        assertThat(policy.windowExhausted(7)).isFalse();   // 91 min
        assertThat(policy.windowExhausted(8)).isTrue();    // 121 min >= 120
    }

    @Test
    @DisplayName("attempts are counted from one")
    void attempts_are_counted_from_one() {
        assertThatThrownBy(() -> policy.intervalForAttempt(0)).isInstanceOf(IllegalArgumentException.class);
    }
}

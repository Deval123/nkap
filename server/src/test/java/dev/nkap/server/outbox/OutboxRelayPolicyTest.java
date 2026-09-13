package dev.nkap.server.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The policy on its own, without a database: the interval doubles from the base and holds at
 * the ceiling — the same math as {@code ReconciliationPolicy} — and an event is exhausted
 * once its attempt count reaches the configured maximum, not once wall-clock time has passed.
 */
class OutboxRelayPolicyTest {

    private final OutboxRelayPolicy policy = new OutboxRelayPolicy(Duration.ofSeconds(30), Duration.ofMinutes(30), 5);

    @Test
    @DisplayName("the interval doubles from the base each attempt and then holds at the maximum")
    void interval_doubles_then_holds_at_the_maximum() {
        assertThat(policy.intervalForAttempt(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.intervalForAttempt(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.intervalForAttempt(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.intervalForAttempt(4)).isEqualTo(Duration.ofMinutes(4));
        assertThat(policy.intervalForAttempt(5)).isEqualTo(Duration.ofMinutes(8));
        assertThat(policy.intervalForAttempt(6)).isEqualTo(Duration.ofMinutes(16));
        assertThat(policy.intervalForAttempt(7)).isEqualTo(Duration.ofMinutes(30));   // 32 -> capped
        assertThat(policy.intervalForAttempt(50)).isEqualTo(Duration.ofMinutes(30));  // no overflow
    }

    @Test
    @DisplayName("an event is exhausted once its attempt count reaches the configured maximum, not before")
    void attempts_are_exhausted_at_the_configured_maximum() {
        assertThat(policy.attemptsExhausted(4)).isFalse();
        assertThat(policy.attemptsExhausted(5)).isTrue();
        assertThat(policy.attemptsExhausted(6)).isTrue();
    }

    @Test
    @DisplayName("attempts are counted from one")
    void attempts_are_counted_from_one() {
        assertThatThrownBy(() -> policy.intervalForAttempt(0)).isInstanceOf(IllegalArgumentException.class);
    }
}

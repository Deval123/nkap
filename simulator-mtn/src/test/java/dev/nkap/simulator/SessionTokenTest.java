package dev.nkap.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The token carries its own expiry, so validation needs no server-side state. */
class SessionTokenTest {

    @Test
    @DisplayName("an issued token has three segments and carries an expiry roughly ttl from now")
    void issued_token_carries_its_expiry() {
        Instant before = Instant.now();
        String token = SessionToken.issue(Duration.ofHours(1));

        assertThat(token.split("\\.")).hasSize(3);
        assertThat(SessionToken.expiry(token)).hasValueSatisfying(exp -> {
            assertThat(exp).isBetween(before.plus(Duration.ofMinutes(59)), before.plus(Duration.ofMinutes(61)));
        });
    }

    @Test
    @DisplayName("a token is live before its expiry and not after")
    void live_until_expiry() {
        String token = SessionToken.issue(Duration.ofSeconds(2));
        Instant expiry = SessionToken.expiry(token).orElseThrow();

        assertThat(SessionToken.isLive(token, expiry.minusSeconds(1))).isTrue();
        assertThat(SessionToken.isLive(token, expiry)).isFalse();
        assertThat(SessionToken.isLive(token, expiry.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("an absent or unreadable token has no expiry and is never live")
    void garbage_is_not_a_token() {
        for (String bad : new String[] {null, "", "   ", "not-a-token", "only.two", "a.b.c", "..."}) {
            assertThat(SessionToken.expiry(bad)).as("expiry of %s", bad).isEmpty();
            assertThat(SessionToken.isLive(bad, Instant.now())).as("liveness of %s", bad).isFalse();
        }
    }

    @Test
    @DisplayName("survives a round-trip through a string: nothing but the token itself is needed to validate it")
    void stateless_round_trip() {
        String issued = SessionToken.issue(Duration.ofMinutes(30));
        String afterWireTransfer = new String(issued.toCharArray());

        assertThat(SessionToken.isLive(afterWireTransfer, Instant.now())).isTrue();
    }
}

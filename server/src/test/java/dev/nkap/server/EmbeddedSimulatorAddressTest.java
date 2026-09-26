package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddedSimulator} must own the address its callers connect to, for as long as it runs
 * (issue #213) — the same assertion {@code test-support}'s {@code SimulatorAddressTest} makes of
 * the harness both adapters' tests share, which had the same defect.
 *
 * <p>Bound to the IPv6 wildcard and reached through {@code localhost}, a second process could bind
 * {@code 127.0.0.1} on the same port with {@code SO_REUSEADDR} and take every call. This fixture
 * is the one the server's integration tests use, alongside Testcontainers publishing ports, so it
 * is the most exposed of the three.
 */
class EmbeddedSimulatorAddressTest {

    private static EmbeddedSimulator simulator;

    @BeforeAll
    static void start() {
        simulator = EmbeddedSimulator.start();
    }

    @AfterAll
    static void stop() {
        simulator.close();
    }

    @Test
    @DisplayName("callers connect to the one address the simulator bound, not to a name that may resolve elsewhere")
    void callers_connect_to_the_address_the_simulator_bound() {
        assertThat(URI.create(simulator.baseUri()).getHost()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("no other listener can take the simulator's address, even with SO_REUSEADDR")
    void no_other_listener_can_take_the_simulators_address() throws Exception {
        InetSocketAddress taken = new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                URI.create(simulator.baseUri()).getPort());
        try (ServerSocket intruder = new ServerSocket()) {
            intruder.setReuseAddress(true);
            assertThatThrownBy(() -> intruder.bind(taken)).isInstanceOf(BindException.class);
        }
    }
}

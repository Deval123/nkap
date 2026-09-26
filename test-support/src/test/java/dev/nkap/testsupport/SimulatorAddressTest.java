package dev.nkap.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The harness talks to the simulator at an address, and that address must belong to the
 * simulator for as long as the test class runs (issue #213).
 *
 * <p>It did not. Tomcat bound the IPv6 wildcard, {@code [::]}, while the harness connected to
 * {@code localhost}, which the JVM resolves to {@code 127.0.0.1} first. On macOS a second process
 * can then bind {@code 127.0.0.1} on the very same port, with {@code SO_REUSEADDR}, and the more
 * specific address wins: every control-plane call of the class goes to that other listener.
 * That was shown on the machine the original failure happened on; the process that did it was
 * never identified. The simulator now binds {@code 127.0.0.1} itself and the harness connects to
 * exactly that, so the address it uses cannot be bound by anyone else.
 *
 * <p>{@code provider-mtn} and {@code provider-mpesa} each ran this test against their own simulator.
 * One test of the one helper replaces both, and boots {@link BareApplication} rather than either
 * simulator. Nothing is lost by that: the address comes from the {@code --server.address} argument
 * {@link SimulatorUnderTest} passes, which outranks any application's own configuration, and
 * neither simulator sets {@code server.address} or supplies its own web server factory.
 */
class SimulatorAddressTest {

    private static SimulatorUnderTest simulator;

    @BeforeAll
    static void start() {
        simulator = new SimulatorUnderTest(BareApplication.class, "bare application");
    }

    @AfterAll
    static void stop() {
        simulator.close();
    }

    @Test
    @DisplayName("the harness connects to the one address the simulator bound, not to a name that may resolve elsewhere")
    void the_harness_connects_to_the_address_the_simulator_bound() {
        assertThat(simulator.baseUrl().getHost()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("no other listener can take the simulator's address, even with SO_REUSEADDR, as the one that shadowed it could")
    void no_other_listener_can_take_the_simulators_address() throws Exception {
        InetSocketAddress taken = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), simulator.baseUrl().getPort());
        try (ServerSocket intruder = new ServerSocket()) {
            intruder.setReuseAddress(true);
            assertThatThrownBy(() -> intruder.bind(taken)).isInstanceOf(BindException.class);
        }
    }
}

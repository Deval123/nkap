package dev.nkap.server.support;

import dev.nkap.testsupport.LoopbackOnly;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Makes the gateway under test bind {@link LoopbackOnly#ADDRESS} instead of the wildcard, on its
 * API port and on its management port (issue #221). {@link LoopbackOnly}'s javadoc gives the
 * mechanism this removes; it is the same defect issue #213 fixed in the test simulators.
 *
 * <p>Both ports, because application.yml puts Actuator on its own {@code management.server.port},
 * and {@code RANDOM_PORT} randomises that one too: {@code server.address} alone would leave it on
 * the wildcard.
 *
 * <p>Only during tests. Nothing outside this JVM connects to a gateway under test: no test
 * exposes a host port to a container, and the one container every test starts is PostgreSQL,
 * which the gateway dials. What a deployed gateway listens on is not decided here.
 */
public final class BindLoopback {

    private BindLoopback() {
    }

    /** For every {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} context in this module. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("server.address", () -> LoopbackOnly.ADDRESS);
        registry.add("management.server.address", () -> LoopbackOnly.ADDRESS);
    }
}

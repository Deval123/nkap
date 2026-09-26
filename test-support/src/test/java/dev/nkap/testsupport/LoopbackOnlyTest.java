package dev.nkap.testsupport;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LoopbackOnly#assertBoundToLoopbackOnly} against plain sockets, so that it is seen to fail
 * on every run and not only on the day someone removes a {@code server.address} line.
 */
class LoopbackOnlyTest {

    @Test
    @DisplayName("a listener bound to 127.0.0.1 alone passes")
    void a_loopback_listener_passes() throws Exception {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(InetAddress.getByName(LoopbackOnly.ADDRESS), 0));

            assertThatCode(() -> LoopbackOnly.assertBoundToLoopbackOnly(listener.getLocalPort()))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a listener bound to the wildcard fails, as Tomcat's default bind would")
    void a_wildcard_listener_fails() throws Exception {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(0));

            assertThatThrownBy(() -> LoopbackOnly.assertBoundToLoopbackOnly(listener.getLocalPort()))
                    .isInstanceOf(AssertionError.class)
                    .as("caught by the ::1 half, which discriminates on every platform")
                    .hasMessageContaining("must not answer at");
        }
    }
}

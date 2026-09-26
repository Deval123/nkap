package dev.nkap.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The address an application under test binds, and the assertion that it bound only that.
 *
 * <p>Tomcat binds the IPv6 wildcard, {@code [::]}, unless told otherwise, while tests dial
 * {@code localhost}, which the JVM resolves to {@code 127.0.0.1} first. On macOS another process
 * can then bind {@code 127.0.0.1} on the same port with {@code SO_REUSEADDR}, and the more
 * specific address wins: the tests' connections go to that process instead (issue #213). Binding
 * {@link #ADDRESS} itself leaves no more specific address for anyone to take.
 */
public final class LoopbackOnly {

    /** What an application under test binds, and what its tests reach it at. */
    public static final String ADDRESS = "127.0.0.1";

    private LoopbackOnly() {
    }

    /**
     * Asserts that the server listening on {@code port} is bound to {@link #ADDRESS} and nothing
     * else, in three halves.
     *
     * <ul>
     *   <li>It answers at {@link #ADDRESS}.</li>
     *   <li>It refuses {@code ::1}. The wildcard would accept a connection there, so this half
     *       fails against the wildcard on every platform with an IPv6 loopback.</li>
     *   <li>No other listener can take {@link #ADDRESS} on that port, even with
     *       {@code SO_REUSEADDR}. This is the steal itself. It fails against the wildcard on
     *       macOS, where the steal happens; on Linux the kernel refuses that bind either way, so
     *       it is the previous half that discriminates there.</li>
     * </ul>
     */
    public static void assertBoundToLoopbackOnly(int port) throws Exception {
        InetSocketAddress loopback = new InetSocketAddress(InetAddress.getByName(ADDRESS), port);
        assertThat(catchThrowable(() -> connect(loopback)))
                .as("the server answers at %s", loopback)
                .isNull();

        InetSocketAddress ipv6Loopback = new InetSocketAddress(InetAddress.getByName("::1"), port);
        assertThat(catchThrowable(() -> connect(ipv6Loopback)))
                .as("the server must not answer at %s: an answer there means it bound a wildcard", ipv6Loopback)
                .isInstanceOf(ConnectException.class);

        try (ServerSocket intruder = new ServerSocket()) {
            intruder.setReuseAddress(true);
            assertThat(catchThrowable(() -> intruder.bind(loopback)))
                    .as("no other listener may take %s, even with SO_REUSEADDR", loopback)
                    .isInstanceOf(BindException.class);
        }
    }

    private static void connect(InetSocketAddress address) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(address, 1_000);
        }
    }
}

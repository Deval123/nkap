package dev.nkap.conformance;

import com.sun.net.httpserver.HttpServer;
import dev.nkap.provider.RawCallback;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A single real HTTP receiver, shared for the lifetime of one test class, that hands back
 * every callback the operator delivers to it exactly as it arrived: headers and body
 * untouched, nothing reconstructed. What a harness needs to implement
 * {@link ConformanceHarness#aDeliveredCallback()} against an operator that calls back over HTTP.
 *
 * <p>In the kit because it costs the kit nothing: the JDK's {@code com.sun.net.httpserver} and
 * {@link RawCallback}, which {@code provider-api} already supplies. Booting an operator to call
 * it is not here, because that would bring whatever the operator runs on into every
 * contributor's test classpath.
 *
 * <p>Kept at class scope and started once, well before the first test runs, on purpose. A
 * fresh {@link HttpServer} per test looked simpler but was flaky: its background dispatch
 * thread was occasionally still starting up — a one-time JIT/classloading cost somewhere in
 * {@code com.sun.net.httpserver} — when the very next scenario's callback arrived a few
 * milliseconds later, and the simulator's delivery has no retry (by design, not a gap this
 * harness should work around). One server, started during {@code @BeforeAll} alongside the
 * simulator itself, gives that one-time cost seconds to resolve instead of milliseconds.
 *
 * <p><strong>One receiver, but not one queue.</strong> {@link ConformanceHarness#aDeliveredCallback()}
 * promises the callback for the submission the kit just made through that harness —
 * not merely the next thing this receiver happened to catch. Sharing one queue across every
 * harness the test class ever opens would not honour that: {@code close()} runs synchronously
 * at the end of a test, but the matching delivery is asynchronous and can still be in flight —
 * land a moment after one test cleared the queue and it sits there for the next test to
 * mistakenly consume. An adapter that names the reference Nkap chose would at least fail loudly
 * on the mismatch; one that only ever names the operator's own reference — the unattributed
 * shape this whole rule exists to certify — has no such check, and a stale callback would
 * satisfy it just as well as a real one. {@link #open()} gives each harness its own path and
 * its own queue, so a late arrival from a harness nobody is listening to anymore has nowhere
 * of another harness's to land in.
 *
 * <p><strong>The receiver does not tell the operator where to call.</strong> The harness does,
 * and how it does is part of what its {@code CALLBACK} rule proves. {@code provider-mtn}'s
 * harness declares {@link Route#url()} to the simulator as the address to call back when a
 * submission names none. {@code provider-mpesa}'s declares no such fallback and hands the
 * address to the adapter alone, in the intent; a callback reaching the route then proves the
 * adapter forwarded the URL it was handed, since the operator had no other way to learn it.
 * Nothing here chooses between the two.
 */
public final class CallbackReceiver implements AutoCloseable {

    private static final String PATH_PREFIX = "/callback/";

    private final HttpServer server;
    private final ConcurrentMap<String, BlockingQueue<RawCallback>> routes = new ConcurrentHashMap<>();

    public CallbackReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext(PATH_PREFIX, exchange -> {
                try {
                    String token = exchange.getRequestURI().getPath().substring(PATH_PREFIX.length());
                    Map<String, String> headers = new LinkedHashMap<>();
                    exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, String.join(",", values)));
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, -1);
                    // A route already forgotten (its harness closed) has nowhere of its own left
                    // to land in -- dropped rather than resurrected into a shared bucket, which
                    // is exactly the leak this per-route design exists to avoid.
                    BlockingQueue<RawCallback> queue = routes.get(token);
                    if (queue != null) {
                        queue.add(new RawCallback(headers, body));
                    }
                } finally {
                    exchange.close();
                }
            });
            server.setExecutor(null);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the shared callback receiver", e);
        }
    }

    /**
     * A fresh route, isolated from every other one this receiver has ever opened: its own
     * random path, its own queue. One harness opens exactly one, for its own lifetime.
     */
    public Route open() {
        String token = UUID.randomUUID().toString();
        routes.put(token, new LinkedBlockingQueue<>());
        return new Route(token);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** One harness's private address on this receiver, and the queue only it ever reads from. */
    public final class Route {

        private final String token;

        private Route(String token) {
            this.token = token;
        }

        /** Where this route's owner should tell the operator to call back. */
        public String url() {
            return "http://localhost:" + server.getAddress().getPort() + PATH_PREFIX + token;
        }

        /** The next callback delivered to this route, waiting up to {@code timeout} for one. */
        public RawCallback poll(Duration timeout) {
            try {
                RawCallback delivered = routes.get(token).poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (delivered == null) {
                    throw new IllegalStateException(
                            "the operator never delivered a callback to this route within " + timeout);
                }
                return delivered;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for the operator's callback", e);
            }
        }

        /** Forgets this route: nothing still in flight for it can be mistaken for anyone else's. */
        public void close() {
            routes.remove(token);
        }
    }
}

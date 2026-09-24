package dev.nkap.provider.mpesa;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;

/**
 * What the harness knows about the simulator's port, so that a failed control-plane call can
 * say which of three different bugs it was (issue #213): nothing accepting on the port
 * (refused), something accepting and then dropping the connection (reset, or closed without
 * answering), or something answering that is not the simulator.
 *
 * <p>Passive until something fails. It logs when the web server reported started and on which
 * address, and when the first control-plane request went out; it sends nothing of its own
 * before a call has already failed, so it cannot change the timing it is there to observe. On
 * a failure it probes the port directly, once per loopback address, and the result goes into
 * the failure's own message, so the diagnosis travels with the stack trace rather than
 * depending on anyone keeping the log.
 *
 * <p>A copy of {@code provider-mtn}'s, for the same reason its {@code CallbackReceiver} is one.
 */
final class SimulatorStartupLog {

    private final String name;
    private final int port;
    private final long startedNanos;
    private final AtomicBoolean firstCall = new AtomicBoolean(true);

    private SimulatorStartupLog(String name, int port, long startedNanos) {
        this.name = name;
        this.port = port;
        this.startedNanos = startedNanos;
    }

    /** Called as soon as {@code SpringApplication.run} returns. */
    static SimulatorStartupLog started(String name, WebServer webServer, long runCalledNanos) {
        long now = System.nanoTime();
        SimulatorStartupLog log = new SimulatorStartupLog(name, webServer.getPort(), now);
        log.print("web server started on port %d, bound to %s, %d ms after SpringApplication.run was called",
                webServer.getPort(), boundAddress(webServer), TimeUnit.NANOSECONDS.toMillis(now - runCalledNanos));
        return log;
    }

    /** Called before every control-plane request; logs only the first. */
    void beforeCall(String method, String path) {
        if (firstCall.compareAndSet(true, false)) {
            print("first control-plane request %s %s, %d ms after the web server reported started",
                    method, path, sinceStarted());
        }
    }

    /** A description of {@code failure} as seen on the wire, with a direct probe of the port. */
    String diagnose(Throwable failure) {
        StringBuilder out = new StringBuilder();
        out.append(name).append(" on port ").append(port).append(", ").append(sinceStarted())
                .append(" ms after it reported started: ").append(classify(failure));
        try {
            out.append("; localhost resolves to ").append(Arrays.toString(InetAddress.getAllByName("localhost")));
        } catch (Exception e) {
            out.append("; localhost does not resolve: ").append(e);
        }
        for (String address : new String[] {"127.0.0.1", "::1"}) {
            out.append("; probe ").append(address).append(':').append(port).append(" -> ").append(probe(address));
        }
        String diagnosis = out.toString();
        print("control-plane call failed: %s", diagnosis);
        return diagnosis;
    }

    private long sinceStarted() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private void print(String format, Object... args) {
        System.err.printf("[simulator-under-test] %s: %s%n", name, String.format(format, args));
    }

    /** The three shapes the issue could not tell apart, named from the exception chain. */
    static String classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = String.valueOf(t.getMessage()).toLowerCase();
            if (t instanceof ConnectException) {
                return "REFUSED -- nothing was accepting on the port (" + t + ")";
            }
            if (t instanceof HttpConnectTimeoutException) {
                return "CONNECT TIMED OUT (" + t + ")";
            }
            if (t instanceof HttpTimeoutException) {
                return "ACCEPTED, NO ANSWER within the request timeout (" + t + ")";
            }
            if (message.contains("reset")) {
                return "RESET -- accepted, then reset (" + t + ")";
            }
            if (t instanceof EOFException || message.contains("received no bytes") || message.contains("closed")) {
                return "CLOSED -- accepted, then closed without answering (" + t + ")";
            }
        }
        return "OTHER (" + failure + ")";
    }

    /** One raw HTTP request to one address: who, if anyone, answers there. */
    private String probe(String address) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(address), port), 1_000);
            socket.setSoTimeout(2_000);
            OutputStream request = socket.getOutputStream();
            request.write(("GET /_nkap/scenarios HTTP/1.1\r\nHost: localhost:" + port
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            request.flush();
            String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();
            return statusLine == null ? "accepted, then closed without answering" : "answered \"" + statusLine + "\"";
        } catch (ConnectException e) {
            return "refused";
        } catch (SocketTimeoutException e) {
            return "accepted, no answer in 2 s";
        } catch (Exception e) {
            return String.valueOf(e);
        }
    }

    /**
     * The address Tomcat's server socket is actually bound to. Tomcat keeps it on its endpoint,
     * which it does not expose, so this reaches for it reflectively and says so if it cannot.
     */
    private static String boundAddress(WebServer webServer) {
        if (!(webServer instanceof TomcatWebServer tomcat)) {
            return "unknown (" + webServer.getClass().getSimpleName() + ")";
        }
        try {
            Object handler = tomcat.getTomcat().getConnector().getProtocolHandler();
            Method getEndpoint = findMethod(handler.getClass(), "getEndpoint");
            Object endpoint = getEndpoint.invoke(handler);
            Method getLocalAddress = findMethod(endpoint.getClass(), "getLocalAddress");
            return String.valueOf(getLocalAddress.invoke(endpoint));
        } catch (Exception e) {
            return "unknown (" + e + ")";
        }
    }

    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}

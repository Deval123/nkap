package dev.nkap.server.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A throwaway HTTP server standing in for a merchant's webhook receiver — the same idea as
 * {@code provider-mtn}'s {@code StubMtn}, one level up the stack: what a webhook delivery
 * attempt actually sent, and a scriptable answer, without a real merchant to run.
 */
public final class StubReceiver implements AutoCloseable {

    public record RecordedRequest(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            for (var entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().isEmpty() ? null : entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    public record StubResponse(int status, String body) {
    }

    public final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Function<RecordedRequest, StubResponse> handler = request -> new StubResponse(200, "{}");
    private final HttpServer server;

    public StubReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hooks");
    }

    public void respondWith(Function<RecordedRequest, StubResponse> handler) {
        this.handler = handler;
    }

    public void alwaysRespond(int status, String body) {
        respondWith(request -> new StubResponse(status, body));
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RecordedRequest recorded = new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                Map.copyOf(exchange.getRequestHeaders()),
                body);
        requests.add(recorded);

        StubResponse response = handler.apply(recorded);
        byte[] out = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), out.length == 0 ? -1 : out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

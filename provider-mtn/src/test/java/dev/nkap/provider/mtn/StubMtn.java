package dev.nkap.provider.mtn;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A throwaway HTTP server standing in for MTN, for the few things the simulator cannot
 * express: asserting an outgoing request header, and scripting a 401 that a live token
 * still hits. Everything else is tested against the real simulator.
 */
final class StubMtn implements AutoCloseable {

    record RecordedRequest(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            for (var entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().isEmpty() ? null : entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    record StubResponse(int status, String body) {}

    final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Function<RecordedRequest, StubResponse> handler = request -> new StubResponse(200, "{}");
    private final HttpServer server;

    StubMtn() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void respondWith(Function<RecordedRequest, StubResponse> handler) {
        this.handler = handler;
    }

    long countPath(String path) {
        return requests.stream().filter(request -> request.path().equals(path)).count();
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

    static String tokenJson(String value, long expiresIn) {
        return String.format(Locale.ROOT, "{\"access_token\":\"%s\",\"expires_in\":%d}", value, expiresIn);
    }
}

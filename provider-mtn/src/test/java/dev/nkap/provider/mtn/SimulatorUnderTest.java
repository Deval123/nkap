package dev.nkap.provider.mtn;

import dev.nkap.simulator.SimulatorApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the real simulator on a random port for the lifetime of a test class, and drives
 * its control plane. This is what the simulator was built for: the MTN adapter is tested
 * against it, never against the real sandbox.
 */
final class SimulatorUnderTest implements AutoCloseable {

    private final ConfigurableApplicationContext context;
    private final URI baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    SimulatorUnderTest() {
        SpringApplication app = new SpringApplication(SimulatorApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        // Command-line args outrank the simulator's application.yml (server.port: 8081).
        // Immediate shutdown: the NO_RESPONSE scenario deliberately leaves a request
        // hanging, and graceful shutdown would then wait out its full timeout.
        this.context = app.run(
                "--server.port=0",
                "--server.shutdown=immediate",
                "--spring.lifecycle.timeout-per-shutdown-phase=3s");
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        this.baseUrl = URI.create("http://localhost:" + port);
    }

    URI baseUrl() {
        return baseUrl;
    }

    /** POST a configuration document to {@code /_nkap/scenarios}. */
    void declare(String configurationJson) {
        call("POST", "/_nkap/scenarios", configurationJson);
    }

    /** Back to the happy path, no enforcement, and every reference forgotten. */
    void reset() {
        call("DELETE", "/_nkap/scenarios", null);
        call("DELETE", "/_nkap/state", null);
    }

    private void call(String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(Duration.ofSeconds(5));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(method + " " + path + " -> " + response.statusCode() + " " + response.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("control-plane call failed: " + method + " " + path, e);
        }
    }

    @Override
    public void close() {
        context.close();
    }
}

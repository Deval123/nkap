package dev.nkap.provider.mtn;

import dev.nkap.simulator.SimulatorApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the real simulator on a random port for the lifetime of a test class, and drives
 * its control plane. This is what the simulator was built for: the MTN adapter is tested
 * against it, never against the real sandbox.
 */
final class SimulatorUnderTest implements AutoCloseable {

    private static final Pattern SUBMISSIONS_COUNT = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");

    private final ConfigurableApplicationContext context;
    private final URI baseUrl;
    private final SimulatorStartupLog startup;
    private final HttpClient http = HttpClient.newHttpClient();

    SimulatorUnderTest() {
        SpringApplication app = new SpringApplication(SimulatorApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        // Command-line args outrank the simulator's application.yml (server.port: 8081).
        // Immediate shutdown: the NO_RESPONSE scenario deliberately leaves a request
        // hanging, and graceful shutdown would then wait out its full timeout.
        long runCalled = System.nanoTime();
        this.context = app.run(
                "--server.port=0",
                "--server.shutdown=immediate",
                "--spring.lifecycle.timeout-per-shutdown-phase=3s");
        WebServer webServer = ((ServletWebServerApplicationContext) context).getWebServer();
        this.startup = SimulatorStartupLog.started("simulator (MTN)", webServer, runCalled);
        this.baseUrl = URI.create("http://localhost:" + webServer.getPort());
    }

    URI baseUrl() {
        return baseUrl;
    }

    /** POST a configuration document to {@code /_nkap/scenarios}. */
    void declare(String configurationJson) {
        call("POST", "/_nkap/scenarios", configurationJson);
    }

    /**
     * How many submissions the simulator has processed since its state was last forgotten —
     * {@code GET /_nkap/submissions} (issue #175).
     */
    int submissionsReceived() {
        String body = call("GET", "/_nkap/submissions", null);
        Matcher count = SUBMISSIONS_COUNT.matcher(body);
        if (!count.find()) {
            throw new IllegalStateException("GET /_nkap/submissions answered " + body);
        }
        return Integer.parseInt(count.group(1));
    }

    /** Back to the happy path, no enforcement, and every reference forgotten. */
    void reset() {
        call("DELETE", "/_nkap/scenarios", null);
        call("DELETE", "/_nkap/state", null);
    }

    private String call(String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(Duration.ofSeconds(5));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        }
        startup.beforeCall(method, path);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(method + " " + path + " -> " + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (Exception e) {
            // An error status is the simulator answering; only a call that got no answer is
            // worth probing the port for.
            String onTheWire = e instanceof IllegalStateException ? "" : " -- " + startup.diagnose(e);
            throw new IllegalStateException("control-plane call failed: " + method + " " + path + onTheWire, e);
        }
    }

    @Override
    public void close() {
        context.close();
    }
}

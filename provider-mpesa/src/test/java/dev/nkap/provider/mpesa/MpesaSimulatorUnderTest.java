package dev.nkap.provider.mpesa;

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
 * Boots M-Pesa's face on the simulator's core on a random port for the lifetime of a test
 * class, and drives its control plane. The same shape as {@code provider-mtn}'s own, which is
 * test-scope there and so cannot be shared.
 */
final class MpesaSimulatorUnderTest implements AutoCloseable {

    private static final Pattern SUBMISSIONS_COUNT = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");

    /** Bound and connected to by address, never by a name that could resolve somewhere else. */
    private static final String LOOPBACK = "127.0.0.1";

    private final ConfigurableApplicationContext context;
    private final URI baseUrl;
    private final SimulatorStartupLog startup;
    private final HttpClient http = HttpClient.newHttpClient();

    MpesaSimulatorUnderTest() {
        SpringApplication app = new SpringApplication(MpesaSimulatorApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        // Immediate shutdown: NO_RESPONSE deliberately leaves a request hanging, and graceful
        // shutdown would wait out its full timeout. Durations as ISO-8601 strings, as the
        // deployable's own application.yml sets them.
        long runCalled = System.nanoTime();
        this.context = app.run(
                "--server.port=0",
                // The exact address the harness connects to, not the wildcard Tomcat binds by
                // default. Bound to [::], the port's 127.0.0.1 could be taken by any other process
                // binding it with SO_REUSEADDR, and every call below would go there instead
                // (issue #213, SimulatorAddressTest).
                "--server.address=" + LOOPBACK,
                "--server.shutdown=immediate",
                "--spring.lifecycle.timeout-per-shutdown-phase=3s",
                "--spring.jackson.serialization.write-durations-as-timestamps=false");
        WebServer webServer = ((ServletWebServerApplicationContext) context).getWebServer();
        this.startup = SimulatorStartupLog.started("simulator-mpesa", webServer, runCalled);
        this.baseUrl = URI.create("http://" + LOOPBACK + ":" + webServer.getPort());
    }

    URI baseUrl() {
        return baseUrl;
    }

    /** POST a configuration document to {@code /_nkap/scenarios}. */
    void declare(String configurationJson) {
        call("POST", "/_nkap/scenarios", configurationJson);
    }

    /** {@code GET /_nkap/submissions}: submissions the simulator processed since its state was forgotten. */
    int submissionsReceived() {
        String body = call("GET", "/_nkap/submissions", null);
        Matcher count = SUBMISSIONS_COUNT.matcher(body);
        if (!count.find()) {
            throw new IllegalStateException("GET /_nkap/submissions answered " + body);
        }
        return Integer.parseInt(count.group(1));
    }

    /** Back to the happy path, no enforcement, and every payment forgotten. */
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

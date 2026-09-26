package dev.nkap.testsupport;

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
 * Boots a real simulator on a random port for the lifetime of a test class, and drives its
 * control plane. This is what the simulators were built for: an adapter is tested against one,
 * never against the operator's real sandbox.
 *
 * <p>Which simulator is the caller's to say, by its application class: {@code simulator}'s
 * {@code SimulatorApplication} for MTN, {@code simulator-mpesa-app}'s
 * {@code MpesaSimulatorApplication} for M-Pesa. Both serve the same {@code /_nkap} control
 * plane, which is all this class speaks. Taking the class rather than depending on either
 * keeps this module free of every nkap module.
 */
public final class SimulatorUnderTest implements AutoCloseable {

    private static final Pattern SUBMISSIONS_COUNT = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");

    /** Bound and connected to by address, never by a name that could resolve somewhere else. */
    private static final String LOOPBACK = "127.0.0.1";

    private final ConfigurableApplicationContext context;
    private final URI baseUrl;
    private final SimulatorStartupLog startup;
    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * @param application the simulator's {@code @SpringBootApplication} class
     * @param name        what the startup log calls it, e.g. {@code "simulator (MTN)"}
     */
    public SimulatorUnderTest(Class<?> application, String name) {
        SpringApplication app = new SpringApplication(application);
        app.setBannerMode(Banner.Mode.OFF);
        // Command-line args outrank the simulator's application.yml (server.port: 8081 or 8082).
        // Immediate shutdown: the NO_RESPONSE scenario deliberately leaves a request
        // hanging, and graceful shutdown would then wait out its full timeout.
        long runCalled = System.nanoTime();
        this.context = app.run(
                "--server.port=0",
                // The exact address the harness connects to, not the wildcard Tomcat binds by
                // default. Bound to [::], the port's 127.0.0.1 could be taken by any other process
                // binding it with SO_REUSEADDR, and every call below would go there instead
                // (issue #213, SimulatorAddressTest).
                "--server.address=" + LOOPBACK,
                "--server.shutdown=immediate",
                "--spring.lifecycle.timeout-per-shutdown-phase=3s");
        WebServer webServer = ((ServletWebServerApplicationContext) context).getWebServer();
        this.startup = SimulatorStartupLog.started(name, webServer, runCalled);
        this.baseUrl = URI.create("http://" + LOOPBACK + ":" + webServer.getPort());
    }

    public URI baseUrl() {
        return baseUrl;
    }

    /** POST a configuration document to {@code /_nkap/scenarios}. */
    public void declare(String configurationJson) {
        call("POST", "/_nkap/scenarios", configurationJson);
    }

    /**
     * How many submissions the simulator has processed since its state was last forgotten —
     * {@code GET /_nkap/submissions} (issue #175).
     */
    public int submissionsReceived() {
        String body = call("GET", "/_nkap/submissions", null);
        Matcher count = SUBMISSIONS_COUNT.matcher(body);
        if (!count.find()) {
            throw new IllegalStateException("GET /_nkap/submissions answered " + body);
        }
        return Integer.parseInt(count.group(1));
    }

    /** Back to the happy path, no enforcement, and every payment forgotten. */
    public void reset() {
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

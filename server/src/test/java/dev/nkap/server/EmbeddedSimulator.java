package dev.nkap.server;

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
 * The real simulator, booted on a random port for a test class, and its control plane.
 *
 * <p>This is the server module's own fixture. It is near-identical to
 * {@code provider-mtn}'s {@code SimulatorUnderTest} — the plan says not to reach into that
 * module's test sources, and this is what "not reaching in" costs: a second copy. If a
 * third consumer appears, a shared test fixture earns its keep; two do not.
 */
final class EmbeddedSimulator implements AutoCloseable {

    /** Bound and connected to by address, never by a name that could resolve somewhere else. */
    private static final String LOOPBACK = "127.0.0.1";

    private final ConfigurableApplicationContext context;
    private final String baseUri;
    private final HttpClient http = HttpClient.newHttpClient();

    private EmbeddedSimulator() {
        SpringApplication app = new SpringApplication(SimulatorApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        // Command-line args outrank the simulator's application.yml. Immediate shutdown
        // because the NO_RESPONSE scenario deliberately leaves a request hanging.
        //
        // The simulator has no database. It is booted here inside the server test JVM,
        // whose classpath now carries spring-boot-starter-jdbc, Flyway and the PostgreSQL
        // driver — enough for their auto-configuration to switch on and try to connect.
        // Turn it off explicitly for this process.
        //
        // Same classpath-contamination reasoning for the management port: this JVM's
        // application.yml (the server's own) fixes it at 9464, and that value leaks into
        // this embedded SimulatorApplication the same way the datasource beans would.
        // Harmless with one instance running at a time, since it is released before the
        // next test class's context needs it — but a second EmbeddedSimulator alive at
        // once (issue #82, MultiCountryPaymentApiIT) collides with the first on that same
        // fixed port, and the loser's failed startup means its @AfterAll never runs to
        // release the winner's hold on it either, wedging every later test in the same
        // JVM. The simulator needs no actuator endpoint in a test at all; disable it.
        this.context = app.run(
                "--server.port=0",
                // The exact address callers connect to, not the wildcard Tomcat binds by default:
                // bound to [::], the port's 127.0.0.1 could be taken by another process binding
                // it with SO_REUSEADDR, and every call would go there instead. The same defect
                // provider-mtn's and provider-mpesa's harnesses had (issue #213).
                "--server.address=" + LOOPBACK,
                "--management.server.port=-1",
                "--server.shutdown=immediate",
                "--spring.lifecycle.timeout-per-shutdown-phase=3s",
                "--spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        this.baseUri = "http://" + LOOPBACK + ":" + port;
    }

    static EmbeddedSimulator start() {
        return new EmbeddedSimulator();
    }

    String baseUri() {
        return baseUri;
    }

    void declareScenario(String configurationJson) {
        call("POST", "/_nkap/scenarios", configurationJson);
    }

    void reset() {
        call("DELETE", "/_nkap/scenarios", null);
        call("DELETE", "/_nkap/state", null);
    }

    private void call(String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUri + path)).timeout(Duration.ofSeconds(5));
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
            throw new IllegalStateException("simulator control-plane call failed: " + method + " " + path, e);
        }
    }

    @Override
    public void close() {
        context.close();
    }
}

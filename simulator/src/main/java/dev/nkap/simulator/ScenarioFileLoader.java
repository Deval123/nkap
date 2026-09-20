package dev.nkap.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.simulator.ControlPlaneController.Declaration;
import dev.nkap.simulator.scenario.ScenarioEngine;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads a scenario declaration from a file at startup, applying it exactly as if it had
 * been {@code POST}ed to {@code /_nkap/scenarios} (issue #99) — the same {@link Declaration}
 * shape, the same Jackson binding {@link ControlPlaneController#declare} uses, the same
 * {@link ScenarioEngine#replaceConfiguration} call. This is not a second source of truth:
 * after this runs, the file has no further existence. {@code GET /_nkap/scenarios} keeps
 * answering what is active in {@link ScenarioEngine}, whatever established it, and a later
 * {@code POST} replaces what this loaded exactly the way it replaces an earlier {@code POST}
 * (ADR 0002's amendment).
 *
 * <p>Runs from {@link PostConstruct}, deliberately not an {@code ApplicationRunner} or
 * {@code CommandLineRunner}: Spring Boot's {@code ServletWebServerApplicationContext} only
 * starts the embedded web server — the point at which the port actually accepts a connection
 * — in {@code finishRefresh()}, which runs after every singleton bean, this one included, has
 * already been constructed and initialised. A runner would not give that guarantee: runners
 * execute after {@code context.refresh()} has returned, by which time the port is already
 * open. This is what makes "no {@code curl} in between" true rather than merely usual.
 *
 * <p>The file is read once, here, and never watched — a watched file trades a startup-only
 * concern for a standing concurrency one (a read mid-rewrite) for no benefit this issue
 * asked for; changing the scenario after startup is what the control plane is already for.
 *
 * <p>No file at the configured path is not an error: the simulator starts on the default
 * scenario, exactly as it did before this existed. A path that exists but is not a regular
 * file — most often a directory, which {@code docker run -v ./scenario.json:...} creates on
 * the container side when the host path does not exist — and a file that exists but does not
 * parse as a {@link Declaration} both refuse to start, naming the path and what was wrong
 * with it: a simulator that silently ignored the scenario it was handed would produce test
 * results nobody could explain.
 */
@Component
class ScenarioFileLoader {

    private static final Logger log = LoggerFactory.getLogger(ScenarioFileLoader.class);

    private final Path path;
    private final ObjectMapper json;
    private final ScenarioEngine engine;

    // The default is repeated here, not left to application.yml alone: EmbeddedSimulator
    // (server module) and provider-mtn's own equivalent boot SimulatorApplication inside a
    // host JVM that carries its own application.yml on the same classpath, and Spring Boot's
    // classpath:/application.yml resolution is not guaranteed to load both. Losing this
    // inline default there would fail an unrelated test class's static initializer on an
    // unresolved placeholder -- a failure with nothing to do with what that test is about.
    ScenarioFileLoader(@Value("${nkap.scenario.file:/etc/nkap/scenario.json}") String path,
                       ObjectMapper json, ScenarioEngine engine) {
        this.path = Path.of(path);
        this.json = json;
        this.engine = engine;
    }

    @PostConstruct
    void load() {
        if (!Files.exists(path)) {
            return;
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("nkap.scenario.file names " + path
                    + ", which exists but is not a regular file -- if this is a Docker bind mount, "
                    + "the most likely cause is that the host path did not exist when the container "
                    + "was started, so Docker created a directory there instead of mounting a file");
        }

        Declaration declaration;
        try {
            declaration = json.readValue(path.toFile(), Declaration.class);
        } catch (IOException e) {
            throw new IllegalStateException("nkap.scenario.file " + path + " could not be read as a scenario "
                    + "declaration (field: " + ControlPlaneController.offendingField(e) + "): " + e.getMessage(), e);
        }

        engine.replaceConfiguration(declaration.token(), declaration.callbackUrl(), declaration.rules(), declaration.account());
        log.info("loaded {} scenario rule(s) from {}, active before the first request", declaration.rules().size(), path);
    }
}

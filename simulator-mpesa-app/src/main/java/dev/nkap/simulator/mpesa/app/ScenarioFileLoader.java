package dev.nkap.simulator.mpesa.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.simulator.ControlPlane;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads a scenario declaration from a file at startup, applying it exactly as if it had been
 * {@code POST}ed to {@code /_nkap/scenarios} (issue #99). A <strong>copy</strong> of
 * {@code simulator}'s {@code ScenarioFileLoader}, whose javadoc gives every reason for how it
 * behaves — {@link PostConstruct} rather than a runner, read once and never watched, a missing
 * file not an error, a directory or an unreadable file refusing to start — and which this copy
 * does not restate. The two must keep behaving alike: the README tells people the same
 * {@code -v ./scenario.json:/etc/nkap/scenario.json} works for either image.
 *
 * <h2>Why a copy, and what it costs</h2>
 *
 * <p>Nothing in the original is MTN's: it binds a declaration and calls
 * {@link ControlPlane#load}. Three homes were possible.
 *
 * <ul>
 *   <li><strong>{@code simulator-core}.</strong> A {@code @Component} there is scanned into every
 *       application that assembles the core — {@code simulator-mpesa}'s test application, both
 *       deployables, {@code server}'s embedded simulator — and
 *       {@code nkap.scenario.file} becomes a behaviour of a published module that only a major
 *       release could take back. It would also move a class out of the MTN deployable, which this
 *       change set out not to touch.</li>
 *   <li><strong>None.</strong> The control plane alone is enough for the one consumer waiting on
 *       this image (#240's {@code kind} job). But a {@code -v} mount that one image honours and the
 *       other silently ignores is the exact failure the original refuses to start over: a
 *       scenario handed over and not played.</li>
 *   <li><strong>A copy</strong>, chosen: a constructor and one method.</li>
 * </ul>
 *
 * <p>The cost: it is the first <em>production</em> duplication in this repository. The copies
 * #214 recorded were all test scope — {@code RecordToString} four times, {@code CallbackReceiver}
 * twice — and #214 removed them. A fix to one loader that is not made to the other makes the two
 * images disagree on the same file, and nothing but this paragraph and {@code ScenarioFileTest}
 * in each module notices.
 * If a third deployable ever needs it, that is the point to move it to {@code simulator-core}
 * and accept the cost above, rather than copy it again.
 */
@Component
class ScenarioFileLoader {

    private static final Logger log = LoggerFactory.getLogger(ScenarioFileLoader.class);

    private final Path path;
    private final ObjectMapper json;
    private final ControlPlane<?, ?> controlPlane;

    // The default is repeated inline for the original's reason, and ConfigurationReferenceTest
    // (server module) checks that it agrees with application.yml and the reference.
    ScenarioFileLoader(@Value("${nkap.scenario.file:/etc/nkap/scenario.json}") String path,
                       ObjectMapper json, ControlPlane<?, ?> controlPlane) {
        this.path = Path.of(path);
        this.json = json;
        this.controlPlane = controlPlane;
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

        int rules;
        try {
            rules = controlPlane.load(json, path);
        } catch (IOException | ControlPlane.UnplayableScenario e) {
            throw new IllegalStateException("nkap.scenario.file " + path + " could not be read as a scenario "
                    + "declaration (field: " + ControlPlane.offendingField(e) + "): " + e.getMessage(), e);
        }

        log.info("loaded {} scenario rule(s) from {}, active before the first request", rules, path);
    }
}

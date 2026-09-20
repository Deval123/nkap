package dev.nkap.simulator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/**
 * Issue #99: a scenario file that cannot be applied must refuse to start rather than being
 * silently ignored -- a simulator that started anyway on a scenario nobody chose would
 * produce test results nobody could explain. Boots a real {@link SpringApplication} headless
 * ({@link WebApplicationType#NONE}), the same shape {@code DatabaseAuthFailureIT} uses in the
 * server module, so this is the actual startup failure a deployment would see, not a unit
 * test of {@link ScenarioFileLoader} in isolation.
 */
class ScenarioFileStartupFailureTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a scenario file that is not valid JSON for the declared document fails startup, naming the file")
    void a_malformed_file_fails_startup_naming_the_file() throws IOException {
        Path file = tempDir.resolve("scenario.json");
        Files.writeString(file, "{\"rules\": [ not json");

        // The informative message (which file, what was wrong) is ScenarioFileLoader's own
        // IllegalStateException, somewhere in the chain Spring wraps around it -- not
        // necessarily the root cause, which is Jackson's own low-level parse error and may
        // say nothing about which file it was reading. hasStackTraceContaining searches every
        // "Caused by" level, which is where this actually needs to be found.
        assertThatThrownBy(() -> boot(file))
                .as("a malformed scenario file must fail startup, not start on an ignored scenario")
                .hasStackTraceContaining(file.toString());
    }

    @Test
    @DisplayName("a scenario file with a field of the wrong shape fails startup, naming the file")
    void a_field_of_the_wrong_shape_fails_startup_naming_the_file() throws IOException {
        Path file = tempDir.resolve("scenario.json");
        Files.writeString(file, "{\"rules\": \"not-a-list-of-rules\"}");

        assertThatThrownBy(() -> boot(file))
                .hasStackTraceContaining(file.toString());
    }

    @Test
    @DisplayName("nkap.scenario.file naming a directory -- what a Docker bind mount creates when the host "
            + "file does not exist -- fails startup, naming the path")
    void a_directory_where_a_file_is_expected_fails_startup_naming_the_path() {
        assertThatThrownBy(() -> boot(tempDir))
                .as("nkap.scenario.file pointed at a directory must fail startup with a clear reason, "
                        + "not silently fall back to the default scenario")
                .hasStackTraceContaining(tempDir.toString())
                .hasStackTraceContaining("not a regular file");
    }

    private static void boot(Path scenarioFile) {
        SpringApplication app = new SpringApplication(SimulatorApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run("--nkap.scenario.file=" + scenarioFile);
    }
}

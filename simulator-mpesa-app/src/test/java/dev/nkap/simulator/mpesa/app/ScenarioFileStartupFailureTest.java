package dev.nkap.simulator.mpesa.app;

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
 * A scenario file that cannot be applied refuses to start rather than being silently ignored,
 * as in the MTN simulator (issue #99). Boots the real application headless, so this is the
 * startup failure a container would see.
 *
 * <p>The last case is this face's own: M-Pesa does not deduplicate, so a scenario declaring
 * {@code CONFLICT} is refused when posted, and must be refused the same way from a file. The MTN
 * simulator's loader never meets it, since MTN does refuse a repeated reference.
 */
class ScenarioFileStartupFailureTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a scenario file that is not valid JSON fails startup, naming the file")
    void a_malformed_file_fails_startup_naming_the_file() throws IOException {
        Path file = tempDir.resolve("scenario.json");
        Files.writeString(file, "{\"rules\": [ not json");

        assertThatThrownBy(() -> boot(file))
                .as("a malformed scenario file must fail startup, not start on an ignored scenario")
                .hasStackTraceContaining(file.toString());
    }

    @Test
    @DisplayName("nkap.scenario.file naming a directory -- what a Docker bind mount creates when the host "
            + "file does not exist -- fails startup, naming the path")
    void a_directory_where_a_file_is_expected_fails_startup_naming_the_path() {
        assertThatThrownBy(() -> boot(tempDir))
                .hasStackTraceContaining(tempDir.toString())
                .hasStackTraceContaining("not a regular file");
    }

    @Test
    @DisplayName("a scenario file declaring CONFLICT, which M-Pesa never answers, fails startup naming the file and the field")
    void a_scenario_this_operator_cannot_play_fails_startup() throws IOException {
        Path file = tempDir.resolve("scenario.json");
        Files.writeString(file, """
            {"rules":[{"scenario":{"name":"conflict","onSubmit":{"outcome":"CONFLICT"}}}]}""");

        assertThatThrownBy(() -> boot(file))
                .hasStackTraceContaining(file.toString())
                .hasStackTraceContaining("rules.[0].scenario.onSubmit.outcome");
    }

    private static void boot(Path scenarioFile) {
        SpringApplication app = new SpringApplication(MpesaSimulatorApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run("--nkap.scenario.file=" + scenarioFile);
    }
}

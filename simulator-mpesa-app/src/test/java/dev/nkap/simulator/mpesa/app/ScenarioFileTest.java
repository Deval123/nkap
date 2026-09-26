package dev.nkap.simulator.mpesa.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.testsupport.LoopbackOnly;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * The startup file load, end to end, as {@code simulator}'s own {@code ScenarioFileTest} proves it
 * for MTN: the file is a seed, not a second source of truth, and {@code GET /_nkap/scenarios} is
 * the one place ever asked what is active. The file is written in M-Pesa's vocabulary, so this
 * also proves the copied loader binds M-Pesa's declaration document rather than MTN's.
 */
// The address its tests dial, not the wildcard Tomcat binds by default (issue #221): see
// LoopbackOnly for what another process could otherwise do with 127.0.0.1 on this port.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=" + LoopbackOnly.ADDRESS)
class ScenarioFileTest {

    private static final Path FILE = createScenarioFile();

    private static Path createScenarioFile() {
        try {
            Path file = Files.createTempFile("nkap-scenario", ".json");
            Files.writeString(file, """
                {"rules":[{"scenario":{"name":"from-file","onQuery":[{"status":"STILL_PROCESSING"}]}}]}""");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void scenarioFile(DynamicPropertyRegistry registry) {
        registry.add("nkap.scenario.file", FILE::toString);
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private RestClient client;

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    @DisplayName("the application under test binds 127.0.0.1 alone, so no other listener can take the address its tests dial")
    void it_binds_loopback_only() throws Exception {
        LoopbackOnly.assertBoundToLoopbackOnly(port);
    }

    @Test
    @DisplayName("a well-formed file is active before the first request, and a POST afterwards replaces it")
    void the_file_is_active_first_and_a_post_replaces_it() {
        client = RestClient.create();

        // The first call this test makes: had the file not been applied by the time the context
        // finished starting, this would see the default scenario instead.
        JsonNode declared = get();
        assertThat(declared.get("rules")).hasSize(1);
        assertThat(declared.at("/rules/0/scenario/name").asText()).isEqualTo("from-file");
        assertThat(declared.at("/rules/0/scenario/onQuery/0/status").asText()).isEqualTo("STILL_PROCESSING");

        client.post().uri(base() + "/_nkap/scenarios")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""
                {"rules":[{"scenario":{"name":"from-post"}}]}""")
            .retrieve().toBodilessEntity();

        assertThat(get().at("/rules/0/scenario/name").asText())
            .as("a POST replaces what the file established exactly as it replaces an earlier POST")
            .isEqualTo("from-post");
    }

    private JsonNode get() {
        String body = client.get().uri(base() + "/_nkap/scenarios").retrieve().body(String.class);
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("response was not JSON: " + body, e);
        }
    }
}

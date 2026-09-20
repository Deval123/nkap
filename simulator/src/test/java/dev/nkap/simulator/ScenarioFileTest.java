package dev.nkap.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The startup file load, end to end (issue #99): the file is a seed, not a second source of
 * truth. {@code GET /_nkap/scenarios} is the one place ever asked what is active, and it
 * answers the same way regardless of whether a file, a {@code POST}, or nothing at all
 * established what it reports — that is the guarantee ADR 0002's amendment records, and the
 * point of every assertion below going through it rather than reading the file back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScenarioFileTest {

    private static final Path FILE = createScenarioFile();

    private static Path createScenarioFile() {
        try {
            Path file = Files.createTempFile("nkap-scenario", ".json");
            Files.writeString(file, """
                {"rules":[{"scenario":{"name":"from-file","onSubmit":{"outcome":"NO_RESPONSE"}}}]}""");
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
    @DisplayName("a well-formed file is active before the first request, and a POST afterwards replaces it -- "
            + "the assertion that pins issue #99's decision: a file is a seed, never a second source of truth")
    void the_file_is_active_first_and_a_post_replaces_it() {
        client = RestClient.create();

        // The very first call this test makes against the running simulator: if the file had
        // not already been applied by the time the context finished starting, this would see
        // the default scenario instead, not "from-file".
        JsonNode declared = get();
        assertThat(declared.get("rules")).hasSize(1);
        assertThat(declared.get("rules").get(0).get("scenario").get("name").asText()).isEqualTo("from-file");

        client.post().uri(base() + "/_nkap/scenarios")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""
                {"rules":[{"scenario":{"name":"from-post"}}]}""")
            .retrieve().toBodilessEntity();

        JsonNode afterPost = get();
        assertThat(afterPost.get("rules")).hasSize(1);
        assertThat(afterPost.get("rules").get(0).get("scenario").get("name").asText())
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

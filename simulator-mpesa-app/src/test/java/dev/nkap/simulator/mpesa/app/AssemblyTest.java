package dev.nkap.simulator.mpesa.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * The deployable assembles the core and M-Pesa's face into one application that answers on both
 * the operator's routes and the control plane. What each route does is {@code simulator-mpesa}'s
 * to test, and is not re-tested here: this proves only that they are all present, in one
 * context, wired to the same state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssemblyTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private RestClient client;

    private String base() {
        return "http://localhost:" + port;
    }

    @BeforeEach
    void reset() {
        client = RestClient.builder().defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
        client.delete().uri(base() + "/_nkap/scenarios").retrieve().toBodilessEntity();
        client.delete().uri(base() + "/_nkap/state").retrieve().toBodilessEntity();
    }

    @Test
    @DisplayName("one application serves the token route, both STK Push routes and the control plane, "
            + "and the control plane sees what the operator routes received")
    void the_face_and_the_control_plane_share_one_context() {
        ResponseEntity<String> token = client.get().uri(base() + "/oauth/v1/generate?grant_type=client_credentials")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                        .encodeToString("assembly-key:assembly-secret".getBytes(StandardCharsets.UTF_8)))
                .retrieve().toEntity(String.class);
        assertThat(token.getStatusCode().value()).isEqualTo(200);
        String bearer = "Bearer " + read(token).get("access_token").asText();

        ResponseEntity<String> submitted = client.post().uri(base() + "/mpesa/stkpush/v1/processrequest")
                .header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("BusinessShortCode", 174379, "Password", "cGFzc3dvcmQ=", "Timestamp", "20260925120000",
                        "TransactionType", "CustomerPayBillOnline", "Amount", "1", "PartyB", "174379",
                        "PhoneNumber", "254708374149", "CallBackURL", "https://example.invalid/nkap/mpesa", "AccountReference", "assembly"))
                .retrieve().toEntity(String.class);
        assertThat(submitted.getStatusCode().value()).isEqualTo(200);
        String checkoutRequestId = read(submitted).get("CheckoutRequestID").asText();

        ResponseEntity<String> queried = client.post().uri(base() + "/mpesa/stkpushquery/v1/query")
                .header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("BusinessShortCode", 174379, "Password", "cGFzc3dvcmQ=", "Timestamp", "20260925120000",
                        "CheckoutRequestID", checkoutRequestId))
                .retrieve().toEntity(String.class);
        assertThat(queried.getStatusCode().value()).isEqualTo(200);
        assertThat(read(queried).get("ResultCode").asInt()).isZero();

        JsonNode received = read(client.get().uri(base() + "/_nkap/received").retrieve().toEntity(String.class));
        assertThat(received.at("/lastTokenRequest/consumerKey").asText()).isEqualTo("assembly-key");
        assertThat(received.at("/lastSubmission/businessShortCode").asText()).isEqualTo("174379");
        assertThat(read(client.get().uri(base() + "/_nkap/submissions").retrieve().toEntity(String.class))
                .get("count").asInt()).isEqualTo(1);
        assertThat(read(client.get().uri(base() + "/_nkap/state/" + checkoutRequestId).retrieve()
                .toEntity(String.class)).get("queryCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the default port is 8082, not the MTN simulator's 8081, so the two run side by side with no flag")
    void the_default_port_is_not_the_mtn_simulators() throws Exception {
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml")).get(0);

        assertThat(yaml.getProperty("server.port")).isEqualTo(8082);
    }

    private JsonNode read(ResponseEntity<String> answer) {
        try {
            return json.readTree(answer.getBody());
        } catch (Exception e) {
            throw new AssertionError("response was not JSON: " + answer.getBody(), e);
        }
    }
}

package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.server.support.PostgresSpringBootIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * A deployment that sets no provider variable at all starts, with no adapter, and answers its
 * health check — enough to provision an API key before any operator credential exists, which
 * every deployment path in this repository promises.
 *
 * <p>This used to be false and was only ever caught by hand. {@code application.yml} defaulted
 * MTN Cameroon's country to {@code cm}, so a context given nothing built {@code mtn-cm} with no
 * credentials and refused to start; each deployment path had to know to blank it. Every slot's
 * country now defaults to blank. This test is what keeps it that way: it declares no provider
 * property, so anything that makes a declared slot configure itself fails here.
 */
class EmptyInstallIT extends PostgresSpringBootIT {

    @Autowired
    AdapterRegistry adapters;

    @Value("${local.management.port}")
    private int managementPort;

    @Test
    @DisplayName("a deployment that sets no provider variable starts, registers no adapter, and serves /actuator/health")
    void an_empty_install_starts_with_no_adapter_and_is_healthy() {
        assertThat(adapters.configuredProviders()).isEmpty();

        ResponseEntity<String> health = new RestTemplate()
                .getForEntity("http://localhost:" + managementPort + "/actuator/health", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"UP\"");
    }
}

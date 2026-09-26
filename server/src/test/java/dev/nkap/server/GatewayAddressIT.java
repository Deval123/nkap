package dev.nkap.server;

import dev.nkap.server.support.PostgresSpringBootIT;
import dev.nkap.testsupport.LoopbackOnly;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The gateway every {@link PostgresSpringBootIT} boots binds the address its tests dial, on both of
 * its ports (issue #221). {@code DisbursementApiIT}, {@code PaymentWebhookApiIT} and
 * {@code RefundApiIT} boot their own contexts and assert the same of them.
 */
class GatewayAddressIT extends PostgresSpringBootIT {

    @LocalServerPort
    private int port;

    @Value("${local.management.port}")
    private int managementPort;

    @Test
    @DisplayName("the gateway under test binds 127.0.0.1 alone on its API port, so no other listener can take the address its tests dial")
    void the_api_port_is_bound_to_loopback_only() throws Exception {
        LoopbackOnly.assertBoundToLoopbackOnly(port);
    }

    @Test
    @DisplayName("the gateway under test binds 127.0.0.1 alone on its management port as well")
    void the_management_port_is_bound_to_loopback_only() throws Exception {
        LoopbackOnly.assertBoundToLoopbackOnly(managementPort);
    }
}

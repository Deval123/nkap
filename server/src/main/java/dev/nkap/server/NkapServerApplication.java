package dev.nkap.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The Nkap gateway.
 *
 * <p>A payment is created over HTTP, handed to an operator through an adapter, and carried
 * to {@code SUCCEEDED} by a callback that {@code query()} confirms; settlement posts to a
 * double-entry ledger. The ledger, the payments and their history, and the idempotency
 * store live in <strong>PostgreSQL</strong>, with the invariants as schema constraints —
 * see {@code db/migration}. There is no in-memory mode.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NkapServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NkapServerApplication.class, args);
    }
}

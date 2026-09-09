package dev.nkap.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The Nkap gateway.
 *
 * <p>This is the first vertical slice: a payment is created over HTTP, handed to an
 * operator through an adapter, and its state can be read back. Nothing settles yet and
 * <strong>nothing is written to the ledger</strong> — only {@code SUCCEEDED} moves money,
 * and no payment reaches {@code SUCCEEDED} without the callback path, which is the next
 * slice. Stores are in memory; that is a step towards PostgreSQL, not a feature.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NkapServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NkapServerApplication.class, args);
    }
}

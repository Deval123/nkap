package dev.nkap.server;

import dev.nkap.core.idempotency.IdempotencyStore;
import dev.nkap.core.idempotency.InMemoryIdempotencyStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The in-memory stores, registered as beans because they live in {@code core} and cannot
 * carry a Spring annotation.
 *
 * <p>They are a step towards PostgreSQL, which implements the same interfaces. There is no
 * flag that selects in-memory and no documented mode — swapping in the database swaps
 * these beans, nothing else.
 */
@Configuration
class StoresConfiguration {

    @Bean
    IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }
}

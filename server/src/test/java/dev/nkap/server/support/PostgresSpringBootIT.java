package dev.nkap.server.support;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for {@code @SpringBootTest} integration tests: a full application context wired to
 * the shared PostgreSQL container, Flyway migrating it on start. Skipped entirely when
 * Docker is not available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(DockerAvailable.class)
public abstract class PostgresSpringBootIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresDatabase db = PostgresDatabase.shared();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        // The reconciler's scheduled pass would otherwise fire mid-test and resolve or
        // reschedule a payment an assertion is looking at. ReconcilerIT drives runOnce()
        // directly and does not use this base.
        registry.add("nkap.reconciler.enabled", () -> "false");
    }
}

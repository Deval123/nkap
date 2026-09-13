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
        // Every subclass with even one differing @DynamicPropertySource value gets its own
        // cached Spring context, and so its own Hikari pool, for the life of the JVM — none
        // of these tests drive real concurrency (a sequential TestRestTemplate call at a
        // time), so Hikari's default of 10 per context was pure waste that added up: enough
        // distinct contexts and the shared PostgreSQL container hit its own connection
        // limit ("sorry, too many clients already"), failing tests that never touched the
        // change that pushed the count over. Small and shared across every subclass here.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        // The reconciler's scheduled pass would otherwise fire mid-test and resolve or
        // reschedule a payment an assertion is looking at. ReconcilerIT drives runOnce()
        // directly and does not use this base.
        registry.add("nkap.reconciler.enabled", () -> "false");
        // Same reasoning, for the outbox relay: a scheduled pass firing mid-test could
        // deliver or dead-letter an event an assertion is still looking at, or open
        // connections to a receiver a test has already closed. OutboxRelayIT drives
        // runOnce() directly and does not use this base.
        registry.add("nkap.webhooks.enabled", () -> "false");
    }
}

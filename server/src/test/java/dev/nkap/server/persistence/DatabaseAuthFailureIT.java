package dev.nkap.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Issue #123's second half: the {@code depends_on} guard already stops the gateway starting
 * against a database it cannot migrate; what was missing is a sentence naming <em>why</em>.
 * Runs a real {@link SpringApplication} — real Postgres, real Flyway, the same {@code
 * spring.factories}-loaded failure-analysis pipeline {@code key-init} and {@code
 * webhook-init} run into for real — against a deliberately wrong password, and reads what an
 * operator would actually see: {@link LoggingFailureAnalysisReporter}'s own log line. A typo
 * in {@code META-INF/spring.factories} would leave {@link WrongDatabasePasswordFailureAnalyzer}
 * silently unregistered, exactly like #123 itself; calling it directly would not catch that.
 */
@ExtendWith(DockerAvailable.class)
class DatabaseAuthFailureIT {

    private static final String WRONG_PASSWORD = "not-the-real-database-password-000000";

    @Test
    @DisplayName("a wrong database password is diagnosed as rejected credentials, not left as a bare stack trace")
    void wrong_password_is_diagnosed_by_the_registered_analyzer() {
        PostgresDatabase shared = PostgresDatabase.shared();
        assertThat(shared.password()).isNotEqualTo(WRONG_PASSWORD);

        String report = reportFor(
                "spring.datasource.url=" + shared.jdbcUrl(),
                "spring.datasource.username=" + shared.username(),
                "spring.datasource.password=" + WRONG_PASSWORD);

        // Bounded to our own analyzer's block: Flyway's own log line above it already
        // names the username (never the password) as part of PostgreSQL's own error
        // message, which this test does not police -- issue #123 is about the sentence we
        // add, not about suppressing PostgreSQL's own.
        String ourOwnDiagnosis = report.substring(report.indexOf("APPLICATION FAILED TO START"));
        assertThat(ourOwnDiagnosis).contains("rejected");
        assertThat(ourOwnDiagnosis).doesNotContain(WRONG_PASSWORD);
        assertThat(ourOwnDiagnosis).doesNotContain(shared.password());
    }

    @Test
    @DisplayName("an unreachable database fails without being misdiagnosed as rejected credentials")
    void unreachable_database_is_not_diagnosed_as_a_credentials_problem() {
        String report = reportFor(
                // Port 1 on localhost: nothing answers, unlike a wrong password, where
                // PostgreSQL answers and refuses.
                "spring.datasource.url=jdbc:postgresql://localhost:1/nkap",
                "spring.datasource.username=nkap",
                "spring.datasource.password=irrelevant-here");

        assertThat(report).doesNotContain("rejected");
    }

    /**
     * Boots a minimal real application — only the datasource/Flyway autoconfiguration a
     * wrong password needs to reproduce — expects it to fail, and captures console output
     * the way {@code docker compose logs key-init} would show it. Not a {@code LogCapture}
     * appender: {@code SpringApplication} reinitialises logging as part of starting up,
     * which detaches any appender attached before {@code run()} is called.
     */
    private static String reportFor(String... properties) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            SpringApplication app = new SpringApplication(MinimalDatasourceApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            String[] args = Arrays.stream(properties).map(property -> "--" + property).toArray(String[]::new);

            assertThatThrows(() -> app.run(args));
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static void assertThatThrows(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected the application to fail to start");
    }

    @Configuration
    @Import({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    static class MinimalDatasourceApplication {}
}

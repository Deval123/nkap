package dev.nkap.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.jdbc.UncategorizedSQLException;

class WrongDatabasePasswordFailureAnalyzerTest {

    private final WrongDatabasePasswordFailureAnalyzer analyzer = new WrongDatabasePasswordFailureAnalyzer();

    @Test
    @DisplayName("a 28P01 SQLSTATE, however deep in the cause chain, is diagnosed as rejected credentials")
    void invalid_password_sqlstate_is_diagnosed() {
        String wrongPassword = "not-the-real-password-000000";
        PSQLException rootCause = new PSQLException(
                "FATAL: password authentication failed for user \"nkap\"", PSQLState.INVALID_PASSWORD);
        UncategorizedSQLException wrapped =
                new UncategorizedSQLException("preparing connection", "n/a", rootCause);

        FailureAnalysis analysis = analyzer.analyze(wrapped);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("rejected").doesNotContain(wrongPassword);
        assertThat(analysis.getAction()).doesNotContain(wrongPassword);
    }

    @Test
    @DisplayName("an unreachable database is not diagnosed as rejected credentials")
    void unreachable_database_is_not_diagnosed_as_a_credentials_problem() {
        PSQLException connectionRefused = new PSQLException(
                "Connection to localhost:5432 refused", PSQLState.CONNECTION_UNABLE_TO_CONNECT);

        assertThat(analyzer.analyze(connectionRefused)).isNull();
    }

    @Test
    @DisplayName("a failure with no PSQLException anywhere in its cause chain is not diagnosed at all")
    void unrelated_failure_is_ignored() {
        assertThat(analyzer.analyze(new IllegalStateException("bean creation failed"))).isNull();
    }
}

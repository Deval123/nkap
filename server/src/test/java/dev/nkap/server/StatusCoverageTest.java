package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StatusCoverage}'s rule, kept by tests rather than by the demonstrations the pull request
 * made by hand. Every set is built here. None uses {@code OpenApiSpecIT.PROVED_ELSEWHERE}, so a
 * change to that constant cannot make one of these vacuous.
 */
class StatusCoverageTest {

    private static final String UNPRODUCED = "no test in OpenApiSpecIT produces it";
    private static final String STALE = "a stale exception";
    private static final String NOT_A_STATUS = "not a status code";

    private static String failure(Set<String> documented, Set<String> observed, Map<String, String> elsewhere) {
        Throwable thrown = catchThrowable(() -> StatusCoverage.assertCoverage(documented, observed, elsewhere));
        assertThat(thrown).as("the coverage check should have failed").isInstanceOf(AssertionError.class);
        return thrown.getMessage();
    }

    @Test
    @DisplayName("a documented status neither produced nor proved elsewhere fails, naming it")
    void an_unproduced_status_fails_naming_it() {
        String message = failure(
                Set.of("GET /payments/{reference} 200", "GET /payments/{reference} 418"),
                Set.of("GET /payments/{reference} 200"),
                Map.of());

        assertThat(message).contains(UNPRODUCED).contains("GET /payments/{reference} 418")
                .doesNotContain(STALE).doesNotContain(NOT_A_STATUS);
    }

    @Test
    @DisplayName("the same status, named as proved elsewhere, passes")
    void a_status_proved_elsewhere_passes() {
        assertThatCode(() -> StatusCoverage.assertCoverage(
                Set.of("GET /payments/{reference} 200", "GET /payments/{reference} 418"),
                Set.of("GET /payments/{reference} 200"),
                Map.of("GET /payments/{reference} 418", "SomeOtherIT")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an exception the spec no longer documents fails as a stale exception, and as nothing else")
    void a_stale_exception_fails_as_that_only() {
        String message = failure(
                Set.of("GET /balance 200"),
                Set.of("GET /balance 200"),
                Map.of("GET /balance 501", "SomeOtherIT"));

        assertThat(message).contains(STALE).contains("GET /balance 501")
                .doesNotContain(UNPRODUCED).doesNotContain(NOT_A_STATUS);
    }

    @Test
    @DisplayName("a documented response key that is not a status code, such as default, fails as not a status code")
    void a_default_response_key_fails() {
        String message = failure(
                Set.of("GET /balance 200", "GET /balance default"),
                Set.of("GET /balance 200"),
                Map.of());

        assertThat(message).contains(NOT_A_STATUS).contains("GET /balance default")
                .doesNotContain(UNPRODUCED).doesNotContain(STALE);
    }

    @Test
    @DisplayName("documented equal to produced plus proved elsewhere passes")
    void the_clean_case_passes() {
        assertThatCode(() -> StatusCoverage.assertCoverage(
                Set.of("GET /balance 200", "GET /balance 501", "POST /payments 201"),
                Set.of("GET /balance 200", "POST /payments 201"),
                Map.of("GET /balance 501", "SomeOtherIT")))
                .doesNotThrowAnyException();
    }
}

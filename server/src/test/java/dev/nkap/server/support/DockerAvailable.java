package dev.nkap.server.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Disables a test class when Docker is not reachable, before Spring or Testcontainers try
 * to use it — unless Docker is declared required, in which case missing Docker fails the
 * build instead.
 *
 * <p>The {@code *IT} tests need a real PostgreSQL and run it in a container. CI
 * ({@code ubuntu-latest}) has Docker; a developer machine may not. On a developer machine,
 * skipping is the right call: rather than fail the build there, {@code mvn verify} reports
 * these as skipped. What they prove — that the database refuses an unbalanced entry, an
 * {@code UPDATE}, a {@code DELETE} — is only real against PostgreSQL, so there is no
 * in-memory fallback to run instead.
 *
 * <p><strong>Skipping is a developer's choice; it is never an outcome of the build.</strong>
 * The day the CI runner image changes, or Testcontainers cannot pull {@code postgres:16},
 * or a network policy blocks it, these tests would otherwise vanish from the build with
 * nothing to notice — a green build that quietly stopped proving the one thing that
 * distinguishes this project from an ordinary gateway. The workflow passes
 * {@code -Dnkap.docker.required=true}; when that system property is set, a missing or
 * unreachable Docker fails the test right here, naming which check could not run, instead
 * of skipping it or failing in a shell step someone could delete without understanding why.
 */
public final class DockerAvailable implements ExecutionCondition {

    private static final String REQUIRE_DOCKER_PROPERTY = "nkap.docker.required";

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Docker is available");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        boolean required = Boolean.getBoolean(REQUIRE_DOCKER_PROPERTY);
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ENABLED;
            }
        } catch (RuntimeException probeFailed) {
            if (required) {
                throw new IllegalStateException(
                        "Docker is required (-D" + REQUIRE_DOCKER_PROPERTY + "=true) but the probe failed; "
                                + "CI must never skip the PostgreSQL integration tests: " + probeFailed.getMessage(),
                        probeFailed);
            }
            return ConditionEvaluationResult.disabled("Docker probe failed: " + probeFailed.getMessage());
        }
        if (required) {
            throw new IllegalStateException(
                    "Docker is required (-D" + REQUIRE_DOCKER_PROPERTY + "=true) but is not available; "
                            + "CI must never skip the PostgreSQL integration tests");
        }
        return ConditionEvaluationResult.disabled("Docker is not available; skipping the PostgreSQL integration test");
    }
}

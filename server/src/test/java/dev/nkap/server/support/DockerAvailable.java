package dev.nkap.server.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Disables a test class when Docker is not reachable, before Spring or Testcontainers try
 * to use it.
 *
 * <p>The {@code *IT} tests need a real PostgreSQL and run it in a container. CI
 * ({@code ubuntu-latest}) has Docker; a developer machine may not. Rather than fail the
 * build there, {@code mvn verify} reports these as skipped. What they prove — that the
 * database refuses an unbalanced entry, an {@code UPDATE}, a {@code DELETE} — is only real
 * against PostgreSQL, so there is no in-memory fallback to run instead.
 */
public final class DockerAvailable implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Docker is available");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ENABLED;
            }
        } catch (RuntimeException probeFailed) {
            return ConditionEvaluationResult.disabled("Docker probe failed: " + probeFailed.getMessage());
        }
        return ConditionEvaluationResult.disabled("Docker is not available; skipping the PostgreSQL integration test");
    }
}

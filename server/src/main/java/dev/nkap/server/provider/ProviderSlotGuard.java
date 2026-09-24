package dev.nkap.server.provider;

import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the gateway when the process environment names a provider slot that does not
 * exist ({@link DeclaredProviderSlots}).
 *
 * <p>A {@link BeanFactoryPostProcessor}, so it runs before any bean is created: the failure comes
 * before an adapter is built or a database is contacted, and reaches whoever started the process
 * even when PostgreSQL is not reachable yet.
 *
 * <p>This is the only code in the gateway that enumerates the process environment, and that
 * environment holds every operator credential. So it takes the variable <em>names</em> from the
 * {@code systemEnvironment} property source and nothing else. It never asks for a value, holds a
 * value, logs anything, or puts the environment or a property source into a message or a cause.
 * The management endpoints leave {@code env} off for the same reason (application.yml).
 *
 * <p>Only the process environment is in scope. It is where every deployment path this repository
 * documents puts provider variables: the chart, both compose files, {@code docker run -e}. The same
 * names passed as JVM system properties or command-line arguments are not checked.
 */
@Component
class ProviderSlotGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    /** Null until the environment is known; the check refuses to run on nothing. */
    private List<String> variableNames;

    @Override
    public void setEnvironment(Environment environment) {
        if (environment instanceof ConfigurableEnvironment configurable) {
            PropertySource<?> system = configurable.getPropertySources()
                    .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            if (system instanceof EnumerablePropertySource<?> enumerable) {
                variableNames = List.of(enumerable.getPropertyNames());
            }
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Fail closed: a check that could not see the environment must not pass as if it had.
        if (variableNames == null) {
            throw new IllegalStateException("Refusing to start: the process environment's variable names"
                    + " could not be read, so provider slots cannot be checked.");
        }
        DeclaredProviderSlots.requireOnlyDeclared(variableNames);
    }
}

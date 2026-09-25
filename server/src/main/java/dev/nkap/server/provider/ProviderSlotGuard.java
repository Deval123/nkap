package dev.nkap.server.provider;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the gateway when the process environment, or the imported credentials
 * directory, names a provider slot that does not exist ({@link DeclaredProviderSlots}).
 *
 * <p>A {@link BeanFactoryPostProcessor}, so it runs before any bean is created: the failure comes
 * before an adapter is built or a database is contacted, and reaches whoever started the process
 * even when PostgreSQL is not reachable yet.
 *
 * <p>This and {@link CredentialSourceGuard} are the only code in the gateway that enumerates the
 * process environment, and that environment holds every operator credential. So both take
 * <em>names</em> and nothing else, through {@link CredentialNames}: the variable names of the
 * {@code systemEnvironment} property source and the file names of the imported directory. It never asks for a value, holds a value,
 * logs anything, or puts the environment or a property source into a message or a cause.
 * The management endpoints leave {@code env} off for the same reason (application.yml).
 *
 * <p>The process environment and the imported directory are in scope: between them, every
 * deployment path this repository documents — the chart, both compose files, {@code docker run -e},
 * a mounted secret. The same names passed as JVM system properties or command-line arguments are
 * not checked.
 */
@Component
class ProviderSlotGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    /** Null until the environment is known; the check refuses to run on nothing. */
    private CredentialNames names;

    @Override
    public void setEnvironment(Environment environment) {
        names = CredentialNames.of(environment);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Fail closed: a check that could not see the environment must not pass as if it had.
        if (names == null) {
            throw new IllegalStateException("Refusing to start: the process environment's variable names"
                    + " could not be read, so provider slots cannot be checked.");
        }
        DeclaredProviderSlots.requireOnlyDeclared(names.variables(), names.files());
    }
}

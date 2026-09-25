package dev.nkap.server.provider;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the gateway when one name is supplied both as an environment variable and as a
 * file in the imported credentials directory ({@link CredentialNames}).
 *
 * <p>Refused, not arbitrated. Spring does rank the two — the variable wins, measured — but a
 * documented precedence is a trap the day credentials are read at the moment they are used rather
 * than once at startup: startup would validate the variable while the credential actually sent
 * came from the file. Two values, one checked and the other used, found on the day someone rotates
 * one of them. Two things claiming one name get the answer two installations claiming one country
 * already get from {@link ConfiguredAdapterRegistry}: configure only one.
 *
 * <p>Every name, not only provider ones: {@code NKAP_DB_PASSWORD} as both a variable and a file is
 * the same ambiguity. The message names the variable and the two places it was found, and never a
 * value or a file's contents; {@link CredentialNames} holds names only, so there is none to leak.
 *
 * <p>A {@link BeanFactoryPostProcessor} for the reason {@link ProviderSlotGuard} is one: the failure
 * comes before any adapter is built or the database is contacted.
 */
@Component
class CredentialSourceGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private CredentialNames names;

    @Override
    public void setEnvironment(Environment environment) {
        names = CredentialNames.of(environment);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Fail closed, as ProviderSlotGuard does: a check that could not look must not pass.
        if (names == null) {
            throw new IllegalStateException("Refusing to start: the process environment's variable names"
                    + " could not be read, so credential sources cannot be checked.");
        }
        requireOneSource(names);
    }

    /** Fails if any name is both a variable and a file, naming every such name at once, sorted. */
    static void requireOneSource(CredentialNames names) {
        Set<String> both = new TreeSet<>(names.files());
        both.retainAll(Set.copyOf(names.variables()));
        if (both.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Refusing to start: " + String.join(", ", List.copyOf(both))
                + (both.size() == 1 ? " is" : " are") + " set both as an environment variable and as a file in the"
                + " imported credentials directory. The gateway does not choose between two sources for one"
                + " name. Remove one of them: the variable or the file.");
    }
}

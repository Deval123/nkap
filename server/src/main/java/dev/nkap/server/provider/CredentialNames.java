package dev.nkap.server.provider;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The names of the two doors a credential can come through, and nothing else: the process
 * environment's variable names, and the file names in the directory {@code application.yml}
 * imports as a config tree ({@code NKAP_SECRETS_DIR}, {@code /run/secrets} by default).
 *
 * <p>A file there is named after the variable it stands in for, and feeds the same
 * {@code application.yml} placeholder: {@code NKAP_PROVIDER_MPESA_KE_PASSKEY} as a file is read
 * exactly where the variable of that name would be. Only an exact name does; a file named in
 * lower case, or with dots, is read by nothing.
 *
 * <p>Both lists come from {@link EnumerablePropertySource#getPropertyNames()}. Nothing here asks a
 * property source for a value, so none is ever held, and no caller can put one in a message.
 *
 * @param variables the {@code systemEnvironment} property source's names
 * @param files     every imported config tree's names; empty when the directory is absent
 */
record CredentialNames(List<String> variables, List<String> files) {

    CredentialNames {
        variables = List.copyOf(variables);
        files = List.copyOf(files);
    }

    /** Null when the environment cannot be enumerated; a guard refuses to run on nothing. */
    static CredentialNames of(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return null;
        }
        PropertySource<?> system = configurable.getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (!(system instanceof EnumerablePropertySource<?> enumerable)) {
            return null;
        }
        List<String> files = new ArrayList<>();
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (source instanceof ConfigTreePropertySource tree) {
                files.addAll(List.of(tree.getPropertyNames()));
            }
        }
        return new CredentialNames(List.of(enumerable.getPropertyNames()), files);
    }
}

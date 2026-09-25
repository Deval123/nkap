package dev.nkap.server.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.boot.env.ConfigTreePropertySource.Option;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * Finds the file in the imported credentials directory that supplied a bound property, if one
 * did, and reads it again on demand.
 *
 * <p><strong>Which file is not worked out here, it is looked up.</strong> A credential file is
 * named after the variable it stands in for (slice A), and which variable feeds which property is
 * written in exactly one place: {@code application.yml}'s placeholder, such as
 * {@code passkey: ${NKAP_PROVIDER_MPESA_KE_PASSKEY:}}. So this asks Spring's own
 * configuration-property sources, in the order the binder uses, which one supplies the property.
 * If it is {@code application.yml} and its raw value is a single placeholder, the placeholder
 * names the file. A property supplied any other way (a variable, a relaxed-name variable such as
 * {@code NKAP_PROVIDER_MPESA_INSTALLATIONS_0_PASSKEY}, a system property, or a default) has no
 * file, and a process cannot see it change.
 *
 * <p><strong>The re-read is Spring's own read.</strong> The environment's config tree caches each
 * file's content on first read and never reads it again. This keeps a second one over the same
 * directory with {@link Option#ALWAYS_READ}, and with {@link Option#AUTO_TRIM_TRAILING_NEW_LINE},
 * the option Spring's config-tree import uses. A re-read value is therefore trimmed exactly as the
 * startup value was: one trailing newline removed, nothing else. The rule is not restated here.
 *
 * <p>Nothing here holds a value. {@link CredentialFile#read()} returns one to its caller, and no
 * message built here contains one.
 */
final class CredentialFileReader {

    /** A raw value that is one placeholder and nothing else: {@code ${NAME}} or {@code ${NAME:default}}. */
    private static final Pattern SINGLE_PLACEHOLDER = Pattern.compile("^\\$\\{([A-Za-z0-9_]+)(?::[^}]*)?}$");

    private final Iterable<ConfigurationPropertySource> sources;
    private final List<ConfigTreePropertySource> trees;

    private CredentialFileReader(Iterable<ConfigurationPropertySource> sources, List<ConfigTreePropertySource> trees) {
        this.sources = sources;
        this.trees = trees;
    }

    /** A reader over the environment's imported credentials directories; none when it has none. */
    static CredentialFileReader of(Environment environment) {
        List<ConfigTreePropertySource> trees = new ArrayList<>();
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (source instanceof ConfigTreePropertySource tree) {
                    trees.add(new ConfigTreePropertySource(tree.getName() + " (re-read)", tree.getSource(),
                            Option.ALWAYS_READ, Option.AUTO_TRIM_TRAILING_NEW_LINE));
                }
            }
        }
        return new CredentialFileReader(ConfigurationPropertySources.get(environment), trees);
    }

    /** The file that supplied {@code property}, e.g. {@code nkap.provider.mpesa.installations[0].passkey}. */
    Optional<CredentialFile> fileFor(String property) {
        ConfigurationPropertyName name = ConfigurationPropertyName.of(property);
        for (ConfigurationPropertySource source : sources) {
            ConfigurationProperty supplied = source.getConfigurationProperty(name);
            if (supplied == null) {
                continue;
            }
            // The first source that has it is the one the binder used. Only a single placeholder
            // can name a file; anything else is a value this process cannot see change.
            Matcher placeholder = SINGLE_PLACEHOLDER.matcher(String.valueOf(supplied.getValue()));
            return placeholder.matches() ? fileNamed(placeholder.group(1)) : Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<CredentialFile> fileNamed(String variable) {
        for (ConfigTreePropertySource tree : trees) {
            Origin origin = tree.getOrigin(variable);
            if (origin instanceof TextResourceOrigin text && text.getResource() != null) {
                try {
                    return Optional.of(new CredentialFile(variable, text.getResource().getFile().toPath(), tree));
                } catch (IOException notAFile) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * One credential file: its name, which is the variable it stands in for, and where it is.
     *
     * <p>{@link #toString()} is the generated one on purpose: every component is a name or a
     * location, none of them secret.
     */
    record CredentialFile(String name, Path path, ConfigTreePropertySource tree) {

        /**
         * What the file is now, without reading it: its real path, modification time and size. Null
         * if it cannot be looked at, gone or unreadable, so a caller on the payment path never sees
         * a throw from here.
         */
        Stamp stamp() {
            try {
                Path real = path.toRealPath();
                return new Stamp(real, Files.getLastModifiedTime(real), Files.size(real));
            } catch (IOException | SecurityException gone) {
                return null;
            }
        }

        /**
         * The file's value now, trimmed as at startup.
         *
         * @throws UnreadableCredentialException if the file is gone or cannot be read. Its message
         *         names the file and nothing else.
         */
        String read() {
            try {
                Object value = tree.getProperty(name);
                if (value == null) {
                    throw new UnreadableCredentialException(name);
                }
                return value.toString();
            } catch (RuntimeException unreadable) {
                if (unreadable instanceof UnreadableCredentialException known) {
                    throw known;
                }
                throw new UnreadableCredentialException(name);
            }
        }
    }

    /**
     * What a credential file is at one moment, compared to decide whether to read it again: its
     * real path, with every symbolic link resolved, its modification time, and its size. Any of the
     * three differing means the file changed.
     *
     * <p>Each deployment changes a different one, which is why no one of them is enough. A mounted
     * Kubernetes Secret is a link through {@code ..data} into a timestamped directory. An update
     * writes a new directory and re-points {@code ..data}; measured on a cluster, the file's
     * modification time stayed the same, and only the <strong>real path</strong> changed. A file
     * bind-mounted by compose, or replaced in place, is not a link: its real path stays the same,
     * and its <strong>modification time</strong> changes. The <strong>size</strong> catches a value
     * replaced by one of another length within the same timestamp, and costs nothing.
     *
     * <p>Rejected, so they are not proposed again. Hashing the contents would detect everything, but
     * it reads the credential on every call, which is what the gate exists to avoid: ADR 0015's
     * first cost is a credential living in the heap, and a hash on the payment path makes it worse.
     * Comparing the directory's modification time is right for Kubernetes and wrong for a file
     * replaced in place, trading one blind deployment for another.
     *
     * <p>No credential is in it, only a location, a time and a size, so it keeps its generated
     * {@code toString()}: the masking every credential-holding record has does not apply.
     */
    record Stamp(Path realPath, FileTime modified, long size) {
    }

    /** A credential file that could not be read. Deliberately carries no cause: see {@link CredentialFile#read()}. */
    static final class UnreadableCredentialException extends RuntimeException {

        private final String file;

        UnreadableCredentialException(String file) {
            super(file + " could not be read");
            this.file = file;
        }

        String file() {
            return file;
        }
    }
}

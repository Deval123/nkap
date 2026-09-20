package dev.nkap.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.server.outbox.OutboxRelayProperties;
import dev.nkap.server.provider.MtnProperties;
import dev.nkap.server.reconcile.ReconcilerProperties;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Issue #90's own reason for existing, extended from issue #88's, and again by issue #99
 * once {@code nkap-simulator} got its first {@code nkap.*} setting of its own: {@code
 * docs/configuration-reference.md} is a file in the repository, and this is what keeps it
 * describing the running application rather than merely hoping to. Three checks, in the same
 * spirit as {@code OpenApiSpecIT} but needing no Spring context at all — everything here is
 * plain reflection over the compiled {@code @ConfigurationProperties} records, a plain text
 * scan of {@code server/src/main/java} and {@code simulator/src/main/java}, and plain text
 * parsing of files already on disk.
 *
 * <h2>What this proves</h2>
 *
 * <ol>
 *   <li>Every leaf property {@link ReconcilerProperties}, {@link OutboxRelayProperties} and
 *       {@link MtnProperties} actually bind — walked recursively through nested records and
 *       {@code List<record>} fields — has a row in {@code docs/configuration-reference.md},
 *       and every {@code nkap.*} row in that file corresponds to a real property. Neither
 *       direction is optional: a stale row is exactly as wrong as an undocumented one.</li>
 *   <li>The same, for every {@code nkap.*} property read directly with {@code @Value} or
 *       {@code @ConditionalOnProperty} rather than through a record — found by
 *       {@link #scanDirectlyBoundProperties(Path, String)} scanning {@code server}'s and
 *       {@code simulator}'s source trees, not by a hand-kept list. See that method's own
 *       javadoc for exactly what it can and cannot find.</li>
 *   <li>For {@link ReconcilerProperties} and {@link OutboxRelayProperties} — where
 *       {@code application.yml} always supplies a concrete literal, so "the default" is a
 *       single, unambiguous fact — the reference's own Default column is checked against
 *       that literal, read straight out of {@code application.yml} rather than retyped here.
 *       {@link #inline_value_defaults_agree_with_their_module_application_yml_and_the_reference()}
 *       does the same for every directly-bound {@code @Value} property that carries an
 *       inline default — {@code nkap.webhooks.allow-insecure-endpoint-url} and
 *       {@code nkap.scenario.file} today — against the {@code application.yml} of whichever
 *       module {@link #scanDirectlyBoundProperties(Path, String)} found it in. A property
 *       with no inline default ({@code nkap.provider.default}) or whose reference row is
 *       deliberately prose rather than a literal ({@code nkap.public-base-url}: {@code
 *       *(blank; set per deployment)*}) is skipped on whichever side has nothing to compare —
 *       silently passing there is correct, not a gap, since there is no fact to disagree
 *       with.</li>
 * </ol>
 *
 * <h2>What this does <strong>not</strong> cover, and why</h2>
 *
 * <p>{@code MtnProperties.Installation}'s fields other than {@code requestTimeout} have no
 * single meaningful "default" to check: most are blank on purpose (real credentials are
 * supplied per-deployment, never committed), and the two installation slots
 * {@code application.yml} declares do not even agree with each other (the first defaults
 * {@code country} to {@code cm}, the second to blank) — there is no one literal for the table
 * to be checked against. {@code requestTimeout} is the one field in that record with an
 * unambiguous default regardless of slot ({@code @DefaultValue("PT20S")}, the only source for
 * it at all in the second installation, which sets no {@code request-timeout} key in
 * {@code application.yml}), so it alone is checked, in {@link #the_one_mtn_default_that_is_unambiguous_is_correct()}.
 *
 * <p>The Spring settings the reference also documents ({@code server.port},
 * {@code spring.datasource.*}, {@code management.server.port}) are not this project's own
 * {@code @ConfigurationProperties} records — there is nothing of ours to reflect on — so they
 * are outside every check here, exactly as the reference's own scope paragraph says.
 */
class ConfigurationReferenceTest {

    private static final Path REFERENCE = Path.of("..", "docs", "configuration-reference.md");
    private static final Path APPLICATION_YML = Path.of("src", "main", "resources", "application.yml");
    private static final Path SIMULATOR_APPLICATION_YML =
            Path.of("..", "simulator", "src", "main", "resources", "application.yml");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path SIMULATOR_MAIN_JAVA = Path.of("..", "simulator", "src", "main", "java");
    private static final String RUN_FROM_SERVER = "the server module (mvn -pl server test)";
    private static final String RUN_FROM_SIMULATOR =
            "the server module, with the simulator module checked out beside it";

    @Test
    @DisplayName("every nkap.* property bound by a @ConfigurationProperties record, or read directly with @Value/@ConditionalOnProperty, has exactly one row in docs/configuration-reference.md")
    void every_property_is_documented_and_every_documented_property_is_real() throws IOException {
        Set<String> fromCode = new TreeSet<>();
        fromCode.addAll(scanDirectlyBoundProperties(MAIN_JAVA, RUN_FROM_SERVER).keySet());
        fromCode.addAll(scanDirectlyBoundProperties(SIMULATOR_MAIN_JAVA, RUN_FROM_SIMULATOR).keySet());
        collect(ReconcilerProperties.class, "nkap.reconciler", fromCode);
        collect(OutboxRelayProperties.class, "nkap.webhooks", fromCode);
        collect(MtnProperties.class, "nkap.provider.mtn", fromCode);

        Set<String> documented = documentedProperties();

        Set<String> undocumented = new TreeSet<>(fromCode);
        undocumented.removeAll(documented);
        assertThat(undocumented)
                .as("real properties with no row in docs/configuration-reference.md")
                .isEmpty();

        Set<String> stale = new TreeSet<>(documented);
        stale.removeAll(fromCode);
        assertThat(stale)
                .as("rows in docs/configuration-reference.md naming a property nothing in code reads")
                .isEmpty();
    }

    @Test
    @DisplayName("the reference's Default column for nkap.reconciler.* and nkap.webhooks.* matches application.yml's own value, property by property")
    void documented_defaults_match_application_yml() throws IOException {
        Map<String, Object> yaml = loadApplicationYml(APPLICATION_YML);
        Map<String, String> documented = documentedDefaults();

        List<String> mismatches = new ArrayList<>();
        for (String prefix : List.of("nkap.reconciler", "nkap.webhooks")) {
            Set<String> properties = new TreeSet<>();
            collect(prefix.equals("nkap.reconciler") ? ReconcilerProperties.class : OutboxRelayProperties.class,
                    prefix, properties);
            for (String property : properties) {
                String actual = valueAt(yaml, property);
                String expected = documented.get(property);
                if (expected == null) {
                    mismatches.add(property + ": no Default column value found in the reference at all");
                } else if (!expected.equals(actual)) {
                    mismatches.add(property + ": application.yml says '" + actual + "', the reference says '" + expected + "'");
                }
            }
        }
        assertThat(mismatches).as("documented default does not match application.yml").isEmpty();
    }

    @Test
    @DisplayName("every directly-bound @Value property's inline default agrees with its own module's application.yml and the reference's Default column")
    void inline_value_defaults_agree_with_their_module_application_yml_and_the_reference() throws IOException {
        List<String> mismatches = new ArrayList<>();
        checkInlineDefaults(scanDirectlyBoundProperties(MAIN_JAVA, RUN_FROM_SERVER), APPLICATION_YML, mismatches);
        checkInlineDefaults(scanDirectlyBoundProperties(SIMULATOR_MAIN_JAVA, RUN_FROM_SIMULATOR), SIMULATOR_APPLICATION_YML, mismatches);
        assertThat(mismatches).as("inline @Value default, application.yml and the reference do not all agree").isEmpty();
    }

    /**
     * For every {@code property -> inlineDefault} pair {@code direct} holds where
     * {@code inlineDefault} is not {@code null} (a {@code @Value} that actually carries a
     * {@code :default}, as opposed to one with none, or a {@code @ConditionalOnProperty}
     * match, neither of which has an inline default to check at all): compares that default
     * against {@code applicationYml}'s own literal and against
     * {@link #documentedDefaults()}'s entry for it, adding a message to {@code mismatches}
     * for either side that disagrees. A side with nothing recorded — no key in
     * {@code application.yml}, or a reference row written as prose rather than a literal —
     * is skipped rather than flagged: there is no fact there to disagree with, and forcing
     * every property to carry a literal default in both places would fight the deliberate
     * exceptions {@code nkap.public-base-url} and {@code nkap.provider.default} already are.
     */
    private static final Pattern ENV_PASSTHROUGH = Pattern.compile("^\\$\\{[A-Za-z0-9_]+:([^}]*)}$");

    /**
     * {@code application.yml}'s own literal for a property, unwrapped one level when that
     * literal is itself an env-var passthrough with a nested default —
     * {@code nkap.public-base-url}'s {@code ${NKAP_PUBLIC_BASE_URL:}} is exactly this shape,
     * and its effective default (what a deployment gets with the env var unset) is the empty
     * string inside the braces, not the four-character literal {@code ${NKAP...}} text. Any
     * other value passes through unchanged.
     */
    private static String effectiveDefault(String applicationYmlValue) {
        if (applicationYmlValue == null) {
            return null;
        }
        Matcher passthrough = ENV_PASSTHROUGH.matcher(applicationYmlValue);
        return passthrough.matches() ? passthrough.group(1) : applicationYmlValue;
    }

    private static void checkInlineDefaults(Map<String, String> direct, Path applicationYml, List<String> mismatches)
            throws IOException {
        Map<String, Object> yaml = loadApplicationYml(applicationYml);
        Map<String, String> documented = documentedDefaults();

        for (Map.Entry<String, String> entry : direct.entrySet()) {
            String property = entry.getKey();
            String inlineDefault = entry.getValue();
            if (inlineDefault == null) {
                continue;
            }

            String ymlValue = effectiveDefault(valueAt(yaml, property));
            if (ymlValue != null && !ymlValue.equals(inlineDefault)) {
                mismatches.add(property + ": " + applicationYml + " says '" + ymlValue
                        + "', the inline @Value default says '" + inlineDefault + "'");
            }

            String documentedValue = documented.get(property);
            if (documentedValue != null && !documentedValue.equals(inlineDefault)) {
                mismatches.add(property + ": the reference says '" + documentedValue
                        + "', the inline @Value default says '" + inlineDefault + "'");
            }
        }
    }

    @Test
    @DisplayName("nkap.provider.mtn.installations[].request-timeout's documented default matches @DefaultValue(\"PT20S\") -- the only field in that record with one unambiguous default")
    void the_one_mtn_default_that_is_unambiguous_is_correct() throws IOException, NoSuchMethodException {
        // @DefaultValue's own @Target is PARAMETER only (not RECORD_COMPONENT), so it shows
        // up on the canonical constructor's parameter, not on RecordComponent.getAnnotation.
        org.springframework.boot.context.properties.bind.DefaultValue annotation =
                canonicalConstructorParameterAnnotation(
                        MtnProperties.Installation.class,
                        "requestTimeout",
                        org.springframework.boot.context.properties.bind.DefaultValue.class);
        assertThat(annotation).as("Installation.requestTimeout must keep a @DefaultValue -- this test's own reasoning depends on it").isNotNull();
        String codeDefault = annotation.value()[0];

        String documented = documentedDefaults().get("nkap.provider.mtn.installations[].request-timeout");
        assertThat(documented).as("no Default column value found for request-timeout").isNotNull();

        // application.yml's own default reads "PT20S" (an ISO-8601 duration, since this is
        // the one MTN field application.yml itself sets no simple-format value for); the
        // reference is free to also show it as "20s", the form every other duration in this
        // table uses, so both spellings are accepted here as long as they mean the same
        // duration -- the point is that the two are not allowed to silently drift, not that
        // one particular spelling is the only correct one.
        assertThat(java.time.Duration.parse(codeDefault))
                .as("'%s' (reference) must be the same duration as '%s' (@DefaultValue)", documented, codeDefault)
                .isEqualTo(parseDurationEitherSpelling(documented));
    }

    // --- reflection: every leaf property a record binds --------------------------------

    private static void collect(Class<?> recordClass, String prefix, Set<String> out) {
        for (RecordComponent component : recordClass.getRecordComponents()) {
            String name = prefix + "." + kebab(component.getName());
            Class<?> type = component.getType();
            if (List.class.isAssignableFrom(type)) {
                Class<?> elementType = (Class<?>) ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
                if (elementType.isRecord()) {
                    collect(elementType, name + "[]", out);
                    continue;
                }
            }
            if (type.isRecord()) {
                collect(type, name, out);
                continue;
            }
            out.add(name);
        }
    }

    /**
     * The canonical constructor's parameter for {@code componentName}, annotated with
     * {@code annotationType}, or {@code null}. {@code @DefaultValue} (like several
     * binding annotations) declares {@code @Target(PARAMETER)} only, so it never appears on
     * {@link RecordComponent#getAnnotation} -- only on the matching constructor parameter.
     */
    private static <A extends java.lang.annotation.Annotation> A canonicalConstructorParameterAnnotation(
            Class<?> recordClass, String componentName, Class<A> annotationType) throws NoSuchMethodException {
        RecordComponent[] components = recordClass.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        int index = -1;
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            if (components[i].getName().equals(componentName)) {
                index = i;
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException(componentName + " is not a record component of " + recordClass);
        }
        java.lang.reflect.Constructor<?> canonical = recordClass.getDeclaredConstructor(types);
        return canonical.getParameters()[index].getAnnotation(annotationType);
    }

    private static String kebab(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    // --- server/src/main/java: nkap.* properties read directly, not through a record ----

    private static final Pattern VALUE_ANNOTATION = Pattern.compile("@Value\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");
    private static final Pattern VALUE_PLACEHOLDER = Pattern.compile("\\$\\{(nkap\\.[A-Za-z0-9_.-]+)(?::([^}]*))?}");
    private static final Pattern CONDITIONAL_ON_PROPERTY = Pattern.compile("@ConditionalOnProperty\\s*\\(([^)]*)\\)");
    private static final Pattern CONDITIONAL_PREFIX = Pattern.compile("\\bprefix\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern CONDITIONAL_NAME = Pattern.compile("\\bname\\s*=\\s*\"([^\"]*)\"");

    /**
     * Every {@code nkap.*} property read directly with {@code @Value} or
     * {@code @ConditionalOnProperty} in {@code mainJava} — the properties no
     * {@code @ConfigurationProperties} record ever sees, found by text-scanning the source
     * tree rather than by naming them in a list that could fall out of date the day a fifth
     * one is added the same way. Called once for {@code server/src/main/java} and once for
     * {@code simulator/src/main/java} (issue #99: the first setting {@code nkap-simulator}
     * ever read this way), each call's result kept separate rather than merged, because a
     * property's inline default can only be checked against the {@code application.yml} of
     * the module it actually came from.
     *
     * <p>The map is keyed by property name; the value is the {@code :default} an
     * {@code @Value("${nkap.foo.bar:some-default}")} carries, or {@code null} for one with
     * none ({@code @Value("${nkap.foo.bar}")}) and for every {@code @ConditionalOnProperty}
     * match, which has no inline-default concept at all in this scan.
     * {@code @ConditionalOnProperty(prefix = "nkap.foo", name = "bar", ...)} — the only shape
     * this codebase actually uses — composes to the property name {@code nkap.foo.bar}.
     * Annotations are matched across the whole file, not line by line, since Java does not
     * require one of either to fit on a single source line.
     *
     * <p>Any {@code @ConditionalOnProperty} that mentions {@code nkap} but does not fit the
     * {@code prefix}/{@code name} shape above — a bare {@code @ConditionalOnProperty("nkap.foo")},
     * a {@code value} alias, an array of names — fails this method outright instead of being
     * silently skipped. That is deliberate: a text scan that recognises one shape and ignores
     * everything else it cannot parse is worse than no scan, so this one refuses to guess.
     *
     * <h2>What this cannot cover</h2>
     *
     * <p>This is a text scan of one module's source at a time, not a reflection- or
     * bytecode-level search of the compiled classpath the way {@link #collect} is for the
     * three records above. It finds a property name only when it appears as a string literal
     * directly inside one of the two annotations above; it would miss one built from a
     * runtime string (e.g. {@code environment.getProperty("nkap." + suffix)}), one read
     * through {@code Environment} or a {@code Binder} call with no annotation at all, or one
     * in a module neither call here covers ({@code provider-mtn}, {@code core},
     * {@code provider-api}). None of those patterns exist in this codebase today (confirmed
     * while writing this scan by grepping for {@code Environment}/{@code getProperty} usage
     * against {@code nkap.*} — there is none, in either module), but a scan is only ever a
     * check against the patterns it was written to expect, not a guarantee no other pattern
     * was introduced.
     */
    private static Map<String, String> scanDirectlyBoundProperties(Path mainJava, String runFrom) throws IOException {
        if (!Files.isDirectory(mainJava)) {
            throw new IllegalStateException(
                    "FAIL: " + mainJava.toAbsolutePath() + " not found -- this test must run from "
                            + runFrom + ", not some other working directory");
        }

        Map<String, String> found = new java.util.TreeMap<>();
        try (var files = Files.walk(mainJava)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);

                Matcher value = VALUE_ANNOTATION.matcher(source);
                while (value.find()) {
                    Matcher placeholder = VALUE_PLACEHOLDER.matcher(value.group(1));
                    while (placeholder.find()) {
                        found.put(placeholder.group(1), placeholder.group(2));
                    }
                }

                Matcher conditional = CONDITIONAL_ON_PROPERTY.matcher(source);
                while (conditional.find()) {
                    String body = conditional.group(1);
                    if (!body.contains("nkap")) {
                        continue;
                    }
                    Matcher prefix = CONDITIONAL_PREFIX.matcher(body);
                    Matcher name = CONDITIONAL_NAME.matcher(body);
                    if (prefix.find() && name.find() && prefix.group(1).startsWith("nkap")) {
                        found.put(prefix.group(1) + "." + name.group(1), null);
                    } else {
                        throw new IllegalStateException(
                                "unhandled @ConditionalOnProperty shape referencing nkap in " + file + ": "
                                        + body.strip() + " -- extend scanDirectlyBoundProperties instead of "
                                        + "letting this be silently missed");
                    }
                }
            }
        }

        assertThat(found)
                .as("scanDirectlyBoundProperties(%s) found nothing at all -- the scan itself is broken "
                        + "(every assertion that depends on it would otherwise pass vacuously)", mainJava)
                .isNotEmpty();
        return found;
    }

    // --- docs/configuration-reference.md: table rows, as text ---------------------------

    /** Every `nkap.*` value found in a table row's first column, anywhere in the file. */
    private static Set<String> documentedProperties() throws IOException {
        Set<String> found = new TreeSet<>();
        Pattern row = Pattern.compile("^\\|\\s*`(nkap\\.[a-zA-Z0-9_.\\[\\]-]+)`\\s*\\|");
        for (String line : Files.readAllLines(REFERENCE)) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    /** The first backtick-quoted token in a documented row's Default column, keyed by property name. */
    private static Map<String, String> documentedDefaults() throws IOException {
        Map<String, String> defaults = new java.util.HashMap<>();
        Pattern row = Pattern.compile("^\\|\\s*`(nkap\\.[a-zA-Z0-9_.\\[\\]-]+)`\\s*\\|([^|]*)\\|");
        Pattern firstCode = Pattern.compile("`([^`]*)`");
        for (String line : Files.readAllLines(REFERENCE)) {
            Matcher m = row.matcher(line);
            if (m.find()) {
                Matcher code = firstCode.matcher(m.group(2));
                if (code.find()) {
                    defaults.put(m.group(1), code.group(1));
                }
            }
        }
        return defaults;
    }

    // --- application.yml: the actual value at a dotted, possibly-list-shaped path -------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadApplicationYml(Path applicationYml) throws IOException {
        try (InputStream in = Files.newInputStream(applicationYml)) {
            return new Yaml().load(in);
        }
    }

    /**
     * Walks {@code property} (dot-separated, an empty {@code []} segment meaning "the first
     * list element" — {@code nkap.reconciler.*}/{@code nkap.webhooks.*} are always flat, so
     * this only ever needs to handle plain dotted paths) and renders the leaf as plain text,
     * the same way it would be typed in a table cell: {@code 30s}, {@code 100}, {@code true}.
     */
    @SuppressWarnings("unchecked")
    private static String valueAt(Map<String, Object> yaml, String property) {
        Object current = yaml;
        for (String segment : property.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(segment);
        }
        return current == null ? null : String.valueOf(current);
    }

    private static java.time.Duration parseDurationEitherSpelling(String value) {
        try {
            return java.time.Duration.parse(value);
        } catch (java.time.format.DateTimeParseException notIso8601) {
            // Spring's own "simple" duration format (30s, 1m, 1h, 2d) -- accepted by
            // @ConfigurationProperties binding but not by java.time.Duration.parse, which
            // wants "PT30S". A tiny, deliberately narrow translation, only for the units
            // this table actually uses.
            Matcher simple = Pattern.compile("^(\\d+)(ms|s|m|h|d)$").matcher(value.strip());
            if (!simple.matches()) {
                throw new IllegalArgumentException("not a duration in either spelling: '" + value + "'", notIso8601);
            }
            long amount = Long.parseLong(simple.group(1));
            return switch (simple.group(2)) {
                case "ms" -> java.time.Duration.ofMillis(amount);
                case "s" -> java.time.Duration.ofSeconds(amount);
                case "m" -> java.time.Duration.ofMinutes(amount);
                case "h" -> java.time.Duration.ofHours(amount);
                case "d" -> java.time.Duration.ofDays(amount);
                default -> throw new IllegalStateException("unreachable");
            };
        }
    }
}

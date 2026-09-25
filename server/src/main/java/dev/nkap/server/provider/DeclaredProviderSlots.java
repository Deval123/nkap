package dev.nkap.server.provider;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The provider slots {@code application.yml} declares, and the check that no environment variable,
 * and no file in the imported credentials directory, names any other.
 *
 * <p>{@code application.yml} reads provider variables by name, one named placeholder per slot:
 * {@code NKAP_PROVIDER_MTN_CM_*}, {@code NKAP_PROVIDER_MTN_GH_*}, {@code NKAP_PROVIDER_MPESA_KE_*}.
 * A variable for any other country or operator is read by nothing. The installation it describes
 * silently does not exist, and its credentials sit in the process unused, unwatched and never
 * rotated. {@link #requireOnlyDeclared} turns that silence into a startup failure.
 *
 * <p>{@link #BY_OPERATOR} repeats what {@code application.yml} declares, because an undeclared
 * country sets nothing anybody reads, so there is nothing to derive it from.
 * {@code DeclaredProviderSlotsTest} reads the real file and fails the build if the two ever
 * disagree.
 */
final class DeclaredProviderSlots {

    /** Operator to the countries it has a slot for, in the upper case the variables use. */
    static final Map<String, Set<String>> BY_OPERATOR = Map.of(
            "MTN", Set.of("CM", "GH"),
            "MPESA", Set.of("KE"));

    /**
     * {@code NKAP_PROVIDER_<OPERATOR>_<COUNTRY>_<FIELD>}. The operator group, {@code [A-Z0-9]+},
     * cannot contain an underscore, so the first underscore after it always ends it and the next
     * two letters are always the country: the split is unambiguous. It has to be. A pattern that
     * could split {@code MTN_CI_API_KEY} two ways would let the check pick the harmless reading
     * and pass without having looked.
     *
     * <p>{@code NKAP_PROVIDER_DEFAULT} does not match: it has no country segment. Neither do
     * Spring's relaxed spellings such as {@code NKAP_PROVIDER_MTN_INSTALLATIONS_0_COUNTRY}, whose
     * segment after the operator is not two letters followed by an underscore.
     *
     * <p>Matched regardless of case, because a variable spelled in lower or mixed case is read by
     * nothing either: Spring upper-cases the name it looks for, not the variable. Such a spelling
     * is therefore reported even for a declared slot.
     */
    private static final Pattern SLOT_VARIABLE =
            Pattern.compile("^NKAP_PROVIDER_([A-Z0-9]+)_([A-Z]{2})_[A-Z0-9_]+$", Pattern.CASE_INSENSITIVE);

    private DeclaredProviderSlots() {
    }

    /** A deployment configured by variables alone: {@link #requireOnlyDeclared(Collection, Collection)} with no files. */
    static void requireOnlyDeclared(Collection<String> variableNames) {
        requireOnlyDeclared(variableNames, List.of());
    }

    /**
     * Fails if any of {@code variableNames} or {@code fileNames} names a slot that does not exist,
     * reporting every offender at once.
     *
     * <p>Both doors are checked on the same terms, because a file in the imported directory is
     * named after the variable it stands in for and feeds the same placeholder. A refusal that
     * covered variables alone would pass a file for an undeclared country, whose credentials
     * would then sit unused exactly as a variable's did before this check existed. Each line
     * says which door the offender came through, so the operator looks in the right place.
     *
     * <p>Takes names and nothing else: no value is ever passed here, so none can reach the
     * message. The message names each offending operator and country, and the family as a group
     * ({@code NKAP_PROVIDER_MTN_CI_*}), never an individual variable or file. Names are not
     * secret, but a message built only from the pair stays harmless whatever is later added to it.
     */
    static void requireOnlyDeclared(Collection<String> variableNames, Collection<String> fileNames) {
        Map<Offence, EnumSet<Door>> offences = new TreeMap<>();
        collect(variableNames, Door.VARIABLE, offences);
        collect(fileNames, Door.FILE, offences);
        if (offences.isEmpty()) {
            return;
        }
        Set<String> lines = new TreeSet<>();
        EnumSet<Door> doors = EnumSet.noneOf(Door.class);
        offences.forEach((offence, through) -> {
            lines.add(offence.describe(through));
            doors.addAll(through);
        });
        String things = Door.plural(doors);
        throw new IllegalStateException("Refusing to start: provider " + things + " name installations this"
                + " gateway does not have.\n  - " + String.join("\n  - ", List.copyOf(lines))
                + "\nRemove those " + things + ", or add the slot to application.yml. The declared slots are "
                + declared() + ".");
    }

    private static void collect(Collection<String> names, Door door, Map<Offence, EnumSet<Door>> offences) {
        for (String name : names) {
            Matcher slot = SLOT_VARIABLE.matcher(name);
            if (!slot.matches()) {
                continue;
            }
            String operator = slot.group(1).toUpperCase(Locale.ROOT);
            String country = slot.group(2).toUpperCase(Locale.ROOT);
            Set<String> countries = BY_OPERATOR.get(operator);
            Offence.Kind kind;
            if (countries == null) {
                kind = Offence.Kind.UNKNOWN_OPERATOR;
            } else if (!countries.contains(country)) {
                kind = Offence.Kind.UNDECLARED_COUNTRY;
            } else if (!name.equals(name.toUpperCase(Locale.ROOT))) {
                kind = Offence.Kind.MIS_CASED;
            } else {
                continue;
            }
            offences.computeIfAbsent(new Offence(operator, country, kind), o -> EnumSet.noneOf(Door.class)).add(door);
        }
    }

    /** Where a name was found. Files are exact-name only: a lower-case file feeds nothing either. */
    private enum Door {
        VARIABLE, FILE;

        static String plural(Set<Door> doors) {
            if (doors.size() == 2) {
                return "variables and files";
            }
            return doors.contains(FILE) ? "files" : "variables";
        }
    }

    /** One line of the failure: an operator and country, and what is wrong with them. */
    private record Offence(String operator, String country, Kind kind) implements Comparable<Offence> {

        enum Kind { UNKNOWN_OPERATOR, UNDECLARED_COUNTRY, MIS_CASED }

        @Override
        public int compareTo(Offence other) {
            return (operator + " " + country + " " + kind).compareTo(other.operator + " " + other.country + " " + other.kind);
        }

        String describe(Set<Door> through) {
            String family = "NKAP_PROVIDER_" + operator + "_" + country + "_*";
            String pair = operator + " " + country + ": ";
            String things = Door.plural(through);
            return switch (kind) {
                case UNKNOWN_OPERATOR -> pair + found(family, through) + ", but no " + operator
                        + " adapter is built into this image. The " + things + " configure nothing, and any"
                        + " credentials among them are provisioned for an adapter that does not exist.";
                case UNDECLARED_COUNTRY -> pair + found(family, through) + ", but " + operator
                        + " has slots only for " + new TreeSet<>(BY_OPERATOR.get(operator)) + ". Nothing reads these "
                        + things + ": the installation would silently not exist, and its credentials would be"
                        + " provisioned but never used.";
                case MIS_CASED -> pair + misCased(family, through);
            };
        }

        private static String found(String family, Set<Door> through) {
            if (through.size() == 2) {
                return family + " is set, and a file of that family is in the imported credentials directory";
            }
            return through.contains(Door.FILE)
                    ? "a file of " + family + " is in the imported credentials directory"
                    : family + " is set";
        }

        private static String misCased(String family, Set<Door> through) {
            String variable = "a variable of " + family + " is spelled in lower or mixed case, and nothing reads"
                    + " that spelling. Environment names are read in upper case only.";
            String file = "a file of " + family + " in the imported credentials directory is named in lower or"
                    + " mixed case, and nothing reads that name. A file is read only under the variable's exact,"
                    + " upper-case name.";
            if (through.size() == 2) {
                return variable + " And " + file;
            }
            return through.contains(Door.FILE) ? file : variable;
        }
    }

    private static String declared() {
        Set<String> all = new TreeSet<>();
        BY_OPERATOR.forEach((operator, countries) -> countries.forEach(country -> all.add(operator + " " + country)));
        return String.join(", ", all);
    }
}

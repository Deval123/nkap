package dev.nkap.server.provider;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The provider slots {@code application.yml} declares, and the check that no environment variable
 * names any other.
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

    /**
     * Fails if any of {@code variableNames} names a slot that does not exist, reporting every
     * offender at once.
     *
     * <p>Takes names and nothing else: no value is ever passed here, so none can reach the
     * message. The message names each offending operator and country, and the variable family as
     * a group ({@code NKAP_PROVIDER_MTN_CI_*}), never an individual variable. Names are not
     * secret, but a message built only from the pair stays harmless whatever is later added to it.
     */
    static void requireOnlyDeclared(Collection<String> variableNames) {
        Set<String> undeclared = new TreeSet<>();
        for (String name : variableNames) {
            Matcher slot = SLOT_VARIABLE.matcher(name);
            if (!slot.matches()) {
                continue;
            }
            String operator = slot.group(1).toUpperCase(Locale.ROOT);
            String country = slot.group(2).toUpperCase(Locale.ROOT);
            String family = "NKAP_PROVIDER_" + operator + "_" + country + "_*";
            Set<String> countries = BY_OPERATOR.get(operator);
            if (countries == null) {
                undeclared.add(operator + " " + country + ": " + family + " is set, but no " + operator
                        + " adapter is built into this image. The variables configure nothing, and any"
                        + " credentials among them are provisioned for an adapter that does not exist.");
            } else if (!countries.contains(country)) {
                undeclared.add(operator + " " + country + ": " + family + " is set, but " + operator
                        + " has slots only for " + new TreeSet<>(countries) + ". Nothing reads these variables:"
                        + " the installation would silently not exist, and its credentials would be"
                        + " provisioned but never used.");
            } else if (!name.equals(name.toUpperCase(Locale.ROOT))) {
                undeclared.add(operator + " " + country + ": a variable of " + family + " is spelled in lower"
                        + " or mixed case, and nothing reads that spelling. Environment names are read in upper"
                        + " case only.");
            }
        }
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException("Refusing to start: provider variables name installations this"
                    + " gateway does not have.\n  - " + String.join("\n  - ", List.copyOf(undeclared))
                    + "\nRemove those variables, or add the slot to application.yml. The declared slots are "
                    + declared() + ".");
        }
    }

    private static String declared() {
        Set<String> all = new TreeSet<>();
        BY_OPERATOR.forEach((operator, countries) -> countries.forEach(country -> all.add(operator + " " + country)));
        return String.join(", ", all);
    }
}

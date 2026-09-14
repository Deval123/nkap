package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.core.payment.PaymentState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #92's own reason for existing: {@code docs/providers/mtn.md}'s "Status and error
 * mapping" table is a copy of {@link MtnStatusMap#TABLE} for a reader, not a second source of
 * truth, and a copy nothing checks is exactly the kind of true-looking sentence beside correct
 * code this project keeps finding (the {@code Currency} membership criterion, {@code V8}'s
 * {@code CHECK} claim, {@code isWellFormed}'s javadoc — three examples the same week this
 * issue was written). {@code MtnStatusMap} is package-private, so this test lives beside it
 * rather than in {@code server} or {@code docs}, the same placement {@code CurrencyTest} and
 * {@code ConfigurationReferenceTest} use for their own tables.
 *
 * <h2>What this proves</h2>
 *
 * <ol>
 *   <li>Every code in {@link MtnStatusMap#TABLE} has a row in the doc's mapping table, every
 *       row in that table names a real code, and the state named in the row is the state the
 *       map actually returns for it. Checked in both directions: a row nobody removed when a
 *       code changed is exactly as wrong as a code nobody documented.</li>
 *   <li>The doc's "Documented by MTN" column agrees with {@link MtnStatusMap#DOCUMENTED_CODES}
 *       in both directions too — the doc must not claim ADR 0004 documents a code it does not
 *       list (or the reverse: silently drop a code ADR 0004 does list).</li>
 * </ol>
 *
 * <h2>What this does not cover</h2>
 *
 * <p>The doc's "Appears in" column — which of {@code status}, {@code reason} or the error
 * body's {@code code} a token is read from — is not checked against anything. Nothing in
 * {@link MtnStatusMap} records that distinction; the map is one flat
 * {@code Map<String, PaymentState>} precisely because the three namespaces are assumed
 * disjoint, and that assumption is exactly what the column exists to make visible, not
 * something a test can verify from the map itself.
 *
 * <p>The HTTP-layer table in the doc's own "Observed responses" section (what a {@code 409},
 * {@code 404} or {@code 400} means) is prose about status codes, not {@code PaymentState}
 * rows, and is not read here — {@code MtnStatusMap} has nothing to check it against.
 */
class MtnStatusMappingDocTest {

    private static final Path DOC = Path.of("..", "docs", "providers", "mtn.md");
    private static final Pattern ROW = Pattern.compile(
            "^\\|\\s*`([A-Z_]+)`\\s*\\|[^|]*\\|\\s*`([A-Z_]+)`\\s*\\|\\s*(yes|no)\\s*\\|\\s*$");

    @Test
    @DisplayName("every code in MtnStatusMap.TABLE has a row in docs/providers/mtn.md, every row a real code, and the states agree")
    void the_doc_table_agrees_with_the_map_in_both_directions() throws IOException {
        Map<String, PaymentState> documented = documentedStates();

        Map<String, PaymentState> undocumented = new TreeMap<>(MtnStatusMap.TABLE);
        undocumented.keySet().removeAll(documented.keySet());
        assertThat(undocumented)
                .as("codes MtnStatusMap.TABLE maps that have no row in docs/providers/mtn.md")
                .isEmpty();

        Map<String, PaymentState> stale = new TreeMap<>(documented);
        stale.keySet().removeAll(MtnStatusMap.TABLE.keySet());
        assertThat(stale)
                .as("rows in docs/providers/mtn.md naming a code MtnStatusMap.TABLE does not have")
                .isEmpty();

        Map<String, String> mismatches = new TreeMap<>();
        for (Map.Entry<String, PaymentState> entry : MtnStatusMap.TABLE.entrySet()) {
            PaymentState docState = documented.get(entry.getKey());
            if (docState != null && docState != entry.getValue()) {
                mismatches.put(entry.getKey(), "map says " + entry.getValue() + ", doc says " + docState);
            }
        }
        assertThat(mismatches).as("a code's documented state disagrees with MtnStatusMap.TABLE").isEmpty();
    }

    @Test
    @DisplayName("the doc's \"Documented by MTN\" column agrees with MtnStatusMap.DOCUMENTED_CODES in both directions")
    void the_documented_by_mtn_column_agrees_with_documented_codes() throws IOException {
        java.util.Set<String> documentedByMtnInDoc = new TreeSet<>();
        for (Map.Entry<String, Boolean> entry : documentedByMtnFlags().entrySet()) {
            if (entry.getValue()) {
                documentedByMtnInDoc.add(entry.getKey());
            }
        }

        java.util.Set<String> missingFromDoc = new TreeSet<>(MtnStatusMap.DOCUMENTED_CODES);
        missingFromDoc.removeAll(documentedByMtnInDoc);
        assertThat(missingFromDoc)
                .as("codes in MtnStatusMap.DOCUMENTED_CODES the doc does not mark \"yes\"")
                .isEmpty();

        java.util.Set<String> overclaimedByDoc = new TreeSet<>(documentedByMtnInDoc);
        overclaimedByDoc.removeAll(MtnStatusMap.DOCUMENTED_CODES);
        assertThat(overclaimedByDoc)
                .as("codes the doc marks \"yes\" (documented by MTN) that ADR 0004 / DOCUMENTED_CODES does not list")
                .isEmpty();
    }

    private static Map<String, PaymentState> documentedStates() throws IOException {
        Map<String, PaymentState> states = new HashMap<>();
        for (String line : Files.readAllLines(DOC)) {
            Matcher m = ROW.matcher(line);
            if (m.matches()) {
                states.put(m.group(1), PaymentState.valueOf(m.group(2)));
            }
        }
        return states;
    }

    private static Map<String, Boolean> documentedByMtnFlags() throws IOException {
        Map<String, Boolean> flags = new HashMap<>();
        for (String line : Files.readAllLines(DOC)) {
            Matcher m = ROW.matcher(line);
            if (m.matches()) {
                flags.put(m.group(1), "yes".equals(m.group(3)));
            }
        }
        return flags;
    }
}

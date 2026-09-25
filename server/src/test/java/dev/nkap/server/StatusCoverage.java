package dev.nkap.server;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.assertj.core.api.SoftAssertions;

/**
 * {@link OpenApiSpecIT}'s coverage rule on its own: documented statuses must equal those produced
 * plus those proved elsewhere. Kept apart from {@code OpenApiSpecIT} because that class starts an
 * embedded simulator when it is loaded, and this rule is three sets and nothing else, tested by
 * {@code StatusCoverageTest} without a server or a database.
 */
final class StatusCoverage {

    private StatusCoverage() {
    }

    /**
     * Fails unless every documented {@code "METHOD path status"} is in {@code observed} or
     * {@code provedElsewhere}. Reports separately a documented status nobody produces, an entry of
     * {@code provedElsewhere} that is no longer documented, and a documented response key that is
     * not a status code.
     */
    static void assertCoverage(Set<String> documented, Set<String> observed, Map<String, String> provedElsewhere) {
        Set<String> notStatusCodes = new TreeSet<>();
        Set<String> unproduced = new TreeSet<>();
        for (String triple : documented) {
            String status = triple.substring(triple.lastIndexOf(' ') + 1);
            if (!status.chars().allMatch(Character::isDigit)) {
                notStatusCodes.add(triple);
            } else if (!observed.contains(triple) && !provedElsewhere.containsKey(triple)) {
                unproduced.add(triple);
            }
        }
        Set<String> stale = new TreeSet<>(provedElsewhere.keySet());
        stale.removeAll(documented);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(unproduced)
                .as("documented in docs/openapi.yaml, but no test in OpenApiSpecIT produces it and PROVED_ELSEWHERE"
                        + " names no class that does")
                .isEmpty();
        softly.assertThat(stale)
                .as("named in PROVED_ELSEWHERE, but docs/openapi.yaml no longer documents it: a stale exception")
                .isEmpty();
        softly.assertThat(notStatusCodes)
                .as("documented under a response key that is not a status code, which no single test can produce")
                .isEmpty();
        softly.assertAll();
    }
}

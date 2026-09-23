package dev.nkap.simulator.scenario;

/**
 * Selects the requests a {@link ScenarioRule} applies to. A field that is
 * {@code null} is not compared, so a matcher with every field null matches
 * every request — that is how a default rule is written.
 */
public record RequestMatcher(String referenceId, String msisdn, String amount, String currency) {

    public boolean matches(String referenceId, String msisdn, String amount, String currency) {
        return fieldMatches(this.referenceId, referenceId)
                && fieldMatches(this.msisdn, msisdn)
                && fieldMatches(this.amount, amount)
                && fieldMatches(this.currency, currency);
    }

    private static boolean fieldMatches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }
}

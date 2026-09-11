package dev.nkap.simulator.scenario;

/**
 * How {@code GET .../account/balance} answers: {@code availableBalance} as MTN's own decimal
 * string in the major unit, and a {@code currency} — the shape the Account Balance API
 * documents, not one observed against a live sandbox (issue #72; see "Still unknown" in
 * {@code docs/providers/mtn.md}). {@link AccountOutcome#NO_RESPONSE} is the operator going
 * silent, the same case {@link SubmitOutcome#NO_RESPONSE} covers for a submission.
 */
public record BalanceBehaviour(AccountOutcome outcome, String availableBalance, String currency) {

    public BalanceBehaviour {
        outcome = outcome != null ? outcome : AccountOutcome.ANSWER;
        availableBalance = availableBalance != null ? availableBalance : "1000.00";
        currency = currency != null ? currency : "EUR";
    }

    static BalanceBehaviour defaultBehaviour() {
        return new BalanceBehaviour(null, null, null);
    }
}

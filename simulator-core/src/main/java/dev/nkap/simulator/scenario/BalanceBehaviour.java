package dev.nkap.simulator.scenario;

/**
 * How the account-balance read answers (issue #72): an {@code availableBalance}, kept as the
 * string the operator states it in rather than parsed into a number — the face that answers
 * decides what that string looks like on the wire — and a {@code currency}.
 * {@link AccountOutcome#NO_RESPONSE} is the operator going silent, the same case
 * {@link SubmitOutcome#NO_RESPONSE} covers for a submission.
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

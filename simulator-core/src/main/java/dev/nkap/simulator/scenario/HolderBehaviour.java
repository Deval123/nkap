package dev.nkap.simulator.scenario;

/**
 * How account-holder validation answers (issue #72): whether the holder is {@code active}.
 * The simulator does not vary the answer by MSISDN — there is one declared holder status,
 * the same as there is one declared balance, not a per-account ledger.
 */
public record HolderBehaviour(AccountOutcome outcome, boolean active) {

    public HolderBehaviour {
        outcome = outcome != null ? outcome : AccountOutcome.ANSWER;
    }

    static HolderBehaviour defaultBehaviour() {
        return new HolderBehaviour(null, true);
    }
}

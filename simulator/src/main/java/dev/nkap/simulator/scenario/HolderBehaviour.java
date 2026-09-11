package dev.nkap.simulator.scenario;

/**
 * How {@code GET .../accountholder/msisdn/{msisdn}/active} answers: {@code active}, in the
 * shape the Account Holder API documents, not one observed against a live sandbox (issue
 * #72; see "Still unknown" in {@code docs/providers/mtn.md}). The simulator does not vary
 * the answer by MSISDN — there is one declared holder status, the same as there is one
 * declared balance, not a per-account ledger.
 */
public record HolderBehaviour(AccountOutcome outcome, boolean active) {

    public HolderBehaviour {
        outcome = outcome != null ? outcome : AccountOutcome.ANSWER;
    }

    static HolderBehaviour defaultBehaviour() {
        return new HolderBehaviour(null, true);
    }
}

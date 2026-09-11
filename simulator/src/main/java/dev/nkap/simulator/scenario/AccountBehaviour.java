package dev.nkap.simulator.scenario;

/**
 * What the simulator answers for the two operator-wide reads that have no reference and no
 * timeline: the account balance and account-holder validation (issue #72). Like the token
 * lifetime, this is declared configuration, not per-reference state — set alongside the
 * rules and answered identically to whichever caller asks, because neither read is part of
 * any payment's timeline the way {@link Scenario} is (ADR 0002 does not apply here).
 */
public record AccountBehaviour(BalanceBehaviour balance, HolderBehaviour holder) {

    public AccountBehaviour {
        balance = balance != null ? balance : BalanceBehaviour.defaultBehaviour();
        holder = holder != null ? holder : HolderBehaviour.defaultBehaviour();
    }

    public static AccountBehaviour defaultBehaviour() {
        return new AccountBehaviour(null, null);
    }
}

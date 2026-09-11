package dev.nkap.provider;

/**
 * What an adapter can do. Declared rather than discovered, so the gateway can refuse a
 * request the provider cannot serve instead of failing halfway through it.
 *
 * <p>Two different kinds share this name, and nothing used to keep them apart. An
 * {@link Operation} is what a caller <strong>submits</strong> — {@link PaymentIntent} carries
 * one, and it decides which product a reference belongs to. A {@link Feature} is something the
 * gateway asks an adapter to do outright — no intent, no reference, no payment.
 *
 * <p>Sealing {@code Capability} to exactly these two enums makes the distinction structural
 * rather than checked. {@link PaymentIntent} declares a field of type {@code Operation}, so an
 * intent for {@code BALANCE} is not rejected — it cannot be written. And because the two kinds
 * are separate types rather than a flag one member could forget to set, a fifth capability
 * cannot be added without choosing which one it is: there is no third place to put it, and
 * nothing to remember to fill in. A {@code switch} over members, however tidy, does not have
 * that property — it compiles fine when someone adds a member and forgets it.
 */
public sealed interface Capability {

    /** A payment operation a caller submits. What a {@link PaymentIntent} carries. */
    enum Operation implements Capability {
        /** Take money from a payer — MTN calls this Collections / requesttopay. */
        COLLECT,

        /** Send money to a payee — MTN calls this Disbursements / transfer. */
        DISBURSE
    }

    /** Something the gateway asks an adapter to do outright — no intent, no reference. */
    enum Feature implements Capability {
        /** Report the balance of the account Nkap holds at the provider. */
        BALANCE,

        /** Produce a statement for a period, which the reconciler compares to the ledger. */
        STATEMENT
    }
}

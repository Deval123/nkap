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

    /**
     * Something the gateway asks an adapter to do outright — no intent, no reference, and
     * every member has a method on {@link ProviderAdapter} to call for it. The conformance
     * kit holds that: {@code ProviderAdapterConformanceTest.feature_declaration_and_support_agree}
     * asserts that its own map of per-feature checks, one contract call each, names every
     * member of this enum before it exercises any of them, so a member added without a check
     * fails on that assertion in every adapter's conformance run. Named as text, not linked:
     * {@code conformance} depends on this module, not the other way round.
     *
     * <p>Statement reconciliation was deliberately left off this list: it reads a file from
     * disk, and the operator is never asked for one — see {@code docs/positioning.md}'s
     * *what is not in scope* section for why, and for what it would take to add it back as a
     * member with its own contract method rather than a name alone (issue #74).
     */
    enum Feature implements Capability {
        /** Report the balance of the account Nkap holds at the provider. */
        BALANCE,

        /** Whether the account behind an MSISDN is active at the provider. */
        HOLDER_VALIDATION
    }
}

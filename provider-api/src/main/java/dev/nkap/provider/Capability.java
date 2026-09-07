package dev.nkap.provider;

/**
 * What an adapter can do. Declared rather than discovered, so the gateway can refuse a
 * request the provider cannot serve instead of failing halfway through it.
 */
public enum Capability {

    /** Take money from a payer — MTN calls this Collections / requesttopay. */
    COLLECT,

    /** Send money to a payee — MTN calls this Disbursements / transfer. */
    DISBURSE,

    /** Report the balance of the account Nkap holds at the provider. */
    BALANCE,

    /** Produce a statement for a period, which the reconciler compares to the ledger. */
    STATEMENT
}

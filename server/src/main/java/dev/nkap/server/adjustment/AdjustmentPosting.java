package dev.nkap.server.adjustment;

/**
 * One line of a proposed entry, as the proposer wrote it: an account, a signed amount in minor
 * units (debit positive, credit negative, the ledger's own convention) and a currency code.
 * Nothing here has been checked; {@link AdjustmentService} does that.
 */
public record AdjustmentPosting(String account, long amountMinor, String currency) {
}

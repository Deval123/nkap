package dev.nkap.server.web;

/** What {@code GET /balance} returns: a live read, never a stored value. */
public record BalanceResponse(String provider, String operation, long amountMinorUnits, String currency) {
}

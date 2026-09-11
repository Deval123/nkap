package dev.nkap.server.web;

/** What {@code GET /account-holders/{msisdn}} returns: a live read, never a stored value. */
public record AccountHolderResponse(String provider, String operation, String msisdn, boolean active) {
}

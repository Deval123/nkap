package dev.nkap.server.web;

/**
 * What one manual replay attempt did. {@code delivered} is the fact an admin asked for; a
 * failure is not this gateway's error, so it is a {@code 200} body, not a {@code 5xx} — the
 * same reasoning {@code ConfirmationOutcome} follows for a callback's confirming query.
 */
public record WebhookReplayResponse(String eventId, boolean delivered, String error) {
}

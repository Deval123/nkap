package dev.nkap.server.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where {@link OutboxRelay} claims due events and records what happened to them. Mirrors
 * {@code ReconciliationStore}'s shape — claim a bounded batch under {@code FOR UPDATE SKIP
 * LOCKED}, advance the schedule in the claim transaction, act outside it — because it is the
 * same mechanism aimed at a different table. See {@code OutboxRelay}'s javadoc for why this
 * stayed a second, parallel implementation rather than one shared with the reconciler's.
 */
public interface OutboxRelayStore {

    /**
     * Claims up to {@code batch} due events — not yet delivered, not dead-lettered, due at or
     * before {@code now} — advancing each one's attempt count and next-attempt time in the
     * same transaction as the claim, the way the reconciler's claim does.
     */
    List<Claim> claimDue(int batch, Instant now);

    /** Marks an event delivered: it will not be claimed again. */
    void markDelivered(UUID id, Instant at);

    /**
     * Records a failed delivery attempt. If {@code deadLetter} is {@code true}, the event is
     * also marked dead-lettered — it stops being claimed, but the row and {@code error} stay,
     * findable, never deleted.
     */
    void recordFailure(UUID id, String error, boolean deadLetter, Instant at);

    /**
     * The event {@code id} names, regardless of its delivery state — delivered, pending or
     * dead-lettered. {@code WebhookReplayController} is the one caller: replaying an event
     * does not care whether the relay would claim it right now, only that it exists.
     */
    Optional<StoredEvent> find(UUID id);

    /** An event as read back, independent of the claim it was or was not part of. */
    record StoredEvent(UUID id, String merchantId, String eventType, String payload) {
    }

    /**
     * One claimed event, ready to build a request from.
     *
     * @param attempts how many attempts this event has now had, counting the one about to be
     *                 made — 1 the first time, matching {@code ReconciliationStore.Claim}
     */
    record Claim(UUID id, String merchantId, String eventType, String payload, int attempts) {
    }
}

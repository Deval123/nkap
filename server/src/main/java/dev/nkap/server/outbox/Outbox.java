package dev.nkap.server.outbox;

/**
 * Where a notification-worthy event is written before anything tries to deliver it.
 *
 * <p>Mirrors {@code Ledger}'s shape on purpose: {@link #append} joins whatever transaction
 * is already open rather than starting its own, the same way {@code Ledger.append} does — so
 * a caller with its own transaction open ({@code SettlementService}, {@code PaymentService})
 * gets the property ADR 0003 exists for: the state change and the event commit together, or
 * neither does. That is the property {@code OutboxTransactionIT} proves first, before
 * anything about delivery.
 */
public interface Outbox {

    /** Writes {@code event}, ready for {@link OutboxRelay} to claim once this transaction commits. */
    void append(OutboxEvent event);
}

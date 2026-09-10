package dev.nkap.server.statement;

import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;

/**
 * The bit of a {@code SUCCEEDED} payment that reconciliation needs: its reference, the
 * operator's transaction id settlement recorded, the gross amount, and the merchant.
 *
 * <p>A read model, not a {@code Payment}. Reconciliation matches statement lines to these by
 * {@link #operatorTransactionId()} and compares {@link #amount()}; it never moves a payment
 * through its state machine.
 */
public record SettledPayment(ReferenceId reference, String operatorTransactionId, Money amount, String merchantId) {
}

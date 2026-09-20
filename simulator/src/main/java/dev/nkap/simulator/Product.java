package dev.nkap.simulator;

/**
 * The two MTN products the simulator plays, {@code RequestToPayController}'s Collections
 * and {@code TransferController}'s Disbursements. Each is its own reference space: a
 * reference submitted under one does not exist under the other, the same way it would not
 * at a real operator (issue #69).
 */
public enum Product {
    COLLECTIONS,
    DISBURSEMENTS
}

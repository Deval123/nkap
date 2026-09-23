package dev.nkap.simulator;

/**
 * The two directions a simulated operator moves money in: collecting it from a payer, and
 * disbursing it to a payee. Each is its own payment space: a payment submitted under one
 * does not exist under the other, the same way it would not at a real operator (issue #69).
 */
public enum Product {
    COLLECTIONS,
    DISBURSEMENTS
}

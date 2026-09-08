package dev.nkap.simulator.scenario;

/**
 * Which reference a simulated callback carries: the one the request was made
 * with, or a reference the client has never heard of.
 */
public enum CallbackTarget {
    SAME_REFERENCE,
    UNKNOWN_REFERENCE
}

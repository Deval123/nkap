package dev.nkap.simulator;

/**
 * How a simulated operator identifies a payment. Declared by each face, never assumed by
 * the core, because two things about it are behaviour rather than wire format, and a face
 * alone cannot express either:
 *
 * <ul>
 *   <li><strong>Who gives a payment its identity</strong> — {@link #mintedBy()}. Whichever
 *       side it is, that identity is what the operator records the payment under, what a
 *       status query looks it up by, and what a callback names.</li>
 *   <li><strong>What a submission repeating a recorded identity means</strong> —
 *       {@link #onRepeat()}: whether the operator deduplicates on it at all.</li>
 * </ul>
 *
 * <p>Each question has exactly one answer declared today, the one the only face in this
 * repository gives. The core carries them as named policies rather than as what it does,
 * so that a face answering differently changes a declaration, not the engine's code.
 */
public interface PaymentIdentity {

    /** Who gives a payment the identity its operator records it under. */
    enum Minter {
        /**
         * The submission carries it: the reference the caller chose is the payment's
         * identity, and nothing else is.
         */
        CALLER
    }

    /** What the operator does with a submission whose identity it has already recorded. */
    enum Repeat {
        /** Refuses it, recording nothing: the identity already names a payment. */
        REFUSED
    }

    Minter mintedBy();

    Repeat onRepeat();

    /**
     * The one textual form an identity is recorded and looked up under, so that a
     * submission and a later query or control-plane lookup agree on the same payment
     * however each one happened to spell it.
     */
    String canonical(String identity);
}

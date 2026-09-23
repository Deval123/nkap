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
 *   <li><strong>What a submission repeating the caller's reference means</strong> —
 *       {@link #onRepeat()}: whether the operator deduplicates on the reference the caller
 *       chose at all.</li>
 * </ul>
 *
 * <p>The two answers are declared separately but are not independent today: an operator
 * whose caller chooses the identity refuses a repeat of it, since the reference already
 * names a payment, and an operator that mints its own does not deduplicate on the caller's.
 * Those are the only two combinations a face declares; {@link Submissions} refuses any
 * other at startup rather than guess what it would mean.
 */
public interface PaymentIdentity {

    /** Who gives a payment the identity its operator records it under. */
    enum Minter {
        /**
         * The submission carries it: the reference the caller chose is the payment's
         * identity, and nothing else is.
         */
        CALLER,
        /**
         * The operator mints it and returns it in its answer to the submission. The
         * reference the caller chose is a key to nothing.
         */
        OPERATOR
    }

    /** What the operator does with a submission whose caller-chosen reference it has seen before. */
    enum Repeat {
        /** Refuses it, recording nothing: the reference already names a payment. */
        REFUSED,
        /** Accepts it as a new, independent payment: the operator does not deduplicate. */
        NOT_DEDUPLICATED
    }

    Minter mintedBy();

    Repeat onRepeat();

    /**
     * The one textual form an identity is recorded and looked up under, so that a
     * submission and a later query or control-plane lookup agree on the same payment
     * however each one happened to spell it.
     */
    String canonical(String identity);

    /**
     * A fresh identity for a payment being submitted, when the operator mints them. Called
     * only when {@link #mintedBy()} is {@link Minter#OPERATOR}.
     *
     * @param msisdn the counterparty's MSISDN as the submission gave it, or {@code null};
     *               an operator may encode it in the identities it mints
     */
    default String mint(String msisdn) {
        throw new UnsupportedOperationException("this operator does not mint identities: " + mintedBy());
    }

    /**
     * An identity no payment was ever submitted under, in this operator's own shape — what
     * a callback carries when its scenario targets a payment the client has never heard of.
     */
    String neverSubmitted();
}

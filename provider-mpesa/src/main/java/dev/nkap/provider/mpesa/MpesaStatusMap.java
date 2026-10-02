package dev.nkap.provider.mpesa;

import dev.nkap.core.payment.PaymentState;

/**
 * Safaricom's STK Push {@code ResultCode}s, mapped to {@link PaymentState}. The code is the
 * contract; {@code ResultDesc} is not, and is never read here: the same code has been observed
 * with different prose on the query and the callback, and on the query across days
 * ({@code docs/providers/m-pesa.md}, "ResultCode is the contract; ResultDesc is not").
 *
 * <p>Four codes, each labelled with how it is known. Every other code is
 * {@link PaymentState#UNKNOWN}: two members of a vocabulary are not the vocabulary, and a code
 * this table has never seen is not a failure it can assert.
 */
final class MpesaStatusMap {

    /** In flight. <strong>Observed</strong> on the query, 2026-09-23, answered {@code HTTP 200}. */
    static final int STILL_PROCESSING = 4999;

    /** The payer never answered the prompt. <strong>Observed</strong> 2026-09-18, 09-22 and 09-23. */
    static final int NO_RESPONSE_FROM_USER = 1037;

    /**
     * The payer dismissed the prompt. <strong>Observed</strong> 2026-10-02 on the query, 27
     * seconds after submission ({@code ws_CO_021020261805069181954437}), "Request Cancelled by
     * user.", unchanged on every later answer.
     */
    static final int CANCELLED_BY_USER = 1032;

    /**
     * Success. <strong>Observed</strong> 2026-10-02 on the query, 17 seconds after submission
     * ({@code ws_CO_021020261810465181954437}): see {@link #stateFor(int)}.
     */
    static final int SUCCESS = 0;

    private MpesaStatusMap() {
    }

    static PaymentState stateFor(int resultCode) {
        return switch (resultCode) {
            // In flight is a state, not an error: Safaricom answered HTTP 200 with a code that
            // says "not finished yet". Non-terminal, so the reconciler keeps asking.
            case STILL_PROCESSING -> PaymentState.PENDING;

            // FAILED, and this is the line most likely to be got wrong in either direction.
            //
            // It looks like it breaks this project's first invariant -- "a timeout is never a
            // failure" -- and it does not. That invariant is about OUR call going unanswered:
            // nobody spoke, so nothing is known, so the payment is UNKNOWN. 1037 is the opposite
            // case. The operator did answer, conclusively, and what it said is that the payer
            // never responded to the prompt, so the payment will not happen. A verdict about a
            // timeout on the payer's side is still a verdict; it is not the absence of one.
            //
            // Getting it wrong is expensive both ways. Mapping 1037 to UNKNOWN would leave every
            // unanswered prompt for a human forever, since no later answer will ever change it.
            // Mapping a real absence of an answer to FAILED -- a lost response, a 500.001.1001 --
            // would break the invariant outright; those raise ProviderUnavailableException in
            // MpesaAdapter and never reach this table.
            case NO_RESPONSE_FROM_USER -> PaymentState.FAILED;

            // FAILED, for the reason 1037 gives above and not repeated: the operator answered, and
            // what it said is that the payment will not happen. Observed 2026-10-02. Left to the
            // default, a payment its payer cancelled would stay unresolved, be claimed by the
            // reconciler until its window was spent, and then be escalated to a human.
            case CANCELLED_BY_USER -> PaymentState.FAILED;

            // OBSERVED 2026-10-02 on the query, by hand with a real Safaricom line and not through
            // this adapter: 0, 17 seconds after submission (ws_CO_021020261810465181954437), with
            // the payer's receipt SMS the same minute. What a callback carries for it is still
            // unobserved, and the simulator's callback prose for 0 is still its own choice, so a
            // green callback test here proves agreement with the model, not with Safaricom.
            case SUCCESS -> PaymentState.SUCCEEDED;

            default -> PaymentState.UNKNOWN;
        };
    }
}

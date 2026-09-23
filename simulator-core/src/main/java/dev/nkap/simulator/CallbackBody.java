package dev.nkap.simulator;

import java.util.Map;

/**
 * The body a callback is delivered with, in the face's own shape. When a callback is due,
 * where it is sent and what is recorded about it are {@link CallbackDispatcher}'s; what it
 * says is the operator's.
 *
 * @param <S> the face's status
 */
public interface CallbackBody<S> {

    /**
     * The JSON body for one delivery.
     *
     * @param paymentId the payment the callback names — the submitted one, or one never
     *                  submitted when the scenario asks for that
     * @param amount    the submitted amount, or {@code null} when the submission carried none
     * @param currency  the submitted currency, or {@code null} when the submission carried none
     * @param status    what the callback reports
     */
    Map<String, Object> render(String paymentId, String amount, String currency, S status);
}

package dev.nkap.provider;

/**
 * Thrown when a provider call did not produce an answer: timeout, connection failure,
 * 5xx, or an unreadable response.
 *
 * <p>This exception means "I do not know", and the gateway translates it to
 * {@link dev.nkap.core.payment.PaymentState#UNKNOWN}. An adapter must never convert it to
 * a failure on the caller's behalf.
 */
public class ProviderUnavailableException extends Exception {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

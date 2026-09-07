package dev.nkap.provider;

/**
 * Thrown when a callback fails authentication or cannot be parsed.
 *
 * <p>The gateway answers 2xx anyway — a provider that receives an error will retry
 * forever — but records the rejection and writes nothing.
 */
public class UntrustedCallbackException extends Exception {

    public UntrustedCallbackException(String message) {
        super(message);
    }

    public UntrustedCallbackException(String message, Throwable cause) {
        super(message, cause);
    }
}

package dev.nkap.provider;

/**
 * Thrown when a callback cannot be read or recognized.
 *
 * <p>Not, despite the name, "fails authentication": no operator observed against this
 * project offers a callback anything to authenticate — MTN's carries no signature, and
 * neither does M-Pesa's (ADR 0011 §2, issue #39's comment). The name stays, because a
 * published type is not renamed for a naming preference (ADR 0011's own "Alternatives
 * rejected"); this javadoc stops describing a check no adapter here performs.
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

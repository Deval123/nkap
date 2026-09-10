package dev.nkap.server.statement;

/**
 * A statement file could not be read into {@link StatementLine}s: a missing or wrong header,
 * a malformed row, an unparseable amount or timestamp.
 *
 * <p>Distinct from a <em>reconciliation</em> anomaly. This is "the file is not in the shape
 * we defined"; an anomaly is "the file is fine but disagrees with what we recorded". The
 * first is a 400 to whoever uploaded it; the second is a report.
 */
public final class StatementFormatException extends RuntimeException {

    public StatementFormatException(String message) {
        super(message);
    }

    public StatementFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}

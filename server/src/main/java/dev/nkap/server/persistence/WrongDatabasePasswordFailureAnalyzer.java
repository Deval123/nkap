package dev.nkap.server.persistence;

import org.postgresql.util.PSQLException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns PostgreSQL's {@code 28P01} ("password authentication failed") into a one-line
 * diagnosis instead of a Flyway stack trace forty lines into a service's logs — issue #123
 * found this exact failure with nothing saying "the database answered and rejected these
 * credentials", which is a different problem, and a different fix, than "the database is
 * unreachable".
 *
 * <p>Deliberately keyed on the SQLSTATE, not the message text, and never reads
 * {@code NKAP_DB_PASSWORD} or any configured credential: it names the failure, not the
 * value that caused it.
 */
public final class WrongDatabasePasswordFailureAnalyzer extends AbstractFailureAnalyzer<PSQLException> {

    /** {@code invalid_password} in PostgreSQL's own error codes appendix. */
    private static final String INVALID_PASSWORD_SQLSTATE = "28P01";

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, PSQLException cause) {
        if (!INVALID_PASSWORD_SQLSTATE.equals(cause.getSQLState())) {
            return null;
        }
        return new FailureAnalysis(
                "The database answered and rejected NKAP_DB_USER/NKAP_DB_PASSWORD for this "
                        + "connection. That is a different problem than the database being "
                        + "unreachable, and a different fix.",
                "Check NKAP_DB_PASSWORD against what this database was actually initialised "
                        + "with -- PostgreSQL applies POSTGRES_PASSWORD only the first time it "
                        + "initialises an empty data directory, so a volume created by an "
                        + "earlier, differently-configured deployment keeps its original "
                        + "password regardless of what this one now supplies. See the "
                        + "\"Two compose files, one volume\" note in README.md for the "
                        + "non-destructive repair -- `docker compose down -v` discards the "
                        + "ledger history to fix this, which is worse than the problem.",
                cause);
    }
}

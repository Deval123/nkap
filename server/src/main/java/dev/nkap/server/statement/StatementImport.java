package dev.nkap.server.statement;

import dev.nkap.provider.ProviderId;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Runs one statement import from a file on the host: parse, reconcile, and say what should
 * happen next.
 *
 * <p>This is the whole of "importing a statement" as a callable unit. It is reached by a
 * command ({@link StatementImportRunner}), <strong>not</strong> by an HTTP endpoint: a
 * statement file is evidence, reconciliation writes fee and suspense entries to an
 * append-only ledger straight from it, and there is nothing — no {@code adapter.query}, no
 * operator to confirm — between the file and a permanent entry the way there is on the
 * callback path. An import is an operator action, done rarely and deliberately by someone
 * with access to the deployment; it does not need a network surface.
 *
 * <p>{@link StatementReconciliation} is untouched by that decision: it already takes parsed
 * lines and returns a report. Only the way it is reached changed.
 */
@Component
public class StatementImport {

    /** The report has at least one anomaly — a suspense posting, a missing settlement, an amount mismatch. */
    public static final int EXIT_DISCREPANCIES = 2;

    private final StatementParser parser;
    private final StatementReconciliation reconciliation;

    public StatementImport(StatementParser parser, StatementReconciliation reconciliation) {
        this.parser = parser;
        this.reconciliation = reconciliation;
    }

    /**
     * Reads {@code file}, reconciles it for {@code provider}, and returns the report together
     * with the exit code a scheduled run should terminate with: {@code 0} when the report is
     * clean, {@link #EXIT_DISCREPANCIES} when it holds a discrepancy a human must notice.
     *
     * @throws StatementFormatException if the file is not in the expected shape
     * @throws UncheckedIOException     if the file cannot be read
     */
    public Result run(Path file, ProviderId provider, String source) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the statement file " + file, e);
        }
        ReconciliationReport report = reconciliation.reconcile(provider, source, parser.parse(new StringReader(content)));
        return new Result(report, exitCodeFor(report));
    }

    /**
     * {@code 0} when the report has no anomaly, {@link #EXIT_DISCREPANCIES} when it has one:
     * a suspense posting, a settled payment the statement omitted, or an amount that
     * disagreed. A fee posted or a plain match is not a discrepancy.
     */
    public static int exitCodeFor(ReconciliationReport report) {
        return report.anomalies().isEmpty() ? 0 : EXIT_DISCREPANCIES;
    }

    /** The report an import produced, and the process exit code it implies. */
    public record Result(ReconciliationReport report, int exitCode) {
    }
}

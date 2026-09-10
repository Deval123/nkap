package dev.nkap.server.statement;

import dev.nkap.provider.ProviderId;
import dev.nkap.server.statement.ReconciliationReport.Finding;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * The statement import as a command: run the application with
 * {@code --nkap.statement.import=<path>} and it reconciles that file, prints the report, and
 * exits — {@code 0} if the report is clean, {@code 2} if it holds a discrepancy, so a
 * scheduled run is noticed. Optional {@code --nkap.statement.provider=<id>} overrides the
 * configured default; {@code --nkap.statement.source=<name>} labels the import (the file
 * name by default).
 *
 * <p>Without that argument this runner does nothing, so a normal {@code server} start is
 * unaffected. {@code NkapServerApplication.main} additionally starts the context with no web
 * server when the argument is present, so the command opens no port.
 *
 * <p>There is deliberately no HTTP route that triggers an import — see {@link StatementImport}.
 */
@Component
class StatementImportRunner implements ApplicationRunner, ExitCodeGenerator {

    static final String IMPORT_OPTION = "nkap.statement.import";
    static final String PROVIDER_OPTION = "nkap.statement.provider";
    static final String SOURCE_OPTION = "nkap.statement.source";

    private static final Logger log = LoggerFactory.getLogger(StatementImportRunner.class);

    private final StatementImport statementImport;
    private final ProviderId defaultProvider;
    private final PrintStream out;
    private volatile int exitCode = 0;

    @Autowired
    StatementImportRunner(StatementImport statementImport, @Value("${nkap.provider.default}") String defaultProvider) {
        this(statementImport, defaultProvider, System.out);
    }

    StatementImportRunner(StatementImport statementImport, String defaultProvider, PrintStream out) {
        this.statementImport = statementImport;
        this.defaultProvider = ProviderId.of(defaultProvider);
        this.out = out;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(IMPORT_OPTION)) {
            return;
        }
        this.exitCode = execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    /** Does the import and prints the report; returns the exit code. Package-private so it can be driven without a real process exit. */
    int execute(ApplicationArguments args) {
        Path file = Path.of(single(args, IMPORT_OPTION));
        ProviderId provider = args.containsOption(PROVIDER_OPTION)
                ? ProviderId.of(single(args, PROVIDER_OPTION)) : defaultProvider;
        String source = args.containsOption(SOURCE_OPTION)
                ? single(args, SOURCE_OPTION) : String.valueOf(file.getFileName());

        log.info("statement import: file={} provider={} source={}", file, provider, source);
        StatementImport.Result result = statementImport.run(file, provider, source);
        print(result.report());
        return result.exitCode();
    }

    private void print(ReconciliationReport report) {
        out.println("Statement import " + report.importId() + " (" + report.provider() + ", " + report.sourceName()
                + "): " + report.lineCount() + " line(s)");
        for (Finding.Kind kind : Finding.Kind.values()) {
            long n = report.count(kind);
            if (n > 0) {
                out.println("  " + kind + ": " + n);
            }
        }
        List<Finding> anomalies = report.anomalies();
        if (anomalies.isEmpty()) {
            out.println("No discrepancies.");
        } else {
            out.println(anomalies.size() + " discrepanc" + (anomalies.size() == 1 ? "y" : "ies") + " — a human must look:");
            for (Finding finding : anomalies) {
                out.println("  [" + finding.kind() + "] " + finding.detail());
            }
        }
    }

    private static String single(ApplicationArguments args, String option) {
        List<String> values = args.getOptionValues(option);
        if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            throw new IllegalArgumentException("--" + option + " requires a value");
        }
        return values.get(0);
    }
}

package dev.nkap.server.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.nkap.provider.ProviderId;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * The glue: the runner does nothing unless {@code --nkap.statement.import} is present, and
 * when it is, it turns the arguments into a {@link StatementImport} call and carries its
 * exit code out. The reconciliation itself is {@code StatementReconciliationIT}'s.
 */
class StatementImportRunnerTest {

    private final StatementImport statementImport = mock(StatementImport.class);
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final StatementImportRunner runner =
            new StatementImportRunner(statementImport, "mtn", new PrintStream(captured, true, StandardCharsets.UTF_8));

    @Test
    @DisplayName("without --nkap.statement.import the runner does nothing and a normal server start is unaffected")
    void does_nothing_without_the_option() {
        runner.run(new DefaultApplicationArguments());

        verifyNoInteractions(statementImport);
        assertThat(runner.getExitCode()).isZero();
        assertThat(captured.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @DisplayName("the file, an explicit provider and source are passed through, and the import's exit code is carried out")
    void passes_the_arguments_through_and_carries_the_exit_code() {
        when(statementImport.run(eq(Path.of("/srv/statements/sep.csv")), eq(ProviderId.of("orange")), eq("september")))
                .thenReturn(new StatementImport.Result(report(), StatementImport.EXIT_DISCREPANCIES));

        runner.run(new DefaultApplicationArguments(
                "--nkap.statement.import=/srv/statements/sep.csv",
                "--nkap.statement.provider=orange",
                "--nkap.statement.source=september"));

        verify(statementImport).run(Path.of("/srv/statements/sep.csv"), ProviderId.of("orange"), "september");
        assertThat(runner.getExitCode()).isEqualTo(StatementImport.EXIT_DISCREPANCIES);
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("Statement import");
    }

    @Test
    @DisplayName("provider defaults to the configured one, and source to the file name")
    void defaults_provider_and_source() {
        when(statementImport.run(ArgumentMatchers.any(), eq(ProviderId.of("mtn")), eq("sep.csv")))
                .thenReturn(new StatementImport.Result(report(), 0));

        runner.run(new DefaultApplicationArguments("--nkap.statement.import=/tmp/sep.csv"));

        verify(statementImport).run(Path.of("/tmp/sep.csv"), ProviderId.of("mtn"), "sep.csv");
        verify(statementImport, never()).run(ArgumentMatchers.any(), eq(ProviderId.of("orange")), ArgumentMatchers.any());
        assertThat(runner.getExitCode()).isZero();
    }

    private static ReconciliationReport report() {
        return new ReconciliationReport(UUID.randomUUID(), ProviderId.of("mtn"), "src", Instant.now(), 0, List.of());
    }
}

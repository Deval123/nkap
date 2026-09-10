package dev.nkap.server.statement;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.statement.ReconciliationReport.Finding;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The exit code the command hands the shell: {@code 0} when a scheduled import found nothing
 * a human must act on, {@code 2} when it did. A fee posted or a plain match is routine and
 * exits {@code 0}; a suspense posting, a settled payment the statement omitted, or an amount
 * mismatch exits {@code 2}.
 */
class StatementImportTest {

    @Test
    @DisplayName("a report with no findings, or only routine ones, exits zero")
    void routine_outcomes_exit_zero() {
        assertThat(StatementImport.exitCodeFor(report())).isZero();
        assertThat(StatementImport.exitCodeFor(report(
                new Finding(Finding.Kind.MATCHED, "t1", ref(), "amounts agree, no fee"),
                new Finding(Finding.Kind.FEE_POSTED, "t2", ref(), "posted 75"),
                new Finding(Finding.Kind.FEE_ALREADY_POSTED, "t3", ref(), "re-import"))))
                .isZero();
    }

    @Test
    @DisplayName("a report with any anomaly exits with EXIT_DISCREPANCIES")
    void an_anomaly_exits_non_zero() {
        for (Finding.Kind anomaly : List.of(
                Finding.Kind.SUSPENSE_POSTED, Finding.Kind.SUSPENSE_ALREADY_POSTED,
                Finding.Kind.MISSING_FROM_STATEMENT, Finding.Kind.AMOUNT_MISMATCH)) {
            ReconciliationReport report = report(
                    new Finding(Finding.Kind.FEE_POSTED, "t1", ref(), "routine"),
                    new Finding(anomaly, "t2", ref(), "needs a human"));
            assertThat(StatementImport.exitCodeFor(report))
                    .as("%s is a discrepancy", anomaly)
                    .isEqualTo(StatementImport.EXIT_DISCREPANCIES);
        }
    }

    private static ReconciliationReport report(Finding... findings) {
        return new ReconciliationReport(UUID.randomUUID(), ProviderId.of("mtn"), "src", Instant.now(),
                findings.length, List.of(findings));
    }

    private static ReferenceId ref() {
        return ReferenceId.newReference();
    }
}

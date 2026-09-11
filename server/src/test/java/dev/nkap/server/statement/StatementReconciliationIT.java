package dev.nkap.server.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.payment.SettlementService;
import dev.nkap.server.persistence.PostgresLedger;
import dev.nkap.server.persistence.PostgresPaymentRepository;
import dev.nkap.server.provider.AdapterRegistry;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Statement reconciliation, against a real PostgreSQL.
 *
 * <p>The rule these defend, from ADR 0006 and the issue: reconciliation posts the fee and
 * the suspense entries, and for the two outcomes that tempt an automatic correction — a
 * settled payment the statement omits, and an amount that disagrees — it writes
 * <strong>nothing</strong> and reports. Correcting either on the strength of a file is the
 * same class of inference as calling a timeout a failure.
 *
 * <p>Every case here goes through {@link StatementImport} — the command — from a file on
 * disk, which is the only way an import is reached. The report it returns is the same object
 * the reconciliation produces; that these bodies did not change is the proof that only the
 * entry point moved.
 */
@ExtendWith(DockerAvailable.class)
class StatementReconciliationIT {

    private static final ProviderId MTN = ProviderId.of("mtn");

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;
    private static PostgresLedger ledger;
    private static PostgresPaymentRepository payments;
    private static PostgresStatementReconciliationStore store;
    private static StatementReconciliation reconciliation;
    private static StatementImport statementImport;
    private static final CsvStatementParser parser = new CsvStatementParser();

    @BeforeAll
    static void connect() {
        PostgresDatabase db = PostgresDatabase.shared();
        jdbc = db.jdbcTemplate();
        txManager = db.transactionManager();
        ledger = new PostgresLedger(jdbc, txManager);
        payments = new PostgresPaymentRepository(jdbc, new ObjectMapper());
        store = new PostgresStatementReconciliationStore(jdbc, txManager);
        reconciliation = new StatementReconciliation(ledger, store);
        statementImport = new StatementImport(parser, reconciliation);
    }

    // === 4. a SUCCEEDED payment the statement omits: reported, and the ledger is untouched ===

    @Test
    @DisplayName("a SUCCEEDED payment missing from the statement is reported and the ledger is byte-for-byte unchanged")
    void a_succeeded_payment_missing_from_the_statement_is_reported_and_nothing_is_written() {
        ReferenceId reference = aSettledPayment("txn-omitted-" + System.nanoTime(), Money.of(5_000, Currency.EUR));

        List<LedgerEntry> ledgerBefore = ledger.entries();

        // A statement that does not mention this payment at all — here, header only.
        ReconciliationReport report = reconcileCsv(CsvStatementParser.HEADER + "\n", "september-partial.csv");

        assertThat(report.findings())
                .as("the omitted settlement is reported")
                .anySatisfy(finding -> {
                    assertThat(finding.kind()).isEqualTo(ReconciliationReport.Finding.Kind.MISSING_FROM_STATEMENT);
                    assertThat(finding.paymentReference()).isEqualTo(reference);
                });

        assertThat(ledger.entries())
                .as("nothing was written: the ledger is exactly as it was before reconciliation ran")
                .isEqualTo(ledgerBefore);

        // The import itself is recorded, and the discrepancy is in it — tied back to this run.
        ReconciliationReport persisted = store.findReport(report.importId()).orElseThrow();
        assertThat(persisted.lineCount()).isZero();
        assertThat(persisted.findings())
                .anyMatch(f -> f.kind() == ReconciliationReport.Finding.Kind.MISSING_FROM_STATEMENT
                        && reference.equals(f.paymentReference()));
    }

    // === 1. a matched line with a fee posts one ADR 0006 entry; a re-import posts nothing more ===

    @Test
    @DisplayName("a matched line with a fee posts exactly one fee entry (DR fees / CR float, sum zero); importing the same file again posts nothing")
    void a_matched_line_with_a_fee_posts_one_entry_and_a_reimport_is_refused_by_the_ledger() {
        String txnId = "txn-fee-" + System.nanoTime();
        ReferenceId reference = aSettledPayment(txnId, Money.of(5_000, Currency.EUR));

        String csv = CsvStatementParser.HEADER + "\n"
                + txnId + ",5000,75,EUR,2026-09-10T11:04:22Z,SETTLED\n";

        ReconciliationReport first = reconcileCsv(csv, "fees.csv");
        assertThat(first.count(ReconciliationReport.Finding.Kind.FEE_POSTED)).isEqualTo(1);

        List<LedgerEntry> feeEntries = ledger.entriesForReference(reference.toString()).stream()
                .filter(e -> e.id().equals(StatementReconciliation.feeEntryId(MTN, txnId)))
                .toList();
        assertThat(feeEntries).singleElement().satisfies(entry -> {
            assertThat(entry.postings()).hasSize(2);
            assertThat(entry.total()).isEqualTo(Money.of(75, Currency.EUR));
            assertThat(signed(entry, AccountId.fees("mtn", Currency.EUR))).isEqualTo(75L);
            assertThat(signed(entry, AccountId.providerFloat("mtn", Currency.EUR))).isEqualTo(-75L);
            long sum = entry.postings().stream().mapToLong(p -> p.amount().amount()).sum();
            assertThat(sum).as("the entry balances").isZero();
        });

        // The same file again. The duplicate is refused by the ledger's primary key, not by
        // a check here: the entry id is derived from the statement line's identity.
        List<LedgerEntry> ledgerBeforeReimport = ledger.entries();
        ReconciliationReport again = reconcileCsv(csv, "fees.csv");

        assertThat(again.count(ReconciliationReport.Finding.Kind.FEE_ALREADY_POSTED)).isEqualTo(1);
        assertThat(again.count(ReconciliationReport.Finding.Kind.FEE_POSTED)).isZero();
        assertThat(ledger.entries())
                .as("a re-import writes no new ledger entry")
                .isEqualTo(ledgerBeforeReimport);
    }

    // === 2. a matched line with no fee posts nothing and is not an anomaly ===========

    @Test
    @DisplayName("a matched line with no fee posts nothing and reports no anomaly")
    void a_matched_line_with_no_fee_posts_nothing_and_is_not_an_anomaly() {
        String txnId = "txn-nofee-" + System.nanoTime();
        aSettledPayment(txnId, Money.of(7_000, Currency.EUR));

        String csv = CsvStatementParser.HEADER + "\n"
                + txnId + ",7000,,EUR,2026-09-10T11:06:00Z,SETTLED\n";

        List<LedgerEntry> ledgerBefore = ledger.entries();
        ReconciliationReport report = reconcileCsv(csv, "nofee.csv");

        assertThat(report.findings())
                .filteredOn(f -> txnId.equals(f.operatorTransactionId()))
                .singleElement()
                .satisfies(f -> assertThat(f.kind()).isEqualTo(ReconciliationReport.Finding.Kind.MATCHED));
        assertThat(report.anomalies())
                .as("this line is not among the anomalies")
                .noneMatch(f -> txnId.equals(f.operatorTransactionId()));
        assertThat(ledger.entries()).as("no fee, nothing written").isEqualTo(ledgerBefore);
    }

    // === 3. an unattributable line posts to suspense, and suspense is non-zero afterwards ===

    @Test
    @DisplayName("a line naming money no payment holds posts DR float / CR suspense, and the suspense balance is non-zero")
    void an_unattributable_line_posts_to_suspense() {
        String orphanTxn = "txn-orphan-" + System.nanoTime();
        Money before = ledger.balance(AccountId.suspense("mtn", Currency.EUR), Currency.EUR);

        String csv = CsvStatementParser.HEADER + "\n"
                + orphanTxn + ",3000,0,EUR,2026-09-10T12:00:00Z,SETTLED\n";
        ReconciliationReport report = reconcileCsv(csv, "orphan.csv");

        assertThat(report.count(ReconciliationReport.Finding.Kind.SUSPENSE_POSTED)).isEqualTo(1);
        Money after = ledger.balance(AccountId.suspense("mtn", Currency.EUR), Currency.EUR);
        assertThat(after).isEqualTo(before.minus(Money.of(3_000, Currency.EUR)));
        assertThat(after.isZero()).as("the suspense balance did not return to zero — a human must look").isFalse();

        LedgerEntry entry = ledger.entriesForReference("statement:" + orphanTxn).stream()
                .filter(e -> e.id().equals(StatementReconciliation.suspenseEntryId(MTN, orphanTxn)))
                .findFirst().orElseThrow();
        assertThat(signed(entry, AccountId.providerFloat("mtn", Currency.EUR))).isEqualTo(3_000L);
        assertThat(signed(entry, AccountId.suspense("mtn", Currency.EUR))).isEqualTo(-3_000L);

        // Re-importing the same line: the suspense entry's id is derived from the line, so
        // the ledger refuses the duplicate and the balance does not move again.
        List<LedgerEntry> ledgerBeforeReimport = ledger.entries();
        ReconciliationReport again = reconcileCsv(csv, "orphan.csv");
        assertThat(again.count(ReconciliationReport.Finding.Kind.SUSPENSE_ALREADY_POSTED)).isEqualTo(1);
        assertThat(again.count(ReconciliationReport.Finding.Kind.SUSPENSE_POSTED)).isZero();
        assertThat(ledger.entries()).isEqualTo(ledgerBeforeReimport);
        assertThat(ledger.balance(AccountId.suspense("mtn", Currency.EUR), Currency.EUR)).isEqualTo(after);
    }

    // === 5. an amount mismatch is reported and nothing is written ====================

    @Test
    @DisplayName("a line whose amount disagrees with the payment is reported and the ledger is unchanged")
    void an_amount_mismatch_is_reported_and_nothing_is_written() {
        String txnId = "txn-mismatch-" + System.nanoTime();
        ReferenceId reference = aSettledPayment(txnId, Money.of(5_000, Currency.EUR));

        String csv = CsvStatementParser.HEADER + "\n"
                + txnId + ",4200,50,EUR,2026-09-10T11:07:00Z,SETTLED\n";

        List<LedgerEntry> ledgerBefore = ledger.entries();
        ReconciliationReport report = reconcileCsv(csv, "mismatch.csv");

        assertThat(report.findings())
                .filteredOn(f -> txnId.equals(f.operatorTransactionId()))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.kind()).isEqualTo(ReconciliationReport.Finding.Kind.AMOUNT_MISMATCH);
                    assertThat(f.paymentReference()).isEqualTo(reference);
                });
        assertThat(ledger.entries())
                .as("an amount mismatch writes nothing — not the fee, not a correction")
                .isEqualTo(ledgerBefore);
    }

    // === 6. the report ties back to its import, and both survive a restart ===========

    @Test
    @DisplayName("the report and its statement lines are rebuilt from their rows by a fresh store — they survive a restart")
    void the_report_and_its_lines_survive_a_restart() {
        String txnId = "txn-persist-" + System.nanoTime();
        ReferenceId reference = aSettledPayment(txnId, Money.of(9_000, Currency.EUR));
        String csv = CsvStatementParser.HEADER + "\n"
                + txnId + ",9000,120,EUR,2026-09-10T13:00:00Z,SETTLED\n"
                + "txn-orphan-persist,1000,0,EUR,2026-09-10T13:01:00Z,SETTLED\n";

        ReconciliationReport live = reconcileCsv(csv, "restart.csv");

        // A brand-new store, as a process restart would build — nothing cached.
        StatementReconciliationStore afterRestart = new PostgresStatementReconciliationStore(jdbc, txManager);
        ReconciliationReport reloaded = afterRestart.findReport(live.importId()).orElseThrow();

        assertThat(reloaded.importId()).isEqualTo(live.importId());
        assertThat(reloaded.provider()).isEqualTo(MTN);
        assertThat(reloaded.sourceName()).isEqualTo("restart.csv");
        assertThat(reloaded.lineCount()).isEqualTo(2);
        assertThat(reloaded.count(ReconciliationReport.Finding.Kind.FEE_POSTED)).isEqualTo(1);
        assertThat(reloaded.count(ReconciliationReport.Finding.Kind.SUSPENSE_POSTED)).isEqualTo(1);
        assertThat(reloaded.findings())
                .anyMatch(f -> f.kind() == ReconciliationReport.Finding.Kind.FEE_POSTED
                        && reference.equals(f.paymentReference()));

        List<StatementLine> reloadedLines = afterRestart.findLines(live.importId());
        assertThat(reloadedLines).hasSize(2);
        assertThat(reloadedLines.get(0).operatorTransactionId()).isEqualTo(txnId);
        assertThat(reloadedLines.get(0).fee()).isEqualTo(Money.of(120, Currency.EUR));
        assertThat(reloadedLines.get(1).operatorTransactionId()).isEqualTo("txn-orphan-persist");
    }

    // === 2. the command's exit code says whether a human must look ===================

    @Test
    @DisplayName("an import whose report holds a discrepancy exits non-zero, so a scheduled run is noticed")
    void the_command_exits_non_zero_when_the_report_has_a_discrepancy() {
        // An orphan line always yields a SUSPENSE_POSTED anomaly, whatever else is in the
        // shared database. (The clean -> 0 direction, and the kind-by-kind mapping, are
        // StatementImportTest's — it controls the report.)
        StatementImport.Result result = runImport(CsvStatementParser.HEADER + "\n"
                + "txn-exit-orphan-" + System.nanoTime() + ",1000,0,EUR,2026-09-10T15:01:00Z,SETTLED\n", "orphan.csv");

        assertThat(result.report().anomalies()).isNotEmpty();
        assertThat(result.exitCode()).isEqualTo(StatementImport.EXIT_DISCREPANCIES);
    }

    // === helpers ===================================================================

    private static long signed(LedgerEntry entry, AccountId account) {
        return entry.postings().stream()
                .filter(p -> p.account().equals(account))
                .mapToLong(p -> p.amount().amount())
                .sum();
    }

    private static ReconciliationReport reconcileCsv(String csv, String source) {
        return runImport(csv, source).report();
    }

    /** Writes {@code csv} to a temp file and runs the import command over it, as the runner does. */
    private static StatementImport.Result runImport(String csv, String source) {
        try {
            Path file = Files.createTempFile("statement-", ".csv");
            file.toFile().deleteOnExit();
            Files.writeString(file, csv);
            return statementImport.run(file, MTN, source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PaymentIntent intent(Money amount) {
        return new PaymentIntent(Capability.Operation.COLLECT, amount, "46733123453", "rent", "march", Map.of());
    }

    /** Persists a payment and drives it to SUCCEEDED through the real settlement path: one gross entry, one SUCCEEDED transition. */
    private static ReferenceId aSettledPayment(String operatorTransactionId, Money amount) {
        ReferenceId reference = ReferenceId.newReference();
        Payment submitted = Payment.create(reference, MTN, "merchant-1", intent(amount));
        submitted.applyTransition(PaymentState.SUBMITTED, PaymentTransition.Cause.SUBMIT_RESPONSE, "", "", "");
        payments.save(submitted);

        ProviderAdapter adapter = mock(ProviderAdapter.class);
        AdapterRegistry adapters = mock(AdapterRegistry.class);
        when(adapters.require(any())).thenReturn(adapter);
        try {
            when(adapter.query(any(), any())).thenReturn(new ProviderStatus(
                    PaymentState.SUCCEEDED, "SUCCESSFUL", operatorTransactionId, null, "",
                    "{\"status\":\"SUCCESSFUL\"}"));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        new SettlementService(payments, adapters, ledger, txManager)
                .confirm(MTN, reference, PaymentTransition.Cause.CALLBACK);
        return reference;
    }
}

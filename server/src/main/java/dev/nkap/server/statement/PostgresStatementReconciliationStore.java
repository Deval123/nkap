package dev.nkap.server.statement;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.statement.ReconciliationReport.Finding;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link StatementReconciliationStore} in PostgreSQL: plain SQL through {@link JdbcTemplate}.
 *
 * <p>The settled-payment reads are a projection of the {@code payment} table — {@code state =
 * 'SUCCEEDED'} rows that carry an operator transaction id. The import writes go to the three
 * append-only tables from {@code V5}, in one transaction so a report is never half-recorded.
 */
public final class PostgresStatementReconciliationStore implements StatementReconciliationStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PostgresStatementReconciliationStore(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public Optional<SettledPayment> findSettledByOperatorTransactionId(ProviderId provider, String operatorTransactionId) {
        List<SettledPayment> found = jdbc.query(
                SETTLED_SELECT + " AND provider_transaction_id = ?",
                SETTLED_MAPPER, provider.toString(), operatorTransactionId);
        return found.stream().findFirst();
    }

    @Override
    public List<SettledPayment> settledPayments(ProviderId provider) {
        return jdbc.query(SETTLED_SELECT + " ORDER BY created_at, reference", SETTLED_MAPPER, provider.toString());
    }

    private static final String SETTLED_SELECT =
            "SELECT reference, provider_transaction_id, amount_minor, currency, merchant_id "
                    + "FROM payment WHERE state = 'SUCCEEDED' AND provider = ? AND provider_transaction_id <> ''";

    private static final RowMapper<SettledPayment> SETTLED_MAPPER = (ResultSet rs, int rowNum) -> new SettledPayment(
            new ReferenceId(rs.getObject("reference", UUID.class)),
            rs.getString("provider_transaction_id"),
            Money.of(rs.getLong("amount_minor"), Currency.valueOf(rs.getString("currency"))),
            rs.getString("merchant_id"));

    @Override
    public void record(ReconciliationReport report, List<StatementLine> lines) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(lines, "lines");
        tx.executeWithoutResult(status -> {
            jdbc.update(
                    "INSERT INTO statement_import (id, provider, source_name, imported_at, line_count, "
                            + "fee_posted_count, fee_already_count, matched_count, suspense_count, "
                            + "suspense_already_count, missing_count, mismatch_count) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    report.importId(),
                    report.provider().toString(),
                    report.sourceName(),
                    OffsetDateTime.ofInstant(report.importedAt(), ZoneOffset.UTC),
                    report.lineCount(),
                    report.count(Finding.Kind.FEE_POSTED),
                    report.count(Finding.Kind.FEE_ALREADY_POSTED),
                    report.count(Finding.Kind.MATCHED),
                    report.count(Finding.Kind.SUSPENSE_POSTED),
                    report.count(Finding.Kind.SUSPENSE_ALREADY_POSTED),
                    report.count(Finding.Kind.MISSING_FROM_STATEMENT),
                    report.count(Finding.Kind.AMOUNT_MISMATCH));

            for (int seq = 0; seq < lines.size(); seq++) {
                StatementLine line = lines.get(seq);
                jdbc.update(
                        "INSERT INTO statement_line (import_id, seq, operator_transaction_id, amount_minor, fee_minor, "
                                + "currency, occurred_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        report.importId(), seq,
                        line.operatorTransactionId(),
                        line.amount().amount(),
                        line.fee().amount(),
                        line.amount().currency().name(),
                        OffsetDateTime.ofInstant(line.occurredAt(), ZoneOffset.UTC),
                        line.status().name());
            }

            List<Finding> findings = report.findings();
            for (int seq = 0; seq < findings.size(); seq++) {
                Finding finding = findings.get(seq);
                jdbc.update(
                        "INSERT INTO statement_finding (import_id, seq, kind, operator_transaction_id, payment_reference, detail) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        report.importId(), seq,
                        finding.kind().name(),
                        finding.operatorTransactionId(),
                        finding.paymentReference() == null ? null : finding.paymentReference().value(),
                        finding.detail());
            }
        });
    }

    @Override
    public Optional<ReconciliationReport> findReport(UUID importId) {
        List<ImportRow> rows = jdbc.query(
                "SELECT id, provider, source_name, imported_at, line_count FROM statement_import WHERE id = ?",
                IMPORT_MAPPER, importId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        ImportRow header = rows.get(0);
        List<Finding> findings = jdbc.query(
                "SELECT kind, operator_transaction_id, payment_reference, detail "
                        + "FROM statement_finding WHERE import_id = ? ORDER BY seq",
                FINDING_MAPPER, importId);
        return Optional.of(new ReconciliationReport(
                header.id(), ProviderId.of(header.provider()), header.sourceName(),
                header.importedAt(), header.lineCount(), findings));
    }

    @Override
    public List<StatementLine> findLines(UUID importId) {
        return jdbc.query(
                "SELECT operator_transaction_id, amount_minor, fee_minor, currency, occurred_at, status "
                        + "FROM statement_line WHERE import_id = ? ORDER BY seq",
                LINE_MAPPER, importId);
    }

    private record ImportRow(UUID id, String provider, String sourceName, java.time.Instant importedAt, int lineCount) {
    }

    private static final RowMapper<ImportRow> IMPORT_MAPPER = (ResultSet rs, int rowNum) -> new ImportRow(
            rs.getObject("id", UUID.class),
            rs.getString("provider"),
            rs.getString("source_name"),
            rs.getObject("imported_at", OffsetDateTime.class).toInstant(),
            rs.getInt("line_count"));

    private static final RowMapper<Finding> FINDING_MAPPER = (ResultSet rs, int rowNum) -> {
        UUID reference = rs.getObject("payment_reference", UUID.class);
        return new Finding(
                Finding.Kind.valueOf(rs.getString("kind")),
                rs.getString("operator_transaction_id"),
                reference == null ? null : new ReferenceId(reference),
                rs.getString("detail"));
    };

    private static final RowMapper<StatementLine> LINE_MAPPER = (ResultSet rs, int rowNum) -> mapLine(rs);

    private static StatementLine mapLine(ResultSet rs) throws SQLException {
        Currency currency = Currency.valueOf(rs.getString("currency"));
        return new StatementLine(
                rs.getString("operator_transaction_id"),
                Money.of(rs.getLong("amount_minor"), currency),
                Money.of(rs.getLong("fee_minor"), currency),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                StatementLine.Status.valueOf(rs.getString("status")));
    }
}

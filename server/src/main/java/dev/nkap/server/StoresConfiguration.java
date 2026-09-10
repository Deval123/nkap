package dev.nkap.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.core.idempotency.IdempotencyStore;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.server.payment.PaymentRepository;
import dev.nkap.server.persistence.PostgresIdempotencyStore;
import dev.nkap.server.persistence.PostgresLedger;
import dev.nkap.server.persistence.PostgresPaymentRepository;
import dev.nkap.server.statement.CsvStatementParser;
import dev.nkap.server.statement.PostgresStatementReconciliationStore;
import dev.nkap.server.statement.StatementParser;
import dev.nkap.server.statement.StatementReconciliationStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The stores, backed by PostgreSQL.
 *
 * <p>The interfaces live in {@code core} and {@code server.payment}; the implementations
 * here are plain SQL through {@link JdbcTemplate}. The in-memory reference implementations
 * stay in {@code core} (and the test tree) as what the rules were written against — the
 * server simply does not wire them. There is no flag and no documented in-memory mode:
 * this class is the one place that decides, and swapping a bean is the whole change.
 */
@Configuration
class StoresConfiguration {

    @Bean
    IdempotencyStore idempotencyStore(JdbcTemplate jdbc) {
        return new PostgresIdempotencyStore(jdbc);
    }

    @Bean
    Ledger ledger(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        return new PostgresLedger(jdbc, txManager);
    }

    @Bean
    PaymentRepository paymentRepository(JdbcTemplate jdbc, ObjectMapper json) {
        return new PostgresPaymentRepository(jdbc, json);
    }

    @Bean
    StatementReconciliationStore statementReconciliationStore(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        return new PostgresStatementReconciliationStore(jdbc, txManager);
    }

    @Bean
    StatementParser statementParser() {
        // The one implementation, and a placeholder until a real MTN statement has been seen
        // — the column layout is ours (see CsvStatementParser and docs/providers/mtn.md).
        return new CsvStatementParser();
    }
}

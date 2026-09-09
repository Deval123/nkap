package dev.nkap.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.DuplicateLedgerEntryException;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.ledger.Posting;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.server.support.DockerAvailable;
import dev.nkap.server.support.PostgresDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The invariants, proved from the adversarial position: connect, try to corrupt the ledger,
 * expect PostgreSQL to refuse — an unbalanced entry, a mixed currency, an {@code UPDATE},
 * a {@code DELETE}. A rule that lives only in Java is one a migration script walks through.
 */
@ExtendWith(DockerAvailable.class)
class LedgerConstraintsIT {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static PostgresLedger ledger;

    @BeforeAll
    static void connect() {
        PostgresDatabase db = PostgresDatabase.shared();
        dataSource = db.dataSource();
        jdbc = db.jdbcTemplate();
        ledger = new PostgresLedger(jdbc);
    }

    private static String freshEntryId() {
        return "test:" + UUID.randomUUID();
    }

    private static LedgerEntry balanced(String id, long amount) {
        return new LedgerEntry(id, Instant.now(), UUID.randomUUID().toString(), "test entry", List.of(
                Posting.debit(AccountId.of("provider:mtn:float:EUR"), Money.of(amount, Currency.EUR)),
                Posting.credit(AccountId.of("merchant:acme:payable:EUR"), Money.of(amount, Currency.EUR))));
    }

    @Test
    @DisplayName("an unbalanced entry is refused by the database — at commit, after the postings went in one by one")
    void an_unbalanced_entry_is_refused_at_commit() throws SQLException {
        String id = freshEntryId();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertEntry(connection, id, "EUR");
            insertPosting(connection, id, 0, "provider:mtn:float:EUR", 5_000);   // deferred check: no error yet
            insertPosting(connection, id, 1, "merchant:acme:payable:EUR", -3_000); // still no error

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("does not balance");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE id = ?", Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("a single-posting entry is refused too: a movement has at least two sides")
    void a_single_posting_entry_is_refused_at_commit() throws SQLException {
        String id = freshEntryId();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertEntry(connection, id, "EUR");
            insertPosting(connection, id, 0, "provider:mtn:float:EUR", 5_000);

            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("at least two sides");
        }
    }

    @Test
    @DisplayName("a balanced entry whose postings were inserted one by one commits cleanly")
    void a_balanced_entry_commits() throws SQLException {
        String id = freshEntryId();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            insertEntry(connection, id, "EUR");
            insertPosting(connection, id, 0, "provider:mtn:float:EUR", 5_000);
            insertPosting(connection, id, 1, "merchant:acme:payable:EUR", -5_000);
            connection.commit();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting WHERE entry_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    @DisplayName("a mixed-currency entry cannot be expressed: a posting has no currency column, the entry carries it")
    void a_mixed_currency_entry_cannot_be_expressed() {
        Integer postingCurrencyColumns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'posting' AND column_name = 'currency'",
                Integer.class);
        assertThat(postingCurrencyColumns).isZero();

        Integer entryCurrencyColumns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'ledger_entry' AND column_name = 'currency'",
                Integer.class);
        assertThat(entryCurrencyColumns).isEqualTo(1);
    }

    @Test
    @DisplayName("amounts are BIGINT minor units, never NUMERIC")
    void amounts_are_bigint() {
        assertThat(columnType("posting", "amount_minor")).isEqualTo("bigint");
        assertThat(columnType("payment", "amount_minor")).isEqualTo("bigint");
    }

    @Test
    @DisplayName("UPDATE and DELETE on a recorded ledger entry are refused from the application's own connection")
    void update_and_delete_on_a_recorded_entry_are_refused() {
        String id = freshEntryId();
        ledger.append(balanced(id, 5_000));

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entry SET description = 'tampered' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entry WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE posting SET amount_minor = 1 WHERE entry_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM posting WHERE entry_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject("SELECT description FROM ledger_entry WHERE id = ?", String.class, id))
                .isEqualTo("test entry");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting WHERE entry_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    @DisplayName("a duplicate entry id raises the duplicate-specific exception; a different integrity error does not")
    void a_duplicate_id_raises_the_duplicate_specific_exception() {
        LedgerEntry entry = balanced(freshEntryId(), 5_000);
        ledger.append(entry);

        assertThatThrownBy(() -> ledger.append(entry)).isInstanceOf(DuplicateLedgerEntryException.class);

        // A foreign-key violation is an integrity error, but not this one.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO posting (entry_id, seq, account, amount_minor) VALUES ('no-such-entry', 0, 'a:b:EUR', 1)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateLedgerEntryException.class);
    }

    @Test
    @DisplayName("appending through PostgresLedger and reading it back round-trips the entry")
    void append_then_read_round_trips() {
        String reference = UUID.randomUUID().toString();
        LedgerEntry entry = new LedgerEntry("test:" + reference, Instant.now(), reference, "round trip", List.of(
                Posting.debit(AccountId.of("provider:mtn:float:XAF"), Money.of(5_000, Currency.XAF)),
                Posting.credit(AccountId.of("merchant:acme:payable:XAF"), Money.of(5_000, Currency.XAF))));

        assertThatCode(() -> ledger.append(entry)).doesNotThrowAnyException();

        assertThat(ledger.entriesForReference(reference)).singleElement().satisfies(read -> {
            assertThat(read.currency()).isEqualTo(Currency.XAF);
            assertThat(read.total()).isEqualTo(Money.of(5_000, Currency.XAF));
            assertThat(read.postings()).hasSize(2);
        });
        assertThat(ledger.balance(AccountId.of("provider:mtn:float:XAF"), Currency.XAF).amount()).isGreaterThanOrEqualTo(5_000);
    }

    private static void insertEntry(Connection connection, String id, String currency) throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT INTO ledger_entry (id, occurred_at, reference, description, currency) VALUES (?, now(), ?, '', ?)")) {
            ps.setString(1, id);
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, currency);
            ps.executeUpdate();
        }
    }

    private static void insertPosting(Connection connection, String entryId, int seq, String account, long amount)
            throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT INTO posting (entry_id, seq, account, amount_minor) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, entryId);
            ps.setInt(2, seq);
            ps.setString(3, account);
            ps.setLong(4, amount);
            ps.executeUpdate();
        }
    }

    private static String columnType(String table, String column) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                String.class, table, column);
    }
}

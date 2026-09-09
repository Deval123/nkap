package dev.nkap.server.persistence;

import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.DuplicateLedgerEntryException;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.ledger.Posting;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link Ledger} in PostgreSQL.
 *
 * <p>The invariants are the schema's, not this class's (see {@code V1__initial_schema.sql}):
 * the currency lives on the entry so a mixed-currency entry cannot be expressed, deferred
 * constraint triggers enforce zero-sum and the entry's declared posting count at commit, and
 * a {@code BEFORE UPDATE OR DELETE} trigger makes both tables append-only. This class only
 * writes and reads rows — {@code posting_count} is set here from {@code entry.postings()}
 * so the entry declares its own shape at the moment it is recorded.
 *
 * <p>{@code append} inserts the entry and its postings in one transaction: the deferred
 * trigger on {@code ledger_entry} checks the declared count against the actual one at
 * commit, so a caller relying on autocommit — the entry row committing on its own, before a
 * single posting exists — would trip that check every time. {@link TransactionTemplate}'s
 * default propagation joins a transaction already open (as {@code SettlementService} keeps
 * the entry and the payment's state change together) rather than nesting one inside it.
 *
 * <p>{@code append} maps the one integrity error it must classify — a duplicate entry id,
 * which the primary key raises <em>immediately</em> — to {@link DuplicateLedgerEntryException}.
 * Every other integrity error propagates. A zero-sum or shape violation cannot come from a
 * {@link LedgerEntry} (its own constructor guarantees balance); if the deferred trigger ever
 * fires it is on adversarial SQL, and it aborts the transaction at commit.
 */
public final class PostgresLedger implements Ledger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PostgresLedger(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void append(LedgerEntry entry) {
        Objects.requireNonNull(entry, "entry");
        tx.executeWithoutResult(status -> insertEntryAndPostings(entry));
    }

    private void insertEntryAndPostings(LedgerEntry entry) {
        try {
            jdbc.update(
                    "INSERT INTO ledger_entry (id, occurred_at, reference, description, currency, posting_count) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    entry.id(),
                    OffsetDateTime.ofInstant(entry.occurredAt(), ZoneOffset.UTC),
                    entry.reference(),
                    entry.description(),
                    entry.currency().name(),
                    entry.postings().size());
        } catch (DuplicateKeyException duplicate) {
            throw new DuplicateLedgerEntryException(entry.id());
        }

        List<Posting> postings = entry.postings();
        jdbc.batchUpdate(
                "INSERT INTO posting (entry_id, seq, account, amount_minor) VALUES (?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Posting posting = postings.get(i);
                        ps.setString(1, entry.id());
                        ps.setInt(2, i);
                        ps.setString(3, posting.account().value());
                        ps.setLong(4, posting.amount().amount());
                    }

                    @Override
                    public int getBatchSize() {
                        return postings.size();
                    }
                });
    }

    @Override
    public Money balance(AccountId account, Currency currency) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(currency, "currency");
        Long sum = jdbc.queryForObject(
                "SELECT coalesce(sum(p.amount_minor), 0) FROM posting p "
                        + "JOIN ledger_entry e ON e.id = p.entry_id "
                        + "WHERE p.account = ? AND e.currency = ?",
                Long.class, account.value(), currency.name());
        return Money.of(sum == null ? 0L : sum, currency);
    }

    @Override
    public List<LedgerEntry> entries() {
        return jdbc.query(
                "SELECT e.id, e.occurred_at, e.reference, e.description, e.currency, "
                        + "p.seq, p.account, p.amount_minor "
                        + "FROM ledger_entry e JOIN posting p ON p.entry_id = e.id "
                        + "ORDER BY e.occurred_at, e.id, p.seq",
                PostgresLedger::extractEntries);
    }

    @Override
    public List<LedgerEntry> entriesForReference(String reference) {
        Objects.requireNonNull(reference, "reference");
        return jdbc.query(
                "SELECT e.id, e.occurred_at, e.reference, e.description, e.currency, "
                        + "p.seq, p.account, p.amount_minor "
                        + "FROM ledger_entry e JOIN posting p ON p.entry_id = e.id "
                        + "WHERE e.reference = ? "
                        + "ORDER BY e.occurred_at, e.id, p.seq",
                PostgresLedger::extractEntries, reference);
    }

    /** Groups the joined rows back into entries, each row of an entry being one posting. */
    private static List<LedgerEntry> extractEntries(ResultSet rs) throws SQLException {
        List<LedgerEntry> result = new ArrayList<>();
        String currentId = null;
        OffsetDateTime occurredAt = null;
        String reference = null;
        String description = null;
        Currency currency = null;
        List<Posting> postings = new ArrayList<>();

        while (rs.next()) {
            String id = rs.getString("id");
            if (!id.equals(currentId)) {
                if (currentId != null) {
                    result.add(new LedgerEntry(currentId, occurredAt.toInstant(), reference, description, postings));
                }
                currentId = id;
                occurredAt = rs.getObject("occurred_at", OffsetDateTime.class);
                reference = rs.getString("reference");
                description = rs.getString("description");
                currency = Currency.valueOf(rs.getString("currency"));
                postings = new ArrayList<>();
            }
            postings.add(new Posting(AccountId.of(rs.getString("account")),
                    Money.of(rs.getLong("amount_minor"), currency)));
        }
        if (currentId != null) {
            result.add(new LedgerEntry(currentId, occurredAt.toInstant(), reference, description, postings));
        }
        return result;
    }
}

package dev.nkap.server.statement;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The one {@link StatementParser} — and a <strong>placeholder until a real MTN statement has
 * been seen</strong>. The column layout below is <em>ours</em>, defined from what
 * {@link StatementLine} needs, not from anything observed:
 *
 * <pre>
 * operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status
 * FT2026-0001,5000,75,EUR,2026-09-10T11:04:22Z,SETTLED
 * FT2026-0002,5000,,EUR,2026-09-10T11:05:01Z,SETTLED
 * </pre>
 *
 * <ul>
 *   <li>A header row is required and must match, field for field, in this order.</li>
 *   <li>{@code amount_minor} and {@code fee_minor} are integer minor units. {@code fee_minor}
 *       may be empty or {@code 0} — a line with no fee.</li>
 *   <li>{@code currency} is a {@link Currency} name; {@code occurred_at} is an ISO-8601
 *       instant; {@code status} is a {@link StatementLine.Status} name.</li>
 *   <li>No quoting, no embedded commas, no escaping. A real statement will need more; this
 *       is deliberately the least parser that lets the rest of the slice be built and
 *       tested. When the real format is known, this class changes and nothing else does.</li>
 * </ul>
 *
 * <p>Not named {@code MtnStatementParser}: it is not MTN's format, it is the format Nkap can
 * define today. See {@code docs/providers/mtn.md}, <em>Still unknown</em>.
 */
public final class CsvStatementParser implements StatementParser {

    static final String HEADER = "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status";
    private static final int FIELD_COUNT = 6;

    @Override
    public List<StatementLine> parse(Reader source) {
        List<StatementLine> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(source)) {
            String header = reader.readLine();
            if (header == null) {
                throw new StatementFormatException("empty statement: expected a header row '" + HEADER + "'");
            }
            if (!HEADER.equals(header.strip())) {
                throw new StatementFormatException(
                        "unexpected header: got '" + header.strip() + "', expected '" + HEADER + "'");
            }
            String row;
            int number = 1;
            while ((row = reader.readLine()) != null) {
                number++;
                if (row.isBlank()) {
                    continue;
                }
                lines.add(parseRow(row, number));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the statement", e);
        }
        return List.copyOf(lines);
    }

    private static StatementLine parseRow(String row, int number) {
        String[] fields = row.split(",", -1);
        if (fields.length != FIELD_COUNT) {
            throw new StatementFormatException(
                    "row " + number + " has " + fields.length + " fields, expected " + FIELD_COUNT + ": '" + row + "'");
        }
        String operatorTransactionId = fields[0].strip();
        Currency currency = currency(fields[3].strip(), number);
        Money amount = Money.of(longField(fields[1].strip(), "amount_minor", number), currency);
        String feeText = fields[2].strip();
        Money fee = feeText.isEmpty() ? Money.zero(currency)
                : Money.of(longField(feeText, "fee_minor", number), currency);
        Instant occurredAt = instant(fields[4].strip(), number);
        StatementLine.Status status = status(fields[5].strip(), number);
        try {
            return new StatementLine(operatorTransactionId, amount, fee, occurredAt, status);
        } catch (IllegalArgumentException rejected) {
            throw new StatementFormatException("row " + number + ": " + rejected.getMessage(), rejected);
        }
    }

    private static long longField(String value, String field, int number) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new StatementFormatException("row " + number + ": " + field + " '" + value + "' is not an integer");
        }
    }

    private static Currency currency(String value, int number) {
        try {
            return Currency.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new StatementFormatException("row " + number + ": unknown currency '" + value + "'");
        }
    }

    private static Instant instant(String value, int number) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new StatementFormatException("row " + number + ": occurred_at '" + value + "' is not an ISO-8601 instant");
        }
    }

    private static StatementLine.Status status(String value, int number) {
        try {
            return StatementLine.Status.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new StatementFormatException("row " + number + ": unknown status '" + value + "', expected SETTLED or FAILED");
        }
    }
}

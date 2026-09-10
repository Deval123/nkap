package dev.nkap.server.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one parser, and the format is <strong>ours</strong> — a placeholder until a real MTN
 * statement is seen. These pin what the placeholder accepts so that, when the real format is
 * known, the change is visible as a diff to this test and {@link CsvStatementParser} alone.
 */
class CsvStatementParserTest {

    private final CsvStatementParser parser = new CsvStatementParser();

    private List<StatementLine> parse(String csv) {
        return parser.parse(new StringReader(csv));
    }

    @Test
    @DisplayName("a well-formed file parses to lines in file order, an empty fee field meaning no fee")
    void parses_rows_in_order_with_optional_fee() {
        List<StatementLine> lines = parse(CsvStatementParser.HEADER + "\n"
                + "FT-1,5000,75,EUR,2026-09-10T11:04:22Z,SETTLED\n"
                + "FT-2,5000,,EUR,2026-09-10T11:05:01Z,SETTLED\n"
                + "FT-3,4000,0,XAF,2026-09-10T11:06:00Z,FAILED\n");

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo(new StatementLine(
                "FT-1", Money.of(5000, Currency.EUR), Money.of(75, Currency.EUR),
                Instant.parse("2026-09-10T11:04:22Z"), StatementLine.Status.SETTLED));
        assertThat(lines.get(1).hasFee()).as("an empty fee field is no fee, not an error").isFalse();
        assertThat(lines.get(1).fee()).isEqualTo(Money.zero(Currency.EUR));
        assertThat(lines.get(2).status()).isEqualTo(StatementLine.Status.FAILED);
    }

    @Test
    @DisplayName("blank lines are skipped, not treated as malformed rows")
    void skips_blank_lines() {
        List<StatementLine> lines = parse(CsvStatementParser.HEADER + "\n"
                + "FT-1,5000,75,EUR,2026-09-10T11:04:22Z,SETTLED\n"
                + "\n"
                + "FT-2,6000,0,EUR,2026-09-10T11:05:01Z,SETTLED\n");
        assertThat(lines).extracting(StatementLine::operatorTransactionId).containsExactly("FT-1", "FT-2");
    }

    @Test
    @DisplayName("a wrong or missing header is refused, naming the header expected")
    void rejects_a_wrong_header() {
        assertThatThrownBy(() -> parse("txn,amount,fee\nFT-1,5000,75\n"))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining(CsvStatementParser.HEADER);
        assertThatThrownBy(() -> parse(""))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("header");
    }

    @Test
    @DisplayName("a malformed row — wrong field count, non-integer amount, unknown currency or status — is refused with the row number")
    void rejects_malformed_rows() {
        assertThatThrownBy(() -> parse(CsvStatementParser.HEADER + "\nFT-1,5000,75,EUR\n"))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("row 2");
        assertThatThrownBy(() -> parse(CsvStatementParser.HEADER + "\nFT-1,notanumber,75,EUR,2026-09-10T11:04:22Z,SETTLED\n"))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("amount_minor");
        assertThatThrownBy(() -> parse(CsvStatementParser.HEADER + "\nFT-1,5000,75,ZZZ,2026-09-10T11:04:22Z,SETTLED\n"))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> parse(CsvStatementParser.HEADER + "\nFT-1,5000,75,EUR,2026-09-10T11:04:22Z,DONE\n"))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> parse(CsvStatementParser.HEADER + "\nFT-1,0,0,EUR,2026-09-10T11:04:22Z,SETTLED\n"))
                .as("a non-positive amount is refused by StatementLine and surfaced as a format error")
                .isInstanceOf(StatementFormatException.class);
    }
}

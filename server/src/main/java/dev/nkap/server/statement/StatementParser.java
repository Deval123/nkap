package dev.nkap.server.statement;

import java.io.Reader;
import java.util.List;

/**
 * Reads a statement file into provider-neutral {@link StatementLine}s.
 *
 * <p>One method, and one implementation ({@link CsvStatementParser}). The interface exists
 * so that the day a real MTN statement is seen, the parser is the <em>only</em> thing that
 * changes: reconciliation, persistence and the report all work in {@link StatementLine}s and
 * never touch a file format.
 */
public interface StatementParser {

    /**
     * Parses {@code source} into lines, in file order.
     *
     * @throws StatementFormatException if the file is not in the expected shape
     */
    List<StatementLine> parse(Reader source);
}

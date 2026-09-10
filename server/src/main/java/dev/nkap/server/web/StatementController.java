package dev.nkap.server.web;

import dev.nkap.provider.ProviderId;
import dev.nkap.server.statement.ReconciliationReport;
import dev.nkap.server.statement.StatementFormatException;
import dev.nkap.server.statement.StatementLine;
import dev.nkap.server.statement.StatementParser;
import dev.nkap.server.statement.StatementReconciliation;
import dev.nkap.server.statement.StatementReconciliationStore;
import java.io.StringReader;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The statement-reconciliation surface. An import is a fire-once action whose result is read
 * afterwards, not watched, so:
 *
 * <ul>
 *   <li>{@code POST /statements/imports} — body is the statement file (the CSV layout of
 *       {@code CsvStatementParser}, a placeholder until a real MTN statement is seen).
 *       Optional {@code ?provider=} overrides the configured default. Answers <strong>200</strong>
 *       with the full {@link ReconciliationReport}: what was posted, and every discrepancy.
 *       A file that will not parse is <strong>400</strong>; a discrepancy is not an error, it
 *       is in the report.</li>
 *   <li>{@code GET /statements/imports/{id}} — the same report, rebuilt from its rows, so it
 *       can be tied back to later. <strong>404</strong> if the id is unknown.</li>
 * </ul>
 */
@RestController
@RequestMapping("/statements/imports")
class StatementController {

    private static final Logger log = LoggerFactory.getLogger(StatementController.class);

    private final StatementParser parser;
    private final StatementReconciliation reconciliation;
    private final StatementReconciliationStore store;
    private final ProviderId defaultProvider;

    StatementController(StatementParser parser, StatementReconciliation reconciliation,
                        StatementReconciliationStore store,
                        @Value("${nkap.provider.default}") String defaultProvider) {
        this.parser = parser;
        this.reconciliation = reconciliation;
        this.store = store;
        this.defaultProvider = ProviderId.of(defaultProvider);
    }

    @PostMapping(consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE})
    ResponseEntity<ReconciliationReport> importStatement(@RequestBody String body,
                                                        @RequestParam(name = "provider", required = false) String provider,
                                                        @RequestParam(name = "source", defaultValue = "upload") String source) {
        ProviderId providerId = provider == null || provider.isBlank() ? defaultProvider : ProviderId.of(provider);
        List<StatementLine> lines;
        try {
            lines = parser.parse(new StringReader(body));
        } catch (StatementFormatException notParseable) {
            log.info("rejected a statement upload for {}: {}", providerId, notParseable.getMessage());
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_STATEMENT,
                    "The statement could not be parsed", notParseable.getMessage());
        }
        ReconciliationReport report = reconciliation.reconcile(providerId, source, lines);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/{importId}")
    ReconciliationReport get(@PathVariable String importId) {
        UUID id;
        try {
            id = UUID.fromString(importId);
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, ProblemTypes.IMPORT_NOT_FOUND,
                    "No such import", "'" + importId + "' is not a statement import id.");
        }
        return store.findReport(id).orElseThrow(() -> new ApiException(
                org.springframework.http.HttpStatus.NOT_FOUND, ProblemTypes.IMPORT_NOT_FOUND,
                "No such import", "No statement import has id " + importId + "."));
    }
}

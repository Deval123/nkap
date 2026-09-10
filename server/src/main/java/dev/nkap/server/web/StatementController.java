package dev.nkap.server.web;

import dev.nkap.server.auth.ApiCredential;
import dev.nkap.server.statement.ReconciliationReport;
import dev.nkap.server.statement.StatementReconciliationStore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read side of statement reconciliation, and <strong>only</strong> the read side.
 *
 * <p>{@code GET /statements/imports/{id}} rebuilds a stored {@link ReconciliationReport}
 * from its rows so a discrepancy can be tied back to the file and the moment that produced
 * it. It writes nothing, but it is operator data across <em>all</em> merchants, so it
 * requires an <strong>admin</strong> API key — a merchant key is {@code 403}. One boolean
 * on the key, not a role system.
 *
 * <p>There is deliberately no {@code POST} here. Running an import writes fee and suspense
 * entries to an append-only ledger straight from a file, with nothing between the file and a
 * permanent entry — unlike the callback path, where {@code adapter.query} is the authority
 * and the endpoint only ever triggers a question. This application authenticates nothing, so
 * an import endpoint would be an anonymous write path to the ledger. The import is a command
 * instead: {@code java -jar … --nkap.statement.import=<path>} (see {@code StatementImportRunner}).
 * The asymmetry — read over HTTP, write only from the host — is the point, not an oversight.
 */
@RestController
@RequestMapping("/statements/imports")
class StatementController {

    private final StatementReconciliationStore store;

    StatementController(StatementReconciliationStore store) {
        this.store = store;
    }

    @GetMapping("/{importId}")
    ReconciliationReport get(ApiCredential caller, @PathVariable String importId) {
        if (!caller.admin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ProblemTypes.ADMIN_REQUIRED,
                    "An admin key is required",
                    "The statement report is operator data across all merchants. This key is a merchant key.");
        }
        UUID id;
        try {
            id = UUID.fromString(importId);
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(HttpStatus.NOT_FOUND, ProblemTypes.IMPORT_NOT_FOUND,
                    "No such import", "'" + importId + "' is not a statement import id.");
        }
        return store.findReport(id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, ProblemTypes.IMPORT_NOT_FOUND,
                "No such import", "No statement import has id " + importId + "."));
    }
}

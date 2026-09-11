package dev.nkap.server.web;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.provider.Capability;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.server.auth.ApiCredential;
import dev.nkap.server.provider.AdapterRegistry;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /balance} — what this installation's default provider reports for one
 * product, read live from the operator right now.
 *
 * <p>This is deliberately <strong>not</strong> shaped like {@code GET /payments/{reference}}:
 * that endpoint reads Nkap's own stored state and never calls the operator, because a read
 * that hits a third party is a read that times out. This one calls the operator on every
 * request, on purpose — a cached balance is not a balance, it is yesterday's balance with
 * today's date on it.
 *
 * <p>Operator data across <strong>all</strong> merchants, so it requires an admin key, the
 * same gate {@code GET /statements/imports/{id}} uses. An operator that does not answer is
 * not a {@code 500}: {@link ProviderUnavailableException} is mapped to {@code 503} centrally
 * in {@link ApiExceptionHandler}, the read-side equivalent of a submission that does not
 * answer being a {@code 202} rather than an error.
 */
@RestController
class BalanceController {

    private final AdapterRegistry adapters;
    private final ProviderId defaultProvider;

    BalanceController(AdapterRegistry adapters, @Value("${nkap.provider.default}") String defaultProvider) {
        this.adapters = adapters;
        this.defaultProvider = ProviderId.of(defaultProvider);
    }

    @GetMapping("/balance")
    BalanceResponse balance(ApiCredential caller, @RequestParam String operation, @RequestParam String currency)
            throws ProviderUnavailableException {
        requireAdmin(caller);
        Capability.Operation op = operation(operation);
        Currency ccy = currency(currency);

        Money balance = adapters.require(defaultProvider).balance(op, ccy);

        return new BalanceResponse(defaultProvider.toString(), op.name(), balance.amount(), balance.currency().name());
    }

    private static void requireAdmin(ApiCredential caller) {
        if (!caller.admin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ProblemTypes.ADMIN_REQUIRED,
                    "An admin key is required",
                    "The balance is operator data across all merchants. This key is a merchant key.");
        }
    }

    /** Shared with {@link AccountHolderController}: the same field, the same rule. */
    static Capability.Operation operation(String raw) {
        try {
            return Capability.Operation.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException notAnOperation) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_REQUEST,
                    "The request is not valid", "operation must be COLLECT or DISBURSE, was '" + raw + "'");
        }
    }

    private static Currency currency(String raw) {
        try {
            return Currency.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException notACurrency) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_REQUEST,
                    "The request is not valid", "currency '" + raw + "' is not one Nkap counts in");
        }
    }
}

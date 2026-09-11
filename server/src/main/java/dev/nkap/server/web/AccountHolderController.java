package dev.nkap.server.web;

import dev.nkap.provider.Capability;
import dev.nkap.provider.HolderStatus;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.server.provider.AdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /account-holders/{msisdn}} — whether the number is an active mobile money
 * account at this installation's default provider, read live, right now.
 *
 * <p>A merchant's own question about a number it is about to pay, not operator-wide data the
 * way {@code GET /balance} is — a <strong>merchant key</strong> is enough. Every route on
 * this gateway except {@code /callbacks/**} is authenticated by {@code CallerAuthInterceptor}
 * whether or not the controller declares {@code ApiCredential caller}; this one does not
 * declare it, because unlike {@code GET /balance} there is no further check to make once a
 * key of either kind has been accepted.
 *
 * <p>{@code GET}, and it is worth one sentence of why. The check is idempotent — asking
 * twice does not change the account — and cacheable in principle, which is what a
 * {@code GET} promises. It is deliberately <strong>not</strong> cached here: the promise a
 * cache relies on is that the answer is stable for a while, and this answer can flip the
 * moment the account is deactivated, so serving a stale "active" from a shared cache costs
 * someone a payment sent to a number that no longer takes it. What {@code GET} still buys
 * is honesty about the method — this looks something up and changes nothing at this
 * gateway — at the real cost the plan asked to name: every call here is an outbound
 * request against the operator's own quota, not Nkap's.
 *
 * <p>An operator that does not answer is {@code 503} via {@link ProviderUnavailableException},
 * mapped centrally in {@link ApiExceptionHandler} — never {@link HolderStatus#INACTIVE}
 * guessed on the caller's behalf.
 */
@RestController
class AccountHolderController {

    private final AdapterRegistry adapters;
    private final ProviderId defaultProvider;

    AccountHolderController(AdapterRegistry adapters, @Value("${nkap.provider.default}") String defaultProvider) {
        this.adapters = adapters;
        this.defaultProvider = ProviderId.of(defaultProvider);
    }

    @GetMapping("/account-holders/{msisdn}")
    AccountHolderResponse validate(@PathVariable String msisdn, @RequestParam String operation)
            throws ProviderUnavailableException {
        Capability.Operation op = BalanceController.operation(operation);

        HolderStatus status = adapters.require(defaultProvider).validateHolder(op, msisdn);

        return new AccountHolderResponse(defaultProvider.toString(), op.name(), msisdn, status == HolderStatus.ACTIVE);
    }
}

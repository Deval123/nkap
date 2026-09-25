package dev.nkap.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.provider.Capability;
import dev.nkap.provider.HolderStatus;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.auth.ApiCredential;
import dev.nkap.server.provider.AdapterRegistry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

/**
 * The two live reads against a mocked default provider, for the combinations no single
 * integration context can hold: a default that declares every capability must still reach
 * the adapter for <strong>both</strong> operations — the guard against a check that refuses
 * too much — and one that declares a capability's absence must not reach it at all.
 * {@code FeatureNotOfferedApiIT} and {@code UnservedReadApiIT} prove the same at the operator.
 */
class DeclaredCapabilitiesTest {

    private static final ProviderId DEFAULT = ProviderId.of("mtn-sandbox");
    private static final ApiCredential ADMIN = new ApiCredential(UUID.randomUUID(), "ops", true);
    private static final Set<Capability> EVERYTHING = Set.of(
            Capability.Operation.COLLECT, Capability.Operation.DISBURSE,
            Capability.Feature.BALANCE, Capability.Feature.HOLDER_VALIDATION);

    private final AdapterRegistry adapters = mock(AdapterRegistry.class);
    private final ProviderAdapter adapter = mock(ProviderAdapter.class);

    private void declares(Set<Capability> capabilities) {
        when(adapters.require(DEFAULT)).thenReturn(adapter);
        when(adapters.settlementCurrency(DEFAULT)).thenReturn(Optional.of(Currency.EUR));
        when(adapter.capabilities()).thenReturn(capabilities);
        when(adapter.operations()).thenCallRealMethod();
    }

    @ParameterizedTest
    @EnumSource(Capability.Operation.class)
    @DisplayName("a default that declares everything still answers the balance for every operation, from the adapter")
    void a_default_declaring_everything_answers_the_balance(Capability.Operation operation) throws Exception {
        declares(EVERYTHING);
        when(adapter.balance(operation, Currency.EUR)).thenReturn(Money.of(500, Currency.EUR));

        BalanceResponse response = new BalanceController(adapters, DEFAULT.toString())
                .balance(ADMIN, operation.name(), "EUR");

        assertThat(response.amountMinorUnits()).isEqualTo(500);
        verify(adapter).balance(operation, Currency.EUR);
    }

    @ParameterizedTest
    @EnumSource(Capability.Operation.class)
    @DisplayName("a default that declares everything still answers holder validation for every operation, from the adapter")
    void a_default_declaring_everything_answers_holder_validation(Capability.Operation operation) throws Exception {
        declares(EVERYTHING);
        when(adapter.validateHolder(operation, "46733123453")).thenReturn(HolderStatus.ACTIVE);

        AccountHolderResponse response = new AccountHolderController(adapters, DEFAULT.toString())
                .validate("46733123453", operation.name());

        assertThat(response.active()).isTrue();
        verify(adapter).validateHolder(operation, "46733123453");
    }

    @Test
    @DisplayName("a default without BALANCE is 501 feature-not-offered, and the adapter's balance is never called")
    void a_missing_feature_is_501_without_calling_the_adapter() throws Exception {
        declares(Set.of(Capability.Operation.COLLECT, Capability.Operation.DISBURSE, Capability.Feature.HOLDER_VALIDATION));

        assertThatThrownBy(() -> new BalanceController(adapters, DEFAULT.toString()).balance(ADMIN, "COLLECT", "EUR"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
                    assertThat(e.type()).isEqualTo(ProblemTypes.FEATURE_NOT_OFFERED);
                });
        verify(adapter, never()).balance(any(), any());
    }

    @Test
    @DisplayName("a default without DISBURSE is 400 operation-not-served for a DISBURSE holder check, and the adapter is never called")
    void a_missing_operation_is_400_without_calling_the_adapter() throws Exception {
        declares(Set.of(Capability.Operation.COLLECT, Capability.Feature.BALANCE, Capability.Feature.HOLDER_VALIDATION));

        assertThatThrownBy(() -> new AccountHolderController(adapters, DEFAULT.toString()).validate("46733123453", "DISBURSE"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.type()).isEqualTo(ProblemTypes.OPERATION_NOT_SERVED);
                });
        verify(adapter, never()).validateHolder(any(), any());
    }
}

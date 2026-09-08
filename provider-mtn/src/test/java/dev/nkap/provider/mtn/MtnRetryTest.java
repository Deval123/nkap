package dev.nkap.provider.mtn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderUnavailableException;
import dev.nkap.provider.SubmitResult;
import dev.nkap.provider.mtn.StubMtn.StubResponse;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The 401-despite-a-live-token contract: one refresh, one retry, same reference. */
class MtnRetryTest {

    private MtnProfile profileAt(URI base) {
        return new MtnProfile(base, "sandbox", "sub-key", "api-user", "api-key", Currency.EUR, "sandbox");
    }

    private PaymentIntent intent() {
        return new PaymentIntent(Capability.COLLECT, Money.of(5000, Currency.EUR), "46733123453", "", "", Map.of());
    }

    @Test
    @DisplayName("a 401 despite a live token triggers exactly one refresh and one retry with the same reference")
    void a_401_is_retried_once_with_the_same_reference() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            AtomicInteger tokensIssued = new AtomicInteger();
            AtomicInteger submits = new AtomicInteger();
            List<String> submitReferences = new CopyOnWriteArrayList<>();
            List<String> submitAuthorizations = new CopyOnWriteArrayList<>();

            mtn.respondWith(request -> {
                if (request.path().equals("/collection/token/")) {
                    return new StubResponse(200, StubMtn.tokenJson("tok-" + tokensIssued.incrementAndGet(), 3600));
                }
                submitReferences.add(request.header("X-Reference-Id"));
                submitAuthorizations.add(request.header("Authorization"));
                return submits.incrementAndGet() == 1
                        ? new StubResponse(401, "{\"message\":\"token expired\"}")
                        : new StubResponse(202, "");
            });

            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));
            ReferenceId reference = ReferenceId.newReference();

            SubmitResult result = adapter.submit(intent(), reference);

            assertThat(result.state()).isEqualTo(PaymentState.SUBMITTED);
            assertThat(submits).hasValue(2);
            assertThat(tokensIssued).hasValue(2);
            assertThat(submitReferences).containsExactly(reference.toString(), reference.toString());
            assertThat(submitAuthorizations.get(0)).isNotEqualTo(submitAuthorizations.get(1));
        }
    }

    @Test
    @DisplayName("a second 401 after the refresh is ProviderUnavailableException, not a failure")
    void a_second_401_is_unavailable() throws Exception {
        try (StubMtn mtn = new StubMtn()) {
            mtn.respondWith(request -> request.path().equals("/collection/token/")
                    ? new StubResponse(200, StubMtn.tokenJson("tok", 3600))
                    : new StubResponse(401, "{\"message\":\"nope\"}"));

            MtnCollectionsAdapter adapter = new MtnCollectionsAdapter(profileAt(mtn.baseUrl()), Duration.ofSeconds(3));

            assertThatThrownBy(() -> adapter.submit(intent(), ReferenceId.newReference()))
                    .isInstanceOf(ProviderUnavailableException.class);
        }
    }
}

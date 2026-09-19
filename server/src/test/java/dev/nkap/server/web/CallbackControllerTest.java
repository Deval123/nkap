package dev.nkap.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.CallbackEvent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.ProviderStatus;
import dev.nkap.provider.UntrustedCallbackException;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentRepository;
import dev.nkap.server.payment.PaymentTransition;
import dev.nkap.server.payment.SettlementService;
import dev.nkap.server.provider.AdapterRegistry;
import dev.nkap.server.support.LogCapture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Issue #129's security constraint, at the one seam it actually governs: every request is
 * counted, but only a callback naming a reference this deployment issued is safe to log on
 * every request — the other two branches (unparseable, unknown reference) are exactly what a
 * scanner or a guess produces for free, so each is logged at most once, ever, and never with
 * the exception's own message or the raw body, either of which can echo attacker-controlled
 * text pulled from the request (a log-injection surface, not a debugging convenience).
 *
 * <p>A fresh {@link CallbackController} per test, deliberately: the "log at most once"
 * counters are instance state, and reusing one instance across test methods would make
 * whichever test happens to run first the only one that ever sees the line.
 */
class CallbackControllerTest {

    private static final ProviderId MTN = ProviderId.of("mtn");

    private final AdapterRegistry adapters = mock(AdapterRegistry.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final SettlementService settlement = mock(SettlementService.class);
    private final ProviderAdapter adapter = mock(ProviderAdapter.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private CallbackController controller;

    @BeforeEach
    void wireAdapter() {
        controller = new CallbackController(adapters, payments, settlement, registry);
        when(adapters.find(MTN)).thenReturn(Optional.of(adapter));
    }

    @Test
    @DisplayName("a callback naming a reference this deployment issued is logged and counted as confirmed")
    void a_known_reference_is_logged_and_counted() throws Exception {
        ReferenceId reference = ReferenceId.newReference();
        stubParsed(reference);
        when(payments.findByReference(reference)).thenReturn(Optional.of(mock(Payment.class)));

        try (LogCapture logs = new LogCapture(CallbackController.class)) {
            controller.receive("mtn", Map.of(), "{}");

            assertThat(logs.events())
                    .as("issue #129: a callback naming a real reference is safe to log every time")
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("mtn").contains(reference.toString()));
        }
        verify(settlement).confirm(MTN, reference, PaymentTransition.Cause.CALLBACK);
        assertThat(counter("nkap.callback.received")).isEqualTo(1.0);
        assertThat(counter("nkap.callback.confirmed")).isEqualTo(1.0);
        assertThat(counter("nkap.callback.rejected")).isZero();
    }

    @Test
    @DisplayName("an unparseable callback is logged at most once, however many arrive, and the exception's own message never appears")
    void unparseable_callbacks_are_logged_at_most_once() throws Exception {
        String marker = "MARKER-parse-failure-detail-should-never-be-logged";
        when(adapter.parseCallback(any())).thenThrow(new UntrustedCallbackException(
                "callback carries no usable reference: '" + marker + "'"));

        try (LogCapture logs = new LogCapture(CallbackController.class)) {
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> controller.receive("mtn", Map.of(), "{\"referenceId\":\"" + marker + "\"}"))
                        .isInstanceOf(ApiException.class);
            }

            assertThat(logs.events())
                    .as("issue #129: at most one line, ever, regardless of how many requests arrive")
                    .hasSizeLessThanOrEqualTo(1);
            assertThat(logs.events())
                    .as("never the exception's own message: it can echo attacker-controlled text from the body")
                    .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains(marker));
        }
        assertThat(counter("nkap.callback.received")).isEqualTo(5.0);
        assertThat(counterTagged("nkap.callback.rejected", "reason", "unparseable")).isEqualTo(5.0);
    }

    @Test
    @DisplayName("a callback naming a reference this gateway never issued is logged at most once, however many arrive")
    void unknown_reference_callbacks_are_logged_at_most_once() throws Exception {
        ReferenceId stranger = ReferenceId.newReference();
        stubParsed(stranger);
        when(payments.findByReference(stranger)).thenReturn(Optional.empty());

        try (LogCapture logs = new LogCapture(CallbackController.class)) {
            for (int i = 0; i < 5; i++) {
                controller.receive("mtn", Map.of(), "{}");
            }

            assertThat(logs.events())
                    .as("issue #129: this is exactly as cheap for an attacker to produce as an unparseable body")
                    .hasSizeLessThanOrEqualTo(1);
        }
        assertThat(counter("nkap.callback.received")).isEqualTo(5.0);
        assertThat(counterTagged("nkap.callback.rejected", "reason", "unknown_reference")).isEqualTo(5.0);
        assertThat(counter("nkap.callback.confirmed")).isZero();
    }

    @Test
    @DisplayName("a callback naming only the operator's own reference settles once the gateway resolves it to a payment it issued")
    void a_provider_reference_only_callback_resolves_and_confirms() throws Exception {
        String providerReference = "ws_CO_180920261803512708374149";
        ReferenceId resolved = ReferenceId.newReference();
        stubParsedUnattributed(providerReference);
        Payment resolvedPayment = mock(Payment.class);
        when(resolvedPayment.reference()).thenReturn(resolved);
        when(payments.findByProviderReference(MTN, providerReference)).thenReturn(Optional.of(resolvedPayment));
        when(payments.findByReference(resolved)).thenReturn(Optional.of(resolvedPayment));

        ResponseEntity<Void> response = controller.receive("mtn", Map.of(), "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(settlement).confirm(MTN, resolved, PaymentTransition.Cause.CALLBACK);
        assertThat(counter("nkap.callback.confirmed")).isEqualTo(1.0);
        assertThat(counter("nkap.callback.rejected")).isZero();
    }

    @Test
    @DisplayName("a callback naming a provider reference this gateway has never recorded is 202, discarded, and logged at "
            + "most once, however many arrive — reaching this branch takes only an invented value, not a correct guess")
    void unresolved_provider_reference_callbacks_are_logged_at_most_once() throws Exception {
        String providerReference = "ws_CO_a_lost_submit_response";
        stubParsedUnattributed(providerReference);
        when(payments.findByProviderReference(MTN, providerReference)).thenReturn(Optional.empty());

        try (LogCapture logs = new LogCapture(CallbackController.class)) {
            for (int i = 0; i < 5; i++) {
                ResponseEntity<Void> response = controller.receive("mtn", Map.of(), "{}");
                assertThat(response.getStatusCode().value()).isEqualTo(202);
            }

            assertThat(logs.events())
                    .as("this branch is reached by a miss, exactly as cheap for an attacker to produce as an "
                            + "unparseable body or a guessed Nkap reference — it gets the same bounded treatment")
                    .hasSizeLessThanOrEqualTo(1);
            assertThat(logs.events())
                    .as("the provider reference is attacker-controlled request-body content (issue #129) and must "
                            + "never appear in the log, even in the one line that is written")
                    .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains(providerReference));
        }
        assertThat(counter("nkap.callback.received")).isEqualTo(5.0);
        assertThat(counterTagged("nkap.callback.rejected", "reason", "unresolved_provider_reference")).isEqualTo(5.0);
        assertThat(counter("nkap.callback.confirmed")).isZero();
        verifyNoInteractions(settlement);
    }

    @Test
    @DisplayName("a callback naming a provider reference more than one payment shares is refused, exactly like an "
            + "unresolved one — never a guess at which payment it belongs to")
    void ambiguous_provider_reference_callbacks_are_refused() throws Exception {
        String providerReference = "shared-by-two-payments-somehow";
        stubParsedUnattributed(providerReference);
        // PaymentRepository's own contract: empty for zero matches AND for more than one --
        // the controller cannot tell them apart, and must not need to.
        when(payments.findByProviderReference(MTN, providerReference)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.receive("mtn", Map.of(), "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(counterTagged("nkap.callback.rejected", "reason", "unresolved_provider_reference")).isEqualTo(1.0);
        verifyNoInteractions(settlement);
    }

    @Test
    @DisplayName("a request naming no configured provider is still counted, tagged \"unknown\", never the raw attacker-supplied value")
    void unconfigured_provider_is_counted_as_unknown() {
        assertThatThrownBy(() -> controller.receive("not-configured", Map.of(), "{}"))
                .isInstanceOf(ApiException.class);
        // A case variant of the provider this test class actually has configured ("mtn").
        // ProviderId.of's shape check is case-sensitive today, so this also fails to
        // resolve -- but the point being pinned is the tag, not that failure: even a
        // string a human would read as naming the same operator must not surface as its
        // own tag value, raw or otherwise. If ProviderId.of ever normalises case, this
        // request would resolve, and the tag must still be "mtn" (the resolved id's own
        // canonical value), never "MTN" (what the request supplied).
        assertThatThrownBy(() -> controller.receive("MTN", Map.of(), "{}"))
                .isInstanceOf(ApiException.class);

        assertThat(counterTagged("nkap.callback.received", "provider", "unknown")).isEqualTo(2.0);
        assertThat(registry.find("nkap.callback.received").tags("provider", "not-configured").counter())
                .as("the raw path segment must never become a tag value -- unbounded cardinality")
                .isNull();
        assertThat(registry.find("nkap.callback.received").tags("provider", "MTN").counter())
                .as("nor a case variant of a configured provider's own spelling")
                .isNull();
    }

    private void stubParsed(ReferenceId reference) throws UntrustedCallbackException {
        when(adapter.parseCallback(any()))
                .thenReturn(new CallbackEvent(reference, ProviderStatus.unknown("PENDING", "")));
    }

    private void stubParsedUnattributed(String providerReference) throws UntrustedCallbackException {
        when(adapter.parseCallback(any()))
                .thenReturn(CallbackEvent.unattributed(providerReference, ProviderStatus.unknown("PENDING", "")));
    }

    private double counter(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double counterTagged(String name, String tagKey, String tagValue) {
        var counter = registry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0 : counter.count();
    }
}

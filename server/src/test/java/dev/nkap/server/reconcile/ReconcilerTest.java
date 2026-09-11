package dev.nkap.server.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.nkap.core.payment.PaymentState;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.payment.ConfirmationOutcome;
import dev.nkap.server.payment.SettlementService;
import dev.nkap.server.reconcile.ReconciliationStore.Claim;
import dev.nkap.server.support.LogCapture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reconciler's own log correlation: every line one pass emits carries that pass's id as
 * a structured field ({@code MDC}) — including, since {@link SettlementService#confirm} runs
 * on the calling thread, lines {@code SettlementService} logs while resolving one of the
 * pass's claims (issue #75). Everything else about a pass — claiming, escalating, retrying
 * — is {@link ReconcilerIT}'s job, driven against a real database; this file only exists for
 * the log field.
 */
class ReconcilerTest {

    private final ReconciliationStore store = mock(ReconciliationStore.class);
    private final SettlementService settlement = mock(SettlementService.class);
    private final ReconciliationPolicy policy = new ReconciliationPolicy(
            Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofHours(24));
    private final ReconcilerProperties properties = new ReconcilerProperties(
            Duration.ofSeconds(30), 100, Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofHours(24));
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Reconciler reconciler = new Reconciler(
            store, settlement, policy, properties, Clock.fixed(now, ZoneOffset.UTC), new SimpleMeterRegistry());

    @Test
    @DisplayName("every log line from one pass carries the same reconcilerPass field — including the settlement line the pass triggers")
    void every_line_in_one_pass_shares_a_pass_id() {
        ReferenceId reference = ReferenceId.newReference();
        Claim claim = new Claim(ProviderId.of("mtn"), reference, 5, now.minus(Duration.ofHours(25)));
        when(store.claimDue(anyInt(), any())).thenReturn(List.of(claim));
        when(store.markEscalated(reference, now)).thenReturn(true);
        when(settlement.confirm(any(), any(), any())).thenAnswer(invocation -> {
            // A real SettlementService.confirm would log through its own logger on this
            // same thread; the mock stands in for the "resolved but not conclusive"
            // outcome that lets escalation proceed, which is the case with the most log
            // lines in one pass to check.
            return new ConfirmationOutcome(ConfirmationOutcome.Kind.INCONCLUSIVE, PaymentState.UNKNOWN, "PENDING");
        });

        try (LogCapture logs = new LogCapture(Reconciler.class)) {
            reconciler.runOnce();

            assertThat(logs.events()).isNotEmpty();
            String passId = logs.events().get(0).getMDCPropertyMap().get("reconcilerPass");
            assertThat(passId).isNotBlank();
            assertThat(logs.events()).allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                    .as("every line this pass logs carries the same pass id")
                    .containsEntry("reconcilerPass", passId));
        }
    }

    @Test
    @DisplayName("two passes get two different pass ids")
    void two_passes_get_different_ids() {
        when(store.claimDue(anyInt(), any())).thenReturn(List.of());

        try (LogCapture logs = new LogCapture(Reconciler.class)) {
            reconciler.runOnce();
            reconciler.runOnce();

            assertThat(logs.events()).hasSizeGreaterThanOrEqualTo(2);
            String first = logs.events().get(0).getMDCPropertyMap().get("reconcilerPass");
            String second = logs.events().get(1).getMDCPropertyMap().get("reconcilerPass");
            assertThat(first).isNotEqualTo(second);
        }
    }
}

package dev.nkap.simulator.mpesa;

import dev.nkap.simulator.scenario.SubmitBehaviour;
import dev.nkap.simulator.scenario.SubmitOutcome;
import dev.nkap.simulator.scenario.Timeline;
import java.util.List;

/**
 * An M-Pesa scenario: what the simulator does at each interaction point of one STK Push
 * (ADR 0002), written in Safaricom's {@link MpesaResult} vocabulary.
 *
 * <p>Every field is optional in JSON:
 *
 * <ul>
 *   <li>no {@code onSubmit} means {@link SubmitOutcome#ACCEPT} with no delay;</li>
 *   <li>an empty {@code onQuery} means a single {@link MpesaResult#SUCCESS} — a
 *       <strong>modelled</strong> success, since none has been observed.</li>
 * </ul>
 */
public record MpesaScenario(
        String name,
        SubmitBehaviour onSubmit,
        List<MpesaQueryBehaviour> onQuery,
        List<MpesaCallbackSpec> callbacks) implements Timeline<MpesaQueryBehaviour, MpesaCallbackSpec> {

    /** The name a scenario carries when the document declaring it does not give it one. */
    public static final String DEFAULT_NAME = "happy-path";

    public MpesaScenario {
        name = (name == null || name.isBlank()) ? DEFAULT_NAME : name;
        onSubmit = onSubmit != null ? onSubmit : new SubmitBehaviour(null, null, null);
        onQuery = (onQuery == null || onQuery.isEmpty())
                ? List.of(new MpesaQueryBehaviour(null, null, false, null))
                : List.copyOf(onQuery);
        callbacks = callbacks != null ? List.copyOf(callbacks) : List.of();
    }

    /** Accepted on submission, {@link MpesaResult#SUCCESS} on the next query — modelled. */
    public static MpesaScenario happyPath() {
        return new MpesaScenario(DEFAULT_NAME, null, null, null);
    }
}

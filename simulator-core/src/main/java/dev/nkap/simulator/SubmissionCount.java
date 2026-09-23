package dev.nkap.simulator;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * How many submissions the operator has processed since its state was last forgotten — the
 * observation the conformance kit uses to tell whether an adapter's call reached the operator
 * at all, and whether it reached it more than once (issue #175).
 *
 * <p>A submission counts once it is past the face's own checks and authentication and reaches
 * {@link Submissions}, whatever happens to it there: accepted, refused as a repeat, or refused
 * by the scenario. A request the face turns away first — a {@code 401}, or a request too
 * malformed to submit — is not counted. Retrying after a {@code 401} precedes processing and
 * is allowed (ADR 0014, decision 4), so it must not look like a resend.
 */
@Component
public class SubmissionCount {

    private final AtomicInteger processed = new AtomicInteger();

    void increment() {
        processed.incrementAndGet();
    }

    /** Submissions processed since the last {@link #reset()}. */
    public int processed() {
        return processed.get();
    }

    void reset() {
        processed.set(0);
    }
}

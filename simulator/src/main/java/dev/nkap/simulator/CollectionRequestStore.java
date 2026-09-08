package dev.nkap.simulator;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The operator's record of which {@code X-Reference-Id} values it has already
 * seen. It is the idempotency gate and nothing more: what a payment then
 * <em>does</em> is the scenario engine's job, not this class's.
 *
 * <p>No persistence — the simulator forgets everything on restart, which is what
 * a test fixture wants.
 */
@Component
public class CollectionRequestStore {

    private final Set<String> references = ConcurrentHashMap.newKeySet();

    /**
     * Records a new reference. Returns {@code false} when it was already used —
     * the caller must then answer 409, exactly like the real API.
     */
    public boolean record(String referenceId) {
        return references.add(referenceId);
    }

    /** Forgets every reference, so each test starts from an empty operator. */
    void clear() {
        references.clear();
    }
}

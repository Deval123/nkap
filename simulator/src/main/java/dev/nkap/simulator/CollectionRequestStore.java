package dev.nkap.simulator;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory record of every {@code requesttopay} call, keyed by the
 * client-supplied {@code X-Reference-Id}. No persistence: the simulator forgets
 * everything on restart, which is what a test fixture wants.
 */
@Component
public class CollectionRequestStore {

    /** The lifecycle the real MTN Collections API exposes on a request. */
    public enum Status { PENDING, SUCCESSFUL, FAILED }

    private static final class Entry {
        final String amount;
        final String currency;
        Status status = Status.PENDING;

        Entry(String amount, String currency) {
            this.amount = amount;
            this.currency = currency;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Records a new request. Returns {@code false} when {@code referenceId} was
     * already used — the caller must then answer 409, exactly like the real API.
     */
    public boolean record(String referenceId, String amount, String currency) {
        return entries.putIfAbsent(referenceId, new Entry(amount, currency)) == null;
    }

    /**
     * Returns the current status, applying the default scenario: the first query
     * moves a {@code PENDING} request to {@code SUCCESSFUL}. Empty when the
     * reference is unknown.
     */
    public Optional<Status> poll(String referenceId) {
        Entry entry = entries.get(referenceId);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            if (entry.status == Status.PENDING) {
                entry.status = Status.SUCCESSFUL;
            }
            return Optional.of(entry.status);
        }
    }

    /** Test-only reset so each test starts from an empty operator. */
    void clear() {
        entries.clear();
    }
}

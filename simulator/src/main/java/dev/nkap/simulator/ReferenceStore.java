package dev.nkap.simulator;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The operator's record of which {@code X-Reference-Id} values it has already seen. It is
 * the idempotency gate and nothing more: what a payment then <em>does</em> is the scenario
 * engine's job, not this class's.
 *
 * <p>Partitioned per {@link Product} (issue #69): a reference recorded for one product does
 * not collide with, or answer for, the same reference under the other. Named
 * {@code CollectionRequestStore} until this landed — a name that served both products from
 * the start, but only became actively misleading once it was more than one product's.
 *
 * <p>No persistence — the simulator forgets everything on restart, which is what
 * a test fixture wants.
 */
@Component
public class ReferenceStore {

    private final Map<Product, Set<String>> references = Map.of(
            Product.COLLECTIONS, ConcurrentHashMap.newKeySet(),
            Product.DISBURSEMENTS, ConcurrentHashMap.newKeySet());

    /**
     * Records a new reference for {@code product}. Returns {@code false} when it was already
     * used <strong>for that product</strong> — the caller must then answer 409, exactly like
     * the real API. The same reference recorded for the other product is a different record:
     * it does not collide here, and it must not answer for this one either.
     */
    public boolean record(Product product, String referenceId) {
        return references.get(product).add(referenceId);
    }

    /** Forgets every reference, for every product, so each test starts from an empty operator. */
    void clear() {
        references.values().forEach(Set::clear);
    }
}

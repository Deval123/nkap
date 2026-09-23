package dev.nkap.simulator;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The operator's memory of which payments it holds, each recorded under the identity its
 * operator knows it by — {@link PaymentIdentity} declares where that identity comes from.
 * What a payment then <em>does</em> is the scenario engine's job, not this class's; whether
 * a submission repeating a recorded identity is refused is {@link Submissions}'.
 *
 * <p>Partitioned per {@link Product} (issue #69): a payment recorded for one product does
 * not collide with, or answer for, the same identity under the other. Named
 * {@code CollectionRequestStore} until that landed — a name that served both products from
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
     * Records a payment for {@code product}. Returns {@code false} when one with this
     * identity was already recorded <strong>for that product</strong>. The same identity
     * recorded for the other product is a different record: it does not collide here, and
     * it must not answer for this one either.
     */
    public boolean record(Product product, String paymentId) {
        return references.get(product).add(paymentId);
    }

    /** Forgets every payment, for every product, so each test starts from an empty operator. */
    void clear() {
        references.values().forEach(Set::clear);
    }
}

package dev.nkap.simulator.scenario;

import dev.nkap.simulator.Product;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the declared configuration — the ordered rule list and the token
 * lifetime — and the per-payment state, and answers the questions a
 * face needs: which scenario a submission resolves to, what the next
 * query on a payment should return, and how long a token lives.
 *
 * <p>The engine decides <em>what happens</em> and never how it is said: the
 * scenario, its rule and the answer to one query are each face's own types
 * ({@code T}, {@code R} and {@code Q}), because the words a scenario is
 * written in — the statuses a query answers with, above all — are the
 * operator's vocabulary, not the engine's.
 *
 * <p>Three invariants from ADR 0002 live here:
 *
 * <ol>
 *   <li><strong>Resolution happens once, at submission, and is frozen against
 *       the payment.</strong> Replacing the rules afterwards does not change
 *       how an in-flight payment behaves.</li>
 *   <li><strong>The last {@code onQuery} entry repeats.</strong> Query number
 *       <em>n</em> uses index {@code min(n - 1, size - 1)}, so a client that
 *       polls past the end of the scenario keeps getting a defined answer.</li>
 *   <li><strong>A payment belongs to one {@link Product} (issue #69).</strong> State is
 *       partitioned per product: a payment resolved for {@link Product#COLLECTIONS} has
 *       no state under {@link Product#DISBURSEMENTS}, and a query for the wrong product
 *       answers exactly as it does for a payment that was never submitted at all — the
 *       same {@link Optional#empty()}, through the same code path, not a variant of
 *       "unknown."</li>
 * </ol>
 *
 * <p>State is keyed by the payment's identity — whatever its operator knows the payment
 * by, which {@link dev.nkap.simulator.PaymentIdentity} declares — not by anything the
 * engine assumes about where that identity came from.
 *
 * <p>The token lifetime and the fallback callback URL are declared configuration,
 * not per-payment state: they are set with the rules, and the engine only
 * stores them — {@link #tokenTtl()} and {@link #callbackUrl()} hand them back to
 * whoever needs them. The token was once derived from "the most recent
 * submission's scenario"; that raced across payments and is corrected in
 * ADR 0002. Declared configuration is shared across products on purpose — a
 * deployment configures one operator session, not one per product — only the
 * per-payment state below is partitioned.
 *
 * @param <R> the face's rule: a matcher and the scenario it selects
 * @param <T> the face's scenario
 * @param <Q> the face's answer to one status query
 */
public class TimelineEngine<R extends Rule<T>, T extends Timeline<Q, ?>, Q> {

    private static final Logger log = LoggerFactory.getLogger(TimelineEngine.class);

    /** The resolved scenario for a payment, plus how it has been queried. */
    public record ReferenceState<T>(T scenario, Instant submittedAt, int queryCount) {}

    private final Supplier<T> defaultScenario;
    private volatile List<R> rules = List.of();
    private volatile TokenBehaviour token = new TokenBehaviour(null);
    private volatile String callbackUrl;
    private volatile AccountBehaviour account = AccountBehaviour.defaultBehaviour();
    private final Map<Product, Map<String, ReferenceState<T>>> states = Map.of(
            Product.COLLECTIONS, new ConcurrentHashMap<>(),
            Product.DISBURSEMENTS, new ConcurrentHashMap<>());

    /**
     * @param defaultScenario the scenario a submission that matches no rule plays — the
     *                        face's happy path, since what "accepted, then successful"
     *                        looks like is written in its own vocabulary
     */
    protected TimelineEngine(Supplier<T> defaultScenario) {
        this.defaultScenario = defaultScenario;
    }

    /**
     * Resolves the scenario for a new submission and freezes it against
     * {@code paymentId}, under {@code product} only. First matching rule wins; a matcher
     * field that is null is not compared; no match means the default scenario. Rule
     * matching itself does not consider product — {@code product} governs only which
     * payment space the resulting state is recorded in.
     */
    public T resolveForSubmission(Product product, String paymentId, String msisdn, String amount, String currency) {
        T scenario = match(paymentId, msisdn, amount, currency);
        states.get(product).put(paymentId, new ReferenceState<>(scenario, Instant.now(), 0));
        log.info("reference {} resolved to scenario '{}' for {}", paymentId, scenario.name(), product);
        return scenario;
    }

    private T match(String paymentId, String msisdn, String amount, String currency) {
        for (R rule : rules) {
            if (rule.match().matches(paymentId, msisdn, amount, currency)) {
                return rule.scenario();
            }
        }
        return defaultScenario.get();
    }

    /**
     * The behaviour for the next query on {@code paymentId} under {@code product}, or
     * empty if it was never submitted <strong>for that product</strong> — the caller then
     * answers its operator's "not found", whether nothing ever used this identity or the
     * other product did. Advances the query count; the last {@code onQuery} entry is
     * returned for every query past the end of the list.
     */
    public Optional<Q> nextQueryBehaviour(Product product, String paymentId) {
        ReferenceState<T> advanced = states.get(product).computeIfPresent(paymentId,
                (id, state) -> new ReferenceState<>(state.scenario(), state.submittedAt(), state.queryCount() + 1));
        if (advanced == null) {
            return Optional.empty();
        }
        List<Q> onQuery = advanced.scenario().onQuery();
        int index = Math.min(advanced.queryCount() - 1, onQuery.size() - 1);
        return Optional.of(onQuery.get(index));
    }

    /**
     * Which product(s) hold state for {@code paymentId} — empty, one, or, when a test has
     * deliberately submitted the same identity to both, both (issue #69). A payment-keyed
     * control-plane route that cannot say which product it is answering for should not guess
     * between two matches, the same reasoning {@code PaymentRepository.findByProviderReference}
     * follows on the gateway side.
     */
    public Set<Product> productsHolding(String paymentId) {
        Set<Product> holding = EnumSet.noneOf(Product.class);
        for (Product product : Product.values()) {
            if (states.get(product).containsKey(paymentId)) {
                holding.add(product);
            }
        }
        return holding;
    }

    /** The declared token lifetime. Consults nothing else. */
    public Duration tokenTtl() {
        return token.ttl();
    }

    /**
     * The fallback URL for callback delivery, used when a submission names no callback
     * URL of its own. {@code null} when none was declared.
     */
    public String callbackUrl() {
        return callbackUrl;
    }

    /**
     * What the balance and account-holder reads answer — declared configuration, like the
     * token, not per-payment state.
     */
    public AccountBehaviour account() {
        return account;
    }

    // --- control plane ---------------------------------------------------------

    /**
     * Replaces the token lifetime, the fallback callback URL and the rule list, leaving the
     * declared {@link AccountBehaviour} untouched. Kept for the many callers that predate
     * issue #72 and have no reason to know about account behaviour at all.
     */
    public void replaceConfiguration(TokenBehaviour token, String callbackUrl, List<R> rules) {
        replaceConfiguration(token, callbackUrl, rules, this.account);
    }

    /**
     * Replaces the whole declared configuration in one call: the token lifetime, the
     * fallback callback URL, the rule list, and the account balance / holder-validation
     * answers. In-flight payments keep the scenario they resolved to.
     */
    public void replaceConfiguration(TokenBehaviour token, String callbackUrl, List<R> rules,
                                     AccountBehaviour account) {
        this.token = token != null ? token : new TokenBehaviour(null);
        this.callbackUrl = (callbackUrl == null || callbackUrl.isBlank()) ? null : callbackUrl;
        this.rules = List.copyOf(rules);
        this.account = account != null ? account : AccountBehaviour.defaultBehaviour();
    }

    /** Back to the default scenario only, with a one-hour token, no callback URL, and the default account answers. */
    public void resetConfiguration() {
        this.token = new TokenBehaviour(null);
        this.callbackUrl = null;
        this.rules = List.of();
        this.account = AccountBehaviour.defaultBehaviour();
    }

    public List<R> rules() {
        return rules;
    }

    public TokenBehaviour token() {
        return token;
    }

    public Optional<ReferenceState<T>> state(Product product, String paymentId) {
        return Optional.ofNullable(states.get(product).get(paymentId));
    }

    /**
     * Forgets every payment, for every product, so a test suite's cases do not leak into
     * one another. Leaves the declared configuration — rules and token — untouched.
     */
    public void forgetAllState() {
        states.values().forEach(Map::clear);
    }
}

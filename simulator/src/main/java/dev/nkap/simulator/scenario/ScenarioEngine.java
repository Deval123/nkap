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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds the declared configuration — the ordered rule list and the token
 * lifetime — and the per-reference state, and answers the questions a
 * controller needs: which scenario a submission resolves to, what the next
 * query on a reference should return, and how long a token lives.
 *
 * <p>Three invariants from ADR 0002 live here:
 *
 * <ol>
 *   <li><strong>Resolution happens once, at submission, and is frozen against
 *       the reference.</strong> Replacing the rules afterwards does not change
 *       how an in-flight payment behaves.</li>
 *   <li><strong>The last {@code onQuery} entry repeats.</strong> Query number
 *       <em>n</em> uses index {@code min(n - 1, size - 1)}, so a client that
 *       polls past the end of the scenario keeps getting a defined answer.</li>
 *   <li><strong>A reference belongs to one {@link Product} (issue #69).</strong> State is
 *       partitioned per product: a reference resolved for {@link Product#COLLECTIONS} has
 *       no state under {@link Product#DISBURSEMENTS}, and a query for the wrong product
 *       answers exactly as it does for a reference that was never submitted at all — the
 *       same {@link Optional#empty()}, through the same code path, not a variant of
 *       "unknown."</li>
 * </ol>
 *
 * <p>The token lifetime and the fallback callback URL are declared configuration,
 * not per-reference state: they are set with the rules, and the engine only
 * stores them — {@link #tokenTtl()} and {@link #callbackUrl()} hand them back to
 * whoever needs them. The token was once derived from "the most recent
 * submission's scenario"; that raced across references and is corrected in
 * ADR 0002. Declared configuration is shared across products on purpose — a
 * deployment configures one operator session, not one per product — only the
 * per-reference state below is partitioned.
 */
@Component
public class ScenarioEngine {

    private static final Logger log = LoggerFactory.getLogger(ScenarioEngine.class);

    /** The resolved scenario for a reference, plus how it has been queried. */
    public record ReferenceState(Scenario scenario, Instant submittedAt, int queryCount) {}

    private volatile List<ScenarioRule> rules = List.of();
    private volatile TokenBehaviour token = new TokenBehaviour(null);
    private volatile String callbackUrl;
    private volatile AccountBehaviour account = AccountBehaviour.defaultBehaviour();
    private final Map<Product, Map<String, ReferenceState>> states = Map.of(
            Product.COLLECTIONS, new ConcurrentHashMap<>(),
            Product.DISBURSEMENTS, new ConcurrentHashMap<>());

    /**
     * Resolves the scenario for a new submission and freezes it against
     * {@code referenceId}, under {@code product} only. First matching rule wins; a matcher
     * field that is null is not compared; no match means {@link Scenario#happyPath()}. Rule
     * matching itself does not consider product — {@code product} governs only which
     * reference space the resulting state is recorded in.
     */
    public Scenario resolveForSubmission(Product product, String referenceId, String msisdn, String amount, String currency) {
        Scenario scenario = match(referenceId, msisdn, amount, currency);
        states.get(product).put(referenceId, new ReferenceState(scenario, Instant.now(), 0));
        log.info("reference {} resolved to scenario '{}' for {}", referenceId, scenario.name(), product);
        return scenario;
    }

    private Scenario match(String referenceId, String msisdn, String amount, String currency) {
        for (ScenarioRule rule : rules) {
            if (rule.match().matches(referenceId, msisdn, amount, currency)) {
                return rule.scenario();
            }
        }
        return Scenario.happyPath();
    }

    /**
     * The behaviour for the next query on {@code referenceId} under {@code product}, or
     * empty if it was never submitted <strong>for that product</strong> — the caller then
     * answers 404, whether nothing ever used this reference or the other product did.
     * Advances the query count; the last {@code onQuery} entry is returned for every query
     * past the end of the list.
     */
    public Optional<QueryBehaviour> nextQueryBehaviour(Product product, String referenceId) {
        ReferenceState advanced = states.get(product).computeIfPresent(referenceId,
                (ref, state) -> new ReferenceState(state.scenario(), state.submittedAt(), state.queryCount() + 1));
        if (advanced == null) {
            return Optional.empty();
        }
        List<QueryBehaviour> onQuery = advanced.scenario().onQuery();
        int index = Math.min(advanced.queryCount() - 1, onQuery.size() - 1);
        return Optional.of(onQuery.get(index));
    }

    /**
     * Which product(s) hold state for {@code referenceId} — empty, one, or, when a test has
     * deliberately submitted the same reference to both, both (issue #69). A reference-keyed
     * control-plane route that cannot say which product it is answering for should not guess
     * between two matches, the same reasoning {@code PaymentRepository.findByProviderReference}
     * follows on the gateway side.
     */
    public Set<Product> productsHolding(String referenceId) {
        Set<Product> holding = EnumSet.noneOf(Product.class);
        for (Product product : Product.values()) {
            if (states.get(product).containsKey(referenceId)) {
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
     * The fallback URL for callback delivery, used when a submit request carries
     * no {@code X-Callback-Url} header. {@code null} when none was declared.
     */
    public String callbackUrl() {
        return callbackUrl;
    }

    /**
     * What {@code GET .../account/balance} and {@code GET .../accountholder/.../active}
     * answer — declared configuration, like the token, not per-reference state.
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
    public void replaceConfiguration(TokenBehaviour token, String callbackUrl, List<ScenarioRule> rules) {
        replaceConfiguration(token, callbackUrl, rules, this.account);
    }

    /**
     * Replaces the whole declared configuration in one call: the token lifetime, the
     * fallback callback URL, the rule list, and the account balance / holder-validation
     * answers. In-flight payments keep the scenario they resolved to.
     */
    public void replaceConfiguration(TokenBehaviour token, String callbackUrl, List<ScenarioRule> rules,
                                     AccountBehaviour account) {
        this.token = token != null ? token : new TokenBehaviour(null);
        this.callbackUrl = (callbackUrl == null || callbackUrl.isBlank()) ? null : callbackUrl;
        this.rules = List.copyOf(rules);
        this.account = account != null ? account : AccountBehaviour.defaultBehaviour();
    }

    /** Back to the happy path only, with a one-hour token, no callback URL, and the default account answers. */
    public void resetConfiguration() {
        this.token = new TokenBehaviour(null);
        this.callbackUrl = null;
        this.rules = List.of();
        this.account = AccountBehaviour.defaultBehaviour();
    }

    public List<ScenarioRule> rules() {
        return rules;
    }

    public TokenBehaviour token() {
        return token;
    }

    public Optional<ReferenceState> state(Product product, String referenceId) {
        return Optional.ofNullable(states.get(product).get(referenceId));
    }

    /**
     * Forgets every reference, for every product, so a test suite's cases do not leak into
     * one another. Leaves the declared configuration — rules and token — untouched.
     */
    public void forgetAllState() {
        states.values().forEach(Map::clear);
    }
}

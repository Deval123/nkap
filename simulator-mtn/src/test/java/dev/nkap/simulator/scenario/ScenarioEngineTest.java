package dev.nkap.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.simulator.Product;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ADR 0002 invariants the engine has to hold, exercised without a web layer, including
 * the third one issue #69 added: a reference belongs to one {@link Product}. Every test
 * before that one submits under {@link Product#COLLECTIONS} throughout, since it is not
 * itself about which product -- the product-partitioning tests are grouped at the foot of
 * this file.
 */
class ScenarioEngineTest {

    private final ScenarioEngine engine = new ScenarioEngine();

    private static Scenario onQuery(MomoStatus... statuses) {
        List<QueryBehaviour> behaviours = Arrays.stream(statuses)
            .map(s -> new QueryBehaviour(null, s, null))
            .toList();
        return new Scenario("under-test", null, behaviours, null);
    }

    private static ScenarioRule ruleFor(String msisdn, Scenario scenario) {
        return new ScenarioRule(new RequestMatcher(null, msisdn, null, null), scenario);
    }

    @Test
    @DisplayName("a reference never submitted has no next query behaviour")
    void unknown_reference_has_no_behaviour() {
        assertThat(engine.nextQueryBehaviour(Product.COLLECTIONS, "never-seen")).isEmpty();
    }

    @Test
    @DisplayName("with no rules a submission resolves to the happy path")
    void no_rules_means_happy_path() {
        Scenario resolved = engine.resolveForSubmission(Product.COLLECTIONS, "ref-1", "237600000000", "5000", "XAF");

        assertThat(resolved).isEqualTo(Scenario.happyPath());
        assertThat(engine.nextQueryBehaviour(Product.COLLECTIONS, "ref-1")).get()
            .satisfies(q -> assertThat(q.status()).isEqualTo(MomoStatus.SUCCESSFUL));
    }

    @Test
    @DisplayName("the last onQuery entry repeats for every further query")
    void last_on_query_entry_repeats() {
        engine.replaceConfiguration(null, null,
            List.of(ruleFor("237600000001", onQuery(MomoStatus.SUCCESSFUL, MomoStatus.FAILED))));
        engine.resolveForSubmission(Product.COLLECTIONS, "ref-flap", "237600000001", "5000", "XAF");

        assertThat(engine.nextQueryBehaviour(Product.COLLECTIONS, "ref-flap")).get()
            .satisfies(q -> assertThat(q.status()).isEqualTo(MomoStatus.SUCCESSFUL));
        for (int i = 0; i < 9; i++) {
            assertThat(engine.nextQueryBehaviour(Product.COLLECTIONS, "ref-flap")).get()
                .satisfies(q -> assertThat(q.status()).isEqualTo(MomoStatus.FAILED));
        }
    }

    @Test
    @DisplayName("resolution is frozen at submission: replacing the rules does not move an in-flight payment")
    void resolution_is_frozen_at_submission() {
        engine.replaceConfiguration(null, null, List.of(ruleFor("237600000002", onQuery(MomoStatus.FAILED))));
        engine.resolveForSubmission(Product.COLLECTIONS, "ref-frozen", "237600000002", "5000", "XAF");

        engine.replaceConfiguration(null, null, List.of(ruleFor("237600000002", onQuery(MomoStatus.SUCCESSFUL))));

        assertThat(engine.nextQueryBehaviour(Product.COLLECTIONS, "ref-frozen")).get()
            .satisfies(q -> assertThat(q.status()).isEqualTo(MomoStatus.FAILED));
    }

    @Test
    @DisplayName("the first matching rule wins")
    void first_matching_rule_wins() {
        engine.replaceConfiguration(null, null, List.of(
            ruleFor("237600000003", onQuery(MomoStatus.FAILED)),
            ruleFor("237600000003", onQuery(MomoStatus.SUCCESSFUL))));
        engine.resolveForSubmission(Product.COLLECTIONS, "ref-order", "237600000003", "5000", "XAF");

        assertThat(engine.nextQueryBehaviour(Product.COLLECTIONS, "ref-order")).get()
            .satisfies(q -> assertThat(q.status()).isEqualTo(MomoStatus.FAILED));
    }

    @Test
    @DisplayName("forgetting state makes a known reference unknown again")
    void forget_state_clears_references() {
        engine.resolveForSubmission(Product.COLLECTIONS, "ref-gone", "237600000000", "5000", "XAF");
        assertThat(engine.state(Product.COLLECTIONS, "ref-gone")).isPresent();

        engine.forgetAllState();

        assertThat(engine.state(Product.COLLECTIONS, "ref-gone")).isEmpty();
        assertThat(engine.nextQueryBehaviour(Product.COLLECTIONS, "ref-gone")).isEmpty();
    }

    @Test
    @DisplayName("the token lifetime is the one declared with the rule set")
    void token_lifetime_is_declared_with_the_rules() {
        assertThat(engine.tokenTtl()).isEqualTo(Duration.ofHours(1));

        engine.replaceConfiguration(new TokenBehaviour(Duration.ofSeconds(2)), null, List.of());

        assertThat(engine.tokenTtl()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("two submissions resolving different scenarios do not change the token lifetime")
    void submissions_do_not_touch_the_token_lifetime() {
        engine.replaceConfiguration(new TokenBehaviour(Duration.ofSeconds(2)), null, List.of(
            ruleFor("237600000001", onQuery(MomoStatus.FAILED)),
            ruleFor("237600000002", onQuery(MomoStatus.SUCCESSFUL))));

        engine.resolveForSubmission(Product.COLLECTIONS, "ref-a", "237600000001", "5000", "XAF");
        engine.resolveForSubmission(Product.COLLECTIONS, "ref-b", "237600000002", "9000", "XAF");

        assertThat(engine.tokenTtl()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("resetting the configuration returns the token lifetime to one hour")
    void resetting_configuration_restores_the_default_token() {
        engine.replaceConfiguration(new TokenBehaviour(Duration.ofSeconds(2)), null, List.of());

        engine.resetConfiguration();

        assertThat(engine.tokenTtl()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("forgetting state leaves a declared token lifetime alone")
    void forgetting_state_keeps_the_token() {
        engine.replaceConfiguration(new TokenBehaviour(Duration.ofSeconds(2)), null, List.of());

        engine.forgetAllState();

        assertThat(engine.tokenTtl()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("the fallback callback URL is declared config: set with the rules, cleared on reset, kept across state resets")
    void callback_url_is_declared_configuration() {
        assertThat(engine.callbackUrl()).isNull();

        engine.replaceConfiguration(null, "http://localhost:9999/hook", List.of());
        assertThat(engine.callbackUrl()).isEqualTo("http://localhost:9999/hook");

        engine.forgetAllState();
        assertThat(engine.callbackUrl()).isEqualTo("http://localhost:9999/hook");

        engine.resetConfiguration();
        assertThat(engine.callbackUrl()).isNull();
    }

    @Test
    @DisplayName("a blank callback URL is stored as none")
    void blank_callback_url_is_none() {
        engine.replaceConfiguration(null, "   ", List.of());

        assertThat(engine.callbackUrl()).isNull();
    }

    // --- issue #69: a reference belongs to one product ----------------------------------

    @Test
    @DisplayName("a reference resolved for COLLECTIONS has no query behaviour under DISBURSEMENTS -- the same answer as never submitted")
    void a_reference_is_unknown_under_the_other_product() {
        engine.resolveForSubmission(Product.COLLECTIONS, "ref-collections-only", "237600000000", "5000", "XAF");

        assertThat(engine.nextQueryBehaviour(Product.DISBURSEMENTS, "ref-collections-only")).isEmpty();
        assertThat(engine.state(Product.DISBURSEMENTS, "ref-collections-only")).isEmpty();
        // And it is exactly the same Optional.empty() a reference no product has ever seen gets.
        assertThat(engine.nextQueryBehaviour(Product.DISBURSEMENTS, "ref-collections-only"))
            .isEqualTo(engine.nextQueryBehaviour(Product.DISBURSEMENTS, "never-seen-by-either"));
    }

    @Test
    @DisplayName("the mirror: a reference resolved for DISBURSEMENTS has no query behaviour under COLLECTIONS")
    void the_mirror_for_disbursements() {
        engine.resolveForSubmission(Product.DISBURSEMENTS, "ref-disbursements-only", "237600000000", "5000", "XAF");

        assertThat(engine.nextQueryBehaviour(Product.COLLECTIONS, "ref-disbursements-only")).isEmpty();
        assertThat(engine.state(Product.COLLECTIONS, "ref-disbursements-only")).isEmpty();
    }

    @Test
    @DisplayName("both products still answer their own references exactly as before")
    void both_products_keep_answering_their_own_references() {
        engine.resolveForSubmission(Product.COLLECTIONS, "ref-c", "237600000000", "5000", "XAF");
        engine.resolveForSubmission(Product.DISBURSEMENTS, "ref-d", "237600000000", "5000", "XAF");

        assertThat(engine.nextQueryBehaviour(Product.COLLECTIONS, "ref-c")).isPresent();
        assertThat(engine.nextQueryBehaviour(Product.DISBURSEMENTS, "ref-d")).isPresent();
    }

    @Test
    @DisplayName("productsHolding: empty for neither, one for exactly one, both when a test deliberately reused a reference across products")
    void products_holding_reports_zero_one_or_both() {
        assertThat(engine.productsHolding("ref-none")).isEmpty();

        engine.resolveForSubmission(Product.COLLECTIONS, "ref-one", "237600000000", "5000", "XAF");
        assertThat(engine.productsHolding("ref-one")).containsExactly(Product.COLLECTIONS);

        engine.resolveForSubmission(Product.COLLECTIONS, "ref-both", "237600000000", "5000", "XAF");
        engine.resolveForSubmission(Product.DISBURSEMENTS, "ref-both", "237600000000", "5000", "XAF");
        assertThat(engine.productsHolding("ref-both")).containsExactlyInAnyOrder(Product.COLLECTIONS, Product.DISBURSEMENTS);
    }

    @Test
    @DisplayName("forgetting state clears both products' references")
    void forget_state_clears_both_products() {
        engine.resolveForSubmission(Product.COLLECTIONS, "ref-c", "237600000000", "5000", "XAF");
        engine.resolveForSubmission(Product.DISBURSEMENTS, "ref-d", "237600000000", "5000", "XAF");

        engine.forgetAllState();

        assertThat(engine.state(Product.COLLECTIONS, "ref-c")).isEmpty();
        assertThat(engine.state(Product.DISBURSEMENTS, "ref-d")).isEmpty();
    }
}

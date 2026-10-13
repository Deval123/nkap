package dev.nkap.server.adjustment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.InMemoryLedger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.provider.Capability;
import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderId;
import dev.nkap.server.auth.ApiCredential;
import dev.nkap.server.payment.InMemoryPaymentRepository;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.web.ApiException;
import dev.nkap.server.web.ProblemTypes;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * One test per check of ADR 0017, each named for the thing it forbids. The in-memory ledger
 * and store stand for the rules; they are not evidence that PostgreSQL enforces them, and
 * nothing here claims a duplicate approval is refused by the database. That is
 * {@code AdjustmentPersistenceIT}.
 */
class AdjustmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T09:00:00Z");

    private final InMemoryAdjustmentStore store = new InMemoryAdjustmentStore();
    private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ApiCredential proposer = new ApiCredential(UUID.randomUUID(), "acme", false);
    private final ApiCredential approver = new ApiCredential(UUID.randomUUID(), "acme", false);

    private AdjustmentService service;
    private Payment payment;

    @BeforeEach
    void setUp() {
        service = serviceWith(new AdjustmentPolicy(true, 100_000));
        payment = Payment.create(ReferenceId.newReference(), ProviderId.of("mtn-cm"), "acme",
                new PaymentIntent(Capability.Operation.COLLECT, Money.of(5_000, Currency.XAF), "237670000000",
                        "", "", Map.of()));
        payments.save(payment);
        store.paymentBelongsTo(payment.reference().value(), "acme");
    }

    private AdjustmentService serviceWith(AdjustmentPolicy policy) {
        return new AdjustmentService(policy, store, payments, ledger, new NoTransactions(), clock);
    }

    private static AdjustmentPosting posting(String account, long amount) {
        return new AdjustmentPosting(account, amount, "XAF");
    }

    private NewAdjustment proposal(List<AdjustmentPosting> postings) {
        return new NewAdjustment(payment.reference().toString(), "unrecorded_fee", postings,
                Map.of("statement-line", "mtn-cm line 42: fee 150 XAF"), List.of("statement-line"),
                "The operator charged 150 XAF that the ledger never recorded.");
    }

    private NewAdjustment validProposal() {
        return proposal(List.of(
                posting("fees:platform:XAF", 150),
                posting("provider:mtn-cm:float:XAF", -150)));
    }

    private static ApiException refused(Runnable action) {
        return catchThrowableOfType(ApiException.class, action::run);
    }

    private void assertRefusedWith(ApiException refusal, URI type, HttpStatus status) {
        assertThat(refusal).as("the call should have been refused").isNotNull();
        assertThat(refusal.type()).isEqualTo(type);
        assertThat(refusal.status()).isEqualTo(status);
    }

    // ---- check 1: balance ----

    @Test
    @DisplayName("an unbalanced proposal is refused with a readable error and stored nowhere")
    void an_unbalanced_proposal_is_refused_and_not_stored() {
        ApiException refusal = refused(() -> service.propose(proposer, proposal(List.of(
                posting("fees:platform:XAF", 150), posting("provider:mtn-cm:float:XAF", -100)))));

        assertRefusedWith(refusal, ProblemTypes.ADJUSTMENT_UNBALANCED, HttpStatus.BAD_REQUEST);
        assertThat(refusal.getMessage()).contains("sum to 50");
        assertThat(store.list(java.util.Optional.empty())).isEmpty();
    }

    // ---- check 2: allowed accounts ----

    @Test
    @DisplayName("a proposal naming a merchant's payable account is refused, however the rest is shaped")
    void a_merchant_payable_account_is_refused() {
        ApiException refusal = refused(() -> service.propose(proposer, proposal(List.of(
                posting("merchant:acme:payable:XAF", 150), posting("provider:mtn-cm:float:XAF", -150)))));

        assertRefusedWith(refusal, ProblemTypes.ADJUSTMENT_ACCOUNT_NOT_ALLOWED, HttpStatus.BAD_REQUEST);
        assertThat(store.list(java.util.Optional.empty())).isEmpty();
    }

    @Test
    @DisplayName("a proposal naming an account that is not suspense, fees or provider float is refused")
    void an_account_outside_suspense_fees_and_float_is_refused() {
        for (String account : List.of("equity:owner:XAF", "provider:mtn-cm:fx:XAF", "suspense:mtn-cm:XAF:extra",
                "fees:platform:XOF", "merchant:acme:payable:XAF")) {
            ApiException refusal = refused(() -> service.propose(proposer, proposal(List.of(
                    posting(account, 150), posting("provider:mtn-cm:float:XAF", -150)))));

            assertThat(refusal).as(account).isNotNull();
            assertThat(refusal.type()).as(account).isIn(
                    ProblemTypes.ADJUSTMENT_ACCOUNT_NOT_ALLOWED, ProblemTypes.INVALID_REQUEST);
        }
        assertThat(store.list(java.util.Optional.empty())).isEmpty();
    }

    @Test
    @DisplayName("suspense, fees and provider float, each in the posting's currency, are accepted")
    void the_three_allowed_kinds_are_accepted() {
        Adjustment stored = service.propose(proposer, proposal(List.of(
                posting("suspense:mtn-cm:XAF", 300),
                posting("fees:platform:XAF", -100),
                posting("provider:mtn-cm:float:XAF", -200))));

        assertThat(stored.decision()).isEmpty();
        assertThat(stored.proposedBy()).isEqualTo(proposer.keyId());
    }

    // ---- check 3: ceiling ----

    @Test
    @DisplayName("a proposal moving more than the ceiling is refused, and one at the ceiling is not")
    void a_proposal_over_the_ceiling_is_refused() {
        AdjustmentService tight = serviceWith(new AdjustmentPolicy(true, 150));

        assertThat(tight.propose(proposer, validProposal()).id()).isNotNull();
        ApiException refusal = refused(() -> tight.propose(proposer, proposal(List.of(
                posting("fees:platform:XAF", 151), posting("provider:mtn-cm:float:XAF", -151)))));

        assertRefusedWith(refusal, ProblemTypes.ADJUSTMENT_OVER_CEILING, HttpStatus.BAD_REQUEST);
    }

    // ---- check 4: no self-approval ----

    @Test
    @DisplayName("the key that proposed an adjustment cannot approve it, and nothing reaches the ledger")
    void the_proposing_key_cannot_approve_its_own_proposal() {
        Adjustment stored = service.propose(proposer, validProposal());

        ApiException refusal = refused(() -> service.approve(proposer, stored.id(), "looks right to me"));

        assertRefusedWith(refusal, ProblemTypes.ADJUSTMENT_SELF_APPROVAL, HttpStatus.FORBIDDEN);
        assertThat(ledger.entries()).isEmpty();
        assertThat(service.get(proposer, stored.id()).decision())
                .as("a refused self-approval is not a decision: it must not use up the proposal's only one")
                .isEmpty();
    }

    @Test
    @DisplayName("the key that proposed an adjustment cannot reject it either")
    void the_proposing_key_cannot_reject_its_own_proposal() {
        Adjustment stored = service.propose(proposer, validProposal());

        ApiException refusal = refused(() -> service.reject(proposer, stored.id(), "changed my mind"));

        assertRefusedWith(refusal, ProblemTypes.ADJUSTMENT_SELF_APPROVAL, HttpStatus.FORBIDDEN);
        assertThat(service.get(proposer, stored.id()).decision()).isEmpty();
    }

    @Test
    @DisplayName("a different key may approve the same proposal after the proposer was refused")
    void another_key_can_approve_after_a_refused_self_approval() {
        Adjustment stored = service.propose(proposer, validProposal());
        refused(() -> service.approve(proposer, stored.id(), ""));

        Adjustment decided = service.approve(approver, stored.id(), "checked against the statement");

        assertThat(decided.decision()).get().satisfies(d -> {
            assertThat(d.approved()).isTrue();
            assertThat(d.decidedBy()).isEqualTo(approver.keyId());
        });
    }

    // ---- check 5: evidence ----

    @Test
    @DisplayName("a proposal citing evidence it does not carry is refused rather than stored")
    void a_citation_with_nothing_behind_it_is_refused() {
        NewAdjustment citingAbsent = new NewAdjustment(payment.reference().toString(), "unrecorded_fee",
                validProposal().postings(), Map.of("statement-line", "line 42"),
                List.of("statement-line", "support-ticket"), "The operator charged a fee.");

        ApiException refusal = refused(() -> service.propose(proposer, citingAbsent));

        assertRefusedWith(refusal, ProblemTypes.ADJUSTMENT_EVIDENCE_MISSING, HttpStatus.BAD_REQUEST);
        assertThat(refusal.getMessage()).contains("support-ticket");
        assertThat(store.list(java.util.Optional.empty())).isEmpty();
    }

    @Test
    @DisplayName("a proposal citing a blank value, or citing nothing at all, is refused as having no evidence")
    void a_blank_or_empty_citation_is_refused() {
        NewAdjustment blank = new NewAdjustment(payment.reference().toString(), "unrecorded_fee",
                validProposal().postings(), Map.of("statement-line", "  "), List.of("statement-line"), "A fee.");
        NewAdjustment nothingCited = new NewAdjustment(payment.reference().toString(), "unrecorded_fee",
                validProposal().postings(), Map.of("statement-line", "line 42"), List.of(), "A fee.");

        assertRefusedWith(refused(() -> service.propose(proposer, blank)),
                ProblemTypes.ADJUSTMENT_EVIDENCE_MISSING, HttpStatus.BAD_REQUEST);
        assertRefusedWith(refused(() -> service.propose(proposer, nothingCited)),
                ProblemTypes.ADJUSTMENT_EVIDENCE_MISSING, HttpStatus.BAD_REQUEST);
    }

    // ---- check 6: currency ----

    @Test
    @DisplayName("a proposal in a currency other than the payment's is refused")
    void a_currency_other_than_the_payments_is_refused() {
        ApiException refusal = refused(() -> service.propose(proposer, proposal(List.of(
                new AdjustmentPosting("fees:platform:XOF", 150, "XOF"),
                new AdjustmentPosting("provider:mtn-cm:float:XOF", -150, "XOF")))));

        assertRefusedWith(refusal, ProblemTypes.ADJUSTMENT_CURRENCY_MISMATCH, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a proposal mixing two currencies is refused, even when one of them is the payment's")
    void two_currencies_in_one_proposal_are_refused() {
        ApiException refusal = refused(() -> service.propose(proposer, proposal(List.of(
                posting("fees:platform:XAF", 150),
                new AdjustmentPosting("provider:mtn-cm:float:XOF", -150, "XOF")))));

        assertRefusedWith(refusal, ProblemTypes.ADJUSTMENT_CURRENCY_MISMATCH, HttpStatus.BAD_REQUEST);
    }

    // ---- check 7: off by default ----

    @Test
    @DisplayName("while the feature is off, every operation answers 501 adjustment-not-enabled, not 404")
    void a_disabled_feature_refuses_every_operation_with_its_own_problem_type() {
        AdjustmentService off = serviceWith(new AdjustmentPolicy(false, 100_000));
        UUID anyId = UUID.randomUUID();

        List<Runnable> operations = List.of(
                () -> off.propose(proposer, validProposal()),
                () -> off.approve(approver, anyId, ""),
                () -> off.reject(approver, anyId, ""),
                () -> off.list(proposer),
                () -> off.get(proposer, anyId));

        for (Runnable operation : operations) {
            assertRefusedWith(refused(operation), ProblemTypes.ADJUSTMENT_NOT_ENABLED, HttpStatus.NOT_IMPLEMENTED);
        }
        assertThat(ProblemTypes.ADJUSTMENT_NOT_ENABLED).isNotEqualTo(ProblemTypes.FEATURE_NOT_OFFERED);
    }

    // ---- the path through approval ----

    @Test
    @DisplayName("an approval writes the entry the server derived: its id, its time and its reference, not the caller's")
    void an_approval_writes_a_server_derived_entry() {
        Adjustment stored = service.propose(proposer, validProposal());

        service.approve(approver, stored.id(), "matches the statement");

        List<LedgerEntry> entries = ledger.entries();
        assertThat(entries).hasSize(1);
        LedgerEntry entry = entries.get(0);
        assertThat(entry.id()).isEqualTo("adjustment:" + stored.id());
        assertThat(entry.occurredAt()).isEqualTo(NOW);
        assertThat(entry.reference()).isEqualTo(payment.reference().toString());
        assertThat(entry.currency()).isEqualTo(Currency.XAF);
        assertThat(ledger.balance(AccountId.fees("platform", Currency.XAF), Currency.XAF))
                .isEqualTo(Money.of(150, Currency.XAF));
    }

    @Test
    @DisplayName("a rejection records who decided and why, and writes nothing to the ledger")
    void a_rejection_writes_nothing_to_the_ledger() {
        Adjustment stored = service.propose(proposer, validProposal());

        Adjustment decided = service.reject(approver, stored.id(), "the statement shows no such fee");

        assertThat(ledger.entries()).isEmpty();
        assertThat(decided.decision()).get().satisfies(d -> {
            assertThat(d.approved()).isFalse();
            assertThat(d.decidedBy()).isEqualTo(approver.keyId());
            assertThat(d.reason()).isEqualTo("the statement shows no such fee");
        });
    }

    @Test
    @DisplayName("a proposal that stops being allowed by the time it is approved is recorded as refused, with its reason")
    void a_refusal_at_approval_is_recorded_not_dropped() {
        Adjustment stored = service.propose(proposer, validProposal());
        AdjustmentService lowered = serviceWith(new AdjustmentPolicy(true, 100));

        ApiException refusal = refused(() -> lowered.approve(approver, stored.id(), ""));

        assertRefusedWith(refusal, ProblemTypes.ADJUSTMENT_OVER_CEILING, HttpStatus.BAD_REQUEST);
        assertThat(ledger.entries()).isEmpty();
        assertThat(store.find(stored.id()).orElseThrow().decision()).get().satisfies(d -> {
            assertThat(d.approved()).isFalse();
            assertThat(d.reason()).contains("adjustment-over-ceiling");
        });
    }

    @Test
    @DisplayName("an unknown hypothesis is refused as an invalid request")
    void an_unknown_hypothesis_is_refused() {
        NewAdjustment unknown = new NewAdjustment(payment.reference().toString(), "gut_feeling",
                validProposal().postings(), Map.of("k", "v"), List.of("k"), "Because.");

        assertRefusedWith(refused(() -> service.propose(proposer, unknown)),
                ProblemTypes.INVALID_REQUEST, HttpStatus.BAD_REQUEST);
    }

    // ---- scoping ----

    @Test
    @DisplayName("a key of another merchant cannot see, list or decide a proposal; an admin key sees every merchant's")
    void reads_are_scoped_to_the_merchant_and_an_admin_widens_them() {
        Adjustment stored = service.propose(proposer, validProposal());
        ApiCredential stranger = new ApiCredential(UUID.randomUUID(), "globex", false);
        ApiCredential admin = new ApiCredential(UUID.randomUUID(), "ops", true);

        assertRefusedWith(refused(() -> service.get(stranger, stored.id())),
                ProblemTypes.ADJUSTMENT_NOT_FOUND, HttpStatus.NOT_FOUND);
        assertRefusedWith(refused(() -> service.approve(stranger, stored.id(), "")),
                ProblemTypes.ADJUSTMENT_NOT_FOUND, HttpStatus.NOT_FOUND);
        assertThat(service.list(stranger)).isEmpty();
        assertThat(service.list(admin)).extracting(Adjustment::id).containsExactly(stored.id());
        assertThat(service.get(admin, stored.id()).id()).isEqualTo(stored.id());
    }

    @Test
    @DisplayName("a key cannot propose an adjustment about another merchant's payment")
    void a_foreign_payment_is_not_found() {
        ApiCredential stranger = new ApiCredential(UUID.randomUUID(), "globex", false);

        assertRefusedWith(refused(() -> service.propose(stranger, validProposal())),
                ProblemTypes.PAYMENT_NOT_FOUND, HttpStatus.NOT_FOUND);
    }

    /** No transactions: the in-memory store and ledger have nothing to roll back. */
    private static final class NoTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}

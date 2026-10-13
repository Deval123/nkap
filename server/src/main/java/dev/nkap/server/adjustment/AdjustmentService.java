package dev.nkap.server.adjustment;

import dev.nkap.core.ledger.AccountId;
import dev.nkap.core.ledger.DuplicateLedgerEntryException;
import dev.nkap.core.ledger.Ledger;
import dev.nkap.core.ledger.LedgerEntry;
import dev.nkap.core.ledger.Posting;
import dev.nkap.core.money.Currency;
import dev.nkap.core.money.Money;
import dev.nkap.core.payment.ReferenceId;
import dev.nkap.server.auth.ApiCredential;
import dev.nkap.server.payment.Payment;
import dev.nkap.server.payment.PaymentRepository;
import dev.nkap.server.web.ApiException;
import dev.nkap.server.web.ProblemTypes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A human finishes what the reconciler cannot (ADR 0017): one key proposes, another decides,
 * and the server writes the ledger entry. A proposal is data until a decision approves it, and
 * approval is the only thing in this class that touches the ledger.
 *
 * <p>The checks, and where each is enforced:
 * <ol>
 *   <li><b>Balance.</b> Checked here for a readable error, and by the deferred constraint
 *       trigger of V1 whatever this class does. That trigger is the guarantee.</li>
 *   <li><b>Allowed accounts.</b> Suspense, fees and provider float, in the posting's currency,
 *       and of the payment's own provider: the name in the account is never the caller's to
 *       choose, so a proposal cannot mint an account this deployment never configured. Never a
 *       merchant's payable.</li>
 *   <li><b>Ceiling.</b> The sum of the debits, against {@link AdjustmentPolicy#maxAmountMinor()}.</li>
 *   <li><b>No self-decision.</b> The deciding {@code keyId} differs from the proposing one.</li>
 *   <li><b>Evidence.</b> Every cited entry is present in the evidence the proposal carries.</li>
 *   <li><b>Currency.</b> One, and the payment's.</li>
 *   <li><b>Off by default.</b> Every public method refuses first, with its own problem type.</li>
 * </ol>
 *
 * <p>Checks 1, 2, 3, 5 and 6 are made at proposal time, and a proposal that fails one is
 * refused with its problem type and <em>not stored</em>. Checks 1, 2, 3 and 6 are made again
 * at approval, because the ceiling and the payment's data are read then: a refusal at that
 * point is recorded as a rejection with its reason, so nothing is silently dropped. A
 * self-decision is refused and not recorded, since recording it would use up the proposal's
 * only decision and let its proposer lock every other key out.
 *
 * <p>The entry id is {@code "adjustment:" + proposalId}, the readable shape
 * {@code StatementReconciliation} gives its own ({@code fee:<provider>:<txid>}). A second
 * approval is refused by the {@code ledger_entry} primary key, which {@code PostgresLedger}
 * raises as {@link DuplicateLedgerEntryException}, rather than by a read made first. The entry
 * is appended before the decision is recorded for that reason; the decision's unique
 * constraint then stops an approval of a proposal already rejected.
 */
public class AdjustmentService {

    /** The reference prefix of the entry an approval writes; the proposal's id follows it. */
    static final String ENTRY_ID_PREFIX = "adjustment:";

    private final AdjustmentPolicy policy;
    private final AdjustmentStore store;
    private final PaymentRepository payments;
    private final Ledger ledger;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AdjustmentService(AdjustmentPolicy policy, AdjustmentStore store, PaymentRepository payments,
                             Ledger ledger, PlatformTransactionManager txManager, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.store = Objects.requireNonNull(store, "store");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.tx = new TransactionTemplate(Objects.requireNonNull(txManager, "txManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static String entryId(UUID proposalId) {
        return ENTRY_ID_PREFIX + proposalId;
    }

    public Adjustment propose(ApiCredential caller, NewAdjustment request) {
        ensureEnabled();
        Hypothesis hypothesis = Hypothesis.fromCode(request.hypothesis() == null ? "" : request.hypothesis())
                .orElseThrow(() -> invalid("hypothesis must be one of " + hypothesisCodes() + ", was '"
                        + request.hypothesis() + "'"));
        if (request.explanation() == null || request.explanation().isBlank()) {
            throw invalid("explanation must say, in words, why the entry is right");
        }
        Payment payment = visiblePayment(caller, request.paymentReference());

        validatePostings(request.postings(), payment);
        validateEvidence(request.evidence(), request.cites());

        UUID id = UUID.randomUUID();
        store.insertProposal(id, payment.reference().value(), hypothesis, request.postings(), request.evidence(),
                request.cites(), request.explanation().strip(), caller.keyId(), clock.instant());
        return store.find(id).orElseThrow();
    }

    /**
     * Approves: appends the entry the proposal describes and records the decision, in one
     * transaction.
     */
    public Adjustment approve(ApiCredential caller, UUID id, String reason) {
        ensureEnabled();
        Adjustment proposal = visibleProposal(caller, id);
        refuseSelfDecision(caller, proposal);

        LedgerEntry entry;
        try {
            Payment payment = payments.findByReference(ReferenceId.of(proposal.paymentReference()))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ProblemTypes.PAYMENT_NOT_FOUND,
                            "No such payment", "No payment exists for reference " + proposal.paymentReference() + "."));
            entry = entryFor(proposal, validatePostings(proposal.postings(), payment));
        } catch (ApiException refusal) {
            recordRefusal(caller, proposal, refusal);
            throw refusal;
        }

        try {
            tx.executeWithoutResult(status -> {
                // The ledger first: a second approval is refused by its primary key, not by a
                // guard a concurrent caller could race. The decision's own unique constraint
                // then covers a proposal already rejected, and rolls this entry back with it.
                ledger.append(entry);
                store.insertDecision(UUID.randomUUID(), id, true, caller.keyId(), clock.instant(), reason(reason));
            });
        } catch (DuplicateLedgerEntryException | DuplicateKeyException alreadyDecided) {
            throw alreadyDecided(id);
        }
        return store.find(id).orElseThrow();
    }

    /** Rejects: records the decision and writes nothing to the ledger. */
    public Adjustment reject(ApiCredential caller, UUID id, String reason) {
        ensureEnabled();
        Adjustment proposal = visibleProposal(caller, id);
        refuseSelfDecision(caller, proposal);
        try {
            store.insertDecision(UUID.randomUUID(), id, false, caller.keyId(), clock.instant(), reason(reason));
        } catch (DuplicateKeyException alreadyDecided) {
            throw alreadyDecided(id);
        }
        return store.find(id).orElseThrow();
    }

    public Adjustment get(ApiCredential caller, UUID id) {
        ensureEnabled();
        return visibleProposal(caller, id);
    }

    /** The caller's merchant's proposals, newest first; an admin key sees every merchant's. */
    public List<Adjustment> list(ApiCredential caller) {
        ensureEnabled();
        return store.list(caller.admin() ? Optional.empty() : Optional.of(caller.merchantId()));
    }

    // ---- the checks ----

    /**
     * Refuses with {@code adjustment-not-enabled} unless the feature is on. Public so the web
     * layer can ask before it parses anything: a switched-off deployment answers 501 to every
     * request, including one that would otherwise have been a 400 or a 404.
     */
    public void ensureEnabled() {
        if (!policy.enabled()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, ProblemTypes.ADJUSTMENT_NOT_ENABLED,
                    "Adjustments are not enabled on this deployment",
                    "The operator has not switched this feature on (nkap.adjustments.enabled). The same request "
                            + "will be refused until the deployment changes.");
        }
    }

    private static void refuseSelfDecision(ApiCredential caller, Adjustment proposal) {
        if (caller.keyId().equals(proposal.proposedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ProblemTypes.ADJUSTMENT_SELF_APPROVAL,
                    "A proposal cannot be decided by the key that proposed it",
                    "This key proposed adjustment " + proposal.id() + ". A different key must approve or reject it.");
        }
    }

    /** Checks 6, 2, 1 and 3, in that order, and returns the postings as the ledger's own type. */
    private List<Posting> validatePostings(List<AdjustmentPosting> proposed, Payment payment) {
        Currency paymentCurrency = currencyOf(payment);
        if (proposed == null || proposed.size() < 2) {
            throw invalid("an adjustment needs at least two postings: a movement always has two sides");
        }
        List<Posting> postings = new ArrayList<>(proposed.size());
        long sum = 0L;
        long debits = 0L;
        for (AdjustmentPosting posting : proposed) {
            Currency currency = parseCurrency(posting.currency());
            if (currency != paymentCurrency) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.ADJUSTMENT_CURRENCY_MISMATCH,
                        "An adjustment is in the payment's currency, and only that one",
                        "A posting is in " + currency + " and the payment is in " + paymentCurrency + ".");
            }
            AccountId account = parseAccount(posting.account());
            requireAllowed(account, payment.provider().toString(), currency);
            if (posting.amountMinor() == 0L) {
                throw invalid("a posting to " + account + " has a zero amount: it records nothing");
            }
            try {
                sum = Math.addExact(sum, posting.amountMinor());
                if (posting.amountMinor() > 0) {
                    debits = Math.addExact(debits, posting.amountMinor());
                }
            } catch (ArithmeticException overflow) {
                throw invalid("the postings' amounts are too large to add");
            }
            postings.add(new Posting(account, Money.of(posting.amountMinor(), currency)));
        }
        if (sum != 0L) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.ADJUSTMENT_UNBALANCED,
                    "The postings do not balance",
                    "The postings sum to " + sum + " minor units, expected 0. Debits positive, credits negative.");
        }
        if (debits > policy.maxAmountMinor()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.ADJUSTMENT_OVER_CEILING,
                    "The adjustment moves more than this deployment allows",
                    "The postings move " + debits + " minor units; the ceiling is " + policy.maxAmountMinor() + ".");
        }
        return postings;
    }

    /**
     * Suspense, fees and provider float of the payment's own provider, built through
     * {@link AccountId}'s own factories so that the answer is "equal to what the factory makes"
     * rather than a prefix test a crafted segment could slip past. The provider is the
     * payment's, not read out of the account string: a caller cannot name one this deployment
     * never configured, which would show up in balances and could mask a drift. The fee account
     * is named after the provider, as statement reconciliation names it. A merchant's payable is
     * none of the three.
     */
    private static void requireAllowed(AccountId account, String provider, Currency currency) {
        Set<AccountId> allowed = Set.of(
                AccountId.suspense(provider, currency), AccountId.fees(provider, currency),
                AccountId.providerFloat(provider, currency));
        if (!allowed.contains(account)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.ADJUSTMENT_ACCOUNT_NOT_ALLOWED,
                    "An adjustment may only touch the payment's provider's suspense, fee and float accounts",
                    "Account " + account + " is not one of " + allowed.stream().map(AccountId::value).sorted().toList()
                            + ". A merchant's payable account is never allowed, and neither is another provider's.");
        }
    }

    private static void validateEvidence(Map<String, String> evidence, List<String> cites) {
        if (cites == null || cites.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.ADJUSTMENT_EVIDENCE_MISSING,
                    "A proposal must cite its evidence", "cites is empty: name the evidence entries this relies on.");
        }
        for (String cited : cites) {
            String value = evidence == null ? null : evidence.get(cited);
            if (value == null || value.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.ADJUSTMENT_EVIDENCE_MISSING,
                        "A proposal cites evidence it does not carry",
                        "'" + cited + "' is cited and evidence has no value for it.");
            }
        }
    }

    // ---- the rest ----

    private LedgerEntry entryFor(Adjustment proposal, List<Posting> postings) {
        return new LedgerEntry(
                entryId(proposal.id()),
                clock.instant(),
                proposal.paymentReference(),
                "Approved adjustment " + proposal.id() + " (" + proposal.hypothesis().code() + "): "
                        + proposal.explanation(),
                postings);
    }

    private void recordRefusal(ApiCredential caller, Adjustment proposal, ApiException refusal) {
        try {
            store.insertDecision(UUID.randomUUID(), proposal.id(), false, caller.keyId(), clock.instant(),
                    "refused at approval: " + refusal.type() + ": " + refusal.getMessage());
        } catch (DuplicateKeyException alreadyDecided) {
            throw alreadyDecided(proposal.id());
        }
    }

    private Adjustment visibleProposal(ApiCredential caller, UUID id) {
        return store.find(id)
                .filter(found -> caller.admin() || found.merchantId().equals(caller.merchantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ProblemTypes.ADJUSTMENT_NOT_FOUND,
                        "No such adjustment", "No adjustment " + id + " is visible to this key."));
    }

    private Payment visiblePayment(ApiCredential caller, String reference) {
        ReferenceId parsed;
        try {
            parsed = ReferenceId.of(reference == null ? "" : reference);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REFERENCE,
                    "Not a payment reference", "'" + reference + "' is not a well-formed payment reference.");
        }
        return payments.findByReference(parsed)
                .filter(found -> caller.admin() || found.merchantId().equals(caller.merchantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ProblemTypes.PAYMENT_NOT_FOUND,
                        "No such payment", "No payment exists for reference " + reference + "."));
    }

    private static Currency currencyOf(Payment payment) {
        return payment.intent().amount().currency();
    }

    private static Currency parseCurrency(String code) {
        try {
            return Currency.valueOf(code == null ? "" : code);
        } catch (IllegalArgumentException unknown) {
            throw invalid("'" + code + "' is not a currency Nkap knows");
        }
    }

    private static AccountId parseAccount(String value) {
        try {
            return AccountId.of(value == null ? "" : value);
        } catch (IllegalArgumentException malformed) {
            throw invalid("'" + value + "' is not a well-formed account id");
        }
    }

    private static String reason(String reason) {
        return reason == null ? "" : reason.strip();
    }

    private static String hypothesisCodes() {
        return Arrays.stream(Hypothesis.values()).map(Hypothesis::code).toList().toString();
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_REQUEST,
                "The adjustment could not be accepted", detail);
    }

    private static ApiException alreadyDecided(UUID id) {
        return new ApiException(HttpStatus.CONFLICT, ProblemTypes.ADJUSTMENT_ALREADY_DECIDED,
                "This adjustment already has a decision",
                "Adjustment " + id + " was decided before this request; a decision is final.");
    }
}

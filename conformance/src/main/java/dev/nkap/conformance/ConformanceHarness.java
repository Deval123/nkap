package dev.nkap.conformance;

import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.RawCallback;
import java.time.Duration;

/**
 * What a contributor writes to plug their adapter into the conformance kit.
 *
 * <p>It exposes the adapter and the means to put the operator into each condition the kit
 * checks — expressed in terms of what the gateway observes, <strong>never in an operator's
 * own codes</strong>. The kit knows nothing of MTN's {@code RESOURCE_ALREADY_EXIST} or
 * anyone else's; it knows "the operator refused the request".
 *
 * <p>Each condition method is cumulative and takes effect immediately: after
 * {@link #makeSubmitNeverAnswer()} the next {@code submit} through {@link #adapter()} hangs,
 * and stays that way until another method changes it. A fresh harness starts from the happy
 * path: a submission is accepted and the next query succeeds. Declare every condition a test
 * needs <em>before</em> the first call through {@link #adapter()}: a condition set afterwards
 * governs the calls that follow it, not the ones already made.
 *
 * <p><strong>Writing one of these is the contributor's real work, and the thing to judge
 * this kit by.</strong> If it is laborious, the kit is the wrong shape.
 */
public interface ConformanceHarness extends AutoCloseable {

    /** The adapter under test, already configured for the operator the harness drives. */
    ProviderAdapter adapter();

    /**
     * A valid {@link PaymentIntent} the adapter will accept. The kit never requires two calls to
     * differ, so returning an equal intent each time is fine.
     */
    PaymentIntent anIntent();

    /** The next submit call hangs and never returns. The payment may still exist at the operator. */
    void makeSubmitNeverAnswer();

    /** The operator refuses the next submission outright: the request is invalid, no payment is created. */
    void makeSubmitRejected();

    /** A query on a submitted reference reports success. This is also the harness's default. */
    void makeNextQuerySucceed();

    /** Successive queries report success, then failure — a status that flaps. */
    void makeStatusFlap();

    /**
     * The next query answers with a token this adapter has no mapping for — not a specific
     * operator code, an arbitrary one nobody has seen before. The adapter's rule: an answer
     * it cannot map is {@link dev.nkap.core.payment.PaymentState#UNKNOWN}, never
     * {@link dev.nkap.core.payment.PaymentState#FAILED} — describing a token it has never
     * seen would be a guess, not a report.
     */
    void makeStatusUnrecognised();

    /**
     * The operator's credentials expire {@link #credentialLifetime()} from now, mid-flight, while
     * calls are in progress.
     */
    void expireCredentialsMidFlight();

    /**
     * How long the credential installed by {@link #expireCredentialsMidFlight()} stays valid.
     *
     * <p>The kit waits past this before querying again, so an operator whose shortest credential
     * lifetime is a minute is as testable as one whose is two seconds — it only costs the wait.
     * Declare what the harness actually installs; the kit trusts it and nothing else times the
     * test.
     */
    Duration credentialLifetime();

    /**
     * A callback the adapter cannot extract a usable reference from — malformed for this
     * provider's shape, not merely one naming a reference the gateway happens not to
     * recognize. {@link dev.nkap.provider.ProviderAdapter#parseCallback} must reject it as
     * {@link dev.nkap.provider.UntrustedCallbackException}.
     *
     * <p>Issue #39 named two meanings for that exception — unreadable, and readable but
     * naming a reference this gateway never issued — and this rule exercises only the first.
     * The second is not a {@code parseCallback} rule at all: recognizing an unfamiliar but
     * well-formed reference means checking it against payments this gateway holds, which
     * 0008 already forbids an adapter from doing, so {@code CallbackController} does it, not
     * this kit. A third shape a callback can take — naming only the operator's own reference,
     * never one Nkap chose (ADR 0011 §2, issue #149) — has no rule here either, and for the
     * same reason this kit gives for everything it does not test: it is "extracted from a
     * test that already passes against MTN," and MTN's callback always carries what Nkap
     * sent, so no adapter here can supply a harness for it.
     */
    RawCallback anUntrustedCallback();

    /** Returns the operator to a clean state. Narrowed so implementers need not declare {@code throws}. */
    @Override
    void close();
}

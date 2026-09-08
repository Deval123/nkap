package dev.nkap.conformance;

import dev.nkap.provider.PaymentIntent;
import dev.nkap.provider.ProviderAdapter;
import dev.nkap.provider.RawCallback;

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
 * path: a submission is accepted and the next query succeeds.
 *
 * <p><strong>Writing one of these is the contributor's real work, and the thing to judge
 * this kit by.</strong> If it is laborious, the kit is the wrong shape.
 */
public interface ConformanceHarness extends AutoCloseable {

    /** The adapter under test, already configured for the operator the harness drives. */
    ProviderAdapter adapter();

    /** A valid {@link PaymentIntent} the adapter will accept: a fresh one on each call. */
    PaymentIntent anIntent();

    /** The next submit call hangs and never returns. The payment may still exist at the operator. */
    void makeSubmitNeverAnswer();

    /** The operator refuses the next submission outright: the request is invalid, no payment is created. */
    void makeSubmitRejected();

    /** A query on a submitted reference reports success. This is also the harness's default. */
    void makeNextQuerySucceed();

    /** Successive queries report success, then failure — a status that flaps. */
    void makeStatusFlap();

    /** The operator's credentials expire a short time from now, mid-flight, while calls are in progress. */
    void expireCredentialsMidFlight();

    /**
     * A callback the adapter must reject as {@link dev.nkap.provider.UntrustedCallbackException}:
     * it names no payment the gateway issued, or cannot be parsed at all.
     */
    RawCallback anUntrustedCallback();

    /** Returns the operator to a clean state. Narrowed so implementers need not declare {@code throws}. */
    @Override
    void close();
}

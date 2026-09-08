# ADR 0005 — A submission has three outcomes, and the result is sealed

- **Status:** accepted
- **Date:** 2026-09-08

## Context

`provider-api` was designed before any adapter existed. ADR 0004 built the MTN adapter
precisely to test that contract against a real operator before a second adapter arrives and
the shape can no longer change for free. It found two things the contract could not say.

**`submit` could not report a refusal.** `SubmitResult` asserted that a submission may only
report `SUBMITTED` or `PENDING`, "because a definitive outcome comes from `query()` or a
callback". MTN's `400` disproves that sentence: the request was invalid, no payment exists,
and none will under this reference. That is a definitive outcome, available at submission.
It is not an acknowledgement, and it is not `ProviderUnavailableException`, which means "I
do not know". The adapter had to smuggle it out as an unchecked exception the gateway could
not tell apart from a bug.

**`ProviderStatus` had nowhere for the provider's transaction id.** MTN returns a
`financialTransactionId` once a payment settles, and the reconciler matches it against the
operator's statement. It only fit in `rawResponse`, which would make the reconciler parse
strings.

## Decision

**`SubmitResult` becomes a sealed interface with two records.**

```java
public sealed interface SubmitResult {
    record Acknowledged(PaymentState state, String providerReference, String rawResponse)
            implements SubmitResult { /* state ∈ {SUBMITTED, PENDING} */ }
    record Rejected(String providerCode, String reason, String rawResponse)
            implements SubmitResult {}
}
```

`Rejected` carries no `PaymentState`. A rejection always means `FAILED`; letting an adapter
choose the state would allow nonsense. The adapter reports the operator's `code` and
`message`; the gateway records the transition `CREATED → FAILED`. The
`SubmitResult.acknowledged(...)` factory stays, so call sites that only ever acknowledge do
not change.

**`ProviderStatus` gains `providerTransactionId`**, mirroring `SubmitResult`'s
`providerReference`: normalised to `""` like the other optional strings, and exposed as
`Optional<String> transactionId()` to match `fee()`. It is absent while a payment is
pending.

## Rationale

**Why a sealed result and not a checked `ProviderRejectedException`.** This is the question
a reviewer asks, so the answer is written down.

A rejection is not a programming fault. It is an ordinary state transition — `CREATED →
FAILED` — carrying data the gateway records and shows the merchant. An outcome that must
become data should not travel as an exception: every caller would `catch` it only to turn
it back into a value, which is the standard sign the exception is in the wrong place.
Exceptions are for the call that *could not* produce a result — a timeout, a dropped
connection, a 5xx — and `provider-api` already has `ProviderUnavailableException` for
exactly that, and only that.

Sealing makes the compiler force every consumer to handle both cases. A new `switch` over a
`SubmitResult` that forgets `Rejected` does not compile. That is the same mechanism, and
the same house style, as `IdempotentOutcome` in `core`, which is a sealed interface with
four cases for the same reason.

**Why `Rejected` has no state.** The set of legal outcomes is small and the core owns the
state machine. An adapter that could return `Rejected(FAILED)` could also return
`Rejected(SUCCEEDED)`; removing the field removes the question.

## Consequences

- Every future consumer of `submit()` — the server module first — must branch on
  `Acknowledged` vs `Rejected`. The compiler enforces it.
- The MTN adapter drops its unchecked `MtnRequestRejected` and returns
  `SubmitResult.Rejected` from a `400`, carrying MTN's `code` and `message`.
- The adapter populates `providerTransactionId` from `financialTransactionId` when a
  payment has settled, and leaves it empty while pending.
- No change to `core`. `ProviderAdapter.submit`'s signature is unchanged — the return type
  was already `SubmitResult`.
- The lenient "any 409 is already-submitted" handling in the adapter is left as it is,
  waiting on the simulator returning MTN-shaped error bodies (issue #26). Narrowing it now
  would make the adapter's tests pass against a fiction.

## Alternatives rejected

**A checked `ProviderRejectedException`.** Rejected above: an outcome that becomes data
should not be an exception, and callers would only catch-and-convert it.

**Keep `SubmitResult` a record and add a nullable `rejectionCode`.** A nullable field the
compiler does not force anyone to check is how the "unknown mapped to FAILED" class of bug
gets in. Sealing is the point.

**Let `Rejected` carry a `PaymentState`.** Allows an adapter to disagree with the core
about what a rejection means. The core decides; the adapter reports.

# ADR 0014 — A lost submission must be resolvable, not necessarily queryable

- **Status:** accepted
- **Date:** 2026-09-21

## Context

`CONTRIBUTING.md`'s second ground rule is that a timeout is not a failure: an unanswered call
becomes `UNKNOWN`, and something resolves it later without a person and without a guess. The
conformance kit encodes that rule like this:

```java
harness.makeSubmitNeverAnswer();
assertThrows(ProviderUnavailableException.class, () -> adapter.submit(harness.anIntent(), reference));
assertSame(PaymentState.SUCCEEDED, adapter.query(QuerySubject.of(reference), operationUnderTest()).state());
```

That is not the ground rule. It is **one mechanism** for delivering it — MTN's, where the
reference Nkap chose is also the key the operator answers about, so polling always works.

[`docs/providers/m-pesa.md`](../providers/m-pesa.md), written from observation against
Safaricom's sandbox on 2026-09-18, describes an operator where it does not. For STK Push, when
the submit response is lost: Nkap holds no `CheckoutRequestID`, which is the only key
`stkpushquery` accepts and M-Pesa's only synchronous query; `Transaction Status` searches on
`TransactionID` and `OriginalConversationID`, neither of which a caller can choose, and is
asynchronous besides. No amount of polling can ever answer.

But the same page records that **the callback does arrive**, and that the callback path Nkap
supplied arrived unchanged. It cannot be attributed today only because nothing in it names a
value Nkap chose — which is what issue #185 is about, and which, as that issue's costing
shows, is a change to `PublicBaseUrl` and `CallbackController` and **not** to `provider-api`.

So the payment is resolvable without a human. Not by querying. The kit wrote down MTN's how and
has been excluding operators for failing to implement it.

### The second rule, and why it was asserting the wrong thing

`a_reference_submitted_twice_is_idempotent` required the *operator* to deduplicate a reused
reference. Reading the call graph rather than assuming it:

- `adapter.submit` is called from exactly one place, `PaymentService.callOperator`, once per
  payment. `RefundService` goes through the same method. **The reconciler never submits.**
- In the MTN adapters, `send()` retries nothing: a timeout becomes `ProviderUnavailableException`
  immediately. The only repeat is `sendAuthenticated`'s on a `401`, and a `401` precedes
  processing, so it cannot produce a second payment at any operator.
- A merchant replaying `POST /payments` under the same `Idempotency-Key` gets the stored
  payment back, with no second submission.

One reference produces at most one `submit` call, which produces at most one payment at the
operator — for any operator, idempotent or not. **Nkap does not depend on operator-side
idempotency.** The rule asserted a property of MTN.

What would genuinely hurt is different and is tested nowhere: an **adapter** that resends a
submission which may already have been processed. Submitting twice from the kit is not that,
and issue #186's fix — comparing the two submissions' provider references — catches a duplicate
only when the operator hands back two different ones.

## Decision

**The conformance requirement is that a lost submission is resolvable without a human, by at
least one mechanism the adapter declares.** Querying is one such mechanism, not the definition.

### 1. An adapter declares how it resolves

```java
enum Resolution { QUERY, CALLBACK }

Set<Resolution> resolves();
```

In `provider-api`, and **without a default implementation**. A default of `QUERY` would let an
adapter that cannot query claim it can by saying nothing, and this project has already recorded
why that shape is wrong: *"a nullable field the compiler does not force anyone to check is how
the 'unknown mapped to FAILED' class of bug gets in"* (ADR 0005). The compiler asks every
adapter author the question. The 2.0.0 line is already open, so the break costs nothing it was
not going to cost anyway.

`resolves()` must be non-empty. An adapter that can neither be queried nor deliver an
attributable callback cannot resolve a lost submission at all, and this gateway does not accept
one: the alternative is a payment that is only ever settled by a person reading an operator's
web portal, which is not a gateway.

### 2. The kit asserts the declaration, and holds the adapter to it

The rule above becomes: after a lost submission, the payment resolves by **the mechanism the
adapter declared**. For `QUERY` the existing assertion is unchanged. For `CALLBACK` the kit
drives the operator's callback and asserts the event is attributable to the reference the
gateway chose.

The two are **not** equivalent, and the kit must not read as though they were. A callback can be
lost; a query can be repeated. An adapter declaring `CALLBACK` alone is weaker than one
declaring both, and that difference is the subject of the next decision rather than a footnote.

### 3. The gateway acts on the declaration

An adapter that does not declare `QUERY` cannot have a lost submission resolved by polling. The
reconciler must not spend the whole escalation window discovering that. Such a payment is
escalated immediately, and `/actuator/escalatedPayments` says why.

Today the code would poll on a backoff until the window expired, for an answer that cannot
exist. That is waste dressed as diligence, and it is the one part of this decision that changes
behaviour rather than contracts. The precise trigger — which payments, at what moment — belongs
to the implementing slice, which must state it rather than infer it.

### 4. Operator-side idempotency stops being a requirement

It is replaced by the rule that matters: **an adapter must not resend a submission that may
already have been processed.** Retrying a call the operator rejected before processing — a
`401`, an authentication failure — is not resending and stays required.

The kit cannot assert this today: proving that the operator received exactly one request needs
an observation hook `ConformanceHarness` does not have, which is issue #175. Until it exists,
the kit keeps issue #186's reference comparison as a partial catch, and says in its own javadoc
what it does not establish.

**Addendum, 2026-09-23: the condition above is met.** Issue #175 gave the kit
`ConformanceHarness.submissionsReceived()`, counted at the operator. The partial catch is
replaced by the assertion this decision asked for: each rule submits once, and the operator must
have processed exactly one submission for it.

### 5. `README.md` stops promising M-Pesa unconditionally

The roadmap names M-Pesa among the operators to come. Under this ADR an M-Pesa adapter becomes
possible only once #185 lands, because `CALLBACK` is the only mechanism it can declare. The
roadmap says that, or stops naming it.

## Rationale

**Why this is not bending a rule to admit an operator.** The strongest argument for it owes
nothing to M-Pesa. ADR 0008's amendment recorded a circularity it deliberately left unsolved:
nothing can be added to `parseCallback`'s signature that would not require the answer
`parseCallback` is being asked to produce. A reference carried in the callback's own URL breaks
that from outside the signature — the gateway knows which payment a callback concerns before
parsing it, because it chose the address. `parseCallback` goes back to translating a payload
and stops being asked to identify a payment it cannot identify. That is worth having with one
adapter.

**Why the declaration is a method rather than a `Capability.Feature`.** A `Feature` is something
the gateway asks an adapter to do, and every member has a method to call for it
(`CapabilityCoverageTest`). How an adapter resolves is not something anyone calls; it is a fact
about the adapter. Putting it in `Capability` would make the two enums mean two things again,
which `Capability`'s own javadoc spent a slice separating.

**Why not simply let the reconciler try and fail.** Because it cannot fail — it gets `UNKNOWN`
from a query it cannot form, which is indistinguishable from an operator that is merely slow,
which is exactly the state the whole design exists to keep meaningful. A gateway that polls
where polling is impossible has turned "I do not know yet" into "I will never know", silently.

## Consequences

- `provider-api` gains `Resolution` and `ProviderAdapter.resolves()`, abstract. Every adapter —
  there is one — implements it. Both MTN products declare `QUERY` and `CALLBACK`.
- The conformance kit's lost-submission rule branches on the declaration, and gains a
  `CALLBACK` path it cannot exercise until an adapter declares it. That gap is stated, not
  hidden.
- `a_reference_submitted_twice_is_idempotent` keeps #186's comparison and loses its claim to be
  about operator idempotency. Its name and javadoc say what it checks.
- The reconciler skips adapters that cannot be queried and escalates instead. This is the only
  runtime behaviour change in this ADR.
- Issue #175 becomes load-bearing rather than nice to have: it is what will let the kit assert
  the no-resend rule, and what would let the `CALLBACK` path be asserted properly.
- Issue #185 becomes a prerequisite for any M-Pesa adapter, and is worth landing regardless for
  the `parseCallback` reason above.
- No migration, no change to `core`, no change to the HTTP API.

## Alternatives rejected

**Keep queryability as the requirement.** Defensible, and it has the better of one argument: a
callback can be lost, a query can be repeated, so an adapter that only receives callbacks is
genuinely weaker. But the rule as written excludes an operator for failing to implement MTN's
mechanism rather than for failing to deliver the property, and the price is the largest mobile
money market in East Africa, closed by a sentence written when this project had one adapter.
This ADR keeps the objection alive in decision 2 rather than dismissing it: the mechanisms are
declared separately and are not treated as equal.

**Let adapters declare the degradation and require nothing.** That is decision 3 without
decisions 1 and 2 — it admits an operator that can never resolve a lost submission at all, and
pushes the honesty onto whoever reads the escalation queue. It is absorbed here as one of the
declarations rather than adopted as the rule.

**Keep the idempotency requirement and let M-Pesa fail on it.** It asserts a property this
gateway never relies on, established above by reading the call graph rather than assuming it.
Keeping a requirement because it happens to be true of the only operator implemented is how the
queryability rule got written in the first place.

**Wait for an M-Pesa adapter to settle the shape.** Rejected twice in ADR 0008 and once in ADR
0013. The evidence is gathered, it is in a provider page, and an adapter written first would be
written against whichever answer suited it.

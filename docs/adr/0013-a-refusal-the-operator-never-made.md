# ADR 0013 — A refusal the operator never made

- **Status:** accepted
- **Date:** 2026-09-20

## Context

Issue #164 asked whether `submit` needs a way to refuse without calling the operator, and
said the question was *"worth deciding against a second adapter's real needs"*. Both halves
were wrong. `submit` already has a way to refuse; what it has no way to do is refuse
**alone**. And the evidence needed to decide was never about a second operator — it has been
in this repository, shipped, since the first one.

`SubmitResult.Rejected` is documented as *"the provider refused it outright; no payment
exists"*:

```java
record Rejected(String providerCode, String reason, String rawResponse)
```

All three components are properties of **the operator's answer**: its machine-readable code,
its message, its body verbatim. An adapter refusing on its own terms has none of the three.
It would have to invent a `providerCode` and pass empty strings for the rest. That is ADR
0008's rule read from the answering side: the contract can express one thing, and an adapter
forced to use it for another has to fabricate.

### What happens today

Both MTN adapters guard `submit` before building any request:

```java
if (intent.operation() != Capability.Operation.COLLECT) {
    throw new IllegalArgumentException(...);
}
if (intent.amount().currency() != profile.currency()) {
    throw new IllegalArgumentException("payment is in " + intent.amount().currency()
            + " but this MTN profile settles in " + profile.currency());
}
```

Neither has touched the network. `PaymentService.callOperator` catches the
`RuntimeException` and `applyOutcome` records `CREATED → UNKNOWN`, cause `SUBMIT_RESPONSE`,
with the exception's own `toString` in the note. Three consequences, each worse than the one
before it:

1. **`UNKNOWN` for a payment that provably never left the process.** That state means the
   operator may hold the request. Here the operator was never asked.
2. **The reconciler then chases it.** It queries MTN about a reference MTN has never seen,
   gets `404 RESOURCE_NOT_FOUND`, maps it to `UNKNOWN`, backs off, and eventually escalates
   to a human. The machinery this project exists to get right, spent on a payment whose
   outcome was certain before the call.
3. **The row says `SUBMIT_RESPONSE`.** `payment_transition` is append-only, enforced by
   `payment_transition_append_only` since `V1__initial_schema.sql`. That is a permanent,
   uncorrectable row in a payments audit trail asserting what the operator's response said,
   when there was no response.

And the comment above that branch — *"A defect on our side, after the point where the
operator may already have the request"* — is true for a serialisation bug and false for both
guards, which run before the request is built. A true-looking sentence beside correct code.

So this is not a latent trap waiting on a second adapter. It is active, and one adapter was
enough to find it.

### There are two kinds of local refusal, not one

- **What the gateway can determine from what it already holds.** The declared
  `Capability.Operation` is the gateway's own routing fact; it should never hand a `DISBURSE`
  intent to an adapter that declares only `COLLECT`.
- **What only the adapter holds a second opinion on.** `PaymentController` already refuses a
  currency mismatch at the API edge, from `AdapterRegistry.settlementCurrency`. The adapter's
  guard compares against the profile it actually carries, so it fires precisely when
  configuration and profile **disagree** — the one case the gateway cannot pre-compute,
  because pre-computing it means believing the configuration that is wrong.

One shape cannot carry both, which is why this ADR makes two changes rather than one.

## Decision

**No `payment_transition` row may claim an operator spoke when none did.**

That is the rule. Two changes implement it.

### 1. The gateway refuses what it can determine itself, and does not call the adapter

`PaymentTransition.Cause` gains a fifth member:

```java
public enum Cause { SUBMIT_RESPONSE, QUERY, CALLBACK, RECONCILER, GATEWAY }
```

The four existing members each name **the source of the information**. `GATEWAY` names the
remaining one: this gateway, deciding without asking.

`PaymentService` checks `intent.operation()` against the adapter's declared capabilities
before `callOperator`. On a mismatch it records `CREATED → FAILED`, cause `GATEWAY`, with an
empty `operator_code` and an empty `raw_response` that are empty *truthfully*, and releases
the refund reservation exactly as the `Rejected` path does (issue #84). The adapter is never
called.

> If the gateway can know it from what it already holds, the adapter is not asked.

### 2. `SubmitResult` gains a fourth member for what only the adapter knows

```java
/**
 * The adapter refused before calling the operator. No request was sent, nothing can
 * have reached the provider, and there is no response to carry — which is why this
 * record has neither a provider code nor a raw body.
 */
record NotAttempted(String reason) implements SubmitResult { … }
```

`PaymentService` records `CREATED → FAILED`, cause `GATEWAY`, `reason` in the note, raw body
empty. Same cause as change 1, and deliberately so: whether the gateway decided alone or on
the adapter's word, no operator spoke, and that is the only thing the cause is there to say.

**This breaks `provider-api`, so it takes a major release — and this repository publishes
one version for the whole reactor. The next release is Nkap 2.0.0.**

## Rationale

**Why accept a major release, and why now.** ADR 0008's amendment said the window for
changing this contract closes at `v1.0.0`, "because after the tag every signature change
breaks every external adapter". The tag exists; `provider-api` is published at 1.0.0. So this
is the first change made on the far side of that line, and it is worth being exact about what
it actually breaks.

Adding a permitted subtype to a sealed interface does not break an **implementer**. An
adapter returns `Acknowledged` or `Rejected` and keeps compiling, untouched. What breaks is
an exhaustive `switch` — a **consumer** — and `provider-api` was published for adapter
authors, not for authors of competing gateways. The only exhaustive switch in existence today
is `PaymentService.applyOutcome`, in this repository.

So the real cost is not borne by anyone this contract was published for, and the formal cost
is a whole major version of the project — every POM in the reactor, both images, the chart,
the README. That is the price, stated plainly, and it is worth paying now for one reason: it
only ever rises. Today 2.0.0 lands on a gateway that has never handled real money and has no
third-party adapter to migrate. Every month of that being false makes the same change more
expensive and its avoidance more tempting.

**Why not an exception.** ADR 0005 answered this for `Rejected` and the answer holds: an
outcome that becomes data should not be an exception, and callers would only
catch-and-convert. This outcome is emphatically data — it produces a terminal state, a
merchant notification, and a permanent ledger row.

**Why not infer it from an empty `rawResponse`.** Because an operator refusal that happened
to carry an empty body would be silently reclassified as a local one. A rule that holds only
while a set happens to be non-empty is the defect class this project has now shipped and
caught twice — `check-dco.sh`'s empty commit range, and the unreadable `409` body in issues
#28 and #171. Inference is what produced the problem this ADR closes; more inference is not
the remedy.

**Why `FAILED` and not a new state.** The payment is terminal and no money moved. `FAILED` is
exactly right. What was wrong was never the state — it was the claim about who decided it.

## Consequences

- The next release is **2.0.0** — one version for the whole reactor, per `CONTRIBUTING.md`.
  The release notes say what breaks and, more usefully, what does not: an existing adapter
  needs no change, because it implements the contract rather than switching over it.
- `PaymentService.applyOutcome` gains a `NotAttempted` case. The compiler finds it; that is
  what the sealing is for.
- `callOperator`'s `catch (RuntimeException)` stays, and its comment is corrected. It is the
  defect path — a bug on our side, possibly after the request went out — and `UNKNOWN` is
  still the right record for it. The two guards no longer reach it.
- Both MTN adapters return `NotAttempted` for the currency-profile disagreement. They keep
  throwing `IllegalArgumentException` for the operation mismatch, and that is deliberate:
  after change 1 the gateway never routes such an intent, so a caller that reaches it has
  ignored `capabilities()`. That is a programming error, not a payment outcome.
- The conformance kit gains a rule: an intent in a currency the adapter's profile does not
  settle produces `NotAttempted` and no call to the operator. The harness can drive it, so it
  is a rule and not a wish.
- `cause` is `text NOT NULL` and nothing switches exhaustively over the enum, so `GATEWAY`
  needs **no migration**. It does become a value the payment-history response can carry;
  `docs/openapi.yaml` says so, and says membership only grows.
- `CHANGELOG.md` carries a breaking-change entry for the 2.0.0 line.
- The HTTP status code for a gateway refusal is left as `201` — the same status a payment the
  operator answered gets — and `docs/openapi.yaml`'s prose now says so plainly rather than
  implying the operator was asked. Whether that status is the right one, or whether a refusal
  this gateway can determine from its own routing table belongs at the API edge as a `4xx`
  instead of a created-then-failed payment, is not decided here: this ADR's scope is the
  ledger row, not the HTTP contract. See issue #176.

## Alternatives rejected

**Static factories on `Rejected` — `byOperator(...)` and `locally(...)` — with
`operatorAnswered()` derived from whether a raw body is present.** Ships in a minor release
and is the cheapest of the three. It is also the one to be most careful of: the distinction
would be inferred rather than stated, wrong for any operator refusal with an empty body, and
the ledger row would still say `SUBMIT_RESPONSE`. It renames the problem and looks solved,
which makes it the hardest of the three to undo later.

**`Cause.GATEWAY` alone, with no contract change.** It covers only what the gateway can
pre-compute. The one case actually present in the tree today — configuration and profile
disagreeing on the settlement currency — is exactly what it cannot cover, because
pre-computing it means trusting the configuration that is wrong.

**A nullable `locallyRefused` flag on `Rejected`.** ADR 0005 rejected this shape for this
reason: a nullable field the compiler does not force anyone to check is how the
"unknown mapped to FAILED" class of bug gets in. Sealing is the point.

**Map the caught `RuntimeException` to `FAILED` in `PaymentService` and change nothing else.**
It would make every defect on our side a failed payment, including ones thrown after the
request reached the operator. That is the one conclusion this project forbids.

**Ship it as a minor release, on the grounds that no external adapter actually breaks.**
True, and not the rule. `CONTRIBUTING.md` says that from 1.0.0, breaking `provider-api` takes
a major release, "the discipline that makes third-party adapters possible". A discipline that
acquires an exception the first time it costs its author something is not one. The argument
for the exception is also self-serving in a way that should be suspicious: it is made by the
only party the exception benefits, about a population of external consumers that is empty
only because nobody has arrived yet.

**Wait for a second adapter, as issue #164 proposed.** ADR 0008 rejected this reasoning
twice. Here it is worse than unnecessary: the evidence was in this repository the whole time,
and waiting means knowingly continuing to write permanent audit rows that say the operator
spoke when it never did.

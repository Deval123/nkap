# ADR 0011 — Query already has the provider's own reference; a callback cannot produce one

- **Status:** accepted
- **Date:** 2026-09-19

## Context

This is ADR 0008 happening a third time, and — by this repository's own precedent — that is
why it belongs beside 0008 rather than inside it as an amendment.

0008's own first line already named the pattern once: "This is ADR 0005 happening a second
time, and it belongs beside it for that reason." 0005 found that a contract written before any
adapter existed could not report a refusal. 0008 found, twice, that `query` could not reach an
identifier only the operator held — once implementing MTN's two products, once reading Orange
Money's documentation with no Orange adapter to test it against. Neither occurrence was folded
into the other as an amendment, because the earlier decision was not wrong for the case it
addressed; the same class of mistake had simply recurred in a shape the original text did not
anticipate. `CONTRIBUTING.md`'s own rule agrees: amend when later work narrows or corrects a
decision, write beside it when the mistake itself recurs. **This is the third occurrence.** The
project has now watched one assumption — that an adapter can always do its job with what it is
handed — fail three times, each time found by the next operator examined, in a method written
before that operator existed.

M-Pesa is also the first of these three findings backed by a real sandbox rather than
documentation or a third-party client (`docs/providers/m-pesa.md`), which is why its evidence
carries more weight below than Orange's own, admittedly weaker, assumed evidence.

0008's boundary sentence still does all the work:

> An adapter is handed everything it needs to translate. If an adapter has to look something
> up — read gateway state, consult a payment it cannot see — to do its job, the contract is
> missing an argument.

Two methods are checked against it here, with two different outcomes, plus one gap this ADR
declines to fold into the same story.

## Decision

### 1. `query` already has what M-Pesa needs

M-Pesa's `stkpushquery` answers only against the `CheckoutRequestID` Safaricom generates and
returns, synchronously, in the `processrequest` response — a shape 0008's own amendment (issue
#96) already anticipated, on assumed evidence from Orange's `pay_token`: `query`'s
`QuerySubject` carries `providerReference`, populated from exactly what
`SubmitResult.Acknowledged.providerReference()` returned at submission and the gateway
persisted.

`CheckoutRequestID` fits `providerReference`'s own description exactly — "the provider's own id
for the request... at submission." **Nothing about `query`'s signature needs to change for
M-Pesa.** What M-Pesa adds is not a new requirement but the first *observed*, rather than
assumed, confirmation that 0008's amendment was the right shape, not merely a convenience for a
documentation-only operator. That is worth recording precisely because it settles, on stronger
evidence, a question 0008 had only assumed evidence for.

The form this confirms, for whoever writes the next adapter: **the provider's own submit-time
identifier lives on the carrier record `query` already takes, not as a positional parameter
added one operator at a time.** A fourth operator needing a further fact at submission time
extends that record; it does not touch `ProviderAdapter`'s method signature. `QuerySubject`'s
own javadoc already says why this shape was chosen — a record rather than a growing parameter
list, deliberately, because after `v1.0.0` every change to `ProviderAdapter` itself is breaking
— and this ADR treats that reasoning as settled rather than reopening it.

### 2. `parseCallback` does not — and this is the gap that actually blocks an M-Pesa adapter

M-Pesa's callback carries `MerchantRequestID`, `CheckoutRequestID`, `ResultCode`, `ResultDesc`
— and, `m-pesa.md` is explicit, "nothing the caller chose: no `AccountReference`, under that
name or any other." That is not a property of the lost-response case alone; it is a property of
every M-Pesa callback, because `AccountReference` — "the only field the caller chooses" — is
never echoed, not at submission and not at callback.

`ProviderAdapter.parseCallback(RawCallback)` returns a `CallbackEvent` whose `reference` is a
mandatory `ReferenceId` — Nkap's own idempotency key — and the adapter alone constructs it, from
the raw callback and nothing else. MTN's implementation reads `referenceId`/`externalId`
straight out of the callback body, because MTN echoes back exactly what Nkap sent. **An M-Pesa
`parseCallback` has no field to read that value from, ever — not for a payment whose submit
response was lost, and not for one that settled normally.** This is 0008's rule broken a third
time, on a different method than the first two: the contract hands the adapter a raw callback
and asks it to produce Nkap's own reference, when for this operator that reference exists
nowhere in what the adapter is given. Reaching for it any other way — a lookup keyed by the
operator's own identifier — is reaching into gateway state, which 0008 already forbids an
adapter from doing, for the same reasons it forbade it the first time.

**The direction 0008 already set answers this too: hand back what the adapter actually has, and
let whichever side already holds the persisted association — the gateway, which records
`providerReference` at submission exactly for `query`'s benefit — complete the attribution.**
Concretely, and without deciding the exact shape (naming that is for the implementing pull
request, not this ADR): a callback carrier able to report the provider's own reference for a
payment it cannot itself resolve to a `ReferenceId`, so that the gateway — already holding the
persisted `reference ↔ providerReference` association it built for `query` — can complete the
match itself, the same lookup it already performs, from the same table, in the other direction.

This is not free the way `query`'s fix was. `QuerySubject` is constructed only by the gateway
(`SettlementService`) and merely read by adapters, which is exactly why growing it cost nothing
at every existing adapter's call site. The callback carrier is constructed by the adapter
itself — every `parseCallback` implementation, MTN's included, builds it directly. Growing it
without breaking every existing adapter's build needs the same discipline ADR 0005 used for
`SubmitResult.acknowledged(...)`: whatever shape is chosen, the existing construction must keep
compiling unchanged. That is a real cost, and it is the reason this decision names the
direction and the constraint, not a class name.

**On `UntrustedCallbackException`.** Issue #39 already separated "cannot be read" from
"readable, but not ours." M-Pesa's callback for a submission whose response never arrived is
neither: it is readable, and it may well be ours — probably our own payment, the one whose
submit response was lost, in the words that raised this. Answering `202` and discarding it —
the only path the gateway has today for a reference it does not recognize — throws away the
one channel left to learn that payment's outcome at all, since re-submission is not idempotent
for this operator and the query it would otherwise make demands exactly the identifier that was
lost.

**This is a third case, not a symptom of `query` being unsolved** — `query` is not unsolved,
per §1 — but it is a direct symptom of §2 remaining open, and it does not exist independently
of it. Until a callback carrier can name a provider reference the gateway itself has never
persisted, every M-Pesa callback is indistinguishable from this case, attributable or not: the
gap in §2 is what makes "readable but not (yet) attributable" a case at all, rather than a
corner of "not ours." Once §2's carrier exists, this narrows to what it should be: a callback
whose provider reference the gateway's own persisted state has genuinely never recorded, because
nothing survived to persist. That is the shape a **new subclass** of `UntrustedCallbackException`
would carry — additive, per the constraint below, never a sibling type replacing it — decided
here as a direction, not implemented here, because the mechanism that would make it actionable
(§2) is not implemented here either.

**Recorded because it changes what the type's two current meanings actually mean, not because
it changes this decision:** neither MTN's callback nor M-Pesa's carries a signature or any other
credential (issue #39's comment, observed 2026-09-16 for MTN and 2026-09-18 for M-Pesa). Two
real operators, zero authentication checks performed by any adapter here. `UntrustedCallbackException`'s
name describes a check that has not been observed to happen once. That is not grounds to rename
a published type for a naming preference — this ADR does not do that — but it is grounds to
read both of its existing cases, and the one this section adds, as being about whether a
callback can be **read and recognized**, never about whether it can be **authenticated**, until
an operator that offers something to check is observed.

### 3. What `submit` may hand back for a redirect-style operator — deferred

`orange-money.md`'s finding stands: `SubmitResult.Acknowledged` has nowhere to carry a payment
URL, QR code or deeplink, and Orange's documented flow does not complete without one. This ADR
does not decide it, for the reason `orange-money.md` itself gives and nothing here changes: it
is **assumed**, not observed — nobody on this project holds Orange merchant status, and
M-Pesa's own observed evidence, the stronger source everywhere else in this document, says
nothing about redirect-style operators at all. It is also a different kind of gap than §1 and
§2: 0008's rule is about an adapter being asked to look something up it cannot see, and this is
not that — it is `SubmitResult` having no field for something the operator hands back and the
merchant must be shown, which is a question of what Nkap carries at all, not of what an adapter
is translating. Folding it into this ADR's spine would overstate what the evidence for it
actually is.

Deferred explicitly, not silently: whether Nkap carries redirect-style operators at all is a
product-scope decision, not a contract-shape one, and it should be made — by whoever is
actually building toward one — with evidence as real as the evidence §1 and §2 rest on here.

## Rationale

**Why decide §1 and §2 now, with no M-Pesa adapter planned.** 0008's own "Alternatives
rejected" already rejected waiting for a second operator to confirm a shape found by reading,
and did so on weaker evidence than this ADR has — Orange's documentation, not a sandbox.
M-Pesa's evidence is observed. Waiting for an implementation nobody plans to confirm what a live
sandbox already confirmed is the same reasoning rejected twice already, offered a third time
against better evidence than either of the first two.

**Why §1 costs nothing and §2 does.** The asymmetry is not about which method matters more; it
is about who constructs the record `ProviderAdapter` passes across the boundary. `QuerySubject`
and `RawCallback` are both built by the gateway and only read by adapters — growing either is
free at every third-party call site, which is exactly the property `QuerySubject`'s own javadoc
names as the reason it is a record and not a parameter list. `CallbackEvent`, like
`ProviderStatus`, is built by the adapter — growing either changes every adapter's own
construction call, MTN's included. A future contributor extending this contract should reach
for the cheap side of that asymmetry — grow what the gateway hands the adapter — before reaching
for the expensive side, and should expect to pay ADR 0005's compatibility tax when the expensive
side is the only option.

**Why not treat the M-Pesa case as an ordinary unknown reference.** `CallbackController`
already answers `202` for a reference no payment matches, because a `404` there would let a
public endpoint be probed for which references exist. That reasoning defends against an
attacker guessing references; it says nothing about a payment the gateway itself lost its own
paperwork on. Collapsing the two hides the second behind a security justification meant for the
first.

## Consequences

- No change to `ProviderAdapter`, `QuerySubject`, `SubmitResult`, `CallbackEvent`, or any
  adapter's code ships with this ADR — it decides shape and direction, not an implementation,
  which is this ADR's own scope.
- Anyone who has written an adapter against `1.0.0` or `1.1.0` keeps compiling and keeps
  working: nothing here changes a signature or a record's canonical constructor.
- When §2 is implemented, whatever shape the callback carrier takes must keep every existing
  adapter's current construction of it compiling unchanged — the same discipline
  `SubmitResult.acknowledged(...)` (ADR 0005) already set as precedent for exactly this
  situation.
- That implementation, done that way, is **additive** — it does not need a major release.
  `CONTRIBUTING.md` owns exactly when it ships and under what version; this ADR settles only
  that it is the additive kind of change, not the breaking kind, the same distinction issue
  #39's comment already drew for a new exception subclass.
- §3 remains open. No `SubmitResult` change, no claim about which countries or operators Nkap
  will ever carry, and no issue filed by this ADR — that decision belongs to whoever next holds
  real Orange evidence, or decides the project does not need it.
- No claim that an M-Pesa or Orange adapter will be written. Both remain hypothetical, exactly
  as `docs/providers/m-pesa.md` and `docs/providers/orange-money.md` already state.

## Alternatives rejected

**Give `parseCallback` a lookup, the way disbursements' workaround gave `query` one.** Rejected
in 0008 and rejected again here for the same reason: the caller does not have the answer this
time either — 0008's original workaround at least had the payment in hand; a gateway resolving
a callback does not yet know which payment it has. But a lookup performed by the adapter,
reaching into gateway state to answer the question, is the exact inversion of the module
boundary 0008 exists to prevent, whichever side needs the answer.

**Wait for an M-Pesa or Orange contributor to confirm the shape before deciding anything.** The
precise reasoning 0008 rejected twice, against evidence weaker than a live sandbox. Rejected a
third time here, on better evidence than either prior instance had.

**Fold §3 into the same decision as §1 and §2 because all three came from the same review.**
Rejected: §3 is not an instance of 0008's rule, its evidence is assumed rather than observed,
and deciding it requires a scope judgment this ADR's narrower spine should not carry.

**Rename `UntrustedCallbackException`.** The observation that neither operator authenticates
anything is real, but a published type does not get renamed for a naming preference, and issue
#39's own comment already fixed the only path available post-`1.0.0` as a subclass, not a
replacement. Recorded here as a reading of the existing name, not a reason to change it.
